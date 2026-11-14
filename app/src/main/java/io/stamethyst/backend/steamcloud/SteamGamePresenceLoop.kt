package io.stamethyst.backend.steamcloud

import java.io.IOException
import java.util.concurrent.CancellationException
import top.apricityx.workshop.steam.protocol.SteamSessionRevokedException
import top.apricityx.workshop.steam.protocol.isSteamCmCredentialRejection

/** The service's recovery loop, with platform IO supplied at its boundary for offline tests. */
internal class SteamGamePresenceLoop(
    private val owner: SteamGamePresenceOwnership.Owner,
    private val readAuth: (expectedEpoch: String?) -> SteamCloudAuthStore.AuthMaterialRead,
    private val readPresence: () -> Map<String, String>?,
    private val createClient: (SteamCloudAuthStore.SavedAuthMaterial) -> Client,
    private val shouldContinue: () -> Boolean,
    private val nowMs: () -> Long,
    private val awaitChange: () -> Unit,
    private val awaitRetry: (SteamGamePresenceRetryPolicy.RetryWindow) -> Unit,
    private val onRestored: () -> Unit,
    private val onRetry: (Exception) -> Unit,
) {
    interface Client {
        val sessionIdentity: Any?
        fun logOn(auth: SteamCloudAuthStore.SavedAuthMaterial)
        fun setPersonaOnline()
        fun setGamePlayedAppId(appId: Long)
        fun setRichPresence(presence: Map<String, String>)
        fun close(clearState: Boolean)
    }

    // No refresh token survives an iteration or a failed read.
    private data class Account(val name: String, val steamId: String, val revision: Long)
    private var account: Account? = null
    private var epoch: String? = null
    private var client: Client? = null
    private var session: SteamGamePresenceSession? = null
    private var clearStateOnExit = true
    private val retryPolicy = SteamGamePresenceRetryPolicy()

    fun run() {
        try {
            while (owner.isCurrent && shouldContinue()) {
                try {
                    val auth = requireCurrentAuth()
                    val presence = readPresence()
                    if (presence != null) {
                        if (session == null) {
                            val connectedClient = createClient(auth)
                            client = connectedClient
                            clearStateOnExit = true
                            session = guardedSession(connectedClient)
                        }
                        val restored = checkNotNull(session).synchronize(presence)
                        retryPolicy.reset()
                        if (restored) onRestored()
                    }
                } catch (error: Exception) {
                    if (!owner.isCurrent) return
                    // A stop racing an auth failure must not turn it into a clearing RPC, even
                    // if a later read recovers. Ordinary stop/interruption retains final cleanup.
                    if (error is AuthUnavailableException || error is AuthStoppedException ||
                        error is SteamSessionRevokedException || error.isSteamCmCredentialRejection()
                    ) {
                        clearStateOnExit = false
                    }
                    if (!shouldContinue()) throw error
                    val failure = classifyFailure(error)
                    if (failure is AuthStoppedException) return
                    if (!SteamGamePresenceRetryPolicy.shouldRetry(failure)) throw failure
                    disconnect(clearState = false)
                    val retry = retryPolicy.afterFailure(nowMs())
                    onRetry(failure)
                    awaitRetry(retry)
                    continue
                }
                awaitChange()
            }
        } finally {
            try {
                disconnect(clearState = clearStateOnExit)
            } finally {
                owner.release()
            }
        }
    }

    private fun requireCurrentAuth(): SteamCloudAuthStore.SavedAuthMaterial {
        val read = readAuth(epoch)
        if (epoch == null) epoch = read.accountEpoch
        if (read.readFailed) throw AuthUnavailableException()
        val auth = read.material ?: throw AuthStoppedException()
        if (read.accountEpoch != epoch) throw AuthStoppedException()
        val current = Account(auth.accountName, auth.steamId64, auth.credentialRevision)
        if (account != null && account != current) throw AuthStoppedException()
        account = current
        return auth
    }

    private fun classifyFailure(error: Exception): Exception {
        if (error !is SteamSessionRevokedException) return error
        // Shared CM's nullable validator can also report a read error as revocation, including
        // inside logOn/send after our last read. Re-read with the original identity/epoch fence;
        // only a still-valid account or an unreadable state may retry with a fresh client.
        return try {
            requireCurrentAuth()
            IOException("Steam presence CM account validation was interrupted.", error)
        } catch (readError: Exception) {
            readError
        }
    }

    private fun guardedSession(active: Client) = SteamGamePresenceSession(object : SteamGamePresenceSession.Client {
        override val sessionIdentity: Any?
            get() = active.sessionIdentity

        override fun logOn() = owner.withCurrent { active.logOn(requireCurrentAuth()) }

        override fun setPersonaOnline() = owner.withCurrent {
            requireCurrentAuth()
            active.setPersonaOnline()
        }

        override fun setGamePlayedAppId(appId: Long) = owner.withCurrent {
            requireCurrentAuth()
            active.setGamePlayedAppId(appId)
        }

        override fun setRichPresence(presence: Map<String, String>) = owner.withCurrent {
            requireCurrentAuth()
            active.setRichPresence(presence)
        }
    })

    private fun disconnect(clearState: Boolean) {
        val active = client ?: return
        client = null
        session = null
        owner.closeClient { mayClear ->
            // onDestroy only cancels the worker. Mask interruption after acquiring the worker
            // gate so even a stop while waiting for a prior owner's IO preserves safe cleanup.
            var interrupted = Thread.interrupted()
            try {
                val canClear = mayClear && clearState && try {
                    requireCurrentAuth()
                    true
                } catch (error: Exception) {
                    if (error is InterruptedException) interrupted = true
                    false
                }
                active.close(canClear)
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private class AuthUnavailableException : IOException("Steam presence credentials are temporarily unreadable.")

    private class AuthStoppedException : CancellationException("Steam presence account is no longer current.")

    companion object {
        fun shouldStart(read: SteamCloudAuthStore.AuthMaterialRead): Boolean =
            read.readFailed || read.material != null
    }
}

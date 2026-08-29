package top.apricityx.workshop.steam.protocol

import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide CM connection pool.
 *
 * Every feature (market queries, web-token issuance, workshop downloads, cloud
 * sync, achievements, presence) borrows the same underlying [OkHttpSteamCmSession]
 * instead of opening its own websocket per operation. Connect requests are
 * serialized through one mutex and deduplicated: once the shared transport is
 * logged on, further connects with the same refresh token return immediately,
 * which removes the per-RPC/per-file logon storms that trip Steam rate limiting.
 *
 * Borrowed handles ([asCmSession]) report [SteamCmSession.close] as a no-op so
 * existing `sessionFactory().use { }` call sites keep compiling while no longer
 * tearing down the shared transport. A server-side logoff or socket failure
 * clears the session state; the next borrowed connect transparently re-logons.
 */
class SharedSteamCmSession(
    private val sessionFactory: () -> SteamCmSession,
    private val accountProvider: suspend () -> SteamAccountSession? = { null },
) {
    private val mutex = Mutex()
    private val underlying: SteamCmSession by lazy { sessionFactory() }

    @Volatile
    private var loggedOnTokenFingerprint: String? = null

    /**
     * Returns a [SteamCmSession] handle whose close() never closes the shared
     * transport and whose connect methods reuse an existing logon.
     */
    fun asCmSession(): SteamCmSession = Borrowed()

    val currentSession get() = underlying.currentSession

    /**
     * Ensures the shared transport is connected and logged on.
     *
     * Pass an explicit [requestedAccount] to pin the credentials for this call;
     * pass null to use whatever [accountProvider] currently reports, falling
     * back to an anonymous logon when no account is available. An already
     * authenticated transport is always reused, even for anonymous requests.
     */
    suspend fun ensureLoggedOn(
        servers: List<CmServer>,
        requestedAccount: SteamAccountSession?,
    ): SessionContext = mutex.withLock {
        val account = requestedAccount ?: runCatching { accountProvider() }.getOrNull()
        if (underlying.currentSession.value != null) {
            val wantedFingerprint = account?.refreshToken?.let(::tokenFingerprint)
            if (wantedFingerprint == null || wantedFingerprint == loggedOnTokenFingerprint) {
                return@withLock underlying.currentSession.value ?: error("Steam CM session vanished")
            }
            // The stored account changed since this transport logged on.
            closeTransportLocked()
        }
        if (account != null) {
            underlying.connectWithRefreshToken(servers, account).also {
                loggedOnTokenFingerprint = tokenFingerprint(account.refreshToken)
            }
        } else {
            underlying.connectAnonymous(servers).also {
                loggedOnTokenFingerprint = null
            }
        }
    }

    /**
     * Drops the shared transport so the next connect performs a fresh logon.
     * Called after logout or credential wipes; safe to invoke concurrently.
     */
    fun invalidate() {
        loggedOnTokenFingerprint = null
        runCatching { underlying.close() }
    }

    private fun closeTransportLocked() {
        loggedOnTokenFingerprint = null
        runCatching { underlying.close() }
    }

    private fun tokenFingerprint(refreshToken: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(refreshToken.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private inner class Borrowed : SteamCmSession {
        override val currentSession get() = this@SharedSteamCmSession.currentSession

        override suspend fun connect(servers: List<CmServer>) {
            ensureLoggedOn(servers, requestedAccount = null)
        }

        override suspend fun connectAnonymous(servers: List<CmServer>): SessionContext =
            ensureLoggedOn(servers, requestedAccount = null)

        override suspend fun connectWithRefreshToken(
            servers: List<CmServer>,
            account: SteamAccountSession,
        ): SessionContext = ensureLoggedOn(servers, requestedAccount = account)

        override suspend fun <T : com.google.protobuf.MessageLite> callServiceMethod(
            methodName: String,
            request: com.google.protobuf.MessageLite,
            parser: com.google.protobuf.Parser<T>,
        ): T = underlying.callServiceMethod(methodName, request, parser)

        override suspend fun <T : com.google.protobuf.MessageLite> sendClientMessage(
            emsg: Int,
            request: com.google.protobuf.MessageLite,
            responseEmsg: Int,
            parser: com.google.protobuf.Parser<T>,
        ): T = underlying.sendClientMessage(emsg, request, responseEmsg, parser)

        override suspend fun <T : com.google.protobuf.MessageLite> sendClientMessage(
            emsg: Int,
            request: com.google.protobuf.MessageLite,
            responseEmsg: Int,
            parser: com.google.protobuf.Parser<T>,
            routingAppId: UInt?,
        ): T = underlying.sendClientMessage(emsg, request, responseEmsg, parser, routingAppId)

        override suspend fun sendClientMessage(emsg: Int, request: com.google.protobuf.MessageLite) =
            underlying.sendClientMessage(emsg, request)

        override suspend fun sendClientMessage(
            emsg: Int,
            request: com.google.protobuf.MessageLite,
            routingAppId: UInt,
        ) = underlying.sendClientMessage(emsg, request, routingAppId)

        override suspend fun requestDepotDecryptionKey(appId: UInt, depotId: UInt): ByteArray =
            underlying.requestDepotDecryptionKey(appId, depotId)

        override suspend fun requestAppProductInfo(appId: UInt): SteamAppProductInfo =
            underlying.requestAppProductInfo(appId)

        /** Borrowed handle: the shared transport stays open for other features. */
        override fun close() = Unit
    }
}

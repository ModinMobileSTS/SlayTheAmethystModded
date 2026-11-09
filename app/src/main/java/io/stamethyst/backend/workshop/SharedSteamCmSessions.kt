package io.stamethyst.backend.workshop

import android.content.Context
import android.os.FileObserver
import io.stamethyst.backend.steam.SteamCmAccountEpoch
import io.stamethyst.backend.steam.SteamCmLoginFailureNotice
import io.stamethyst.backend.steamcloud.SteamAuthenticationCircuitBreaker
import io.stamethyst.backend.steamcloud.SteamCloudAcceleratedHttp
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import java.util.concurrent.TimeUnit
import top.apricityx.workshop.steam.protocol.SharedSteamCmSession
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.isSteamCmCredentialRejection

/**
 * Holds the one process-wide shared CM connection.
 *
 * Each Android process gets its own instance (the market/workshop stack runs in
 * the main process while Steam Cloud sync runs in `:steamcloud`), so within a
 * process every feature reuses a single logged-on CM websocket instead of each
 * operation opening its own. The first caller's transport configuration wins;
 * later callers only borrow it.
 */
internal object SharedSteamCmSessions {
    @Volatile
    private var instance: SharedSteamCmSession? = null
    private var epoch: String? = null
    private var observer: FileObserver? = null

    @JvmStatic
    fun forProcess(context: Context): SharedSteamCmSession = synchronized(this) {
        val appContext = context.applicationContext
        if (observer == null) {
            @Suppress("DEPRECATION")
            val watcher = object : FileObserver(appContext.filesDir.path, MOVED_TO or CLOSE_WRITE or DELETE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == SteamCmAccountEpoch.FILE_NAME) synchronized(this@SharedSteamCmSessions) {
                        if (runCatching { epoch != SteamCmAccountEpoch.read(appContext) }.getOrDefault(true)) {
                            invalidateProcessSession()
                        }
                    }
                }
            }
            observer = watcher
            watcher.startWatching()
        }
        val currentEpoch = SteamCmAccountEpoch.read(appContext)
        if (epoch != currentEpoch) invalidateProcessSession()
        instance ?: createShared(appContext, currentEpoch).also { instance = it; epoch = currentEpoch }
    }

    /**
     * Drops the shared transport after logout or credential wipes so the next
     * connect performs a fresh logon instead of riding stale credentials.
     */
    @JvmStatic
    @Synchronized
    fun invalidateProcessSession() {
        instance?.invalidate()
        instance = null
    }

    private fun createShared(appContext: Context, sessionEpoch: String): SharedSteamCmSession {
        val identity = WorkshopSteamClientIdentity(appContext)
        val cmHttpClient = SteamCloudAcceleratedHttp.createClient(
            context = appContext,
            connectTimeoutMs = 40_000L,
            readTimeoutMs = 60_000L,
            callTimeoutMs = 60_000L,
        ).configure {
            // Detect half-open sockets after Android suspends background networking.
            pingInterval(30L, TimeUnit.SECONDS)
        }
        return SharedSteamCmSession(
            sessionFactory = {
                identity.createSession(
                    cmHttpClient,
                    accountValidator = { account ->
                        SteamCmAccountEpoch.read(appContext) == sessionEpoch &&
                            readSharedAccount(appContext, identity)?.let {
                                it.steamId == account.steamId && it.refreshToken == account.refreshToken
                            } == true
                    },
                    onAccountLogonFailure = { account, error ->
                        if (error.isSteamCmCredentialRejection()) SteamCmLoginFailureNotice.report(appContext, account)
                    },
                    sessionValidator = { SteamCmAccountEpoch.read(appContext) == sessionEpoch },
                )
            },
            accountProvider = {
                if (SteamCmAccountEpoch.read(appContext) != sessionEpoch) {
                    throw top.apricityx.workshop.steam.protocol.SteamSessionRevokedException()
                }
                readSharedAccount(appContext, identity)
            },
        )
    }

    private fun readSharedAccount(
        appContext: Context,
        identity: WorkshopSteamClientIdentity,
    ): SteamAccountSession? {
        if (SteamAuthenticationCircuitBreaker.isOpen()) {
            return null
        }
        val auth = SteamCloudAuthStore.readCmAuthMaterial(appContext)
            ?: return null
        if (auth.refreshToken.isBlank()) {
            return null
        }
        val steamId64 = auth.steamId64.trim().toLongOrNull()
            ?: 0L
        if (steamId64 <= 0L) {
            return null
        }
        return SteamAccountSession(
            accountName = auth.accountName,
            steamId = steamId64,
            refreshToken = auth.refreshToken,
            machineName = identity.machineName,
        )
    }
}

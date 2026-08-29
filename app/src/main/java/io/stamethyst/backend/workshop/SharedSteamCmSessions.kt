package io.stamethyst.backend.workshop

import android.content.Context
import io.stamethyst.backend.steamcloud.SteamAuthenticationCircuitBreaker
import io.stamethyst.backend.steamcloud.SteamCloudAcceleratedHttp
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import top.apricityx.workshop.steam.protocol.SharedSteamCmSession
import top.apricityx.workshop.steam.protocol.SteamAccountSession

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

    @JvmStatic
    fun forProcess(context: Context): SharedSteamCmSession =
        instance ?: synchronized(this) {
            instance ?: createShared(context.applicationContext).also { instance = it }
        }

    /**
     * Drops the shared transport after logout or credential wipes so the next
     * connect performs a fresh logon instead of riding stale credentials.
     */
    @JvmStatic
    fun invalidateProcessSession() {
        instance?.invalidate()
    }

    private fun createShared(appContext: Context): SharedSteamCmSession {
        val identity = WorkshopSteamClientIdentity(appContext)
        val cmHttpClient = SteamCloudAcceleratedHttp.createClient(
            context = appContext,
            connectTimeoutMs = 40_000L,
            readTimeoutMs = 60_000L,
            callTimeoutMs = 60_000L,
        )
        return SharedSteamCmSession(
            sessionFactory = { identity.createSession(cmHttpClient) },
            accountProvider = { readSharedAccount(appContext, identity) },
        )
    }

    private suspend fun readSharedAccount(
        appContext: Context,
        identity: WorkshopSteamClientIdentity,
    ): SteamAccountSession? {
        if (SteamAuthenticationCircuitBreaker.isOpen()) {
            return null
        }
        val auth = runCatching { SteamCloudAuthStore.readAuthMaterial(appContext) }.getOrNull()
            ?: return null
        if (auth.refreshToken.isBlank()) {
            return null
        }
        val steamId64 = runCatching { SteamCloudAuthStore.readSnapshot(appContext).steamId64 }
            .getOrNull()
            ?.trim()
            ?.toLongOrNull()
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

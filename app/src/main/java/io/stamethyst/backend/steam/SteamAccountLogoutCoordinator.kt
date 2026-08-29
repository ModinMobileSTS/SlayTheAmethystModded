package io.stamethyst.backend.steam

import android.content.Context
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import io.stamethyst.backend.steamcloud.SteamCloudAvatarCacheStore
import io.stamethyst.backend.steamcloud.SteamCloudBaselineStore
import io.stamethyst.backend.steamcloud.SteamCloudDiagnosticsStore
import io.stamethyst.backend.steamcloud.SteamCloudManifestStore
import io.stamethyst.backend.steamcloud.SteamCloudLiveSaveInUseException
import io.stamethyst.backend.steamcloud.SteamCloudOperationMutex
import io.stamethyst.backend.steamcloud.SteamCloudSaveProfileManager
import io.stamethyst.backend.steamcloud.SteamCloudSyncProcessService
import io.stamethyst.backend.steamcloud.SteamGamePresenceService
import io.stamethyst.backend.workshop.SharedSteamCmSessions
import io.stamethyst.backend.workshop.WorkshopService
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.ui.preferences.LauncherPreferences
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession

internal object SteamAccountLogoutCoordinator {
    fun logout(
        context: Context,
        clearDiagnostics: Boolean,
    ) {
        val appContext = context.applicationContext

        SteamGamePresenceService.stop(appContext)
        LauncherPreferences.setSteamGamePresenceEnabled(appContext, false)
        runCatching { SteamCloudSyncProcessService.cancel(appContext) }
        WorkshopService.cancelAllActiveCalls()
        OkHttpSteamCmSession.closeAllActiveSessions()
        // The shared CM transport is closed by closeAllActiveSessions above; also
        // reset its logon fingerprint so the next connect re-logons instead of
        // riding the logged-out state.
        SharedSteamCmSessions.invalidateProcessSession()

        SteamCloudOperationMutex.runExclusive(appContext) {
            val cloudProfileSteamId = SteamCloudAuthStore.readAuthMaterial(appContext)
                ?.steamId64
                .orEmpty()
            val currentMode = LauncherPreferences.readSteamCloudSaveMode(appContext)
            if (currentMode != SteamCloudSaveMode.INDEPENDENT) {
                try {
                    SteamCloudSaveProfileManager.switchMode(
                        context = appContext,
                        fromMode = currentMode,
                        toMode = SteamCloudSaveMode.INDEPENDENT,
                    )
                    LauncherConfig.saveSteamCloudIndependentSwitchPending(appContext, false)
                } catch (_: SteamCloudLiveSaveInUseException) {
                    LauncherConfig.saveSteamCloudIndependentSwitchPending(
                        context = appContext,
                        pending = true,
                        cloudProfileSteamId = cloudProfileSteamId,
                    )
                }
            } else {
                LauncherPreferences.saveSteamCloudSaveMode(appContext, SteamCloudSaveMode.INDEPENDENT)
                LauncherConfig.saveSteamCloudIndependentSwitchPending(appContext, false)
            }
            SteamCloudAuthStore.clear(appContext)
            SteamCloudAvatarCacheStore.clear(appContext)
            SteamCloudManifestStore.clear(appContext)
            SteamCloudBaselineStore.clear(appContext)
            if (clearDiagnostics) {
                SteamCloudDiagnosticsStore.clear(appContext)
            }

            val snapshot = SteamCloudAuthStore.readSnapshot(appContext)
            check(snapshot.accountName.isBlank() &&
                !snapshot.refreshTokenConfigured &&
                !snapshot.guardDataConfigured &&
                snapshot.steamId64.isBlank()
            ) {
                "Steam account credentials are still present after logout."
            }
        }
    }
}

package io.stamethyst.backend.steamcloud

import android.content.Context
import io.stamethyst.R
import io.stamethyst.config.LauncherConfig
import java.util.concurrent.CancellationException

internal object SteamCloudSyncPolicy {
    @JvmStatic
    fun requireSyncEnabled(context: Context) {
        if (LauncherConfig.isSteamCloudSyncDisabled(context)) {
            throw CancellationException(context.getString(R.string.settings_steam_cloud_sync_disabled_title))
        }
    }
}

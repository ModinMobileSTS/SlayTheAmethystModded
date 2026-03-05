package io.stamethyst.ui.settings

import android.app.Activity
import io.stamethyst.config.LauncherConfig
import java.io.IOException

internal object GameplaySettingsService {
    const val DEFAULT_TOUCHSCREEN_ENABLED = LauncherConfig.DEFAULT_TOUCHSCREEN_ENABLED

    fun readTouchscreenEnabled(host: Activity): Boolean {
        return LauncherConfig.readTouchscreenEnabled(host)
    }

    @Throws(IOException::class)
    fun saveTouchscreenEnabled(host: Activity, enabled: Boolean) {
        LauncherConfig.saveTouchscreenEnabled(host, enabled)
    }
}

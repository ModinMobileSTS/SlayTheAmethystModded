package io.stamethyst.ui.settings

import android.content.Context
import io.stamethyst.config.LauncherConfig
import java.io.IOException

internal object GameplaySettingsService {
    const val DEFAULT_TOUCHSCREEN_ENABLED = LauncherConfig.DEFAULT_TOUCHSCREEN_ENABLED

    fun readTouchscreenEnabled(context: Context): Boolean {
        return LauncherConfig.readTouchscreenEnabled(context)
    }

    @Throws(IOException::class)
    fun saveTouchscreenEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.saveTouchscreenEnabled(context, enabled)
    }
}

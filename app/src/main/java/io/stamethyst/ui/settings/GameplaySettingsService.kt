package io.stamethyst.ui.settings

import android.content.Context
import io.stamethyst.config.LauncherConfig
import java.io.IOException

internal object GameplaySettingsService {
    const val DEFAULT_TOUCHSCREEN_ENABLED = LauncherConfig.DEFAULT_TOUCHSCREEN_ENABLED
    const val DEFAULT_FONT_SCALE = LauncherConfig.DEFAULT_GAMEPLAY_FONT_SCALE
    const val MIN_FONT_SCALE = LauncherConfig.MIN_GAMEPLAY_FONT_SCALE
    const val MAX_FONT_SCALE = LauncherConfig.MAX_GAMEPLAY_FONT_SCALE
    const val FONT_SCALE_STEP = LauncherConfig.GAMEPLAY_FONT_SCALE_STEP

    fun readTouchscreenEnabled(context: Context): Boolean {
        return LauncherConfig.readTouchscreenEnabled(context)
    }

    @Throws(IOException::class)
    fun saveTouchscreenEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.saveTouchscreenEnabled(context, enabled)
    }

    fun readFontScale(context: Context): Float {
        return LauncherConfig.readGameplayFontScale(context)
    }

    @Throws(IOException::class)
    fun saveFontScale(context: Context, value: Float): String {
        return LauncherConfig.saveGameplayFontScale(context, value)
    }

    fun normalizeFontScale(value: Float): Float {
        return LauncherConfig.normalizeGameplayFontScale(value)
    }

    fun formatFontScale(value: Float): String {
        return LauncherConfig.formatGameplayFontScale(value)
    }
}

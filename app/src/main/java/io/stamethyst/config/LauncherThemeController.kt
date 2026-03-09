package io.stamethyst.config

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object LauncherThemeController {
    @JvmStatic
    fun apply(themeMode: LauncherThemeMode) {
        AppCompatDelegate.setDefaultNightMode(themeMode.appCompatNightMode)
    }

    @JvmStatic
    fun applySavedThemeMode(context: Context) {
        apply(LauncherConfig.readThemeMode(context))
    }
}

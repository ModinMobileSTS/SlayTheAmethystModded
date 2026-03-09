package io.stamethyst.config

import androidx.appcompat.app.AppCompatDelegate

enum class LauncherThemeMode(
    val persistedValue: String,
    val appCompatNightMode: Int
) {
    FOLLOW_SYSTEM("follow_system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        @JvmStatic
        fun fromPersistedValue(value: String?): LauncherThemeMode? {
            if (value.isNullOrBlank()) {
                return null
            }
            return entries.firstOrNull { it.persistedValue == value.trim() }
        }
    }
}

package io.stamethyst.config

import androidx.compose.ui.graphics.Color

enum class LauncherThemeColor(
    val persistedValue: String,
    val seedColor: Color
) {
    ZHANSHIGE("zhanshige", Color(0xFFC24A4A)),
    LIEBAO("liebao", Color(0xFF3F8A4B)),
    JIBAO("jibao", Color(0xFFD2A72E)),
    GUANJIE("guanjie", Color(0xFF6F4C93)),
    COLORLESS("colorless", Color(0xFF7B7E86));

    companion object {
        @JvmStatic
        fun fromPersistedValue(value: String?): LauncherThemeColor? {
            if (value.isNullOrBlank()) {
                return null
            }
            return entries.firstOrNull { it.persistedValue == value.trim() }
        }
    }
}

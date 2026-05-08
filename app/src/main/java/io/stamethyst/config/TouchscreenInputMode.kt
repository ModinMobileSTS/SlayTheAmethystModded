package io.stamethyst.config

enum class TouchscreenInputMode(val persistedValue: String) {
    DESKTOP("desktop"),
    HYBRID("hybrid"),
    MOBILE("mobile");

    val touchscreenEnabled: Boolean
        get() = this != DESKTOP

    val nativeTouchscreenAllowlistEnabled: Boolean
        get() = this != MOBILE

    companion object {
        fun fromSettings(
            touchscreenEnabled: Boolean,
            nativeTouchscreenAllowlistEnabled: Boolean,
        ): TouchscreenInputMode {
            return when {
                !touchscreenEnabled -> DESKTOP
                nativeTouchscreenAllowlistEnabled -> HYBRID
                else -> MOBILE
            }
        }

        fun fromPersistedValue(value: String?): TouchscreenInputMode? {
            if (value.isNullOrBlank()) {
                return null
            }
            return entries.firstOrNull { it.persistedValue == value.trim() }
        }
    }
}

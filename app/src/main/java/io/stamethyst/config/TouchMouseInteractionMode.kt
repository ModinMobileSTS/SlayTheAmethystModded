package io.stamethyst.config

enum class TouchMouseInteractionMode(val persistedValue: String) {
    OPEN_MENU_ON_TAP("open_menu_on_tap"),
    TOGGLE_BUTTON_ON_TAP("toggle_button_on_tap");

    companion object {
        fun fromPersistedValue(value: String?): TouchMouseInteractionMode? {
            if (value.isNullOrBlank()) {
                return null
            }
            return entries.firstOrNull { it.persistedValue == value.trim() }
        }
    }
}

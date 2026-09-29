package io.stamethyst.config

enum class FramePacingMode(val persistedValue: String) {
    BUILT_IN("built_in"),
    SWAPPY("swappy"),
    OFF("off");

    companion object {
        fun fromPersistedValue(value: String?): FramePacingMode? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.persistedValue == value.trim() }
        }
    }
}

package io.stamethyst.config

enum class ModPageLayout(val persistedValue: String) {
    CLASSIC("classic"),
    COMPACT("compact");

    companion object {
        fun fromPersistedValue(value: String?): ModPageLayout? =
            entries.firstOrNull { it.persistedValue == value }
    }
}

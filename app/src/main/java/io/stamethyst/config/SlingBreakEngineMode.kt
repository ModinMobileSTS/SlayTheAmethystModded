package io.stamethyst.config

enum class SlingBreakEngineMode(val persistedValue: String) {
    WEBVIEW("webview"),
    COMPATIBILITY("compatibility");

    companion object {
        fun fromPersistedValue(value: String?): SlingBreakEngineMode =
            entries.firstOrNull { it.persistedValue == value } ?: WEBVIEW
    }
}

package io.stamethyst.backend.render

enum class RendererSelectionMode(
    val persistedValue: String
) {
    AUTO("auto"),
    MANUAL("manual");

    companion object {
        @JvmStatic
        fun fromPersistedValue(value: String?): RendererSelectionMode? {
            if (value.isNullOrBlank()) {
                return null
            }
            return entries.firstOrNull { it.persistedValue == value.trim() }
        }
    }
}

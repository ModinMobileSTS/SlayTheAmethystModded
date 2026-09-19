package io.stamethyst.config

enum class BootOverlayStyle(
    val persistedValue: String,
    val supportsLoadingAnimation: Boolean = false
) {
    MODERN("modern"),
    LEGACY("legacy", supportsLoadingAnimation = true),
    CLASSIC_LOG("classic_log"),
    MATERIAL_LOG("material_log"),
    SLING_BREAK("sling_break");

    companion object {
        fun fromPersistedValue(value: String?): BootOverlayStyle? {
            return entries.firstOrNull { it.persistedValue == value }
        }
    }
}

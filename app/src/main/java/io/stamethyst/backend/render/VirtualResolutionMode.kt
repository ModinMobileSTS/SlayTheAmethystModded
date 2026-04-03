package io.stamethyst.backend.render

enum class VirtualResolutionMode(
    val persistedValue: String,
    val targetAspectRatio: Float?,
    val fixedBaseWidth: Int? = null,
    val fixedBaseHeight: Int? = null
) {
    FULLSCREEN_FILL("fullscreen_fill", null),
    RESOLUTION_1080P("1080p", 16f / 9f, 1920, 1080),
    RATIO_4_3("4_3", 4f / 3f),
    RATIO_16_9("16_9", 16f / 9f);

    companion object {
        @JvmStatic
        fun fromPersistedValue(value: String?): VirtualResolutionMode? {
            if (value.isNullOrBlank()) {
                return null
            }
            return entries.firstOrNull { it.persistedValue == value.trim() }
        }
    }
}

package io.stamethyst.config

enum class RenderSurfaceBackend(
    val persistedValue: String,
    val usesTextureViewSurface: Boolean
) {
    SURFACE_VIEW("surface_view", false),
    TEXTURE_VIEW("texture_view", true);

    companion object {
        @JvmStatic
        fun fromPersistedValue(value: String?): RenderSurfaceBackend? {
            if (value.isNullOrBlank()) {
                return null
            }
            return entries.firstOrNull { it.persistedValue == value.trim() }
        }
    }
}

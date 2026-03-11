package io.stamethyst.backend.render

enum class RendererAvailabilityReason {
    VULKAN_UNSUPPORTED,
    MISSING_NATIVE_LIBRARIES
}

data class RendererAvailability(
    val backend: RendererBackend,
    val available: Boolean,
    val reasons: List<RendererAvailabilityReason> = emptyList(),
    val missingLibraries: List<String> = emptyList()
) {
    fun describeUnavailable(): String? {
        if (available) {
            return null
        }
        val parts = ArrayList<String>(2)
        if (reasons.contains(RendererAvailabilityReason.VULKAN_UNSUPPORTED)) {
            parts += "requires Vulkan support"
        }
        if (missingLibraries.isNotEmpty()) {
            parts += "missing ${missingLibraries.joinToString(", ")}"
        }
        return parts.joinToString("; ").ifBlank { "unavailable" }
    }
}

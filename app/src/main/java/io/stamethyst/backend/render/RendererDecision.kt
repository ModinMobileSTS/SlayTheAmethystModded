package io.stamethyst.backend.render

import io.stamethyst.config.RenderSurfaceBackend

data class RendererDecision(
    val selectionMode: RendererSelectionMode,
    val manualBackend: RendererBackend?,
    val automaticBackend: RendererBackend,
    val effectiveBackend: RendererBackend,
    val requestedSurfaceBackend: RenderSurfaceBackend,
    val effectiveSurfaceBackend: RenderSurfaceBackend,
    val availableBackends: List<RendererAvailability>,
    val manualFallbackAvailability: RendererAvailability? = null,
    val enableEmuiIteratorMitigation: Boolean = false,
    val enableUbwcHint: Boolean = false
) {
    val surfaceBackendForced: Boolean
        get() = effectiveSurfaceBackend != requestedSurfaceBackend

    val usedAutomaticFallback: Boolean
        get() = manualFallbackAvailability != null

    fun effectiveRendererSummary(): String {
        return buildString {
            append(effectiveBackend.displayName)
            if (selectionMode == RendererSelectionMode.AUTO) {
                append(" (Auto)")
            } else if (usedAutomaticFallback) {
                append(" (Auto fallback)")
            }
        }
    }

    fun autoSelectionSummary(): String = automaticBackend.displayName

    fun fallbackSummary(): String? {
        val fallback = manualFallbackAvailability ?: return null
        return buildString {
            append(manualBackend?.displayName ?: "Manual renderer")
            append(" unavailable")
            fallback.describeUnavailable()?.let {
                append(": ")
                append(it)
            }
            append(". Using ")
            append(automaticBackend.displayName)
            append(".")
        }
    }

    fun overlaySummary(): String {
        return buildString {
            append(effectiveBackend.displayName)
            if (selectionMode == RendererSelectionMode.AUTO) {
                append(" [Auto]")
            } else if (usedAutomaticFallback) {
                append(" [Fallback]")
            }
            if (surfaceBackendForced) {
                append(" [")
                append(
                    when (effectiveSurfaceBackend) {
                        RenderSurfaceBackend.SURFACE_VIEW -> "SV"
                        RenderSurfaceBackend.TEXTURE_VIEW -> "TV"
                    }
                )
                append("]")
            }
        }
    }
}

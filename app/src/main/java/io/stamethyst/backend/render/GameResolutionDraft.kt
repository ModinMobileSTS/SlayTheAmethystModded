package io.stamethyst.backend.render

import kotlin.math.roundToInt

internal data class GameResolutionDraft(val region: GameDisplayRegion, val scale: Float) {
    fun withRegion(region: GameDisplayRegion, width: Int, height: Int): GameResolutionDraft {
        val area = region.toPixels(width, height)
        return GameResolutionDraft(region, scale.coerceIn(VirtualResolutionPolicy.minimumScale(area.width, area.height), 1f))
    }

    companion object {
        /** Preserve the old visible rectangle and approximate its scale until the user edits it. */
        fun fromSaved(
            region: GameDisplayRegion,
            scale: Float,
            mode: VirtualResolutionMode,
            width: Int,
            height: Int,
        ): GameResolutionDraft {
            if (mode == VirtualResolutionMode.FULLSCREEN_FILL) {
                return GameResolutionDraft(region, scale).withRegion(region, width, height)
            }
            val area = region.toPixels(width, height)
            val render = VirtualResolutionPolicy.resolve(area.width, area.height, scale, mode)
            val visible = VirtualResolutionPolicy.fitViewport(area, render.width, render.height)
            val editable = GameDisplayRegion.fromPixels(visible, width, height)
            val pixels = editable.toPixels(width, height)
            val equivalent = maxOf(render.width.toFloat() / pixels.width, render.height.toFloat() / pixels.height)
            return GameResolutionDraft(editable, equivalent).withRegion(editable, width, height)
        }
    }
}

internal enum class GameResolutionPreset {
    FULLSCREEN, HD, PERFORMANCE, CLASSIC;

    fun resolve(width: Int, height: Int): GameResolutionDraft? {
        val fullscreen = GameDisplayRegion.FULLSCREEN
        if (this == FULLSCREEN) return GameResolutionDraft(fullscreen, 1f)
        val ratio = if (this == CLASSIC) 4.0 / 3 else 16.0 / 9
        val widthAtFullHeight = (height * ratio).roundToInt()
        val w = minOf(width, widthAtFullHeight)
        val h = if (widthAtFullHeight <= width) height else minOf(height, (width / ratio).roundToInt())
        val region = fullscreen.withPixelSize(width, height, w, h) ?: return null
        // 1080P and 720P share the same display area; only their render caps differ.
        val scale = when (this) {
            HD -> (1080f / h).coerceAtMost(1f)
            PERFORMANCE -> (720f / h).coerceAtMost(1f)
            else -> 1f
        }
        return GameResolutionDraft(region, scale).withRegion(region, width, height)
    }
}

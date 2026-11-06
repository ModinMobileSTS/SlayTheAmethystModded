package io.stamethyst.backend.render

import kotlin.math.roundToInt

/** Fractions of the complete display in its canonical landscape orientation (camera usually left). */
data class GameDisplayRegion(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun normalized(): GameDisplayRegion {
        val x = left.finiteOr(0f).coerceIn(0f, 1f - MIN_EXTENT)
        val y = top.finiteOr(0f).coerceIn(0f, 1f - MIN_EXTENT)
        return GameDisplayRegion(
            x, y,
            right.finiteOr(1f).coerceIn(x + MIN_EXTENT, 1f),
            bottom.finiteOr(1f).coerceIn(y + MIN_EXTENT, 1f),
        )
    }

    /** Opposite corner stays fixed; neither crossing nor leaving the panel is allowed. */
    fun moveCorner(corner: DisplayRegionCorner, x: Float, y: Float): GameDisplayRegion {
        val region = normalized()
        val nextX = x.finiteOr(if (corner.isLeft) region.left else region.right)
        val nextY = y.finiteOr(if (corner.isTop) region.top else region.bottom)
        return region.copy(
            left = if (corner.isLeft) nextX.coerceIn(0f, region.right - MIN_EXTENT) else region.left,
            top = if (corner.isTop) nextY.coerceIn(0f, region.bottom - MIN_EXTENT) else region.top,
            right = if (!corner.isLeft) nextX.coerceIn(region.left + MIN_EXTENT, 1f) else region.right,
            bottom = if (!corner.isTop) nextY.coerceIn(region.top + MIN_EXTENT, 1f) else region.bottom,
        )
    }

    fun rotated180(): GameDisplayRegion = normalized().let {
        GameDisplayRegion(1f - it.right, 1f - it.bottom, 1f - it.left, 1f - it.top)
    }

    fun toPixels(displayWidth: Int, displayHeight: Int): DisplayPixelRect {
        val w = displayWidth.coerceAtLeast(1)
        val h = displayHeight.coerceAtLeast(1)
        val region = normalized()
        val x = (region.left * w).roundToInt().coerceIn(0, w - 1)
        val y = (region.top * h).roundToInt().coerceIn(0, h - 1)
        return DisplayPixelRect(
            x, y,
            (region.right * w).roundToInt().coerceIn(x + 1, w),
            (region.bottom * h).roundToInt().coerceIn(y + 1, h),
        )
    }

    fun persistedValue(): String = normalized().let { "${it.left},${it.top},${it.right},${it.bottom}" }

    companion object {
        val FULLSCREEN = GameDisplayRegion()
        const val MIN_EXTENT = 0.5f

        fun fromPersistedValue(value: String?): GameDisplayRegion? {
            val parts = value?.split(',')?.map { it.toFloatOrNull() ?: return null } ?: return null
            if (parts.size != 4 || parts.any { !it.isFinite() }) return null
            return GameDisplayRegion(parts[0], parts[1], parts[2], parts[3]).normalized()
        }
    }
}

enum class DisplayRegionCorner(val isLeft: Boolean, val isTop: Boolean) {
    TOP_LEFT(true, true), TOP_RIGHT(false, true), BOTTOM_LEFT(true, false), BOTTOM_RIGHT(false, false)
}

data class DisplayPixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Android Surface rotations are counter-clockwise coordinate transforms, not UI orientation flags. */
object DisplayGeometryRotation {
    fun canonicalLandscapeRotation(width: Int, height: Int, currentRotation: Int): Int {
        val naturalWidth = if (currentRotation % 2 == 0) width else height
        val naturalHeight = if (currentRotation % 2 == 0) height else width
        return if (naturalWidth >= naturalHeight) 0 else 1
    }

    fun rotateRect(rect: DisplayPixelRect, width: Int, height: Int, quarterTurns: Int): DisplayPixelRect =
        when ((quarterTurns % 4 + 4) % 4) {
            1 -> DisplayPixelRect(rect.top, width - rect.right, rect.bottom, width - rect.left)
            2 -> DisplayPixelRect(width - rect.right, height - rect.bottom, width - rect.left, height - rect.top)
            3 -> DisplayPixelRect(height - rect.bottom, rect.left, height - rect.top, rect.right)
            else -> rect
        }
}

private fun Float.finiteOr(fallback: Float): Float = takeIf { it.isFinite() } ?: fallback

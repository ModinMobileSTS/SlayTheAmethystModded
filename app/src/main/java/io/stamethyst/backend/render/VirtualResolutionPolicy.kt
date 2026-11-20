package io.stamethyst.backend.render

import kotlin.math.abs

object VirtualResolutionPolicy {
    const val MIN_GAME_WIDTH = 800
    const val MIN_GAME_HEIGHT = 450

    @JvmStatic
    fun resolve(
        physicalWidth: Int,
        physicalHeight: Int,
        renderScale: Float,
        mode: VirtualResolutionMode = VirtualResolutionMode.FULLSCREEN_FILL
    ): VirtualResolution {
        val viewportSize = resolveViewportSize(physicalWidth, physicalHeight, mode)
        val baseWidth = mode.fixedBaseWidth ?: viewportSize.width
        val baseHeight = mode.fixedBaseHeight ?: viewportSize.height
        val effectiveScale = renderScale.takeIf(Float::isFinite)?.coerceIn(minimumScale(baseWidth, baseHeight), 1f) ?: 1f
        return VirtualResolution(
            width = (baseWidth * effectiveScale).toInt().coerceAtLeast(1),
            height = (baseHeight * effectiveScale).toInt().coerceAtLeast(1),
            effectiveScale = effectiveScale
        )
    }

    internal fun minimumScale(width: Int, height: Int): Float =
        maxOf(.1f, MIN_GAME_WIDTH.toFloat() / width, MIN_GAME_HEIGHT.toFloat() / height).coerceAtMost(1f)

    @JvmStatic
    fun resolveViewportSize(
        availableWidth: Int,
        availableHeight: Int,
        mode: VirtualResolutionMode = VirtualResolutionMode.FULLSCREEN_FILL
    ): ViewportSize {
        val safeAvailableWidth = availableWidth.coerceAtLeast(1)
        val safeAvailableHeight = availableHeight.coerceAtLeast(1)
        val targetAspectRatio = mode.targetAspectRatio ?: return ViewportSize(
            width = safeAvailableWidth,
            height = safeAvailableHeight
        )

        var width = safeAvailableWidth
        var height = (width / targetAspectRatio).toInt().coerceAtLeast(1)
        if (height > safeAvailableHeight) {
            height = safeAvailableHeight
            width = (height * targetAspectRatio).toInt().coerceAtLeast(1)
        }
        return ViewportSize(
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1)
        )
    }

    internal fun fitViewport(
        region: DisplayPixelRect,
        virtualWidth: Int,
        virtualHeight: Int,
        ignorePixelRounding: Boolean = false,
    ): DisplayPixelRect {
        val safeWidth = virtualWidth.coerceAtLeast(1)
        val safeHeight = virtualHeight.coerceAtLeast(1)
        // A uniform scale rounded to integer buffer dimensions can differ by less than one
        // source pixel per axis. Do not letterbox an explicit region for that alone.
        if (ignorePixelRounding &&
            abs(safeWidth.toLong() * region.height - safeHeight.toLong() * region.width) <
                maxOf(region.width, region.height)
        ) return region
        val scale = minOf(region.width.toFloat() / safeWidth, region.height.toFloat() / safeHeight)
        val width = (safeWidth * scale).toInt().coerceAtLeast(1)
        val height = (safeHeight * scale).toInt().coerceAtLeast(1)
        val left = region.left + (region.width - width).coerceAtLeast(0) / 2
        val top = region.top + (region.height - height).coerceAtLeast(0) / 2
        return DisplayPixelRect(left, top, left + width, top + height)
    }

    internal fun previewViewport(region: DisplayPixelRect, renderScale: Float, mode: VirtualResolutionMode): DisplayPixelRect {
        val resolution = resolve(region.width, region.height, renderScale, mode)
        return fitViewport(region, resolution.width, resolution.height)
    }
}

data class VirtualResolution(
    val width: Int,
    val height: Int,
    val effectiveScale: Float
)

data class ViewportSize(
    val width: Int,
    val height: Int
)

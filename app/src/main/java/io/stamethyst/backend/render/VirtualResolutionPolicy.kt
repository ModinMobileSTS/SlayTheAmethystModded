package io.stamethyst.backend.render

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
        val requestedScale = renderScale.coerceIn(0.1f, 1.0f)
        val minScaleForWidth = MIN_GAME_WIDTH.toFloat() / baseWidth.toFloat()
        val minScaleForHeight = MIN_GAME_HEIGHT.toFloat() / baseHeight.toFloat()
        val effectiveScale = maxOf(requestedScale, minScaleForWidth, minScaleForHeight)
            .coerceIn(0.1f, 1.0f)
        return VirtualResolution(
            width = (baseWidth * effectiveScale).toInt().coerceAtLeast(1),
            height = (baseHeight * effectiveScale).toInt().coerceAtLeast(1),
            effectiveScale = effectiveScale
        )
    }

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

package io.stamethyst.backend.render

object VirtualResolutionPolicy {
    const val MIN_GAME_WIDTH = 800
    const val MIN_GAME_HEIGHT = 450

    @JvmStatic
    fun resolve(physicalWidth: Int, physicalHeight: Int, renderScale: Float): VirtualResolution {
        val safePhysicalWidth = physicalWidth.coerceAtLeast(1)
        val safePhysicalHeight = physicalHeight.coerceAtLeast(1)
        val requestedScale = renderScale.coerceIn(0.1f, 1.0f)
        val minScaleForWidth = MIN_GAME_WIDTH.toFloat() / safePhysicalWidth.toFloat()
        val minScaleForHeight = MIN_GAME_HEIGHT.toFloat() / safePhysicalHeight.toFloat()
        val effectiveScale = maxOf(requestedScale, minScaleForWidth, minScaleForHeight)
            .coerceIn(0.1f, 1.0f)
        return VirtualResolution(
            width = (safePhysicalWidth * effectiveScale).toInt().coerceAtLeast(1),
            height = (safePhysicalHeight * effectiveScale).toInt().coerceAtLeast(1),
            effectiveScale = effectiveScale
        )
    }
}

data class VirtualResolution(
    val width: Int,
    val height: Int,
    val effectiveScale: Float
)

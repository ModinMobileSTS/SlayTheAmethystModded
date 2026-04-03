package io.stamethyst.backend.render

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualResolutionPolicyTest {
    @Test
    fun resolve_fullscreenFill_usesAvailablePhysicalSize() {
        val resolution = VirtualResolutionPolicy.resolve(
            physicalWidth = 2400,
            physicalHeight = 1080,
            renderScale = 1.0f,
            mode = VirtualResolutionMode.FULLSCREEN_FILL
        )

        assertEquals(2400, resolution.width)
        assertEquals(1080, resolution.height)
        assertEquals(1.0f, resolution.effectiveScale)
    }

    @Test
    fun resolveViewportSize_ratio4By3_fitsWithinWideScreen() {
        val viewport = VirtualResolutionPolicy.resolveViewportSize(
            availableWidth = 2400,
            availableHeight = 1080,
            mode = VirtualResolutionMode.RATIO_4_3
        )

        assertEquals(1440, viewport.width)
        assertEquals(1080, viewport.height)
    }

    @Test
    fun resolve_ratio16By9_usesFittedViewportBeforeRenderScale() {
        val resolution = VirtualResolutionPolicy.resolve(
            physicalWidth = 2400,
            physicalHeight = 1080,
            renderScale = 0.5f,
            mode = VirtualResolutionMode.RATIO_16_9
        )

        assertEquals(960, resolution.width)
        assertEquals(540, resolution.height)
        assertEquals(0.5f, resolution.effectiveScale)
    }

    @Test
    fun resolve_1080p_usesFixedBaseResolution() {
        val resolution = VirtualResolutionPolicy.resolve(
            physicalWidth = 1440,
            physicalHeight = 1080,
            renderScale = 1.0f,
            mode = VirtualResolutionMode.RESOLUTION_1080P
        )

        assertEquals(1920, resolution.width)
        assertEquals(1080, resolution.height)
        assertEquals(1.0f, resolution.effectiveScale)
    }
}

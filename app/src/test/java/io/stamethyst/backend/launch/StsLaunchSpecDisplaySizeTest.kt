package io.stamethyst.backend.launch

import io.stamethyst.backend.render.FullscreenCanvasSize
import org.junit.Assert.assertEquals
import org.junit.Test

class StsLaunchSpecDisplaySizeTest {
    @Test
    fun resolveLaunchVirtualSize_prefersCroppedBridgeViewport() {
        assertEquals(
            FullscreenCanvasSize(width = 2304, height = 1080),
            StsLaunchSpec.resolveLaunchVirtualSize(
                bridgeWidth = 2304,
                bridgeHeight = 1080,
                fallbackWidth = 2400,
                fallbackHeight = 1080
            )
        )
    }

    @Test
    fun resolveLaunchVirtualSize_usesFullscreenFallbackUntilBridgeIsReady() {
        assertEquals(
            FullscreenCanvasSize(width = 2400, height = 1080),
            StsLaunchSpec.resolveLaunchVirtualSize(
                bridgeWidth = 0,
                bridgeHeight = 0,
                fallbackWidth = 2400,
                fallbackHeight = 1080
            )
        )
    }
}

package io.stamethyst.input

import org.junit.Assert.assertEquals
import org.junit.Test

class StsInputCoordMapperTest {
    @Test
    fun glfwCursorYKeepsTopLeftOrigin() {
        assertEquals(0f, glfwCursorYFromMappedViewY(0f, 1080), 0.0001f)
        assertEquals(240f, glfwCursorYFromMappedViewY(240f, 1080), 0.0001f)
        assertEquals(1079f, glfwCursorYFromMappedViewY(1079f, 1080), 0.0001f)
    }

    @Test
    fun glfwCursorYClampsToWindow() {
        assertEquals(0f, glfwCursorYFromMappedViewY(-30f, 1080), 0.0001f)
        assertEquals(1079f, glfwCursorYFromMappedViewY(1200f, 1080), 0.0001f)
    }

    @Test
    fun mapViewToWindowCoords_invertsFreeformLetterboxIntoFixedCanvas() {
        val bounds = resolveContentBounds(
            rawViewWidth = 1728,
            rawViewHeight = 1080,
            windowWidthRaw = 2400,
            windowHeightRaw = 1080
        )

        assertEquals(0f, bounds.left, 0.0001f)
        assertEquals(151f, bounds.top, 0.0001f)
        assertEquals(1728f, bounds.width, 0.0001f)
        assertEquals(778f, bounds.height, 0.0001f)
        assertEquals(
            WindowCoords(x = 1200f, y = 540f),
            mapViewToWindowCoords(
                viewX = 864f,
                viewY = 540f,
                rawViewWidth = 1728,
                rawViewHeight = 1080,
                windowWidthRaw = 2400,
                windowHeightRaw = 1080
            )
        )
        assertEquals(
            0f,
            mapViewToWindowCoords(
                viewX = 864f,
                viewY = 151f,
                rawViewWidth = 1728,
                rawViewHeight = 1080,
                windowWidthRaw = 2400,
                windowHeightRaw = 1080
            ).y,
            0.0001f
        )
        assertEquals(
            1079f,
            mapViewToWindowCoords(
                viewX = 864f,
                viewY = 929f,
                rawViewWidth = 1728,
                rawViewHeight = 1080,
                windowWidthRaw = 2400,
                windowHeightRaw = 1080
            ).y,
            0.0001f
        )
    }
}

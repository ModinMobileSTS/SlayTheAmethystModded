package io.stamethyst.backend.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameDisplayRegionTest {
    private val region = GameDisplayRegion(.1f, .1f, .9f, .9f)

    @Test fun eachCornerChangesOnlyItsTwoEdges() {
        assertEquals(GameDisplayRegion(.2f, .3f, .9f, .9f),
            region.moveCorner(DisplayRegionCorner.TOP_LEFT, .2f, .3f))
        assertEquals(GameDisplayRegion(.1f, .2f, .7f, .9f),
            region.moveCorner(DisplayRegionCorner.TOP_RIGHT, .7f, .2f))
        assertEquals(GameDisplayRegion(.2f, .1f, .9f, .7f),
            region.moveCorner(DisplayRegionCorner.BOTTOM_LEFT, .2f, .7f))
        assertEquals(GameDisplayRegion(.1f, .1f, .7f, .8f),
            region.moveCorner(DisplayRegionCorner.BOTTOM_RIGHT, .7f, .8f))
    }

    @Test fun draggingCannotCrossOrLeaveDisplay() {
        val crossed = region.moveCorner(DisplayRegionCorner.TOP_LEFT, 2f, 2f)
        assertEquals(.5f, crossed.width, .00001f)
        assertEquals(.5f, crossed.height, .00001f)
        assertEquals(GameDisplayRegion(0f, 0f, .9f, .9f),
            region.moveCorner(DisplayRegionCorner.TOP_LEFT, -1f, -1f))
        assertEquals(GameDisplayRegion(.1f, .1f, 1f, 1f),
            region.moveCorner(DisplayRegionCorner.BOTTOM_RIGHT, 2f, 2f))
    }

    @Test fun malformedValuesNeverProduceEmptyOrNonFiniteGeometry() {
        assertNull(GameDisplayRegion.fromPersistedValue("NaN,0,1,1"))
        assertNull(GameDisplayRegion.fromPersistedValue("0,1,2"))
        assertNull(GameDisplayRegion.fromPersistedValue("not a region"))
        assertEquals(GameDisplayRegion.FULLSCREEN,
            GameDisplayRegion(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 1f).normalized())
        val clamped = GameDisplayRegion(1f, 1f, -1f, -1f).normalized()
        assertEquals(.5f, clamped.width, .00001f)
        assertEquals(.5f, clamped.height, .00001f)
    }

    @Test fun savedCoordinatesRoundTripAndMatchExactPixelBounds() {
        assertEquals(region, GameDisplayRegion.fromPersistedValue(region.persistedValue()))
        assertEquals(DisplayPixelRect(240, 108, 2160, 972), region.toPixels(2400, 1080))
        assertEquals(DisplayPixelRect(0, 0, 1, 1), region.toPixels(0, 0))
    }

    @Test fun reverseLandscapeMirrorsBothOriginAndFarEdges() {
        val original = GameDisplayRegion(.05f, .1f, .9f, .8f)
        val flipped = original.rotated180()
        assertEquals(.1f, flipped.left, .00001f)
        assertEquals(.2f, flipped.top, .00001f)
        assertEquals(.95f, flipped.right, .00001f)
        assertEquals(.9f, flipped.bottom, .00001f)
        assertEquals(original.left, flipped.rotated180().left, .00001f)
    }

    @Test fun portraitCameraRotatesToLeftInCanonicalLandscape() {
        val camera = DisplayPixelRect(480, 0, 600, 80)
        assertEquals(1, DisplayGeometryRotation.canonicalLandscapeRotation(1080, 2400, 0))
        assertEquals(DisplayPixelRect(0, 480, 80, 600),
            DisplayGeometryRotation.rotateRect(camera, 1080, 2400, 1))
        assertEquals(camera, DisplayGeometryRotation.rotateRect(
            DisplayGeometryRotation.rotateRect(camera, 1080, 2400, 1), 2400, 1080, 3))
    }

    @Test fun allRotationsRespectNaturalPortraitAndLandscapePanels() {
        assertEquals(1, DisplayGeometryRotation.canonicalLandscapeRotation(2400, 1080, 3))
        assertEquals(0, DisplayGeometryRotation.canonicalLandscapeRotation(2400, 1080, 0))
        assertEquals(0, DisplayGeometryRotation.canonicalLandscapeRotation(1080, 2400, 1))
        val camera = DisplayPixelRect(0, 480, 80, 600)
        assertEquals(DisplayPixelRect(2320, 480, 2400, 600),
            DisplayGeometryRotation.rotateRect(camera, 2400, 1080, 2))
    }
}

package io.stamethyst.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModDragAutoScrollTest {
    private fun delta(y: Float, x: Float = 100f, bottom: Float = 1000f, density: Float = 1f) =
        modDragAutoScrollDelta(x, y, 0f, 0f, 200f, bottom, density)

    @Test fun edgesScrollInBothDirectionsAndAccelerateTowardBoundary() {
        assertTrue(delta(0f) < delta(50f))
        assertTrue(delta(50f) < 0f)
        assertTrue(delta(950f) > 0f)
        assertTrue(delta(1000f) > delta(950f))
        assertEquals(-delta(0f), delta(1000f), 0.001f)
    }

    @Test fun centerAndHorizontalExitStopImmediately() {
        assertEquals(0f, delta(500f), 0f)
        assertEquals(0f, delta(0f, x = -1f), 0f)
        assertEquals(0f, delta(1000f, x = 201f), 0f)
    }

    @Test fun outsideVerticalBoundaryIsCappedAndShortViewportHasNeutralCenter() {
        assertEquals(delta(0f), delta(-500f), 0f)
        assertEquals(delta(1000f), delta(1500f), 0f)
        assertEquals(0f, delta(30f, bottom = 60f), 0f)
        assertEquals(0f, delta(0f, bottom = 0f), 0f)
    }

    @Test fun speedAndTriggerScaleWithDensity() {
        assertEquals(2f * delta(50f), delta(100f, bottom = 2000f, density = 2f), 0.001f)
    }
}

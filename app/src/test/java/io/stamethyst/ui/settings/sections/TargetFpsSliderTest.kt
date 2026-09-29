package io.stamethyst.ui.settings.sections

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetFpsSliderTest {
    @Test
    fun sliderEndpointsMapToMinimumAndUnlimited() {
        assertEquals(5, targetFpsFromSliderValue(0f))
        assertEquals(0, targetFpsFromSliderValue(240f))
        assertEquals(240f, targetFpsToSliderValue(0f))
        assertEquals(235f, targetFpsToSliderValue(240f))
    }

    @Test
    fun sliderValuesUseFiveFpsSteps() {
        assertEquals(60, targetFpsFromSliderValue(55f))
        assertEquals(90, targetFpsFromSliderValue(85f))
        assertEquals(180f, targetFpsToSliderValue(185f))
    }

    @Test
    fun recommendationIsOneAndHalfTimesRefreshRateRoundedToFiveAndBounded() {
        assertEquals(90, recommendTargetFps(60f))
        assertEquals(120, recommendTargetFps(80f))
        assertEquals(240, recommendTargetFps(165f))
    }
}

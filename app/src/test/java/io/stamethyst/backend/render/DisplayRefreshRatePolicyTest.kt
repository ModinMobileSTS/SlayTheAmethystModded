package io.stamethyst.backend.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayRefreshRatePolicyTest {
    @Test fun oppoDuplicateHighRateModesMustKeepTheCurrentResolution() {
        val modes = listOf(
            mode(1, 1216, 2640, 120.00001f), mode(2, 1216, 2640, 90f), mode(3, 1216, 2640, 60f),
            mode(4, 1080, 2344, 120.00001f), mode(5, 1080, 2344, 90f), mode(6, 1080, 2344, 60f),
        )
        // This is the original user's advertised mode order. A global max incorrectly picks 1.
        assertEquals(1, modes.maxByOrNull { it.rateHz }?.id)
        assertEquals(4, DisplayRefreshRatePolicy.highestModeAtCurrentResolution(modes.last(), modes)?.id)
    }

    @Test fun fasterModeAtAnotherResolutionDoesNotCauseAResolutionChange() {
        val current = mode(2, 1080, 2344, 60f)
        val modes = listOf(mode(1, 1216, 2640, 144f), current, mode(3, 1080, 2344, 90f))
        assertEquals(3, DisplayRefreshRatePolicy.highestModeAtCurrentResolution(current, modes)?.id)
    }

    @Test fun unavailableCurrentOrMatchingModesNeverFallBackToAnotherResolution() {
        val modes = listOf(mode(1, 1216, 2640, 120f))
        assertNull(DisplayRefreshRatePolicy.highestModeAtCurrentResolution(null, modes))
        assertNull(DisplayRefreshRatePolicy.highestModeAtCurrentResolution(mode(6, 1080, 2344, 60f), modes))
        assertNull(DisplayRefreshRatePolicy.highestModeAtCurrentResolution(mode(0, 0, 0, 60f), modes))
    }

    @Test fun invalidRatesAndModeIdsAreIgnoredWithoutRoundingFractionalRates() {
        val current = mode(6, 1080, 2344, 60f)
        val modes = listOf(current, mode(1, 1080, 2344, Float.NaN),
            mode(2, 1080, 2344, Float.POSITIVE_INFINITY), mode(3, 1080, 2344, 0f),
            mode(4, 1080, 2344, -120f), mode(0, 1080, 2344, 144f), mode(7, 1080, 2344, 119.88f))
        assertEquals(mode(7, 1080, 2344, 119.88f),
            DisplayRefreshRatePolicy.highestModeAtCurrentResolution(current, modes))
    }

    @Test fun equallyFastCurrentModeIsRetainedRatherThanSwitchingOtherModeProperties() {
        val current = mode(9, 1080, 2344, 120f)
        assertEquals(current, DisplayRefreshRatePolicy.highestModeAtCurrentResolution(current,
            listOf(mode(4, 1080, 2344, 120f), current)))
    }

    @Test fun emptyModesDoNotInventASupportedMode() {
        assertNull(DisplayRefreshRatePolicy.highestModeAtCurrentResolution(mode(6, 1080, 2344, 60f), emptyList()))
    }

    @Test
    fun mismatchIsShownOnlyWhenActualRateIsLowerThanRequested() {
        assertTrue(
            DisplayRefreshRatePolicy.Snapshot(
                requestedRefreshRateHz = 120f,
                actualRefreshRateHz = 60f,
            ).shouldShowMismatch
        )
        assertFalse(
            DisplayRefreshRatePolicy.Snapshot(
                requestedRefreshRateHz = 120f,
                actualRefreshRateHz = 120f,
            ).shouldShowMismatch
        )
        assertFalse(
            DisplayRefreshRatePolicy.Snapshot(
                requestedRefreshRateHz = 0f,
                actualRefreshRateHz = 60f,
            ).shouldShowMismatch
        )
    }

    private fun mode(id: Int, width: Int, height: Int, rate: Float) =
        DisplayRefreshRatePolicy.ModeCandidate(id, width, height, rate)
}

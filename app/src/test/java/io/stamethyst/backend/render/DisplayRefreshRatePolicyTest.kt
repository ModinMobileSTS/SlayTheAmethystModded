package io.stamethyst.backend.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayRefreshRatePolicyTest {
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
}

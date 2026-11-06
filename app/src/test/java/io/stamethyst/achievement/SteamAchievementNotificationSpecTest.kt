package io.stamethyst.achievement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamAchievementNotificationSpecTest {
    private val spec = SteamAchievementNotificationSpec

    @Test
    fun desktopDefaultsAreNotTheOldTenSecondBouncyToast() {
        assertEquals(283, spec.WIDTH_DP)
        assertEquals(70, spec.HEIGHT_DP)
        assertEquals(3, spec.MAX_VISIBLE)
        assertEquals(300L, spec.ENTER_MS)
        assertEquals(5_000L, spec.HOLD_MS)
        assertEquals(300L, spec.EXIT_MS)
        assertEquals(5_600L, spec.TOTAL_MS)
    }

    @Test
    fun bottomRightMotionMatchesSteamStackIncludingTwoHeightTravel() {
        assertEquals(140f, spec.translationY(0, 70))
        assertEquals(70f, spec.translationY(150, 70))
        assertEquals(0f, spec.translationY(300, 70))
        assertEquals(0f, spec.translationY(5_299, 70))
        assertEquals(0f, spec.translationY(5_300, 70))
        assertEquals(70f, spec.translationY(5_450, 70))
        assertEquals(140f, spec.translationY(5_600, 70))
    }

    @Test
    fun motionIsMonotonicWithoutBounceAtFractionalDensity() {
        for (height in listOf(70, 123, 184, 210)) {
            val enter = (0L..300L).map { spec.translationY(it, height) }
            val exit = (5_300L..5_600L).map { spec.translationY(it, height) }
            assertTrue(enter.zipWithNext().all { (before, after) -> after <= before })
            assertTrue(exit.zipWithNext().all { (before, after) -> after >= before })
            assertTrue((enter + exit).all { it in 0f..(height * 2f) })
        }
    }

    @Test
    fun onlyAchievementsBelowTenPercentGetTheOriginalRareGlow() {
        assertTrue(spec.isRare(0.0))
        assertTrue(spec.isRare(9.999))
        assertFalse(spec.isRare(10.0))
        assertFalse(spec.isRare(80.0))
        assertFalse(spec.isRare(null))
        assertFalse(spec.isRare(Double.NaN))
        assertFalse(spec.isRare(-1.0))
    }

    @Test
    fun simultaneousUnlocksDoNotMixTheSoundWithItself() {
        assertTrue(spec.canPlaySound(1_000, null))
        assertFalse(spec.canPlaySound(1_000, 1_000))
        assertFalse(spec.canPlaySound(1_049, 1_000))
        assertTrue(spec.canPlaySound(1_050, 1_000))
    }
}

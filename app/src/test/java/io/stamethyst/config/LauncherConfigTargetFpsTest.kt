package io.stamethyst.config

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherConfigTargetFpsTest {
    @Test
    fun defaultTargetFps_isASelectableFixedCap() {
        assertEquals(60, LauncherConfig.DEFAULT_TARGET_FPS)
    }

    @Test
    fun targetFpsOptions_includeEveryFiveFpsAndUnlimitedEndpoint() {
        assertArrayEquals(
            intArrayOf(0) + (5..240 step 5).toList().toIntArray(),
            LauncherConfig.TARGET_FPS_OPTIONS
        )
    }

    @Test
    fun nonRecommendedTargetFpsOptions_matchTheSelectableChoices() {
        assertArrayEquals(
            LauncherConfig.TARGET_FPS_OPTIONS,
            LauncherConfig.NON_RECOMMENDED_TARGET_FPS_OPTIONS
        )
    }

    @Test
    fun normalizeTargetFps_acceptsSupportedFpsValues() {
        assertEquals(LauncherConfig.UNLIMITED_TARGET_FPS, LauncherConfig.normalizeTargetFps(0))
        assertEquals(5, LauncherConfig.normalizeTargetFps(5))
        assertEquals(235, LauncherConfig.normalizeTargetFps(235))
        assertEquals(240, LauncherConfig.normalizeTargetFps(240))
        assertEquals(90, LauncherConfig.normalizeTargetFps(90))
    }

    @Test
    fun normalizeTargetFpsFloat_preservesUnlimitedEndpoint() {
        assertEquals(0f, LauncherConfig.normalizeTargetFps(0f), 0f)
    }

    @Test
    fun normalizeTargetFps_stillFallsBackToDefaultForUnsupportedValues() {
        assertEquals(5, LauncherConfig.normalizeTargetFps(4))
        assertEquals(25, LauncherConfig.normalizeTargetFps(24))
        assertEquals(LauncherConfig.DEFAULT_TARGET_FPS, LauncherConfig.normalizeTargetFps(241))
        assertEquals(90, LauncherConfig.normalizeTargetFps(91))
    }
}

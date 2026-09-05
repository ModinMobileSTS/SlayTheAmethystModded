package io.stamethyst.config

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherConfigTargetFpsTest {
    @Test
    fun defaultTargetFps_is144FpsAutomaticCeiling() {
        assertEquals(144, LauncherConfig.DEFAULT_TARGET_FPS)
    }

    @Test
    fun targetFpsOptions_include90Fps() {
        assertArrayEquals(
            intArrayOf(24, 30, 60, 90, 120, 144),
            LauncherConfig.TARGET_FPS_OPTIONS
        )
    }

    @Test
    fun nonRecommendedTargetFpsOptions_preserveTheLegacy240FpsChoice() {
        assertArrayEquals(
            intArrayOf(24, 30, 60, 90, 120, 144, 240),
            LauncherConfig.NON_RECOMMENDED_TARGET_FPS_OPTIONS
        )
    }

    @Test
    fun normalizeTargetFps_acceptsSupportedFpsValues() {
        assertEquals(24, LauncherConfig.normalizeTargetFps(24))
        assertEquals(30, LauncherConfig.normalizeTargetFps(30))
        assertEquals(90, LauncherConfig.normalizeTargetFps(90))
    }

    @Test
    fun normalizeTargetFps_stillFallsBackToDefaultForUnsupportedValues() {
        assertEquals(LauncherConfig.DEFAULT_TARGET_FPS, LauncherConfig.normalizeTargetFps(25))
        assertEquals(LauncherConfig.DEFAULT_TARGET_FPS, LauncherConfig.normalizeTargetFps(59))
    }
}

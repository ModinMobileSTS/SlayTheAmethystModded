package io.stamethyst.config

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherConfigTargetFpsTest {
    @Test
    fun targetFpsOptions_include24And30Fps() {
        assertArrayEquals(
            intArrayOf(24, 30, 60, 120, 240),
            LauncherConfig.TARGET_FPS_OPTIONS
        )
    }

    @Test
    fun normalizeTargetFps_accepts24And30Fps() {
        assertEquals(24, LauncherConfig.normalizeTargetFps(24))
        assertEquals(30, LauncherConfig.normalizeTargetFps(30))
    }

    @Test
    fun normalizeTargetFps_stillFallsBackToDefaultForUnsupportedValues() {
        assertEquals(LauncherConfig.DEFAULT_TARGET_FPS, LauncherConfig.normalizeTargetFps(25))
        assertEquals(LauncherConfig.DEFAULT_TARGET_FPS, LauncherConfig.normalizeTargetFps(59))
    }
}

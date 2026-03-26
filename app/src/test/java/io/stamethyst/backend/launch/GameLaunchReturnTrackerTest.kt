package io.stamethyst.backend.launch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLaunchReturnTrackerTest {
    @Test
    fun isTrackedGameProcess_matchesOnlyLiveGameProcessName() {
        assertTrue(
            GameLaunchReturnTracker.isTrackedGameProcess(
                processName = "io.stamethyst:game",
                packageName = "io.stamethyst",
                pid = 42,
                importance = 100
            )
        )
        assertFalse(
            GameLaunchReturnTracker.isTrackedGameProcess(
                processName = "io.stamethyst",
                packageName = "io.stamethyst",
                pid = 42,
                importance = 100
            )
        )
        assertFalse(
            GameLaunchReturnTracker.isTrackedGameProcess(
                processName = "io.stamethyst:game",
                packageName = "io.stamethyst",
                pid = 42,
                importance = 400
            )
        )
    }

    @Test
    fun isTrackedGameProcessAlive_returnsTrue_forForegroundOrServiceLikeImportance() {
        assertTrue(GameLaunchReturnTracker.isTrackedGameProcessAlive(pid = 42, importance = 100))
        assertTrue(GameLaunchReturnTracker.isTrackedGameProcessAlive(pid = 42, importance = 300))
    }

    @Test
    fun isTrackedGameProcessAlive_returnsFalse_forCachedOrInvalidProcesses() {
        assertFalse(GameLaunchReturnTracker.isTrackedGameProcessAlive(pid = 42, importance = 400))
        assertFalse(GameLaunchReturnTracker.isTrackedGameProcessAlive(pid = 42, importance = 500))
        assertFalse(GameLaunchReturnTracker.isTrackedGameProcessAlive(pid = 0, importance = 100))
    }

    @Test
    fun isTrackedGameProcessAlive_canIncludeCachedProcesses_forForcedCleanup() {
        assertTrue(
            GameLaunchReturnTracker.isTrackedGameProcessAlive(
                pid = 42,
                importance = 400,
                includeCached = true
            )
        )
        assertFalse(
            GameLaunchReturnTracker.isTrackedGameProcessAlive(
                pid = 0,
                importance = 400,
                includeCached = true
            )
        )
    }
}

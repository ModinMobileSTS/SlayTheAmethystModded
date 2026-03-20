package io.stamethyst.backend.render

import android.app.GameManager
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidGameModeSupportTest {
    @Test
    fun resolveTargetFps_keepsRequestedValueInBatteryMode() {
        val snapshot = AndroidGameModeSnapshot(
            rawMode = GameManager.GAME_MODE_BATTERY,
            displayName = "BATTERY",
            description = "",
            supported = true
        )

        assertEquals(120, AndroidGameModeSupport.resolveTargetFps(120, snapshot))
        assertEquals(60, AndroidGameModeSupport.resolveTargetFps(60, snapshot))
    }

    @Test
    fun resolveTargetFps_keepsRequestedValueForPerformanceMode() {
        val snapshot = AndroidGameModeSnapshot(
            rawMode = GameManager.GAME_MODE_PERFORMANCE,
            displayName = "PERFORMANCE",
            description = "",
            supported = true
        )

        assertEquals(120, AndroidGameModeSupport.resolveTargetFps(120, snapshot))
    }
}

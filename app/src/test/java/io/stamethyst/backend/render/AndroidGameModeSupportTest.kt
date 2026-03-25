package io.stamethyst.backend.render

import android.app.GameManager
import io.stamethyst.R
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidGameModeSupportTest {
    @Test
    fun resolveTargetFps_capsRequestedValueInBatteryMode() {
        val snapshot = AndroidGameModeSnapshot(
            rawMode = GameManager.GAME_MODE_BATTERY,
            displayNameResId = R.string.settings_game_mode_name_battery,
            descriptionResId = R.string.settings_game_mode_desc_battery,
            supported = true
        )

        assertEquals(30, AndroidGameModeSupport.resolveTargetFps(120, snapshot))
        assertEquals(24, AndroidGameModeSupport.resolveTargetFps(24, snapshot))
    }

    @Test
    fun resolveTargetFps_keepsRequestedValueForPerformanceMode() {
        val snapshot = AndroidGameModeSnapshot(
            rawMode = GameManager.GAME_MODE_PERFORMANCE,
            displayNameResId = R.string.settings_game_mode_name_performance,
            descriptionResId = R.string.settings_game_mode_desc_performance,
            supported = true
        )

        assertEquals(120, AndroidGameModeSupport.resolveTargetFps(120, snapshot))
    }

    @Test
    fun resolveRenderScale_capsRequestedValueInBatteryMode() {
        val snapshot = AndroidGameModeSnapshot(
            rawMode = GameManager.GAME_MODE_BATTERY,
            displayNameResId = R.string.settings_game_mode_name_battery,
            descriptionResId = R.string.settings_game_mode_desc_battery,
            supported = true
        )

        assertEquals(0.85f, AndroidGameModeSupport.resolveRenderScale(1.0f, snapshot))
        assertEquals(0.70f, AndroidGameModeSupport.resolveRenderScale(0.70f, snapshot))
    }
}

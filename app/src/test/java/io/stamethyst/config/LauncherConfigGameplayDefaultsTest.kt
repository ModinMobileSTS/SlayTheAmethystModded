package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherConfigGameplayDefaultsTest {
    @Test
    fun gameplayFontScale_defaultsToOneX() {
        assertEquals(1.00f, LauncherConfig.DEFAULT_GAMEPLAY_FONT_SCALE, 0.0f)
    }

    @Test
    fun biggerText_isDisabledByDefault() {
        assertEquals(
            LauncherConfig.MIN_GAMEPLAY_FONT_SCALE,
            LauncherConfig.DEFAULT_GAMEPLAY_FONT_SCALE,
            0.0f
        )
    }

    @Test
    fun largerUi_isDisabledByDefault() {
        assertFalse(LauncherConfig.DEFAULT_GAMEPLAY_LARGER_UI_ENABLED)
    }

    @Test
    fun steamCloudAutoLaunchAfterSync_isDisabledByDefault() {
        assertFalse(LauncherConfig.DEFAULT_STEAM_CLOUD_AUTO_LAUNCH_AFTER_SYNC_ENABLED)
    }

    @Test
    fun achievementUnlockNotification_isEnabledByDefault() {
        assertTrue(LauncherConfig.DEFAULT_ACHIEVEMENT_UNLOCK_NOTIFICATION_ENABLED)
    }

    @Test
    fun touchscreenInputMode_defaultsToHybrid() {
        assertEquals(
            TouchscreenInputMode.HYBRID,
            LauncherConfig.DEFAULT_TOUCHSCREEN_INPUT_MODE
        )
    }
}

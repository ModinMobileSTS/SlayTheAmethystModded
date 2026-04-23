package io.stamethyst.config

import org.junit.Assert.assertFalse
import org.junit.Test

class LauncherConfigSteamCloudAutoSyncDefaultsTest {
    @Test
    fun autoPullBeforeLaunch_isDisabledByDefault() {
        assertFalse(LauncherConfig.DEFAULT_STEAM_CLOUD_AUTO_PULL_BEFORE_LAUNCH_ENABLED)
    }

    @Test
    fun autoPushAfterCleanShutdown_isDisabledByDefault() {
        assertFalse(LauncherConfig.DEFAULT_STEAM_CLOUD_AUTO_PUSH_AFTER_CLEAN_SHUTDOWN_ENABLED)
    }
}

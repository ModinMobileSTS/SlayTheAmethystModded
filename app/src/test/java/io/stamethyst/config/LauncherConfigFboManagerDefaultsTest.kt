package io.stamethyst.config

import org.junit.Assert.assertFalse
import org.junit.Test

class LauncherConfigFboManagerDefaultsTest {
    @Test
    fun globalFboManager_defaultsDisabled() {
        assertFalse(LauncherConfig.DEFAULT_FBO_MANAGER_COMPAT_ENABLED)
    }

    @Test
    fun idleReclaim_defaultsDisabled() {
        assertFalse(LauncherConfig.DEFAULT_FBO_IDLE_RECLAIM_COMPAT_ENABLED)
    }

    @Test
    fun pressureDownscale_defaultsDisabled() {
        assertFalse(LauncherConfig.DEFAULT_FBO_PRESSURE_DOWNSCALE_COMPAT_ENABLED)
    }
}

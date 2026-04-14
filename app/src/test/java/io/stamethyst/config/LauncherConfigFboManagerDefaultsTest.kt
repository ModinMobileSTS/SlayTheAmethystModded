package io.stamethyst.config

import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherConfigFboManagerDefaultsTest {
    @Test
    fun globalFboManager_defaultsEnabled() {
        assertTrue(LauncherConfig.DEFAULT_FBO_MANAGER_COMPAT_ENABLED)
    }
}

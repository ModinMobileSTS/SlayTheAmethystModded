package io.stamethyst.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherConfigLogcatDefaultsTest {
    @Test
    fun gameLogcatCapture_isDisabledByDefault() {
        assertFalse(LauncherConfig.DEFAULT_LOGCAT_CAPTURE_ENABLED)
    }

    @Test
    fun launcherLogcatCapture_isEnabledByDefault() {
        assertTrue(LauncherConfig.DEFAULT_LAUNCHER_LOGCAT_CAPTURE_ENABLED)
    }
}

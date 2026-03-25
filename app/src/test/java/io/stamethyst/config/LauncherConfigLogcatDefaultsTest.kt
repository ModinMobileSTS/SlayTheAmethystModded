package io.stamethyst.config

import org.junit.Assert.assertFalse
import org.junit.Test

class LauncherConfigLogcatDefaultsTest {
    @Test
    fun logcatCapture_isDisabledByDefault() {
        assertFalse(LauncherConfig.DEFAULT_LOGCAT_CAPTURE_ENABLED)
    }
}

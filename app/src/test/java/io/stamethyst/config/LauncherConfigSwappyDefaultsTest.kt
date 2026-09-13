package io.stamethyst.config

import org.junit.Assert.assertFalse
import org.junit.Test

class LauncherConfigSwappyDefaultsTest {
    @Test
    fun swappyFramePacing_isDisabledByDefault() {
        assertFalse(LauncherConfig.DEFAULT_SWAPPY_FRAME_PACING_ENABLED)
    }
}

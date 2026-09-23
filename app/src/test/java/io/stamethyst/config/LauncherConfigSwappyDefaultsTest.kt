package io.stamethyst.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherConfigSwappyDefaultsTest {
    @Test
    fun swappyFramePacing_isDisabledByDefault() {
        assertFalse(LauncherConfig.DEFAULT_SWAPPY_FRAME_PACING_ENABLED)
    }

    @Test
    fun framePacingMode_defaultsToOff() {
        assertEquals(FramePacingMode.OFF, LauncherConfig.DEFAULT_FRAME_PACING_MODE)
    }
}

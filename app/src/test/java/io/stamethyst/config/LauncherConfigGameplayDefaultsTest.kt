package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}

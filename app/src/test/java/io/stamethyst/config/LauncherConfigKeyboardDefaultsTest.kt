package io.stamethyst.config

import org.junit.Assert.assertFalse
import org.junit.Test

class LauncherConfigKeyboardDefaultsTest {
    @Test
    fun autoPopupKeyboardIsDisabledByDefault() {
        assertFalse(LauncherConfig.DEFAULT_AUTO_POPUP_KEYBOARD_ENABLED)
    }
}

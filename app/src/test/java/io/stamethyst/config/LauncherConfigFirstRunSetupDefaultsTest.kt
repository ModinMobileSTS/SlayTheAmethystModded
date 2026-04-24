package io.stamethyst.config

import org.junit.Assert.assertFalse
import org.junit.Test

class LauncherConfigFirstRunSetupDefaultsTest {
    @Test
    fun firstRunSetup_isIncompleteByDefault() {
        assertFalse(LauncherConfig.DEFAULT_FIRST_RUN_SETUP_COMPLETED)
    }
}

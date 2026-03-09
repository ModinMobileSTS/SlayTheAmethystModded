package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherThemeModeTest {
    @Test
    fun fromPersistedValue_returnsMatchingMode() {
        assertEquals(
            LauncherThemeMode.FOLLOW_SYSTEM,
            LauncherThemeMode.fromPersistedValue("follow_system")
        )
        assertEquals(
            LauncherThemeMode.LIGHT,
            LauncherThemeMode.fromPersistedValue("light")
        )
        assertEquals(
            LauncherThemeMode.DARK,
            LauncherThemeMode.fromPersistedValue("dark")
        )
    }

    @Test
    fun fromPersistedValue_returnsNullForBlankOrUnknown() {
        assertNull(LauncherThemeMode.fromPersistedValue(null))
        assertNull(LauncherThemeMode.fromPersistedValue(""))
        assertNull(LauncherThemeMode.fromPersistedValue("unknown"))
    }
}

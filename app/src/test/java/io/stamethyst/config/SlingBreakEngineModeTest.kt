package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Test

class SlingBreakEngineModeTest {
    @Test
    fun missingOrUnknownMode_defaultsToWebView() {
        listOf(null, "", "unknown").forEach { stored ->
            assertEquals(SlingBreakEngineMode.WEBVIEW, SlingBreakEngineMode.fromPersistedValue(stored))
        }
    }

    @Test
    fun persistedModes_roundTrip() {
        SlingBreakEngineMode.entries.forEach { mode ->
            assertEquals(mode, SlingBreakEngineMode.fromPersistedValue(mode.persistedValue))
        }
    }

    @Test
    fun compatibility_isOnlyEnabledByItsExplicitPersistedValue() {
        assertEquals(SlingBreakEngineMode.COMPATIBILITY, SlingBreakEngineMode.fromPersistedValue("compatibility"))
        assertEquals(SlingBreakEngineMode.WEBVIEW, SlingBreakEngineMode.fromPersistedValue("webview"))
    }
}

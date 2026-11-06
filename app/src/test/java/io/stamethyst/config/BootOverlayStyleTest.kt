package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BootOverlayStyleTest {
    @Test
    fun retiredWebMinigameIsNotASelectableStyle() {
        assertNull(BootOverlayStyle.fromPersistedValue("sling_break"))
    }

    @Test
    fun godotMinigameHasItsOwnPersistedValue() {
        assertEquals(
            BootOverlayStyle.GODOT_SLING_BREAK,
            BootOverlayStyle.fromPersistedValue("godot_sling_break")
        )
    }

    @Test
    fun allSelectableStylesRoundTrip() {
        for (style in BootOverlayStyle.entries) {
            assertEquals(style, BootOverlayStyle.fromPersistedValue(style.persistedValue))
        }
    }
}

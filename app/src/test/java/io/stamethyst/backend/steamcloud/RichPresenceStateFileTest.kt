package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RichPresenceStateFileTest {
    @Test
    fun parsesBridgeEscapesWithoutCollapsingEscapedBackslashes() {
        assertEquals(
            mapOf(
                "status" to "Silent\\nKnight\nFloor=8",
                "steam_display" to "#Status",
            ),
            RichPresenceStateFile.parse(
                "status=Silent\\\\nKnight\\nFloor\\=8\nsteam_display=#Status\n",
            ),
        )
    }

    @Test
    fun ignoresMalformedLinesAndRejectsEmptyPayloads() {
        assertEquals(
            mapOf("status" to "Main menu", "steam_display" to "#Status"),
            RichPresenceStateFile.parse("invalid\nstatus=Main menu\nsteam_display=#Status"),
        )
        assertNull(RichPresenceStateFile.parse("status=Main menu"))
        assertNull(RichPresenceStateFile.parse("invalid\n=missing-key\n"))
    }
}

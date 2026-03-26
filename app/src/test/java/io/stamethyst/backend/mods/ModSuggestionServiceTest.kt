package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModSuggestionServiceTest {
    @Test
    fun hasContentChanged_treatsFirstFetchAsUpdated() {
        assertTrue(
            ModSuggestionService.hasContentChanged(
                existingRawJson = null,
                fetchedRawJson = "[]"
            )
        )
    }

    @Test
    fun hasContentChanged_ignoresLineEndingOnlyDifferences() {
        assertFalse(
            ModSuggestionService.hasContentChanged(
                existingRawJson = "[\r\n]\r\n",
                fetchedRawJson = "[\n]\n"
            )
        )
    }

    @Test
    fun parseSuggestionMap_supportsNotificationAndSuggestionFields() {
        val parsed = ModSuggestionService.parseSuggestionMap(
            """
            [
              {
                "modid": "image_io_compat",
                "notification": "Deprecated on mobile"
              },
              {
                "modId": "ExampleMod",
                "suggestion": "This mod is only an example."
              }
            ]
            """.trimIndent()
        )

        assertEquals(2, parsed.size)
        assertEquals("Deprecated on mobile", parsed["image_io_compat"])
        assertEquals("This mod is only an example.", parsed["examplemod"])
    }

    @Test
    fun parseSuggestionMap_toleratesTrailingComma() {
        val parsed = ModSuggestionService.parseSuggestionMap(
            """
            [
              {
                "modid": "ypp-rpc",
                "notification": "Open RPC settings if it does not work.",
              }
            ]
            """.trimIndent()
        )

        assertEquals(1, parsed.size)
        assertTrue(parsed.containsKey("ypp-rpc"))
        assertEquals(
            "Open RPC settings if it does not work.",
            parsed["ypp-rpc"]
        )
    }
}

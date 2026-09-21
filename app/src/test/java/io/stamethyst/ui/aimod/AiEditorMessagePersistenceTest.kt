package io.stamethyst.ui.aimod

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEditorMessagePersistenceTest {
    @Test
    fun failedAssistantMessage_persistsItsErrorMessage() {
        val stored = AiEditorSession(
            id = "session-1",
            messages = listOf(
                AiEditorMessage(id = 1, fromUser = true, text = "Inspect this mod"),
                AiEditorMessage(
                    id = 2,
                    fromUser = false,
                    text = "",
                    failed = true,
                    errorMessage = "Request timed out",
                ),
            ),
        )

        val encoded = Json.encodeToString(listOf(stored))
        val restored = Json.decodeFromString<List<AiEditorSession>>(encoded)

        assertTrue(encoded.contains("Request timed out"))
        assertEquals("Request timed out", restored.single().messages.last().errorMessage)
    }
}

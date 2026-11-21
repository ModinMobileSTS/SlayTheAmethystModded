package io.stamethyst.ui.workshop

import io.stamethyst.backend.llm.AgentToolExecutionEvent
import io.stamethyst.backend.llm.LlmReasoningEffort
import io.stamethyst.ui.aimod.AiMessagePartKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

class WorkshopSearchSessionsTest {
    @Test fun searchAndToolCallsKeepTheirOrderAndUpdateInPlace() {
        val search = AgentToolExecutionEvent("search", "search_workshop", """{"query":"map colours"}""")
        val secondSearch = search.copy(id = "alternate", arguments = """{"query":"colored map"}""")
        val read = AgentToolExecutionEvent("read", "read_workshop", """{"id":"123"}""")
        val pending = WorkshopTurn(1, "Map colours?")
            .withToolEvent(search)
            .withToolEvent(secondSearch)
            .withToolEvent(read)

        assertNull(pending.tools.first().result)
        val updated = pending
            .withToolEvent(search.copy(result = """{"candidates":[]}"""))
            .withToolEvent(secondSearch.copy(result = """{"candidates":[{"id":"123"}]}"""))
            .withToolEvent(read.copy(result = """{"error":"description_unavailable"}""", failed = true))

        assertEquals(listOf("search", "alternate", "read"), updated.tools.map { it.id })
        assertEquals(updated.tools.map { it.id }, updated.activityParts.map { it.toolId })
        assertTrue(updated.activityParts.all { it.kind == AiMessagePartKind.TOOL })
        assertEquals("""{"candidates":[]}""", updated.tools.first().result)
        assertFalse(updated.tools.first().failed)
        assertTrue(updated.tools.last().failed)
    }

    @Test fun newChatHasIndependentHistoryDraftAndModelContext() {
        val sessions = WorkshopSearchSessions(CoroutineScope(Dispatchers.Unconfined))
        val first = sessions.current
        first.turns += WorkshopTurn(1L, "Map colours?")
        first.draft = "On a touch screen?"
        first.modelOverride = "my-model"
        first.effortOverride = LlmReasoningEffort.HIGH
        sessions.newChat()
        val second = sessions.current
        assertNotEquals(first.sessionId, second.sessionId)
        assertTrue(second.turns.isEmpty())
        assertEquals("", second.draft)
        assertNull(second.modelOverride)
        assertNull(second.effortOverride)
        sessions.newChat() // Already in an empty conversation.
        assertSame(second, sessions.current)
        assertEquals(2, sessions.sessions.size)
        second.draft = "A different question"
        sessions.select(first)
        assertEquals("Map colours?", sessions.current.turns.single().question)
        assertEquals("On a touch screen?", sessions.current.draft)
        assertEquals("my-model", sessions.current.modelOverride)
        sessions.select(second)
        assertEquals("A different question", sessions.current.draft)
    }
}

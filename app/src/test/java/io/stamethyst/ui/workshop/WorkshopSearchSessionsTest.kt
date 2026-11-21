package io.stamethyst.ui.workshop

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import io.stamethyst.backend.llm.ASK_USER
import io.stamethyst.backend.llm.AgentQuestionDismissed
import io.stamethyst.backend.llm.AgentRequestControl
import io.stamethyst.backend.llm.AgentToolExecutionEvent
import io.stamethyst.backend.llm.AskUserToolTest
import io.stamethyst.backend.llm.LlmReasoningEffort
import io.stamethyst.backend.workshop.WorkshopAnswerBlock
import io.stamethyst.backend.workshop.WorkshopSearchTools
import io.stamethyst.ui.aimod.AiMessagePartKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WorkshopSearchSessionsTest {
    @Test fun searchTurnResumesAfterReplyButNotAfterSkip() = AskUserToolTest.withWorker { worker ->
        listOf(false, true).forEach { skip ->
            AgentRequestControl().use { control ->
                val calls = AtomicInteger()
                val model = object : ChatModel {
                    override fun doChat(request: ChatRequest): ChatResponse {
                        assertTrue(request.parameters().toolSpecifications().any { it.name() == ASK_USER })
                        val message = when (calls.incrementAndGet()) {
                            1 -> AiMessage.from(AskUserToolTest.askUserCall())
                            2 -> {
                                check(!skip)
                                val result = request.messages().filterIsInstance<ToolExecutionResultMessage>().single()
                                assertEquals("""{"answers":[["A"],["B"]]}""", result.text())
                                AiMessage.from("I will prioritize your choices.")
                            }
                            else -> error("Unexpected extra model turn")
                        }
                        return ChatResponse.builder().aiMessage(message).build()
                    }
                }
                val tools = WorkshopSearchTools({ error("No Steam access expected") },
                    { _, _ -> error("No Steam access expected") }, emptySet())
                val task = worker.submit<List<WorkshopAnswerBlock>> {
                    workshopSearchTurn("Find a mod", tools, control, model, null, emptyList(),
                        5_000, { true }, {}, {}, {})
                }
                val pending = AskUserToolTest.awaitQuestion(control)
                assertEquals(1, calls.get())
                if (skip) {
                    assertTrue(control.userQuestions.dismiss(pending.id))
                    val error = assertThrows(ExecutionException::class.java) { task.get(3, TimeUnit.SECONDS) }
                    assertTrue(error.cause is AgentQuestionDismissed)
                    assertEquals(1, calls.get())
                } else {
                    assertTrue(control.userQuestions.reply(pending.id, listOf(listOf("A"), listOf("B"))))
                    assertEquals(listOf(WorkshopAnswerBlock.Text("I will prioritize your choices.")), task.get(3, TimeUnit.SECONDS))
                    assertEquals(2, calls.get())
                }
            }
        }
    }

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

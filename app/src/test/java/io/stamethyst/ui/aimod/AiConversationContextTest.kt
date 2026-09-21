package io.stamethyst.ui.aimod

import io.stamethyst.backend.llm.AgentToolExecutionEvent
import io.stamethyst.backend.llm.AgentContextMessage
import io.stamethyst.backend.llm.AgentContextState
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiConversationContextTest {
    @get:Rule val temporary = TemporaryFolder(generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "AGENTS.md").isFile }.resolve("agent-tmp"))

    @Test fun legacyMessagesMigrateOnceWithExplicitIncompleteToolResults() {
        val session = AiEditorSession("one", messages = listOf(
            AiEditorMessage(1, true, "inspect", attachments = listOf(AiAttachment("note.txt", "exact attachment"))),
            AiEditorMessage(2, false, "done", tools = listOf(AiToolCall("call", "read", "{}", result = "preview", truncated = true))),
            AiEditorMessage(3, true, "continue"),
            AiEditorMessage(4, false, "", streaming = true),
        ))
        val migrated = session.modelContext(4)
        assertEquals(listOf("user", "assistant", "tool", "assistant", "user"), migrated.messages.map { it.role })
        assertTrue(migrated.messages.first().text.contains("exact attachment"))
        assertTrue(migrated.messages[2].text.contains("may be incomplete"))
        assertEquals(migrated, session.copy(context = migrated).modelContext(4))
    }

    @Test fun toolLeadTextIsConsumedAfterTheFirstToolCall() {
        val first = applyToolExecutionEvent(
            AiEditorMessage(2, false, "我先查看工作区当前状态。"),
            AgentToolExecutionEvent("one", "list_agent_workspace", "{}"),
        )
        val second = applyToolExecutionEvent(
            first,
            AgentToolExecutionEvent("two", "list_agent_patch_mods", "{}"),
        )

        assertEquals("", first.text)
        assertEquals("我先查看工作区当前状态。", first.tools.single().precedingText)
        assertEquals("", second.tools.last().precedingText)
    }

    @Test fun modelContextUsesDurableToolResultsRatherThanUiPreview() {
        val state = AgentContextState(messages = listOf(
            AgentContextMessage(1, "user", "goal"),
            AgentContextMessage(2, "assistant", "full completed reply"),
        ))
        val session = AiEditorSession("one", messages = listOf(
            AiEditorMessage(1, true, "goal"), AiEditorMessage(2, false, "UI preview"),
            AiEditorMessage(3, true, "continue"), AiEditorMessage(4, false, "", streaming = true),
        ), context = state)
        val restored = session.modelContext(4)
        assertEquals(state.messages, restored.messages.take(2))
        assertEquals("continue", restored.messages.last().text)
    }

    @Test fun delayedPollCannotRemoveNewUserMessageOrContextCheckpoint() {
        val old = AiEditorSession("one", revision = 1, messages = listOf(AiEditorMessage(1, true, "first")))
        val latest = old.copy(revision = 3, messages = old.messages + AiEditorMessage(2, true, "new"),
            context = AgentContextState(summary = "latest summary"))
        assertEquals(listOf(latest), mergeAiSessions(listOf(latest), listOf(old)))
        val fresh = latest.copy(revision = 4)
        assertEquals(listOf(fresh), mergeAiSessions(listOf(latest), listOf(fresh)))
    }

    @Test fun atomicSessionMutationsPreserveBackgroundChangesAndOtherSessions() {
        val file = temporary.newFile("conversations.json")
        file.delete()
        val ui = AiConversationStore(file)
        val worker = AiConversationStore(file)
        val first = AiEditorSession("one", messages = listOf(AiEditorMessage(1, true, "first")))
        val other = AiEditorSession("two", messages = listOf(AiEditorMessage(1, true, "other")))
        ui.mutate(first.id, first) { it }
        ui.mutate(other.id, other) { it }
        val stale = ui.load().first { it.id == "one" }
        worker.update("one") { it.copy(context = AgentContextState(summary = "worker checkpoint")) }
        ui.mutate("one") { it.copy(messages = it.messages + AiEditorMessage(2, true, "next")) }
        val stored = worker.load()
        assertEquals(2, stored.size)
        assertEquals("worker checkpoint", stored.first().context!!.summary)
        assertEquals(2, stored.first().messages.size)
        assertTrue(stored.first().revision > stale.revision)
        assertEquals(other.messages, stored.last().messages)
    }

    @Test fun concurrentUpdatesDoNotLoseMessages() {
        val file = File(temporary.root, "conversations.json")
        val store = AiConversationStore(file)
        store.mutate("one", AiEditorSession("one", listOf(AiEditorMessage(1, true, "goal")))) { it }
        val start = CountDownLatch(1)
        val workers = (1..2).map { worker -> thread {
            val ownStore = AiConversationStore(file)
            start.await()
            repeat(20) { index -> ownStore.update("one") { current ->
                current.copy(messages = current.messages + AiEditorMessage((worker * 100 + index).toLong(), false, "reply"))
            } }
        } }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(41, store.load().single().messages.size)
        assertEquals(41L, store.load().single().revision)
    }

    @Test fun persistedContextRoundTripsAndOldJsonStillLoads() {
        val oldJson = """[{"id":"old","messages":[{"id":1,"fromUser":true,"text":"goal"}]}]"""
        val old = Json.decodeFromString<List<AiEditorSession>>(oldJson).single()
        assertNull(old.context)
        assertEquals(0L, old.revision)
        val new = old.copy(context = AgentContextState(
            messages = listOf(AgentContextMessage(1, "user", "goal")),
            summary = "memory", summarizedCount = 1, activePatchId = "patch-alpha", compactionCount = 2,
        ))
        assertEquals(new, Json.decodeFromString<AiEditorSession>(Json.encodeToString(new)))
    }
}

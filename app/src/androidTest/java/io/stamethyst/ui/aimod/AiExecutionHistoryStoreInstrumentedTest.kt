package io.stamethyst.ui.aimod

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.backend.llm.AgentExecutionStep
import io.stamethyst.backend.llm.AgentStepKind
import io.stamethyst.backend.llm.AgentStepStatus
import io.stamethyst.backend.llm.agentErrorStep
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AiExecutionHistoryStoreInstrumentedTest {
    @Test fun versionSixUpgradePreservesMessagesAndMakesHistoryDurable() = withStore { root, store ->
        seed(store)
        val file = root.listFiles { file -> file.extension == "db" }!!.single()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE execution_steps")
            db.version = 6
        }
        assertEquals("Inspect", store.load("test")!!.messages.first().text)
        val running = step().copy(status = AgentStepStatus.RUNNING, elapsedMs = null)
        store.recordExecutionStep("test", 2, running)
        store.recordExecutionStep("test", 2, step())
        val restored = AiConversationStore(root).load("test")!!
        assertEquals(listOf(step()), restored.messages.last().executionSteps)
    }

    @Test fun streamUpdatesDoNotDeleteOrRewriteHistory() = withStore { root, store ->
        seed(store)
        store.recordExecutionStep("test", 2, step())
        val before = store.load("test")!!
        store.updateMessage("test", 2, includeExecutionSteps = false) { it.copy(text = "streamed text") }
        val after = store.loadChanges(before)!!
        assertEquals("streamed text", after.messages.last().text)
        assertEquals(listOf(step()), after.messages.last().executionSteps)
        assertSame(before.messages.first(), after.messages.first())
        assertTrue(after.revision > before.revision)
        assertEquals(listOf(step()), AiConversationStore(root).load("test")!!.messages.last().executionSteps)
    }

    @Test fun messageDeletionCleansUpItsSteps() = withStore { root, store ->
        seed(store)
        store.recordExecutionStep("test", 2, step())
        store.mutate("test") { it.copy(messages = it.messages.take(1)) }
        val file = root.listFiles { file -> file.extension == "db" }!!.single()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM execution_steps", null).use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test fun metadataUpdatesKeepTelemetryInInsertionOrder() = withStore { _, store ->
        seed(store)
        store.recordExecutionStep("test", 2, step())
        store.recordExecutionStep("test", 2, step().copy(id = "second"))
        store.recordExecutionStep("test", 2, step().copy(elapsedMs = 42))
        store.mutate("test") { it.copy(title = "Updated title") }
        val restored = store.load("test")!!
        assertEquals(listOf("model", "second"), restored.messages.last().executionSteps.map { it.id })
        assertEquals(42L, restored.messages.last().executionSteps.first().elapsedMs)
    }

    @Test fun errorNodesAndLinksSurviveStreamingAndTerminalUpdates() = withStore { root, store ->
        seed(store)
        val error = agentErrorStep("MODEL", "IOException: connection reset", "model")
        store.recordExecutionStep("test", 2, step())
        store.recordExecutionStep("test", 2, error)
        store.updateMessage("test", 2, includeExecutionSteps = false) { it.copy(text = "partial response") }
        store.updateMessage("test", 2) {
            it.withExecutionProblem("Task failed", AgentStepStatus.FAILED).copy(streaming = false, failed = true)
        }
        val restored = AiConversationStore(root).load("test")!!.messages.last()
        assertEquals(listOf(AgentStepKind.MODEL, AgentStepKind.ERROR, AgentStepKind.ERROR), restored.executionSteps.map { it.kind })
        assertEquals(error, restored.executionSteps[1])
        assertEquals("model", restored.executionSteps.last().relatedStepId)
        assertEquals(150L, AiHistoryBuilder().build(listOf(restored)).usage.tokens)
    }

    private fun seed(store: AiConversationStore) {
        store.mutate("test", create = AiEditorSession("test", listOf(
            AiEditorMessage(1, true, "Inspect"), AiEditorMessage(2, false, "", streaming = true),
        ))) { it }
    }
    private fun step() = AgentExecutionStep("model", AgentStepKind.MODEL, detail = "Read the source", startedAtMs = 1_000,
        elapsedMs = 120, status = AgentStepStatus.COMPLETED, inputTokens = 120, outputTokens = 30)
    private fun withStore(block: (File, AiConversationStore) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ai-history-${UUID.randomUUID()}")
        try { block(root, AiConversationStore(root)) } finally { root.deleteRecursively() }
    }
}

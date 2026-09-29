package io.stamethyst.ui.aimod

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteDatabase
import io.stamethyst.backend.llm.AgentContextMessage
import io.stamethyst.backend.llm.AgentContextState
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiConversationStoreInstrumentedTest {
    private fun newRoot(): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "ai-conversation-store-${UUID.randomUUID()}",
    ).also { it.mkdirs() }

    @Test
    fun roundTripUsesOneDatabaseFilePerConversation() {
        val root = newRoot()
        try {
            val store = AiConversationStore(root)
            val sessionId = "session-${UUID.randomUUID()}"
            store.mutate(sessionId, create = AiEditorSession(sessionId)) { session ->
                session.copy(
                    title = "Inspect mod",
                    messages = listOf(
                        AiEditorMessage(
                            id = 1,
                            fromUser = true,
                            text = "Inspect this mod",
                            attachments = listOf(AiAttachment("note.txt", "details")),
                        ),
                        AiEditorMessage(
                            id = 2,
                            fromUser = false,
                            text = "Done",
                            tools = listOf(AiToolCall("call-1", "read", "{}", result = "ok")),
                        ),
                    ),
                    context = AgentContextState(
                        messages = listOf(AgentContextMessage(1, "user", "Inspect this mod")),
                    ),
                )
            }

            val restored = store.load(sessionId)
            assertNotNull(restored)
            assertEquals("Inspect mod", restored!!.title)
            assertEquals("details", restored.messages.first().attachments.single().content)
            assertEquals("ok", restored.messages.last().tools.single().result)
            assertEquals("Inspect this mod", restored.context!!.messages.single().text)
            assertEquals(1, root.listFiles { file -> file.extension == "db" }!!.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun concurrentMessageUpdatesAreSerializedBySqlite() {
        val root = newRoot()
        try {
            val store = AiConversationStore(root)
            val sessionId = "session-${UUID.randomUUID()}"
            store.mutate(sessionId, create = AiEditorSession(sessionId)) {
                it.copy(messages = listOf(AiEditorMessage(1, true, "goal")))
            }
            val start = CountDownLatch(1)
            val workers = (0 until 2).map { worker ->
                thread {
                    val ownStore = AiConversationStore(root)
                    start.await()
                    repeat(20) { index ->
                        ownStore.mutate(sessionId) { current ->
                            current.copy(
                                messages = current.messages + AiEditorMessage(
                                    id = worker * 100L + index + 10,
                                    fromUser = false,
                                    text = "reply",
                                ),
                            )
                        }
                    }
                }
            }
            start.countDown()
            workers.forEach(Thread::join)

            val restored = requireNotNull(store.load(sessionId))
            assertEquals(41, restored.messages.size)
            assertEquals(41L, restored.revision)
            assertTrue(restored.messages.count { !it.fromUser } == 40)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun databaseWithoutTablesIsRepairedOnOpen() {
        val root = newRoot()
        try {
            val store = AiConversationStore(root)
            val sessionId = "session-${UUID.randomUUID()}"
            store.mutate(sessionId, create = AiEditorSession(sessionId)) {
                it.copy(messages = listOf(AiEditorMessage(1, true, "goal")))
            }
            val dbFile = root.listFiles { file -> file.extension == "db" }!!.single()

            // Reproduce what a process death during an earlier write left behind: the file exists
            // but has no tables and no recorded schema version.
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                listOf("agent_jobs", "tool_calls", "attachments", "messages", "conversation")
                    .forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
                db.version = 0
            }

            // Reading jobs must heal the file instead of throwing "no such table: agent_jobs".
            assertEquals(emptyList<AiAgentJobRecord>(), store.listJobs())
            val restored = requireNotNull(store.load(sessionId))
            assertEquals("goal", restored.messages.single().text)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyJsonIsImportedAndRenamed() {
        val root = newRoot()
        try {
            val legacy = File(root, "conversations.json")
            val session = AiEditorSession(
                id = "legacy-session",
                messages = listOf(AiEditorMessage(1, true, "legacy prompt")),
            )
            legacy.writeText(Json.encodeToString(listOf(session)))

            val restored = AiConversationStore(root).load("legacy-session")
            assertEquals(session, restored)
            assertTrue(File(root, "conversations.json.migrated").isFile)
            assertEquals(1, root.listFiles { file -> file.extension == "db" }!!.size)
        } finally {
            root.deleteRecursively()
        }
    }
}

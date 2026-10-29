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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiConversationStoreInstrumentedTest {
    @Test fun externalWriteLockWaitsWithoutReplayingMutation() {
        val root = newRoot()
        val locked = CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        var holder: Thread? = null
        try {
            val store = AiConversationStore(root)
            store.mutate("locked", create = AiEditorSession("locked")) { it }
            val file = root.listFiles { file -> file.extension == "db" }!!.single()
            // Bypass the store's process-local lock, as a second process would.
            holder = thread {
                try {
                    SQLiteDatabase.openDatabase(file.path, null,
                        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { db ->
                        db.beginTransactionNonExclusive()
                        try {
                            locked.countDown()
                            Thread.sleep(1_500)
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                    }
                } catch (error: Throwable) {
                    failures.add(error)
                    locked.countDown()
                }
            }
            assertTrue(locked.await(10, java.util.concurrent.TimeUnit.SECONDS))
            var calls = 0
            store.mutate("locked") {
                calls++
                it.copy(messages = it.messages + AiEditorMessage(1, true, "only once"))
            }
            holder.join(10_000)
            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals(1, calls)
            assertEquals("only once", store.load("locked")!!.messages.single().text)
        } finally {
            holder?.join(10_000)
            root.deleteRecursively()
        }
    }

    @Test fun interruptionRecoveryDoesNotOverwriteCompletionAfterSnapshot() {
        val root = newRoot()
        try {
            val store = AiConversationStore(root)
            val jobs = AiAgentJobStore(store)
            val job = AiAgentJobRecord("completion-race", "session", "mod", "Mod", "", 2)
            jobs.upsert(job)
            val workerStore = AiConversationStore(root)
            val recovered = jobs.markInterrupted("interrupted") {
                // Deterministically finish after recovery read RUNNING, just before the worker
                // releases its process-local reservation and isRunning returns false.
                workerStore.updateJob(it.jobId) { current ->
                    current.copy(status = AiAgentJobStatus.SUCCEEDED)
                }
                false
            }
            assertTrue(recovered.isEmpty())
            assertEquals(AiAgentJobStatus.SUCCEEDED, jobs.find(job.jobId)!!.status)
            assertEquals("", jobs.find(job.jobId)!!.errorMessage)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun interruptionRecoveryRechecksReservationBeforeClaimingJob() {
        val root = newRoot()
        try {
            val jobs = AiAgentJobStore(AiConversationStore(root))
            val job = AiAgentJobRecord("starting-race", "session", "mod", "Mod", "", 2)
            jobs.upsert(job)
            var checks = 0
            val recovered = jobs.markInterrupted("interrupted") { ++checks > 1 }
            assertTrue(recovered.isEmpty())
            assertEquals(2, checks)
            assertEquals(AiAgentJobStatus.RUNNING, jobs.find(job.jobId)!!.status)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun interruptionRecoveryClaimsOnlyOrphanedJobsOnce() {
        val root = newRoot()
        try {
            val jobs = AiAgentJobStore(AiConversationStore(root))
            val job = AiAgentJobRecord("orphan", "session", "mod", "Mod", "", 2)
            jobs.upsert(job)
            assertEquals(listOf(job.jobId), jobs.markInterrupted("interrupted") { false }.map { it.jobId })
            assertEquals(AiAgentJobStatus.FAILED, jobs.find(job.jobId)!!.status)
            assertEquals("interrupted", jobs.find(job.jobId)!!.errorMessage)
            assertTrue(jobs.markInterrupted("interrupted") { false }.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun incrementalSyncAfterVersionTwoMigration() {
        val root = newRoot()
        try {
            val store = AiConversationStore(root)
            store.mutate("migration", create = AiEditorSession("migration",
                messages = listOf(AiEditorMessage(1, true, "original")))) { it }
            val file = root.listFiles { file -> file.extension == "db" }!!.single()
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                // Recreate the two v2 tables while retaining their data, without v3 columns.
                db.execSQL("CREATE TABLE old_messages AS SELECT id, from_user, text, thinking, streaming, failed, error_message, retry_message, notice, parts_json, model_name, elapsed_ms, context_tokens FROM messages")
                db.execSQL("CREATE TABLE old_conversation AS SELECT id, title, revision, context_json, updated_at FROM conversation")
                db.execSQL("DROP TABLE attachments")
                db.execSQL("DROP TABLE tool_calls")
                db.execSQL("DROP TABLE messages")
                db.execSQL("DROP TABLE conversation")
                db.execSQL("ALTER TABLE old_messages RENAME TO messages")
                db.execSQL("ALTER TABLE old_conversation RENAME TO conversation")
                db.version = 2
            }
            val migrated = store.load("migration")!!
            assertEquals("original", migrated.messages.single().text)
            // Migration must preserve revision and make the new incremental read path usable.
            assertTrue(migrated.messages.single() === store.loadChanges(migrated)!!.messages.single())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun incrementalSnapshotsReuseUnchangedContentAndHandleDeletion() {
        val root = newRoot()
        try {
            val store = AiConversationStore(root)
            val context = AgentContextState(messages = listOf(AgentContextMessage(1, "user", "large".repeat(20_000))))
            store.mutate("incremental", create = AiEditorSession("incremental", messages = listOf(
                AiEditorMessage(1, true, "prompt"), AiEditorMessage(2, false, "answer"),
            ), context = context)) { it }
            val first = store.load("incremental")!!
            store.updateMessage(first.id, 2) { it.copy(text = "updated") }
            val second = store.loadChanges(first)!!
            assertTrue(first.messages[0] === second.messages[0])
            assertTrue(first.context === second.context)
            assertEquals("updated", second.messages[1].text)
            store.updateContext(first.id, context.copy(estimatedTokens = 123))
            val third = store.loadChanges(second)!!
            assertTrue(second.messages[1] === third.messages[1])
            assertEquals(123, third.context!!.estimatedTokens)
            store.mutate(first.id) { it.copy(messages = it.messages.take(1)) }
            assertEquals(listOf(1L), store.loadChanges(third)!!.messages.map { it.id })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oversizedConversationFieldsRemainReadableAndUpdatable() {
        val root = newRoot()
        try {
            val store = AiConversationStore(root)
            // Multibyte text crosses both CursorWindow capacity and UTF-8 chunk boundaries.
            val large = "中文🙂abc".repeat(240_000)
            val session = AiEditorSession(
                id = "large-history",
                messages = listOf(
                    AiEditorMessage(1, true, "inspect", attachments = listOf(AiAttachment("large.txt", large))),
                    AiEditorMessage(2, false, large, thinking = large,
                        parts = listOf(AiMessagePart(AiMessagePartKind.TEXT, large)),
                        tools = listOf(AiToolCall("large-tool", "read", large, result = large))),
                ),
                context = AgentContextState(messages = listOf(AgentContextMessage(1, "user", large))),
            )
            store.mutate(session.id, create = session) { it }
            val reopened = AiConversationStore(root)
            val restored = requireNotNull(reopened.load(session.id))
            assertEquals(session.messages, restored.messages)
            assertEquals(session.context, restored.context)
            assertEquals(listOf(session.id), reopened.loadSummaries().map { it.id })
            reopened.updateMessage(session.id, 2) { it.copy(text = it.text + "done") }
            assertEquals(large + "done", reopened.load(session.id)!!.messages.last().text)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedMessageInsertRollsBackInsteadOfReportingSuccessfulSave() {
        val root = newRoot()
        try {
            val store = AiConversationStore(root)
            store.mutate("write-failure", create = AiEditorSession("write-failure")) {
                it.copy(messages = listOf(AiEditorMessage(1, true, "original")))
            }
            val database = root.listFiles { file -> file.extension == "db" }!!.single()
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("CREATE TRIGGER reject_message BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT, 'test write failure'); END")
            }
            val result = runCatching {
                store.mutate("write-failure") {
                    it.copy(title = "must roll back", messages = it.messages + AiEditorMessage(2, false, "not saved"))
                }
            }
            assertTrue("Failed inserts must not be treated as committed saves", result.isFailure)
            val restored = store.load("write-failure")!!
            assertEquals("", restored.title)
            assertEquals("original", restored.messages.single().text)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedLegacyImportKeepsSourceForRetry() {
        val root = newRoot()
        try {
            val store = AiConversationStore(root)
            store.mutate("legacy-retry", create = AiEditorSession("legacy-retry")) { it }
            val database = root.listFiles { file -> file.extension == "db" }!!.single()
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE messages")
            }
            val legacy = File(root, "conversations.json")
            legacy.writeText(Json.encodeToString(listOf(AiEditorSession(
                "legacy-retry", messages = listOf(AiEditorMessage(1, true, "recover me")),
            ))))
            store.loadSummaries { _, _ -> }
            assertTrue("Failed migration must retain its source", legacy.isFile)
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.version = 0
            }
            assertEquals("recover me", store.load("legacy-retry")!!.messages.single().text)
            assertTrue(File(root, "conversations.json.migrated").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unreadableConversationDoesNotHideHealthyHistory() {
        val root = newRoot()
        try {
            val store = AiConversationStore(root)
            store.mutate("healthy", create = AiEditorSession("healthy")) {
                it.copy(messages = listOf(AiEditorMessage(1, true, "saved prompt")))
            }
            // A file with a current schema version but a missing table fails during querying,
            // rather than during open. It must not abort the history picker.
            SQLiteDatabase.openOrCreateDatabase(File(root, "broken.db"), null).use { db ->
                db.execSQL("CREATE TABLE conversation (id TEXT, title TEXT, revision INTEGER, updated_at INTEGER)")
                db.execSQL("INSERT INTO conversation VALUES ('broken', '', 0, 0)")
                db.version = 3
            }
            val failures = mutableListOf<File>()
            val summaries = store.loadSummaries { file, _ -> failures += file }
            assertEquals(listOf("healthy"), summaries.map { it.id })
            assertEquals(listOf("broken.db"), failures.map { it.name })
            assertEquals("saved prompt", AiConversationStore(root).load("healthy")!!.messages.single().text)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun historyUsesFirstUserMessageInsteadOfLeadingAssistantNotice() {
        val root = newRoot()
        try {
            val store = AiConversationStore(root)
            store.mutate("notice-first", create = AiEditorSession("notice-first")) {
                it.copy(messages = listOf(
                    AiEditorMessage(1, false, "notice", notice = true),
                    AiEditorMessage(2, true, "keep this history"),
                ))
            }
            assertEquals("keep this history", store.loadSummaries().single().messages.single().text)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun queuedSaveSurvivesNavigationAndIsVisibleToReopenedEditor() = runBlocking {
        val root = newRoot()
        val pageScope = CoroutineScope(SupervisorJob() + AiEditorStorage.dispatcher)
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val blocker = AiEditorStorage.scope.launch {
                entered.complete(Unit)
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
            withTimeout(10_000) { entered.await() }
            val save = AiEditorStorage.scope.launch {
                AiConversationStore(root).mutate("saved", create = AiEditorSession("saved")) {
                    it.copy(messages = listOf(AiEditorMessage(1, true, "before leaving")))
                }
            }
            pageScope.cancel()
            release.countDown()
            withTimeout(10_000) { blocker.join(); save.join() }
            val reopened = withContext(AiEditorStorage.dispatcher) { AiConversationStore(root).loadSummaries() }
            assertEquals("before leaving", reopened.single().messages.single().text)
        } finally {
            pageScope.cancel()
            root.deleteRecursively()
        }
    }

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
                            notice = true,
                            parts = listOf(AiMessagePart(AiMessagePartKind.TEXT, "Done")),
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
            assertTrue(restored.messages.last().notice)
            assertEquals("Done", restored.messages.last().parts.single().content)
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
            // Schema repair cannot resurrect rows deliberately dropped above. The repaired
            // database remains usable and can accept a subsequent save.
            assertNull(store.load(sessionId))
            store.mutate(sessionId, create = AiEditorSession(sessionId)) {
                it.copy(messages = listOf(AiEditorMessage(1, true, "goal")))
            }
            assertEquals("goal", store.load(sessionId)!!.messages.single().text)
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

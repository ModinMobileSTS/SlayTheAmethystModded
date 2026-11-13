package io.stamethyst.ui.aimod

import io.stamethyst.backend.llm.AgentContextAttachment
import io.stamethyst.backend.llm.AgentContextMessage
import io.stamethyst.backend.llm.AgentContextState
import io.stamethyst.backend.llm.AgentContextToolCall
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the production incremental engine against a transactional, file-backed row fixture.
 * This is a JVM test of the storage protocol, not an emulation of Android's SQLite/CursorWindow.
 */
class AiContextStorageTest {
    @get:Rule val temporary = TemporaryFolder(
        (File("agent-tmp").takeIf { it.isDirectory } ?: File("../agent-tmp")).also { it.mkdirs() },
    )

    @Test fun legacyMigrationRetainsExactOriginalAndAllCompactionFields() {
        val state = sample().copy(summary = "compressed", recent = "recent turns", summarizedCount = 3,
            compactionCount = 2, activePatchId = "patch", modelKey = "model", checkpointVersion = 2,
            lastInputTokens = 177, lastOutputTokens = 24, measuredMessageCount = 3, estimateScale = 1.25)
        val legacy = Json.encodeToString(state).dropLast(1) + ",\"futureField\":\"keep original\"}"
        val db = database(DiskImage(revision = 7, contextRevision = 5, legacy = legacy))
        val engine = AiContextStorage()

        assertEquals(state, db.read(engine))
        assertEquals(listOf(legacy), db.image().backups)
        assertNull(db.image().legacy)
        assertEquals(7L, db.image().revision)
        assertEquals(listOf(0, 1, 2), db.writtenPositions)
        assertFalse(db.image().metadata!!.contains("base64-image"))
        assertEquals(state, db.reopen().read(AiContextStorage()))
        db.resetCounts()
        assertFalse(db.write(engine, state))
        assertEquals(0, db.writtenPositions.size)
        assertEquals(0, db.headerWrites)
        assertEquals(7L, db.image().revision)
    }

    @Test fun failedLegacyMigrationRollsBackAndCanBeRetriedAfterReopen() {
        val legacy = Json.encodeToString(sample())
        val db = database(DiskImage(legacy = legacy))
        val engine = AiContextStorage()
        db.failPosition = 1

        assertTrue(runCatching { db.read(engine) }.isFailure)
        assertEquals(legacy, db.image().legacy)
        assertNull(db.image().generation)
        assertTrue(db.image().rows.isEmpty())
        assertTrue(db.image().backups.isEmpty())
        assertEquals(sample(), db.reopen().read(AiContextStorage()))
    }

    @Test fun malformedLegacyRemainsRecoverableWhenAnExplicitNewCheckpointIsSaved() {
        val db = database(DiskImage(legacy = "{incomplete"))
        val engine = AiContextStorage()
        assertNull(db.read(engine))
        assertEquals("{incomplete", db.image().legacy)
        assertNull(db.image().generation)
        assertTrue(db.write(engine, sample()))
        assertEquals(listOf("{incomplete"), db.image().backups)
        assertEquals(sample(), db.reopen().read(AiContextStorage()))
    }

    @Test fun eachCompletedToolResultIsAnIndependentDurableAppend() {
        val original = sample()
        val db = database()
        val engine = AiContextStorage()
        val initial = original.copy(messages = original.messages.take(1))
        db.write(engine, initial)
        db.resetCounts()

        for (count in 2..original.messages.size) {
            val next = original.copy(messages = original.messages.take(count))
            assertTrue(db.write(engine, next))
            assertEquals(listOf(count - 1), db.writtenPositions)
            assertEquals(0, db.metadataWrites)
            assertEquals(next, db.reopen().read(AiContextStorage()))
            db.resetCounts()
        }
        val final = original.copy(messages = original.messages + AgentContextMessage(2, "tool", "second result",
            callId = "call-2", toolName = "read"))
        assertTrue(db.write(engine, final))
        assertEquals(listOf(3), db.writtenPositions)
        assertEquals(final, db.reopen().read(AiContextStorage()))
        assertEquals("base64-image".repeat(30_000), db.read(engine)!!.messages.first().attachments.last().data)
    }

    @Test fun equalDeepCopyIsNoOpWithoutRevisionOrMetadataWrite() {
        val db = database()
        val engine = AiContextStorage()
        db.write(engine, sample())
        val before = db.image()
        val copy = Json.decodeFromString<AgentContextState>(Json.encodeToString(sample()))
        db.resetCounts()

        assertFalse(db.write(engine, copy))
        assertEquals(before, db.image())
        assertTrue(db.writtenPositions.isEmpty())
        assertEquals(0, db.headerWrites)
        assertEquals(0, db.metadataReads)
        assertEquals(0, db.messageReads)
    }

    @Test fun summaryAndTokenOnlyChangesKeepArchiveRowsAndCachedMessageObjects() {
        val db = database()
        val writer = AiContextStorage()
        val reader = AiContextStorage()
        db.write(writer, sample())
        val before = db.read(reader)!!
        val archived = db.image().rows
        val compressed = before.copy(summary = "checkpoint", recent = "recent", summarizedCount = 3,
            checkpointVersion = 2, compactionCount = 1, estimatedTokens = 500, lastInputTokens = 450)
        db.resetCounts()

        assertTrue(db.write(writer, compressed))
        assertEquals(archived, db.image().rows)
        assertEquals(1, db.metadataWrites)
        assertTrue(db.writtenPositions.isEmpty())
        val after = db.read(reader)!!
        assertEquals(compressed, after)
        assertSame(before.messages, after.messages)
        assertEquals(0, db.messageReads)
        assertSame(after, db.read(reader))
        assertEquals(compressed, db.reopen().read(AiContextStorage()))
    }

    @Test fun readerFetchesOnlyAppendedRowsAfterAnotherWriterCommits() {
        val db = database()
        val writer = AiContextStorage()
        val reader = AiContextStorage()
        db.write(writer, sample())
        val before = db.read(reader)!!
        val next = before.copy(messages = before.messages + AgentContextMessage(3, "user", "next"))
        db.write(writer, next)
        db.resetCounts()

        val after = db.read(reader)!!
        assertEquals(next, after)
        assertEquals(1, db.messageReads)
        before.messages.indices.forEach { assertSame(before.messages[it], after.messages[it]) }
        db.resetCounts()
        assertSame(after, db.read(reader))
        assertEquals(0, db.messageReads)
        assertEquals(0, db.metadataReads)
    }

    @Test fun sameLengthReplacementWritesAndReadsOnlyChangedPosition() {
        val db = database()
        val writer = AiContextStorage()
        val reader = AiContextStorage()
        db.write(writer, sample())
        val before = db.read(reader)!!
        val next = before.copy(messages = before.messages.mapIndexed { index, message ->
            if (index == 1) message.copy(thinking = "corrected", text = "replacement") else message
        })
        db.resetCounts()

        assertTrue(db.write(writer, next))
        assertEquals(listOf(1), db.writtenPositions)
        val after = db.read(reader)!!
        assertEquals(next, after)
        assertEquals(1, db.messageReads)
        assertSame(before.messages[0], after.messages[0])
        assertNotSame(before.messages[1], after.messages[1])
        assertSame(before.messages[2], after.messages[2])
    }

    @Test fun rollbackTruncatesRowsAndPreservesTheCallersSummaryBoundary() {
        val db = database()
        val writer = AiContextStorage()
        val reader = AiContextStorage()
        val state = sample().copy(summary = "all summarized", summarizedCount = 3, recent = "recent", checkpointVersion = 2)
        db.write(writer, state)
        db.read(reader)
        val retained = state.retainSources(setOf(1))
        db.resetCounts()

        assertTrue(db.write(writer, retained))
        assertTrue(db.writtenPositions.isEmpty())
        assertEquals(listOf(1), db.truncations)
        assertEquals(1, db.image().rows.size)
        assertEquals(retained, db.read(reader))
        assertEquals("", retained.summary)
        assertEquals(0, retained.summarizedCount)
        assertEquals(retained, db.reopen().read(AiContextStorage()))
        assertTrue(db.write(writer, state))
        assertEquals(state, db.read(reader))
    }

    @Test fun removalInMiddleAndReorderingUsePositionRatherThanSourceId() {
        val db = database()
        val engine = AiContextStorage()
        val state = sample()
        db.write(engine, state)
        val next = state.copy(messages = listOf(state.messages[2], state.messages[0]))
        assertTrue(db.write(engine, next))
        assertEquals(next, db.reopen().read(AiContextStorage()))
        assertEquals(listOf(0, 1), db.image().rows.map { it.position })
    }

    @Test fun emptyContextAndAbsentContextStayDistinctAcrossReopen() {
        val db = database()
        val engine = AiContextStorage()
        db.write(engine, sample())
        assertTrue(db.write(engine, AgentContextState()))
        assertEquals(AgentContextState(), db.reopen().read(AiContextStorage()))
        assertTrue(db.write(engine, null))
        assertNull(db.reopen().read(AiContextStorage()))
        assertTrue(db.image().rows.isEmpty())
        val revision = db.image().revision
        assertFalse(db.write(engine, null))
        assertEquals(revision, db.image().revision)
    }

    @Test fun failedCommitCannotPublishUncommittedCacheOrLosePriorCheckpoint() {
        val db = database()
        val engine = AiContextStorage()
        db.write(engine, sample())
        val before = db.image()
        val next = sample().copy(messages = sample().messages + AgentContextMessage(3, "user", "next"))
        db.failCommit = true

        assertTrue(runCatching { db.write(engine, next) }.isFailure)
        assertEquals(before, db.image())
        assertEquals(sample(), db.read(engine))
        db.failCommit = false
        assertTrue(db.write(engine, next))
        assertEquals(before.revision + 1, db.image().revision)
        assertEquals(next, db.reopen().read(AiContextStorage()))
    }

    @Test fun replacedDatabaseWithSameRevisionInvalidatesCachedContent() {
        val db = database()
        val engine = AiContextStorage()
        db.write(engine, sample())
        val before = db.read(engine)
        val replacement = database()
        val other = sample().copy(summary = "restored database")
        replacement.write(AiContextStorage(), other)
        assertEquals(db.image().revision, replacement.image().revision)
        replacement.file.copyTo(db.file, overwrite = true)

        assertEquals(other, db.read(engine))
        assertNotSame(before, db.read(engine))
    }

    @Test fun sessionJsonExportAndRestoreRetainsFullProtocolArchiveAndAttachments() {
        val db = database()
        val state = sample().copy(summary = "summary", summarizedCount = 3, checkpointVersion = 2)
        db.write(AiContextStorage(), state)
        val session = AiEditorSession("session", messages = listOf(AiEditorMessage(1, true, "prompt")),
            context = db.read(AiContextStorage()))
        val exported = Json.encodeToString(listOf(session))
        val restored = Json.decodeFromString<List<AiEditorSession>>(exported).single()
        val destination = database()
        destination.write(AiContextStorage(), restored.context)

        assertEquals(session, restored)
        assertEquals(state, destination.reopen().read(AiContextStorage()))
        assertEquals(state.messages.first().attachments, restored.context!!.messages.first().attachments)
    }

    @Test fun incompleteNormalizedArchiveFailsInsteadOfReturningPartialHistory() {
        val db = database()
        db.write(AiContextStorage(), sample())
        val original = db.image()
        db.replace(original.copy(rows = original.rows.drop(1)))
        assertTrue(runCatching { db.read(AiContextStorage()) }.isFailure)
        db.replace(original)
        assertEquals(sample(), db.read(AiContextStorage()))
    }

    @Test fun oneEngineDoesNotReuseAnotherConversationsRowsAtTheSameRevision() {
        val first = database()
        val second = database()
        val engine = AiContextStorage()
        first.write(engine, sample())
        val other = sample().copy(messages = listOf(AgentContextMessage(1, "user", "other")))
        second.write(engine, other)
        assertEquals(sample(), first.read(engine))
        assertEquals(other, second.read(engine))
    }

    @Test fun readerCatchesUpAcrossTruncationReplacementAndAppendWithoutPollingEveryCheckpoint() {
        val db = database()
        val writer = AiContextStorage()
        val reader = AiContextStorage()
        val state = sample()
        db.write(writer, state)
        val before = db.read(reader)!!
        db.write(writer, state.copy(messages = state.messages.take(1)))
        val next = state.copy(messages = state.messages.take(1) + AgentContextMessage(4, "user", "replacement"))
        db.write(writer, next)
        val last = next.copy(messages = next.messages + AgentContextMessage(5, "assistant", "done"))
        db.write(writer, last)
        db.resetCounts()

        val after = db.read(reader)!!
        assertEquals(last, after)
        assertSame(before.messages[0], after.messages[0])
        assertEquals(2, db.messageReads)
    }

    @Test fun restoringAnEarlierSnapshotOfTheSameDatabaseReloadsAllRows() {
        val db = database()
        val engine = AiContextStorage()
        db.write(engine, sample())
        val original = db.image()
        db.write(engine, sample().copy(messages = listOf(AgentContextMessage(9, "user", "different history"))))
        db.replace(original)
        db.resetCounts()

        assertEquals(sample(), db.read(engine))
        assertEquals(3, db.messageReads)
    }

    @Test fun missingHeaderPreservesMigratedArchiveAndNewToolResultsInsteadOfInitializingEmpty() {
        val state = sample()
        val db = database(DiskImage(legacy = Json.encodeToString(state)))
        val engine = AiContextStorage()
        db.read(engine)
        val appended = state.copy(messages = state.messages + AgentContextMessage(2, "tool", "new durable result",
            callId = "call-2", toolName = "read", attachments = state.messages.first().attachments))
        db.write(engine, appended)
        val damaged = db.image().copy(generation = null, metadata = null, messageCount = 0, messagesRevision = 0)
        assertNull(damaged.legacy)
        db.replace(damaged)
        db.resetCounts()

        assertTrue(runCatching { db.read(engine) }.isFailure)
        assertEquals(damaged, db.image())
        assertTrue(db.truncations.isEmpty())
        assertTrue(db.writtenPositions.isEmpty())
        assertEquals(0, db.headerWrites)
        assertTrue(runCatching { db.reopen().read(AiContextStorage()) }.isFailure)
        assertEquals(appended.messages, db.image().rows.map { Json.decodeFromString<AgentContextMessage>(it.json) })
        assertTrue(runCatching { db.write(engine, AgentContextState()) }.isFailure)
        assertEquals(damaged, db.image())
    }

    @Test fun missingHeaderAndMalformedLegacyCannotDiscardExistingProtocolRows() {
        val db = database()
        db.write(AiContextStorage(), sample())
        val damaged = db.image().copy(generation = null, legacy = "{incomplete")
        db.replace(damaged)

        assertTrue(runCatching { db.read(AiContextStorage()) }.isFailure)
        assertEquals(damaged, db.image())
    }

    @Test fun missingMetadataWithNonzeroMessageCountFailsEvenWithAPopulatedCache() {
        val db = database()
        val engine = AiContextStorage()
        db.write(engine, sample())
        val damaged = db.image().copy(metadata = null)
        db.replace(damaged)
        db.resetCounts()

        assertTrue(runCatching { db.read(engine) }.isFailure)
        assertTrue(runCatching { db.reopen().read(AiContextStorage()) }.isFailure)
        assertEquals(damaged, db.image())
        assertTrue(db.writtenPositions.isEmpty())
        assertTrue(db.truncations.isEmpty())
        assertEquals(0, db.headerWrites)
    }

    @Test fun genuinelyEmptyDatabaseCanInitializeAnAbsentContextWithoutConsumingARevision() {
        val db = database()
        val engine = AiContextStorage()

        assertNull(db.read(engine))
        assertTrue(db.image().generation != null)
        assertEquals(0, db.image().messageCount)
        assertEquals(0L, db.image().revision)
        assertTrue(db.image().rows.isEmpty())
        assertTrue(db.truncations.isEmpty())
        assertNull(db.reopen().read(AiContextStorage()))
        assertTrue(db.write(engine, sample()))
        assertEquals(sample(), db.reopen().read(AiContextStorage()))
    }

    private fun sample(): AgentContextState = AgentContextState(messages = listOf(
        AgentContextMessage(1, "user", "inspect", attachments = listOf(
            AgentContextAttachment("note.txt", "中文🙂".repeat(10_000)),
            AgentContextAttachment("image.png", mime = "image/png", data = "base64-image".repeat(30_000)),
        )),
        AgentContextMessage(2, "assistant", thinking = "check files", calls = listOf(
            AgentContextToolCall("call-1", "read", "{\"path\":\"image.png\"}"),
            AgentContextToolCall("call-2", "read", "{\"path\":\"next.txt\"}"),
        )),
        AgentContextMessage(2, "tool", "full tool result", callId = "call-1", toolName = "read"),
    ))

    private fun database(initial: DiskImage = DiskImage()): FileRows = FileRows(temporary.newFile()).also { it.replace(initial) }

    @Serializable private data class StoredRow(val position: Int, val revision: Long, val json: String)
    @Serializable private data class DiskImage(
        val revision: Long = 0,
        val contextRevision: Long = 0,
        val generation: String? = null,
        val messageCount: Int = 0,
        val messagesRevision: Long = 0,
        val metadata: String? = null,
        val legacy: String? = null,
        val backups: List<String> = emptyList(),
        val rows: List<StoredRow> = emptyList(),
    )

    private class FileRows(val file: File) {
        var failPosition: Int? = null
        var failCommit = false
        val writtenPositions = mutableListOf<Int>()
        val truncations = mutableListOf<Int>()
        var headerWrites = 0
        var metadataWrites = 0
        var metadataReads = 0
        var messageReads = 0

        fun image(): DiskImage = Json.decodeFromString(file.readText())
        fun replace(image: DiskImage) = file.writeText(Json.encodeToString(image))
        fun reopen() = FileRows(file)
        fun resetCounts() {
            writtenPositions.clear()
            truncations.clear()
            headerWrites = 0
            metadataWrites = 0
            metadataReads = 0
            messageReads = 0
        }

        fun read(engine: AiContextStorage): AgentContextState? = transaction(engine) { rows, current ->
            engine.read(file.path, current.contextRevision, rows)
        }

        fun write(engine: AiContextStorage, state: AgentContextState?): Boolean = transaction(engine) { rows, current ->
            engine.write(file.path, current.contextRevision, current.revision + 1, state, rows)
        }

        private fun <T> transaction(engine: AiContextStorage, block: (AiContextStorage.Rows, DiskImage) -> T): T {
            val before = image()
            var pending = before
            val rows = object : AiContextStorage.Rows {
                override fun header(): AiContextStorage.Header? = pending.generation?.let {
                    AiContextStorage.Header(it, pending.messageCount, pending.messagesRevision, pending.metadata != null)
                }
                override fun metadata(): String? { metadataReads++; return pending.metadata }
                override fun legacy(): String? = pending.legacy
                override fun hasMessages(): Boolean = pending.rows.isNotEmpty()
                override fun archiveLegacy() {
                    pending.legacy?.let { pending = pending.copy(backups = pending.backups + it, legacy = null) }
                }
                override fun messages(afterRevision: Long?): List<AiContextStorage.MessageRow> = pending.rows
                    .filter { afterRevision == null || it.revision > afterRevision }
                    .sortedBy { it.position }.map { AiContextStorage.MessageRow(it.position, it.json) }
                    .also { messageReads += it.size }
                override fun putMessage(position: Int, revision: Long, json: String) {
                    check(position != failPosition) { "Injected row write failure" }
                    writtenPositions += position
                    pending = pending.copy(rows = (pending.rows.filterNot { it.position == position } +
                        StoredRow(position, revision, json)).sortedBy { it.position })
                }
                override fun truncateMessages(size: Int) {
                    truncations += size
                    pending = pending.copy(rows = pending.rows.filter { it.position < size })
                }
                override fun putHeader(header: AiContextStorage.Header, metadata: String?, metadataChanged: Boolean) {
                    headerWrites++
                    if (metadataChanged) metadataWrites++
                    pending = pending.copy(generation = header.generation, messageCount = header.messageCount,
                        messagesRevision = header.messagesRevision, metadata = if (metadataChanged) metadata else pending.metadata)
                }
            }
            return try {
                val result = block(rows, before)
                if (result == true) {
                    check(!failCommit) { "Injected commit failure" }
                    pending = pending.copy(revision = before.revision + 1, contextRevision = before.revision + 1)
                }
                replace(pending)
                result
            } catch (error: Throwable) {
                engine.invalidate(file.path)
                throw error
            }
        }
    }
}

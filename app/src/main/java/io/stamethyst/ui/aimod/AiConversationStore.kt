package io.stamethyst.ui.aimod

import android.content.ContentValues
import android.database.Cursor
import android.database.CursorWrapper
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import io.stamethyst.backend.llm.AgentContextState
import io.stamethyst.backend.llm.AgentExecutionStep
import io.stamethyst.backend.workshop.WorkshopJsonFileStore
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * SQLite-backed storage for AI conversations.
 *
 * Each conversation owns one database file. The database is deliberately opened for one operation
 * and closed again so the UI and the foreground service can safely use the same files from
 * different processes. SQLite WAL and its transaction locking provide the cross-process safety;
 * the in-memory locks only avoid redundant writers in one process.
 */
internal class AiConversationStore(root: File) {
    private val root = if (root.extension == "json") root.parentFile ?: root else root
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<AiEditorSession> {
        migrateLegacyConversations()
        return databaseFiles()
            .mapNotNull { file ->
                withDatabaseFile(file) { db ->
                    readSession(db, null)?.let { session ->
                        session to readUpdatedAt(db)
                    }
                }
            }
            .filter { it.first.hasUserPrompt() }
            .sortedBy { it.second }
            .map { it.first }
    }

    /** Loads only metadata and the first user message for the history picker. */
    fun loadSummaries(onReadFailure: (File, Exception) -> Unit = { file, error ->
        android.util.Log.w(LOG_TAG, "Failed to read conversation ${file.name}", error)
    }): List<AiEditorSession> {
        migrateLegacyConversations()
        return databaseFiles()
            .mapNotNull { file ->
                try {
                    withDatabaseFile(file) { db ->
                        readSessionSummary(db)?.let { summary ->
                            summary to readUpdatedAt(db)
                        }
                    }
                } catch (error: Exception) {
                    onReadFailure(file, error)
                    null
                }
            }
            .filter { it.first.hasUserPrompt() }
            .sortedBy { it.second }
            .map { it.first }
    }

    fun load(sessionId: String): AiEditorSession? {
        migrateLegacyConversations()
        return withDatabase(sessionId, create = false) { db ->
            db.beginWriteTransactionWithRetry()
            try {
                readSession(db, sessionId).also { db.setTransactionSuccessful() }
            } finally {
                db.endTransaction()
            }
        }
    }

    /** Read a consistent snapshot, reusing unchanged message/context objects across polls. */
    fun loadChanges(previous: AiEditorSession): AiEditorSession? =
        withDatabase(previous.id, create = false) { db ->
            db.beginWriteTransactionWithRetry()
            try {
                readSession(db, previous.id, previous).also { db.setTransactionSuccessful() }
            } finally {
                db.endTransaction()
            }
        }

    fun revision(sessionId: String): Long? {
        migrateLegacyConversations()
        return withDatabase(sessionId, create = false) { db ->
            db.query(
                TABLE_CONVERSATION,
                arrayOf(COLUMN_REVISION),
                "$COLUMN_ID = ?",
                arrayOf(sessionId),
                null,
                null,
                null,
                "1",
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        }
    }

    fun mutate(
        sessionId: String,
        create: AiEditorSession? = null,
        transform: (AiEditorSession) -> AiEditorSession,
    ): AiEditorSession? {
        require(sessionId.isNotBlank()) { "Conversation id must not be blank" }
        migrateLegacyConversations()
        return withLockedDatabase(sessionId, create = true) { db ->
            db.beginWriteTransactionWithRetry()
            try {
                val previous = readSession(db, sessionId) ?: create ?: return@withLockedDatabase null
                val next = transform(previous).copy(revision = previous.revision + 1)
                writeSession(db, previous, next)
                db.setTransactionSuccessful()
                next
            } finally {
                db.endTransaction()
            }
        }
    }

    fun update(sessionId: String, transform: (AiEditorSession) -> AiEditorSession) {
        mutate(sessionId, transform = transform)
    }

    /** Updates one message without loading or rewriting the rest of the conversation. */
    fun updateMessage(
        sessionId: String,
        messageId: Long,
        includeExecutionSteps: Boolean = true,
        transform: (AiEditorMessage) -> AiEditorMessage,
    ): AiEditorMessage? {
        migrateLegacyConversations()
        return withLockedDatabase(sessionId, create = false) { db ->
            db.beginWriteTransactionWithRetry()
            try {
                val previous = readMessage(db, messageId, includeExecutionSteps) ?: return@withLockedDatabase null
                val next = transform(previous)
                writeMessage(db, next, previous = previous)
                bumpRevision(db)
                db.setTransactionSuccessful()
                next
            } finally {
                db.endTransaction()
            }
        }
    }

    /** O(1) telemetry writes, independent of transcript length and the number of previous steps. */
    fun recordExecutionStep(sessionId: String, messageId: Long, step: AgentExecutionStep) {
        withLockedDatabase(sessionId, create = false) { db ->
            db.beginWriteTransactionWithRetry()
            try {
                if (db.update(TABLE_MESSAGES, ContentValues().apply {
                    put("changed_revision", db.rawQuery("SELECT revision + 1 FROM conversation LIMIT 1", null).use {
                        if (it.moveToFirst()) it.getLong(0) else 1L
                    })
                }, "id = ?", arrayOf(messageId.toString())) > 0) {
                    writeExecutionStep(db, messageId, step)
                    bumpRevision(db)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }

    fun updateContext(sessionId: String, context: AgentContextState): Boolean {
        migrateLegacyConversations()
        return withLockedDatabase(sessionId, create = false) { db ->
            db.beginWriteTransactionWithRetry()
            try {
                val values = ContentValues().apply {
                    put(COLUMN_CONTEXT, json.encodeToString(context))
                    put(COLUMN_UPDATED_AT, System.currentTimeMillis())
                }
                val updated = db.update(TABLE_CONVERSATION, values, "id = ?", arrayOf(sessionId))
                if (updated > 0) {
                    db.execSQL(
                        "UPDATE $TABLE_CONVERSATION SET context_revision = $COLUMN_REVISION + 1, $COLUMN_REVISION = $COLUMN_REVISION + 1 WHERE $COLUMN_ID = ?",
                        arrayOf(sessionId),
                    )
                }
                db.setTransactionSuccessful()
                updated > 0
            } finally {
                db.endTransaction()
            }
        } ?: false
    }

    internal fun listJobs(sessionId: String? = null): List<AiAgentJobRecord> {
        migrateLegacyConversations()
        val files = sessionId?.let { listOf(databaseFile(it)) } ?: databaseFiles()
        return files.flatMap { file ->
            withDatabaseFile(file) { db -> readJobs(db) }.orEmpty()
        }
    }

    internal fun findJob(jobId: String): AiAgentJobRecord? = listJobs().firstOrNull { it.jobId == jobId }

    internal fun upsertJob(record: AiAgentJobRecord) {
        migrateLegacyConversations()
        withLockedDatabase(record.conversationId, create = true) { db ->
            db.beginWriteTransactionWithRetry()
            try {
                ensureConversationRow(db, record.conversationId)
                writeJob(db, record)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** Recheck conditional updates against the current row while holding the write transaction. */
    internal fun updateJob(
        jobId: String,
        predicate: (AiAgentJobRecord) -> Boolean = { true },
        transform: (AiAgentJobRecord) -> AiAgentJobRecord,
    ): AiAgentJobRecord? {
        migrateLegacyConversations()
        val current = findJob(jobId) ?: return null
        return withLockedDatabase(current.conversationId, create = false) { db ->
            db.beginWriteTransactionWithRetry()
            try {
                val stored = readJobs(db).firstOrNull { it.jobId == jobId } ?: return@withLockedDatabase null
                if (!predicate(stored)) return@withLockedDatabase null
                val updated = transform(stored).copy(updatedAtMillis = System.currentTimeMillis())
                writeJob(db, updated)
                db.setTransactionSuccessful()
                updated
            } finally {
                db.endTransaction()
            }
        }
    }

    internal fun migrateLegacyJobs() {
        migrateLegacyConversations()
        val legacy = File(root, LEGACY_JOBS_FILE)
        if (!legacy.isFile) return
        synchronized(lockFor(legacy)) {
            WorkshopJsonFileStore.withFileLock(legacy, lockFor(legacy)) {
                if (!legacy.isFile) return@withFileLock
                val records = runCatching {
                    json.decodeFromString<List<AiAgentJobRecord>>(
                        legacy.readText(StandardCharsets.UTF_8),
                    )
                }.getOrNull() ?: return@withFileLock
                records.forEach(::upsertJobWithoutMigration)
                moveLegacyFile(legacy)
            }
        }
    }

    private fun upsertJobWithoutMigration(record: AiAgentJobRecord) {
        withLockedDatabase(record.conversationId, create = true) { db ->
            db.beginWriteTransactionWithRetry()
            try {
                ensureConversationRow(db, record.conversationId)
                writeJob(db, record)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun migrateLegacyConversations() {
        val legacy = File(root, LEGACY_CONVERSATIONS_FILE)
        if (!legacy.isFile) return
        synchronized(lockFor(legacy)) {
            WorkshopJsonFileStore.withFileLock(legacy, lockFor(legacy)) {
                if (!legacy.isFile) return@withFileLock
                val sessions = runCatching {
                    json.decodeFromString<List<AiEditorSession>>(
                        legacy.readText(StandardCharsets.UTF_8),
                    )
                }.getOrNull() ?: return@withFileLock
                var allMigrated = true
                sessions.forEach { session ->
                    // A session file may exist but be empty: an earlier failed write can leave a
                    // conversation row with no messages. Only trust a stored copy that has content,
                    // otherwise the legacy JSON import would be skipped and history lost.
                    if (session.hasUserPrompt()) {
                        // One unreadable session must not abort the whole migration; retain the
                        // legacy file until every nonempty conversation has been imported.
                        runCatching {
                            if (loadWithoutMigration(session.id)?.messages?.isNotEmpty() == true) return@runCatching
                            withLockedDatabase(session.id, create = true) { db ->
                                db.beginWriteTransactionWithRetry()
                                try {
                                    writeSession(db, null, session)
                                    db.setTransactionSuccessful()
                                } finally {
                                    db.endTransaction()
                                }
                            }
                        }.onFailure {
                            allMigrated = false
                            android.util.Log.w(LOG_TAG, "Failed to migrate session ${session.id}", it)
                        }
                    }
                }
                if (allMigrated) moveLegacyFile(legacy)
            }
        }
    }

    private fun loadWithoutMigration(sessionId: String): AiEditorSession? =
        withDatabase(sessionId, create = false) { db -> readSession(db, sessionId) }

    private fun readUpdatedAt(db: SQLiteDatabase): Long = db.query(
        TABLE_CONVERSATION,
        arrayOf(COLUMN_UPDATED_AT),
        null,
        null,
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun databaseFiles(): List<File> = root.listFiles()
        ?.filter { it.isFile && it.extension == DATABASE_EXTENSION }
        .orEmpty()

    private fun <T> withDatabase(sessionId: String, create: Boolean, block: (SQLiteDatabase) -> T): T? {
        val file = databaseFile(sessionId)
        if (!create && !file.isFile) return null
        return withDatabaseFile(file, create, block)
    }

    private fun <T> withDatabaseFile(
        file: File,
        create: Boolean = false,
        block: (SQLiteDatabase) -> T,
    ): T? = synchronized(lockFor(file)) {
        if (!create && !file.isFile) return@synchronized null
        file.parentFile?.mkdirs()
        // Readers also open/configure the database and may initialize its schema. Serialize the
        // entire handle lifetime locally, not just writes; SQLite handles other processes below.
        val database = retryDatabaseLocked {
            val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING or
                if (create) SQLiteDatabase.CREATE_IF_NECESSARY else 0
            val opened = SQLiteDatabase.openDatabase(file.path, null, flags)
            try {
                opened.execPragma("PRAGMA busy_timeout=1000")
                opened.setForeignKeyConstraintsEnabled(true)
                opened.execPragma("PRAGMA synchronous=NORMAL")
                ensureSchema(opened)
                opened
            } catch (error: Throwable) {
                opened.close()
                throw error
            }
        }
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun <T> withLockedDatabase(sessionId: String, create: Boolean, block: (SQLiteDatabase) -> T): T? =
        withDatabase(sessionId, create, block)

    /** Retry only before executing a caller's transform; replaying a whole mutation is unsafe. */
    private fun SQLiteDatabase.beginWriteTransactionWithRetry() {
        retryDatabaseLocked { beginTransactionNonExclusive() }
    }

    private fun <T> retryDatabaseLocked(block: () -> T): T {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
        var delayMillis = 25L
        while (true) {
            try {
                return block()
            } catch (error: SQLiteDatabaseLockedException) {
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0) throw error
                // Thread.sleep preserves cancellation by throwing instead of swallowing interrupts.
                Thread.sleep(minOf(delayMillis, remaining))
                delayMillis = minOf(delayMillis * 2, 250L)
            }
        }
    }

    /**
     * Runs a connection-level PRAGMA. Some PRAGMAs (for example `busy_timeout`) return a result row,
     * and [SQLiteDatabase.execSQL] rejects those with "Queries can be performed using SQLiteDatabase
     * query or rawQuery methods only."; `rawQuery` accepts both forms.
     */
    private fun SQLiteDatabase.execPragma(statement: String) {
        rawQuery(statement, null).use { it.moveToFirst() }
    }

    /**
     * Brings any database file up to the current schema before it is used.
     *
     * The schema is not only created for brand-new databases: a process death during an earlier
     * write can leave a database file that exists but has no tables, and older builds may have
     * created a subset of them. Opening such a file with `create = false` used to skip schema
     * creation, so the first query failed with "no such table: agent_jobs" and crashed the app.
     * `PRAGMA user_version` makes the check a single cheap read; every statement in
     * [createSchema] is `IF NOT EXISTS`, so re-running is safe.
     */
    private fun ensureSchema(db: SQLiteDatabase) {
        if (db.version >= SCHEMA_VERSION) return
        db.beginWriteTransactionWithRetry()
        try {
            createSchema(db)
            if (db.version < 2) {
                addColumnIfMissing(db, TABLE_MESSAGES, COLUMN_NOTICE, "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, TABLE_MESSAGES, COLUMN_PARTS_JSON, "TEXT NOT NULL DEFAULT ''")
            }
            addColumnIfMissing(db, TABLE_MESSAGES, "changed_revision", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, TABLE_CONVERSATION, "context_revision", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, TABLE_CONVERSATION, "inbox_json", "TEXT NOT NULL DEFAULT '[]'")
            // Images ride along with a message as base64 plus a MIME type; older rows stay text.
            addColumnIfMissing(db, TABLE_ATTACHMENTS, COLUMN_MIME, "TEXT NOT NULL DEFAULT 'text/plain'")
            addColumnIfMissing(db, TABLE_ATTACHMENTS, COLUMN_ATTACHMENT_DATA, "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, TABLE_TOOLS, COLUMN_IMAGE_ATTACHMENTS_JSON, "TEXT NOT NULL DEFAULT '[]'")
            db.version = SCHEMA_VERSION
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                revision INTEGER NOT NULL DEFAULT 0,
                context_json TEXT,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY NOT NULL,
                from_user INTEGER NOT NULL,
                text TEXT NOT NULL,
                thinking TEXT NOT NULL,
                streaming INTEGER NOT NULL,
                failed INTEGER NOT NULL,
                error_message TEXT NOT NULL,
                retry_message TEXT NOT NULL,
                notice INTEGER NOT NULL DEFAULT 0,
                parts_json TEXT NOT NULL DEFAULT '',
                model_name TEXT NOT NULL,
                elapsed_ms INTEGER,
                context_tokens INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS attachments (
                message_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                name TEXT NOT NULL,
                content TEXT NOT NULL,
                mime TEXT NOT NULL DEFAULT 'text/plain',
                data TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(message_id, position),
                FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tool_calls (
                message_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                id TEXT NOT NULL,
                name TEXT NOT NULL,
                arguments TEXT NOT NULL,
                preceding_text TEXT NOT NULL,
                result TEXT,
                failed INTEGER NOT NULL,
                truncated INTEGER NOT NULL,
                image_attachments_json TEXT NOT NULL DEFAULT '[]',
                PRIMARY KEY(message_id, position),
                FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_jobs (
                job_id TEXT PRIMARY KEY NOT NULL,
                conversation_id TEXT NOT NULL,
                mod_id TEXT NOT NULL,
                mod_name TEXT NOT NULL,
                storage_path TEXT NOT NULL,
                assistant_message_id INTEGER NOT NULL,
                status TEXT NOT NULL,
                error_message TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_id ON messages(id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_jobs_status ON agent_jobs(status)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS execution_steps (
                message_id INTEGER NOT NULL,
                id TEXT NOT NULL,
                position INTEGER NOT NULL,
                data_json TEXT NOT NULL,
                PRIMARY KEY(message_id, id),
                FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_execution_step_order ON execution_steps(message_id, position)")
    }

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        table: String,
        column: String,
        definition: String,
    ) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }
                .any { it == column }
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }

    /**
     * CursorWindow limits the size of an entire row, not the database value. Project large
     * strings as NULL and fetch their UTF-8 bytes in bounded slices on demand. This also reads
     * already-written oversized rows without a migration or truncating conversation content.
     */
    private fun SQLiteDatabase.queryWindowSafe(
        table: String,
        columns: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        groupBy: String?,
        having: String?,
        orderBy: String?,
        limit: String? = null,
    ): Cursor {
        val names = columns ?: rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }.toTypedArray()
        }
        val projection = names.map { column ->
            "CASE WHEN typeof($column) = 'text' AND length(CAST($column AS BLOB)) > 16384 " +
                "THEN NULL ELSE $column END AS $column"
        } + "rowid AS _storage_rowid"
        val cursor = query(table, projection.toTypedArray(), selection, selectionArgs, groupBy, having, orderBy, limit)
        return object : CursorWrapper(cursor) {
            private fun rowArgs() = arrayOf(cursor.getLong(names.size).toString())

            override fun isNull(columnIndex: Int): Boolean {
                if (!cursor.isNull(columnIndex) || columnIndex >= names.size) return cursor.isNull(columnIndex)
                return rawQuery("SELECT ${names[columnIndex]} IS NULL FROM $table WHERE rowid = ?", rowArgs()).use {
                    !it.moveToFirst() || it.getInt(0) != 0
                }
            }

            override fun getString(columnIndex: Int): String? {
                if (!cursor.isNull(columnIndex) || columnIndex >= names.size) return cursor.getString(columnIndex)
                val column = names[columnIndex]
                val size = rawQuery("SELECT length(CAST($column AS BLOB)) FROM $table WHERE rowid = ?", rowArgs()).use {
                    if (!it.moveToFirst() || it.isNull(0)) return null
                    it.getInt(0)
                }
                val bytes = ByteArrayOutputStream()
                var offset = 0
                while (offset < size) {
                    val chunk = rawQuery(
                        "SELECT substr(CAST($column AS BLOB), ?, 16384) FROM $table WHERE rowid = ?",
                        arrayOf((offset + 1).toString(), rowArgs()[0]),
                    ).use {
                        check(it.moveToFirst()) { "Conversation row disappeared while reading" }
                        it.getBlob(0)
                    }
                    check(chunk.isNotEmpty()) { "Incomplete conversation value" }
                    bytes.write(chunk)
                    offset += chunk.size
                }
                return bytes.toString(StandardCharsets.UTF_8.name())
            }
        }
    }

    private fun readSession(db: SQLiteDatabase, expectedId: String?, previous: AiEditorSession? = null): AiEditorSession? {
        val metadata = db.queryWindowSafe(
            TABLE_CONVERSATION,
            arrayOf(COLUMN_ID, COLUMN_TITLE, COLUMN_REVISION, "context_revision"),
            expectedId?.let { "$COLUMN_ID = ?" },
            expectedId?.let { arrayOf(it) },
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            ConversationMetadata(
                id = cursor.getString(0),
                title = cursor.getString(1),
                revision = cursor.getLong(2),
                context = if (previous != null && cursor.getLong(3) <= previous.revision) previous.context else
                    db.queryWindowSafe(TABLE_CONVERSATION, arrayOf(COLUMN_CONTEXT), expectedId?.let { "$COLUMN_ID = ?" },
                        expectedId?.let { arrayOf(it) }, null, null, null, "1").use { contextCursor ->
                        if (!contextCursor.moveToFirst()) null else contextCursor.getStringOrNull(0)?.let { value ->
                            runCatching { json.decodeFromString<AgentContextState>(value) }.getOrNull()
                        }
                    },
            )
        }
        val messages = if (previous == null) {
            val attachments = readAttachments(db)
            val tools = readTools(db)
            val steps = readExecutionSteps(db)
            db.queryWindowSafe(TABLE_MESSAGES, null, null, null, null, null, "$COLUMN_MESSAGE_ID ASC").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.readMessage(attachments, tools, steps)) }
            }
        } else {
            val cached = previous.messages.associateBy { it.id }
            val revisions = db.query(TABLE_MESSAGES, arrayOf(COLUMN_MESSAGE_ID, "changed_revision"),
                null, null, null, null, "$COLUMN_MESSAGE_ID ASC").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getLong(1)) }
            }
            revisions.mapNotNull { (id, revision) ->
                cached[id]?.takeIf { revision <= previous.revision } ?: readMessage(db, id)
            }
        }
        return AiEditorSession(
            id = metadata.id,
            messages = messages,
            title = metadata.title,
            revision = metadata.revision,
            context = metadata.context,
            inbox = db.queryWindowSafe(TABLE_CONVERSATION, arrayOf("inbox_json"), null, null, null, null, null, "1").use {
                if (it.moveToFirst()) json.decodeFromString<List<AiInboxItem>>(it.getString(0)) else emptyList()
            },
        )
    }

    private fun readSessionSummary(db: SQLiteDatabase): AiEditorSession? {
        val metadata = db.queryWindowSafe(
            TABLE_CONVERSATION,
            arrayOf(COLUMN_ID, COLUMN_TITLE, COLUMN_REVISION),
            null,
            null,
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            Triple(cursor.getString(0), cursor.getString(1), cursor.getLong(2))
        }
        val firstMessage = db.queryWindowSafe(
            TABLE_MESSAGES,
            null,
            "$COLUMN_FROM_USER = 1",
            null,
            null,
            null,
            "$COLUMN_MESSAGE_ID ASC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE_ID))
                cursor.readMessage(
                    readAttachments(db, id),
                    readTools(db, id),
                )
            }
        }
        return AiEditorSession(
            id = metadata.first,
            title = metadata.second,
            revision = metadata.third,
            messages = listOfNotNull(firstMessage),
        )
    }

    private fun readMessage(db: SQLiteDatabase, messageId: Long, includeExecutionSteps: Boolean = true): AiEditorMessage? {
        val attachments = readAttachments(db, messageId)
        val tools = readTools(db, messageId)
        val steps = if (includeExecutionSteps) readExecutionSteps(db, messageId) else emptyMap()
        return db.queryWindowSafe(
            TABLE_MESSAGES,
            null,
            "$COLUMN_MESSAGE_ID = ?",
            arrayOf(messageId.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.readMessage(attachments, tools, steps)
        }
    }

    private fun writeSession(db: SQLiteDatabase, previous: AiEditorSession?, next: AiEditorSession) {
        ensureConversationRow(db, next.id)
        val metadata = ContentValues().apply {
            put(COLUMN_ID, next.id)
            put(COLUMN_TITLE, next.title)
            put("inbox_json", json.encodeToString(next.inbox))
            put(COLUMN_REVISION, next.revision)
            put("context_revision", if (previous == null || previous.context != next.context) next.revision else
                db.rawQuery("SELECT context_revision FROM conversation LIMIT 1", null).use { if (it.moveToFirst()) it.getLong(0) else 0L })
            put(COLUMN_UPDATED_AT, System.currentTimeMillis())
            next.context?.let { put(COLUMN_CONTEXT, json.encodeToString(it)) } ?: putNull(COLUMN_CONTEXT)
        }
        check(db.insertWithOnConflict(TABLE_CONVERSATION, null, metadata, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
            "Failed to save conversation metadata"
        }

        val previousMessages = previous?.messages?.associateBy { it.id }.orEmpty()
        val nextIds = next.messages.mapTo(HashSet()) { it.id }
        previousMessages.keys.filterNot(nextIds::contains).forEach { messageId ->
            db.delete(TABLE_ATTACHMENTS, "$COLUMN_FOREIGN_MESSAGE_ID = ?", arrayOf(messageId.toString()))
            db.delete(TABLE_TOOLS, "$COLUMN_FOREIGN_MESSAGE_ID = ?", arrayOf(messageId.toString()))
            db.delete(TABLE_STEPS, "$COLUMN_FOREIGN_MESSAGE_ID = ?", arrayOf(messageId.toString()))
            db.delete(TABLE_MESSAGES, "$COLUMN_MESSAGE_ID = ?", arrayOf(messageId.toString()))
        }
        next.messages.forEach { message ->
            if (previousMessages[message.id] != message) writeMessage(db, message, next.revision, previousMessages[message.id])
        }
    }

    private fun ensureConversationRow(db: SQLiteDatabase, sessionId: String) {
        val values = ContentValues().apply {
            put(COLUMN_ID, sessionId)
            put(COLUMN_TITLE, "")
            put(COLUMN_REVISION, 0L)
            put(COLUMN_UPDATED_AT, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_CONVERSATION, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun bumpRevision(db: SQLiteDatabase) {
        db.execSQL(
            "UPDATE $TABLE_CONVERSATION SET $COLUMN_REVISION = $COLUMN_REVISION + 1, " +
                "$COLUMN_UPDATED_AT = ?",
            arrayOf(System.currentTimeMillis()),
        )
    }

    private fun writeMessage(db: SQLiteDatabase, message: AiEditorMessage, revision: Long? = null, previous: AiEditorMessage? = null) {
        val values = ContentValues().apply {
            put("changed_revision", revision ?: db.rawQuery("SELECT revision + 1 FROM conversation LIMIT 1", null).use {
                if (it.moveToFirst()) it.getLong(0) else 1L
            })
            put(COLUMN_MESSAGE_ID, message.id)
            put(COLUMN_FROM_USER, if (message.fromUser) 1 else 0)
            put(COLUMN_TEXT, message.text)
            put(COLUMN_THINKING, message.thinking)
            put(COLUMN_STREAMING, if (message.streaming) 1 else 0)
            put(COLUMN_FAILED, if (message.failed) 1 else 0)
            put(COLUMN_ERROR_MESSAGE, message.errorMessage)
            put(COLUMN_RETRY_MESSAGE, message.retryMessage)
            put(COLUMN_NOTICE, if (message.notice) 1 else 0)
            put(COLUMN_PARTS_JSON, json.encodeToString(coalesceAiParts(message.parts)))
            put(COLUMN_MODEL_NAME, message.modelName)
            message.elapsedMs?.let { put(COLUMN_ELAPSED_MS, it) } ?: putNull(COLUMN_ELAPSED_MS)
            message.contextTokens?.let { put(COLUMN_CONTEXT_TOKENS, it) } ?: putNull(COLUMN_CONTEXT_TOKENS)
        }
        // REPLACE deletes the parent row and cascades all telemetry on every stream delta.
        if (db.update(TABLE_MESSAGES, values, "id = ?", arrayOf(message.id.toString())) == 0) {
            db.insertOrThrow(TABLE_MESSAGES, null, values)
        }
        if (previous?.executionSteps !== message.executionSteps) {
            val old = previous?.executionSteps?.associateBy { it.id }.orEmpty()
            val nextIds = message.executionSteps.mapTo(HashSet()) { it.id }
            old.keys.filterNot(nextIds::contains).forEach { id ->
                db.delete(TABLE_STEPS, "message_id = ? AND id = ?", arrayOf(message.id.toString(), id))
            }
            message.executionSteps.forEach { step ->
                if (old[step.id] != step) writeExecutionStep(db, message.id, step)
            }
        }
        db.delete(TABLE_ATTACHMENTS, "$COLUMN_FOREIGN_MESSAGE_ID = ?", arrayOf(message.id.toString()))
        message.attachments.forEachIndexed { position, attachment ->
            db.insertOrThrow(TABLE_ATTACHMENTS, null, ContentValues().apply {
                put(COLUMN_FOREIGN_MESSAGE_ID, message.id)
                put(COLUMN_POSITION, position)
                put(COLUMN_NAME, attachment.name)
                put(COLUMN_CONTENT, attachment.content)
                put(COLUMN_MIME, attachment.mime)
                put(COLUMN_ATTACHMENT_DATA, attachment.data)
            })
        }
        db.delete(TABLE_TOOLS, "$COLUMN_FOREIGN_MESSAGE_ID = ?", arrayOf(message.id.toString()))
        message.tools.forEachIndexed { position, tool ->
            db.insertOrThrow(TABLE_TOOLS, null, ContentValues().apply {
                put(COLUMN_FOREIGN_MESSAGE_ID, message.id)
                put(COLUMN_POSITION, position)
                put(COLUMN_TOOL_ID, tool.id)
                put(COLUMN_TOOL_NAME, tool.name)
                put(COLUMN_ARGUMENTS, tool.arguments)
                put(COLUMN_PRECEDING_TEXT, tool.precedingText)
                tool.result?.let { put(COLUMN_RESULT, it) } ?: putNull(COLUMN_RESULT)
                put(COLUMN_FAILED, if (tool.failed) 1 else 0)
                put(COLUMN_TRUNCATED, if (tool.truncated) 1 else 0)
                put(COLUMN_IMAGE_ATTACHMENTS_JSON, json.encodeToString(tool.imageAttachments))
            })
        }
    }

    private fun readAttachments(db: SQLiteDatabase, messageId: Long? = null): Map<Long, List<AiAttachment>> {
        val result = LinkedHashMap<Long, MutableList<AiAttachment>>()
        db.queryWindowSafe(
            TABLE_ATTACHMENTS,
            arrayOf(COLUMN_FOREIGN_MESSAGE_ID, COLUMN_NAME, COLUMN_CONTENT, COLUMN_MIME, COLUMN_ATTACHMENT_DATA),
            messageId?.let { "$COLUMN_FOREIGN_MESSAGE_ID = ?" },
            messageId?.let { arrayOf(it.toString()) },
            null,
            null,
            "$COLUMN_FOREIGN_MESSAGE_ID ASC, $COLUMN_POSITION ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) result.getOrPut(cursor.getLong(0)) { ArrayList() }
                .add(AiAttachment(cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4)))
        }
        return result
    }

    private fun readTools(db: SQLiteDatabase, messageId: Long? = null): Map<Long, List<AiToolCall>> {
        val result = LinkedHashMap<Long, MutableList<AiToolCall>>()
        db.queryWindowSafe(
            TABLE_TOOLS,
            arrayOf(
                COLUMN_FOREIGN_MESSAGE_ID,
                COLUMN_TOOL_ID,
                COLUMN_TOOL_NAME,
                COLUMN_ARGUMENTS,
                COLUMN_PRECEDING_TEXT,
                COLUMN_RESULT,
                COLUMN_FAILED,
                COLUMN_TRUNCATED,
                COLUMN_IMAGE_ATTACHMENTS_JSON,
            ),
            messageId?.let { "$COLUMN_FOREIGN_MESSAGE_ID = ?" },
            messageId?.let { arrayOf(it.toString()) },
            null,
            null,
            "$COLUMN_FOREIGN_MESSAGE_ID ASC, $COLUMN_POSITION ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) result.getOrPut(cursor.getLong(0)) { ArrayList() }
                .add(
                    AiToolCall(
                        id = cursor.getString(1),
                        name = cursor.getString(2),
                        arguments = cursor.getString(3),
                        precedingText = cursor.getString(4),
                        result = cursor.getStringOrNull(5),
                        failed = cursor.getInt(6) != 0,
                        truncated = cursor.getInt(7) != 0,
                        imageAttachments = runCatching {
                            json.decodeFromString<List<AiAttachment>>(cursor.getString(8))
                        }.getOrDefault(emptyList()),
                    ),
                )
        }
        return result
    }

    private fun Cursor.readMessage(
        attachments: Map<Long, List<AiAttachment>>,
        tools: Map<Long, List<AiToolCall>>,
        steps: Map<Long, List<AgentExecutionStep>> = emptyMap(),
    ): AiEditorMessage {
        val id = getLong(getColumnIndexOrThrow(COLUMN_MESSAGE_ID))
        return AiEditorMessage(
            id = id,
            fromUser = getInt(getColumnIndexOrThrow(COLUMN_FROM_USER)) != 0,
            text = getString(getColumnIndexOrThrow(COLUMN_TEXT)),
            attachments = attachments[id].orEmpty(),
            thinking = getString(getColumnIndexOrThrow(COLUMN_THINKING)),
            streaming = getInt(getColumnIndexOrThrow(COLUMN_STREAMING)) != 0,
            failed = getInt(getColumnIndexOrThrow(COLUMN_FAILED)) != 0,
            errorMessage = getString(getColumnIndexOrThrow(COLUMN_ERROR_MESSAGE)),
            retryMessage = getString(getColumnIndexOrThrow(COLUMN_RETRY_MESSAGE)),
            notice = getInt(getColumnIndexOrThrow(COLUMN_NOTICE)) != 0,
            parts = getString(getColumnIndexOrThrow(COLUMN_PARTS_JSON)).let { encoded ->
                runCatching { json.decodeFromString<List<AiMessagePart>>(encoded) }.getOrDefault(emptyList())
            },
            tools = tools[id].orEmpty(),
            modelName = getString(getColumnIndexOrThrow(COLUMN_MODEL_NAME)),
            elapsedMs = getLongOrNull(COLUMN_ELAPSED_MS),
            contextTokens = getIntOrNull(COLUMN_CONTEXT_TOKENS),
            executionSteps = steps[id].orEmpty(),
        )
    }

    private fun writeExecutionStep(db: SQLiteDatabase, messageId: Long, step: AgentExecutionStep) {
        val data = json.encodeToString(step)
        if (db.update(TABLE_STEPS, ContentValues().apply { put("data_json", data) },
                "message_id = ? AND id = ?", arrayOf(messageId.toString(), step.id)) == 0) {
            val position = db.rawQuery("SELECT COALESCE(MAX(position), -1) + 1 FROM execution_steps WHERE message_id = ?",
                arrayOf(messageId.toString())).use { it.moveToFirst(); it.getLong(0) }
            db.insertOrThrow(TABLE_STEPS, null, ContentValues().apply {
                put("message_id", messageId)
                put("id", step.id)
                put("position", position)
                put("data_json", data)
            })
        }
    }

    private fun readExecutionSteps(db: SQLiteDatabase, messageId: Long? = null): Map<Long, List<AgentExecutionStep>> {
        val result = LinkedHashMap<Long, MutableList<AgentExecutionStep>>()
        db.query(TABLE_STEPS, arrayOf("message_id", "data_json"), messageId?.let { "message_id = ?" },
            messageId?.let { arrayOf(it.toString()) }, null, null, "message_id ASC, position ASC").use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { json.decodeFromString<AgentExecutionStep>(cursor.getString(1)) }.getOrNull()?.let { step ->
                    result.getOrPut(cursor.getLong(0)) { ArrayList() }.add(step)
                }
            }
        }
        return result
    }

    private fun readJobs(db: SQLiteDatabase): List<AiAgentJobRecord> = db.queryWindowSafe(
        TABLE_JOBS,
        null,
        null,
        null,
        null,
        null,
        "$COLUMN_JOB_UPDATED_AT DESC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                AiAgentJobRecord(
                    jobId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_JOB_ID)),
                    conversationId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_JOB_CONVERSATION_ID)),
                    modId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_JOB_MOD_ID)),
                    modName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_JOB_MOD_NAME)),
                    storagePath = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_JOB_STORAGE_PATH)),
                    assistantMessageId = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_JOB_ASSISTANT_ID)),
                    status = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_JOB_STATUS)),
                    errorMessage = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_JOB_ERROR)),
                    updatedAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_JOB_UPDATED_AT)),
                ),
            )
        }
    }

    private fun writeJob(db: SQLiteDatabase, record: AiAgentJobRecord) {
        check(db.insertWithOnConflict(TABLE_JOBS, null, ContentValues().apply {
            put(COLUMN_JOB_ID, record.jobId)
            put(COLUMN_JOB_CONVERSATION_ID, record.conversationId)
            put(COLUMN_JOB_MOD_ID, record.modId)
            put(COLUMN_JOB_MOD_NAME, record.modName)
            put(COLUMN_JOB_STORAGE_PATH, record.storagePath)
            put(COLUMN_JOB_ASSISTANT_ID, record.assistantMessageId)
            put(COLUMN_JOB_STATUS, record.status)
            put(COLUMN_JOB_ERROR, record.errorMessage)
            put(COLUMN_JOB_UPDATED_AT, record.updatedAtMillis)
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Failed to save AI job" }
    }

    private fun databaseFile(sessionId: String): File {
        require(sessionId.isNotBlank()) { "Conversation id must not be blank" }
        val readable = sessionId.map { character ->
            if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
        }.joinToString("").take(80).ifBlank { "conversation" }
        return File(root, "$readable-${shortDigest(sessionId)}.$DATABASE_EXTENSION")
    }

    private fun lockFor(file: File): Any = locks.computeIfAbsent(file.absolutePath) { Any() }

    private fun moveLegacyFile(file: File) {
        val migrated = File(file.parentFile, "${file.name}.migrated")
        runCatching {
            Files.move(
                file.toPath(),
                migrated.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun shortDigest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .take(8)
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class ConversationMetadata(
        val id: String,
        val title: String,
        val revision: Long,
        val context: AgentContextState?,
    )

    private companion object {
        const val LOG_TAG = "AiModStore"
        /** Bump when [createSchema] changes so existing files are migrated on next open. */
        const val SCHEMA_VERSION = 7
        const val DATABASE_EXTENSION = "db"
        const val LEGACY_CONVERSATIONS_FILE = "conversations.json"
        const val LEGACY_JOBS_FILE = "agent_jobs.json"
        const val TABLE_CONVERSATION = "conversation"
        const val TABLE_MESSAGES = "messages"
        const val TABLE_ATTACHMENTS = "attachments"
        const val TABLE_TOOLS = "tool_calls"
        const val TABLE_JOBS = "agent_jobs"
        const val TABLE_STEPS = "execution_steps"
        const val COLUMN_ID = "id"
        const val COLUMN_TITLE = "title"
        const val COLUMN_REVISION = "revision"
        const val COLUMN_CONTEXT = "context_json"
        const val COLUMN_UPDATED_AT = "updated_at"
        const val COLUMN_MESSAGE_ID = "id"
        /** Child tables (attachments, tool_calls) reference the owning message by this column. */
        const val COLUMN_FOREIGN_MESSAGE_ID = "message_id"
        const val COLUMN_FROM_USER = "from_user"
        const val COLUMN_TEXT = "text"
        const val COLUMN_THINKING = "thinking"
        const val COLUMN_STREAMING = "streaming"
        const val COLUMN_FAILED = "failed"
        const val COLUMN_ERROR_MESSAGE = "error_message"
        const val COLUMN_RETRY_MESSAGE = "retry_message"
        const val COLUMN_NOTICE = "notice"
        const val COLUMN_PARTS_JSON = "parts_json"
        const val COLUMN_MODEL_NAME = "model_name"
        const val COLUMN_ELAPSED_MS = "elapsed_ms"
        const val COLUMN_CONTEXT_TOKENS = "context_tokens"
        const val COLUMN_POSITION = "position"
        const val COLUMN_NAME = "name"
        const val COLUMN_CONTENT = "content"
        const val COLUMN_MIME = "mime"
        const val COLUMN_ATTACHMENT_DATA = "data"
        const val COLUMN_TOOL_ID = "id"
        const val COLUMN_TOOL_NAME = "name"
        const val COLUMN_ARGUMENTS = "arguments"
        const val COLUMN_PRECEDING_TEXT = "preceding_text"
        const val COLUMN_RESULT = "result"
        const val COLUMN_TRUNCATED = "truncated"
        const val COLUMN_IMAGE_ATTACHMENTS_JSON = "image_attachments_json"
        const val COLUMN_JOB_ID = "job_id"
        const val COLUMN_JOB_CONVERSATION_ID = "conversation_id"
        const val COLUMN_JOB_MOD_ID = "mod_id"
        const val COLUMN_JOB_MOD_NAME = "mod_name"
        const val COLUMN_JOB_STORAGE_PATH = "storage_path"
        const val COLUMN_JOB_ASSISTANT_ID = "assistant_message_id"
        const val COLUMN_JOB_STATUS = "status"
        const val COLUMN_JOB_ERROR = "error_message"
        const val COLUMN_JOB_UPDATED_AT = "updated_at"
        val locks = ConcurrentHashMap<String, Any>()
    }
}

private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

private fun Cursor.getLongOrNull(column: String): Long? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getLong(index)
}

private fun Cursor.getIntOrNull(column: String): Int? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getInt(index)
}

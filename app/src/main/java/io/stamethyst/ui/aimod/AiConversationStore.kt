package io.stamethyst.ui.aimod

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.stamethyst.backend.llm.AgentContextState
import io.stamethyst.backend.workshop.WorkshopJsonFileStore
import java.io.File
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
    fun loadSummaries(): List<AiEditorSession> {
        migrateLegacyConversations()
        return databaseFiles()
            .mapNotNull { file ->
                withDatabaseFile(file) { db ->
                    readSessionSummary(db)?.let { summary ->
                        summary to readUpdatedAt(db)
                    }
                }
            }
            .filter { it.first.hasUserPrompt() }
            .sortedBy { it.second }
            .map { it.first }
    }

    fun load(sessionId: String): AiEditorSession? {
        migrateLegacyConversations()
        return withDatabase(sessionId, create = false) { db -> readSession(db, sessionId) }
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
            db.beginTransactionNonExclusive()
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
        transform: (AiEditorMessage) -> AiEditorMessage,
    ): AiEditorMessage? {
        migrateLegacyConversations()
        return withLockedDatabase(sessionId, create = false) { db ->
            db.beginTransactionNonExclusive()
            try {
                val previous = readMessage(db, messageId) ?: return@withLockedDatabase null
                val next = transform(previous)
                writeMessage(db, next)
                bumpRevision(db)
                db.setTransactionSuccessful()
                next
            } finally {
                db.endTransaction()
            }
        }
    }

    fun updateContext(sessionId: String, context: AgentContextState): Boolean {
        migrateLegacyConversations()
        return withLockedDatabase(sessionId, create = false) { db ->
            db.beginTransactionNonExclusive()
            try {
                val values = ContentValues().apply {
                    put(COLUMN_CONTEXT, json.encodeToString(context))
                    put(COLUMN_UPDATED_AT, System.currentTimeMillis())
                }
                val updated = db.update(TABLE_CONVERSATION, values, "id = ?", arrayOf(sessionId))
                if (updated > 0) {
                    db.execSQL(
                        "UPDATE $TABLE_CONVERSATION SET $COLUMN_REVISION = $COLUMN_REVISION + 1 WHERE $COLUMN_ID = ?",
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
            db.beginTransactionNonExclusive()
            try {
                ensureConversationRow(db, record.conversationId)
                writeJob(db, record)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    internal fun updateJob(jobId: String, transform: (AiAgentJobRecord) -> AiAgentJobRecord) {
        migrateLegacyConversations()
        val current = findJob(jobId) ?: return
        withLockedDatabase(current.conversationId, create = false) { db ->
            db.beginTransactionNonExclusive()
            try {
                val stored = readJobs(db).firstOrNull { it.jobId == jobId } ?: return@withLockedDatabase
                writeJob(db, transform(stored).copy(updatedAtMillis = System.currentTimeMillis()))
                db.setTransactionSuccessful()
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
            db.beginTransactionNonExclusive()
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
                sessions.forEach { session ->
                    // A session file may exist but be empty: an earlier failed write can leave a
                    // conversation row with no messages. Only trust a stored copy that has content,
                    // otherwise the legacy JSON import would be skipped and history lost.
                    val alreadyMigrated = loadWithoutMigration(session.id)?.messages?.isNotEmpty() == true
                    if (session.hasUserPrompt() && !alreadyMigrated) {
                        // One unreadable session must not abort the whole migration; the legacy file
                        // is only renamed once every session has been attempted.
                        runCatching {
                            withLockedDatabase(session.id, create = true) { db ->
                                db.beginTransactionNonExclusive()
                                try {
                                    writeSession(db, null, session)
                                    db.setTransactionSuccessful()
                                } finally {
                                    db.endTransaction()
                                }
                            }
                        }.onFailure {
                            android.util.Log.w(LOG_TAG, "Failed to migrate session ${session.id}", it)
                        }
                    }
                }
                moveLegacyFile(legacy)
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
    ): T? {
        if (!create && !file.isFile) return null
        file.parentFile?.mkdirs()
        val flags = SQLiteDatabase.OPEN_READWRITE or
            if (create) SQLiteDatabase.CREATE_IF_NECESSARY else 0
        val database = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, flags)
        }.getOrNull() ?: return null
        return try {
            database.enableWriteAheadLogging()
            database.setForeignKeyConstraintsEnabled(true)
            // PRAGMA statements that return a row must not go through execSQL, which only accepts
            // statements that change rows. rawQuery tolerates both forms.
            database.execPragma("PRAGMA synchronous=NORMAL")
            database.execPragma("PRAGMA busy_timeout=5000")
            ensureSchema(database)
            block(database)
        } finally {
            database.close()
        }
    }

    private fun <T> withLockedDatabase(sessionId: String, create: Boolean, block: (SQLiteDatabase) -> T): T? {
        val file = databaseFile(sessionId)
        return synchronized(lockFor(file)) {
            withDatabaseFile(file, create, block)
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
        db.beginTransactionNonExclusive()
        try {
            createSchema(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.version = SCHEMA_VERSION
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
    }

    private fun readSession(db: SQLiteDatabase, expectedId: String?): AiEditorSession? {
        val metadata = db.query(
            TABLE_CONVERSATION,
            arrayOf(COLUMN_ID, COLUMN_TITLE, COLUMN_REVISION, COLUMN_CONTEXT),
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
                context = cursor.getStringOrNull(3)?.let { value ->
                    runCatching { json.decodeFromString<AgentContextState>(value) }.getOrNull()
                },
            )
        }
        val attachments = readAttachments(db)
        val tools = readTools(db)
        val messages = db.query(TABLE_MESSAGES, null, null, null, null, null, "$COLUMN_MESSAGE_ID ASC")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.readMessage(attachments, tools))
                }
            }
        return AiEditorSession(
            id = metadata.id,
            messages = messages,
            title = metadata.title,
            revision = metadata.revision,
            context = metadata.context,
        )
    }

    private fun readSessionSummary(db: SQLiteDatabase): AiEditorSession? {
        val metadata = db.query(
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
        val firstMessage = db.query(
            TABLE_MESSAGES,
            null,
            null,
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

    private fun readMessage(db: SQLiteDatabase, messageId: Long): AiEditorMessage? {
        val attachments = readAttachments(db, messageId)
        val tools = readTools(db, messageId)
        return db.query(
            TABLE_MESSAGES,
            null,
            "$COLUMN_MESSAGE_ID = ?",
            arrayOf(messageId.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.readMessage(attachments, tools)
        }
    }

    private fun writeSession(db: SQLiteDatabase, previous: AiEditorSession?, next: AiEditorSession) {
        ensureConversationRow(db, next.id)
        val metadata = ContentValues().apply {
            put(COLUMN_ID, next.id)
            put(COLUMN_TITLE, next.title)
            put(COLUMN_REVISION, next.revision)
            put(COLUMN_UPDATED_AT, System.currentTimeMillis())
            next.context?.let { put(COLUMN_CONTEXT, json.encodeToString(it)) } ?: putNull(COLUMN_CONTEXT)
        }
        db.insertWithOnConflict(TABLE_CONVERSATION, null, metadata, SQLiteDatabase.CONFLICT_REPLACE)

        val previousMessages = previous?.messages?.associateBy { it.id }.orEmpty()
        val nextIds = next.messages.mapTo(HashSet()) { it.id }
        previousMessages.keys.filterNot(nextIds::contains).forEach { messageId ->
            db.delete(TABLE_ATTACHMENTS, "$COLUMN_FOREIGN_MESSAGE_ID = ?", arrayOf(messageId.toString()))
            db.delete(TABLE_TOOLS, "$COLUMN_FOREIGN_MESSAGE_ID = ?", arrayOf(messageId.toString()))
            db.delete(TABLE_MESSAGES, "$COLUMN_MESSAGE_ID = ?", arrayOf(messageId.toString()))
        }
        next.messages.forEach { message ->
            if (previousMessages[message.id] != message) writeMessage(db, message)
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

    private fun writeMessage(db: SQLiteDatabase, message: AiEditorMessage) {
        val values = ContentValues().apply {
            put(COLUMN_MESSAGE_ID, message.id)
            put(COLUMN_FROM_USER, if (message.fromUser) 1 else 0)
            put(COLUMN_TEXT, message.text)
            put(COLUMN_THINKING, message.thinking)
            put(COLUMN_STREAMING, if (message.streaming) 1 else 0)
            put(COLUMN_FAILED, if (message.failed) 1 else 0)
            put(COLUMN_ERROR_MESSAGE, message.errorMessage)
            put(COLUMN_RETRY_MESSAGE, message.retryMessage)
            put(COLUMN_MODEL_NAME, message.modelName)
            message.elapsedMs?.let { put(COLUMN_ELAPSED_MS, it) } ?: putNull(COLUMN_ELAPSED_MS)
            message.contextTokens?.let { put(COLUMN_CONTEXT_TOKENS, it) } ?: putNull(COLUMN_CONTEXT_TOKENS)
        }
        db.insertWithOnConflict(TABLE_MESSAGES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        db.delete(TABLE_ATTACHMENTS, "$COLUMN_FOREIGN_MESSAGE_ID = ?", arrayOf(message.id.toString()))
        message.attachments.forEachIndexed { position, attachment ->
            db.insertOrThrow(TABLE_ATTACHMENTS, null, ContentValues().apply {
                put(COLUMN_FOREIGN_MESSAGE_ID, message.id)
                put(COLUMN_POSITION, position)
                put(COLUMN_NAME, attachment.name)
                put(COLUMN_CONTENT, attachment.content)
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
            })
        }
    }

    private fun readAttachments(db: SQLiteDatabase, messageId: Long? = null): Map<Long, List<AiAttachment>> {
        val result = LinkedHashMap<Long, MutableList<AiAttachment>>()
        db.query(
            TABLE_ATTACHMENTS,
            arrayOf(COLUMN_FOREIGN_MESSAGE_ID, COLUMN_NAME, COLUMN_CONTENT),
            messageId?.let { "$COLUMN_FOREIGN_MESSAGE_ID = ?" },
            messageId?.let { arrayOf(it.toString()) },
            null,
            null,
            "$COLUMN_FOREIGN_MESSAGE_ID ASC, $COLUMN_POSITION ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) result.getOrPut(cursor.getLong(0)) { ArrayList() }
                .add(AiAttachment(cursor.getString(1), cursor.getString(2)))
        }
        return result
    }

    private fun readTools(db: SQLiteDatabase, messageId: Long? = null): Map<Long, List<AiToolCall>> {
        val result = LinkedHashMap<Long, MutableList<AiToolCall>>()
        db.query(
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
                    ),
                )
        }
        return result
    }

    private fun Cursor.readMessage(
        attachments: Map<Long, List<AiAttachment>>,
        tools: Map<Long, List<AiToolCall>>,
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
            tools = tools[id].orEmpty(),
            modelName = getString(getColumnIndexOrThrow(COLUMN_MODEL_NAME)),
            elapsedMs = getLongOrNull(COLUMN_ELAPSED_MS),
            contextTokens = getIntOrNull(COLUMN_CONTEXT_TOKENS),
        )
    }

    private fun readJobs(db: SQLiteDatabase): List<AiAgentJobRecord> = db.query(
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
        db.insertWithOnConflict(TABLE_JOBS, null, ContentValues().apply {
            put(COLUMN_JOB_ID, record.jobId)
            put(COLUMN_JOB_CONVERSATION_ID, record.conversationId)
            put(COLUMN_JOB_MOD_ID, record.modId)
            put(COLUMN_JOB_MOD_NAME, record.modName)
            put(COLUMN_JOB_STORAGE_PATH, record.storagePath)
            put(COLUMN_JOB_ASSISTANT_ID, record.assistantMessageId)
            put(COLUMN_JOB_STATUS, record.status)
            put(COLUMN_JOB_ERROR, record.errorMessage)
            put(COLUMN_JOB_UPDATED_AT, record.updatedAtMillis)
        }, SQLiteDatabase.CONFLICT_REPLACE)
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
        const val SCHEMA_VERSION = 1
        const val DATABASE_EXTENSION = "db"
        const val LEGACY_CONVERSATIONS_FILE = "conversations.json"
        const val LEGACY_JOBS_FILE = "agent_jobs.json"
        const val TABLE_CONVERSATION = "conversation"
        const val TABLE_MESSAGES = "messages"
        const val TABLE_ATTACHMENTS = "attachments"
        const val TABLE_TOOLS = "tool_calls"
        const val TABLE_JOBS = "agent_jobs"
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
        const val COLUMN_MODEL_NAME = "model_name"
        const val COLUMN_ELAPSED_MS = "elapsed_ms"
        const val COLUMN_CONTEXT_TOKENS = "context_tokens"
        const val COLUMN_POSITION = "position"
        const val COLUMN_NAME = "name"
        const val COLUMN_CONTENT = "content"
        const val COLUMN_TOOL_ID = "id"
        const val COLUMN_TOOL_NAME = "name"
        const val COLUMN_ARGUMENTS = "arguments"
        const val COLUMN_PRECEDING_TEXT = "preceding_text"
        const val COLUMN_RESULT = "result"
        const val COLUMN_TRUNCATED = "truncated"
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

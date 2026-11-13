package io.stamethyst.ui.aimod

import io.stamethyst.backend.llm.AgentContextMessage
import io.stamethyst.backend.llm.AgentContextState
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Incremental protocol archive. [Rows] operations must share the caller's SQLite transaction.
 * The one-entry cache is bounded by a single conversation, and is invalidated if that transaction
 * fails. A persisted generation prevents reuse across separately initialized databases. Restoring
 * a file from the same lineage while this store is alive requires explicitly invalidating it.
 */
internal class AiContextStorage {
    internal data class Header(
        val generation: String,
        val messageCount: Int,
        val messagesRevision: Long,
        val hasMetadata: Boolean,
    )
    internal data class MessageRow(val position: Int, val json: String)

    internal interface Rows {
        fun header(): Header?
        fun metadata(): String?
        fun legacy(): String?
        /** Move the original bytes out of the frequently updated conversation row, atomically. */
        fun archiveLegacy()
        fun messages(afterRevision: Long?): List<MessageRow>
        fun hasMessages(): Boolean
        fun putMessage(position: Int, revision: Long, json: String)
        fun truncateMessages(size: Int)
        /** When metadataChanged is false, do not rewrite the (possibly large) summary. */
        fun putHeader(header: Header, metadata: String?, metadataChanged: Boolean)
    }

    private data class Cached(
        val key: String,
        val revision: Long,
        val header: Header,
        val state: AgentContextState?,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private var cached: Cached? = null

    @Synchronized
    fun invalidate(key: String) {
        if (cached?.key == key) cached = null
    }

    @Synchronized
    fun read(key: String, revision: Long, rows: Rows): AgentContextState? {
        val header = rows.header() ?: return migrate(key, revision, rows)
        check(header.messageCount == 0 || header.hasMetadata) { "Missing metadata for context archive" }
        val old = cached?.takeIf { it.key == key && it.header.generation == header.generation }
        if (old?.revision == revision && old.header == header) return old.state

        val metadata = rows.metadata()?.let { json.decodeFromString<AgentContextState>(it) }
        check(metadata != null || header.messageCount == 0) { "Missing metadata for context archive" }
        val state = metadata?.let {
            val reusable = old?.takeIf { it.revision <= revision && it.state != null }
            val messages = if (reusable != null && reusable.header.messagesRevision == header.messagesRevision) {
                reusable.state!!.messages
            } else {
                val archive = reusable?.state?.messages?.take(header.messageCount)?.toMutableList()
                    ?: mutableListOf()
                rows.messages(reusable?.revision).forEach { row ->
                    val message = json.decodeFromString<AgentContextMessage>(row.json)
                    check(row.position in 0..archive.size) { "Gap in context archive at ${row.position}" }
                    if (row.position == archive.size) archive.add(message) else archive[row.position] = message
                }
                check(archive.size == header.messageCount) { "Incomplete context archive" }
                archive.toList()
            }
            it.copy(messages = messages)
        }
        cached = Cached(key, revision, header, state)
        return state
    }

    /** Returns false for an equal checkpoint, without writing rows or consuming a revision. */
    @Synchronized
    fun write(key: String, currentRevision: Long, nextRevision: Long, next: AgentContextState?, rows: Rows): Boolean {
        val previous = read(key, currentRevision, rows)
        if (previous == next) return false
        val oldHeader = rows.header()
        val oldMessages = previous?.messages.orEmpty()
        val messages = next?.messages.orEmpty()
        var messagesChanged = oldMessages.size != messages.size
        messages.forEachIndexed { position, message ->
            if (oldMessages.getOrNull(position) != message) {
                rows.putMessage(position, nextRevision, json.encodeToString(message))
                messagesChanged = true
            }
        }
        if (messages.size < oldMessages.size) rows.truncateMessages(messages.size)
        val oldMetadata = previous?.copy(messages = emptyList())
        val metadata = next?.copy(messages = emptyList())
        val metadataChanged = oldHeader == null || oldMetadata != metadata
        val header = Header(
            generation = oldHeader?.generation ?: UUID.randomUUID().toString(),
            messageCount = messages.size,
            messagesRevision = if (messagesChanged) nextRevision else oldHeader?.messagesRevision ?: nextRevision,
            hasMetadata = next != null,
        )
        rows.putHeader(header, if (metadataChanged) metadata?.let { json.encodeToString(it) } else null, metadataChanged)
        if (oldHeader == null) rows.archiveLegacy()
        val archive = if (!messagesChanged && previous != null) previous.messages else messages.toList()
        cached = Cached(key, nextRevision, header, next?.copy(messages = archive))
        return true
    }

    private fun migrate(key: String, revision: Long, rows: Rows): AgentContextState? {
        // A lost completion marker is corruption, not an empty/new database. Do not overwrite
        // recoverable normalized messages (including results appended after the legacy backup).
        check(!rows.hasMessages()) { "Missing context header for existing archive" }
        val legacy = rows.legacy()
        // Keep unreadable legacy JSON intact and unmarked, so it can still be recovered later.
        val state = if (legacy == null) null else runCatching {
            json.decodeFromString<AgentContextState>(legacy)
        }.getOrElse { return null }
        val header = Header(UUID.randomUUID().toString(), state?.messages?.size ?: 0, revision, state != null)
        state?.messages?.forEachIndexed { position, message ->
            rows.putMessage(position, revision, json.encodeToString(message))
        }
        // The header is the completion marker; keep the original JSON in a cold backup row.
        rows.putHeader(header, state?.copy(messages = emptyList())?.let { json.encodeToString(it) }, true)
        rows.archiveLegacy()
        cached = Cached(key, revision, header, state)
        return state
    }
}

package io.stamethyst.ui.aimod

import android.content.Context
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.backend.workshop.WorkshopJsonFileStore
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object AiAgentJobStatus {
    const val RUNNING = "RUNNING"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

@Serializable
internal data class AiAgentJobRecord(
    val jobId: String,
    val conversationId: String,
    val modId: String,
    val modName: String,
    val storagePath: String,
    val assistantMessageId: Long,
    val status: String = AiAgentJobStatus.RUNNING,
    val errorMessage: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

internal class AiConversationStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = locks.computeIfAbsent(file.canonicalPath) { Any() }

    fun load(): List<AiEditorSession> = withLockedFile {
        WorkshopJsonFileStore.readJsonOrDefault(file, emptyList()) { text ->
            json.decodeFromString<List<AiEditorSession>>(text)
        }.filter { it.hasUserPrompt() }
    }

    fun update(sessionId: String, transform: (AiEditorSession) -> AiEditorSession) {
        mutate(sessionId, transform = transform)
    }

    /** Read/modify/write one session under the same lock; callers never save a stale full snapshot. */
    fun mutate(
        sessionId: String,
        create: AiEditorSession? = null,
        transform: (AiEditorSession) -> AiEditorSession,
    ): AiEditorSession? = withLockedFile {
        val sessions = loadUnlocked().toMutableList()
        val index = sessions.indexOfFirst { it.id == sessionId }
        val previous = if (index >= 0) sessions[index] else create ?: return@withLockedFile null
        val next = transform(previous).copy(revision = previous.revision + 1)
        if (index >= 0) sessions[index] = next else sessions += next
        writeUnlocked(sessions)
        next
    }

    private fun loadUnlocked(): List<AiEditorSession> =
        WorkshopJsonFileStore.readJsonOrDefault(file, emptyList()) { text ->
            json.decodeFromString<List<AiEditorSession>>(text)
        }.filter { it.hasUserPrompt() }

    private fun writeUnlocked(sessions: List<AiEditorSession>) {
        file.parentFile?.mkdirs()
        WorkshopJsonFileStore.writeAtomically(
            file,
            json.encodeToString(sessions.filter { it.hasUserPrompt() }),
        )
    }

    private fun <T> withLockedFile(block: () -> T): T =
        WorkshopJsonFileStore.withFileLock(file, lock, block)

    private companion object {
        val locks = ConcurrentHashMap<String, Any>()
    }
}

internal class AiAgentJobStore(context: Context, modId: String) {
    private val file = File(
        RuntimePaths.agentModConversationsRoot(
            context.applicationContext,
            AgentPatchModManager.parentModSegment(modId),
        ),
        "agent_jobs.json",
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = locks.computeIfAbsent(file.canonicalPath) { Any() }

    fun list(): List<AiAgentJobRecord> = withLockedFile { loadUnlocked() }

    fun find(jobId: String): AiAgentJobRecord? = list().firstOrNull { it.jobId == jobId }

    fun upsert(record: AiAgentJobRecord) {
        withLockedFile {
            val records = loadUnlocked().toMutableList()
            val index = records.indexOfFirst { it.jobId == record.jobId }
            if (index >= 0) records[index] = record else records.add(0, record)
            saveUnlocked(records)
        }
    }

    fun update(jobId: String, transform: (AiAgentJobRecord) -> AiAgentJobRecord) {
        withLockedFile {
            val records = loadUnlocked().toMutableList()
            val index = records.indexOfFirst { it.jobId == jobId }
            if (index < 0) return@withLockedFile
            records[index] = transform(records[index]).copy(updatedAtMillis = System.currentTimeMillis())
            saveUnlocked(records)
        }
    }

    fun markInterrupted(
        interruptedMessage: String,
        isRunning: (AiAgentJobRecord) -> Boolean,
    ): List<AiAgentJobRecord> = withLockedFile {
        val interrupted = ArrayList<AiAgentJobRecord>()
        val records = loadUnlocked().map { record ->
            if (record.status == AiAgentJobStatus.RUNNING && !isRunning(record)) {
                record.copy(
                    status = AiAgentJobStatus.FAILED,
                    errorMessage = interruptedMessage,
                    updatedAtMillis = System.currentTimeMillis(),
                ).also(interrupted::add)
            } else {
                record
            }
        }
        if (interrupted.isNotEmpty()) saveUnlocked(records)
        interrupted
    }

    private fun loadUnlocked(): List<AiAgentJobRecord> =
        WorkshopJsonFileStore.readJsonOrDefault(file, emptyList()) { text ->
            json.decodeFromString<List<AiAgentJobRecord>>(text)
        }

    private fun saveUnlocked(records: List<AiAgentJobRecord>) {
        file.parentFile?.mkdirs()
        WorkshopJsonFileStore.writeAtomically(file, json.encodeToString(records))
    }

    private fun <T> withLockedFile(block: () -> T): T =
        WorkshopJsonFileStore.withFileLock(file, lock, block)

    private companion object {
        val locks = ConcurrentHashMap<String, Any>()
    }
}

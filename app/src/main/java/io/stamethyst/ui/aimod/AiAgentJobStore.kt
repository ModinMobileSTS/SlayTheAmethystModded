package io.stamethyst.ui.aimod

import android.content.Context
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.config.RuntimePaths
import kotlinx.serialization.Serializable

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

internal class AiAgentJobStore internal constructor(private val conversationStore: AiConversationStore) {
    constructor(context: Context, modId: String) : this(AiConversationStore(RuntimePaths.agentModConversationsRoot(
        context.applicationContext,
        AgentPatchModManager.parentModSegment(modId),
    )))

    fun list(): List<AiAgentJobRecord> {
        conversationStore.migrateLegacyJobs()
        return conversationStore.listJobs()
    }

    fun find(jobId: String): AiAgentJobRecord? = list().firstOrNull { it.jobId == jobId }

    fun listForConversation(conversationId: String): List<AiAgentJobRecord> {
        conversationStore.migrateLegacyJobs()
        return conversationStore.listJobs(conversationId)
    }

    fun upsert(record: AiAgentJobRecord) {
        conversationStore.migrateLegacyJobs()
        conversationStore.upsertJob(record)
    }

    fun update(jobId: String, transform: (AiAgentJobRecord) -> AiAgentJobRecord) {
        conversationStore.migrateLegacyJobs()
        conversationStore.updateJob(jobId, transform = transform)
    }

    fun markInterrupted(
        interruptedMessage: String,
        isRunning: (AiAgentJobRecord) -> Boolean,
    ): List<AiAgentJobRecord> {
        val interrupted = list().filter {
            it.status == AiAgentJobStatus.RUNNING && !isRunning(it)
        }
        return interrupted.mapNotNull { record ->
            // The worker can finish between list() and isRunning(). Never overwrite its terminal
            // status using that stale snapshot, or report a message as interrupted without a claim.
            conversationStore.updateJob(record.jobId, predicate = {
                it.status == AiAgentJobStatus.RUNNING && !isRunning(it)
            }) {
                it.copy(
                    status = AiAgentJobStatus.FAILED,
                    errorMessage = interruptedMessage,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
        }
    }
}

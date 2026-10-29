package io.stamethyst.backend.llm

import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
enum class AgentStepKind { MODEL, TOOL, COMPACTION, RETRY, ERROR }

@Serializable
enum class AgentStepStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

/** Small, durable telemetry. Never stores a prompt, credential, full tool result or reasoning. */
@Serializable
data class AgentExecutionStep(
    val id: String,
    val kind: AgentStepKind,
    val name: String = "",
    val detail: String = "",
    val startedAtMs: Long,
    val elapsedMs: Long? = null,
    val status: AgentStepStatus = AgentStepStatus.RUNNING,
    /** Null means not reported; tools/retry waits explicitly consume zero model tokens. */
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    /** The action which caused an ERROR event; its timing/usage remains on that action. */
    val relatedStepId: String? = null,
) {
    val totalTokens: Long? get() = if (inputTokens != null && outputTokens != null)
        inputTokens + outputTokens else null
}

/** Wall time locates a step; a monotonic clock measures it, independent of clock adjustments. */
internal class AgentStepTimer(
    kind: AgentStepKind,
    name: String = "",
    detail: String = "",
    id: String = UUID.randomUUID().toString(),
) {
    private val startedNanos = System.nanoTime()
    val step = AgentExecutionStep(id, kind, name, detail.take(480), System.currentTimeMillis(),
        inputTokens = if (kind == AgentStepKind.TOOL || kind == AgentStepKind.RETRY || kind == AgentStepKind.ERROR) 0 else null,
        outputTokens = if (kind == AgentStepKind.TOOL || kind == AgentStepKind.RETRY || kind == AgentStepKind.ERROR) 0 else null)

    fun finish(
        status: AgentStepStatus = AgentStepStatus.COMPLETED,
        detail: String = step.detail,
        inputTokens: Long? = step.inputTokens,
        outputTokens: Long? = step.outputTokens,
    ): AgentExecutionStep = step.copy(
        status = status,
        detail = detail.take(480),
        elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos).coerceAtLeast(0),
        inputTokens = inputTokens?.takeIf { it >= 0 },
        outputTokens = outputTokens?.takeIf { it >= 0 },
    )
}

internal fun agentStepFailureStatus(error: Throwable): AgentStepStatus =
    if (error is java.util.concurrent.CancellationException || error is InterruptedException ||
        Thread.currentThread().isInterrupted) AgentStepStatus.CANCELLED else AgentStepStatus.FAILED

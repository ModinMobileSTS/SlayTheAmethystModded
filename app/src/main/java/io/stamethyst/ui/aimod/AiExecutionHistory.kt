package io.stamethyst.ui.aimod

import io.stamethyst.backend.llm.AgentCompactionProgress
import io.stamethyst.backend.llm.AgentExecutionStep
import io.stamethyst.backend.llm.AgentStepKind
import io.stamethyst.backend.llm.AgentStepStatus
import io.stamethyst.backend.llm.agentErrorDetail
import io.stamethyst.backend.llm.agentErrorStep
import io.stamethyst.backend.llm.agentToolFailure
import kotlinx.serialization.json.Json
import java.util.Locale

internal fun AiEditorMessage.withExecutionStep(step: AgentExecutionStep): AiEditorMessage {
    val index = executionSteps.indexOfFirst { it.id == step.id }
    return copy(executionSteps = if (index < 0) executionSteps + step else
        executionSteps.toMutableList().apply { set(index, step) })
}

/** A killed process cannot supply an end time. Do not turn missing telemetry into measured time. */
internal fun AiEditorMessage.closeExecutionSteps(status: AgentStepStatus): AiEditorMessage = copy(
    executionSteps = executionSteps.map { if (it.status == AgentStepStatus.RUNNING) it.copy(status = status) else it },
)

/** A terminal event is idempotent, including recovery when the actual end time is unknowable. */
internal fun AiEditorMessage.withExecutionProblem(
    detail: String,
    status: AgentStepStatus,
    source: String = "SESSION",
): AiEditorMessage {
    val related = executionSteps.lastOrNull { it.kind != AgentStepKind.ERROR }?.id
    val eventId = "$id:terminal-error"
    val closed = closeExecutionSteps(status)
    return if (closed.executionSteps.any { it.id == eventId }) closed else closed.withExecutionStep(
        agentErrorStep(source, detail, related, status, id = eventId),
    )
}

internal fun historyToolError(result: String?, errorDetail: String = ""): String {
    if (errorDetail.isNotBlank()) return agentErrorDetail(errorDetail)
    // Never retain a complete tool result (which can contain source code or image data).
    return result?.takeIf { it.length <= 51_200 }?.let(::agentToolFailure) ?: "tool_execution_failed"
}

internal fun AgentCompactionProgress.executionStep() = startedAtMs?.let { started ->
    AgentExecutionStep(
        id = id, kind = AgentStepKind.COMPACTION, name = reason,
        detail = error.take(480), startedAtMs = started, elapsedMs = elapsedMs,
        status = when (status) {
            "completed" -> AgentStepStatus.COMPLETED
            "failed" -> AgentStepStatus.FAILED
            "interrupted" -> AgentStepStatus.CANCELLED
            else -> AgentStepStatus.RUNNING
        },
        inputTokens = inputTokens, outputTokens = outputTokens,
    )
}

internal data class AiHistoryStep(
    val key: String,
    val kind: AgentStepKind,
    val name: String,
    val detail: String,
    val modelName: String,
    val startedAtMs: Long?,
    val elapsedMs: Long?,
    val status: AgentStepStatus,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val legacy: Boolean = false,
    val relatedStepId: String? = null,
    val relatedStepNumber: Int? = null,
) {
    val totalTokens: Long? get() = if (inputTokens != null && outputTokens != null) inputTokens + outputTokens else null
    val billable: Boolean get() = kind == AgentStepKind.MODEL || kind == AgentStepKind.COMPACTION
}

internal data class AiHistoryUsage(val tokens: Long?, val reported: Int, val requests: Int) {
    val partial: Boolean get() = reported < requests
}

internal fun aiHistoryUsage(steps: List<AiHistoryStep>): AiHistoryUsage {
    var total = 0L
    var reported = 0
    var requests = 0
    steps.forEach { step ->
        if (step.billable) {
            requests++
            step.totalTokens?.let { total += it; reported++ }
        }
    }
    return AiHistoryUsage(total.takeIf { reported > 0 }, reported, requests)
}

internal data class AiHistoryTurn(
    val id: Long,
    val number: Int,
    val prompt: String,
    val steps: List<AiHistoryStep>,
    val running: Boolean,
    val failed: Boolean,
    val elapsedMs: Long?,
    val usage: AiHistoryUsage,
    val spectrum: List<AiHistorySegment>,
)

internal data class AiHistorySegment(val kind: AgentStepKind, val weight: Float, val failed: Boolean)

internal data class AiHistoryColumn(val turnId: Long, val segments: List<AiHistorySegment>, val running: Boolean)

internal data class AiHistorySnapshot(
    val turns: List<AiHistoryTurn> = emptyList(),
    val stepCount: Int = 0,
    val elapsedMs: Long? = null,
    val usage: AiHistoryUsage = AiHistoryUsage(null, 0, 0),
    val overview: List<AiHistoryColumn> = emptyList(),
)

/** Bounded draw commands, even for a conversation containing tens of thousands of steps. */
private fun historySpectrum(steps: List<AiHistoryStep>, limit: Int = 64): List<AiHistorySegment> {
    if (steps.isEmpty()) return emptyList()
    val size = ((steps.size + limit - 1) / limit).coerceAtLeast(1)
    return steps.chunked(size).map { group ->
        val kind = if (group.any { it.kind == AgentStepKind.ERROR }) AgentStepKind.ERROR else
            group.groupingBy { it.kind }.eachCount().maxBy { it.value }.key
        AiHistorySegment(kind, group.sumOf { (it.elapsedMs ?: 1_000L).coerceAtLeast(100L) }.toFloat(),
            group.any { it.status == AgentStepStatus.FAILED || it.status == AgentStepStatus.CANCELLED })
    }
}

/** Identity cache: streaming one message must not re-parse every old tool argument/part. */
internal class AiHistoryBuilder {
    private val cache = HashMap<Long, Pair<AiEditorMessage, List<AiHistoryStep>>>()
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun build(messages: List<AiEditorMessage>): AiHistorySnapshot {
        val turns = ArrayList<AiHistoryTurn>()
        var id = 0L
        var prompt = ""
        var started = false
        var running = false
        var failed = false
        var fallbackMs: Long? = null
        val steps = ArrayList<AiHistoryStep>()
        fun flush() {
            if (!started) return
            val positions = steps.mapIndexed { index, step -> step.key to index + 1 }.toMap()
            val frozen = steps.map { step ->
                if (step.relatedStepId == null) step else step.copy(
                    relatedStepNumber = positions["${step.key.substringBefore(':')}:${step.relatedStepId}"],
                )
            }
            val actions = frozen.filter { it.kind != AgentStepKind.ERROR }
            val first = actions.mapNotNull { it.startedAtMs }.minOrNull()
            val last = actions.mapNotNull { step -> step.startedAtMs?.let { start -> step.elapsedMs?.let { start + it } } }.maxOrNull()
            val elapsed = if (first != null && last != null) (last - first).coerceAtLeast(0) else fallbackMs
            turns += AiHistoryTurn(id, turns.size + 1, prompt, frozen, running, failed, elapsed, aiHistoryUsage(frozen), historySpectrum(frozen))
            steps.clear()
            running = false
            failed = false
            fallbackMs = null
        }
        messages.forEach { message ->
            if (message.fromUser) {
                flush()
                started = true
                id = message.id
                prompt = message.text.take(600).ifBlank { message.attachments.firstOrNull()?.name.orEmpty() }
            } else if (!message.notice) {
                if (!started) { started = true; id = message.id }
                running = running || message.streaming
                failed = failed || message.failed
                message.elapsedMs?.let { fallbackMs = (fallbackMs ?: 0) + it }
                val previous = cache[message.id]
                val prepared = if (previous?.first === message) previous.second else prepareSteps(message).also {
                    cache[message.id] = message to it
                }
                steps += prepared
            }
        }
        flush()
        cache.keys.retainAll(messages.mapTo(HashSet()) { it.id })
        val usages = turns.map { it.usage }
        val known = usages.mapNotNull { it.tokens }
        val elapsed = turns.mapNotNull { it.elapsedMs }
        val columnSize = ((turns.size + 39) / 40).coerceAtLeast(1)
        val overview = turns.chunked(columnSize).map { group ->
            val weights = linkedMapOf<AgentStepKind, Float>()
            group.forEach { turn -> turn.spectrum.forEach { segment -> weights[segment.kind] = (weights[segment.kind] ?: 0f) + segment.weight } }
            AiHistoryColumn(group.last().id, weights.map { AiHistorySegment(it.key, it.value, false) }, group.any { it.running })
        }
        return AiHistorySnapshot(turns, turns.sumOf { it.steps.size }, elapsed.takeIf { it.isNotEmpty() }?.sum(),
            AiHistoryUsage(known.takeIf { it.isNotEmpty() }?.sum(), usages.sumOf { it.reported }, usages.sumOf { it.requests }), overview)
    }

    private fun prepareSteps(message: AiEditorMessage): List<AiHistoryStep> {
        val recorded = message.executionSteps.map { step ->
            AiHistoryStep("${message.id}:${step.id}", step.kind, step.name, step.detail.take(480), message.modelName,
                step.startedAtMs, step.elapsedMs,
                if (!message.streaming && step.status == AgentStepStatus.RUNNING) AgentStepStatus.CANCELLED else step.status,
                step.inputTokens, step.outputTokens, relatedStepId = step.relatedStepId)
        }
        if (recorded.any { it.kind != AgentStepKind.ERROR }) return recorded.let { steps ->
            if ((message.failed || message.errorMessage.isNotBlank()) && steps.none { it.kind == AgentStepKind.ERROR })
                steps + legacyProblem(message) else steps
        }
        // Legacy conversations retain their real action order, but never borrow the last request's
        // context token count or divide a whole turn's duration between invented steps.
        val tools = message.tools.associateBy { it.id }
        val result = ArrayList<AiHistoryStep>()
        val model = StringBuilder()
        fun add(kind: AgentStepKind, name: String = "", detail: String = "", failed: Boolean = false,
            running: Boolean = false, input: Long? = null, output: Long? = null) {
            result += AiHistoryStep("${message.id}:legacy:${result.size}", kind, name, detail.take(480), message.modelName,
                null, null, when { running -> AgentStepStatus.RUNNING; failed -> AgentStepStatus.FAILED; else -> AgentStepStatus.COMPLETED },
                input, output, legacy = true)
        }
        fun flushModel() {
            if (model.isNotBlank()) { add(AgentStepKind.MODEL, detail = model.toString()); model.setLength(0) }
        }
        fun tool(id: String?) {
            tools[id]?.let {
                flushModel()
                add(AgentStepKind.TOOL, it.name, historyToolDetail(it.arguments), it.failed,
                    message.streaming && it.result == null, input = 0, output = 0)
                if (it.failed) add(AgentStepKind.ERROR, "TOOL: ${it.name}", historyToolError(it.result), failed = true, input = 0, output = 0)
            }
        }
        if (message.parts.isNotEmpty()) message.parts.forEach { part ->
            when (part.kind) {
                AiMessagePartKind.TEXT -> if (model.length < 480) model.append(part.content.take(480 - model.length))
                AiMessagePartKind.THINKING -> if (model.isEmpty()) model.append('…') // Do not copy private reasoning into telemetry.
                AiMessagePartKind.TOOL -> tool(part.toolId)
                AiMessagePartKind.COMPACTION -> {
                    flushModel()
                    val event = runCatching { json.decodeFromString<AgentCompactionProgress>(part.content) }.getOrNull()
                    add(AgentStepKind.COMPACTION, event?.reason.orEmpty(), event?.error.orEmpty(), event?.status == "failed",
                        message.streaming && event?.status == "running", event?.inputTokens, event?.outputTokens)
                    if (event?.status == "failed" || event?.status == "interrupted")
                        add(AgentStepKind.ERROR, "COMPACTION", agentErrorDetail(event.error), failed = true, input = 0, output = 0)
                }
            }
        } else {
            message.tools.forEach { call ->
                if (call.precedingText.isNotBlank()) model.append(call.precedingText.take(480))
                tool(call.id)
            }
            model.append(message.text.take(480))
        }
        flushModel()
        if (result.isEmpty() && recorded.isEmpty() && (message.streaming || message.failed || message.elapsedMs != null))
            add(AgentStepKind.MODEL, failed = message.failed, running = message.streaming)
        // Recovering an older in-flight conversation must not hide its actions just because its
        // first durable telemetry is the new interruption event. A startup error invents no model.
        if (recorded.isNotEmpty()) return result + recorded
        if (message.failed || message.errorMessage.isNotBlank()) result += legacyProblem(message)
        return result
    }

    private fun legacyProblem(message: AiEditorMessage) = AiHistoryStep(
        key = "${message.id}:legacy-error", kind = AgentStepKind.ERROR, name = "SESSION",
        detail = agentErrorDetail(message.errorMessage), modelName = message.modelName,
        startedAtMs = null, elapsedMs = null, status = AgentStepStatus.FAILED,
        inputTokens = 0, outputTokens = 0, legacy = true,
    )
}

internal fun historyToolDetail(arguments: String): String {
    // Arguments can be megabytes of source code. Only inspect a bounded prefix for the overview.
    if (arguments.length > 4096) return ""
    val parsed = runCatching { Json.parseToJsonElement(arguments) as? kotlinx.serialization.json.JsonObject }.getOrNull()
    val value = listOf("path", "query", "class_name", "className", "patch_id", "pattern", "name").firstNotNullOfOrNull { key ->
        (parsed?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }.orEmpty()
    return value.take(480)
}

internal fun formatAiHistoryDuration(ms: Long?): String = when {
    ms == null -> "—"
    ms < 1_000 -> "${ms.coerceAtLeast(0)} ms"
    ms < 60_000 -> String.format(Locale.ROOT, "%.1f s", ms / 1000.0)
    ms < 3_600_000 -> "${ms / 60_000}m ${ms % 60_000 / 1000}s"
    else -> "${ms / 3_600_000}h ${ms % 3_600_000 / 60_000}m"
}

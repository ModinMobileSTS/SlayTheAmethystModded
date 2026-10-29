package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.Content
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.response.ChatResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil

const val DEFAULT_AGENT_CONTEXT_LIMIT = 200_000

/**
 * Leading text of the user message that carries images read by a tool.
 *
 * OpenAI-compatible chat APIs accept only text inside a tool result, so media is extracted and
 * re-sent as a user turn after the tool batch, matching OpenCode V2's message conversion.
 */
const val SYNTHETIC_ATTACHMENT_PROMPT = "Attached image(s) from tool result:"

/** Flat prompt cost for one image. Providers price images by resolution; this stays deliberately high. */
const val AGENT_IMAGE_TOKEN_ESTIMATE = 1_600

@Serializable
data class AgentContextToolCall(val id: String, val name: String, val arguments: String)

@Serializable
data class AgentContextAttachment(
    val name: String,
    /** UTF-8 content of a text attachment; empty for an image. */
    val content: String = "",
    /** MIME type: text/plain for pasted text, image/png or image/jpeg for a picture. */
    val mime: String = "text/plain",
    /** Base64 payload of an image attachment; empty for text. Never sent to the model as text. */
    val data: String = "",
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

/** Explains an image to a model that cannot view it, instead of failing the whole turn. */
internal fun imageInputPlaceholder(attachment: AgentContextAttachment): String =
    "[Attached ${attachment.mime}: ${attachment.name}. This model cannot view images; " +
        "ask the user to describe it or enable image input in the LLM settings.]"

/** One user turn that shows tool-read images to a vision model. */
internal fun agentMediaUserMessage(
    media: List<AgentContextAttachment>,
    supportsImageInput: Boolean,
): UserMessage = UserMessage.from(
    *buildList<Content> {
        add(TextContent.from(SYNTHETIC_ATTACHMENT_PROMPT))
        media.forEach { image ->
            if (supportsImageInput) {
                add(ImageContent.from(image.data, image.mime))
            } else {
                add(TextContent.from(imageInputPlaceholder(image)))
            }
        }
    }.toTypedArray(),
)

/** Durable protocol messages, separate from the UI's truncated tool previews. */
@Serializable
data class AgentContextMessage(
    val sourceMessageId: Long,
    val role: String,
    val text: String = "",
    val calls: List<AgentContextToolCall> = emptyList(),
    val callId: String = "",
    val toolName: String = "",
    val thinking: String? = null,
    /** Internal append-only state notice, not a new user goal. */
    val patchState: String? = null,
    val attachments: List<AgentContextAttachment> = emptyList(),
) {
    fun toChatMessages(supportsImageInput: Boolean = true): List<ChatMessage> = when (role) {
        "user" -> listOf(userChatMessage(supportsImageInput))
        // Some OpenAI-compatible gateways translate tool_calls into Anthropic tool_use blocks
        // only when the assistant message has a concrete content field. Keep an empty string
        // instead of null for tool-call turns so the preceding tool_use is not dropped.
        "assistant" -> listOf(AiMessage.builder().text(if (calls.isEmpty()) text.ifBlank { null } else text)
            .thinking(thinking)
            .toolExecutionRequests(calls.map {
                ToolExecutionRequest.builder().id(it.id).name(it.name).arguments(it.arguments).build()
            }).build())
        "tool" -> listOf(ToolExecutionResultMessage.from(callId, toolName, text))
        else -> error("Unsupported context role: $role")
    }

    private fun userChatMessage(supportsImageInput: Boolean): UserMessage {
        val prompt = buildString {
            append(text)
            attachments.filterNot { it.isImage }.forEach { append("\n\nAttachment: ${it.name}\n${it.content}") }
        }
        val images = attachments.filter { it.isImage }
        if (images.isEmpty()) return UserMessage.from(prompt)
        val contents = buildList<Content> {
            if (prompt.isNotBlank()) add(TextContent.from(prompt))
            images.forEach { image ->
                if (supportsImageInput) {
                    add(ImageContent.from(image.data, image.mime))
                } else {
                    add(TextContent.from(imageInputPlaceholder(image)))
                }
            }
        }
        return UserMessage.from(*contents.toTypedArray())
    }
}

@Serializable
data class AgentContextState(
    val messages: List<AgentContextMessage> = emptyList(),
    val summary: String = "",
    val summarizedCount: Int = 0,
    val compactionCount: Int = 0,
    val activePatchId: String? = null,
    val modelKey: String = "",
    val estimatedTokens: Int? = null,
    val lastInputTokens: Int? = null,
    val lastOutputTokens: Int? = null,
    val estimateScale: Double = 1.0,
    val cacheStats: AgentCacheStats = AgentCacheStats(),
    /** V2 checkpoints include recent conversation as text, never as orphaned protocol messages. */
    val recent: String = "",
    val checkpointVersion: Int = 0,
    val measuredMessageCount: Int? = null,
) {
    /** Rollback invalidates a summary if any of its source messages were removed. */
    fun retainSources(ids: Set<Long>): AgentContextState {
        val retained = messages.filter { it.sourceMessageId in ids }
        val summaryValid = messages.take(summarizedCount).all { it.sourceMessageId in ids }
        return copy(
            messages = retained,
            summary = if (summaryValid) summary else "",
            recent = if (summaryValid) recent else "",
            summarizedCount = if (summaryValid) summarizedCount else 0,
            estimatedTokens = null,
            lastInputTokens = null,
            lastOutputTokens = null,
            measuredMessageCount = null,
            activePatchId = if (retained == messages) activePatchId else inferActivePatch(retained),
        )
    }

    /** Never replay a tool with unknown side effects after interruption. */
    fun closeInterruptedTools(): AgentContextState {
        val repaired = ArrayList<AgentContextMessage>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index++]
            if (message.role == "tool") continue
            repaired += message
            if (message.calls.isEmpty()) continue
            val results = ArrayList<AgentContextMessage>()
            while (index < messages.size && messages[index].role == "tool") results += messages[index++]
            // Do not replay a stale result from another assistant turn. Providers that enforce
            // tool ordering reject an orphaned tool_result when its tool_use is not immediately
            // present in the preceding assistant message.
            val callsById = message.calls.associateBy { it.id }
            val matched = HashSet<String>()
            results.forEach { result ->
                if (result.callId in callsById && matched.add(result.callId)) repaired += result
            }
            message.calls.forEach { call ->
                if (call.id !in matched) repaired += AgentContextMessage(
                    sourceMessageId = message.sourceMessageId,
                    role = "tool",
                    callId = call.id,
                    toolName = call.name,
                    text = "Execution was interrupted. The outcome is unknown. Inspect files/state before retrying; do not assume failure or success.",
                )
            }
        }
        // A summary boundary must never split an assistant tool-call group. Otherwise the next
        // request can contain a tool_result whose preceding message is no longer its tool_use.
        // That is rejected by Anthropic-compatible gateways even when the durable records looked
        // valid before the boundary was applied.
        var safeSummarizedCount = summarizedCount.coerceIn(0, repaired.size)
        if (safeSummarizedCount < repaired.size && repaired[safeSummarizedCount].role == "tool") {
            var groupStart = safeSummarizedCount
            while (groupStart > 0 && repaired[groupStart - 1].role == "tool") groupStart--
            if (groupStart > 0 && repaired[groupStart - 1].role == "assistant" &&
                repaired[groupStart - 1].calls.isNotEmpty()
            ) {
                safeSummarizedCount = groupStart - 1
            }
        }
        val changedMessages = repaired != messages
        val changedBoundary = safeSummarizedCount != summarizedCount
        return if (!changedMessages && !changedBoundary) {
            this
        } else {
            copy(
                messages = repaired,
                summary = if (changedMessages || changedBoundary) "" else summary,
                recent = if (changedMessages || changedBoundary) "" else recent,
                measuredMessageCount = null,
                summarizedCount = if (changedMessages || changedBoundary) 0 else safeSummarizedCount,
            )
        }
    }

    companion object {
        fun inferActivePatch(messages: List<AgentContextMessage>): String? = messages.asReversed().firstNotNullOfOrNull { message ->
            if (message.patchState != null) message.patchState else if (message.role != "tool" || message.toolName !in setOf(
                    "create_agent_patch_workspace",
                    "create_agent_patch_mod", // Legacy sessions may still contain the old tool name.
                )) null else runCatching {
                Json.parseToJsonElement(message.text).jsonObject["patch_id"]?.jsonPrimitive?.content
            }.getOrNull()
        }
    }
}

data class AgentContextBudget(val limit: Int = DEFAULT_AGENT_CONTEXT_LIMIT) {
    init { require(limit > 0) }
    // The configured limit applies to input only; output is budgeted separately.
    val outputReserve: Int = maxOf(limit / 10, if (limit >= 32_000) 16_000 else 1)
    val inputLimit: Int = limit
    val summaryOutputLimit: Int = minOf(32_000, maxOf(1_024, outputReserve), limit / 2).coerceAtLeast(1)
}

/** Approximation for unknown provider tokenizers; calibrated upwards by actual prompt usage. */
object AgentTokenEstimate {
    fun text(value: String): Int {
        var ascii = 0L
        var other = 0L
        value.forEach { if (it.code < 128) ascii++ else other++ }
        return ((ascii + 2) / 3 + (other * 3 + 1) / 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun message(value: AgentContextMessage): Int {
        val images = value.attachments.filter { it.isImage }
        val textOnly = if (images.isEmpty()) value else value.copy(attachments = value.attachments.filterNot { it.isImage })
        // Base64 never becomes prompt text; charge each image a flat, conservative amount instead.
        return text(Json.encodeToString(textOnly)) + 16 + images.size * AGENT_IMAGE_TOKEN_ESTIMATE
    }
}

class AgentContextCapacityException(message: String) : IllegalStateException(message)
class AgentCompactionException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Only successful, validated summaries replace the active prefix. The archive is never deleted. */
class AgentContextManager(
    initial: AgentContextState,
    private val systemPrompt: String,
    private val toolSchema: String,
    var budget: AgentContextBudget,
    private var sourceMessageId: Long,
    private val model: ChatModel,
    private val checkpoint: (AgentContextState) -> Unit,
    private val checkCancelled: () -> Unit = {},
    private val toolSpecifications: List<ToolSpecification> = emptyList(),
    private val streamingModel: StreamingChatModel? = null,
    private val summaryTimeoutMillis: Long = 120_000,
    private val onCompaction: (AgentCompactionProgress) -> Unit = {},
    /** When false, images are replaced by a short text notice instead of an image part. */
    val supportsImageInput: Boolean = true,
) {
    var state: AgentContextState = initial.closeInterruptedTools()
        private set

    fun switchModel(modelKey: String, contextLimit: Int) {
        if (state.modelKey == modelKey) return
        budget = AgentContextBudget(contextLimit)
        state = state.copy(modelKey = modelKey, estimateScale = 1.0,
            lastInputTokens = null, lastOutputTokens = null, measuredMessageCount = null,
            cacheStats = AgentCacheStats(endpoint = state.cacheStats.endpoint))
        persist()
    }

    private fun activeRecords(value: AgentContextState = state): List<AgentContextMessage> {
        val tail = value.messages.drop(value.summarizedCount)
        if (value.checkpointVersion >= 2) return tail
        val lastUser = value.messages.indexOfLast { it.role == "user" && it.patchState == null }
        // A long tool loop may compact part of the current turn. Keep its user request verbatim.
        return if (lastUser >= 0 && lastUser < value.summarizedCount) listOf(value.messages[lastUser]) + tail else tail
    }

    private fun memory(value: AgentContextState): String = AgentContextCompaction.memory(value.summary, value.recent)

    private fun rawEstimate(value: AgentContextState): Int {
        val total = AgentTokenEstimate.text(systemPrompt).toLong() + AgentTokenEstimate.text(memory(value)) +
            AgentTokenEstimate.text(toolSchema) + 64L +
            activeRecords(value).sumOf { AgentTokenEstimate.message(it).toLong() }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun estimate(value: AgentContextState): Int {
        val anchor = value.measuredMessageCount
        if (anchor != null && anchor in value.summarizedCount..value.messages.size && value.lastInputTokens != null) {
            val output = value.messages.getOrNull(anchor)
            val measuredOutput = value.lastOutputTokens?.takeIf { output?.role == "assistant" }
            val since = value.messages.drop(anchor + if (measuredOutput != null) 1 else 0)
            return (value.lastInputTokens.toLong() + (measuredOutput ?: 0) + since
                .sumOf { AgentTokenEstimate.message(it).toLong() }).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return ceil(rawEstimate(value) * value.estimateScale).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }

    fun persist() {
        checkCancelled()
        val next = state.copy(estimatedTokens = estimate(state))
        checkpoint(next)
        state = next
    }

    fun append(message: ChatMessage, media: List<AgentContextAttachment> = emptyList()) {
        require(media.all { it.isImage }) { "Only image attachments may accompany a tool result." }
        val record = when (message) {
            is AiMessage -> AgentContextMessage(sourceMessageId, "assistant", message.text().orEmpty(),
                message.toolExecutionRequests().map { AgentContextToolCall(it.id(), it.name(), it.arguments()) },
                thinking = message.thinking())
            is ToolExecutionResultMessage -> AgentContextMessage(sourceMessageId, "tool", message.text(),
                callId = message.id(), toolName = message.toolName(), attachments = media)
            else -> error("Only completed assistant/tool messages may be appended")
        }
        state = state.copy(messages = state.messages + record)
        persist()
    }

    fun appendInput(message: AgentContextMessage, assistantMessageId: Long) {
        require(message.role == "user")
        sourceMessageId = assistantMessageId
        state = state.copy(messages = state.messages + message)
        persist()
    }

    fun observe(response: ChatResponse, requestEstimate: Int) {
        val usage = response.tokenUsage()
        val cacheUsage = AgentCacheUsage.from(response, state.cacheStats.inputExcludesCached)
        val input = cacheUsage.input?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        val observed = if (input != null && requestEstimate > 0) input.toDouble() / requestEstimate else 1.0
        state = state.copy(
            lastInputTokens = input,
            lastOutputTokens = usage?.outputTokenCount(),
            measuredMessageCount = if (input != null) state.messages.size else null,
            estimateScale = maxOf(state.estimateScale, observed * 1.1),
            cacheStats = state.cacheStats.observe(cacheUsage),
        )
        persist()
    }

    fun requestEstimate(): Int = rawEstimate(state)

    fun setActivePatch(patchId: String) {
        state = state.copy(activePatchId = patchId)
        persist()
    }

    fun prepare(force: Boolean = false, manual: Boolean = false): List<ChatMessage> {
        checkCancelled()
        // Re-run repair before every request. A persisted context may have been created by an
        // older build with a summary boundary between an assistant tool_use and its tool_result,
        // and a long-running turn may also have changed the active tail since initialization.
        val repaired = state.closeInterruptedTools()
        if (repaired != state) {
            state = repaired
            persist()
        }
        appendPatchStateIfNeeded()
        if (manual || force || estimate(state) >= budget.inputLimit) {
            compact(if (manual) "manual" else if (force) "overflow" else "auto")
            appendPatchStateIfNeeded()
        }
        persist()
        if (estimate(state) >= budget.inputLimit) throw AgentContextCapacityException(
            "The latest request, system prompt and tools exceed the input token limit (${budget.inputLimit} tokens). " +
                "Reduce attachments/request size or configure the provider's actual context limit.",
        )
        return buildList {
            add(SystemMessage.from(systemPrompt))
            if (state.summary.isNotBlank()) add(UserMessage.from(memory(state)))
            // Tool media becomes one user turn after the whole tool batch, never between tool
            // results: OpenAI-compatible APIs require all tool messages to follow their call.
            val pendingMedia = ArrayList<AgentContextAttachment>()
            fun flushMedia() {
                if (pendingMedia.isEmpty()) return
                add(agentMediaUserMessage(pendingMedia.toList(), supportsImageInput))
                pendingMedia.clear()
            }
            activeRecords().forEach { record ->
                if (record.role != "tool") flushMedia()
                addAll(record.toChatMessages(supportsImageInput))
                if (record.role == "tool") pendingMedia += record.attachments.filter { it.isImage }
            }
            flushMedia()
        }
    }

    /** Never rewrite an earlier prompt when a workspace is created or selected. */
    private fun appendPatchStateIfNeeded() {
        val patch = state.activePatchId ?: return
        if (activeRecords().lastOrNull { it.patchState != null }?.patchState == patch) return
        state = state.copy(messages = state.messages + AgentContextMessage(
            sourceMessageId, "user",
            "Launcher state update (not a new user request): Current active patch ID: $patch; workspace: patch_source/$patch/.",
            patchState = patch,
        ))
    }

    private fun compact(reason: String) {
        val original = state
        val compactor = AgentContextCompaction(model, streamingModel, systemPrompt, toolSchema,
            toolSpecifications, budget, summaryTimeoutMillis, checkCancelled, onCompaction)
        compactor.run(original, reason) { summary, recent ->
            val candidate = original.copy(summary = summary, recent = recent, checkpointVersion = 2,
                summarizedCount = original.messages.size, compactionCount = original.compactionCount + 1,
                measuredMessageCount = null, estimateScale = 1.0)
            if (reason != "manual" && estimate(candidate) >= estimate(original)) throw AgentCompactionException(
                "Compaction did not reduce the context. Original messages were retained.",
            )
            if (estimate(candidate) >= budget.inputLimit) throw AgentContextCapacityException(
                "The checkpoint and latest exchange exceed the context budget. Reduce the request or correct the context limit.",
            )
            checkCancelled()
            val next = candidate.copy(estimatedTokens = estimate(candidate))
            checkpoint(next)
            state = next
        }
    }
}

fun isAgentContextOverflow(error: Throwable): Boolean = generateSequence(error) { it.cause }
    .take(8).any {
        val message = it.message.orEmpty().lowercase()
        listOf("context_length_exceeded", "maximum context length", "context window", "context limit",
            "too many tokens", "prompt is too long", "input is too long").any(message::contains)
    }

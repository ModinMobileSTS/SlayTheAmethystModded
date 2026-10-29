/* Adapted from OpenCode V2, revision 4617210822bdbb31e5749751afbfb4584ccedac8.
 * packages/core/src/session/compaction.ts and model-request.ts. MIT; see NOTICE. */
package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.output.FinishReason
import kotlinx.serialization.Serializable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Serializable
data class AgentCompactionProgress(
    val id: String = java.util.UUID.randomUUID().toString(),
    val status: String = "running",
    val reason: String = "auto",
    val summary: String = "",
    val error: String = "",
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val startedAtMs: Long? = null,
    val elapsedMs: Long? = null,
)

internal class AgentContextCompaction(
    private val model: ChatModel,
    private val streamingModel: StreamingChatModel?,
    private val system: String,
    private val toolSchema: String,
    private val tools: List<ToolSpecification>,
    private val budget: AgentContextBudget,
    private val timeoutMillis: Long,
    private val checkCancelled: () -> Unit,
    private val progress: (AgentCompactionProgress) -> Unit,
) {
    fun run(state: AgentContextState, reason: String, commit: (String, String) -> Unit) {
        val timer = AgentStepTimer(AgentStepKind.COMPACTION)
        var event = AgentCompactionProgress(reason = reason, startedAtMs = timer.step.startedAtMs)
        var committing = false
        var progressFailed = false
        fun publish() {
            if (event.status != "running") event = event.copy(elapsedMs = timer.finish().elapsedMs)
            try { progress(event) } catch (error: Exception) { progressFailed = true; throw error }
        }
        publish()
        try {
            val records = state.messages.drop(state.summarizedCount)
            if ((records.isEmpty() && state.summary.isBlank()) ||
                (state.summary.isBlank() && records.all { it.role == "user" })) {
                throw AgentContextCapacityException("Nothing to compact yet. Reduce the current request or attachments.")
            }
            val split = split(records, previousRecent = state.recent.isNotBlank())
            // As in V2, recent context is saved as text alongside (not inside) the summary.
            val older = records.take(split)
            val recent = records.drop(split).mapNotNull { text(it).takeIf(String::isNotBlank) }.joinToString("\n\n")
            val prompt = buildPrompt(state.summary.isNotBlank(), state.checkpointVersion < 2)
            val lead = buildList<ChatMessage> {
                add(SystemMessage.from(system))
                if (state.summary.isNotBlank()) add(UserMessage.from(memory(state.summary, state.recent)))
            }
            val raw = lead + protocol(older)
            val overhead = AgentTokenEstimate.text(prompt) + AgentTokenEstimate.text(NUDGE)
            var target = budget.inputLimit - overhead
            if (reason == "overflow") target = minOf(target, (estimate(raw) * 0.7).toInt())
            var rejectedSize: Int? = null
            var rejections = 0
            var networkRetries = 0
            var forceText = false
            while (true) {
                checkCancelled()
                val requestMessages = if (!forceText && estimate(raw) <= target) raw else
                    flatten(state, older, target) ?: if (rejections == 0) {
                        if (forceText) flatten(state, older, Int.MAX_VALUE) else raw
                    } else null
                if (requestMessages == null) throw AgentCompactionException(
                        "The compaction request cannot be reduced further without losing the latest exchange or checkpoint.",
                    )
                try {
                    var prompted = requestMessages + UserMessage.from(prompt)
                    fun send(): ChatResponse {
                        event = event.copy(summary = "")
                        publish()
                        val request = ChatRequest.builder().messages(prompted).toolSpecifications(tools)
                            .maxOutputTokens(budget.summaryOutputLimit).build()
                        val response = chat(request) { delta ->
                            event = event.copy(summary = event.summary + delta)
                            publish()
                        }
                        checkCancelled()
                        val usage = AgentCacheUsage.from(response, state.cacheStats.inputExcludesCached)
                        event = event.copy(
                            inputTokens = addUsage(event.inputTokens, usage.input),
                            outputTokens = addUsage(event.outputTokens, response.tokenUsage()?.outputTokenCount()?.toLong()),
                        )
                        when (response.finishReason()) {
                            FinishReason.LENGTH -> throw AgentCompactionException("Compaction summary reached the output token limit.")
                            FinishReason.CONTENT_FILTER -> throw AgentCompactionException("Compaction summary was blocked by the provider.")
                            FinishReason.OTHER -> throw AgentCompactionException("The provider response ended with an unknown finish reason.")
                            else -> Unit
                        }
                        if (response.aiMessage().hasToolExecutionRequests()) throw AgentCompactionException("Compaction called tools instead of returning a summary.")
                        return response
                    }
                    var response = send()
                    var summary = response.aiMessage().text().orEmpty().trim()
                    if (!filled(summary)) {
                        prompted = prompted + UserMessage.from(NUDGE)
                        response = send()
                        summary = response.aiMessage().text().orEmpty().trim()
                    }
                    if (!filled(summary)) throw AgentCompactionException(if (summary.isBlank())
                        "Compaction produced no summary." else "Compaction summary did not match the required template.")
                    committing = true
                    commit(summary, recent)
                    event = event.copy(status = "completed", summary = summary)
                    publish()
                    return
                } catch (error: Exception) {
                    checkCancelled()
                    if (committing || progressFailed) throw error
                    val tooLarge = generateSequence<Throwable>(error) { it.cause }.take(8).any {
                        (it is dev.langchain4j.exception.HttpException && it.statusCode() == 413) ||
                            it.message.orEmpty().contains("payload too large", ignoreCase = true)
                    }
                    if (tooLarge && requestMessages === raw) {
                        forceText = true
                        continue
                    }
                    if (tooLarge || isAgentContextOverflow(error)) {
                        if (rejections >= SHRINK_STEPS.size) throw error
                        if (rejectedSize == null) rejectedSize = estimate(requestMessages)
                        target = (rejectedSize * SHRINK_STEPS[rejections++]).toInt()
                        forceText = true
                        continue
                    }
                    if (error !is AgentCompactionException && isRetryableAgentRequestFailure(error) && networkRetries < 2) {
                        val wait = 10 shl networkRetries++
                        repeat(wait) { checkCancelled(); Thread.sleep(100) }
                        continue
                    }
                    throw error
                }
            }
        } catch (error: Exception) {
            val cancelled = error is java.util.concurrent.CancellationException || error is InterruptedException || Thread.currentThread().isInterrupted
            event = event.copy(status = if (cancelled) "interrupted" else "failed", summary = "",
                error = if (cancelled) "" else error.message.orEmpty(), elapsedMs = timer.finish().elapsedMs)
            runCatching { progress(event) }.onFailure { if (it !== error) error.addSuppressed(it) }
            if (committing || progressFailed || cancelled || error is AgentCompactionException || error is AgentContextCapacityException) throw error
            throw AgentCompactionException("Context summarization failed; original messages were retained.", error)
        }
    }

    private fun estimate(messages: List<ChatMessage>): Int = (AgentTokenEstimate.text(toolSchema).toLong() +
        messages.sumOf { message ->
            // SDK toString() is a diagnostic preview, not the wire payload; it may truncate text.
            16L + when (message) {
                is SystemMessage -> AgentTokenEstimate.text(message.text()).toLong()
                is UserMessage -> userText(message).let { text ->
                    AgentTokenEstimate.text(text).toLong() +
                        message.contents().count { it is ImageContent } * AGENT_IMAGE_TOKEN_ESTIMATE
                }
                is AiMessage -> AgentTokenEstimate.text(message.text().orEmpty()).toLong() +
                    AgentTokenEstimate.text(message.thinking().orEmpty()) + message.toolExecutionRequests().sumOf {
                        AgentTokenEstimate.text(it.name()).toLong() + AgentTokenEstimate.text(it.arguments().orEmpty())
                    }
                is ToolExecutionResultMessage -> AgentTokenEstimate.text(message.text()).toLong()
                else -> AgentTokenEstimate.text(message.toString()).toLong()
            }
        }).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Joins the text parts of a user turn; image parts are irrelevant to the summary model. */
    private fun userText(message: UserMessage): String =
        message.contents().filterIsInstance<TextContent>().joinToString("\n") { it.text() }

    /**
     * Preserve native OpenAI parallel tool calls and their single original reasoning block.
     * The summarizer runs without images: media is rendered as a short text notice by
     * [AgentContextMessage.toChatMessages] when image input is disabled.
     */
    private fun protocol(records: List<AgentContextMessage>): List<ChatMessage> =
        records.flatMap { it.toChatMessages(supportsImageInput = false) }

    /** Drop whole exchanges, not arbitrary JSON character chunks. Never drop the previous summary. */
    private fun flatten(state: AgentContextState, records: List<AgentContextMessage>, target: Int): List<ChatMessage>? {
        val lead = buildList<ChatMessage> {
            add(SystemMessage.from(system))
            if (state.summary.isNotBlank()) add(UserMessage.from(memory(state.summary, "")))
        }
        val exchanges = ArrayList<StringBuilder>()
        if (state.recent.isNotBlank()) exchanges += StringBuilder(state.recent)
        records.forEach { record ->
            val line = text(record, reasoning = false)
            if (line.isNotBlank()) {
                if (record.role == "user" || exchanges.isEmpty()) exchanges += StringBuilder(line)
                else exchanges.last().append('\n').append(line)
            }
        }
        if (exchanges.isEmpty()) return lead.takeIf { estimate(it) <= target }
        var start = 0
        var total = exchanges.sumOf { AgentTokenEstimate.text(it.toString()).toLong() + 1 }
        val room = target - estimate(lead) - 32
        while (start < exchanges.size && total > room) {
            total -= AgentTokenEstimate.text(exchanges[start++].toString()) + 1
        }
        if (start == exchanges.size) return null
        val note = if (start > 0) "[$start older exchanges omitted]\n\n" else ""
        return lead + UserMessage.from(note + exchanges.drop(start).joinToString("\n\n"))
    }

    private fun chat(request: ChatRequest, delta: (String) -> Unit): ChatResponse {
        val streaming = streamingModel ?: return model.chat(request)
        val result = CompletableFuture<ChatResponse>()
        val lock = Any()
        var accepting = true
        try {
            streaming.chat(request, object : StreamingChatResponseHandler {
                override fun onPartialResponse(partialResponse: String) = synchronized(lock) {
                    if (accepting && !result.isDone) try { delta(partialResponse) } catch (error: Exception) { result.completeExceptionally(error) }
                    Unit
                }
                override fun onCompleteResponse(response: ChatResponse) { result.complete(response) }
                override fun onError(error: Throwable) { result.completeExceptionally(error) }
            })
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (true) {
                checkCancelled()
                if (System.nanoTime() >= deadline) throw TimeoutException("Compaction request timed out.")
                try { return result.get(100, TimeUnit.MILLISECONDS) }
                catch (_: TimeoutException) { /* Cancellation remains responsive while awaiting the provider. */ }
                catch (error: ExecutionException) { throw (error.cause ?: error) }
            }
        } finally {
            synchronized(lock) { accepting = false }
        }
    }

    companion object {
        private val SHRINK_STEPS = listOf(0.7, 0.5, 0.35)
        private const val NUDGE = "The previous response did not fill in the required summary template. Do not call tools. Return the summary as text using the exact section headings from the template."

        fun memory(summary: String, recent: String): String = if (summary.isBlank()) "" else
            "<conversation-checkpoint>\n<summary>\n$summary\n</summary>\n<recent-context>\n$recent\n</recent-context>\n</conversation-checkpoint>"

        private fun addUsage(previous: Long?, next: Long?): Long? = if (previous == null && next == null) null else (previous ?: 0) + (next ?: 0)

        private fun filled(text: String): Boolean = text.lineSequence().any { it.trim() in HEADINGS }
        private val HEADINGS = setOf("## Objective", "## Requirements", "## Decisions", "## Work State", "### Completed", "### Active", "### Blocked", "## Next Move", "## Relevant Files", "## Important Context")

        internal fun split(records: List<AgentContextMessage>, keep: Int = 15_000, previousRecent: Boolean = false): Int {
            val entries = records.indices.filter { text(records[it]).isNotBlank() }
            if (entries.isEmpty()) return records.size
            var total = 0
            var dropped = entries.size
            while (dropped > 0) {
                val next = total + AgentTokenEstimate.text(text(records[entries[dropped - 1]]))
                if (next > keep) break
                total = next
                dropped--
            }
            dropped = minOf(dropped, entries.lastIndex)
            val boundary = entries.take(dropped + 1).lastOrNull { records[it].role == "user" && records[it].patchState == null }
            if (boundary != null && boundary > entries.first()) return boundary
            val latest = entries.lastOrNull { records[it].role == "user" && records[it].patchState == null }
            return latest?.takeIf { it > entries.first() } ?: if (previousRecent) 0 else records.size
        }

        internal fun text(record: AgentContextMessage, reasoning: Boolean = true): String {
            if (record.patchState != null) return ""
            return when (record.role) {
                "user" -> buildString {
                    append("[User]: ${record.text}")
                    record.attachments.forEach { append("\n[Attached ${it.mime}: ${it.name}]") }
                }
                "assistant" -> buildList {
                    if (record.text.isNotBlank()) add("[Assistant]: ${record.text}")
                    if (reasoning && !record.thinking.isNullOrBlank()) add("[Assistant reasoning]: ${record.thinking}")
                    record.calls.forEach { add("[Assistant tool call]: ${it.name}(${it.arguments})") }
                }.joinToString("\n")
                "tool" -> buildString {
                    append("[Tool result]: ${truncate(record.text)}")
                    record.attachments.forEach { append("\n[Attached ${it.mime}: ${it.name}]") }
                }
                else -> ""
            }
        }

        private fun truncate(text: String): String {
            if (text.codePointCount(0, text.length) <= 1250) return text
            return text.substring(0, text.offsetByCodePoints(0, 1250)) + "\n[truncated]"
        }

        internal fun buildPrompt(update: Boolean, legacy: Boolean): String = buildString {
            appendLine(if (update) "Update the existing checkpoint in the conversation above into one consolidated summary." else
                "You MUST summarize the conversation above into a structured summary that will be given to another agent to resume the work.")
            if (update) {
                if (legacy) appendLine("The existing checkpoint was written with an earlier format that recorded far more detail than this one asks for. Rewrite it at the level of detail described below rather than carrying its detail forward. Keep its requirements, decisions, and open questions; they came from earlier conversation with the user.")
                appendLine("Newer history always takes precedence over the existing checkpoint. Preserve previous information unless newer history clearly contradicts, supersedes, resolves, or makes it stale. If something is no longer relevant to continuing the work, you may remove it.")
                appendLine("Incorporate newer requirements, decisions, progress, and context. Reconcile Work State and Next Move: move completed work out of Active, remove resolved blockers and answered questions, and preserve unresolved or pending work.")
                appendLine("Return only the updated Markdown sections. Do not reproduce the `<conversation-checkpoint>`, `<summary>`, or `<recent-context>` wrapper tags from the previous checkpoint.")
            }
            appendLine("Summarize only what the user and the assistant said and did. Leave out instructions and setup the assistant was given rather than told by the user: repository conventions, instruction files such as AGENTS.md, and environment details like the session ID. The next agent receives current versions of all of these separately.")
            appendLine(TEMPLATE)
            appendLine(RULES)
            appendLine("Do not continue the task or call tools.")
            appendLine("Return only the structured summary in the requested format. Do not include a preamble, explanation, or other commentary.")
        }

        private const val RULES = """Rules:
- Keep each section concise. Use terse, single-line bullets, not prose paragraphs or nested lists.
- Prefer short references over detailed restatement. It is fine to leave out information the next agent can recover from the code or the files listed above.
- Preserve exact file paths, symbols, commands, error strings, URLs, and identifiers.
- Carry forward only user questions or requests that remain unanswered or require further action. Do not repeat ones that newer history has answered or resolved. Preserve exact wording when carrying one forward.
- Preserve consequential workflow state, including whether changes are uncommitted, committed, pushed, under review, or merged.
- Do not mention the summary process or that context was compacted."""

        private const val TEMPLATE = """You MUST use this format for your response (you may omit sections that aren't applicable). Do not include the <template> tags in your response.
<template>
## Objective
- [one or two brief sentences describing what the user is trying to accomplish]

## Requirements
- [constraints, preferences, requirements, and scope boundaries stated by the user, or "(none)"]

## Decisions
- [decisions already made and why, or "(none)"]

## Work State
Break the objective into smaller goals and report which are completed, which are being worked on, and which are blocked.
### Completed
- [goals that have been completed; otherwise "(none)"]

### Active
- [goals currently being worked on; otherwise "(none)"]

### Blocked
- [anything blocking progress, and why; otherwise "(none)"]

## Next Move
1. [ordered list of next actions, or "(none)"]

## Relevant Files
List the files and directories, other than the current working directory, that another agent would need to open to continue this work. Include at most 15, most important first. Do not list every file that was read or changed. Include paths outside the current working directory when relevant. If none, write "(none)".
- `[file or directory path]`: [brief reason it matters]

## Important Context
- [facts the next agent cannot continue without and cannot easily find on its own; or "(none)"]
</template>"""
    }
}

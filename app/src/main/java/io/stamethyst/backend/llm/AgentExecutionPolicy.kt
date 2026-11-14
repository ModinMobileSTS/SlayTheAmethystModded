package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.model.chat.response.ChatResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/** Execution limits are independent of request deadlines. Null explicitly disables a limit. */
data class AgentExecutionPolicy(
    val maxDurationMillis: Long? = 2 * 60 * 60 * 1_000L,
    val maxTokens: Long? = null,
    val repeatedBatchLimit: Int = 3,
) {
    init {
        require(maxDurationMillis == null || maxDurationMillis > 0)
        require(maxTokens == null || maxTokens > 0)
        require(repeatedBatchLimit >= 2)
    }
}

class AgentExecutionLimitException(message: String) : IllegalStateException(message)

internal class AgentExecutionBudget(private val policy: AgentExecutionPolicy, private val clock: AgentRequestClock) {
    private val startedAt = clock.nowMillis()
    private var tokens = 0L

    @Synchronized fun observe(response: ChatResponse) {
        // Providers without usage cannot enforce an exact token ceiling; the time limit still applies.
        val usage = response.tokenUsage()
        val count = usage?.totalTokenCount()?.toLong()
            ?: ((usage?.inputTokenCount()?.toLong() ?: 0) + (usage?.outputTokenCount()?.toLong() ?: 0))
        tokens = (tokens + count.coerceAtLeast(0)).coerceAtLeast(tokens)
    }

    @Synchronized fun check() {
        val reason = when {
            policy.maxDurationMillis?.let { clock.nowMillis() - startedAt >= it } == true -> "execution time budget"
            policy.maxTokens?.let { tokens >= it } == true -> "execution token budget"
            else -> return
        }
        throw AgentExecutionLimitException("Stopped at the $reason. Completed tool results are saved; resume the task to continue with a fresh execution budget.")
    }
}

/** Compare complete ordered batches, ignoring call IDs and JSON object key order, never tool schema. */
internal class AgentProgressGuard(private val repeatLimit: Int) {
    private var previous: String? = null
    private var repeats = 0

    fun reset() { previous = null; repeats = 0 }

    fun completed(batch: List<Pair<ToolExecutionRequest, AgentToolOutput>>, changed: Boolean): String? {
        if (changed) { reset(); return null }
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray()); digest.update(0); digest.update(bytes)
        }
        batch.forEach { (request, result) ->
            add(request.name()); add(normalize(request.arguments())); add(normalize(result.text))
            result.attachments.forEach { add(it.mime); add(it.content); add(it.data) }
        }
        val signature = digest.digest().joinToString("") { "%02x".format(it) }
        repeats = if (signature == previous) repeats + 1 else 1
        previous = signature
        return if (repeats >= repeatLimit) {
            "Stopped after $repeats identical tool batches with no progress (${batch.joinToString { it.first.name() }}). " +
                "All tool results are saved. Check the reported failure, change the arguments or provide new input before resuming."
        } else null
    }

    private fun normalize(text: String): String = runCatching { canonical(Json.parseToJsonElement(text)).toString() }.getOrDefault(text)
    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
}

/** Streaming progress is observable; synchronous generation must retain the full user limit. */
internal fun agentAttemptPolicy(totalMillis: Long, streaming: Boolean) = AgentRequestTimeoutPolicy(
    totalTimeoutMillis = totalMillis,
    firstEventTimeoutMillis = if (streaming) minOf(totalMillis, 120_000) else totalMillis,
    idleTimeoutMillis = if (streaming) minOf(totalMillis, 60_000) else totalMillis,
)

package io.stamethyst.backend.llm

import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata
import dev.langchain4j.model.openai.OpenAiResponsesChatResponseMetadata
import dev.langchain4j.model.openai.OpenAiTokenUsage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Normalized input includes cached tokens. Null means unreported, never an assumed cache miss. */
data class AgentCacheUsage(
    val input: Long?, val read: Long?, val write: Long?,
    val inputExcludesCached: Boolean = false,
) {
    companion object {
        fun from(response: ChatResponse, inputExcludesCached: Boolean = false): AgentCacheUsage {
            val metadata = response.metadata()
            val bodies = when (metadata) {
                is OpenAiChatResponseMetadata -> listOfNotNull(metadata.rawHttpResponse()?.body()) +
                    metadata.rawServerSentEvents().orEmpty().map { it.data() }
                is OpenAiResponsesChatResponseMetadata -> listOfNotNull(metadata.rawHttpResponse()?.body()) +
                    metadata.rawServerSentEvents().orEmpty().map { it.data() }
                else -> emptyList()
            }
            // SSE may end with [DONE] or usage:null. Only consume actual usage objects.
            val raw = bodies.asReversed().asSequence().mapNotNull { body -> runCatching {
                val root = Json.parseToJsonElement(body).jsonObject
                (root["usage"] as? JsonObject) ?: (root["response"] as? JsonObject)?.get("usage") as? JsonObject
            }.getOrNull() }.firstOrNull()
            // The Responses decoder synthesizes cachedTokens=0 even when the wire usage omits it.
            // Captured payloads are authoritative about whether cache usage was actually reported.
            // Keep SDK-only responses supported when no raw payload is available.
            val fallbackRead = (response.tokenUsage() as? OpenAiTokenUsage)
                ?.inputTokensDetails()?.cachedTokens()?.toLong()
                ?.takeUnless { bodies.any(String::isNotBlank) }
            return parse(raw, response.tokenUsage()?.inputTokenCount()?.toLong(), fallbackRead,
                inputExcludesCached)
        }

        internal fun parse(raw: JsonObject?, fallbackInput: Long? = null, fallbackRead: Long? = null,
            inputExcludesCached: Boolean = false): AgentCacheUsage {
            fun JsonObject?.number(key: String): Long? = this?.get(key)?.let {
                runCatching { it.jsonPrimitive.longOrNull }.getOrNull()
            }?.takeIf { it >= 0 }
            val input = (raw.number("prompt_tokens") ?: raw.number("input_tokens") ?: fallbackInput)?.takeIf { it >= 0 }
            val details = (raw?.get("prompt_tokens_details") as? JsonObject)
                ?: (raw?.get("input_tokens_details") as? JsonObject)
            val read = details.number("cached_tokens") ?: raw.number("prompt_cache_hit_tokens") ?:
                raw.number("cached_tokens") ?: raw.number("cache_read_input_tokens") ?: fallbackRead?.takeIf { raw == null }
            val write = details.number("cache_write_tokens") ?: raw.number("cache_creation_input_tokens") ?:
                raw.number("cache_created_input_tokens")
            // Some compatibility bridges forward Anthropic-style *uncached* input as prompt_tokens
            // and leave an unused input_tokens=0. Require the full observed wire signature before
            // learning this convention, rather than clamping a >100% rate or accepting any bad count.
            val output = raw.number("completion_tokens")
            val exclusive = inputExcludesCached || (input != null && read != null && read > input &&
                raw.number("input_tokens") == 0L && output != null && output <= Long.MAX_VALUE - input &&
                raw.number("total_tokens") == input + output)
            val extraWrite = write ?: 0
            val normalizedInput = if (exclusive && input != null && read != null && read >= 0 &&
                read <= Long.MAX_VALUE - extraWrite && input <= Long.MAX_VALUE - read - extraWrite)
                input + read + extraWrite else input
            return AgentCacheUsage(normalizedInput, read?.takeIf { it >= 0 && normalizedInput != null && it <= normalizedInput },
                write?.takeIf { it >= 0 && normalizedInput != null && it <= normalizedInput }, exclusive)
        }
    }
}

/** Successful main-agent responses, including empty retries; excludes separate summary calls. */
@Serializable
data class AgentCacheStats(
    val endpoint: String = "",
    val requests: Long = 0,
    val reportedRequests: Long = 0,
    val inputTokens: Long = 0,
    val readTokens: Long = 0,
    val lastInputTokens: Long? = null,
    val lastReadTokens: Long? = null,
    val lastWriteTokens: Long? = null,
    /** Learned only from the explicit compatibility signature above, scoped to this model key. */
    val inputExcludesCached: Boolean = false,
    val writeTokens: Long = 0,
) {
    val lastRate: Double? get() = rate(lastReadTokens, lastInputTokens)
    val totalRate: Double? get() = rate(readTokens, inputTokens)

    fun observe(usage: AgentCacheUsage): AgentCacheStats {
        val known = usage.input != null && usage.read != null
        return copy(
            requests = requests + 1,
            reportedRequests = reportedRequests + if (known) 1 else 0,
            // Once this wire convention is established, fix earlier known inputs in this model's
            // cumulative counters too. Those already contain the reported cache subsets.
            inputTokens = inputTokens + (if (usage.inputExcludesCached && !inputExcludesCached) readTokens + writeTokens else 0) +
                if (known) usage.input!! else 0,
            readTokens = readTokens + if (known) usage.read!! else 0,
            writeTokens = writeTokens + if (known) usage.write ?: 0 else 0,
            lastInputTokens = usage.input,
            lastReadTokens = usage.read,
            lastWriteTokens = usage.write,
            inputExcludesCached = inputExcludesCached || usage.inputExcludesCached,
        )
    }

    private fun rate(read: Long?, input: Long?): Double? =
        if (read != null && input != null && input > 0) read.toDouble() / input else null
}

package io.stamethyst.backend.llm

import dev.langchain4j.model.chat.response.ChatResponse

/** Splits inline thinking without exposing tags, even when an SSE chunk splits a delimiter. */
internal class AgentThinkingContentParser(
    private val onText: (String) -> Unit,
    private val onThinking: (String) -> Unit,
) {
    // At most seven characters: the longest proper prefix of either delimiter.
    private var pending = ""
    private var thinking = false

    fun append(content: String) {
        if (content.isEmpty()) return
        val input = if (pending.isEmpty()) content else pending + content
        pending = ""
        var offset = 0
        while (offset < input.length) {
            val delimiter = if (thinking) "</think>" else "<think>"
            val index = input.indexOf(delimiter, offset)
            if (index >= 0) {
                emit(input.substring(offset, index))
                offset = index + delimiter.length
                thinking = !thinking
                continue
            }
            // Retain only a possible delimiter prefix, not the whole reasoning/answer.
            var retained = minOf(input.length - offset, delimiter.length - 1)
            while (retained > 0 && !input.regionMatches(input.length - retained, delimiter, 0, retained)) {
                retained--
            }
            val ready = input.length - retained
            emit(input.substring(offset, ready))
            pending = input.substring(ready)
            break
        }
    }

    fun finish() {
        // An unclosed thinking block stays thinking; an incomplete opening tag is ordinary text.
        emit(pending)
        pending = ""
    }

    private fun emit(value: String) {
        if (value.isNotEmpty()) {
            if (thinking) onThinking(value) else onText(value)
        }
    }
}

/** Normalize before telemetry, empty-response detection and context persistence, not just display. */
internal fun parseAgentThinkingContent(response: ChatResponse): ChatResponse {
    val message = response.aiMessage()
    val content = message.text() ?: return response
    if (!content.contains("<think>")) return response
    val text = StringBuilder()
    val thinking = StringBuilder()
    AgentThinkingContentParser({ text.append(it) }, { thinking.append(it) }).apply {
        append(content)
        finish()
    }
    return response.toBuilder().aiMessage(message.toBuilder()
        .text(text.toString())
        .thinking(listOfNotNull(message.thinking()?.takeIf { it.isNotEmpty() },
            thinking.toString().takeIf { it.isNotEmpty() }).joinToString("\n"))
        .build()).build()
}

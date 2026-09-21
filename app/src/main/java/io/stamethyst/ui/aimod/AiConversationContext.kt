package io.stamethyst.ui.aimod

import android.content.Context
import io.stamethyst.backend.llm.AgentContextMessage
import io.stamethyst.backend.llm.AgentContextState
import io.stamethyst.backend.llm.AgentContextToolCall
import io.stamethyst.backend.llm.DEFAULT_AGENT_CONTEXT_LIMIT

internal object AiContextLimits {
    fun key(baseUrl: String, model: String): String = "$baseUrl/$model"

    fun get(context: Context, key: String): Int = context
        .getSharedPreferences("ai_context_limits", Context.MODE_PRIVATE)
        .getInt(key, 0).takeIf { it > 0 } ?: DEFAULT_AGENT_CONTEXT_LIMIT
}

/** Migration is explicit about old, display-only tool results; never invent missing tool output. */
internal fun AiEditorSession.modelContext(excludingMessageId: Long): AgentContextState {
    val old = context ?: AgentContextState()
    val importedIds = old.messages.mapTo(HashSet()) { it.sourceMessageId }
    val additions = messages.filter { it.id != excludingMessageId && it.id !in importedIds }.flatMap { message ->
        buildList {
            if (message.fromUser) {
                add(AgentContextMessage(message.id, "user", buildString {
                    append(message.text)
                    message.attachments.forEach { append("\n\nAttachment: ${it.name}\n${it.content}") }
                }))
            } else {
                message.tools.forEach { tool ->
                    add(AgentContextMessage(message.id, "assistant", calls = listOf(
                        AgentContextToolCall(tool.id, tool.name, tool.arguments),
                    )))
                    add(AgentContextMessage(message.id, "tool", callId = tool.id, toolName = tool.name,
                        text = (tool.result ?: "Interrupted tool; outcome unknown. Inspect state before retrying.") +
                            "\n[Imported legacy UI record; output may be incomplete. Re-read files to verify exact content.]"))
                }
                if (message.text.isNotBlank()) add(AgentContextMessage(message.id, "assistant",
                    message.text + if (message.failed || message.streaming)
                        "\n[This reply was interrupted or failed; statements may describe unfinished work.]" else ""))
            }
        }
    }
    val records = old.messages + additions
    return old.copy(
        messages = records,
        activePatchId = old.activePatchId ?: AgentContextState.inferActivePatch(records),
    ).closeInterruptedTools()
}

/** A delayed poll must never rewind a newer local revision. */
internal fun mergeAiSessions(local: List<AiEditorSession>, incoming: List<AiEditorSession>): List<AiEditorSession> {
    val result = local.associateByTo(LinkedHashMap()) { it.id }
    incoming.forEach { candidate ->
        val current = result[candidate.id]
        if (current == null || candidate.revision > current.revision) result[candidate.id] = candidate
    }
    return result.values.toList()
}

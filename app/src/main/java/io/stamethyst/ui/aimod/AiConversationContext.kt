package io.stamethyst.ui.aimod

import android.content.Context
import io.stamethyst.backend.llm.AgentContextMessage
import io.stamethyst.backend.llm.AgentContextState
import io.stamethyst.backend.llm.AgentContextToolCall
import io.stamethyst.backend.llm.AgentContextAttachment
import io.stamethyst.backend.llm.DEFAULT_AGENT_CONTEXT_LIMIT
import io.stamethyst.backend.llm.AgentContextBudget
import io.stamethyst.backend.llm.AgentModelBudgetPolicy
import io.stamethyst.backend.llm.AgentModelLimits
import io.stamethyst.backend.llm.NewApiModel
import io.stamethyst.backend.llm.DEFAULT_AGENT_OUTPUT_TARGET
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object AiContextLimits {
    fun key(baseUrl: String, model: String): String = "$baseUrl/$model"

    fun get(context: Context, key: String): Int = context
        .getSharedPreferences("ai_context_limits", Context.MODE_PRIVATE)
        .getInt(key, 0).takeIf { it > 0 } ?: DEFAULT_AGENT_CONTEXT_LIMIT

    fun explicitInputLimit(context: Context, key: String): Int? = context
        .getSharedPreferences("ai_context_limits", Context.MODE_PRIVATE)
        .getInt(key, 0).takeIf { it > 0 }

    fun overrides(context: Context, key: String): AgentModelLimits = readLimits(context, "ai_model_budget_overrides", key)

    fun explicitOutputTarget(context: Context, key: String): Int? = context
        .getSharedPreferences("ai_model_output_targets", Context.MODE_PRIVATE)
        .getInt(key, 0).takeIf { it > 0 }

    fun budget(context: Context, key: String): AgentContextBudget = AgentModelBudgetPolicy.resolve(
        userInputLimit = explicitInputLimit(context, key),
        metadata = readLimits(context, "ai_model_budget_catalog", key),
        overrides = overrides(context, key),
        outputTarget = explicitOutputTarget(context, key) ?: DEFAULT_AGENT_OUTPUT_TARGET,
    )

    fun set(context: Context, key: String, inputLimit: Int?, overrides: AgentModelLimits, outputTarget: Int? = null) {
        require(inputLimit == null || inputLimit > 0)
        require(outputTarget == null || outputTarget > 0)
        context.getSharedPreferences("ai_context_limits", Context.MODE_PRIVATE).edit().apply {
            if (inputLimit == null) remove(key) else putInt(key, inputLimit)
        }.apply()
        context.getSharedPreferences("ai_model_budget_overrides", Context.MODE_PRIVATE).edit()
            .putString(key, Json.encodeToString(overrides)).apply()
        context.getSharedPreferences("ai_model_output_targets", Context.MODE_PRIVATE).edit().apply {
            if (outputTarget == null) remove(key) else putInt(key, outputTarget)
        }.apply()
    }

    /** Call on a successful catalog refresh, before reducing models to their display IDs. */
    fun cacheCatalog(context: Context, baseUrl: String, models: List<NewApiModel>) {
        val savedBaseUrl = baseUrl.trim().trimEnd('/') // Match LlmSettingsRepository.set normalization.
        context.getSharedPreferences("ai_model_budget_catalog", Context.MODE_PRIVATE).edit().apply {
            models.forEach { putString(key(savedBaseUrl, it.id), Json.encodeToString(it.limits)) }
        }.apply()
    }

    private fun readLimits(context: Context, preferences: String, key: String): AgentModelLimits = runCatching {
        context.getSharedPreferences(preferences, Context.MODE_PRIVATE).getString(key, null)
            ?.let { Json { ignoreUnknownKeys = true }.decodeFromString<AgentModelLimits>(it) }
    }.getOrNull() ?: AgentModelLimits()
}

/** Migration is explicit about old, display-only tool results; never invent missing tool output. */
internal fun AiEditorSession.modelContext(excludingMessageId: Long): AgentContextState {
    val old = context ?: AgentContextState()
    val importedIds = old.messages.mapTo(HashSet()) { it.sourceMessageId }
    val additions = messages.filter { it.id != excludingMessageId && it.id !in importedIds }.flatMap { message ->
        buildList {
            if (message.notice) {
                // Retry/failure notices are UI history, not instructions for the
                // next model turn.
            } else if (message.fromUser) {
                add(AgentContextMessage(message.id, "user", message.text,
                    attachments = message.attachments.map {
                        AgentContextAttachment(
                            name = it.name,
                            content = it.content,
                            mime = it.mime,
                            data = it.data,
                        )
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

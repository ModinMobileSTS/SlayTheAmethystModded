package io.stamethyst.backend.llm

import kotlinx.serialization.Serializable

/** Unknown capabilities stay null. Defaults below are application budgets, not model claims. */
@Serializable
data class AgentModelLimits(
    val contextWindow: Int? = null,
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int? = null,
) {
    init {
        require(contextWindow == null || contextWindow >= 2)
        require(maxInputTokens == null || maxInputTokens > 0)
        require(maxOutputTokens == null || maxOutputTokens > 0)
    }

    fun overriding(metadata: AgentModelLimits): AgentModelLimits = AgentModelLimits(
        contextWindow ?: metadata.contextWindow,
        maxInputTokens ?: metadata.maxInputTokens,
        maxOutputTokens ?: metadata.maxOutputTokens,
    )
}

const val DEFAULT_AGENT_OUTPUT_TARGET = 4_000
const val DEFAULT_AGENT_SUMMARY_OUTPUT_TARGET = 2_000
const val MAX_AGENT_SUMMARY_OUTPUT_TARGET = 8_000

/** Reserve 10% normally, up to 20% for the most recent tool batch; never a fixed small-window tax. */
data class AgentCompactionMargin(
    val fraction: Double = 0.10,
    val maxFraction: Double = 0.20,
    val maxTokens: Int = 8_192,
) {
    init {
        require(fraction.isFinite() && fraction in 0.0..0.5)
        require(maxFraction.isFinite() && maxFraction in fraction..0.5)
        require(maxTokens >= 0)
    }

    fun tokens(inputLimit: Int, recentToolBatchTokens: Int = 0): Int {
        require(inputLimit > 0)
        val cap = minOf((inputLimit * maxFraction).toInt(), maxTokens, inputLimit - 1)
        return maxOf((inputLimit * fraction).toInt(), recentToolBatchTokens.coerceAtLeast(0)).coerceAtMost(cap)
    }
}

data class AgentContextBudget(
    /** User input budget, before independently known model constraints. Never a total window. */
    val limit: Int = DEFAULT_AGENT_CONTEXT_LIMIT,
    val modelLimits: AgentModelLimits = AgentModelLimits(),
    val outputTarget: Int = DEFAULT_AGENT_OUTPUT_TARGET,
    val summaryOutputTarget: Int = DEFAULT_AGENT_SUMMARY_OUTPUT_TARGET,
    val compactionMargin: AgentCompactionMargin = AgentCompactionMargin(),
    val usesDefaultInputBudget: Boolean = false,
) {
    init {
        require(limit > 0)
        require(outputTarget > 0)
        require(summaryOutputTarget > 0)
    }

    // Reserve the requested output first only within a known shared window. With no window the
    // explicit input budget is unchanged, even when it is smaller than the output target.
    val outputReserve: Int = outputCap(outputTarget)
    val inputLimit: Int = inputCap(outputReserve)
    val summaryOutputLimit: Int = outputCap(minOf(summaryOutputTarget, MAX_AGENT_SUMMARY_OUTPUT_TARGET))
    val summaryInputLimit: Int = inputCap(summaryOutputLimit)

    private fun outputCap(target: Int): Int = minOf(
        target, modelLimits.maxOutputTokens ?: Int.MAX_VALUE,
        modelLimits.contextWindow?.minus(1) ?: Int.MAX_VALUE,
    )

    private fun inputCap(output: Int): Int = minOf(
        limit, modelLimits.maxInputTokens ?: Int.MAX_VALUE,
        modelLimits.contextWindow?.minus(output) ?: Int.MAX_VALUE,
    )

    fun compactionThreshold(recentToolBatchTokens: Int = 0): Int =
        inputLimit - compactionMargin.tokens(inputLimit, recentToolBatchTokens)
}

object AgentModelBudgetPolicy {
    /** Explicit per-model overrides > optional catalog metadata > conservative application budgets. */
    fun resolve(
        userInputLimit: Int? = null,
        metadata: AgentModelLimits = AgentModelLimits(),
        overrides: AgentModelLimits = AgentModelLimits(),
        outputTarget: Int = DEFAULT_AGENT_OUTPUT_TARGET,
        summaryOutputTarget: Int = DEFAULT_AGENT_SUMMARY_OUTPUT_TARGET,
        compactionMargin: AgentCompactionMargin = AgentCompactionMargin(),
    ): AgentContextBudget = AgentContextBudget(
        limit = userInputLimit ?: DEFAULT_AGENT_CONTEXT_LIMIT,
        modelLimits = overrides.overriding(metadata),
        outputTarget = outputTarget,
        summaryOutputTarget = summaryOutputTarget,
        compactionMargin = compactionMargin,
        usesDefaultInputBudget = userInputLimit == null,
    )
}

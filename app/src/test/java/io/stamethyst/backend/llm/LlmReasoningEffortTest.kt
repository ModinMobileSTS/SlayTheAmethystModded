package io.stamethyst.backend.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmReasoningEffortTest {
    @Test
    fun selectableTiersIncludeOffWithoutImplyingProviderWireValues() {
        assertEquals(listOf("off", "low", "high", "max"), LlmReasoningEffort.selectable.map { it.name.lowercase() })
    }

    @Test
    fun savedSelectionsSurviveAndLegacyMediumKeepsItsMigration() {
        LlmReasoningEffort.entries.forEach {
            assertEquals(it, LlmReasoningEffort.fromStored(it.name))
        }
        assertEquals(LlmReasoningEffort.HIGH, LlmReasoningEffort.fromStored("MEDIUM"))
    }

    @Test
    fun newAndInvalidPreferencesUseFasterLowDefault() {
        assertEquals(LlmReasoningEffort.LOW, LlmReasoningEffort.fromStored(null))
        assertEquals(LlmReasoningEffort.LOW, LlmReasoningEffort.fromStored("unknown"))
        assertEquals(LlmReasoningEffort.LOW, LlmSettings().reasoningEffort)
    }
}

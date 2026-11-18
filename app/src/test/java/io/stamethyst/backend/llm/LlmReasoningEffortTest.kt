package io.stamethyst.backend.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmReasoningEffortTest {
    @Test
    fun selectableTiersExposePopularProviderPresetsInOrder() {
        assertEquals(listOf("none", "minimal", "low", "medium", "high", "xhigh", "max"),
            LlmReasoningEffort.selectable.map { it.name.lowercase() })
    }

    @Test
    fun savedSelectionsSurviveIncludingTheNewMediumTier() {
        LlmReasoningEffort.entries.forEach {
            assertEquals(it, LlmReasoningEffort.fromStored(it.name))
        }
        assertEquals(LlmReasoningEffort.MEDIUM, LlmReasoningEffort.fromStored("MEDIUM"))
    }

    @Test
    fun newAndInvalidPreferencesUseFasterLowDefault() {
        assertEquals(LlmReasoningEffort.LOW, LlmReasoningEffort.fromStored(null))
        assertEquals(LlmReasoningEffort.LOW, LlmReasoningEffort.fromStored("unknown"))
        assertEquals(LlmReasoningEffort.LOW, LlmSettings().reasoningEffort)
    }

    @Test fun availablePresetsAreOrderedDeduplicatedAndNeverEmpty() {
        assertEquals(listOf(LlmReasoningEffort.LOW, LlmReasoningEffort.MAX), LlmReasoningEffort.normalize(
            listOf(LlmReasoningEffort.MAX, LlmReasoningEffort.OFF, LlmReasoningEffort.LOW, LlmReasoningEffort.MAX)))
        assertEquals(listOf(LlmReasoningEffort.LOW), LlmReasoningEffort.normalize(emptyList()))
    }

    @Test fun removingActivePresetSelectsFirstRemainingButAddingPresetsKeepsSelection() {
        val old = LlmSettings(reasoningEffort = LlmReasoningEffort.HIGH)
        val onlyMedium = LlmSettingsPatch(availableReasoningEfforts = listOf(LlmReasoningEffort.MEDIUM))
            .normalized().applyTo(old)
        assertEquals(LlmReasoningEffort.MEDIUM, onlyMedium.reasoningEffort)
        assertEquals(listOf(LlmReasoningEffort.MEDIUM), onlyMedium.availableReasoningEfforts)
        val expanded = LlmSettingsPatch(availableReasoningEfforts = listOf(LlmReasoningEffort.LOW, LlmReasoningEffort.MEDIUM))
            .normalized().applyTo(onlyMedium)
        assertEquals(LlmReasoningEffort.MEDIUM, expanded.reasoningEffort)
    }

    @Test fun unrelatedEditsPreserveLegacyOffButConfiguringPresetsExitsIt() {
        val legacy = LlmSettings(reasoningEffort = LlmReasoningEffort.OFF)
        assertEquals(LlmReasoningEffort.OFF, LlmSettingsPatch(modelName = "other").applyTo(legacy).reasoningEffort)
        assertEquals(LlmReasoningEffort.HIGH, LlmSettingsPatch(availableReasoningEfforts = listOf(LlmReasoningEffort.HIGH))
            .applyTo(legacy).reasoningEffort)
        assertEquals(LlmReasoningEffort.OFF,
            LlmSettingsPatch(availableReasoningEfforts = listOf(LlmReasoningEffort.HIGH))
                .applyTo(legacy) { false }.reasoningEffort)
    }
}

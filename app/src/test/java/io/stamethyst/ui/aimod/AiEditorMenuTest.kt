package io.stamethyst.ui.aimod

import io.stamethyst.backend.llm.LlmReasoningEffort
import org.junit.Assert.*
import org.junit.Test

class AiEditorMenuTest {
    @Test fun pickerUsesSelectedEffortAndFallsBackWhenItIsRemoved() {
        val options = listOf(LlmReasoningEffort.MEDIUM, LlmReasoningEffort.XHIGH)
        assertEquals(LlmReasoningEffort.XHIGH, resolveAiReasoningEffort(LlmReasoningEffort.XHIGH, options))
        assertEquals(LlmReasoningEffort.MEDIUM, resolveAiReasoningEffort(LlmReasoningEffort.HIGH, options))
        assertEquals(LlmReasoningEffort.LOW, resolveAiReasoningEffort(LlmReasoningEffort.MAX, emptyList()))
    }

    @Test fun legacyOffRemainsDistinctFromProviderNone() {
        assertEquals(LlmReasoningEffort.OFF,
            resolveAiReasoningEffort(LlmReasoningEffort.OFF, LlmReasoningEffort.defaults))
        assertNotEquals(LlmReasoningEffort.OFF, LlmReasoningEffort.NONE)
    }

    @Test fun sliderUsesOnlyConfiguredTiersAndRetainsLegacyOffWhenNeeded() {
        assertEquals(listOf(LlmReasoningEffort.LOW, LlmReasoningEffort.XHIGH),
            aiReasoningPickerOptions(LlmReasoningEffort.LOW,
                listOf(LlmReasoningEffort.XHIGH, LlmReasoningEffort.LOW, LlmReasoningEffort.XHIGH)))
        assertEquals(listOf(LlmReasoningEffort.OFF, LlmReasoningEffort.MEDIUM),
            aiReasoningPickerOptions(LlmReasoningEffort.OFF, listOf(LlmReasoningEffort.MEDIUM)))
        assertEquals(listOf(LlmReasoningEffort.LOW),
            aiReasoningPickerOptions(LlmReasoningEffort.LOW, emptyList()))
    }

    @Test fun sliderClampsAndSnapsForOneTwoAndSevenTiers() {
        assertEquals(0, aiReasoningSliderIndex(0f, 1))
        assertEquals(0, aiReasoningSliderIndex(1f, 1))
        assertEquals(0, aiReasoningSliderIndex(-2f, 2))
        assertEquals(0, aiReasoningSliderIndex(0.49f, 2))
        assertEquals(1, aiReasoningSliderIndex(0.51f, 2))
        assertEquals(4, aiReasoningSliderIndex(4.2f, 7))
        assertEquals(6, aiReasoningSliderIndex(9f, 7))
    }

    @Test fun usageBreakdownMatchesTotalAndDoesNotInventMissingTelemetry() {
        val known = AiHistoryStep("a", io.stamethyst.backend.llm.AgentStepKind.MODEL, "", "", "",
            null, null, io.stamethyst.backend.llm.AgentStepStatus.COMPLETED, 100, 20)
        val unknown = known.copy(key = "b", inputTokens = 999, outputTokens = null)
        val tool = known.copy(key = "c", kind = io.stamethyst.backend.llm.AgentStepKind.TOOL, inputTokens = 999)
        val turn = AiHistoryTurn(1, 1, "prompt", listOf(known, unknown, tool), false, false, null,
            aiHistoryUsage(listOf(known, unknown, tool)), emptyList())
        assertEquals(100L to 20L, aiEditorTokenBreakdown(AiHistorySnapshot(turns = listOf(turn))))
        assertEquals(null to null, aiEditorTokenBreakdown(AiHistorySnapshot()))
        assertTrue(turn.usage.partial)
        assertEquals(120L, turn.usage.tokens)
    }
}

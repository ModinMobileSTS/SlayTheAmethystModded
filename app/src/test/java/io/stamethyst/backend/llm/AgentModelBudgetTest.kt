package io.stamethyst.backend.llm

import org.junit.Assert.*
import org.junit.Test

class AgentModelBudgetTest {
    @Test fun unknownModelUsesBudgetsWithoutInventingCapabilities() {
        val budget = AgentModelBudgetPolicy.resolve()
        assertEquals(32_000, budget.inputLimit)
        assertEquals(4_000, budget.outputReserve)
        assertEquals(2_000, budget.summaryOutputLimit)
        assertEquals(AgentModelLimits(), budget.modelLimits)
        assertTrue(budget.usesDefaultInputBudget)
    }

    @Test fun explicitLegacyInputBudgetIsNotTreatedAsASharedWindow() {
        val budget = AgentModelBudgetPolicy.resolve(userInputLimit = 200_000)
        assertEquals(200_000, budget.inputLimit)
        assertEquals(4_000, budget.outputReserve)
        assertFalse(budget.usesDefaultInputBudget)
        assertEquals(128, AgentModelBudgetPolicy.resolve(userInputLimit = 128).inputLimit)
    }

    @Test fun overridesWinPerFieldAndUnspecifiedFieldsRetainMetadata() {
        val budget = AgentModelBudgetPolicy.resolve(
            userInputLimit = 100_000,
            metadata = AgentModelLimits(contextWindow = 20_000, maxInputTokens = 16_000, maxOutputTokens = 1_000),
            overrides = AgentModelLimits(contextWindow = 40_000, maxOutputTokens = 3_000),
        )
        assertEquals(AgentModelLimits(40_000, 16_000, 3_000), budget.modelLimits)
        assertEquals(16_000, budget.inputLimit)
        assertEquals(3_000, budget.outputReserve)
    }

    @Test fun sharedWindowReservesCappedOutputWithoutDeductingItTwice() {
        val limits = AgentModelLimits(contextWindow = 32_000, maxOutputTokens = 2_000)
        val constrained = AgentModelBudgetPolicy.resolve(userInputLimit = 32_000, metadata = limits)
        assertEquals(30_000, constrained.inputLimit)
        assertEquals(2_000, constrained.outputReserve)
        assertEquals(8_000, AgentModelBudgetPolicy.resolve(userInputLimit = 8_000, metadata = limits).inputLimit)
    }

    @Test fun outputTargetIsIndependentOfInputAndDoesNotGrowToTheModelMaximum() {
        val budget = AgentModelBudgetPolicy.resolve(userInputLimit = 200_000,
            metadata = AgentModelLimits(maxOutputTokens = 64_000), outputTarget = 6_000)
        assertEquals(6_000, budget.outputReserve)
        assertEquals(200_000, budget.inputLimit)
        assertEquals(2_000, budget.summaryOutputLimit)
    }

    @Test fun summaryHasItsOwnBoundedTargetAndSharedWindowReservation() {
        val budget = AgentModelBudgetPolicy.resolve(userInputLimit = 100_000,
            metadata = AgentModelLimits(contextWindow = 32_000, maxOutputTokens = 12_000),
            outputTarget = 12_000, summaryOutputTarget = 20_000)
        assertEquals(20_000, budget.inputLimit)
        assertEquals(8_000, budget.summaryOutputLimit)
        assertEquals(24_000, budget.summaryInputLimit)
    }

    @Test fun smallestSharedWindowAndLargeIntegersNeverOverflow() {
        val tiny = AgentModelBudgetPolicy.resolve(metadata = AgentModelLimits(contextWindow = 2))
        assertEquals(1, tiny.inputLimit)
        assertEquals(1, tiny.outputReserve)
        assertEquals(1, tiny.summaryInputLimit)
        val large = AgentContextBudget(Int.MAX_VALUE, AgentModelLimits(contextWindow = Int.MAX_VALUE),
            outputTarget = Int.MAX_VALUE)
        assertEquals(1, large.inputLimit)
        assertEquals(Int.MAX_VALUE - 1, large.outputReserve)
    }

    @Test fun knownConstraintsHoldForBothMainAndSummaryAcrossBudgets() {
        listOf(2, 128, 8_000, 32_000, Int.MAX_VALUE).forEach { window ->
            listOf(1, 4_000, Int.MAX_VALUE).forEach { target ->
                val budget = AgentContextBudget(Int.MAX_VALUE,
                    AgentModelLimits(window, maxInputTokens = 6_000, maxOutputTokens = 3_000), target)
                assertTrue(budget.inputLimit.toLong() + budget.outputReserve <= window)
                assertTrue(budget.summaryInputLimit.toLong() + budget.summaryOutputLimit <= window)
                assertTrue(budget.inputLimit in 1..6_000)
                assertTrue(budget.outputReserve in 1..3_000)
            }
        }
    }

    @Test fun compactionHeadroomScalesDownAndRecentBatchHeadroomIsBounded() {
        val budget = AgentContextBudget(1_000)
        assertEquals(900, budget.compactionThreshold())
        assertEquals(850, budget.compactionThreshold(150))
        assertEquals(800, budget.compactionThreshold(Int.MAX_VALUE))
        assertEquals(1, AgentContextBudget(1).compactionThreshold(Int.MAX_VALUE))
        assertEquals(200_000 - 8_192, AgentContextBudget(200_000).compactionThreshold(Int.MAX_VALUE))
    }

    @Test fun compactionMarginCanBeConfiguredOrDisabled() {
        val budget = AgentContextBudget(1_000, compactionMargin = AgentCompactionMargin(0.05, 0.15, 120))
        assertEquals(950, budget.compactionThreshold())
        assertEquals(880, budget.compactionThreshold(500))
        assertEquals(1_000, budget.copy(compactionMargin = AgentCompactionMargin(0.0, 0.0)).compactionThreshold(500))
    }

    @Test fun invalidBudgetsAreRejectedInsteadOfBecomingNegativeRequests() {
        assertThrows(IllegalArgumentException::class.java) { AgentContextBudget(0) }
        assertThrows(IllegalArgumentException::class.java) { AgentModelLimits(contextWindow = 1) }
        assertThrows(IllegalArgumentException::class.java) { AgentModelLimits(maxOutputTokens = -1) }
        assertThrows(IllegalArgumentException::class.java) { AgentContextBudget(outputTarget = 0) }
        assertThrows(IllegalArgumentException::class.java) { AgentCompactionMargin(fraction = Double.NaN) }
    }
}

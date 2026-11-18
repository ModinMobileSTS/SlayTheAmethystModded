package io.stamethyst.backend.llm

import org.junit.Assert.*
import org.junit.Test

class AgentCacheProbeBoundsTest {
    @Test fun missingColdCacheFieldRemainsUnknownAndStillConstrainsTheWholeWorkload() {
        val bounds = AgentCacheProbeBounds.from(listOf(
            AgentCacheUsage(1000, null, null), AgentCacheUsage(1000, 900, null),
        ))
        assertEquals(0.45, bounds.lower!!, 0.000001)
        assertEquals(0.95, bounds.upper!!, 0.000001)
    }

    @Test fun fullyReportedReadsGiveOneTokenWeightedRateNotAnAverageOfPercentages() {
        val bounds = AgentCacheProbeBounds.from(listOf(
            AgentCacheUsage(100, 100, null), AgentCacheUsage(900, 0, null),
        ))
        assertEquals(0.1, bounds.lower!!, 0.000001)
        assertEquals(bounds.lower, bounds.upper)
    }

    @Test fun unknownInputOrZeroDenominatorCannotProduceAWorkloadRate() {
        listOf(emptyList(), listOf(AgentCacheUsage(0, 0, null)),
            listOf(AgentCacheUsage(null, null, null), AgentCacheUsage(100, 90, null))).forEach {
            assertEquals(AgentCacheProbeBounds(null, null), AgentCacheProbeBounds.from(it))
        }
    }

    @Test fun allUnreportedCacheReadsYieldFullUncertaintyNotAnInventedMissRate() {
        assertEquals(AgentCacheProbeBounds(0.0, 1.0),
            AgentCacheProbeBounds.from(listOf(AgentCacheUsage(1000, null, null))))
    }
}

package io.stamethyst.backend.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/** Accounting fixtures, not live model calls or estimates of a user's actual cache performance. */
class AgentCacheAccountingRegressionTest {
    @Test fun recordedTerraWorkflowHasHighLastRateButSeventyPercentReportedWeightedRate() {
        // Numeric usage only, from the documented complete-editor synthetic acceptance run.
        val rows = listOf(
            6878 to null, 6937 to null, 26508 to 6784, 26531 to 26368,
            26590 to 26368, 46195 to 26496, 7073 to 6656, 7096 to 6656,
        )
        val stats = rows.fold(AgentCacheStats()) { current, (input, cached) ->
            current.observe(parse("""{"prompt_tokens":$input,"completion_tokens":5,"total_tokens":${input + 5}${cached?.let {
                ""","prompt_tokens_details":{"cached_tokens":$it}"""
            }.orEmpty()}}"""))
        }
        assertEquals(8L, stats.requests)
        assertEquals(6L, stats.reportedRequests)
        assertEquals(99_328L, stats.readTokens)
        assertEquals(139_993L, stats.inputTokens)
        assertEquals(0.7095211903452315, stats.totalRate!!, 0.0000001)
        assertEquals(0.9379932356257046, stats.lastRate!!, 0.0000001)
        assertFalse("Standard OpenAI counters must not trigger additive gateway normalization", stats.inputExcludesCached)
    }

    @Test fun standardEightyPercentReadDoesNotAddCachedOrOutputTokensToTheDenominator() {
        val usage = parse("""{"prompt_tokens":20000,"completion_tokens":3000,"total_tokens":23000,
            "prompt_tokens_details":{"cached_tokens":16000}}""")
        val stats = AgentCacheStats().observe(usage)
        assertEquals(20_000L, stats.lastInputTokens)
        assertEquals(16_000L, stats.lastReadTokens)
        assertEquals(0.8, stats.lastRate!!, 0.0000001)
        assertEquals(0.8, stats.totalRate!!, 0.0000001)
        assertFalse(stats.inputExcludesCached)
    }

    @Test fun almostFullyCachedRecentRequestsCanLegitimatelyLeaveCumulativeRateAtSeventyFivePercent() {
        val stats = AgentCacheStats()
            .observe(parse("""{"prompt_tokens":20000,"prompt_tokens_details":{"cached_tokens":0}}"""))
            .observe(parse("""{"prompt_tokens":30000,"prompt_tokens_details":{"cached_tokens":29900}}"""))
            .observe(parse("""{"prompt_tokens":30000,"prompt_tokens_details":{"cached_tokens":29900}}"""))
        assertEquals(0.7475, stats.totalRate!!, 0.0000001)
        assertEquals(29_900.0 / 30_000, stats.lastRate!!, 0.0000001)
        assertEquals(3L, stats.reportedRequests)
    }

    @Test fun wireUsageWithoutCacheCountersCannotBorrowSyntheticSdkZeroOrAnotherReadValue() {
        val raw = Json.parseToJsonElement("""{"prompt_tokens":106891}""").jsonObject
        listOf(0L, 100_000L).forEach { sdkRead ->
            val usage = AgentCacheUsage.parse(raw, fallbackInput = 106_891, fallbackRead = sdkRead)
            assertNull(usage.read)
            assertNull(AgentCacheStats().observe(usage).lastRate)
        }
    }

    @Test fun sdkOnlyResponsesStillSupportExplicitZeroAndPositiveCacheCounters() {
        assertEquals(AgentCacheUsage(100, 0, null), AgentCacheUsage.parse(null, fallbackInput = 100, fallbackRead = 0))
        assertEquals(AgentCacheUsage(100, 80, null), AgentCacheUsage.parse(null, fallbackInput = 100, fallbackRead = 80))
    }

    private fun parse(value: String) = AgentCacheUsage.parse(Json.parseToJsonElement(value).jsonObject)
}

package io.stamethyst.ui.aimod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.system.measureNanoTime

/** Opt-in trend measurements, not wall-clock assertions on shared CI machines. */
class AiConversationLoadingCostTest {
    @Before
    fun onlyWhenRequested() {
        assumeTrue(System.getProperty("sts.runAiConversationLoadingCost") == "true")
    }

    @Test
    fun openingCostAsHistoryGrows() {
        println("LOAD_COST,axis,messages,chars_per_answer,parts_per_answer,visible_rows,install_median_ms,prepare_median_ms,prepare_p90_ms")
        for (count in listOf(10, 100, 1_000, 5_000)) measure("history", count, 4_096)
    }

    @Test
    fun openingCostAsIndividualMessagesGrow() {
        println("LOAD_COST,axis,messages,chars_per_answer,parts_per_answer,visible_rows,install_median_ms,prepare_median_ms,prepare_p90_ms")
        for (chars in listOf(1_024, 16_384, 65_536, 262_144)) measure("payload", 100, chars)
        // Historical stores may contain one part per streamed token, unlike coalesced new turns.
        for (parts in listOf(100, 1_000, 10_000, 50_000)) measure("legacy_parts", 100, parts * 8, parts)
    }

    private fun measure(axis: String, count: Int, chars: Int, partsCount: Int = 1) {
        val paragraph = "检查 **模组** 的 `patch` 行为。\n\n"
        val text = paragraph.repeat(chars / paragraph.length + 1).take(chars)
        val parts = if (partsCount == 1) listOf(AiMessagePart(AiMessagePartKind.TEXT, text)) else
            List(partsCount) { AiMessagePart(AiMessagePartKind.TEXT, "正文片段1234") }
        val source = List(count) { index ->
            if (index % 2 == 0) AiEditorMessage(index.toLong(), true, "请检查模组 $index") else
                AiEditorMessage(index.toLong(), false, text, parts = parts)
        }
        var rowCount = 0
        fun prepare() {
            val rows = source.takeLast(3).flatMap(::buildAiTranscriptRows)
            rowCount = rows.size
            assertTrue(rows.isNotEmpty())
            assertTrue(rows.all { it.original.id >= count - 3 })
        }
        repeat(3) { prepare() }
        val installed = mutableListOf<AiEditorMessage>()
        val install = List(9) {
            installed.clear()
            measureNanoTime { applyIncomingMessages(installed, source) }
        }.sorted()
        assertEquals(count, installed.size)
        val preparation = List(9) { measureNanoTime { prepare() } }.sorted()
        println(String.format(Locale.US, "LOAD_COST,%s,%d,%d,%d,%d,%.3f,%.3f,%.3f",
            axis, count, chars, partsCount, rowCount, install[4] / 1e6, preparation[4] / 1e6, preparation[8] / 1e6))
    }
}

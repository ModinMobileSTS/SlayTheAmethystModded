package io.stamethyst.ui.aimod

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite reads, intentionally separate from JVM preparation timings. Does not evict OS caches. */
@RunWith(AndroidJUnit4::class)
class AiConversationLoadingCostInstrumentedTest {
    @Test
    fun readCostAsHistoryAndPayloadGrow() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("aiLoadingBenchmark") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = StringBuilder("axis,messages,chars_per_answer,database_bytes,first_read_ms,read_median_ms,read_p90_ms,summary_median_ms,prepare_median_ms\n")
        val cases = listOf(10, 100, 1_000, 5_000).map { Triple("history", it, 4_096) } +
            listOf(1_024, 16_384, 65_536, 262_144).map { Triple("payload", 100, it) }
        for ((axis, count, chars) in cases) {
            val root = File(context.cacheDir, "ai-load-cost-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val paragraph = "检查 **模组** 的 `patch` 行为。\n\n"
                val text = paragraph.repeat(chars / paragraph.length + 1).take(chars)
                val session = AiEditorSession("benchmark", messages = List(count) { index ->
                    if (index % 2 == 0) AiEditorMessage(index.toLong(), true, "检查模组 $index") else
                        AiEditorMessage(index.toLong(), false, text,
                            parts = listOf(AiMessagePart(AiMessagePartKind.TEXT, text)))
                })
                AiConversationStore(root).mutate(session.id, create = session) { it }
                var loaded = session
                fun read(): Long = measureNanoTime {
                    loaded = requireNotNull(AiConversationStore(root).load(session.id))
                }
                val first = read()
                assertEquals(count, loaded.messages.size)
                assertEquals(text, loaded.messages.last().text)
                val reads = List(9) { read() }.sorted()
                val summaries = List(9) {
                    measureNanoTime { assertEquals(1, AiConversationStore(root).loadSummaries().size) }
                }.sorted()
                val preparation = List(9) {
                    measureNanoTime { loaded.messages.takeLast(3).flatMap(::buildAiTranscriptRows) }
                }.sorted()
                report.append(String.format(Locale.US, "%s,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f\n",
                    axis, count, chars, root.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    first / 1e6, reads[4] / 1e6, reads[8] / 1e6, summaries[4] / 1e6, preparation[4] / 1e6))
            } finally {
                root.deleteRecursively()
            }
        }
        val output = File(context.getExternalFilesDir(null), "ai-conversation-loading-cost.csv")
        output.writeText(report.toString())
        Log.i("AiLoadingCost", "report=$output\n$report")
    }
}

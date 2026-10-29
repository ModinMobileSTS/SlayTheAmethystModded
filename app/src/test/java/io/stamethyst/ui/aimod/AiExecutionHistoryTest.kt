package io.stamethyst.ui.aimod

import io.stamethyst.backend.llm.AgentExecutionStep
import io.stamethyst.backend.llm.AgentStepKind
import io.stamethyst.backend.llm.AgentStepStatus
import io.stamethyst.backend.llm.AgentToolExecutionEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.AbstractList

class AiExecutionHistoryTest {
    @Test fun stepsRoundTripAndOldMessagesRemainReadable() {
        val message = assistant(listOf(step("model", input = 125, output = 25)))
        assertEquals(message, Json.decodeFromString<AiEditorMessage>(Json.encodeToString(message)))
        val legacy = Json.decodeFromString<AiEditorMessage>("""{"id":1,"fromUser":false,"text":"old reply"}""")
        assertTrue(legacy.executionSteps.isEmpty())
    }

    @Test fun updatesKeepStepOrderAndNeverDoubleCountUsage() {
        val running = step("first").copy(status = AgentStepStatus.RUNNING, elapsedMs = null)
        val message = assistant(emptyList()).withExecutionStep(running).withExecutionStep(step("second", start = 300))
            .withExecutionStep(step("first", input = 80, output = 20))
        assertEquals(listOf("first", "second"), message.executionSteps.map { it.id })
        val history = AiHistoryBuilder().build(listOf(user(1), message))
        assertEquals(2, history.stepCount)
        assertEquals(100L, history.usage.tokens)
        assertTrue(history.usage.partial)
    }

    @Test fun groupsRetryAssistantsUnderTheirOriginalTurnAndNewInputStartsAnother() {
        val retry = AiEditorMessage(3, false, "retrying", notice = true)
        val history = AiHistoryBuilder().build(listOf(user(1), assistant(listOf(step("a"))), retry,
            assistant(listOf(step("b", kind = AgentStepKind.RETRY))).copy(id = 4), user(5),
            assistant(listOf(step("c"))).copy(id = 6)))
        assertEquals(listOf(1L, 5L), history.turns.map { it.id })
        assertEquals(listOf(1, 2), history.turns.map { it.number })
        assertEquals(2, history.turns.first().steps.size)
        assertEquals(1, history.turns.last().steps.size)
    }

    @Test fun parallelToolsContributeWallTimeNotSumOfDurations() {
        val history = AiHistoryBuilder().build(listOf(user(1), assistant(listOf(
            step("model", start = 1_000, elapsed = 2_000, input = 100, output = 40),
            step("tool-a", AgentStepKind.TOOL, start = 3_000, elapsed = 4_000, input = 0, output = 0),
            step("tool-b", AgentStepKind.TOOL, start = 3_000, elapsed = 4_000, input = 0, output = 0),
            step("final", start = 7_000, elapsed = 1_000, input = 150, output = 30),
        ))))
        assertEquals(7_000L, history.turns.single().elapsedMs)
        assertEquals(320L, history.usage.tokens)
        assertEquals(2, history.usage.requests)
        assertFalse(history.usage.partial)
    }

    @Test fun compactionCountsAndMissingUsageStaysUnknown() {
        val history = AiHistoryBuilder().build(listOf(user(1), assistant(listOf(
            step("compact", AgentStepKind.COMPACTION, input = 100, output = 50), step("unreported"),
        ))))
        assertEquals(150L, history.usage.tokens)
        assertEquals(1, history.usage.reported)
        assertEquals(2, history.usage.requests)
        assertTrue(history.usage.partial)
        assertNull(AiHistoryBuilder().build(listOf(user(1), assistant(listOf(step("unknown"))))).usage.tokens)
    }

    @Test fun legacyHistoryPreservesActionOrderWithoutInventedMeasurements() {
        val message = AiEditorMessage(2, false, "final", elapsedMs = 12_000, contextTokens = 999_999,
            tools = listOf(AiToolCall("read", "read_workspace_file", """{"path":"src/Card.java"}""", result = "read")),
            parts = listOf(AiMessagePart(AiMessagePartKind.TEXT, "Inspect the card"),
                AiMessagePart(AiMessagePartKind.TOOL, toolId = "read"), AiMessagePart(AiMessagePartKind.TEXT, "All done")))
        val turn = AiHistoryBuilder().build(listOf(user(1), message)).turns.single()
        assertEquals(listOf(AgentStepKind.MODEL, AgentStepKind.TOOL, AgentStepKind.MODEL), turn.steps.map { it.kind })
        assertEquals("src/Card.java", turn.steps[1].detail)
        assertEquals(12_000L, turn.elapsedMs)
        assertTrue(turn.steps.all { it.elapsedMs == null && it.legacy })
        assertNull(turn.usage.tokens)
    }

    @Test fun orphanedRunningStepsAreNotPresentedAsStillRunning() {
        val message = assistant(listOf(step("unfinished").copy(elapsedMs = null, status = AgentStepStatus.RUNNING))).copy(failed = true)
        val turn = AiHistoryBuilder().build(listOf(user(1), message)).turns.single()
        assertEquals(AgentStepStatus.CANCELLED, turn.steps.single().status)
        assertNull(turn.steps.single().elapsedMs)
        assertTrue(turn.failed)
    }

    @Test fun toolEventsCaptureDurationWithoutAddingTokens() {
        val event = AgentToolExecutionEvent("read", "read_workspace_file", """{"path":"a.txt"}""", startedAtMs = 123)
        val started = applyToolExecutionEvent(assistant(emptyList()), event)
        val completed = applyToolExecutionEvent(started, event.copy(result = "hello", elapsedMs = 42))
        assertEquals(1, completed.executionSteps.size)
        val measured = completed.executionSteps.single()
        assertEquals(AgentStepStatus.COMPLETED, measured.status)
        assertEquals(42L, measured.elapsedMs)
        assertEquals(0L, measured.totalTokens)
        assertEquals("a.txt", measured.detail)
    }

    @Test fun unchangedMessagesNeverReparseTheirSteps() {
        val counted = CountingSteps(List(2_000) { step("s$it") })
        val messages = listOf(user(1), assistant(counted))
        val builder = AiHistoryBuilder()
        val before = builder.build(messages)
        counted.reads = 0
        val after = builder.build(messages)
        assertEquals(0, counted.reads)
        assertSame(before.turns.single().steps[0], after.turns.single().steps[0])
    }

    @Test fun largeHistoryKeepsAllStepsButBoundsChartDrawCommands() {
        val steps = List(20_000) { step("s$it", kind = AgentStepKind.entries[it % 4], input = 1, output = 1) }
        val history = AiHistoryBuilder().build(listOf(user(1), assistant(steps)))
        assertEquals(20_000, history.stepCount)
        assertTrue(history.turns.single().spectrum.size <= 64)
        val turns = (0 until 150).flatMap { listOf(user(it * 2L + 1), assistant(listOf(step("s$it"))).copy(id = it * 2L + 2)) }
        assertTrue(AiHistoryBuilder().build(turns).overview.size <= 40)
    }

    @Test fun largeToolArgumentsAreNotDecodedForOverview() {
        assertEquals("", historyToolDetail("{\"content\":\"" + "x".repeat(100_000) + "\"}"))
    }

    @Test fun durationFormattingHandlesUnknownZeroAndLongRuns() {
        assertEquals("—", formatAiHistoryDuration(null))
        assertEquals("0 ms", formatAiHistoryDuration(0))
        assertEquals("1.5 s", formatAiHistoryDuration(1_500))
        assertEquals("1m 5s", formatAiHistoryDuration(65_000))
        assertEquals("1h 2m", formatAiHistoryDuration(3_720_000))
    }

    private fun user(id: Long) = AiEditorMessage(id, true, "Inspect turn $id")
    private fun assistant(steps: List<AgentExecutionStep>) = AiEditorMessage(2, false, "done", executionSteps = steps)
    private fun step(id: String, kind: AgentStepKind = AgentStepKind.MODEL, start: Long = 100, elapsed: Long = 100,
        input: Long? = null, output: Long? = null) = AgentExecutionStep(id, kind, "test", startedAtMs = start,
        elapsedMs = elapsed, status = AgentStepStatus.COMPLETED, inputTokens = input, outputTokens = output)

    private class CountingSteps(private val backing: List<AgentExecutionStep>) : AbstractList<AgentExecutionStep>() {
        var reads = 0
        override val size get() = backing.size
        override fun get(index: Int): AgentExecutionStep = backing[index].also { reads++ }
    }
}

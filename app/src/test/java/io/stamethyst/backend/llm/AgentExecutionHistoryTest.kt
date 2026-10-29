package io.stamethyst.backend.llm

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class AgentExecutionHistoryTest {
    private lateinit var server: MockWebServer
    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun close() { server.close() }

    @Test fun nonStreamingRequestsReportMeasuredTimeAndActualUsage() {
        server.enqueue(response("done"))
        val events = ArrayList<AgentExecutionStep>()
        gateway { events += it }.respond("system", "prompt")
        assertEquals(2, events.size)
        val (start, end) = events
        assertEquals(start.id, end.id)
        assertEquals(AgentStepStatus.RUNNING, start.status)
        assertEquals(AgentStepStatus.COMPLETED, end.status)
        assertEquals("done", end.detail)
        assertEquals(120L, end.inputTokens)
        assertEquals(30L, end.outputTokens)
        assertNotNull(end.elapsedMs)
        assertTrue(end.elapsedMs!! >= 0)
    }

    @Test fun streamingRequestsRecordUsageAndNotPrivateReasoning() {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"private reasoning\",\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":30,\"total_tokens\":150}}\n\n" +
                "data: [DONE]\n\n",
        ).build())
        val events = ArrayList<AgentExecutionStep>()
        gateway { events += it }.respondStreaming("system", "prompt", AgentChatModelFactory.createStreaming(config())!!, {})
        assertEquals(150L, events.last().totalTokens)
        assertEquals("done", events.last().detail)
        assertFalse(events.any { it.detail.contains("private reasoning") })
    }

    @Test fun emptyResponsesStillRecordEveryBillableAttempt() {
        server.enqueue(response("")); server.enqueue(response("done"))
        val events = ArrayList<AgentExecutionStep>()
        gateway { events += it }.respond("system", "prompt")
        assertEquals(2, events.count { it.status == AgentStepStatus.COMPLETED })
        assertEquals(300L, events.filter { it.status == AgentStepStatus.COMPLETED }.sumOf { it.totalTokens!! })
    }

    @Test fun retryHasItsOwnMeasuredZeroTokenStep() {
        server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
        server.enqueue(response("done"))
        val events = ArrayList<AgentExecutionStep>()
        gateway { events += it }.respond("system", "prompt")
        assertEquals(listOf(AgentStepKind.MODEL, AgentStepKind.RETRY, AgentStepKind.MODEL),
            events.filter { it.status != AgentStepStatus.RUNNING }.map { it.kind })
        val wait = events.last { it.kind == AgentStepKind.RETRY }
        assertEquals(0L, wait.totalTokens)
        assertTrue(wait.elapsedMs!! >= 900)
        assertEquals(AgentStepStatus.FAILED, events[1].status)
    }

    @Test fun successfulBillableRequestIsNeverRetriedBecauseHistoryWriteFailed() {
        server.enqueue(response("done"))
        val failure = runCatching { gateway {
            if (it.status == AgentStepStatus.COMPLETED) throw IOException("database checkpoint failed")
        }.respond("system", "prompt") }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(1, server.requestCount)
    }

    @Test fun timerMarksNonModelActionsAsZeroAndSanitizesUsage() {
        val timer = AgentStepTimer(AgentStepKind.TOOL, detail = "x".repeat(2_000))
        assertEquals(480, timer.step.detail.length)
        assertEquals(0L, timer.step.totalTokens)
        assertNull(timer.finish(inputTokens = -1).inputTokens)
        assertEquals(AgentStepStatus.CANCELLED, agentStepFailureStatus(CancellationException()))
    }

    private fun gateway(onStep: (AgentExecutionStep) -> Unit) = PolicyGatedAgentGateway(
        chatModel = AgentChatModelFactory.create(config()), toolRegistry = AgentToolRegistry(emptyList()), onExecutionStep = onStep)
    private fun config() = OpenAiCompatibleModelConfig(server.url("/v1/").toString(), "test-key", "test-model")
    private fun response(text: String) = MockResponse.Builder().body("""
        {"id":"history","object":"chat.completion","created":0,"model":"test-model",
         "choices":[{"index":0,"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}],
         "usage":{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150}}
    """.trimIndent()).build()
}

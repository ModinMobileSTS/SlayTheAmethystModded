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
        assertEquals(listOf(AgentStepKind.MODEL, AgentStepKind.ERROR, AgentStepKind.RETRY, AgentStepKind.MODEL),
            events.filter { it.status != AgentStepStatus.RUNNING }.map { it.kind })
        val wait = events.last { it.kind == AgentStepKind.RETRY }
        assertEquals(0L, wait.totalTokens)
        assertTrue(wait.elapsedMs!! >= 900)
        assertEquals(AgentStepStatus.FAILED, events[1].status)
        val error = events.single { it.kind == AgentStepKind.ERROR }
        assertEquals(events[1].id, error.relatedStepId)
        assertTrue(error.detail, error.detail.contains("503"))
        assertEquals(0L, error.totalTokens)
    }

    @Test fun emptyResponseHasAnErrorEvenWhenTheNextAttemptRecovers() {
        server.enqueue(response("")); server.enqueue(response("done"))
        val events = ArrayList<AgentExecutionStep>()
        gateway { events += it }.respond("system", "prompt")
        val error = events.single { it.kind == AgentStepKind.ERROR }
        assertTrue(error.detail.contains("AgentEmptyResponseException"))
        assertEquals(events.first().id, error.relatedStepId)
        assertEquals(0L, error.totalTokens)
        assertEquals(AgentStepStatus.COMPLETED, events.last().status)
    }

    @Test fun terminalProviderFailureIsAnIndependentErrorEvent() {
        server.enqueue(MockResponse.Builder().code(401).body("invalid credential").build())
        val events = ArrayList<AgentExecutionStep>()
        assertNotNull(runCatching { gateway { events += it }.respond("system", "prompt") }.exceptionOrNull())
        assertEquals(1, server.requestCount)
        assertEquals(listOf(AgentStepKind.MODEL, AgentStepKind.MODEL, AgentStepKind.ERROR), events.map { it.kind })
        assertTrue(events.last().detail, events.last().detail.contains("401"))
        assertEquals(AgentStepStatus.FAILED, events.last().status)
    }

    @Test fun requestCancellationIsRecordedAndDoesNotRetry() {
        val events = ArrayList<AgentExecutionStep>()
        val gateway = PolicyGatedAgentGateway(chatModel = object : dev.langchain4j.model.chat.ChatModel {
            override fun doChat(request: dev.langchain4j.model.chat.request.ChatRequest): dev.langchain4j.model.chat.response.ChatResponse =
                throw CancellationException("request stopped")
        }, toolRegistry = AgentToolRegistry(emptyList()), onExecutionStep = { events += it })
        assertThrows(CancellationException::class.java) { gateway.respond("system", "prompt") }
        assertEquals(AgentStepStatus.CANCELLED, events[1].status)
        assertEquals(AgentStepKind.ERROR, events.last().kind)
        assertEquals(AgentStepStatus.CANCELLED, events.last().status)
        assertTrue(events.last().detail.contains("request stopped"))
    }

    @Test fun interruptedRetryWaitHasItsOwnProblemEvent() {
        server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
        val events = ArrayList<AgentExecutionStep>()
        var waiting = false
        val gateway = PolicyGatedAgentGateway(
            chatModel = AgentChatModelFactory.create(config()), toolRegistry = AgentToolRegistry(emptyList()),
            checkCancelled = { if (waiting) throw CancellationException("retry stopped") },
            onExecutionStep = { events += it; if (it.kind == AgentStepKind.RETRY) waiting = true },
        )
        assertThrows(CancellationException::class.java) { gateway.respond("system", "prompt") }
        val wait = events.last { it.kind == AgentStepKind.RETRY }
        val error = events.last()
        assertEquals(AgentStepStatus.CANCELLED, wait.status)
        assertEquals("RETRY", error.name)
        assertEquals(wait.id, error.relatedStepId)
        assertEquals(AgentStepStatus.CANCELLED, error.status)
    }

    @Test fun providerTruncationIsVisibleWithoutLosingReportedUsage() {
        server.enqueue(MockResponse.Builder().body("""
            {"id":"history","object":"chat.completion","created":0,"model":"test-model",
             "choices":[{"index":0,"message":{"role":"assistant","content":"partial answer"},"finish_reason":"length"}],
             "usage":{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150}}
        """.trimIndent()).build())
        val events = ArrayList<AgentExecutionStep>()
        assertEquals("partial answer", gateway { events += it }.respond("system", "prompt").text)
        assertEquals(150L, events.last { it.kind == AgentStepKind.MODEL }.totalTokens)
        assertEquals(AgentStepKind.ERROR, events.last().kind)
        assertTrue(events.last().detail.contains("LENGTH"))
    }

    @Test fun errorDescriptionsAreBoundedAndRedactCommonCredentials() {
        val error = agentErrorStep("MODEL", "Authorization: Bearer secret-value; {\"api_key\":\"another-secret\"}; sk-1234567890abcd")
        assertFalse(error.detail.contains("secret-value"))
        assertFalse(error.detail.contains("another-secret"))
        assertFalse(error.detail.contains("sk-1234567890abcd"))
        assertEquals(480, agentErrorDetail(IOException("x".repeat(2_000))).length)
        assertEquals(0L, error.elapsedMs)
        assertEquals(0L, error.totalTokens)
    }

    @Test fun toolExceptionsRetainASafeDescriptionForTheErrorTimeline() {
        val events = ArrayList<AgentToolExecutionEvent>()
        val registry = AgentToolRegistry(listOf(object : AgentTool {
            override val specification = dev.langchain4j.agent.tool.ToolSpecification.builder().name("read").build()
            override val safety = AgentToolSafety.READ_ONLY
            override fun execute(arguments: String): String = throw IOException("disk read failed")
        }), onToolExecution = { events += it })
        registry.execute(dev.langchain4j.agent.tool.ToolExecutionRequest.builder().name("read").arguments("{}").build())
        assertTrue(events.last().failed)
        assertTrue(events.last().errorDetail.contains("IOException: disk read failed"))
        assertEquals("{\"error\":\"tool_execution_failed\"}", events.last().result)
    }

    @Test fun successfulBillableRequestIsNeverRetriedBecauseHistoryWriteFailed() {
        server.enqueue(response("done"))
        val failure = runCatching { gateway {
            if (it.status == AgentStepStatus.COMPLETED) throw IOException("database checkpoint failed")
        }.respond("system", "prompt") }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(1, server.requestCount)
    }

    @Test fun failedRequestIsNotRetriedWhenItsHistoryCheckpointFails() {
        server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
        val failure = runCatching { gateway {
            if (it.kind == AgentStepKind.ERROR) throw IOException("database checkpoint failed")
        }.respond("system", "prompt") }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("checkpoint failed"))
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

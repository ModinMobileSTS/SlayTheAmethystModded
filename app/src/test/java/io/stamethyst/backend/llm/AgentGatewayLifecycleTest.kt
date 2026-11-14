package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.PartialThinking
import dev.langchain4j.model.chat.response.PartialToolCall
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.output.FinishReason
import dev.langchain4j.model.output.TokenUsage
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.Assert.*
import org.junit.Test

class AgentGatewayLifecycleTest {
    @Test fun streamingRecoveryIsRememberedAcrossToolRoundsWithOrderedIdsAndThinking() {
        var streams = 0
        var syncs = 0
        val requests = mutableListOf<ChatRequest>()
        val model = model {
            requests += it
            syncs++
            if (syncs <= 2) calls(syncs) else answer("done")
        }
        val stream = stream { streams++; it.onCompleteResponse(empty()) }
        val manager = manager(model)
        val steps = mutableListOf<AgentExecutionStep>()
        val result = PolicyGatedAgentGateway(model, registry(), contextManager = manager, onExecutionStep = { steps += it })
            .respondStreaming("system", "go", stream, {})
        assertEquals("done", result.text)
        assertEquals(2, streams)
        assertEquals(3, syncs) // Former behavior would issue another four empty streams.
        assertEquals(5, steps.count { it.kind == AgentStepKind.MODEL && it.status == AgentStepStatus.COMPLETED })
        assertEquals(listOf("1-a", "1-b"), requests[1].messages().filterIsInstance<ToolExecutionResultMessage>().map { it.id() })
        assertEquals("reason once", requests[1].messages().filterIsInstance<AiMessage>().single { it.hasToolExecutionRequests() }.thinking())
        assertEquals(listOf("1-a", "1-b", "2-a", "2-b"), manager.state.messages.filter { it.role == "tool" }.map { it.callId })
    }

    @Test fun changingConfigurationReprobesStreamingButNewInputAloneKeepsRecovery() {
        var streams = 0
        var syncs = 0
        var boundary = 0
        var config = "model-a"
        val model = model { syncs++; if (syncs < 3) calls(syncs) else answer("done") }
        val streaming = stream { streams++; it.onCompleteResponse(if (config == "model-b") answer("new model") else empty()) }
        val result = PolicyGatedAgentGateway(model, registry(), currentModelConfiguration = { config }, receiveInbox = { idle ->
            if (idle) false else { boundary++; if (boundary == 3) config = "model-b"; boundary > 1 }
        }).respondStreaming("system", "go", streaming, {})
        assertEquals("new model", result.text)
        assertEquals(3, streams)
        assertEquals(2, syncs)
    }

    @Test fun healthyStreamingNeverUsesFallback() {
        var streams = 0
        val result = PolicyGatedAgentGateway(model { fail("fallback"); answer("") }, registry()).respondStreaming(
            "system", "go", stream { streams++; it.onCompleteResponse(if (streams == 1) calls(1) else answer("done")) }, {},
        )
        assertEquals("done", result.text)
        assertEquals(2, streams)
    }

    @Test fun explicitEmptyTruncationAndFilteringNeverRepeatOrFallback() {
        listOf(FinishReason.LENGTH, FinishReason.CONTENT_FILTER).forEach { reason ->
            var attempts = 0
            val gateway = PolicyGatedAgentGateway(model { fail("fallback"); answer("") }, registry())
            val error = assertThrows(AgentResponseTerminatedException::class.java) {
                gateway.respondStreaming("system", "go", stream {
                    attempts++; it.onCompleteResponse(empty(reason))
                }, {})
            }
            assertTrue(error.message!!.contains(reason.name))
            assertEquals(1, attempts)
        }
    }

    @Test fun transportFailureDoesNotPoisonStreamingCompatibility() {
        val time = FakeTime()
        var attempts = 0
        val streaming = stream {
            attempts++
            if (attempts == 1) it.onError(IOException("transient"))
            else it.onCompleteResponse(if (attempts == 2) calls(1) else answer("done"))
        }
        val reply = PolicyGatedAgentGateway(model { fail("fallback"); answer("") }, registry(),
            requestControl = time.control(), requestClock = time, sleep = time::advance,
        ).respondStreaming("system", "go", streaming, {})
        assertEquals("done", reply.text)
        assertEquals(3, attempts)
    }

    @Test fun textThinkingAndToolArgumentProgressKeepStreamAlive() {
        val time = FakeTime()
        val streaming = stream { handler ->
            time.advance(110_000)
            handler.onPartialThinking(PartialThinking("thinking"))
            time.advance(55_000)
            handler.onPartialToolCall(PartialToolCall.builder().index(0).id("call").name("read").partialArguments("{").build())
            time.advance(55_000)
            handler.onPartialResponse("done")
            time.advance(55_000)
            handler.onCompleteResponse(answer("done"))
        }
        assertEquals("done", gateway(time).respondStreaming("system", "go", streaming, {}).text)
        assertEquals(0, time.pending)
    }

    @Test fun firstEventIdleAndTotalEnforcedAtGatewayWithoutRealSleeps() {
        listOf(AgentRequestTimeoutKind.FIRST_EVENT, AgentRequestTimeoutKind.STREAM_IDLE, AgentRequestTimeoutKind.TOTAL).forEach { kind ->
            val time = FakeTime()
            val gateway = gateway(time, onRetry = { error -> throw error })
            val failure = assertThrows(AgentRequestTimeoutException::class.java) {
                gateway.respondStreaming("system", "go", stream { handler ->
                    when (kind) {
                        AgentRequestTimeoutKind.FIRST_EVENT -> {
                            handler.onPartialResponse("") // An empty callback/keepalive is not progress.
                            time.advance(120_000)
                        }
                        AgentRequestTimeoutKind.STREAM_IDLE -> { handler.onPartialResponse("x"); time.advance(60_000) }
                        else -> repeat(6) { time.advance(50_000); handler.onPartialResponse("x") }
                    }
                }, {})
            }
            assertEquals(if (kind == AgentRequestTimeoutKind.TOTAL) AgentRequestTimeoutKind.ROUND_BUDGET else kind, failure.kind)
            assertEquals(0, time.pending)
        }
    }

    @Test fun fallbackUsesOnlyRemainingRoundBudget() {
        val time = FakeTime()
        var streams = 0
        var syncs = 0
        val gateway = PolicyGatedAgentGateway(model { syncs++; time.advance(41); answer("too late") }, registry(),
            streamingTimeoutMillis = 200, requestControl = time.control(), requestClock = time, sleep = time::advance)
        val error = assertThrows(AgentRequestTimeoutException::class.java) {
            gateway.respondStreaming("system", "go", stream { streams++; time.advance(80); it.onCompleteResponse(empty()) }, {})
        }
        assertEquals(AgentRequestTimeoutKind.ROUND_BUDGET, error.kind)
        assertEquals(2, streams)
        assertEquals(1, syncs)
        assertFalse(isRetryableAgentRequestFailure(error))
    }

    @Test fun networkRecoveryAndBackoffSpendSameRoundBudget() {
        val time = FakeTime()
        var attempts = 0
        val gateway = PolicyGatedAgentGateway(model { attempts++; time.advance(1_000); throw java.net.SocketException("Connection reset") }, registry(),
            streamingTimeoutMillis = 6_500, requestControl = time.control(), requestClock = time,
            sleep = time::advance, hasUsableNetwork = { false })
        val failure = assertThrows(AgentRequestTimeoutException::class.java) { gateway.respond("system", "go") }
        assertEquals(AgentRequestTimeoutKind.ROUND_BUDGET, failure.kind)
        assertEquals(6_500L, time.now)
        assertEquals(1, attempts)
    }

    @Test fun repeatedFailuresStopAfterFullDurableBatchesAndNeverReplayTools() {
        var attempts = 0
        var executions = 0
        val model = model { attempts++; calls(attempts, sameArguments = true) }
        val registry = registry { executions++; "{\"error\":\"missing_file\",\"hint\":\"list files\"}" }
        val manager = manager(model)
        val failure = assertThrows(AgentExecutionLimitException::class.java) {
            PolicyGatedAgentGateway(model, registry, contextManager = manager).respond("system", "go")
        }
        assertTrue(failure.message!!.contains("3 identical"))
        assertEquals(3, attempts)
        assertEquals(6, executions)
        assertEquals(6, manager.state.messages.count { it.role == "tool" })
        assertEquals(listOf("1-a", "1-b", "2-a", "2-b", "3-a", "3-b"), manager.state.messages.filter { it.role == "tool" }.map { it.callId })
    }

    @Test fun unchangedReadBatchesStopButChangingArgumentsAndSuccessfulWritesContinue() {
        var reads = 0
        val readModel = model { reads++; calls(reads, sameArguments = true) }
        assertThrows(AgentExecutionLimitException::class.java) { PolicyGatedAgentGateway(readModel, registry()).respond("s", "u") }
        assertEquals(3, reads)
        listOf(false, true).forEach { write ->
            var rounds = 0
            val model = model { rounds++; if (rounds == 12) answer("done") else calls(rounds, sameArguments = write) }
            val registry = registry(safety = if (write) AgentToolSafety.WORKSPACE_WRITE else AgentToolSafety.READ_ONLY)
            assertEquals(11, PolicyGatedAgentGateway(model, registry).respond("s", "u").toolRounds)
        }
    }

    @Test fun newInputResetsPendingNoProgressStop() {
        var rounds = 0
        var boundary = 0
        val model = model { rounds++; if (rounds == 6) answer("done") else calls(rounds, sameArguments = true) }
        val gateway = PolicyGatedAgentGateway(model, registry(), receiveInbox = { idle -> !idle && ++boundary == 4 })
        assertEquals("done", gateway.respond("system", "go").text)
        assertEquals(6, rounds)
    }

    @Test fun executionTokenCeilingStopsOnlyAfterSavingCompletedToolResults() {
        val time = FakeTime()
        val control = time.control(AgentExecutionPolicy(maxDurationMillis = null, maxTokens = 10))
        val model = model { calls(1) }
        val manager = manager(model)
        assertThrows(AgentExecutionLimitException::class.java) {
            PolicyGatedAgentGateway(model, registry(), contextManager = manager, requestControl = control, requestClock = time).respond("s", "u")
        }
        assertEquals(2, manager.state.messages.count { it.role == "tool" })
    }

    @Test fun toolExceptionReasonIsBoundedRedactedAndActionable() {
        val output = registry { throw IOException("Authorization: Bearer secret-value; " + "x".repeat(2_000)) }
            .execute(call("x", "{}"))
        assertTrue(output.text.contains("IOException"))
        assertTrue(output.text.contains("hint"))
        assertFalse(output.text.contains("secret-value"))
        assertTrue(output.text.length < 900)
    }

    @Test fun summaryCorrectionAndRetriesShareBudgetAndRetainOriginalRecords() {
        listOf(false, true).forEach { transportFailure ->
            val time = FakeTime()
            var attempts = 0
            val model = model {
                assertNotNull("SDK thread must be bound", AgentRequestScope.current())
                attempts++
                time.advance(if (transportFailure) 250 else 60)
                if (transportFailure) throw IOException("temporary") else answer(if (attempts == 1) "bad template" else "## Objective\nContinue")
            }
            val manager = AgentContextManager(
                AgentContextState(messages = listOf(AgentContextMessage(1, "user", "go"), AgentContextMessage(2, "assistant", "work"))),
                "system", "", AgentContextBudget(), 3, model, {}, requestControl = time.control(), requestClock = time,
                summaryTimeoutMillis = if (transportFailure) 500 else 100,
            )
            val before = manager.state
            assertThrows(AgentCompactionException::class.java) { manager.prepare(manual = true) }
            assertEquals(if (transportFailure) 1 else 2, attempts)
            assertEquals(before, manager.state)
            assertEquals(0, time.pending)
        }
    }

    @Test fun summaryStreamingProgressAndExecutionDurationAreBounded() {
        val time = FakeTime()
        val control = time.control(AgentExecutionPolicy(maxDurationMillis = 300_000))
        val model = model { fail("sync summary not expected"); answer("") }
        val streaming = stream {
            assertNotNull(AgentRequestScope.current())
            time.advance(110_000)
            it.onPartialThinking(PartialThinking("reason"))
            time.advance(55_000)
            it.onPartialToolCall(PartialToolCall.builder().index(0).id("call").name("read").partialArguments("{").build())
            time.advance(55_000)
            it.onPartialResponse("## Objective\nContinue")
            it.onCompleteResponse(answer("## Objective\nContinue"))
        }
        val manager = AgentContextManager(
            AgentContextState(messages = listOf(AgentContextMessage(1, "user", "go"), AgentContextMessage(2, "assistant", "work"))),
            "system", "", AgentContextBudget(), 3, model, {}, streamingModel = streaming,
            requestControl = control, requestClock = time, summaryTimeoutMillis = 300_000,
        )
        manager.prepare(manual = true)
        assertEquals("## Objective\nContinue", manager.state.summary)
        time.advance(80_000)
        assertThrows(AgentExecutionLimitException::class.java) { control.checkActive() }
        assertEquals(0, time.pending)
    }

    @Test(timeout = 12_000) fun cancellationReachesBlockingSyncStreamingAndSummaryHttpAndIsolatesExecutions() {
        listOf("sync", "stream", "summary-sync", "summary-stream").forEach { mode ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
                val config = OpenAiCompatibleModelConfig(server.url("/v1/").toString(), "test", "test")
                val model = AgentChatModelFactory.create(config)
                val stream = AgentChatModelFactory.createStreaming(config)!!
                AgentRequestControl().use { control ->
                    AgentRequestControl().use { other ->
                        val untouched = other.newRequest(AgentRequestTimeoutPolicy(5_000))
                        val manager = manager(model, control, if (mode == "summary-stream") stream else null)
                        val gateway = PolicyGatedAgentGateway(model, registry(), requestControl = control)
                        val result = CompletableFuture.supplyAsync {
                            runCatching {
                                when (mode) {
                                    "sync" -> gateway.respond("system", "go")
                                    "stream" -> gateway.respondStreaming("system", "go", stream, {})
                                    else -> manager.prepare(manual = true)
                                }
                            }.exceptionOrNull()
                        }
                        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                        control.cancel()
                        assertTrue("$mode cancellation", result.get(2, TimeUnit.SECONDS) is CancellationException)
                        assertNull(untouched.stopReason)
                        assertEquals(1, server.requestCount)
                        assertEquals("", manager.state.summary)
                        untouched.close()
                    }
                }
            }
        }
    }

    private fun gateway(time: FakeTime, onRetry: (Throwable) -> Unit = {}) = PolicyGatedAgentGateway(
        model { fail("unexpected sync"); answer("") }, registry(), requestControl = time.control(), requestClock = time,
        sleep = time::advance, onRetry = { _, _, error -> onRetry(error) },
    )
    private fun manager(model: ChatModel, control: AgentRequestControl? = null, stream: StreamingChatModel? = null) = AgentContextManager(
        AgentContextState(messages = listOf(AgentContextMessage(1, "user", "go"), AgentContextMessage(2, "assistant", "work"))),
        "system", "", AgentContextBudget(), 3, model, {}, requestControl = control, streamingModel = stream,
    )
    private fun registry(safety: AgentToolSafety = AgentToolSafety.READ_ONLY, result: () -> String = { "same result" }) = AgentToolRegistry(
        listOf(object : AgentTool {
            override val specification = ToolSpecification.builder().name("read").build()
            override val safety = safety
            override fun execute(arguments: String) = result()
        }), AgentToolPolicyGate(setOf(safety)),
    )
    private fun call(id: String, args: String) = ToolExecutionRequest.builder().id(id).name("read").arguments(args).build()
    private fun calls(round: Int, sameArguments: Boolean = false) = ChatResponse.builder().aiMessage(AiMessage.builder()
        .thinking("reason once").toolExecutionRequests(listOf(
            call("$round-a", if (sameArguments) (if (round % 2 == 0) "{\"a\":1,\"b\":2}" else "{\"b\":2,\"a\":1}") else "{\"page\":$round}"),
            call("$round-b", if (sameArguments) (if (round % 2 == 0) "{\"b\":2,\"a\":1}" else "{\"a\":1,\"b\":2}") else "{\"page\":$round}"),
        )).build()).tokenUsage(TokenUsage(10, 5)).build()
    private fun answer(text: String) = ChatResponse.builder().aiMessage(AiMessage.from(text)).build()
    private fun empty(reason: FinishReason = FinishReason.STOP) = ChatResponse.builder().aiMessage(AiMessage.builder().text("").build()).finishReason(reason).build()
    private fun model(run: (ChatRequest) -> ChatResponse) = object : ChatModel { override fun doChat(request: ChatRequest) = run(request) }
    private fun stream(run: (StreamingChatResponseHandler) -> Unit) = object : StreamingChatModel {
        override fun doChat(request: ChatRequest, handler: StreamingChatResponseHandler) = run(handler)
    }

    internal class FakeTime : AgentRequestClock, AgentRequestScheduler {
        var now = 0L
        private data class Scheduled(val due: Long, val action: () -> Unit)
        private val tasks = mutableListOf<Scheduled>()
        val pending get() = tasks.size
        override fun nowMillis() = now
        override fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable {
            val task = Scheduled(now + delayMillis, action)
            tasks += task
            return AutoCloseable { tasks.remove(task) }
        }
        fun advance(millis: Long) {
            now += millis
            while (true) {
                val task = tasks.firstOrNull { it.due <= now } ?: return
                tasks.remove(task); task.action()
            }
        }
        fun control(policy: AgentExecutionPolicy = AgentExecutionPolicy()) = AgentRequestControl(this, this, policy)
    }
}

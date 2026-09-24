package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.output.FinishReason
import dev.langchain4j.model.output.TokenUsage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AgentConversationContextTest {
    @Test fun defaultsReserveReplyAndSafetySpace() {
        val budget = AgentContextBudget()
        assertEquals(200_000, budget.limit)
        assertEquals(16_000, budget.outputReserve)
        assertEquals(174_000, budget.inputLimit)
    }

    @Test fun persistedProtocolRetainsRolesCallIdsAndThinking() {
        val initial = AgentContextState(messages = listOf(
            AgentContextMessage(1, "user", "Inspect"),
            AgentContextMessage(2, "assistant", calls = listOf(AgentContextToolCall("a", "read", "{}")), thinking = "reasoning"),
            AgentContextMessage(2, "tool", "exact result", callId = "a", toolName = "read"),
        ))
        val restored = Json.decodeFromString<AgentContextState>(Json.encodeToString(initial))
        val messages = manager(restored).prepare()
        assertTrue(messages[1] is UserMessage)
        assertEquals("a", (messages[2] as AiMessage).toolExecutionRequests().single().id())
        assertEquals("reasoning", (messages[2] as AiMessage).thinking())
        assertEquals("exact result", (messages[3] as ToolExecutionResultMessage).text())
    }

    @Test fun compactionChunksOversizedHistoryAndPreservesRecentToolGroup() {
        val recent = listOf(
            AgentContextMessage(3, "user", "Keep this exact request"),
            AgentContextMessage(4, "assistant", calls = listOf(AgentContextToolCall("recent", "read", "{}"))),
            AgentContextMessage(4, "tool", "verified signature", callId = "recent", toolName = "read"),
        )
        val original = longHistory().copy(messages = longHistory().messages + recent)
        val requests = ArrayList<ChatRequest>()
        val model = fake { request ->
            requests += request
            assertTrue(request.toolSpecifications().isEmpty())
            assertTrue(request.messages().joinToString().length < 15_000)
            response("Verified memory: patch-alpha, src/Test.java; compilation pending.")
        }
        val checkpoints = ArrayList<AgentContextState>()
        val manager = manager(original, model = model, limit = 8000, checkpoint = checkpoints::add)
        val active = manager.prepare()
        assertTrue(requests.size > 1)
        assertEquals(original.messages, manager.state.messages)
        assertEquals(2, manager.state.summarizedCount)
        assertEquals(1, manager.state.compactionCount)
        assertEquals("Keep this exact request", (active[1] as UserMessage).singleText())
        assertEquals("recent", (active[2] as AiMessage).toolExecutionRequests().single().id())
        assertEquals("recent", (active[3] as ToolExecutionResultMessage).id())
        assertTrue(manager.state.estimatedTokens!! < 8000)
        val restored = manager(manager.state, limit = 8000).prepare()
        assertEquals(active, restored)
    }

    @Test fun lateSummaryFailureLeavesOriginalStateAndCheckpointUntouched() {
        val original = longHistory()
        var calls = 0
        val checkpoints = ArrayList<AgentContextState>()
        val manager = manager(original, limit = 8000, checkpoint = checkpoints::add, model = fake {
            if (++calls == 2) error("network unavailable")
            response("partial summary")
        })
        assertThrows(AgentCompactionException::class.java) { manager.prepare() }
        assertEquals(original, manager.state)
        assertTrue(checkpoints.isEmpty())
    }

    @Test fun emptyAndLengthLimitedSummariesAreNeverCommitted() {
        listOf(response(""), response("unfinished", FinishReason.LENGTH)).forEach { invalid ->
            val original = longHistory()
            val manager = manager(original, limit = 8000, model = fake { invalid })
            assertThrows(AgentCompactionException::class.java) { manager.prepare() }
            assertEquals(original, manager.state)
        }
    }

    @Test fun transientEmptySummaryRetriesWithSmallerFragment() {
        var calls = 0
        val original = longHistory()
        val manager = manager(original, limit = 8000, model = fake {
            if (++calls == 1) response("") else response("Recovered memory")
        })

        manager.prepare()

        assertTrue(calls > 1)
        assertEquals("Recovered memory", manager.state.summary)
        assertEquals(original.messages, manager.state.messages)
    }

    @Test fun oneHugeCurrentUserMessageFailsWithoutLossySummarization() {
        val original = AgentContextState(messages = listOf(AgentContextMessage(1, "user", "x".repeat(50_000))))
        val manager = manager(original, limit = 8000, model = fake { error("must not summarize latest user alone") })
        assertThrows(AgentContextCapacityException::class.java) { manager.prepare() }
        assertEquals(original, manager.state)
    }

    @Test fun longSingleToolTurnRetainsUserVerbatimAndCompactsWholeCallGroup() {
        val original = AgentContextState(messages = listOf(
            AgentContextMessage(1, "user", "Do not enable patch-alpha"),
            AgentContextMessage(2, "assistant", calls = listOf(AgentContextToolCall("big", "read", "{}"))),
            AgentContextMessage(2, "tool", "x".repeat(50_000), callId = "big", toolName = "read"),
        ))
        val manager = manager(original, limit = 8000)
        val active = manager.prepare()
        assertEquals(3, manager.state.summarizedCount)
        assertEquals(2, active.size)
        assertEquals("Do not enable patch-alpha", (active[1] as UserMessage).singleText())
        assertEquals(original.messages, manager.state.messages)
    }

    @Test fun interruptedToolGroupsGetUnknownResultsRatherThanReexecution() {
        val state = AgentContextState(messages = listOf(
            AgentContextMessage(1, "user", "write"),
            AgentContextMessage(2, "assistant", calls = listOf(
                AgentContextToolCall("a", "write", "{}"), AgentContextToolCall("b", "package", "{}"),
            )),
            AgentContextMessage(2, "tool", "written", callId = "a", toolName = "write"),
            AgentContextMessage(3, "user", "continue"),
        ))
        val repaired = state.closeInterruptedTools()
        assertEquals("written", repaired.messages[2].text)
        assertEquals("b", repaired.messages[3].callId)
        assertTrue(repaired.messages[3].text.contains("outcome is unknown"))
        assertEquals("user", repaired.messages[4].role)
        assertEquals(repaired, repaired.closeInterruptedTools())
    }

    @Test fun staleToolResultsAreNotReplayedIntoTheNextAssistantTurn() {
        val state = AgentContextState(messages = listOf(
            AgentContextMessage(1, "user", "write"),
            AgentContextMessage(2, "assistant", calls = listOf(
                AgentContextToolCall("current", "write", "{}"),
            )),
            AgentContextMessage(2, "tool", "stale result", callId = "old", toolName = "read"),
        ))

        val repaired = state.closeInterruptedTools()
        assertEquals(listOf("user", "assistant", "tool"), repaired.messages.map { it.role })
        assertEquals("current", repaired.messages[2].callId)
        assertTrue(repaired.messages[2].text.contains("outcome is unknown"))
        assertFalse(repaired.messages.any { it.callId == "old" })
    }

    @Test fun duplicateToolResultsAreCollapsedToOneResultPerCall() {
        val state = AgentContextState(messages = listOf(
            AgentContextMessage(1, "user", "write"),
            AgentContextMessage(2, "assistant", calls = listOf(
                AgentContextToolCall("current", "write", "{}"),
            )),
            AgentContextMessage(2, "tool", "first", callId = "current", toolName = "write"),
            AgentContextMessage(2, "tool", "duplicate", callId = "current", toolName = "write"),
        ))

        val repaired = state.closeInterruptedTools()
        assertEquals(3, repaired.messages.size)
        assertEquals("first", repaired.messages[2].text)
    }

    @Test fun summaryBoundaryNeverSplitsAssistantToolGroup() {
        val state = AgentContextState(
            messages = listOf(
                AgentContextMessage(1, "user", "goal"),
                AgentContextMessage(2, "assistant", calls = listOf(
                    AgentContextToolCall("current", "write", "{}"),
                )),
                AgentContextMessage(2, "tool", "written", callId = "current", toolName = "write"),
            ),
            summary = "old memory",
            summarizedCount = 2,
        )

        val repaired = state.closeInterruptedTools()
        assertEquals(0, repaired.summarizedCount)
        assertEquals("", repaired.summary)
        val messages = manager(repaired).prepare()
        assertEquals(4, messages.size)
        assertEquals("current", (messages[2] as AiMessage).toolExecutionRequests().single().id())
        assertEquals("current", (messages[3] as ToolExecutionResultMessage).id())
    }

    @Test fun summaryBoundaryNeverSplitsMultiToolGroupAfterFirstResult() {
        val state = AgentContextState(
            messages = listOf(
                AgentContextMessage(1, "user", "goal"),
                AgentContextMessage(2, "assistant", calls = listOf(
                    AgentContextToolCall("first", "write", "{}"),
                    AgentContextToolCall("second", "read", "{}"),
                )),
                AgentContextMessage(2, "tool", "written", callId = "first", toolName = "write"),
                AgentContextMessage(2, "tool", "read", callId = "second", toolName = "read"),
            ),
            summary = "old memory",
            summarizedCount = 3,
        )

        val repaired = state.closeInterruptedTools()
        assertEquals(0, repaired.summarizedCount)
        assertEquals("", repaired.summary)
        val messages = manager(repaired).prepare()
        assertEquals("first", (messages[2] as AiMessage).toolExecutionRequests()[0].id())
        assertEquals("second", (messages[2] as AiMessage).toolExecutionRequests()[1].id())
        assertEquals("first", (messages[3] as ToolExecutionResultMessage).id())
        assertEquals("second", (messages[4] as ToolExecutionResultMessage).id())
    }

    @Test fun prepareRepairsPersistedSplitBoundaryBeforeBuildingRequest() {
        val state = AgentContextState(
            messages = listOf(
                AgentContextMessage(1, "user", "write"),
                AgentContextMessage(2, "assistant", calls = listOf(
                    AgentContextToolCall("current", "write", "{}"),
                )),
                AgentContextMessage(2, "tool", "written", callId = "current", toolName = "write"),
                AgentContextMessage(3, "user", "continue"),
            ),
            summary = "stale summary",
            summarizedCount = 2,
        )
        val checkpoints = ArrayList<AgentContextState>()
        val manager = manager(state, checkpoint = checkpoints::add)

        val prepared = manager.prepare()

        assertEquals(listOf("system", "user", "assistant", "tool", "user"), prepared.map { it.type().name.lowercase() })
        assertEquals("current", (prepared[2] as AiMessage).toolExecutionRequests().single().id())
        assertEquals("current", (prepared[3] as ToolExecutionResultMessage).id())
        assertTrue(checkpoints.any { it.summarizedCount == 0 && it.summary.isEmpty() })
    }

    @Test fun rollbackInvalidatesOnlySummariesCoveringRemovedMessages() {
        val original = longHistory().copy(summary = "memory", summarizedCount = 2)
        assertEquals("memory", original.retainSources(setOf(1, 2)).summary)
        val removed = original.retainSources(setOf(1))
        assertEquals("", removed.summary)
        assertEquals(0, removed.summarizedCount)
        assertEquals(listOf(1L), removed.messages.map { it.sourceMessageId })
    }

    @Test fun actualPromptUsageCalibratesEstimateWithoutCountingOutputAsInput() {
        val manager = manager(AgentContextState(messages = listOf(AgentContextMessage(1, "user", "hi"))))
        manager.prepare()
        val before = manager.requestEstimate()
        manager.observe(ChatResponse.builder().aiMessage(AiMessage.from("ok"))
            .tokenUsage(TokenUsage(before * 2, 42)).build(), before)
        assertEquals(before * 2, manager.state.lastInputTokens)
        assertEquals(42, manager.state.lastOutputTokens)
        assertTrue(manager.state.estimatedTokens!! >= before * 2)
    }

    @Test fun cancelledSummaryDoesNotCommit() {
        var cancelled = false
        val original = longHistory()
        val manager = AgentContextManager(original, "system", "tools", AgentContextBudget(8000), 3,
            fake { cancelled = true; response("memory") }, { fail("must not commit") },
            { if (cancelled) throw java.util.concurrent.CancellationException() })
        assertThrows(java.util.concurrent.CancellationException::class.java) { manager.prepare() }
        assertEquals(original, manager.state)
    }

    @Test fun summaryOverflowRetriesSmallerFragmentsAndEventuallyCompletes() {
        var failures = 0
        var successes = 0
        val original = longHistory()
        val manager = manager(original, limit = 8000, model = fake { request ->
            val fragment = (request.messages().last() as UserMessage).singleText().substringAfter("Next history fragment")
            if (fragment.length > 1000) {
                failures++
                throw IllegalArgumentException("context_length_exceeded")
            }
            successes++
            response("memory")
        })
        manager.prepare()
        assertTrue(failures in 1..3)
        assertTrue(successes > 1)
        assertEquals(original.messages, manager.state.messages)
        assertEquals(1, manager.state.compactionCount)
    }

    @Test fun summaryCheckpointFailureDoesNotActivateNewSummary() {
        val original = longHistory()
        val manager = manager(original, limit = 8000, checkpoint = { throw java.io.IOException("disk full") })
        assertThrows(java.io.IOException::class.java) { manager.prepare() }
        assertEquals(original, manager.state)
    }

    @Test fun repeatedProviderOverflowStopsAfterOneRecoveryAttempt() {
        var attempts = 0
        val model = fake { request ->
            if ((request.messages().first() as SystemMessage).text().startsWith("Summarize")) response("memory")
            else { attempts++; throw IllegalArgumentException("context_length_exceeded") }
        }
        val manager = manager(AgentContextState(messages = listOf(
            AgentContextMessage(1, "user", "old request ".repeat(300)),
            AgentContextMessage(2, "assistant", "done"),
            AgentContextMessage(3, "user", "continue"),
        )), model = model)
        assertThrows(IllegalArgumentException::class.java) {
            PolicyGatedAgentGateway(model, AgentToolRegistry(emptyList()), contextManager = manager).respond("", "")
        }
        assertEquals(2, attempts)
    }

    @Test fun providerOverflowCompactsOnceAndRetriesWithoutReplayingTools() {
        var executionCount = 0
        var modelTurns = 0
        var summaryTurns = 0
        val model = fake { request ->
            if ((request.messages().first() as SystemMessage).text().startsWith("Summarize")) {
                summaryTurns++
                response("Old context memory")
            } else when (++modelTurns) {
                1 -> ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                    .id("write-1").name("write").arguments("{}").build())).build()
                2 -> throw IllegalArgumentException("context_length_exceeded")
                else -> response("done")
            }
        }
        val tool = object : AgentTool {
            override val specification = ToolSpecification.builder().name("write").description("write").build()
            override val safety = AgentToolSafety.READ_ONLY
            override fun execute(arguments: String): String { executionCount++; return "written" }
        }
        val manager = manager(AgentContextState(messages = listOf(
            AgentContextMessage(1, "user", "old request ".repeat(300)),
            AgentContextMessage(2, "assistant", "old answer ".repeat(300)),
            AgentContextMessage(3, "user", "write once"),
        )), model = model)
        val gateway = PolicyGatedAgentGateway(model, AgentToolRegistry(listOf(tool)), contextManager = manager)
        assertEquals("done", gateway.respond("", "").text)
        assertEquals(1, executionCount)
        assertEquals(3, modelTurns)
        assertEquals(1, summaryTurns)
        assertEquals("done", manager.state.messages.last().text)
    }

    @Test fun streamingAlsoRecoversFromProviderOverflow() {
        var attempts = 0
        val streaming = object : StreamingChatModel {
            override fun doChat(request: ChatRequest, handler: StreamingChatResponseHandler) {
                if (++attempts == 1) handler.onError(IllegalArgumentException("maximum context length exceeded"))
                else { handler.onPartialResponse("done"); handler.onCompleteResponse(response("done")) }
            }
        }
        val model = fake { response("memory") }
        val manager = manager(AgentContextState(messages = listOf(
            AgentContextMessage(1, "user", "old request ".repeat(300)),
            AgentContextMessage(2, "assistant", "old answer"),
            AgentContextMessage(3, "user", "continue"),
        )), model = model)
        val result = PolicyGatedAgentGateway(model, AgentToolRegistry(emptyList()), contextManager = manager)
            .respondStreaming("", "", streaming, {})
        assertEquals("done", result.text)
        assertEquals(2, attempts)
        assertEquals(1, manager.state.compactionCount)
    }

    private fun longHistory() = AgentContextState(messages = listOf(
        AgentContextMessage(1, "user", "Original goal"),
        AgentContextMessage(2, "assistant", "x".repeat(40_000)),
    ))

    private fun manager(
        state: AgentContextState,
        model: ChatModel = fake { response("Verified memory: keep user constraints and patch-alpha.") },
        limit: Int = 200_000,
        checkpoint: (AgentContextState) -> Unit = {},
    ) = AgentContextManager(state, "system", "tools", AgentContextBudget(limit), 10, model, checkpoint)

    private fun fake(reply: (ChatRequest) -> ChatResponse) = object : ChatModel {
        override fun doChat(request: ChatRequest): ChatResponse = reply(request)
    }

    private fun response(text: String, finish: FinishReason = FinishReason.STOP): ChatResponse =
        ChatResponse.builder().aiMessage(AiMessage.from(text)).finishReason(finish).build()
}

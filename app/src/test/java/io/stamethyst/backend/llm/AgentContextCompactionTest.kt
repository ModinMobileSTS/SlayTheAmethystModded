package io.stamethyst.backend.llm

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.output.FinishReason
import dev.langchain4j.model.output.TokenUsage
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class AgentContextCompactionTest {
    private val history = AgentContextState(messages = listOf(
        AgentContextMessage(1, "user", "Inspect patch-alpha"),
        AgentContextMessage(2, "assistant", "old work"),
        AgentContextMessage(3, "user", "Do not enable the patch"),
        AgentContextMessage(4, "assistant", "verification pending"),
    ))

    @Test fun manualCompactionWorksBelowThresholdAndKeepsLatestExchange() {
        val events = ArrayList<AgentCompactionProgress>()
        val requests = ArrayList<ChatRequest>()
        val manager = manager(model = fake {
            requests += it
            answer()
        }, events = events)

        val active = manager.prepare(manual = true)

        assertEquals(1, requests.size)
        assertTrue(requests.single().messages().first() is SystemMessage)
        assertEquals("## Objective\nContinue the task", manager.state.summary)
        assertTrue(manager.state.recent.contains("Do not enable the patch"))
        assertFalse(manager.state.recent.contains("Inspect patch-alpha"))
        assertEquals(history.messages, manager.state.messages)
        assertEquals(2, active.size)
        assertEquals(listOf("running", "running", "completed"), events.map { it.status })
        assertEquals(10L, events.last().inputTokens)
        assertEquals(5L, events.last().outputTokens)
        assertEquals(1, events.map { it.id }.distinct().size)
    }

    @Test fun laterCheckpointIncludesPriorSummaryAndRecentWithoutDuplicatingProtocolTail() {
        val first = manager()
        first.prepare(manual = true)
        val state = first.state.copy(messages = first.state.messages + listOf(
            AgentContextMessage(5, "user", "Now build"), AgentContextMessage(6, "assistant", "build succeeded"),
        ))
        var request: ChatRequest? = null
        val second = manager(state = state, model = fake { request = it; answer() })

        second.prepare(manual = true)

        val checkpoint = (request!!.messages()[1] as UserMessage).singleText()
        assertTrue(checkpoint.contains(first.state.summary))
        assertTrue(checkpoint.contains(first.state.recent))
        assertTrue((request!!.messages().last() as UserMessage).singleText().startsWith("Update the existing checkpoint"))
        assertEquals(2, second.state.compactionCount)
        assertEquals(state.messages.size, second.state.summarizedCount)
        assertTrue(second.state.recent.contains("Now build"))
    }

    @Test fun oversizedToolResultsAreShortenedOnlyInCheckpointNotArchive() {
        val output = "😀".repeat(1500)
        val record = AgentContextMessage(1, "tool", output, callId = "a", toolName = "read")
        val text = AgentContextCompaction.text(record)
        assertTrue(text.endsWith("\n[truncated]"))
        assertTrue(text.contains("😀".repeat(1250)))
        assertFalse(text.contains("😀".repeat(1251)))
        assertEquals(output, record.text)
    }

    @Test fun recentAttachmentKeepsNameWithoutReplayingContent() {
        val record = AgentContextMessage(1, "user", "Inspect this file", attachments = listOf(
            AgentContextAttachment("source.java", "large attachment content")))
        assertTrue((record.toChatMessages().single() as UserMessage).singleText().contains("large attachment content"))
        val text = AgentContextCompaction.text(record)
        assertTrue(text.contains("[Attached text/plain: source.java]"))
        assertFalse(text.contains("large attachment content"))
    }

    @Test fun summaryReceivesSessionSystemAndToolSchemasButNeverExecutesTools() {
        val tool = ToolSpecification.builder().name("read").description("Read a file").build()
        val model = fake { request ->
            assertEquals("session system", (request.messages().first() as SystemMessage).text())
            assertEquals(listOf(tool), request.toolSpecifications())
            assertEquals(2_000, request.parameters().maxOutputTokens().toInt())
            answer()
        }
        val manager = AgentContextManager(history, "session system", "read schema", AgentContextBudget(), 5,
            model, {}, toolSpecifications = listOf(tool))

        manager.prepare(manual = true)

        assertEquals(1, manager.state.compactionCount)
    }

    @Test fun missingUsageRemainsUnknownAndUnknownFinishReasonIsNotAccepted() {
        val events = ArrayList<AgentCompactionProgress>()
        val manager = manager(model = fake {
            ChatResponse.builder().aiMessage(AiMessage.from("## Objective\nIncomplete"))
                .finishReason(FinishReason.OTHER).build()
        }, events = events)

        assertThrows(AgentCompactionException::class.java) { manager.prepare(manual = true) }

        assertEquals("failed", events.last().status)
        assertNull(events.last().inputTokens)
        assertNull(events.last().outputTokens)
        assertEquals(history, manager.state)
    }

    @Test fun summaryPreservesNativeParallelToolGroupsAndSingleReasoningBlock() {
        val records = listOf(
            AgentContextMessage(1, "user", "Inspect"),
            AgentContextMessage(2, "assistant", thinking = "Inspect both results.", calls = listOf(
                AgentContextToolCall("a", "read", "{}"), AgentContextToolCall("b", "read", "{}"))),
            AgentContextMessage(2, "tool", "one", callId = "a", toolName = "read"),
            AgentContextMessage(2, "tool", "two", callId = "b", toolName = "read"),
        )
        var request: ChatRequest? = null
        val manager = manager(state = AgentContextState(messages = records), model = fake { request = it; answer() })

        manager.prepare(manual = true)

        val assistant = request!!.messages().filterIsInstance<AiMessage>().single()
        assertEquals(listOf("a", "b"), assistant.toolExecutionRequests().map { it.id() })
        assertEquals("Inspect both results.", assistant.thinking())
        assertEquals(listOf("a", "b"), request!!.messages().filterIsInstance<ToolExecutionResultMessage>().map { it.id() })
    }

    @Test fun splitKeepsNewSingleExchangeWhenPreviousCheckpointAlreadyHadRecent() {
        val records = listOf(AgentContextMessage(5, "user", "New request"), AgentContextMessage(6, "assistant", "done"))
        assertEquals(0, AgentContextCompaction.split(records, previousRecent = true))
        assertEquals(records.size, AgentContextCompaction.split(records))
    }

    @Test fun payloadTooLargeRetriesOnceAsTextWithoutResendingToolProtocol() {
        var calls = 0
        val problems = ArrayList<AgentExecutionStep>()
        val state = AgentContextState(messages = listOf(
            AgentContextMessage(1, "user", "Inspect"),
            AgentContextMessage(2, "assistant", calls = listOf(AgentContextToolCall("a", "read", "{}"))),
            AgentContextMessage(2, "tool", "result", callId = "a", toolName = "read"),
        ))
        val manager = manager(state = state, steps = problems, model = fake { request ->
            if (++calls == 1) throw IllegalArgumentException("payload too large")
            assertTrue(request.messages().none { it is ToolExecutionResultMessage })
            assertTrue(request.messages().filterIsInstance<UserMessage>().any { it.singleText().contains("[Tool result]: result") })
            answer()
        })

        manager.prepare(manual = true)

        assertEquals(2, calls)
        assertEquals(state.messages, manager.state.messages)
        assertEquals(AgentStepKind.ERROR, problems.single().kind)
        assertTrue(problems.single().detail.contains("payload too large"))
        assertNotNull(problems.single().relatedStepId)
        assertEquals(0L, problems.single().totalTokens)
    }

    @Test fun compactionFailureRecordsOneProblemEvenAcrossNestedCatches() {
        val problems = ArrayList<AgentExecutionStep>()
        val events = ArrayList<AgentCompactionProgress>()
        val manager = manager(steps = problems, events = events, model = fake {
            throw IllegalArgumentException("invalid provider response")
        })
        assertThrows(AgentCompactionException::class.java) { manager.prepare(manual = true) }
        assertEquals(1, problems.size)
        assertEquals(events.last().id, problems.single().relatedStepId)
        assertEquals(AgentStepStatus.FAILED, problems.single().status)
        assertTrue(problems.single().detail.contains("invalid provider response"))
    }

    @Test fun compactionCancellationRecordsAnInterruptedProblem() {
        val problems = ArrayList<AgentExecutionStep>()
        val manager = manager(steps = problems, model = fake { throw CancellationException("summary stopped") })
        assertThrows(CancellationException::class.java) { manager.prepare(manual = true) }
        assertEquals(AgentStepStatus.CANCELLED, problems.single().status)
        assertTrue(problems.single().detail.contains("summary stopped"))
    }

    @Test fun errorHistoryCheckpointFailureNeverRetriesCompactionRequest() {
        var calls = 0
        val manager = AgentContextManager(history, "system", "tools", AgentContextBudget(), 5,
            fake { calls++; throw java.io.IOException("network unavailable") }, {},
            onExecutionStep = { throw java.io.IOException("history unavailable") })
        assertThrows(java.io.IOException::class.java) { manager.prepare(manual = true) }
        assertEquals(1, calls)
    }

    @Test fun summaryOverflowNeverDropsNewestExchangeToMakeRequestFit() {
        var calls = 0
        val events = ArrayList<AgentCompactionProgress>()
        val manager = manager(state = history.copy(messages = history.messages.take(2)), model = fake {
            calls++
            throw IllegalArgumentException("context_length_exceeded")
        }, events = events)

        assertThrows(AgentCompactionException::class.java) { manager.prepare(force = true) }

        assertTrue(calls in 1..4)
        assertEquals("", manager.state.summary)
        assertEquals("failed", events.last().status)
    }

    @Test fun streamDeltasAreVisibleAndFinalSummaryIsCheckpointedAtomically() {
        val events = ArrayList<AgentCompactionProgress>()
        val streaming = object : StreamingChatModel {
            override fun doChat(request: ChatRequest, handler: StreamingChatResponseHandler) {
                handler.onPartialResponse("## Objective\n")
                handler.onPartialResponse("Continue the task")
                handler.onCompleteResponse(answer())
            }
        }
        val manager = manager(model = fake { error("must use stream") }, streaming = streaming, events = events)

        manager.prepare(manual = true)

        assertTrue(events.any { it.status == "running" && it.summary == "## Objective\n" })
        assertEquals("completed", events.last().status)
        assertEquals(events.last().summary, manager.state.summary)
    }

    @Test fun cancellationDisablesLateStreamCallbacksAndDoesNotCheckpoint() {
        var handler: StreamingChatResponseHandler? = null
        var cancelled = false
        val events = ArrayList<AgentCompactionProgress>()
        val streaming = object : StreamingChatModel {
            override fun doChat(request: ChatRequest, callback: StreamingChatResponseHandler) {
                handler = callback
                callback.onPartialResponse("## Objective\n")
                cancelled = true
            }
        }
        val manager = manager(streaming = streaming, events = events,
            cancelled = { if (cancelled) throw CancellationException() })

        assertThrows(CancellationException::class.java) { manager.prepare(manual = true) }
        val count = events.size
        handler!!.onPartialResponse("late text")
        handler!!.onCompleteResponse(answer())

        assertEquals(count, events.size)
        assertEquals("interrupted", events.last().status)
        assertEquals(history, manager.state)
    }

    @Test fun checkpointFailureDoesNotRetryPaidSummaryCall() {
        var calls = 0
        val manager = AgentContextManager(history, "system", "tools", AgentContextBudget(), 5,
            fake { calls++; answer() }, { throw java.io.IOException("disk full") })
        assertThrows(java.io.IOException::class.java) { manager.prepare(manual = true) }
        assertEquals(1, calls)
        assertEquals(history, manager.state)
    }

    @Test fun progressStorageFailureDoesNotRetryPaidSummaryCall() {
        var calls = 0
        val streaming = object : StreamingChatModel {
            override fun doChat(request: ChatRequest, handler: StreamingChatResponseHandler) {
                calls++
                handler.onPartialResponse("## Objective\n")
                handler.onCompleteResponse(answer())
            }
        }
        val manager = AgentContextManager(history, "system", "tools", AgentContextBudget(), 5, fake { answer() }, {},
            streamingModel = streaming, onCompaction = {
                if (it.summary.isNotBlank()) throw java.io.IOException("disk full")
            })

        assertThrows(java.io.IOException::class.java) { manager.prepare(manual = true) }

        assertEquals(1, calls)
        assertEquals(history, manager.state)
    }

    @Test fun rollbackInvalidatesRecentTextAndMeasurementWithSummary() {
        val state = history.copy(summary = "## Objective\nMemory", recent = "recent facts", checkpointVersion = 2,
            summarizedCount = 4, measuredMessageCount = 4)
        val rolledBack = state.retainSources(setOf(1, 2))
        assertEquals("", rolledBack.summary)
        assertEquals("", rolledBack.recent)
        assertNull(rolledBack.measuredMessageCount)
    }

    @Test fun measuredPromptAndOutputAnchorAddsOnlyNewToolResults() {
        val manager = manager()
        manager.prepare()
        manager.observe(ChatResponse.builder().aiMessage(AiMessage.from("reply"))
            .tokenUsage(TokenUsage(1000, 50)).build(), manager.requestEstimate())
        manager.append(AiMessage.from("reply"))
        assertEquals(1050, manager.state.estimatedTokens)
        manager.append(ToolExecutionResultMessage.from("call", "read", "new tool output"))
        assertEquals(1050 + AgentTokenEstimate.message(manager.state.messages.last()), manager.state.estimatedTokens)
    }

    @Test fun legacyCheckpointRemainsUsableUntilSuccessfulMigration() {
        val legacy = history.copy(summary = "old format", summarizedCount = 2)
        val manager = manager(state = legacy)
        val before = manager.prepare()
        assertEquals(0, manager.state.checkpointVersion)
        assertTrue((before[1] as UserMessage).singleText().contains("old format"))

        manager.prepare(manual = true)

        assertEquals(2, manager.state.checkpointVersion)
        assertEquals(history.messages, manager.state.messages)
        assertEquals(history.messages.size, manager.state.summarizedCount)
    }

    @Test fun modelSwitchUpdatesBothSummaryTransportsAndTheirBudgetsInBothDirections() {
        val requested = ArrayList<Pair<String, Int>>()
        val smallModel = fake { request ->
            requested += "small" to request.parameters().maxOutputTokens()
            answer()
        }
        val largeStreaming = object : StreamingChatModel {
            override fun doChat(request: ChatRequest, handler: StreamingChatResponseHandler) {
                requested += "large-stream" to request.parameters().maxOutputTokens()
                handler.onCompleteResponse(answer())
            }
        }
        val smallBudget = AgentModelBudgetPolicy.resolve(metadata = AgentModelLimits(12_000, 9_000, 700))
        val largeBudget = AgentModelBudgetPolicy.resolve(metadata = AgentModelLimits(64_000, 60_000, 1_400))
        val manager = AgentContextManager(history.copy(modelKey = "small"), "system", "tools", smallBudget,
            5, smallModel, {})
        val largeTarget = AgentCompactionTarget(fake { error("must use new stream") }, largeStreaming, largeBudget)
        manager.switchModel("large", largeBudget, largeTarget)
        manager.prepare(manual = true)
        manager.appendInput(AgentContextMessage(5, "user", "Continue"), 6)
        manager.append(AiMessage.from("new work"))
        manager.switchModel("small", smallBudget, AgentCompactionTarget(smallModel, budget = smallBudget))
        manager.prepare(manual = true)
        assertEquals(listOf("large-stream" to 1_400, "small" to 700), requested)
        assertEquals("small", manager.state.modelKey)
        assertEquals(2, manager.state.compactionCount)
    }

    @Test fun dynamicSummarySupplierIsReadOncePerCompactionAndCanChangeIndependently() {
        var snapshots = 0
        val outputs = ArrayList<Int>()
        val model = fake { outputs += it.parameters().maxOutputTokens(); answer() }
        var active = AgentCompactionTarget(model, budget = AgentContextBudget(summaryOutputTarget = 600))
        val manager = AgentContextManager(history, "system", "tools", AgentContextBudget(), 5,
            fake { error("startup model is stale") }, {}, currentCompactionTarget = { snapshots++; active })
        manager.prepare(manual = true)
        active = active.copy(budget = AgentContextBudget(summaryOutputTarget = 1_200))
        manager.prepare(manual = true)
        assertEquals(listOf(600, 1_200), outputs)
        assertEquals(2, snapshots)
    }

    @Test fun sameModelKeyStillAcceptsBudgetAndSummaryTargetUpdates() {
        val outputs = ArrayList<Int>()
        val model = fake { outputs += it.parameters().maxOutputTokens(); answer() }
        val manager = manager(state = history.copy(modelKey = "same"))
        val next = AgentContextBudget(10_000, summaryOutputTarget = 500)
        manager.switchModel("same", next, AgentCompactionTarget(model, budget = next))
        manager.prepare(manual = true)
        assertEquals(next, manager.budget)
        assertEquals(listOf(500), outputs)
    }

    @Test fun smallerSummaryModelRejectsUnfittableLatestExchangeBeforeSendingIt() {
        val state = AgentContextState(messages = listOf(
            AgentContextMessage(1, "user", "Keep the newest exchange"),
            AgentContextMessage(2, "assistant", "x".repeat(40_000)),
        ))
        val model = fake { error("must not send over-budget summary input") }
        val manager = manager(state = state, model = model)
        manager.updateCompactionTarget(AgentCompactionTarget(model, budget = AgentModelBudgetPolicy.resolve(
            metadata = AgentModelLimits(contextWindow = 8_000, maxInputTokens = 6_000, maxOutputTokens = 500))))
        assertThrows(AgentCompactionException::class.java) { manager.prepare(manual = true) }
        assertEquals(state, manager.state)
    }

    @Test fun summaryPromptAndReminderMustFitItsOwnInputBudget() {
        val model = fake { error("template alone exceeds input budget") }
        val manager = manager(model = model)
        manager.updateCompactionTarget(AgentCompactionTarget(model, budget = AgentContextBudget(256)))
        assertThrows(AgentCompactionException::class.java) { manager.prepare(manual = true) }
        assertEquals(history, manager.state)
    }

    @Test fun headingOnlySummaryIsNotAValidCheckpoint() {
        var calls = 0
        val manager = manager(model = fake {
            calls++
            ChatResponse.builder().aiMessage(AiMessage.from("## Objective\n\n## Requirements\n-"))
                .finishReason(FinishReason.STOP).build()
        })
        assertThrows(AgentCompactionException::class.java) { manager.prepare(manual = true) }
        assertEquals(2, calls)
        assertEquals(history, manager.state)
    }

    private fun manager(
        state: AgentContextState = history,
        model: ChatModel = fake { answer() },
        streaming: StreamingChatModel? = null,
        events: MutableList<AgentCompactionProgress> = ArrayList(),
        cancelled: () -> Unit = {},
        steps: MutableList<AgentExecutionStep> = ArrayList(),
    ) = AgentContextManager(state, "system", "tools", AgentContextBudget(), 5, model, {}, cancelled,
        streamingModel = streaming, onCompaction = { events += it }, onExecutionStep = { steps += it })

    private fun fake(reply: (ChatRequest) -> ChatResponse) = object : ChatModel {
        override fun doChat(request: ChatRequest) = reply(request)
    }

    private fun answer() = ChatResponse.builder().aiMessage(AiMessage.from("## Objective\nContinue the task"))
        .finishReason(FinishReason.STOP).tokenUsage(TokenUsage(10, 5)).build()
}

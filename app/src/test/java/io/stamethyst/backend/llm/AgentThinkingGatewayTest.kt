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
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.output.FinishReason
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentThinkingGatewayTest {
    @Test fun streamingHttpSeparatesBothReasoningFormatsAndKeepsToolHistoryClean() {
        MockWebServer().use { server ->
            server.start()
            val chunks = listOf("<thi", "nk>private", "</th", "ink>Checking.")
            val call = """{"index":0,"id":"call-read","type":"function","function":{"name":"read","arguments":"{}"}}"""
            val events = listOf(delta(JSONObject().put("reasoning_content", "native"))) +
                chunks.map { delta(JSONObject().put("content", it)) } +
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[$call]},\"finish_reason\":\"tool_calls\"}]}\n\n"
            server.enqueue(sse(events.joinToString("")))
            server.enqueue(sse(delta(JSONObject().put("content", "<think>final thought</think>Done."))))
            val config = OpenAiCompatibleModelConfig(server.url("/v1/").toString(), "test-key", "test-model")
            val text = StringBuilder()
            val thinking = StringBuilder()
            val steps = ArrayList<AgentExecutionStep>()
            val reply = PolicyGatedAgentGateway(AgentChatModelFactory.create(config), registry(),
                onExecutionStep = { steps += it }).respondStreaming("system", "go",
                AgentChatModelFactory.createStreaming(config)!!, { text.append(it) }, { thinking.append(it) })
            assertEquals("Done.", reply.text)
            assertEquals(1, reply.toolRounds)
            assertEquals("Checking.Done.", text.toString())
            assertEquals("nativeprivatefinal thought", thinking.toString())
            assertFalse(steps.any { it.detail.contains("private") || it.detail.contains("final thought") })
            server.takeRequest()
            val messages = JSONObject(server.takeRequest().body!!.utf8()).getJSONArray("messages")
            val assistant = (0 until messages.length()).map { messages.getJSONObject(it) }
                .single { it.getString("role") == "assistant" }
            assertEquals("Checking.", assistant.getString("content"))
            assertEquals("native\nprivate", assistant.getString("reasoning_content"))
            assertEquals(1, assistant.getJSONArray("tool_calls").length())
        }
    }

    @Test fun synchronousResponseRoutesThinkingAndPreservesToolsAndHistory() {
        var calls = 0
        val text = StringBuilder()
        val thinking = StringBuilder()
        val steps = ArrayList<AgentExecutionStep>()
        val model = model { request ->
            if (calls++ == 0) response("<think>inspect</think>", tool = true) else {
                val previous = request.messages().filterIsInstance<AiMessage>().single()
                assertEquals("", previous.text())
                assertEquals("inspect", previous.thinking())
                assertEquals(1, previous.toolExecutionRequests().size)
                assertTrue(request.messages().last() is ToolExecutionResultMessage)
                response("<think>checked</think>Done.", "native")
            }
        }
        val reply = PolicyGatedAgentGateway(model, registry(), onNonStreamingText = { text.append(it) },
            onNonStreamingThinking = { thinking.append(it) }, onExecutionStep = { steps += it }).respond("system", "go")
        assertEquals("Done.", reply.text)
        assertEquals("Done.", text.toString())
        assertEquals("inspectnative\nchecked", thinking.toString())
        assertFalse(steps.any { it.detail.contains("inspect") || it.detail.contains("checked") })
    }

    @Test fun thinkingOnlyResponseRetriesRatherThanCompletingSuccessfully() {
        var calls = 0
        val text = StringBuilder()
        val thinking = StringBuilder()
        val gateway = PolicyGatedAgentGateway(model {
            if (calls++ == 0) response("<think>not finished") else response("Done.")
        }, registry(), onNonStreamingText = { text.append(it) }, onNonStreamingThinking = { thinking.append(it) })
        assertEquals("Done.", gateway.respond("system", "go").text)
        assertEquals(2, calls)
        assertEquals("Done.", text.toString())
        assertEquals("not finished", thinking.toString())
    }

    @Test fun streamingThinkingOnlyRetriesAndNonStreamingFallbackAlsoParsesTags() {
        var streams = 0
        var syncs = 0
        val text = StringBuilder()
        val thinking = StringBuilder()
        val stream = stream { handler ->
            streams++
            handler.onPartialResponse("<think>stream thought</think>")
            handler.onCompleteResponse(response("<think>stream thought</think>"))
        }
        val reply = PolicyGatedAgentGateway(model { syncs++; response("<think>fallback thought</think>Done.") }, registry())
            .respondStreaming("system", "go", stream, { text.append(it) }, { thinking.append(it) })
        assertEquals("Done.", reply.text)
        assertTrue(streams > 1)
        assertEquals(1, syncs)
        assertEquals("Done.", text.toString())
        assertEquals("stream thought".repeat(streams) + "fallback thought", thinking.toString())
    }

    @Test fun completeOnlyStreamsAreParsedAndNativeThinkingIsNotDuplicated() {
        listOf(false, true).forEach { partial ->
            val text = StringBuilder()
            val thinking = StringBuilder()
            val stream = stream { handler ->
                if (partial) {
                    handler.onPartialThinking(PartialThinking("native"))
                    handler.onPartialResponse("<think>inline</think>Done.")
                }
                handler.onCompleteResponse(response("<think>inline</think>Done.", "native"))
            }
            val reply = PolicyGatedAgentGateway(model { error("unexpected fallback") }, registry())
                .respondStreaming("system", "go", stream, { text.append(it) }, { thinking.append(it) })
            assertEquals("Done.", reply.text)
            assertEquals("Done.", text.toString())
            assertEquals("nativeinline", thinking.toString())
        }
    }

    @Test fun truncatedThinkingOnlyFailsWithoutPretendingToHaveAnAnswer() {
        val text = StringBuilder()
        val gateway = PolicyGatedAgentGateway(model {
            response("<think>unfinished", reason = FinishReason.LENGTH)
        }, registry(), onNonStreamingText = { text.append(it) })
        assertThrows(AgentResponseTerminatedException::class.java) { gateway.respond("system", "go") }
        assertEquals("", text.toString())
    }

    private fun response(text: String, thinking: String? = null, tool: Boolean = false,
        reason: FinishReason = FinishReason.STOP): ChatResponse = ChatResponse.builder()
        .aiMessage(AiMessage.builder().text(text).thinking(thinking).apply {
            if (tool) toolExecutionRequests(listOf(ToolExecutionRequest.builder()
                .id("call-read").name("read").arguments("{}").build()))
        }.build()).finishReason(reason).build()

    private fun model(block: (ChatRequest) -> ChatResponse) = object : ChatModel {
        override fun doChat(request: ChatRequest) = block(request)
    }

    private fun stream(block: (StreamingChatResponseHandler) -> Unit) = object : StreamingChatModel {
        override fun doChat(request: ChatRequest, handler: StreamingChatResponseHandler) = block(handler)
    }

    private fun registry() = AgentToolRegistry(listOf(object : AgentTool {
        override val specification = ToolSpecification.builder().name("read").build()
        override val safety = AgentToolSafety.READ_ONLY
        override fun execute(arguments: String) = "read result"
    }))

    private fun delta(value: JSONObject) = "data: " + JSONObject().put("choices",
        org.json.JSONArray().put(JSONObject().put("index", 0).put("delta", value))).toString() + "\n\n"

    private fun sse(events: String) = MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
        .body(events + "data: [DONE]\n\n").build()
}

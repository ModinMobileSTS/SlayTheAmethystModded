package io.stamethyst.backend.llm

import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Real SSE/Responses decoders: missing cache counters must never become synthetic zero-percent hits. */
class AgentCacheStreamingAccountingTest {
    @Test fun missingEmptyAndNullStreamingCacheFieldsRemainUnknown() {
        listOf("", ""","prompt_tokens_details":{}""",
            ""","prompt_tokens_details":{"cached_tokens":null}""").forEach { extra ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(sse(extra))
                val manager = manager(server)
                respond(server, manager)
                assertEquals(106_891L, manager.state.cacheStats.lastInputTokens)
                assertNull("Missing SSE cache counter must not appear as 0%", manager.state.cacheStats.lastReadTokens)
                assertNull(manager.state.cacheStats.lastRate)
                assertEquals(0L, manager.state.cacheStats.reportedRequests)
            }
        }
    }

    @Test fun explicitlyReportedStreamingZeroIsZeroAndCountsAsReported() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(sse(""","prompt_tokens_details":{"cached_tokens":0}"""))
            val manager = manager(server)
            respond(server, manager)
            assertEquals(0L, manager.state.cacheStats.lastReadTokens)
            assertEquals(0.0, manager.state.cacheStats.lastRate!!, 0.0)
            assertEquals(1L, manager.state.cacheStats.reportedRequests)
        }
    }

    @Test fun missingStreamingCacheCounterClearsLastRateWithoutDepressingKnownCumulativeRate() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(sse(""","prompt_tokens_details":{"cached_tokens":100000}"""))
            server.enqueue(sse(""))
            val manager = manager(server)
            respond(server, manager)
            respond(server, manager)
            assertEquals(2L, manager.state.cacheStats.requests)
            assertEquals(1L, manager.state.cacheStats.reportedRequests)
            assertNull(manager.state.cacheStats.lastReadTokens)
            assertEquals(106_891L, manager.state.cacheStats.inputTokens)
            assertEquals(100_000.0 / 106_891, manager.state.cacheStats.totalRate!!, 0.0000001)
        }
    }

    @Test fun missingResponsesApiCacheCounterAlsoRemainsUnknown() {
        listOf("", ""","input_tokens_details":{}""",
            ""","input_tokens_details":{"cached_tokens":null}""").forEach { extra ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json").body(
                    """{"id":"synthetic","object":"response","status":"completed","output":[{"id":"msg-synthetic","type":"message","role":"assistant","content":[{"type":"output_text","text":"OK","annotations":[]}]}],"usage":{"input_tokens":106891,"output_tokens":806,"total_tokens":107697$extra}}""",
                ).build())
                val model = AgentChatModelFactory.create(config(server).copy(endpoint = LlmEndpoint.RESPONSES))
                val response = model.chat(ChatRequest.builder().messages(UserMessage.from("Synthetic input.")).build())
                val usage = AgentCacheUsage.from(response)
                assertEquals(106_891L, usage.input)
                assertNull("Responses API must not fill absent cache reads with 0", usage.read)
            }
        }
    }

    @Test fun absentResponsesUsageDoesNotInventCacheStatisticsFromSdkDefaults() {
        listOf("", ""","usage":null""", ""","usage":{}""").forEach { usage ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json").body(
                    """{"id":"synthetic","object":"response","status":"completed","output":[{"id":"msg-synthetic","type":"message","role":"assistant","content":[{"type":"output_text","text":"OK","annotations":[]}]}]$usage}""",
                ).build())
                val model = AgentChatModelFactory.create(config(server).copy(endpoint = LlmEndpoint.RESPONSES))
                val response = model.chat(ChatRequest.builder().messages(UserMessage.from("Synthetic input.")).build())
                val stats = AgentCacheStats().observe(AgentCacheUsage.from(response))
                assertNull(stats.lastReadTokens)
                assertNull(stats.lastRate)
                assertEquals(0L, stats.reportedRequests)
            }
        }
    }

    @Test fun explicitResponsesZeroRemainsReportedAndMissingNextResponseDoesNotDiluteTheCumulativeRate() {
        MockWebServer().use { server ->
            server.start()
            val model = AgentChatModelFactory.create(config(server).copy(endpoint = LlmEndpoint.RESPONSES))
            var stats = AgentCacheStats()
            listOf(
                ""","input_tokens_details":{"cached_tokens":0}""",
                ""","input_tokens_details":{"cached_tokens":100000}""",
                "",
            ).forEachIndexed { index, extra ->
                server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json").body(
                    """{"id":"synthetic","object":"response","status":"completed","output":[{"id":"msg-synthetic","type":"message","role":"assistant","content":[{"type":"output_text","text":"OK","annotations":[]}]}],"usage":{"input_tokens":106891,"output_tokens":806,"total_tokens":107697$extra}}""",
                ).build())
                val response = model.chat(ChatRequest.builder().messages(UserMessage.from("Synthetic input.")).build())
                stats = stats.observe(AgentCacheUsage.from(response))
                if (index == 0) assertEquals(0.0, stats.lastRate!!, 0.0)
            }
            assertEquals(3L, stats.requests)
            assertEquals(2L, stats.reportedRequests)
            assertNull(stats.lastReadTokens)
            assertEquals(213_782L, stats.inputTokens)
            assertEquals(100_000.0 / 213_782, stats.totalRate!!, 0.0000001)
        }
    }

    private fun manager(server: MockWebServer) = AgentContextManager(
        AgentContextState(), "synthetic system", "", AgentContextBudget(), 1,
        AgentChatModelFactory.create(config(server)), {},
    )

    private fun respond(server: MockWebServer, manager: AgentContextManager) {
        PolicyGatedAgentGateway(AgentChatModelFactory.create(config(server)),
            AgentToolRegistry(emptyList()), contextManager = manager)
            .respondStreaming("", "", requireNotNull(AgentChatModelFactory.createStreaming(config(server))), {})
    }

    private fun config(server: MockWebServer) =
        OpenAiCompatibleModelConfig(server.url("/v1/").toString(), "synthetic-key", "synthetic-model")

    private fun sse(extra: String) = MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
        .body("""
            data: {"id":"synthetic","choices":[{"index":0,"delta":{"role":"assistant","content":"OK"},"finish_reason":null}]}

            data: {"id":"synthetic","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: {"id":"synthetic","choices":[],"usage":{"prompt_tokens":106891,"completion_tokens":806,"total_tokens":107697$extra}}

            data: [DONE]

        """.trimIndent() + "\n").build()
}

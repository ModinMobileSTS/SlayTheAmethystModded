package io.stamethyst.backend.llm

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Local HTTP only: verifies wire values, not provider support for any particular level. */
class LlmReasoningPresetWireTest {
    @Test fun chatCompletionsSendsEachSelectedPresetLiterallyAndLegacyOffOmitsTheField() {
        MockWebServer().use { server ->
            server.start()
            (LlmReasoningEffort.selectable + LlmReasoningEffort.OFF).forEach { effort ->
                server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
                    .body("""{"choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}""").build())
                AgentChatModelFactory.create(OpenAiCompatibleModelConfig(
                    baseUrl = server.url("/v1/").toString(), apiKey = "local-test", modelName = "test-model",
                    reasoningEffort = effort,
                )).chat("test")
                val body = JSONObject(server.takeRequest().body!!.utf8())
                if (effort == LlmReasoningEffort.OFF) assertFalse(body.has("reasoning_effort"))
                else assertEquals(effort.name.lowercase(), body.getString("reasoning_effort"))
            }
        }
    }
}

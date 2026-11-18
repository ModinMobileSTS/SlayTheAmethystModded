package io.stamethyst.backend.llm

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class LlmModelTestServiceTest {
    @Test fun bothProtocolsPreserveActualHttpStatusAndProviderReasonThroughSdkWrapping() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            LlmEndpoint.entries.forEach { endpoint ->
                listOf(400, 401, 402, 403, 404, 422, 429, 500).forEach { status ->
                    val body = """{"error":{"message":"Provider rejected probe: $status; echoed probe-key","code":"diagnostic_code"}}"""
                    server.enqueue(MockResponse.Builder().code(status).body(body).build())
                    val error = runCatching {
                        LlmModelTestService().test(OpenAiCompatibleModelConfig(
                            baseUrl = server.url("/v1").toString(), apiKey = "probe-key",
                            modelName = "probe-model", endpoint = endpoint,
                        ))
                    }.exceptionOrNull()

                    assertNotNull("$endpoint / $status must fail", error)
                    val detail = llmSettingsErrorDetail(error!!, "probe-key")
                    assertTrue("$endpoint: $detail", detail.contains("HTTP $status"))
                    assertTrue("$endpoint: $detail", detail.contains("Provider rejected probe: $status"))
                    assertTrue(detail.contains("diagnostic_code"))
                    assertTrue(detail.contains("Request: POST"))
                    assertTrue(detail.contains("proxy=DIRECT"))
                    assertFalse(detail.contains("probe-key"))
                    val request = server.takeRequest()
                    val path = if (endpoint == LlmEndpoint.RESPONSES) "/v1/responses" else "/v1/chat/completions"
                    assertEquals(path, request.url.encodedPath)
                    assertTrue(detail.contains("Request: POST ${server.url(path)}"))
                }
            }
        }
    }
}

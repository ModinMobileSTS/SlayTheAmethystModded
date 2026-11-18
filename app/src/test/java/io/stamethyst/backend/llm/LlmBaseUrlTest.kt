package io.stamethyst.backend.llm

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class LlmBaseUrlTest {
    @Test fun acceptsProviderBasePathsCustomPortsLocalhostAndIpv6() {
        listOf(
            "https://open.bigmodel.cn/api/paas/v4",
            "https://gateway.example.invalid:55555/v1",
            "https://api.deepseek.com/v1/",
            "http://127.0.0.1:8080/v1", "http://[::1]:8080/v1",
            "https://example.invalid/api/model+group/v1", "https://example.invalid/api/%E4%B8%AD%E6%96%87/v1",
        ).forEach { assertTrue(it, isValidLlmBaseUrl(it)) }
        assertEquals("https://example.invalid/v1", requireLlmBaseUrl(" https://example.invalid/v1/ \n"))
    }

    @Test fun rejectsPastedCommandsAndJsonEvenWhenOkHttpAcceptsThemAsPaths() {
        val base = "https://example.invalid/v4"
        val command = "$base  -H \"Authorization: Bearer fictional-old-key\" -d '{\"model\":\"test\"}'"
        val encoded = command.toHttpUrl().toString()
        listOf(command, encoded, encoded.replace("%20", "%2520"),
            "$base/\"fictional-token\"", "$base/{\"model\":\"test\"}", "$base/[\"body\"]",
            "$base%09-H%09fictional-header", "$base%0Afictional-header", "$base%20Authorization:fictional-key",
        ).forEach {
            assertFalse("Must reject command/JSON", isValidLlmBaseUrl(it))
            val error = assertThrows(IllegalArgumentException::class.java) { requireLlmBaseUrl(it) }
            assertFalse(error.message.orEmpty().contains("fictional"))
        }
    }

    @Test fun rejectsCredentialsQueryFragmentsAndNonHttpSchemes() {
        listOf("", "example.invalid/v1", "ftp://example.invalid/v1", "https://user:pass@example.invalid/v1",
            "https://example.invalid/v1?api_key=fictional", "https://example.invalid/v1#fragment",
        ).forEach { assertFalse("Must reject invalid Base URL", isValidLlmBaseUrl(it)) }
    }

    @Test fun abnormalDiagnosticPathsAreOmittedIncludingUnknownCredentialFragments() {
        val polluted = "https://example.invalid/v4 -H \"MalformedAuth:fictional-leaked-fragment\" -d '{\"model\":\"test\"}'"
        val url = polluted.toHttpUrl()
        val safe = llmDiagnosticUrl(url)
        assertEquals("https://example.invalid/<invalid API path omitted>", safe)
        val error = IllegalArgumentException("Bad URL: $url")
        val detail = llmSettingsErrorDetail(error, "different-current-key")
        assertFalse(detail.contains("fictional-leaked-fragment"))
        assertFalse(detail.contains("MalformedAuth"))
        assertFalse(detail.contains("model"))
        assertTrue(detail.contains("invalid API path omitted"))
    }

    @Test fun pollutedStoredUrlIsRejectedBeforeAnyCatalogOrModelRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val polluted = server.url("/v1").toString() + " -H \"MalformedAuth:fictional-leaked-fragment\""
            val error = runCatching { NewApiModelService().fetchModels(polluted, "current-key") }
                .exceptionOrNull()!!
            val detail = llmSettingsErrorDetail(error, "current-key")
            assertTrue(error is IllegalArgumentException)
            assertTrue(detail.contains("Invalid API Base URL"))
            assertTrue(detail.contains("invalid API path omitted"))
            assertFalse(detail.contains("fictional-leaked-fragment"))
            LlmEndpoint.entries.forEach { endpoint ->
                val config = OpenAiCompatibleModelConfig(
                    baseUrl = polluted.toHttpUrl().toString(), apiKey = "current-key", modelName = "test", endpoint = endpoint,
                )
                val failure = runCatching { LlmModelTestService().test(config) }.exceptionOrNull()!!
                assertTrue(failure is IllegalArgumentException)
                assertFalse(llmSettingsErrorDetail(failure, "current-key").contains("fictional-leaked-fragment"))
                assertThrows(IllegalArgumentException::class.java) { AgentChatModelFactory.create(config) }
                if (endpoint == LlmEndpoint.CHAT_COMPLETIONS) {
                    assertThrows(IllegalArgumentException::class.java) { AgentChatModelFactory.createStreaming(config) }
                }
            }
            assertEquals("No request should leave the client", 0, server.requestCount)
        }
    }
}

package io.stamethyst.backend.llm

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class LlmSettingsHttpDiagnosticsTest {
    @Test fun empty400CatalogShowsActualRouteAndAllowlistedHeadersWithoutCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(400)
                .addHeader("Server", "diagnostic-gateway")
                .addHeader("X-Oneapi-Request-Id", "request-id-123")
                .addHeader("Set-Cookie", "session=private-cookie")
                .addHeader("X-Internal-Secret", "private-header")
                .build())
            val key = "probe-secret-key"
            val error = runCatching {
                NewApiModelService().fetchModels(server.url("/v1").toString(), key)
            }.exceptionOrNull()!!
            val detail = llmSettingsErrorDetail(error, key)

            assertTrue(detail.contains("HTTP 400"))
            assertTrue(detail.contains("LLM HTTP diagnostics v1"))
            assertTrue(detail.contains("Request: GET ${server.url("/v1/models")}"))
            assertTrue(detail.contains("Peer:"))
            assertTrue(detail.contains(":${server.port}; proxy=DIRECT"))
            assertTrue(detail.contains("Transport: http/1.1; TLS=none"))
            assertTrue(detail.contains("Configured key: chars=${key.length}; whitespace=0; nonAscii=0; bearerPrefix=false"))
            assertTrue(detail.contains("Credential header: present=true; chars=${"Bearer $key".length}"))
            assertTrue(detail.contains("Response body bytes (declared): 0"))
            assertTrue(detail.contains("Server: diagnostic-gateway"))
            assertTrue(detail.contains("X-Oneapi-Request-Id: request-id-123"))
            listOf(key, "private-cookie", "private-header", "Set-Cookie", "X-Internal-Secret").forEach {
                assertFalse("Must not disclose $it", detail.contains(it))
            }
            assertEquals("Bearer $key", server.takeRequest().headers["Authorization"])
            assertNull("Capture must be removed after failure", LlmSettingsHttpDiagnostics.interceptor())
        }
    }

    @Test fun diagnosticFollowsRedirectsAndRemovesUrlUserInfoQueryAndFragment() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(302)
                .addHeader("Location", server.url("/final/models?token=private-query").newBuilder()
                    .username("private-user").password("private-password").build().toString()).build())
            server.enqueue(MockResponse.Builder().code(400).build())
            val base = server.url("/v1").toString()
            val error = runCatching { NewApiModelService().fetchModels(base, "probe-key") }.exceptionOrNull()!!
            val detail = llmSettingsErrorDetail(error, "probe-key")

            assertTrue(detail.contains("Request: GET ${server.url("/final/models")}"))
            assertTrue(detail.contains("Attempt: 2"))
            listOf("private-user", "private-password", "private-query", "?token=").forEach {
                assertFalse("Must not disclose $it", detail.contains(it))
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun successClearsCaptureAndDoesNotAddExtraRequests() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"data":[{"id":"working"}]}""").build())
            assertEquals(listOf("working"), NewApiModelService()
                .fetchModels(server.url("/v1").toString(), "probe-key").map { it.id })
            assertEquals(1, server.requestCount)
            assertNull(LlmSettingsHttpDiagnostics.interceptor())
        }
    }

    @Test fun noCaptureForOrdinaryAgentClientsAndCancellationDoesNotGetDiagnostics() {
        assertNull(LlmSettingsHttpDiagnostics.interceptor())
        val stop = CancellationException("stopped")
        val failure = runCatching {
            LlmSettingsHttpDiagnostics.capture("https://example.invalid/v1", "key") {
                assertNotNull(LlmSettingsHttpDiagnostics.interceptor())
                throw stop
            }
        }.exceptionOrNull()
        assertSame(stop, failure)
        assertEquals(0, stop.suppressed.size)
        assertNull(LlmSettingsHttpDiagnostics.interceptor())
    }

    @Test fun nestedCaptureRestoresOuterAndKeepsExceptionTypeAndCause() {
        val cause = IllegalArgumentException("invalid")
        LlmSettingsHttpDiagnostics.capture("https://example.invalid/v1?secret=hidden#hidden", " key ") {
            val outer = LlmSettingsHttpDiagnostics.interceptor()
            val failure = runCatching {
                LlmSettingsHttpDiagnostics.capture("https://example.invalid/v2", "Bearer fake-key") { throw cause }
            }.exceptionOrNull()
            assertSame(cause, failure)
            assertNull(cause.cause)
            assertTrue(llmSettingsErrorDetail(cause, "Bearer fake-key").contains("bearerPrefix=true"))
            assertSame(outer, LlmSettingsHttpDiagnostics.interceptor())
        }
        assertNull(LlmSettingsHttpDiagnostics.interceptor())
    }

    @Test fun configuredUrlSecretsAndProviderEchoedKeyAreRedacted() {
        val key = "custom+secret.key"
        val error = runCatching {
            LlmSettingsHttpDiagnostics.capture(
                "https://private-user:private-password@example.invalid/v1?token=private-query#private-fragment", key,
            ) { throw IllegalStateException("Rejected $key") }
        }.exceptionOrNull()!!
        val detail = llmSettingsErrorDetail(error, key)
        assertTrue(detail.contains("Configured URL: https://example.invalid/v1"))
        listOf(key, "private-user", "private-password", "private-query", "private-fragment").forEach {
            assertFalse("Must not disclose $it", detail.contains(it))
        }
        assertTrue(detail.contains("HTTP exchange: not observed"))
    }

    @Test fun allowlistedHeaderIsRedactedBeforeTruncation() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val key = "custom-secret-123456789"
            server.enqueue(MockResponse.Builder().code(400)
                .addHeader("Server", "x".repeat(175) + key + "y".repeat(100)).build())
            val error = runCatching {
                NewApiModelService().fetchModels(server.url("/v1").toString(), key)
            }.exceptionOrNull()!!
            val detail = llmSettingsErrorDetail(error, key)
            assertFalse(detail.contains("custo"))
            assertTrue(detail.contains("Server: " + "x".repeat(175)))
        }
    }
}

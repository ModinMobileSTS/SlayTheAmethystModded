package io.stamethyst.backend.llm

import dev.langchain4j.exception.HttpException
import dev.langchain4j.exception.InvalidRequestException
import java.net.UnknownHostException
import org.junit.Assert.*
import org.junit.Test

class LlmSettingsErrorTest {
    @Test fun wrappedHttpFailureShowsStatusAndOriginalProviderMessageWithoutDuplicates() {
        val body = """{"error":{"message":"Account quota exhausted","code":"insufficient_quota"}}"""
        val detail = llmSettingsErrorDetail(InvalidRequestException(HttpException(402, body)), "secret")

        assertTrue(detail.startsWith("InvalidRequestException (HTTP 402)\n"))
        assertTrue(detail.contains(body))
        assertEquals(1, Regex("Account quota exhausted").findAll(detail).count())
    }

    @Test fun networkFailurePreservesUnderlyingReasonWithoutInventingHttpStatus() {
        val detail = llmSettingsErrorDetail(
            RuntimeException("Request failed", UnknownHostException("Unable to resolve host example.invalid")), "secret",
        )
        assertTrue(detail.contains("Request failed"))
        assertTrue(detail.contains("Unable to resolve host example.invalid"))
        assertFalse(detail.contains("HTTP"))
        assertFalse(detail.contains("\tat "))
    }

    @Test fun missingMessageFallsBackToExceptionType() {
        assertEquals("IllegalStateException", llmSettingsErrorDetail(IllegalStateException(), ""))
    }

    @Test fun emptyHttpBodyIsExplicitInsteadOfOnlyAnExceptionName() {
        val detail = llmSettingsErrorDetail(InvalidRequestException(HttpException(400, "")), "")
        assertTrue(detail.contains("HTTP 400"))
        assertTrue(detail.contains("Response body: <empty>"))
    }

    @Test fun providerEchoedCredentialsAreRedactedInEveryCause() {
        val key = "custom.key+with-regex?chars"
        val body = """{"error":"rejected $key", "api_key":"other-secret", "Authorization":"Bearer bearer-secret", "access_token":"token-secret"} sk-anotherSecret123"""
        val detail = llmSettingsErrorDetail(InvalidRequestException(HttpException(400, body)), " $key ")

        listOf(key, "other-secret", "bearer-secret", "token-secret", "sk-anotherSecret123").forEach {
            assertFalse("Leaked $it", detail.contains(it))
        }
        assertTrue(detail.contains("HTTP 400"))
        assertTrue(detail.contains("[redacted]"))
    }

    @Test fun emptyConfiguredKeyDoesNotReplaceEveryCharacter() {
        assertTrue(llmSettingsErrorDetail(HttpException(403, "Bearer abc-secret denied"), "  ")
            .contains("Bearer [redacted] denied"))
    }

    @Test fun longResponsesAreBoundedAfterRedactionAndKeepStatus() {
        val key = "custom-secret-1234567890"
        val detail = llmSettingsErrorDetail(
            HttpException(502, "x".repeat(4_060) + key + "y".repeat(1_000)), key,
        )
        assertEquals(4_096, detail.length)
        assertTrue(detail.startsWith("HttpException (HTTP 502)"))
        assertTrue(detail.endsWith("…"))
        assertFalse(detail.contains("custom-secret"))
    }

    @Test fun cyclicCauseChainTerminates() {
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)
        assertEquals("RuntimeException\nfirst\nsecond", llmSettingsErrorDetail(first, ""))
    }
}

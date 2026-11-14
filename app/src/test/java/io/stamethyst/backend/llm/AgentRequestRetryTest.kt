package io.stamethyst.backend.llm

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.exception.HttpException
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import java.io.IOException
import java.util.concurrent.CancellationException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AgentRequestRetryTest {
    @Test
    fun retryPolicy_rejectsPermanentErrorsAndCancellation() {
        listOf(400, 401, 403, 404, 422).forEach {
            assertFalse(isRetryableAgentRequestFailure(HttpException(it, "invalid request")))
        }
        assertFalse(isRetryableAgentRequestFailure(CancellationException()))
        assertFalse(isRetryableAgentRequestFailure(IllegalStateException("bad state")))
        assertFalse(isRetryableAgentRequestFailure(IOException(CancellationException())))
        listOf(408, 429, 500, 502, 503).forEach {
            assertTrue(isRetryableAgentRequestFailure(HttpException(it, "temporary")))
        }
        assertTrue(isRetryableAgentRequestFailure(IOException("connection reset")))
    }

    @Test
    fun permanentFailure_isNotRetried() {
        var attempts = 0
        val model = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse {
                attempts++
                throw HttpException(401, "bad key")
            }
        }
        val error = runCatching {
            PolicyGatedAgentGateway(model, AgentToolRegistry(emptyList()), onRetry = { _, _, _ ->
                fail("permanent errors must not retry")
            }).respond("system", "user")
        }.exceptionOrNull()
        assertTrue(error is HttpException)
        assertEquals(1, attempts)
    }

    @Test
    fun streaming_deliversPartialOutputBeforeCompletionWithoutDuplicatingIt() {
        val delivered = ArrayList<String>()
        val streaming = object : StreamingChatModel {
            override fun doChat(request: ChatRequest, handler: StreamingChatResponseHandler) {
                handler.onPartialResponse("hello")
                assertEquals(listOf("hello"), delivered)
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("hello")).build())
                handler.onPartialResponse("late")
            }
        }
        val gateway = PolicyGatedAgentGateway(object : ChatModel {}, AgentToolRegistry(emptyList()))
        val result = gateway.respondStreaming("system", "user", streaming, { delivered += it })
        assertEquals("hello", result.text)
        assertEquals(listOf("hello"), delivered)
    }

    @Test(timeout = 2_000)
    fun streaming_missingTerminalCallback_timesOutInsteadOfWaitingForever() {
        val gateway = PolicyGatedAgentGateway(
            object : ChatModel {}, AgentToolRegistry(emptyList()),
            streamingTimeoutMillis = 20,
            onRetry = { _, _, _ -> fail("An exhausted round must not schedule another request") },
        )
        val error = runCatching {
            gateway.respondStreaming("system", "user", object : StreamingChatModel {
                override fun doChat(request: ChatRequest, handler: StreamingChatResponseHandler) = Unit
            }, {})
        }.exceptionOrNull()
        assertTrue(error is AgentRequestTimeoutException)
        assertEquals(AgentRequestTimeoutKind.ROUND_BUDGET, (error as AgentRequestTimeoutException).kind)
    }

    @Test(timeout = 4_000)
    fun streaming_retrySeparatesPartialFailedAttemptAndIgnoresLateCallbacks() {
        var attempt = 0
        var oldHandler: StreamingChatResponseHandler? = null
        val failedOutput = ArrayList<String>()
        val output = StringBuilder()
        val gateway = PolicyGatedAgentGateway(object : ChatModel {}, AgentToolRegistry(emptyList()),
            onRetry = { _, _, _ ->
                failedOutput += output.toString()
                output.setLength(0)
            },
        )
        val result = gateway.respondStreaming("system", "user", object : StreamingChatModel {
            override fun doChat(request: ChatRequest, handler: StreamingChatResponseHandler) {
                if (attempt++ == 0) {
                    oldHandler = handler
                    handler.onPartialResponse("partial")
                    handler.onError(IOException("connection reset"))
                } else {
                    oldHandler!!.onPartialResponse("stale")
                    handler.onPartialResponse("recovered")
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("recovered")).build())
                }
            }
        }, { output.append(it) })
        assertEquals(listOf("partial"), failedOutput)
        assertEquals("recovered", output.toString())
        assertEquals("recovered", result.text)
    }

    @Test(timeout = 2_000)
    fun cancellationDuringBackoff_preventsNextRequest() {
        var cancelled = false
        var attempts = 0
        val model = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse {
                attempts++
                throw IOException("temporary")
            }
        }
        val error = runCatching {
            PolicyGatedAgentGateway(model, AgentToolRegistry(emptyList()),
                checkCancelled = { if (cancelled) throw CancellationException() },
                onRetry = { _, _, _ -> cancelled = true },
            ).respond("system", "user")
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, attempts)
    }

    @Test(timeout = 6_000)
    fun transientFailure_stopsAfterTwoRetries() {
        var attempts = 0
        val retries = ArrayList<Int>()
        val model = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse {
                attempts++
                throw IOException("temporary")
            }
        }
        val error = runCatching {
            PolicyGatedAgentGateway(model, AgentToolRegistry(emptyList()),
                onRetry = { number, _, _ -> retries += number },
            ).respond("system", "user")
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(3, attempts)
        assertEquals(listOf(1, 2), retries)
    }

    @Test
    fun connectionAbort_isRecognisedAcrossTheCauseChain() {
        assertTrue(isConnectionAbort(java.net.SocketException("Software caused connection abort")))
        assertTrue(isConnectionAbort(java.net.SocketException("Connection reset")))
        assertTrue(isConnectionAbort(java.net.SocketException("Broken pipe")))
        assertFalse(isConnectionAbort(java.net.SocketTimeoutException("timeout")))
        assertTrue(isConnectionAbort(java.io.EOFException()))
        // Transport wrappers hide the socket failure one level down.
        assertTrue(isConnectionAbort(RuntimeException("wrapped", java.net.SocketException("Connection reset"))))
        // A protocol error is not a dropped socket and must keep the short budget.
        assertFalse(isConnectionAbort(HttpException(400, "bad request")))
        assertFalse(isConnectionAbort(IllegalStateException("bad state")))
    }

    @Test
    fun abortBackoffIsLongerThanTransientBackoff() {
        // The radio can take tens of seconds to come back after a lock, while a transient provider
        // hiccup is worth retrying almost immediately.
        val abort = (0..2).map { retryDelaySeconds(it, java.net.SocketException("Software caused connection abort")) }
        val transient = (0..2).map { retryDelaySeconds(it, IOException("temporary")) }
        assertEquals(listOf(5L, 15L, 30L), abort)
        assertEquals(listOf(1L, 2L, 4L), transient)
        assertTrue("abort waits longer at every step", abort.zip(transient).all { (a, t) -> a > t })
    }

    @Test(timeout = 60_000)
    fun connectionAbort_getsMoreAttemptsThanATransientFailure() {
        var attempts = 0
        val aborted = ArrayList<Int>()
        val model = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse {
                attempts++
                throw java.net.SocketException("Software caused connection abort")
            }
        }
        val error = runCatching {
            PolicyGatedAgentGateway(model, AgentToolRegistry(emptyList()),
                onRetry = { number, _, _ -> aborted += number },
            ).respond("system", "user")
        }.exceptionOrNull()
        assertTrue(error is java.net.SocketException)
        // MAX_ABORT_RETRIES = 3, so four attempts and three reported retries.
        assertEquals(4, attempts)
        assertEquals(listOf(1, 2, 3), aborted)
    }

    @Test(timeout = 60_000)
    fun connectionAbort_evictsTheConnectionPoolBeforeRetrying() {
        // Reusing the dead pooled connection is why an abort used to fail again immediately.
        val evictions = ArrayList<Int>()
        var attempts = 0
        val model = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse {
                attempts++
                if (attempts == 1) throw java.net.SocketException("Software caused connection abort")
                return ChatResponse.builder().aiMessage(AiMessage.from("recovered")).build()
            }
        }
        val reply = PolicyGatedAgentGateway(model, AgentToolRegistry(emptyList()),
            evictConnectionPool = { evictions += attempts },
        ).respond("system", "user")
        assertEquals("recovered", reply.text)
        assertEquals("the pool must be cleared once, before the retry", 1, evictions.size)
        assertEquals(1, evictions.single())
    }

    @Test
    fun transientFailure_doesNotEvictTheConnectionPool() {
        val evictions = ArrayList<Int>()
        val model = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse = throw HttpException(503, "unavailable")
        }
        runCatching {
            PolicyGatedAgentGateway(model, AgentToolRegistry(emptyList()),
                evictConnectionPool = { evictions += 1 },
            ).respond("system", "user")
        }
        assertTrue("a provider error keeps its warm connections", evictions.isEmpty())
    }

    @Test(timeout = 10_000)
    fun httpFailure_hasNoHiddenModelRetries() {
        MockWebServer().use { server ->
            server.start()
            repeat(6) { server.enqueue(MockResponse.Builder().code(503).body("unavailable").build()) }
            val config = OpenAiCompatibleModelConfig(
                baseUrl = server.url("/v1/").toString(), apiKey = "test", modelName = "test",
            )
            val retries = ArrayList<Int>()
            val error = runCatching {
                PolicyGatedAgentGateway(AgentChatModelFactory.create(config), AgentToolRegistry(emptyList()),
                    onRetry = { number, _, _ -> retries += number },
                ).respond("system", "user")
            }.exceptionOrNull()
            assertNotNull(error)
            assertEquals(listOf(1, 2), retries)
            assertEquals(3, server.requestCount)
        }
    }
}

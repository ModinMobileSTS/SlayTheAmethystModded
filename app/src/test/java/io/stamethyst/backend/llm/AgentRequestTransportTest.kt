package io.stamethyst.backend.llm

import dev.langchain4j.exception.HttpException
import dev.langchain4j.http.client.HttpClient
import dev.langchain4j.http.client.HttpMethod
import dev.langchain4j.http.client.HttpRequest
import dev.langchain4j.http.client.SuccessfulHttpResponse
import dev.langchain4j.http.client.okhttp.OkHttpClientBuilder
import dev.langchain4j.http.client.sse.ServerSentEvent
import dev.langchain4j.http.client.sse.ServerSentEventContext
import dev.langchain4j.http.client.sse.ServerSentEventListener
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okio.BufferedSink
import org.junit.Assert.*
import org.junit.Test
import org.junit.After

class AgentRequestTransportTest {
    private val resources = CopyOnWriteArrayList<AutoCloseable>()
    private val cleanupStarted = AtomicBoolean()

    /** JUnit's timeout interrupts but does not join the test thread. @After runs on the runner. */
    @After fun closeResourcesEvenWhenTimedTestHasNotUnwound() {
        cleanupStarted.set(true)
        resources.reversed().forEach { resource ->
            if (resources.remove(resource)) runCatching { resource.close() }
        }
    }

    private fun <T : AutoCloseable> T.tracked(): T = also { resource ->
        resources += resource
        // A timed-out worker may still finish initialization after runner cleanup began.
        if (cleanupStarted.get() && resources.remove(resource)) runCatching { resource.close() }
    }

    @Test(timeout = 5_000) fun productionStreamingFactoryCapturesScopeBeforeFirstModelToken() {
        MockWebServer().tracked().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
            val model = AgentChatModelFactory.createStreaming(OpenAiCompatibleModelConfig(
                baseUrl = server.url("/v1/").toString(), apiKey = "test", modelName = "test",
            ))!!
            AgentRequestControl().tracked().use { owner ->
                owner.newRequest(policy()).use { scope ->
                    val error = CompletableFuture<Throwable>()
                    scope.bind {
                        model.chat(ChatRequest.builder().messages(UserMessage.from("test")).build(),
                            object : StreamingChatResponseHandler {
                                override fun onCompleteResponse(response: ChatResponse) { error.complete(AssertionError("unexpected response")) }
                                override fun onError(failure: Throwable) { error.complete(failure) }
                            })
                    }
                    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    assertEquals(1, scope.activeCallCount)
                    scope.cancel()
                    assertTrue(error.get(2, TimeUnit.SECONDS) is CancellationException)
                }
            }
        }
    }

    @Test(timeout = 5_000) fun productionNonStreamingFactoriesCaptureScopeForBothEndpoints() {
        LlmEndpoint.entries.forEach { endpoint ->
            MockWebServer().tracked().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
                val model = AgentChatModelFactory.create(OpenAiCompatibleModelConfig(
                    baseUrl = server.url("/v1/").toString(), apiKey = "test", modelName = "test", endpoint = endpoint,
                ))
                AgentRequestControl().tracked().use { owner ->
                    owner.newRequest(policy()).use { scope ->
                        val error = CompletableFuture.supplyAsync {
                            runCatching { scope.bind { model.chat(ChatRequest.builder().messages(UserMessage.from("test")).build()) } }
                                .exceptionOrNull()
                        }
                        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                        assertEquals("endpoint $endpoint", 1, scope.activeCallCount)
                        scope.cancel()
                        val failure = error.get(2, TimeUnit.SECONDS)
                        assertNotNull(failure)
                        assertFalse("cancelled model call must not retry: $endpoint", isRetryableAgentRequestFailure(failure!!))
                        assertEquals(1, server.requestCount)
                    }
                }
            }
        }
    }

    @Test(timeout = 5_000) fun cancellationBeforeHeadersReleasesSilentSseConnection() {
        MockWebServer().tracked().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
            val probe = Probe()
            val client = client(probe)
            AgentRequestControl().tracked().use { owner ->
                owner.newRequest(policy()).use { scope ->
                    val listener = StreamResult()
                    scope.bind { client.execute(request(server), listener) }
                    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    assertEquals(1, scope.activeCallCount)
                    owner.cancel()
                    assertTrue(listener.error.get(2, TimeUnit.SECONDS) is CancellationException)
                    probe.assertCancelledAndReleased()
                    assertEquals(0, listener.events.size)
                }
            }
        }
    }

    @Test(timeout = 5_000) fun cancellationAfterFirstSseEventClosesActiveBody() {
        MockWebServer().tracked().use { server ->
            server.start()
            val body = HeldBody("data: first\n\n")
            try {
                server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(body).build())
                val probe = Probe()
                val client = client(probe)
                AgentRequestControl().tracked().use { owner ->
                    owner.newRequest(policy()).use { scope ->
                        val listener = StreamResult { scope.onMeaningfulEvent() }
                        scope.bind { client.execute(request(server), listener) }
                        assertTrue(listener.firstEvent.await(2, TimeUnit.SECONDS))
                        scope.cancel()
                        assertTrue(listener.error.get(2, TimeUnit.SECONDS) is CancellationException)
                        probe.assertCancelledAndReleased()
                        assertEquals(listOf("first"), listener.events)
                    }
                }
            } finally { body.release.countDown() }
        }
    }

    @Test(timeout = 5_000) fun cancellationInterruptsBlockingNonStreamingResponseBody() {
        MockWebServer().tracked().use { server ->
            server.start()
            val body = HeldBody("{")
            try {
                server.enqueue(MockResponse.Builder().body(body).build())
                val probe = Probe()
                val client = client(probe)
                AgentRequestControl().tracked().use { owner ->
                    owner.newRequest(policy()).use { scope ->
                        val result = CompletableFuture.supplyAsync {
                            runCatching { scope.bind { client.execute(request(server)) } }.exceptionOrNull()
                        }
                        assertTrue(body.written.await(2, TimeUnit.SECONDS))
                        owner.cancel()
                        assertTrue(result.get(2, TimeUnit.SECONDS) is CancellationException)
                        probe.assertCancelledAndReleased()
                    }
                }
            } finally { body.release.countDown() }
        }
    }

    @Test(timeout = 5_000) fun scopeCancelsAsyncNonStreamingBeforeResponse() {
        MockWebServer().tracked().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
            val probe = Probe()
            val client = client(probe)
            AgentRequestControl().tracked().use { owner ->
                owner.newRequest(policy()).use { scope ->
                    val future = scope.bind { client.executeAsync(request(server)) }
                    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    scope.cancel()
                    assertThrows(CancellationException::class.java) { future.get(2, TimeUnit.SECONDS) }
                    probe.assertCancelledAndReleased()
                }
            }
        }
    }

    @Test(timeout = 5_000) fun returnedFutureCancellationStillCancelsUnderlyingAsyncCall() {
        MockWebServer().tracked().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
            val probe = Probe()
            val client = client(probe)
            AgentRequestControl().tracked().use { owner ->
                owner.newRequest(policy()).use { scope ->
                    val future = scope.bind { client.executeAsync(request(server)) }
                    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    future.cancel(true)
                    probe.assertCancelledAndReleased()
                }
            }
        }
    }

    @Test(timeout = 5_000) fun completedCallsUnregisterBeforeScopeCloses() {
        MockWebServer().tracked().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("complete").build())
            val probe = Probe()
            val client = client(probe)
            AgentRequestControl().tracked().use { owner ->
                val scope = owner.newRequest(policy())
                assertEquals("complete", scope.bind { client.execute(request(server)) }.body())
                assertEquals(0, scope.activeCallCount)
                scope.close()
                assertFalse("completed calls must not remain owned by a closed scope", probe.call.get().isCanceled())
            }
        }
    }

    @Test(timeout = 5_000) fun cancellationDuringSilentTlsHandshakeClosesSocketBeforeFirstEvent() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).tracked().use { server ->
            val stages = CopyOnWriteArrayList<String>().apply { add("listening") }
            val hello = CompletableFuture<Unit>()
            val peerSocket = AtomicReference<java.net.Socket?>()
            val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "agent-test-silent-tls").apply { isDaemon = true } }
            val peerCleanup = AutoCloseable {
                try { peerSocket.get()?.close() } finally { executor.shutdownNow() }
            }.tracked()
            val peerClosed = executor.submit<Boolean> {
                try {
                    stages += "accept-started"
                    server.accept().tracked().use { socket ->
                        peerSocket.set(socket)
                        stages += "accepted"
                        socket.soTimeout = 2_000
                        val input = socket.getInputStream()
                        check(input.read() >= 0) { "Peer closed before ClientHello: $stages" }
                        stages += "client-hello"
                        hello.complete(Unit)
                        val bytes = ByteArray(8_192)
                        while (input.read(bytes) != -1) { /* Silent TLS peer; wait for client close. */ }
                        stages += "peer-eof"
                        true
                    }
                } catch (error: Throwable) {
                    hello.completeExceptionally(error)
                    throw error
                }
            }
            val probe = object : Probe() {
                override fun secureConnectStart(call: Call) { stages += "secure-connect-started" }
            }
            val client = client(probe)
            try { AgentRequestControl().tracked().use { owner ->
                owner.newRequest(policy()).use { scope ->
                    val listener = StreamResult()
                    val request = HttpRequest.builder().method(HttpMethod.GET)
                        .url("https://127.0.0.1:${server.localPort}/").build()
                    scope.bind { client.execute(request, listener) }
                    try { hello.get(2, TimeUnit.SECONDS) } catch (error: Exception) {
                        throw AssertionError("TLS ClientHello readiness failed; stages=$stages", error)
                    }
                    scope.cancel()
                    assertTrue(listener.error.get(2, TimeUnit.SECONDS) is CancellationException)
                    try { assertTrue(peerClosed.get(2, TimeUnit.SECONDS)) } catch (error: Exception) {
                        throw AssertionError("Cancelled TLS peer did not reach EOF; stages=$stages", error)
                    }
                    assertTrue(probe.call.get().isCanceled())
                    assertTrue("Cancelled TLS call did not terminate; stages=$stages", probe.finished.await(2, TimeUnit.SECONDS))
                    assertTrue(listener.events.isEmpty())
                }
            } } finally { peerCleanup.close() }
        }
    }

    @Test(timeout = 5_000) fun sharedOwnerStopsMainAndSummaryButLeavesOtherExecutionAlive() {
        MockWebServer().tracked().use { server ->
            server.start()
            val mainBody = HeldBody("data: main\n\n")
            val summaryBody = HeldBody("data: summary\n\n")
            val otherBody = HeldBody("data: other\n\n", completeOnRelease = true)
            val bodies = listOf(mainBody, summaryBody, otherBody)
            try {
                bodies.forEach { server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(it).build()) }
                val probes = List(3) { Probe() }
                val client = client { call -> probes[call.request().url.encodedPath.removePrefix("/").toInt()] }
                AgentRequestControl().tracked().use { owner ->
                    AgentRequestControl().tracked().use { otherOwner ->
                        val main = owner.newRequest(policy())
                        val summary = owner.newRequest(policy())
                        val other = otherOwner.newRequest(policy())
                        try {
                            val listeners = listOf(main, summary, other).mapIndexed { index, scope ->
                                StreamResult().also {
                                    scope.bind { client.execute(request(server, "/$index"), it) }
                                    assertTrue(it.firstEvent.await(2, TimeUnit.SECONDS))
                                }
                            }
                            owner.cancel()
                            listeners.take(2).forEach { assertTrue(it.error.get(2, TimeUnit.SECONDS) is CancellationException) }
                            probes.take(2).forEach { it.assertCancelledAndReleased() }
                            assertFalse(probes[2].call.get().isCanceled())
                            assertNull(other.stopReason)
                            otherBody.release.countDown()
                            assertTrue(listeners[2].closed.await(2, TimeUnit.SECONDS))
                            assertFalse(listeners[2].error.isDone)
                        } finally { main.close(); summary.close(); other.close() }
                    }
                }
            } finally { bodies.forEach { it.release.countDown() } }
        }
    }

    @Test(timeout = 5_000) fun registrationRacingCancellationCancelsBeforeConnect() {
        MockWebServer().tracked().use { server ->
            server.start()
            AgentRequestControl().tracked().use { owner ->
                val probe = object : Probe() {
                    override fun callStart(call: Call) {
                        super.callStart(call)
                        // Existing listener runs before the added registration listener.
                        owner.cancel()
                    }
                }
                val client = client(probe)
                owner.newRequest(policy()).use { scope ->
                    val listener = StreamResult()
                    scope.bind { client.execute(request(server), listener) }
                    assertTrue(listener.error.get(2, TimeUnit.SECONDS) is CancellationException)
                    assertTrue(probe.call.get().isCanceled())
                    assertTrue(probe.finished.await(2, TimeUnit.SECONDS))
                    assertEquals(0, server.requestCount)
                }
            }
        }
    }

    @Test(timeout = 5_000) fun automaticFirstEventDeadlineCancelsActualSocket() {
        MockWebServer().tracked().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
            val probe = Probe()
            val client = client(probe)
            AgentRequestControl().tracked().use { owner ->
                owner.newRequest(AgentRequestTimeoutPolicy(2_000, 300, 300)).use { scope ->
                    val listener = StreamResult()
                    scope.bind { client.execute(request(server), listener) }
                    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    val error = listener.error.get(2, TimeUnit.SECONDS)
                    assertEquals(AgentRequestTimeoutKind.FIRST_EVENT, (error as AgentRequestTimeoutException).kind)
                    probe.assertCancelledAndReleased()
                }
            }
        }
    }

    @Test(timeout = 5_000) fun idleDeadlineAfterProgressCancelsActualSocketWithTimeoutReason() {
        assertProgressDeadlineClosesSocket(AgentRequestTimeoutKind.STREAM_IDLE)
    }

    @Test(timeout = 5_000) fun totalDeadlineCancelsActualSocketEvenAfterRecentProgress() {
        assertProgressDeadlineClosesSocket(AgentRequestTimeoutKind.TOTAL)
    }

    private fun assertProgressDeadlineClosesSocket(expected: AgentRequestTimeoutKind) {
        MockWebServer().tracked().use { server ->
            server.start()
            val body = HeldBody("data: progress\n\n")
            try {
                server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(body).build())
                val probe = Probe()
                val client = client(probe)
                val now = java.util.concurrent.atomic.AtomicLong()
                // Clock advancement and explicit checks avoid wall-clock sleeps for phase tests.
                val clock = AgentRequestClock { now.get() }
                val scheduler = AgentRequestScheduler { _, _ -> AutoCloseable {} }
                AgentRequestControl(clock, scheduler).tracked().use { owner ->
                    owner.newRequest(AgentRequestTimeoutPolicy(1_000, 100, 200)).use { scope ->
                        val listener = StreamResult { scope.onMeaningfulEvent() }
                        scope.bind { client.execute(request(server), listener) }
                        assertTrue(listener.firstEvent.await(2, TimeUnit.SECONDS))
                        if (expected == AgentRequestTimeoutKind.TOTAL) {
                            (100L..900L step 100).forEach { now.set(it); scope.onMeaningfulEvent() }
                            now.set(1_000)
                        } else {
                            now.set(200)
                        }
                        scope.checkTimeout()
                        assertEquals(expected, (listener.error.get(2, TimeUnit.SECONDS) as AgentRequestTimeoutException).kind)
                        probe.assertCancelledAndReleased()
                    }
                }
            } finally { body.release.countDown() }
        }
    }

    @Test(timeout = 5_000) fun builderKeepsHeadersBodyErrorsAndAndroidSseParsing() {
        MockWebServer().tracked().use { server ->
            server.start()
            val probe = Probe()
            val builder = AndroidCompatibleSseHttpClientBuilder(OkHttpClientBuilder()
                .okHttpClientBuilder(okhttp3.OkHttpClient.Builder().eventListener(probe)))
            assertNull(builder.connectTimeout())
            assertNull(builder.readTimeout())
            builder.connectTimeout(Duration.ofSeconds(2)).readTimeout(Duration.ofSeconds(2))
            val client = builder.build()
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json").body("{\"ok\":true}").build())
            val request = HttpRequest.builder().method(HttpMethod.POST).url(server.url("/").toString())
                .addHeader("x-session-affinity", "session-test").addHeader("X-Session-Id", "session-test")
                .addHeader("Authorization", "Bearer test").body("{\"input\":\"test\"}").build()
            assertEquals("{\"ok\":true}", client.execute(request).body())
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("session-test", recorded.headers["x-session-affinity"])
            assertEquals("session-test", recorded.headers["X-Session-Id"])
            assertEquals("Bearer test", recorded.headers["Authorization"])
            assertEquals("{\"input\":\"test\"}", recorded.body!!.utf8())
            server.enqueue(MockResponse.Builder().code(429).body("rate limit details").build())
            val error = assertThrows(HttpException::class.java) { client.execute(request) }
            assertEquals(429, error.statusCode())
            assertTrue(error.message!!.contains("rate limit details"))
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                .body(": heartbeat\n\nevent: delta\ndata: first\ndata: second\n\ndata: tail").build())
            val stream = StreamResult()
            builder.build().execute(request, stream)
            assertTrue(stream.closed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("first\nsecond", "tail"), stream.events)
            assertFalse(stream.error.isDone)
        }
    }

    private fun policy() = AgentRequestTimeoutPolicy(10_000)

    private fun client(probe: Probe) = client { probe }

    private fun client(listener: (Call) -> EventListener): HttpClient =
        AndroidCompatibleSseHttpClientBuilder(OkHttpClientBuilder().okHttpClientBuilder(
            okhttp3.OkHttpClient.Builder().eventListenerFactory(listener)
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false),
        )).build()

    private fun request(server: MockWebServer, path: String = "/") = HttpRequest.builder()
        .method(HttpMethod.GET).url(server.url(path).toString()).build()

    private open class Probe : EventListener() {
        val call = AtomicReference<Call>()
        val released = CountDownLatch(1)
        val finished = CountDownLatch(1)
        override fun callStart(call: Call) { this.call.set(call) }
        override fun connectionReleased(call: Call, connection: Connection) { released.countDown() }
        override fun callFailed(call: Call, ioe: IOException) { finished.countDown() }
        override fun callEnd(call: Call) { finished.countDown() }

        fun assertCancelledAndReleased() {
            assertTrue("real OkHttp Call was cancelled", call.get().isCanceled())
            assertTrue("connection released, not just callbacks suppressed", released.await(2, TimeUnit.SECONDS))
            assertTrue("real call terminated", finished.await(2, TimeUnit.SECONDS))
        }
    }

    private class StreamResult(private val onEvent: () -> Unit = {}) : ServerSentEventListener {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val firstEvent = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val error = CompletableFuture<Throwable>()
        override fun onEvent(event: ServerSentEvent, context: ServerSentEventContext) {
            events += event.data()
            onEvent()
            firstEvent.countDown()
        }
        override fun onError(error: Throwable) { this.error.complete(error) }
        override fun onClose() { closed.countDown() }
    }

    private class HeldBody(private val prefix: String, private val completeOnRelease: Boolean = false) : MockResponseBody {
        val written = CountDownLatch(1)
        val release = CountDownLatch(1)
        override val contentLength: Long = if (completeOnRelease) prefix.toByteArray().size + 1L else 100_000L
        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8(prefix).flush()
            written.countDown()
            check(release.await(3, TimeUnit.SECONDS)) { "test did not release response body" }
            if (completeOnRelease) sink.writeUtf8("\n").flush()
        }
    }
}

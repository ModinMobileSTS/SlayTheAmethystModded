package io.stamethyst.backend.resources

import io.stamethyst.backend.github.GithubRequestClients
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourcePackLinkSelectorTest {
    @Test
    fun graceWindowCollectsOtherReachableLinksInCompletionOrder() {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                first.start()
                second.start()
                first.enqueue(MockResponse.Builder().code(200).build())
                second.enqueue(MockResponse.Builder().code(200).build())
                val firstCompleted = CountDownLatch(1)
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    if (chain.request().url.port == second.port) {
                        assertTrue(firstCompleted.await(2, TimeUnit.SECONDS))
                    }
                    chain.proceed(chain.request())
                }.build()

                val results = ResourcePackLinkSelector.probe(
                    clients(client),
                    listOf(candidate(second), candidate(first)),
                    successGraceMillis = 1_000L,
                ) { _, done ->
                    if (done == 1) firstCompleted.countDown()
                }

                assertEquals(listOf(1, 0), results.map { it.candidateIndex })
                assertTrue(results.all { it.reachable })
            }
        }
    }

    @Test
    fun fastLinkStartsSelectionWithoutWaitingForFirstStalledLink() {
        MockWebServer().use { slow ->
            MockWebServer().use { fast ->
                slow.start()
                fast.start()
                slow.enqueue(MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).build())
                fast.enqueue(MockResponse.Builder().code(200).build())
                val slowStarted = CountDownLatch(1)
                val slowCancelled = CountDownLatch(1)
                val client = OkHttpClient.Builder()
                    .eventListener(object : EventListener() {
                        override fun requestHeadersEnd(call: Call, request: okhttp3.Request) {
                            if (request.url.port == slow.port) slowStarted.countDown()
                        }

                        override fun canceled(call: Call) {
                            if (call.request().url.port == slow.port) slowCancelled.countDown()
                        }
                    })
                    .addInterceptor { chain ->
                        if (chain.request().url.port == fast.port) {
                            assertTrue(slowStarted.await(2, TimeUnit.SECONDS))
                        }
                        chain.proceed(chain.request())
                    }
                    .build()
                val candidates = listOf(candidate(slow), candidate(fast))
                val callbackThread = Thread.currentThread()
                val completed = mutableListOf<Int>()
                val started = System.nanoTime()

                val results = ResourcePackLinkSelector.probe(clients(client), candidates) { result, _ ->
                    assertEquals(callbackThread, Thread.currentThread())
                    completed += result.candidateIndex
                }

                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                assertTrue("Selection took ${elapsedMs}ms", elapsedMs < 2_000L)
                assertEquals(listOf(1), completed)
                assertTrue(results.single().reachable)
                assertTrue(slowCancelled.await(1, TimeUnit.SECONDS))
                val ordered = ExternalResourcePackService.orderResourcePackDownloadCandidates(results, candidates)
                assertEquals(listOf(1, 0), ordered.map { it.candidateIndex })
            }
        }
    }

    @Test
    fun reusesHeadRangeMetadataWithoutAnotherHeadRequest() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().code(200)
                    .addHeader("Accept-Ranges", "bytes")
                    .addHeader("Content-Length", 8_000_000L)
                    .build()
            )
            val client = OkHttpClient()
            val results = ResourcePackLinkSelector.probe(clients(client), listOf(candidate(server)))
            val selected = ExternalResourcePackService.orderResourcePackDownloadCandidates(results).single()

            assertTrue(selected.rangeSupportProbed)
            assertEquals(8_000_000L, selected.rangeSupportedContentLength)
            assertEquals(8_000_000L, ExternalResourcePackService.fetchRangeSupportedContentLength(
                client, selected.requestUrl, selected
            ))
            assertEquals(1, server.requestCount)
            assertEquals("HEAD", server.takeRequest(1, TimeUnit.SECONDS)?.method)
        }
    }

    @Test
    fun headUnsupportedFallsBackToRangeAndReusesContentRange() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(405).build())
            server.enqueue(
                MockResponse.Builder().code(206)
                    .addHeader("Content-Range", "bytes 0-0/8000000")
                    .body("x")
                    .build()
            )
            val results = ResourcePackLinkSelector.probe(clients(OkHttpClient()), listOf(candidate(server)))
            val selected = ExternalResourcePackService.orderResourcePackDownloadCandidates(results).single()

            assertEquals(8_000_000L, selected.rangeSupportedContentLength)
            assertEquals("HEAD", server.takeRequest(1, TimeUnit.SECONDS)?.method)
            val range = server.takeRequest(1, TimeUnit.SECONDS)
            assertEquals("GET", range?.method)
            assertEquals("bytes=0-0", range?.headers?.get("Range"))
        }
    }

    @Test
    fun noRangeSupportIsRememberedWithoutRepeatingHead() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).build())
            val client = OkHttpClient()
            val results = ResourcePackLinkSelector.probe(clients(client), listOf(candidate(server)))
            val selected = ExternalResourcePackService.orderResourcePackDownloadCandidates(results).single()

            assertTrue(selected.rangeSupportProbed)
            assertNull(ExternalResourcePackService.fetchRangeSupportedContentLength(client, selected.requestUrl, selected))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun ignoredRangeOrInvalidContentRangeDoesNotEnableChunking() {
        for ((status, contentRange) in listOf(200 to "bytes 0-0/8000000", 206 to "bytes 1-1/8000000")) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().code(501).build())
                server.enqueue(MockResponse.Builder().code(status).addHeader("Content-Range", contentRange).body("x").build())

                val result = ResourcePackLinkSelector.probe(clients(OkHttpClient()), listOf(candidate(server))).single()

                assertTrue(result.reachable)
                assertTrue(result.rangeSupportProbed)
                assertNull(result.rangeSupportedContentLength)
            }
        }
    }

    @Test
    fun failedProbesKeepClassifiedErrorsAndDoNotDiscardFallbacks() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(404).build())
            server.enqueue(MockResponse.Builder().code(503).build())
            val candidates = listOf(candidate(server))

            val results = ResourcePackLinkSelector.probe(clients(OkHttpClient()), candidates)

            assertFalse(results.single().reachable)
            assertEquals("HTTP 503", results.single().error?.message)
            assertEquals("HTTP 404", results.single().error?.suppressed?.single()?.message)
            assertEquals(1, ExternalResourcePackService.orderResourcePackDownloadCandidates(results, candidates).size)
        }
    }

    @Test
    fun noReachableLinkIsBoundedByOverallDeadline() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).build())
            val candidates = listOf(candidate(server))
            val started = System.nanoTime()

            val results = ResourcePackLinkSelector.probe(clients(OkHttpClient()), candidates, timeoutMillis = 200L)

            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("Selection took ${elapsedMs}ms", elapsedMs < 1_500L)
            assertTrue(results.none { it.reachable })
            assertEquals(1, server.requestCount)
            assertEquals(1, ExternalResourcePackService.orderResourcePackDownloadCandidates(results, candidates).size)
        }
    }

    @Test
    fun interruptingSelectionCancelsRequestsAndPreservesInterruptFlag() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).build())
            val started = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val interrupted = AtomicBoolean(false)
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun requestHeadersEnd(call: Call, request: okhttp3.Request) = started.countDown()
                override fun canceled(call: Call) = cancelled.countDown()
            }).build()
            val executor = Executors.newSingleThreadExecutor()
            val future = executor.submit {
                try {
                    ResourcePackLinkSelector.probe(clients(client), listOf(candidate(server)))
                } catch (_: IOException) {
                    interrupted.set(Thread.currentThread().isInterrupted)
                } finally {
                    finished.countDown()
                }
            }
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS))
                future.cancel(true)
                assertTrue(finished.await(1, TimeUnit.SECONDS))
                assertTrue(interrupted.get())
                assertTrue(cancelled.await(1, TimeUnit.SECONDS))
            } finally {
                future.cancel(true)
                executor.shutdownNow()
            }
        }
    }

    private fun clients(client: OkHttpClient) = GithubRequestClients(client, client)

    private fun candidate(server: MockWebServer) =
        ExternalResourcePackService.ConfiguredResourcePackDownloadCandidate(
            displayName = "test-${server.port}",
            requestUrl = server.url("/resources.zip").toString(),
            usesGithubAcceleration = false,
            preferredMirrorSource = null,
        )
}

package io.stamethyst.backend.github

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRequestControlTest {
    @Test
    fun registeringAfterCancellationCancelsCallImmediately() {
        val control = HttpRequestControl(6_000L)
        control.cancel()
        val call = OkHttpClient().newCall(Request.Builder().url("https://resource.example.test/").build())

        assertTrue(runCatching { control.register(call) }.exceptionOrNull() is IOException)
        assertTrue(call.isCanceled())
    }

    @Test
    fun cancellingProbeAlsoCancelsNestedWattCallWithoutOfficialRetry() {
        MockWebServer().use { forward ->
            forward.start()
            forward.enqueue(MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).build())
            val cancelled = CountDownLatch(1)
            val directClient = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) = cancelled.countDown()
            }).build()
            val officialAttempts = AtomicInteger()
            val client = forwardedClient(forward, directClient, officialAttempts)
            val control = HttpRequestControl(6_000L)
            val request = Request.Builder().url("https://resource.example.test/resources.zip")
                .head().tag(HttpRequestControl::class.java, control).build()
            val call = client.newCall(request)
            control.register(call)
            val executor = Executors.newSingleThreadExecutor()
            val future = executor.submit<Throwable?> {
                runCatching { call.execute().use { } }.exceptionOrNull()
            }
            try {
                assertNotNull(forward.takeRequest(2, TimeUnit.SECONDS))
                control.cancel()

                assertTrue(future.get(2, TimeUnit.SECONDS) is IOException)
                assertTrue(cancelled.await(1, TimeUnit.SECONDS))
                assertEquals(0, officialAttempts.get())
                assertEquals(1, forward.requestCount)
            } finally {
                control.cancel()
                future.cancel(true)
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun probeDeadlineAppliesToNestedWattCall() {
        MockWebServer().use { forward ->
            forward.start()
            forward.enqueue(MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).build())
            val officialAttempts = AtomicInteger()
            val client = forwardedClient(forward, OkHttpClient(), officialAttempts)
            val control = HttpRequestControl(200L)
            val request = Request.Builder().url("https://resource.example.test/resources.zip")
                .head().tag(HttpRequestControl::class.java, control).build()
            val call = client.newCall(request)
            control.register(call)
            val executor = Executors.newSingleThreadExecutor()
            val future = executor.submit<Throwable?> {
                runCatching { call.execute().use { } }.exceptionOrNull()
            }
            try {
                assertTrue(future.get(2, TimeUnit.SECONDS) is IOException)
                assertEquals(0, officialAttempts.get())
            } finally {
                control.cancel()
                future.cancel(true)
                executor.shutdownNow()
            }
        }
    }

    private fun forwardedClient(
        server: MockWebServer,
        directClient: OkHttpClient,
        officialAttempts: AtomicInteger,
    ): OkHttpClient {
        val profile = WattToolkitRouteProfile(
            name = "resource-test",
            cacheFileName = "unused.json",
            supportedHosts = setOf("resource.example.test"),
            bootstrapForwardTargets = emptyList(),
        )
        val cached = PersistedWattToolkitGithubRoute(
            route = WattToolkitGithubRoute(
                logicalHosts = profile.supportedHosts,
                forwardTargets = listOf(server.url("/").toString()),
            ),
            cachedAtMs = 1_000L,
        )
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = profile,
            routeStore = object : WattToolkitGithubRouteStore {
                override fun load() = cached
                override fun save(route: PersistedWattToolkitGithubRoute) = Unit
                override fun clear() = Unit
            },
            nowProvider = { 2_000L },
            backgroundExecutor = Executor { },
        )
        return OkHttpClient.Builder()
            .addInterceptor(ExperimentalGithubDirectAccessInterceptor(listOf(resolver), directClient))
            .addInterceptor {
                officialAttempts.incrementAndGet()
                throw IOException("Unexpected official retry")
            }
            .build()
    }
}

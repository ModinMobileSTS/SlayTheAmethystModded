package io.stamethyst.backend.steamcloud

import io.stamethyst.backend.github.AcceleratedResponseValidator
import io.stamethyst.backend.github.ExperimentalGithubDirectAccessRuntime
import io.stamethyst.backend.github.WattToolkitForwardTargetProbe
import io.stamethyst.backend.github.WattToolkitGithubRouteResolver
import io.stamethyst.backend.github.WattToolkitRouteProfile
import io.stamethyst.backend.github.withWattRouting
import io.stamethyst.backend.network.AccelerationStrategy
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real routed Calls and loopback rule HTTP, with deterministic node IO, probes and clock. */
class SteamBundledHopRecoveryTest {
    @Test
    fun bothPathsFail_rediscoversAndReusesAWorkingNodeWithoutWaitingForCooldown() {
        Fixture().use { fixture ->
            fixture.enqueueRoutes()

            assertEquals("alternate", fixture.fetch())
            assertEquals("alternate", fixture.fetch())

            assertEquals(listOf("bundled", "official", "alternate", "alternate"), fixture.attempts)
            assertEquals(1, fixture.directory.requestCount)
            assertEquals("known-failed official path must not be probed", 0, fixture.officialProbes.get())
        }
    }

    @Test
    fun officialFailsDuringBundledCooldown_rediscoversInsteadOfStayingOnOfficial() {
        Fixture().use { fixture ->
            fixture.officialFails = false
            assertEquals("official", fixture.fetch())
            assertEquals(0, fixture.directory.requestCount)

            fixture.officialFails = true
            fixture.enqueueRoutes()
            assertEquals("alternate", fixture.fetch())

            assertEquals(listOf("bundled", "official", "official", "alternate"), fixture.attempts)
            assertEquals(1, fixture.directory.requestCount)
        }
    }

    @Test
    fun rediscoveredNodeAlsoFails_requestStopsWithoutReplayingFailedPaths() {
        Fixture().use { fixture ->
            fixture.alternateFails = true
            fixture.enqueueRoutes()

            assertTrue(runCatching { fixture.fetch() }.exceptionOrNull() is IOException)

            assertEquals(listOf("bundled", "official", "alternate"), fixture.attempts)
            assertEquals(1, fixture.directory.requestCount)
        }
    }

    @Test
    fun unavailableRuleService_requestFailsButTheNextRequestCanDiscoverARecoveredNode() {
        Fixture().use { fixture ->
            repeat(5) { fixture.directory.enqueue(MockResponse.Builder().code(503).build()) }

            assertTrue(runCatching { fixture.fetch() }.exceptionOrNull() is IOException)
            assertEquals(listOf("bundled", "official"), fixture.attempts)
            assertEquals(5, fixture.directory.requestCount)

            fixture.enqueueRoutes()
            assertEquals("alternate", fixture.fetch())
            assertEquals(listOf("bundled", "official", "alternate"), fixture.attempts)
            assertEquals(6, fixture.directory.requestCount)
        }
    }

    @Test
    fun officialRecovers_resumesNormalCooldownWithoutFurtherDiscovery() {
        Fixture().use { fixture ->
            fixture.enqueueRoutes()
            assertEquals("alternate", fixture.fetch())

            fixture.alternateFails = true
            fixture.officialFails = false
            assertEquals("official", fixture.fetch())
            assertEquals("official", fixture.fetch())

            assertEquals(listOf("bundled", "official", "alternate", "alternate", "official", "official"), fixture.attempts)
            assertEquals(1, fixture.directory.requestCount)
        }
    }

    @Test
    fun rejectedOfficialResponseAlsoTriggersRediscovery() {
        Fixture().use { fixture ->
            fixture.officialFails = false
            fixture.enqueueRoutes()
            val request = fixture.request.newBuilder()
                .tag(AcceleratedResponseValidator::class.java, AcceleratedResponseValidator { response ->
                    if (response.peekBody(64L).string() == "official") throw IOException("invalid official response")
                })
                .build()

            fixture.transport.newCall(request).execute().use {
                assertEquals(200, it.code)
                assertEquals("alternate", it.body.string())
            }

            assertEquals(listOf("bundled", "official", "alternate"), fixture.attempts)
            assertEquals(1, fixture.directory.requestCount)
        }
    }

    @Test
    fun recoveryExpires_bundledHopGetsAnotherChanceAfterCooldown() {
        Fixture().use { fixture ->
            fixture.enqueueRoutes()
            assertEquals("alternate", fixture.fetch())

            fixture.bundledFails = false
            fixture.now.addAndGet(TimeUnit.MINUTES.toMillis(31))
            assertEquals("bundled", fixture.fetch())

            assertEquals(listOf("bundled", "official", "alternate", "bundled"), fixture.attempts)
            assertEquals(1, fixture.directory.requestCount)
        }
    }

    @Test
    fun officialFailureOnMutation_doesNotRediscoverOrReplayTheRequest() {
        Fixture().use { fixture ->
            fixture.resolver.recordFailedForwardTargets("steamcommunity.com", listOf(Fixture.BUNDLED_TARGET))
            val mutation = fixture.request.newBuilder().post("update".toRequestBody()).build()

            assertTrue(runCatching { fixture.transport.newCall(mutation).execute().close() }.exceptionOrNull() is IOException)

            assertEquals(listOf("official"), fixture.attempts)
            assertEquals(0, fixture.directory.requestCount)
        }
    }

    @Test
    fun recoveryDiscoveryRemainsWithinTheOriginalCallDeadline() {
        Fixture().use { fixture ->
            fixture.directory.enqueue(MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).build())
            val transport = fixture.transport.configure { callTimeout(300, TimeUnit.MILLISECONDS) }
            val started = System.nanoTime()

            val error = runCatching { transport.newCall(fixture.request).execute().close() }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(error!!.message.orEmpty().contains("timeout"))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
            assertEquals(listOf("bundled", "official"), fixture.attempts)
            assertEquals(1, fixture.directory.requestCount)
        }
    }

    private class Fixture : AutoCloseable {
        val directory = MockWebServer().apply { start() }
        val now = AtomicLong(1_000L)
        val attempts = mutableListOf<String>()
        val officialProbes = AtomicInteger()
        var bundledFails = true
        var officialFails = true
        var alternateFails = false
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = WattToolkitRouteProfile(
                name = "steam-recovery",
                cacheFileName = "unused.json",
                supportedHosts = setOf("steamcommunity.com"),
                bootstrapForwardTargets = listOf(BUNDLED_TARGET),
                bootstrapSupportedHosts = setOf("steamcommunity.com"),
            ),
            projectGroupsUrl = directory.url("/accelerator/projectgroups"),
            forwardTargetProbe = { WattToolkitForwardTargetProbe(successes = 1, attempts = 1, latencyMs = 1L) },
            officialTargetProbe = { _, _ ->
                officialProbes.incrementAndGet()
                WattToolkitForwardTargetProbe(successes = 1, attempts = 1, latencyMs = 0L)
            },
            nowProvider = now::get,
            sleepProvider = {},
            backgroundExecutor = Executor { },
            accelerationStrategyProvider = { AccelerationStrategy.RMBGAME_FIRST },
        )
        private val officialClient = OkHttpClient.Builder().addInterceptor { chain ->
            attempts += "official"
            if (officialFails) throw IOException("official unavailable")
            response(chain.request(), "official")
        }.build()
        private val forwardClient = OkHttpClient.Builder().addInterceptor { chain ->
            val (name, fails) = when (val host = chain.request().url.host) {
                "bundled.test" -> "bundled" to bundledFails
                "alternate.test" -> "alternate" to alternateFails
                else -> throw AssertionError("Unexpected forward host: $host")
            }
            attempts += name
            if (fails) throw IOException("$name unavailable")
            response(chain.request(), name)
        }.build()
        val transport = officialClient.withWattRouting(
            ExperimentalGithubDirectAccessRuntime(listOf(resolver), officialClient.hostnameVerifier, forwardClient),
        )
        val request = Request.Builder().url("https://steamcommunity.com/workshop/browse/").build()

        fun enqueueRoutes() {
            directory.enqueue(MockResponse.Builder().body(
                """
                {"🦓":[{"Items":[{
                    "MatchDomainNames":"steamcommunity.com",
                    "ForwardDomainNames":"$BUNDLED_TARGET;https://alternate.test",
                    "ProxyType":0,
                    "Checked":true
                }]}]}
                """.trimIndent(),
            ).build())
        }

        fun fetch(): String = transport.newCall(request).execute().use { it.body.string() }

        override fun close() {
            transport.cancelAll()
            directory.close()
        }

        private fun response(request: Request, body: String): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody())
            .build()

        companion object {
            const val BUNDLED_TARGET = "https://bundled.test"
        }
    }
}

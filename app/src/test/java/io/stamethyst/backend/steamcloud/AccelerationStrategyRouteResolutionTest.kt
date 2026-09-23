package io.stamethyst.backend.steamcloud

import io.stamethyst.backend.github.WattToolkitForwardTargetProbe
import io.stamethyst.backend.github.WattToolkitGithubRouteResolver
import io.stamethyst.backend.github.WattToolkitRouteProfile
import io.stamethyst.backend.network.AccelerationStrategy
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers [AccelerationStrategy.RMBGAME_FIRST]: the bundled rmbgame.net hop is used directly
 * with zero Watt rule traffic and zero probing, and the official origin takes over once the
 * hop has failed real traffic.
 */
class AccelerationStrategyRouteResolutionTest {
    private lateinit var apiServer: MockWebServer
    private val probeCalls = AtomicInteger(0)

    private val communityBundleTarget = "https://steamcommunity.rmbgame.net"

    @Before
    fun setUp() {
        apiServer = MockWebServer()
        apiServer.start()
    }

    @After
    fun tearDown() {
        apiServer.close()
    }

    private fun resolver(
        strategy: AccelerationStrategy,
        routeProfile: WattToolkitRouteProfile = SteamCommunityWattToolkitRouteProfile,
        nowProvider: () -> Long = { 1_000L },
    ) = WattToolkitGithubRouteResolver(
        routeProfile = routeProfile,
        client = OkHttpClient.Builder().build(),
        projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
        forwardTargetProbe = {
            probeCalls.incrementAndGet()
            WattToolkitForwardTargetProbe(successes = 1, attempts = 1, latencyMs = 1L)
        },
        officialTargetProbe = { _, _ ->
            probeCalls.incrementAndGet()
            WattToolkitForwardTargetProbe.failed()
        },
        nowProvider = nowProvider,
        sleepProvider = {},
        backgroundExecutor = Executor { /* no background work in this unit test */ },
        accelerationStrategyProvider = { strategy },
    )

    @Test
    fun bundledHopStrategy_usesRmbgameHopWithoutRuleFetchOrProbe() {
        val route = resolver(AccelerationStrategy.RMBGAME_FIRST)
            .resolveRouteForHost("steamcommunity.com")

        assertNotNull(route)
        assertEquals(listOf(communityBundleTarget), route!!.forwardTargets)
        assertFalse(route.isOfficial)
        assertEquals("no Watt rule discovery", 0, apiServer.requestCount)
        assertEquals("no hop probing", 0, probeCalls.get())
    }

    @Test
    fun bundledHopStrategy_coversSubdomainFamiliesWithTheBundledHop() {
        val route = resolver(
            strategy = AccelerationStrategy.RMBGAME_FIRST,
            routeProfile = SteamImageCdnWattToolkitRouteProfile,
        ).resolveRouteForHost("avatars.steamstatic.com")

        assertNotNull(route)
        assertEquals(listOf("https://steamimage.rmbgame.net"), route!!.forwardTargets)
        assertFalse(route.isOfficial)
        assertTrue("suffix host is still forwarded", route.matchesLogicalHost("avatars.steamstatic.com"))
        assertEquals(0, apiServer.requestCount)
        assertEquals(0, probeCalls.get())
    }

    @Test
    fun bundledHopStrategy_validatesCommunityHopAgainstItsAkamaiEdgeName() {
        val route = resolver(AccelerationStrategy.RMBGAME_FIRST)
            .resolveRouteForHost("steamcommunity.com")!!

        assertEquals("steamstore-a.akamaihd.net", route.fakeServerName)
        assertFalse(route.ignoreSslCertVerification)
        assertEquals(
            "the Akamai edge name must resolve to the rmbgame hop",
            "steamcommunity.rmbgame.net",
            route.forwardHosts.first(),
        )
        assertEquals(
            "steamstore-a.akamaihd.net",
            route.buildForwardedUrl("https://steamcommunity.com/workshop/browse/".toHttpUrl()).host,
        )
    }

    @Test
    fun bundledHopStrategy_relaxesValidationForHopsWattMarksUnsafe() {
        val resolver = resolver(
            strategy = AccelerationStrategy.RMBGAME_FIRST,
            routeProfile = SteamImageCdnWattToolkitRouteProfile,
        )
        val route = resolver.resolveRouteForHost("cdn.akamai.steamstatic.com")!!

        assertTrue(route.ignoreSslCertVerification)
        assertTrue(
            "certificate validation must be relaxed for the rmbgame hop itself",
            resolver.allowsUnsafeHostnameBypass("steamimage.rmbgame.net"),
        )
    }

    @Test
    fun bestPathStrategy_doesNotRelaxValidationWithoutARuleThatSaysSo() {
        val resolver = resolver(
            strategy = AccelerationStrategy.BEST_PATH,
            routeProfile = SteamImageCdnWattToolkitRouteProfile,
        )

        assertFalse(resolver.allowsUnsafeHostnameBypass("steamimage.rmbgame.net"))
    }

    @Test
    fun bundledHopStrategy_returnsOfficialAfterBundleHopFailedRealTraffic() {
        val resolver = resolver(AccelerationStrategy.RMBGAME_FIRST)
        assertEquals(
            listOf(communityBundleTarget),
            resolver.resolveRouteForHost("steamcommunity.com")!!.forwardTargets,
        )

        resolver.recordFailedForwardTargets(
            host = "steamcommunity.com",
            forwardTargets = listOf(communityBundleTarget),
        )

        val fallback = resolver.resolveRouteForHost("steamcommunity.com")
        assertNotNull(fallback)
        assertTrue(fallback!!.isOfficial)
        assertTrue(fallback.forwardTargets.isEmpty())
        assertEquals(0, apiServer.requestCount)
    }

    @Test
    fun bundledHopStrategy_retriesBundleHopAfterFailureMarkExpires() {
        val now = AtomicInteger(1_000)
        val resolver = resolver(
            strategy = AccelerationStrategy.RMBGAME_FIRST,
            nowProvider = { now.get().toLong() },
        )
        resolver.recordFailedForwardTargets(
            host = "steamcommunity.com",
            forwardTargets = listOf(communityBundleTarget),
        )
        assertTrue(resolver.resolveRouteForHost("steamcommunity.com")!!.isOfficial)

        // Grind past the failed-hop TTL; the bundled hop is worth one more attempt.
        now.addAndGet(31 * 60 * 1_000)
        assertEquals(
            listOf(communityBundleTarget),
            resolver.resolveRouteForHost("steamcommunity.com")!!.forwardTargets,
        )
    }

    @Test
    fun bundledHopStrategy_confirmSuccessfulForwardTargetClearsFailureMark() {
        val resolver = resolver(AccelerationStrategy.RMBGAME_FIRST)
        resolver.recordFailedForwardTargets(
            host = "steamcommunity.com",
            forwardTargets = listOf(communityBundleTarget),
        )

        resolver.confirmSuccessfulForwardTarget("steamcommunity.com", communityBundleTarget)

        assertEquals(
            listOf(communityBundleTarget),
            resolver.resolveRouteForHost("steamcommunity.com")!!.forwardTargets,
        )
    }

    @Test
    fun bundledHopStrategy_refreshReturnsNullWhenBundleHopWasExcluded() {
        val resolver = resolver(AccelerationStrategy.RMBGAME_FIRST)

        val refreshed = resolver.refreshRouteForHost(
            host = "steamcommunity.com",
            excludedForwardTargets = listOf(communityBundleTarget),
        )

        assertNull(refreshed)
        assertEquals(0, apiServer.requestCount)
    }

    @Test
    fun bundledHopStrategy_profileWithoutBundleHopKeepsRuleDiscovery() {
        apiServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "🦓": [
                        {
                          "Items": [
                            {
                              "MatchDomainNames": "steamserver.net",
                              "ListenDomainNames": "steamserver.net",
                              "ForwardDomainNames": "cm-node.test",
                              "ProxyType": 0,
                              "Checked": true
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val route = resolver(
            strategy = AccelerationStrategy.RMBGAME_FIRST,
            routeProfile = SteamCmWattToolkitRouteProfile,
        ).resolveRouteForHost("steamserver.net")

        assertNotNull(route)
        assertEquals(listOf("cm-node.test"), route!!.forwardTargets)
        assertEquals("Steam CM has no bundled hop, so rules are still fetched", 1, apiServer.requestCount)
    }

    @Test
    fun bestPathStrategy_stillRankedDiscoversAndProbes() {
        apiServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "🦓": [
                        {
                          "Items": [
                            {
                              "MatchDomainNames": "steamcommunity.com",
                              "ListenDomainNames": "steamcommunity.com",
                              "ForwardDomainNames": "community-node.test",
                              "ProxyType": 0,
                              "Checked": true
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val route = resolver(AccelerationStrategy.BEST_PATH)
            .resolveRouteForHost("steamcommunity.com")

        assertNotNull(route)
        assertEquals(
            listOf("community-node.test", communityBundleTarget),
            route!!.forwardTargets,
        )
        assertEquals(1, apiServer.requestCount)
        assertTrue("legacy path probes candidate hops", probeCalls.get() > 0)
    }
}

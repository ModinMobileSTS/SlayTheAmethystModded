package io.stamethyst.backend.workshop

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.stamethyst.backend.github.TestRouting
import io.stamethyst.backend.github.routing
import io.stamethyst.backend.github.NoOpWattToolkitGithubRouteStore
import io.stamethyst.backend.github.PersistedWattToolkitGithubRoute
import io.stamethyst.backend.github.WattToolkitForwardTargetProbe
import io.stamethyst.backend.github.WattToolkitGithubRoute
import io.stamethyst.backend.github.WattToolkitGithubRouteResolver
import io.stamethyst.backend.github.WattToolkitGithubRouteStore
import io.stamethyst.backend.network.AccelerationStrategy
import io.stamethyst.backend.steamcloud.SteamCommunityWattToolkitRouteProfile
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Offline simulation of the Steam Workshop market load under an unstable player network.
 *
 * The live test in `SteamCommunityMarketNetworkAcceptanceTest` can only tell us what Watt and
 * Steam happen to do on the current machine. These fixtures instead inject deterministic
 * failures at the same production seams the market uses:
 *
 * `WorkshopService.browse()` reads the HTML through [io.stamethyst.backend.github.RoutedHttpTransport],
 * which chooses Watt forward targets with [WattToolkitGithubRouteResolver]. Every fault below is a
 * local MockWebServer behaviour, so the matrix needs no device, account, proxy or internet.
 *
 * Scenarios cover both node failures (DNS, refused, reset, blackhole, 403, broken TLS) and player
 * network transitions: switching Wi-Fi to mobile invalidates the current hop, a stale persisted
 * route cache must be refreshed on cold start, and a connection that drops mid-page must not be
 * mistaken for an empty market. Both acceleration strategies get coverage: the production-default
 * bundled `rmbgame` hop and the Watt rule-discovery [AccelerationStrategy.BEST_PATH] fallback.
 * A VPN/policy switch that disables acceleration mid-session must move later requests to the
 * official origin without restarting the market screen.
 *
 * The test uses short OkHttp timeouts (hundreds of milliseconds) instead of production's
 * 15s/60s so a blackholed node fails quickly. The failover/fallback logic under test is the
 * production one; only the fault durations are shortened.
 */
class WorkshopMarketNetworkSimulationTest {
    private lateinit var rootDir: File

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("workshop-market-network-simulation").toFile()
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun healthyNetwork_loadsMarketThroughWattNode() = fixture().use { fixture ->
        val healthy = fixture.node { okMarket() }
        fixture.routeTargets = listOf(healthy.target)

        val result = fixture.browse()

        assertEquals(listOf(SIMULATED_MOD_TITLE), result.items.map { it.title })
        assertEquals(1, healthy.requestCount())
        assertEquals(0, fixture.officialRequestCount())
    }

    @Test
    fun firstNodeRefusesConnection_failsOverToHealthyNode() = fixture().use { fixture ->
        val healthy = fixture.node { okMarket() }
        fixture.routeTargets = listOf(REFUSED_TARGET, healthy.target)

        val result = fixture.browseWithProgress()

        assertEquals(listOf(SIMULATED_MOD_TITLE), result.items.map { it.title })
        assertEquals(1, healthy.requestCount())
        assertEquals(0, fixture.officialRequestCount())
        // The player must see "retrying another node", not a frozen loading bar.
        assertTrue(
            "failover must be narrated to the market UI: ${fixture.phases()}",
            fixture.phases().contains(WorkshopLoadPhase.FailingOver),
        )
        assertFalse(fixture.phases().contains(WorkshopLoadPhase.Failed))
    }

    @Test
    fun firstNodeDnsFailure_failsOverToHealthyNode() = fixture().use { fixture ->
        val healthy = fixture.node { okMarket() }
        fixture.routeTargets = listOf("http://unresolvable.invalid:443", healthy.target)

        val result = fixture.browse()

        assertEquals(listOf(SIMULATED_MOD_TITLE), result.items.map { it.title })
        assertEquals(1, healthy.requestCount())
        assertEquals(0, fixture.officialRequestCount())
    }

    @Test
    fun firstNodeResetsConnection_failsOverToHealthyNode() = fixture().use { fixture ->
        val resetting = fixture.node {
            MockResponse.Builder()
                .onResponseStart(SocketEffect.CloseSocket())
                .build()
        }
        val healthy = fixture.node { okMarket() }
        fixture.routeTargets = listOf(resetting.target, healthy.target)

        val result = fixture.browse()

        assertEquals(listOf(SIMULATED_MOD_TITLE), result.items.map { it.title })
        assertEquals(1, healthy.requestCount())
        assertEquals(0, fixture.officialRequestCount())
    }

    @Test
    fun firstNodeBlackholes_timesOutAndFailsOver() = fixture().use { fixture ->
        val blackholed = fixture.node {
            MockResponse.Builder()
                .onResponseStart(SocketEffect.Stall)
                .build()
        }
        val healthy = fixture.node { okMarket() }
        fixture.routeTargets = listOf(blackholed.target, healthy.target)

        val startedAtNs = System.nanoTime()
        val result = fixture.browse()
        val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L

        assertEquals(listOf(SIMULATED_MOD_TITLE), result.items.map { it.title })
        assertEquals(1, healthy.requestCount())
        assertTrue("blackholed node must actually be attempted", blackholed.requestCount() >= 1)
        assertTrue("failover should stay bounded, took ${elapsedMs}ms", elapsedMs < 5_000L)
    }

    @Test
    fun firstNodeRejectsWorkshopBrowse_failsOverAfterRouteRefresh() = fixture().use { fixture ->
        val rejecting = fixture.node {
            MockResponse.Builder()
                .code(403)
                .body("this Watt hop rejects the logical Host")
                .build()
        }
        val healthy = fixture.node { okMarket() }
        fixture.routeTargets = listOf(rejecting.target, healthy.target)

        val result = fixture.browse()

        assertEquals(listOf(SIMULATED_MOD_TITLE), result.items.map { it.title })
        assertEquals(1, rejecting.requestCount())
        assertEquals(1, healthy.requestCount())
        assertTrue("a rejected hop must be replaced by route refresh", fixture.routeRequestCount() >= 2)
        assertEquals(0, fixture.officialRequestCount())
    }

    @Test
    fun everyWattNodeFails_fallsBackToOfficialOrigin() = fixture().use { fixture ->
        val reset = fixture.node {
            MockResponse.Builder()
                .onResponseStart(SocketEffect.CloseSocket())
                .build()
        }
        val resetAgain = fixture.node {
            MockResponse.Builder()
                .onResponseStart(SocketEffect.CloseSocket())
                .build()
        }
        fixture.routeTargets = listOf(reset.target, resetAgain.target)
        fixture.officialServer.enqueue(okMarket())

        val result = fixture.browse()

        assertEquals(listOf(SIMULATED_MOD_TITLE), result.items.map { it.title })
        assertEquals(1, fixture.officialRequestCount())
    }

    @Test
    fun routeDiscoveryOutage_stillFallsBackToOfficialOrigin() = fixture(
        routeResponse = { MockResponse.Builder().code(500).body("Watt directory unavailable").build() },
    ).use { fixture ->
        fixture.officialServer.enqueue(okMarket())

        val result = fixture.browse()

        assertEquals(listOf(SIMULATED_MOD_TITLE), result.items.map { it.title })
        assertEquals(1, fixture.officialRequestCount())
        assertTrue("route discovery must be retried before giving up", fixture.routeRequestCount() >= 2)
    }

    @Test
    fun slowButUsableNode_isNotMistakenForFailure() = fixture().use { fixture ->
        val slow = fixture.node {
            MockResponse.Builder()
                .code(200)
                .body(marketHtml())
                .bodyDelay(350, TimeUnit.MILLISECONDS)
                .build()
        }
        fixture.routeTargets = listOf(slow.target)

        val result = fixture.browse()

        assertEquals(listOf(SIMULATED_MOD_TITLE), result.items.map { it.title })
        assertEquals(0, fixture.officialRequestCount())
    }

    @Test
    fun captivePortal_isReportedAsNetworkLoginProblemNotAnEmptyMarket() = fixture().use { fixture ->
        val portal = fixture.node {
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <html>
                      <head><title>WiFi login required</title></head>
                      <body>Please sign in to continue.</body>
                    </html>
                    """.trimIndent(),
                )
                .build()
        }
        fixture.routeTargets = listOf(portal.target)

        val error = runCatching { fixture.browse() }.exceptionOrNull()

        assertNotNull("captive portal must not look like an empty market", error)
        assertTrue(
            "unexpected captive portal error: ${error!!.message}",
            error.message.orEmpty().contains("认证页面"),
        )
        assertEquals(0, fixture.officialRequestCount())
    }

    @Test
    fun allRoutesDown_failsWithinBoundedTimeInsteadOfHanging() = fixture(
        routeResponse = { MockResponse.Builder().code(500).build() },
    ).use { fixture ->
        fixture.officialServer.enqueue(MockResponse.Builder().code(503).build())

        val startedAtNs = System.nanoTime()
        val error = runCatching { fixture.browse() }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L

        assertNotNull("all routes down must fail instead of returning a fake market", error)
        assertTrue("failure should stay bounded, took ${elapsedMs}ms", elapsedMs < 5_000L)
    }

    @Test
    fun networkRecovers_retryLoadsMarketWithoutStayingOnFailedRoutes() {
        var routeFailuresRemaining = 5
        fixture(
            routeResponse = {
                if (routeFailuresRemaining-- > 0) {
                    MockResponse.Builder().code(500).build()
                } else {
                    MockResponse.Builder().code(200).body(routePayload()).build()
                }
            },
        ).use { fixture ->
            val healthy = fixture.node { okMarket() }
            fixture.routeTargets = listOf(healthy.target)
            // The first attempt also loses the official origin, as during a real outage.
            fixture.officialServer.enqueue(MockResponse.Builder().code(503).build())

            val firstAttempt = runCatching { fixture.browse() }
            assertTrue("first attempt during the outage must fail", firstAttempt.isFailure)

            // The node and the Watt directory are reachable again on the next user retry.
            val recovered = fixture.browse()

            assertEquals(listOf(SIMULATED_MOD_TITLE), recovered.items.map { it.title })
            assertEquals(1, healthy.requestCount())
        }
    }

    @Test
    fun networkSwitchInvalidatesOldNode_refreshesToNewRoute() = fixture().use { fixture ->
        var oldNodeResponder: () -> MockResponse = { okMarket() }
        val oldNode = fixture.node { oldNodeResponder() }
        val newNode = fixture.node { okMarket() }
        fixture.routeTargets = listOf(oldNode.target)

        // The first page confirms the Wi-Fi route as the current best path.
        assertEquals(1, fixture.browse().items.size)
        assertEquals(1, oldNode.requestCount())

        // The player switches to mobile: the old hop now resets, and Watt publishes a new hop.
        oldNodeResponder = { resetConnection() }
        fixture.routeTargets = listOf(newNode.target)

        val switched = fixture.browse()

        assertEquals(listOf(SIMULATED_MOD_TITLE), switched.items.map { it.title })
        assertEquals(1, newNode.requestCount())
        assertTrue("the stale Wi-Fi hop must be attempted and rejected", oldNode.requestCount() >= 2)
    }

    @Test
    fun coldStartWithStaleRouteCache_recoversAfterNetworkSwitch() {
        val routeStore = MemoryRouteStore()
        fixture(routeStore = routeStore).use { fixture ->
            val staleNode = fixture.node { resetConnection() }
            val newNode = fixture.node { okMarket() }
            routeStore.persisted = PersistedWattToolkitGithubRoute(
                route = WattToolkitGithubRoute(
                    logicalHosts = setOf("steamcommunity.com", "www.steamcommunity.com"),
                    forwardTargets = listOf(staleNode.target),
                ),
                cachedAtMs = 1_000L,
            )
            fixture.routeTargets = listOf(newNode.target)

            val result = fixture.browse()

            assertEquals(listOf(SIMULATED_MOD_TITLE), result.items.map { it.title })
            assertEquals(1, newNode.requestCount())
            assertTrue("the dead cached route must be attempted first", staleNode.requestCount() >= 1)
            val persistedTargets = routeStore.persisted?.route?.forwardTargets.orEmpty()
            assertEquals(newNode.target, persistedTargets.firstOrNull())
            assertFalse(
                "the dead cached hop must not survive the network switch",
                persistedTargets.contains(staleNode.target),
            )
        }
    }

    @Test
    fun connectionDropsMidPage_doesNotBecomeAnEmptyMarketAndRetryLoads() = fixture().use { fixture ->
        var nodeResponder: () -> MockResponse = {
            MockResponse.Builder()
                .code(200)
                .body(marketHtml())
                .onResponseBody(SocketEffect.CloseSocket())
                .build()
        }
        val node = fixture.node { nodeResponder() }
        fixture.routeTargets = listOf(node.target)

        val firstAttempt = runCatching { fixture.browse() }

        assertTrue(
            "a truncated page must fail explicitly instead of looking like an empty market",
            firstAttempt.isFailure,
        )

        // The player reconnects and retries: the same node now serves the complete page.
        nodeResponder = { okMarket() }
        val recovered = fixture.browse()

        assertEquals(listOf(SIMULATED_MOD_TITLE), recovered.items.map { it.title })
    }

    @Test
    fun brokenTlsHop_failsOverToWorkingNode() = fixture().use { fixture ->
        // A Watt hop whose TLS endpoint is broken (for example a captive proxy or MITM box).
        val brokenTls = fixture.node(scheme = "https") { okMarket() }
        val healthy = fixture.node { okMarket() }
        fixture.routeTargets = listOf(brokenTls.target, healthy.target)

        val result = fixture.browse()

        assertEquals(listOf(SIMULATED_MOD_TITLE), result.items.map { it.title })
        assertEquals(1, healthy.requestCount())
        assertEquals(0, fixture.officialRequestCount())
    }

    @Test
    fun metadataApiOutage_keepsAlreadyLoadedMarketPage() = fixture().use { fixture ->
        val healthy = fixture.node { okMarket() }
        fixture.routeTargets = listOf(healthy.target)
        val page = fixture.browse()

        // The page is already on screen; the separate metadata backfill API goes down.
        fixture.apiServer.enqueue(MockResponse.Builder().code(503).build())
        val enriched = fixture.loadMetadata(page.items)

        assertEquals(page.items, enriched)
        assertEquals(1, fixture.apiRequestCount())
    }

    @Test
    fun vpnOrPolicySwitch_disablesAccelerationAndUsesOfficialOrigin() = fixture().use { fixture ->
        val hop = fixture.node { okMarket() }
        fixture.routeTargets = listOf(hop.target)

        assertEquals(1, fixture.browse().items.size)
        assertEquals(1, hop.requestCount())

        // The player enables a VPN (or the launcher policy turns acceleration off) mid-session.
        fixture.accelerationEnabled = false
        fixture.officialServer.enqueue(okMarket())
        val official = fixture.browse()

        assertEquals(listOf(SIMULATED_MOD_TITLE), official.items.map { it.title })
        assertEquals(1, hop.requestCount())
        assertEquals(1, fixture.officialRequestCount())
    }

    @Test
    fun bundledHopStrategy_healthyHopLoadsWithoutWattDiscovery() = fixture(
        accelerationStrategy = AccelerationStrategy.RMBGAME_FIRST,
    ).use { fixture ->
        val hop = fixture.node { okMarket() }
        fixture.bundledHopTarget = hop.target

        val result = fixture.browse()

        assertEquals(listOf(SIMULATED_MOD_TITLE), result.items.map { it.title })
        assertEquals(1, hop.requestCount())
        assertEquals(0, fixture.routeRequestCount())
        assertEquals(0, fixture.officialRequestCount())
    }

    @Test
    fun bundledHopStrategy_hopFailureFallsBackToOfficial() = fixture(
        accelerationStrategy = AccelerationStrategy.RMBGAME_FIRST,
    ).use { fixture ->
        val hop = fixture.node { resetConnection() }
        fixture.bundledHopTarget = hop.target
        fixture.officialServer.enqueue(okMarket())

        val result = fixture.browse()

        assertEquals(listOf(SIMULATED_MOD_TITLE), result.items.map { it.title })
        assertEquals(1, hop.requestCount())
        assertEquals(1, fixture.officialRequestCount())
    }

    private fun fixture(
        routeStore: WattToolkitGithubRouteStore? = null,
        accelerationStrategy: AccelerationStrategy = AccelerationStrategy.BEST_PATH,
        routeResponse: Fixture.() -> MockResponse = {
            MockResponse.Builder().code(200).body(routePayload()).build()
        },
    ): Fixture = Fixture(routeStore, accelerationStrategy, routeResponse)

    private inner class Fixture(
        private val routeStore: WattToolkitGithubRouteStore?,
        private val accelerationStrategy: AccelerationStrategy,
        private val routeResponse: Fixture.() -> MockResponse,
    ) : AutoCloseable {
        val officialServer = MockWebServer()
        val apiServer = MockWebServer()
        var routeTargets: List<String> = emptyList()
        var bundledHopTarget: String? = null
        var accelerationEnabled: Boolean = true
        private val routeServer = MockWebServer()
        private val nodes = mutableListOf<Node>()
        private val probeExecutor: ExecutorService = Executors.newFixedThreadPool(4)
        private val progressReporterListener: (WorkshopLoadProgress) -> Unit = { progress ->
            synchronized(progressLock) { progressUpdates += progress }
        }
        private val progressLock = Any()
        private val progressUpdates = mutableListOf<WorkshopLoadProgress>()
        private val context: Context = createContext(rootDir)
        private val service: WorkshopService by lazy { createService() }
        private val httpClients = mutableListOf<OkHttpClient>()

        init {
            routeServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    routeResponse(this@Fixture)
            }
            routeServer.start()
            officialServer.start()
            apiServer.start()
            WorkshopLoadProgressReporter.setListener(progressReporterListener)
        }

        fun node(
            scheme: String = "http",
            response: () -> MockResponse,
        ): Node {
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = response()
            }
            server.start()
            return Node(server, scheme, "market-node-${nodes.size + 1}.test").also(nodes::add)
        }

        fun browse(): WorkshopBrowseResult = runBlocking {
            service.browse(WorkshopBrowseQuery(pageSize = 30))
        }

        fun loadMetadata(items: List<WorkshopItemSummary>): List<WorkshopItemSummary> =
            runBlocking { service.loadBrowseItemMetadata(items) }

        fun browseWithProgress(): WorkshopBrowseResult {
            val sessionId = WorkshopLoadProgressReporter.beginSession()
            service.beginProgressSession(sessionId)
            return try {
                browse()
            } finally {
                service.beginProgressSession(null)
                WorkshopLoadProgressReporter.endSession(sessionId)
            }
        }

        fun phases(): List<WorkshopLoadPhase> = synchronized(progressLock) {
            progressUpdates.map(WorkshopLoadProgress::phase)
        }

        fun officialRequestCount(): Int = officialServer.requestCount

        fun apiRequestCount(): Int = apiServer.requestCount

        fun routeRequestCount(): Int = routeServer.requestCount

        override fun close() {
            runCatching { service.close() }
            WorkshopLoadProgressReporter.setListener(null)
            nodes.forEach { node -> runCatching { node.server.close() } }
            runCatching { routeServer.close() }
            runCatching { officialServer.close() }
            runCatching { apiServer.close() }
            probeExecutor.shutdownNow()
            httpClients.forEach { client -> client.dispatcher.executorService.shutdownNow() }
            httpClients.forEach { client -> client.connectionPool.evictAll() }
        }

        private fun createService(): WorkshopService =
            WorkshopService(context, createClient())

        private fun createClient(): io.stamethyst.backend.github.RoutedHttpTransport {
            val routeClient = OkHttpClient.Builder()
                .dns(loopbackDns)
                .callTimeout(2, TimeUnit.SECONDS)
                .build()
            // The bundled-hop strategy is the production default. Its loopback fixture must not
            // use the real SNI-cloaking hostname, which would bypass the local MockWebServer.
            val routeProfile = if (accelerationStrategy == AccelerationStrategy.RMBGAME_FIRST) {
                SteamCommunityWattToolkitRouteProfile.copy(
                    bootstrapForwardTargets = emptyList(),
                    bootstrapFakeServerName = "",
                    bootstrapIgnoreSslCertVerification = true,
                )
            } else {
                SteamCommunityWattToolkitRouteProfile
            }
            val resolver = WattToolkitGithubRouteResolver(
                routeProfile = routeProfile,
                client = routeClient,
                projectGroupsUrl = routeServer.url("/accelerator/projectgroups"),
                forwardTargetProbe = {
                    WattToolkitForwardTargetProbe(successes = 3, attempts = 3, latencyMs = 10L)
                },
                officialTargetProbe = { _, _ -> WattToolkitForwardTargetProbe.failed() },
                bootstrapRouteProvider = { profile ->
                    bundledHopTarget?.let { target ->
                        WattToolkitGithubRoute(
                            logicalHosts = profile.supportedHosts,
                            forwardTargets = listOf(target),
                        )
                    }
                },
                routeStore = routeStore ?: NoOpWattToolkitGithubRouteStore,
                sleepProvider = {},
                // Route refresh and health searches stay synchronous in this fixture.
                backgroundExecutor = Executor {},
                probeExecutor = probeExecutor,
                accelerationStrategyProvider = { accelerationStrategy },
            )
            val directClient = OkHttpClient.Builder()
                .dns(loopbackDns)
                .connectTimeout(FIXTURE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(FIXTURE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(FIXTURE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val client = OkHttpClient.Builder()
                .dns(loopbackDns)
                .connectTimeout(FIXTURE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(FIXTURE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(FIXTURE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .routing(
                    TestRouting(
                        routeResolvers = listOf(resolver),
                        directCallFactory = directClient,
                        // Mirrors SteamCloudAcceleratedHttp: VPN/policy is read per request.
                        enabledProvider = { accelerationEnabled },
                    ),
                )
                // Only the official fallback reaches this interceptor: accelerated calls are
                // re-issued on directClient. Point that fallback at the local origin fixture
                // instead of the real steamcommunity.com.
                .addInterceptor { chain ->
                    val request = chain.request()
                    val query = request.url.encodedQuery?.let { "?$it" }.orEmpty()
                    val localOrigin = when (request.url.host) {
                        "steamcommunity.com", "www.steamcommunity.com" -> officialServer
                        "api.steampowered.com" -> apiServer
                        else -> null
                    }
                    if (localOrigin != null) {
                        chain.proceed(
                            request.newBuilder()
                                .url(localOrigin.url(request.url.encodedPath + query))
                                .build(),
                        )
                    } else {
                        chain.proceed(request)
                    }
                }
                .build()
            httpClients += routeClient
            httpClients += directClient
            httpClients += client.client
            return client
        }

        fun routePayload(): String = """
            {
              "🦓": [
                {
                  "Items": [
                    {
                      "MatchDomainNames": "steamcommunity.com;www.steamcommunity.com",
                      "ListenDomainNames": "steamcommunity.com;www.steamcommunity.com",
                      "ForwardDomainNames": "${routeTargets.joinToString(";")}",
                      "ProxyType": 0,
                      "IgnoreSSLCertVerification": true,
                      "Checked": true
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        inner class Node(val server: MockWebServer, scheme: String, host: String) {
            // Distinct hostnames matter: forward-target identity is compared by host, not port.
            val target: String = "$scheme://$host:${server.port}"

            fun requestCount(): Int = server.requestCount
        }
    }

    private fun okMarket(): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body(marketHtml())
            .build()

    private fun resetConnection(): MockResponse =
        MockResponse.Builder()
            .onResponseStart(SocketEffect.CloseSocket())
            .build()

    private class MemoryRouteStore : WattToolkitGithubRouteStore {
        var persisted: PersistedWattToolkitGithubRoute? = null

        override fun load(): PersistedWattToolkitGithubRoute? = persisted

        override fun save(route: PersistedWattToolkitGithubRoute) {
            persisted = route
        }

        override fun clear() {
            persisted = null
        }
    }

    private fun marketHtml(): String = """
        <div class="workshopItem" data-publishedfileid="123456" data-appid="646570">
          <a class="ugc" href="https://steamcommunity.com/sharedfiles/filedetails/?id=123456">
            <img class="workshopItemPreviewImage" src="https://cdn.example/preview.jpg" />
            <div class="workshopItemTitle">$SIMULATED_MOD_TITLE</div>
            <div class="workshopItemAuthorName">Author</div>
          </a>
        </div>
    """.trimIndent()

    private fun createContext(root: File): Context {
        val filesDir = File(root, "internal-files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        return object : ContextWrapper(Application()) {
            override fun getFilesDir(): File = filesDir

            override fun getCacheDir(): File = cacheDir

            override fun getApplicationContext(): Context = this

            override fun getPackageName(): String = "io.stamethyst.test"
        }
    }

    private companion object {
        const val SIMULATED_MOD_TITLE = "Simulated Workshop Mod"
        const val REFUSED_TARGET = "http://127.0.0.1:1"
        const val FIXTURE_CONNECT_TIMEOUT_MS = 500L
        const val FIXTURE_READ_TIMEOUT_MS = 1_000L
        const val FIXTURE_CALL_TIMEOUT_MS = 4_000L

        /**
         * Loopback-only DNS. Test hosts end in `.test`; anything else is treated like a broken
         * player DNS response instead of leaking a request to the real internet.
         */
        val loopbackDns = Dns { host ->
            when {
                host == "127.0.0.1" || host == "localhost" || host.endsWith(".test") ->
                    listOf(InetAddress.getByName("127.0.0.1"))
                else -> throw UnknownHostException("simulated DNS failure: $host")
            }
        }
    }
}

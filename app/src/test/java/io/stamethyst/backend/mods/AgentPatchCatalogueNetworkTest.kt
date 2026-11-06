package io.stamethyst.backend.mods

import io.stamethyst.backend.github.ExperimentalGithubDirectAccessRuntime
import io.stamethyst.backend.github.FileBackedWattToolkitGithubRouteStore
import io.stamethyst.backend.github.GithubUserContentWattToolkitRouteProfile
import io.stamethyst.backend.github.WattToolkitForwardTargetProbe
import io.stamethyst.backend.github.WattToolkitGithubRouteResolver
import io.stamethyst.backend.github.routing
import io.stamethyst.backend.network.AccelerationStrategy
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real sockets + real resolver/interceptor/parser/cache; no public-network dependency.
 * Loopback HTTP deliberately isolates routing from TLS (HTTPS enforcement has separate tests).
 */
class AgentPatchCatalogueNetworkTest {
    @get:Rule val temp = TemporaryFolder()
    private val rules = MockWebServer()
    private val official = MockWebServer()
    private val first = MockWebServer()
    private val second = MockWebServer()
    private lateinit var cache: File
    private lateinit var routeCache: File
    private var targets = emptyList<String>()
    private var ruleBody: String? = null
    private var preferOfficial = false
    private val path = "/ModinMobileSTS/SlaytheAmethystPlayerModCatalogue/main/index.json"
    private val valid = JSONObject().put("schemaVersion", 3).put("mods", JSONObject()
        .put("basemod", JSONObject().put("name", "BaseMod").put("patches", JSONArray()
            .put(JSONObject().put("name", "Network patch").put("version", "1.0")
                .put("patchModId", "network_patch").put("jar", "patch.jar")
                .put("download_url", "https://raw.githubusercontent.com/owner/repo/main/patch.jar")
                .put("size", 10).put("sha256", "a".repeat(64))
                .put("target", JSONObject().put("modId", "basemod")))))).toString()

    @Before fun setUp() {
        listOf(rules, official, first, second).forEach { it.start() }
        cache = File(temp.root, "catalogue/index.json")
        routeCache = File(temp.root, "route.json")
        targets = listOf("http://first.test:${first.port}", "http://second.test:${second.port}")
        rules.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().body(
                ruleBody ?: JSONObject().put("🦓", JSONArray().put(JSONObject().put("Items", JSONArray()
                    .put(JSONObject().put("MatchDomainNames", "raw.githubusercontent.com")
                        .put("ForwardDomainNames", targets.joinToString(";"))
                        .put("ProxyType", 0))))).toString(),
            ).build()
        }
    }

    @After fun tearDown() {
        listOf(rules, official, first, second).forEach { it.close() }
    }

    private fun client(strategy: AccelerationStrategy = AccelerationStrategy.RMBGAME_FIRST): Call.Factory {
        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val transport = OkHttpClient.Builder().dns(dns).retryOnConnectionFailure(false)
            .connectTimeout(300, TimeUnit.MILLISECONDS).readTimeout(300, TimeUnit.MILLISECONDS).build()
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = GithubUserContentWattToolkitRouteProfile,
            client = transport,
            projectGroupsUrl = rules.url("/accelerator/projectgroups"),
            routeStore = FileBackedWattToolkitGithubRouteStore(routeCache,
                GithubUserContentWattToolkitRouteProfile.supportedHosts),
            forwardTargetProbe = { target ->
                WattToolkitForwardTargetProbe(2, 2, if (target.contains("first.test")) 10 else 20)
            },
            officialTargetProbe = { _, _ -> WattToolkitForwardTargetProbe(2, 2, if (preferOfficial) 1 else 100) },
            backgroundExecutor = Executor { },
            sleepProvider = {},
            // Production default: raw has no bundled hop and must still discover rules.
            accelerationStrategyProvider = { strategy },
        )
        return transport.newBuilder().routing(
            ExperimentalGithubDirectAccessRuntime(listOf(resolver), transport.hostnameVerifier, transport),
        ).build()
    }

    private fun fetch(client: Call.Factory = client()) = AgentPatchCatalogueService.fetchCatalogue(
        client, cache, "http://raw.githubusercontent.com:${official.port}$path",
    )

    @Test fun preferredForwardFetchesParsesAndCachesThenReusesPersistedRoute() {
        first.enqueue(MockResponse.Builder().body(valid).build())
        val result = fetch(client(AccelerationStrategy.BEST_PATH))
        assertEquals("Network patch", result.mods.single().patches.single().name)
        assertTrue(result.changed)
        assertEquals(valid, cache.readText())
        val request = first.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals(path, request.url.encodedPath)
        assertEquals("raw.githubusercontent.com", request.headers["Host"])
        assertEquals("SlayTheAmethyst-AIPatchCatalogue", request.headers["User-Agent"])
        assertEquals("application/json", request.headers["Accept"])
        val ruleRequests = rules.requestCount
        first.enqueue(MockResponse.Builder().body(valid).build())
        assertFalse(fetch(client(AccelerationStrategy.BEST_PATH)).changed)
        assertEquals("cold client must reuse saved route", ruleRequests, rules.requestCount)
        assertEquals(0, official.requestCount)
        assertEquals(0, second.requestCount)
    }

    @Test fun fasterOfficialWinsWithoutContactingForwardNodes() {
        preferOfficial = true
        official.enqueue(MockResponse.Builder().body(valid).build())
        assertTrue(fetch().changed)
        assertEquals(1, official.requestCount)
        assertEquals(0, first.requestCount)
    }

    @Test fun connectionRefusedOnPreferredNodeUsesBackup() {
        targets = listOf("http://dead.test:1", targets[1])
        second.enqueue(MockResponse.Builder().body(valid).build())
        assertTrue(fetch().changed)
        assertEquals(1, second.requestCount)
        assertEquals(0, official.requestCount)
    }

    @Test fun stalledResponseHeadersUseBackup() {
        first.enqueue(MockResponse.Builder().headersDelay(800, TimeUnit.MILLISECONDS).body(valid).build())
        second.enqueue(MockResponse.Builder().body(valid).build())
        assertTrue(fetch().changed)
        assertEquals(1, second.requestCount)
    }

    @Test fun stalledResponseBodyUsesBackupBeforeConfirmingRoute() {
        first.enqueue(MockResponse.Builder().bodyDelay(800, TimeUnit.MILLISECONDS).body(valid).build())
        second.enqueue(MockResponse.Builder().body(valid).build())
        assertTrue(fetch().changed)
        assertEquals(1, second.requestCount)
    }

    @Test fun invalid200ResponsesAndHttpErrorsUseBackupAndDoNotPoisonCache() {
        val failures = listOf(
            MockResponse.Builder().body("<html>gateway error</html>").build(),
            MockResponse.Builder().body("{broken").build(),
            MockResponse.Builder().body("{\"schemaVersion\":2,\"mods\":{}}").build(),
            MockResponse.Builder().body("{\"schemaVersion\":3}").build(),
            MockResponse.Builder().body("").build(),
            MockResponse.Builder().code(403).body("forbidden").build(),
            MockResponse.Builder().code(429).body("rate limited").build(),
            MockResponse.Builder().code(503).body("unavailable").build(),
            MockResponse.Builder().body("x".repeat(4 * 1024 * 1024 + 1)).build(),
        )
        failures.forEachIndexed { index, failure ->
            routeCache.delete()
            first.enqueue(failure)
            second.enqueue(MockResponse.Builder().body(valid).build())
            assertEquals("case $index", valid, fetch().bytes.toString(Charsets.UTF_8))
            assertEquals("case $index", valid, cache.readText())
        }
        assertEquals(failures.size, second.requestCount)
        assertEquals(0, official.requestCount)
    }

    @Test fun bothForwardNodesFailThenOfficialSucceeds() {
        first.enqueue(MockResponse.Builder().code(502).build())
        second.enqueue(MockResponse.Builder().body("not json").build())
        official.enqueue(MockResponse.Builder().body(valid).build())
        assertTrue(fetch().changed)
        assertEquals(1, official.requestCount)
        assertEquals(valid, cache.readText())
    }

    @Test fun rejected200NodeIsNotPersistedAsSuccessfulBestPath() {
        first.enqueue(MockResponse.Builder().body("<html>not a catalogue</html>").build())
        second.enqueue(MockResponse.Builder().body(valid).build())
        fetch(client(AccelerationStrategy.BEST_PATH))
        second.enqueue(MockResponse.Builder().body(valid).build())
        assertFalse(fetch(client(AccelerationStrategy.BEST_PATH)).changed)
        assertEquals(1, first.requestCount)
        assertEquals(2, second.requestCount)
        assertEquals(1, rules.requestCount)
    }

    @Test fun truncatedResponseBodyUsesBackup() {
        first.enqueue(MockResponse.Builder().body("{\"schemaVersion\":3")
            .setHeader("Content-Length", 10_000).build())
        second.enqueue(MockResponse.Builder().body(valid).build())
        assertTrue(fetch().changed)
        assertEquals(1, second.requestCount)
        assertEquals(valid, cache.readText())
    }

    @Test fun failureWithoutPreviousCacheDoesNotCreateAnIndex() {
        first.enqueue(MockResponse.Builder().code(503).build())
        second.enqueue(MockResponse.Builder().body("invalid").build())
        official.enqueue(MockResponse.Builder().code(403).build())
        assertTrue(runCatching { fetch() }.exceptionOrNull() is IOException)
        assertFalse(cache.exists())
    }

    @Test fun malformedRulesStillAllowOfficialFetch() {
        ruleBody = "<html>rule service broken</html>"
        official.enqueue(MockResponse.Builder().body(valid).build())
        assertTrue(fetch().changed)
        assertEquals(0, first.requestCount)
        assertEquals(1, official.requestCount)
    }

    @Test fun invalidOfficialResponseFallsBackToForwardNode() {
        preferOfficial = true
        official.enqueue(MockResponse.Builder().body("<html>blocked</html>").build())
        first.enqueue(MockResponse.Builder().body(valid).build())
        assertTrue(fetch().changed)
        assertEquals(1, first.requestCount)
    }

    @Test fun totalFailurePreservesExistingCacheAndNextRequestCanRecover() {
        cache.parentFile!!.mkdirs()
        cache.writeText(valid)
        first.enqueue(MockResponse.Builder().body("invalid").build())
        second.enqueue(MockResponse.Builder().code(503).build())
        official.enqueue(MockResponse.Builder().body("{\"schemaVersion\":99}").build())
        val client = client()
        val error = runCatching { fetch(client) }.exceptionOrNull()
        assertTrue("expected bounded failure, got $error", error is IOException)
        assertEquals(valid, cache.readText())
        assertEquals(listOf("index.json"), cache.parentFile!!.list()!!.toList())
        official.enqueue(MockResponse.Builder().body(valid).build())
        assertFalse(fetch(client).changed)
        assertEquals(valid, cache.readText())
    }
}

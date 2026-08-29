package io.stamethyst.backend.steamcloud

import io.stamethyst.backend.github.ExperimentalGithubDirectAccessRuntime
import io.stamethyst.backend.github.ExperimentalGithubDirectAccessInterceptor
import io.stamethyst.backend.github.CredentialSafeRedirectInterceptor
import io.stamethyst.backend.github.GithubDirectHostnameVerifier
import io.stamethyst.backend.github.PersistedWattToolkitGithubRoute
import io.stamethyst.backend.github.WattToolkitForwardDns
import io.stamethyst.backend.github.WattToolkitForwardTargetProbe
import io.stamethyst.backend.github.WattToolkitGithubRoute
import io.stamethyst.backend.github.WattToolkitGithubRouteResolver
import io.stamethyst.backend.github.WattToolkitGithubRouteStore
import io.stamethyst.backend.github.WattToolkitRouteProfile
import io.stamethyst.backend.github.addHttpsOnlyTransport
import io.stamethyst.backend.github.addExperimentalGithubDirectAccess
import io.stamethyst.backend.github.withAcceleratedCookieJar
import java.net.InetAddress
import java.net.ProtocolException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.apricityx.workshop.steam.protocol.SteamDeclaredCdnHosts
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SteamCloudAcceleratedHttpTest {
    private lateinit var apiServer: MockWebServer
    private lateinit var steamStoreForwardServer: MockWebServer
    private lateinit var steamContentForwardServer: MockWebServer

    @Before
    fun setUp() {
        apiServer = MockWebServer()
        steamStoreForwardServer = MockWebServer()
        steamContentForwardServer = MockWebServer()
        apiServer.start()
        steamStoreForwardServer.start()
        steamContentForwardServer.start()
    }

    @After
    fun tearDown() {
        apiServer.close()
        steamStoreForwardServer.close()
        steamContentForwardServer.close()
        SteamCloudAcceleratedHttp.clearRuntimeCacheForTests()
        SteamDeclaredCdnHosts.clear()
    }

    @Test
    fun routeResolver_matchesSteamStoreHostsFromWattRules() {
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
                              "MatchDomainNames": "store.steampowered.com;api.steampowered.com;login.steampowered.com",
                              "ListenDomainNames": "store.steampowered.com;api.steampowered.com;login.steampowered.com",
                              "ForwardDomainNames": "http://steamstore.rmbgame.net:${steamStoreForwardServer.port}",
                              "ProxyType": 0,
                              "IgnoreSSLCertVerification": true
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamStoreWattToolkitRouteProfile,
            client = OkHttpClient.Builder().dns(dns).build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
        )

        val route = resolver.resolveRouteForHost("login.steampowered.com")

        assertNotNull(route)
        assertTrue(route!!.logicalHosts.contains("api.steampowered.com"))
        assertTrue(route.logicalHosts.contains("login.steampowered.com"))
        assertEquals(
            "steamstore.rmbgame.net",
            route.buildForwardedUrl(
                "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
                    .toHttpUrl(),
            ).host,
        )
    }

    @Test
    fun steamImageRouteProfile_supportsFastlyAvatarHost() {
        assertTrue(
            SteamImageCdnWattToolkitRouteProfile.supportedHosts.contains(
                "avatars.fastly.steamstatic.com",
            ),
        )
    }

    @Test
    fun steamCmRouteProfile_coversDirectoryWebsocketHosts() {
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamCmWattToolkitRouteProfile,
            client = OkHttpClient(),
        )

        listOf("steamserver.net", "cm0-ord.steamserver.net").forEach { host ->
            assertTrue("expected $host to be covered by the steam-cm profile", resolver.isProfileHost(host))
        }
        assertTrue(!resolver.isProfileHost("steamserver.net.attacker.test"))
    }

    @Test
    fun steamCmWebSocketForwarding_preservesLogicalHostAndSocketPath() {
        val route = WattToolkitGithubRoute(
            logicalHosts = setOf("steamserver.net"),
            logicalHostSuffixes = setOf(".steamserver.net"),
            forwardTargets = listOf("https://cm-forward.rmbgame.net"),
            fakeServerName = "cm-front.rmbgame.net",
        )
        val request = Request.Builder()
            .url("wss://cmp2-hkg1.steamserver.net/cmsocket/?transport=websocket")
            .build()

        val forwarded = buildSteamCmForwardedWebSocketRequest(request, route)

        assertEquals("cm-front.rmbgame.net", forwarded.url.host)
        assertEquals("/cmsocket/", forwarded.url.encodedPath)
        assertEquals("transport=websocket", forwarded.url.encodedQuery)
        assertEquals("cmp2-hkg1.steamserver.net", forwarded.header("Host"))
    }

    @Test
    fun steamCmWebSocketFactory_rejectsCleartextBeforeOfficialOrForwardPath() {
        val factory = SteamCmAcceleratedWebSocketFactory(
            officialClient = OkHttpClient(),
            forwardClient = OkHttpClient(),
            routeResolvers = emptyList(),
        )

        val error = runCatching {
            factory.newWebSocket(
                Request.Builder()
                    .url("http://cm0-ord.steamserver.net/cmsocket/")
                    .build(),
                object : WebSocketListener() {},
            )
        }.exceptionOrNull()

        assertTrue(error is ProtocolException)
    }

    @Test
    fun requireHttpsResolver_dropsCleartextBootstrapRouteWithoutForwardRequest() {
        val profile = WattToolkitRouteProfile(
            name = "https-only-bootstrap",
            cacheFileName = "unused.json",
            supportedHosts = setOf("steamcommunity.com"),
            bootstrapForwardTargets = listOf("http://cleartext-forward.test"),
        )
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = profile,
            client = OkHttpClient(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
            bootstrapRouteProvider = {
                WattToolkitGithubRoute(
                    logicalHosts = setOf("steamcommunity.com"),
                    forwardTargets = listOf("http://cleartext-forward.test"),
                )
            },
            sleepProvider = {},
            backgroundExecutor = Executor { },
            requireHttps = true,
        )

        assertNull(resolver.resolveRouteForHost("steamcommunity.com"))
        assertEquals(0, apiServer.requestCount)
    }

    @Test
    fun interceptor_refreshesCachedRouteAfterStaleHttpErrorAndRetriesOnce() {
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
                              "MatchDomainNames": "steamcommunity.com;www.steamcommunity.com",
                              "ForwardDomainNames": "http://new-community-route.test:${steamContentForwardServer.port}",
                              "ProxyType": 0,
                              "IgnoreSSLCertVerification": false
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        steamStoreForwardServer.enqueue(
            MockResponse.Builder()
                .code(404)
                .body("stale-route")
                .build(),
        )
        steamContentForwardServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("workshop-ok")
                .build(),
        )

        val oldRoute = WattToolkitGithubRoute(
            logicalHosts = setOf("steamcommunity.com", "www.steamcommunity.com"),
            forwardTargets = listOf("http://old-community-route.test:${steamStoreForwardServer.port}"),
        )
        val routeStore = object : WattToolkitGithubRouteStore {
            var persisted: PersistedWattToolkitGithubRoute? = PersistedWattToolkitGithubRoute(
                route = oldRoute,
                cachedAtMs = 1_000L,
            )

            override fun load(): PersistedWattToolkitGithubRoute? = persisted

            override fun save(route: PersistedWattToolkitGithubRoute) {
                persisted = route
            }

            override fun clear() {
                persisted = null
            }
        }
        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamCommunityWattToolkitRouteProfile,
            client = OkHttpClient.Builder()
                .callTimeout(5, TimeUnit.SECONDS)
                .build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
            routeStore = routeStore,
            nowProvider = { 1_000L },
            sleepProvider = {},
            backgroundExecutor = Executor { /* keep unit test deterministic */ },
        )
        val directClient = OkHttpClient.Builder()
            .dns(dns)
            .callTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val client = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(
                ExperimentalGithubDirectAccessInterceptor(
                    routeResolvers = listOf(resolver),
                    directCallFactory = directClient,
                ),
            )
            .build()

        client.newCall(
            Request.Builder()
                .url("https://steamcommunity.com/workshop/browse/?appid=646570")
                .build(),
        ).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("steamcommunity.com", response.request.url.host)
            assertEquals("workshop-ok", response.body.string())
        }

        assertEquals("/accelerator/projectgroups", apiServer.takeRequest(5, TimeUnit.SECONDS)?.url?.encodedPath)
        val staleRequest = steamStoreForwardServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("/workshop/browse/", staleRequest?.url?.encodedPath)
        assertEquals("steamcommunity.com", staleRequest?.headers?.get("Host"))
        val refreshedRequest = steamContentForwardServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("/workshop/browse/", refreshedRequest?.url?.encodedPath)
        assertEquals("steamcommunity.com", refreshedRequest?.headers?.get("Host"))
    }

    @Test
    fun steamImageRouteProfile_supportsSteamUserContentImageHosts() {
        assertTrue(
            SteamImageCdnWattToolkitRouteProfile.supportedHosts.containsAll(
                setOf("images.steamusercontent.com", "steamusercontent.com"),
            ),
        )
    }

    @Test
    fun steamImageRouteProfile_acceleratesLoggedInAvatarSiblingHosts() {
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamImageCdnWattToolkitRouteProfile,
            client = OkHttpClient(),
        )

        // Logged-in profile avatars resolve to these hosts, which are not in the
        // hand-written supportedHosts list and previously fell through unaccelerated.
        listOf(
            "avatars.steamstatic.com",
            "avatars.cloudflare.steamstatic.com",
            "shared.cloudflare.steamstatic.com",
            "steamuserimages-a.akamaihd.net",
        ).forEach { host ->
            assertTrue(
                "expected $host to be covered by the steam-image-cdn profile",
                resolver.isProfileHost(host),
            )
        }
    }

    @Test
    fun steamImageRouteProfile_doesNotClaimUnrelatedHosts() {
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamImageCdnWattToolkitRouteProfile,
            client = OkHttpClient(),
        )

        listOf(
            "steamcommunity.com",
            "api.steampowered.com",
            "notsteamstatic.com",
            "evil-steamstatic.com.attacker.test",
        ).forEach { host ->
            assertTrue(
                "expected $host to stay outside the steam-image-cdn profile",
                !resolver.isProfileHost(host),
            )
        }
    }

    @Test
    fun steamContentRouteProfile_supportsSteamPipeCdnHostsSeenOnDevice() {
        val expectedHosts = setOf(
            "st.dl.eccdnx.com",
            "xz.pphimalayanrt.com",
            "xz.sycontroller.com",
            "dl.steam.clngaa.com",
            "files.steam.nsclouds.cn",
        )

        assertTrue(
            SteamContentCdnWattToolkitRouteProfile.supportedHosts.containsAll(expectedHosts),
        )
    }

    @Test
    fun steamContentCdnHttpTransport_allowsManifestAndChunkPathsButRejectsOtherHosts() {
        steamContentForwardServer.enqueue(MockResponse.Builder().code(200).body("manifest").build())
        steamContentForwardServer.enqueue(MockResponse.Builder().code(200).body("chunk").build())
        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val client = OkHttpClient.Builder()
            .dns(dns)
            .addHttpsOnlyTransport(::allowsSteamContentCdnHttp)
            .build()

        listOf(
            "/depot/646570/manifest/4615174550123654200/5",
            "/depot/646570/chunk/abcdef",
        ).forEach { path ->
            client.newCall(
                Request.Builder()
                    .url("http://st.dl.eccdnx.com:${steamContentForwardServer.port}$path")
                    .build(),
            ).execute().use { response ->
                assertEquals(200, response.code)
            }
        }

        val error = runCatching {
            client.newCall(
                Request.Builder()
                    .url("http://api.steampowered.com:${steamContentForwardServer.port}/ISteamNews/GetNewsForApp/v2/")
                    .build(),
            ).execute()
        }.exceptionOrNull()

        assertTrue(error is ProtocolException)
    }

    @Test
    fun steamDeclaredCdnHost_permitsCleartextChunkRedirectForChinaEdge() {
        val chunkPath = "/depot/646570/chunk/009626FE3E032E23093B6F03483535C8BC832434"
        val redirectTarget = "http://edge-cdn.steamchina.test:${steamStoreForwardServer.port}$chunkPath?reqhost=ctgslb"
        steamContentForwardServer.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", redirectTarget).build(),
        )
        steamContentForwardServer.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", redirectTarget).build(),
        )
        steamStoreForwardServer.enqueue(MockResponse.Builder().code(200).body("chunk-bytes").build())

        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val client = OkHttpClient.Builder()
            .dns(dns)
            .followRedirects(false)
            .followSslRedirects(false)
            .addHttpsOnlyTransport(::allowsSteamContentCdnHttp)
            .addInterceptor(
                CredentialSafeRedirectInterceptor(
                    requireHttps = true,
                    allowInsecureUrl = ::allowsSteamContentCdnHttp,
                ),
            )
            .build()

        val request = Request.Builder()
            .url("http://st.dl.eccdnx.com:${steamContentForwardServer.port}$chunkPath")
            .build()

        val error = runCatching { client.newCall(request).execute() }.exceptionOrNull()
        assertTrue("expected undeclared cleartext redirect to be rejected", error is ProtocolException)
        assertEquals("HTTPS is required for redirected request: $redirectTarget", error?.message)
        assertEquals(0, steamStoreForwardServer.requestCount)

        SteamDeclaredCdnHosts.register("edge-cdn.steamchina.test")

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("chunk-bytes", response.body.string())
        }
        assertEquals(chunkPath, steamStoreForwardServer.takeRequest().url.encodedPath)
    }

    @Test
    fun routeResolver_preservesFakeServerNameForForwardedTlsHost() {
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
                              "MatchDomainNames": "api.steampowered.com",
                              "ListenDomainNames": "api.steampowered.com",
                              "ForwardDomainNames": "http://steamstore-forward.test:${steamStoreForwardServer.port}",
                              "FakeServerName": "officecdn-microsoft-com.akamaized.net",
                              "ProxyType": 0,
                              "IgnoreSSLCertVerification": false
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamStoreWattToolkitRouteProfile,
            client = OkHttpClient.Builder().dns(dns).build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
        )
        val route = resolver.resolveRouteForHost("api.steampowered.com")

        assertNotNull(route)
        assertEquals("officecdn-microsoft-com.akamaized.net", route!!.fakeServerName)
        assertEquals(
            "officecdn-microsoft-com.akamaized.net",
            route.buildForwardedUrl(
                "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
                    .toHttpUrl(),
            ).host,
        )
    }

    @Test
    fun interceptor_routesFakeTlsHostToForwardTargetDns() {
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
                              "MatchDomainNames": "api.steampowered.com",
                              "ListenDomainNames": "api.steampowered.com",
                              "ForwardDomainNames": "http://steamstore-forward.test:${steamStoreForwardServer.port}",
                              "FakeServerName": "officecdn-microsoft-com.akamaized.net",
                              "ProxyType": 0,
                              "IgnoreSSLCertVerification": true
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        steamStoreForwardServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("{\"response\":{\"result\":1}}")
                .build(),
        )

        val routeDns = WattToolkitForwardDns(Dns { listOf(InetAddress.getByName("127.0.0.1")) })
        val resolverDns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamStoreWattToolkitRouteProfile,
            client = OkHttpClient.Builder().dns(resolverDns).build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
        )
        val directClient = OkHttpClient.Builder()
            .dns(routeDns)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                ExperimentalGithubDirectAccessInterceptor(
                    routeResolvers = listOf(resolver),
                    directCallFactory = directClient,
                    forwardDns = routeDns,
                ),
            )
            .build()

        client.newCall(
            Request.Builder()
                .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/")
                .build(),
        ).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("api.steampowered.com", response.request.url.host)
        }

        apiServer.takeRequest()
        val forwardedRequest = steamStoreForwardServer.takeRequest()
        assertEquals("api.steampowered.com", forwardedRequest.headers["Host"])
        assertEquals(
            "/ISteamRemoteStorage/GetPublishedFileDetails/v1/",
            forwardedRequest.url.encodedPath,
        )
    }

    @Test
    fun steamCommunityBootstrap_usesHttpsForwardTarget() {
        assertEquals(
            "https://steamcommunity.rmbgame.net",
            SteamCommunityWattToolkitRouteProfile.bootstrapForwardTargets.single(),
        )
    }

    @Test
    fun routeResolver_matchesSteamContentCdnHostsFromWattProxyRule() {
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
                              "MatchDomainNames": "st.dl.eccdnx.com;xz.pphimalayanrt.com;dl.steam.clngaa.com;files.steam.nsclouds.cn",
                              "ListenDomainNames": "st.dl.eccdnx.com;xz.pphimalayanrt.com;dl.steam.clngaa.com;files.steam.nsclouds.cn",
                              "ForwardDomainNames": "http://cdn.queniuqe.com:${steamContentForwardServer.port}",
                              "ProxyType": 1,
                              "IgnoreSSLCertVerification": true,
                              "Checked": false
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamContentCdnWattToolkitRouteProfile,
            client = OkHttpClient.Builder().dns(dns).build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
        )

        val route = resolver.resolveRouteForHost("xz.pphimalayanrt.com")

        assertNotNull(route)
        assertTrue(route!!.logicalHosts.contains("xz.pphimalayanrt.com"))
        assertTrue(route.logicalHosts.contains("st.dl.eccdnx.com"))
        assertTrue(route.logicalHosts.contains("dl.steam.clngaa.com"))
        assertTrue(route.logicalHosts.contains("files.steam.nsclouds.cn"))
        assertEquals(
            "cdn.queniuqe.com",
            route.buildForwardedUrl(
                "https://xz.pphimalayanrt.com/depot/646571/manifest/1616206291221819177/5".toHttpUrl(),
            ).host,
        )
    }

    @Test
    fun routeResolver_matchesWildcardSteamContentCdnHosts() {
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
                              "MatchDomainNames": "*.st.dl.eccdnx.com",
                              "ListenDomainNames": "*.st.dl.eccdnx.com",
                              "ForwardDomainNames": "cdn.queniuqe.com",
                              "ProxyType": 1,
                              "IgnoreSSLCertVerification": true,
                              "FakeServerName": ""
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamContentCdnWattToolkitRouteProfile,
            client = OkHttpClient.Builder().dns(dns).build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
        )

        val route = resolver.resolveRouteForHost("st.dl.eccdnx.com")

        assertNotNull(route)
        assertTrue(route!!.logicalHosts.contains("st.dl.eccdnx.com"))
        assertEquals("cdn.queniuqe.com", route.buildForwardedUrl("https://st.dl.eccdnx.com/file".toHttpUrl()).host)
    }

    @Test
    fun routeResolver_acceptsUncheckedSteamContentCdnRouteForWorkshopDownloads() {
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
                              "MatchDomainNames": "*.st.dl.eccdnx.com",
                              "ListenDomainNames": "*.st.dl.eccdnx.com",
                              "ForwardDomainNames": "cdn.queniuqe.com",
                              "ProxyType": 1,
                              "Checked": false
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamContentCdnWattToolkitRouteProfile,
            client = OkHttpClient.Builder().build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
        )

        val route = resolver.resolveRouteForHost("st.dl.eccdnx.com")

        assertNotNull(route)
        assertEquals(listOf("cdn.queniuqe.com"), route!!.forwardTargets)
    }

    @Test
    fun routeResolver_ranksForwardTargetsBySuccessRateThenLatency() {
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
                              "MatchDomainNames": "store.steampowered.com",
                              "ListenDomainNames": "store.steampowered.com",
                              "ForwardDomainNames": "slow-node.test;fast-node.test",
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

        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamStoreWattToolkitRouteProfile,
            client = OkHttpClient.Builder().build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
            forwardTargetProbe = { target ->
                when (target) {
                    "slow-node.test" -> WattToolkitForwardTargetProbe(
                        successes = 2,
                        attempts = 3,
                        latencyMs = 5,
                    )
                    else -> WattToolkitForwardTargetProbe(
                        successes = 3,
                        attempts = 3,
                        latencyMs = 40,
                    )
                }
            },
        )

        val route = resolver.resolveRouteForHost("store.steampowered.com")

        assertNotNull(route)
        // Probe-ranked Watt order first, then bootstrap fallback hops.
        assertEquals(
            listOf("fast-node.test", "slow-node.test", "https://steamstore.rmbgame.net"),
            route!!.forwardTargets,
        )
    }

    @Test
    fun routeResolver_prefersPersistedBestPathWithoutBlockingOnProjectGroups() {
        val routeStore = object : WattToolkitGithubRouteStore {
            var loadCount = 0
            var persisted: PersistedWattToolkitGithubRoute? = PersistedWattToolkitGithubRoute(
                route = WattToolkitGithubRoute(
                    logicalHosts = setOf("steamcommunity.com", "www.steamcommunity.com"),
                    forwardTargets = listOf("https://cached-community.rmbgame.net"),
                    ignoreSslCertVerification = true,
                ),
                cachedAtMs = 1_000L,
            )

            override fun load(): PersistedWattToolkitGithubRoute? {
                loadCount++
                return persisted
            }

            override fun save(route: PersistedWattToolkitGithubRoute) {
                persisted = route
            }

            override fun clear() {
                persisted = null
            }
        }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamCommunityWattToolkitRouteProfile,
            client = OkHttpClient.Builder().build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
            routeStore = routeStore,
            nowProvider = { 1_000L + (30L * 60L * 1_000L) + 1L },
            backgroundExecutor = Executor { /* no background work in this unit test */ },
        )

        val route = resolver.resolveRouteForHost("steamcommunity.com")

        assertNotNull(route)
        assertEquals(listOf("https://cached-community.rmbgame.net"), route!!.forwardTargets)
        assertEquals(0, apiServer.requestCount)
        assertEquals(1, routeStore.loadCount)
    }

    @Test
    fun routeResolver_skipsRecentlyFailedCachedTargetAndUsesBootstrap() {
        val routeStore = object : WattToolkitGithubRouteStore {
            var persisted: PersistedWattToolkitGithubRoute? = PersistedWattToolkitGithubRoute(
                route = WattToolkitGithubRoute(
                    logicalHosts = setOf("steamcommunity.com", "www.steamcommunity.com"),
                    forwardTargets = listOf("www.valvesoftware.com"),
                    ignoreSslCertVerification = false,
                    fakeServerName = "www.valvesoftware.com",
                ),
                cachedAtMs = 1_000L,
            )

            override fun load(): PersistedWattToolkitGithubRoute? = persisted

            override fun save(route: PersistedWattToolkitGithubRoute) {
                persisted = route
            }

            override fun clear() {
                persisted = null
            }
        }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamCommunityWattToolkitRouteProfile,
            client = OkHttpClient.Builder()
                .callTimeout(200, TimeUnit.MILLISECONDS)
                .build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
            routeStore = routeStore,
            nowProvider = { 2_000L },
            sleepProvider = {},
            // Inject a no-op probe so bootstrap fallback target ranking never opens a real socket.
            forwardTargetProbe = { WattToolkitForwardTargetProbe(successes = 1, attempts = 1, latencyMs = 0L) },
            backgroundExecutor = Executor { /* no background work in this unit test */ },
        )

        // Warm poison cache, then mark it failed like a real connect timeout would.
        assertEquals(
            listOf("www.valvesoftware.com"),
            resolver.resolveRouteForHost("steamcommunity.com")!!.forwardTargets,
        )
        val refreshed = resolver.refreshRouteForHost(
            host = "steamcommunity.com",
            excludedForwardTargets = listOf("www.valvesoftware.com"),
        )

        assertNotNull(refreshed)
        assertEquals(
            listOf("https://steamcommunity.rmbgame.net"),
            refreshed!!.forwardTargets,
        )
        assertEquals(
            listOf("https://steamcommunity.rmbgame.net"),
            resolver.resolveRouteForHost("steamcommunity.com")!!.forwardTargets,
        )
        // Unconfirmed rediscovery stays in-memory only; poison disk entry is cleared.
        assertNull(routeStore.persisted)
    }

    @Test
    fun routeResolver_confirmSuccessfulForwardTarget_reordersPreferredPath() {
        val routeStore = object : WattToolkitGithubRouteStore {
            var persisted: PersistedWattToolkitGithubRoute? = PersistedWattToolkitGithubRoute(
                route = WattToolkitGithubRoute(
                    logicalHosts = setOf("steamcommunity.com", "www.steamcommunity.com"),
                    forwardTargets = listOf(
                        "https://slow-community.rmbgame.net",
                        "https://fast-community.rmbgame.net",
                    ),
                ),
                cachedAtMs = 1_000L,
            )

            override fun load(): PersistedWattToolkitGithubRoute? = persisted

            override fun save(route: PersistedWattToolkitGithubRoute) {
                persisted = route
            }

            override fun clear() {
                persisted = null
            }
        }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamCommunityWattToolkitRouteProfile,
            client = OkHttpClient.Builder().build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
            routeStore = routeStore,
            nowProvider = { 2_000L },
            backgroundExecutor = Executor { /* no background work in this unit test */ },
        )

        // Warm restored cache first.
        assertEquals(
            listOf("https://slow-community.rmbgame.net", "https://fast-community.rmbgame.net"),
            resolver.resolveRouteForHost("steamcommunity.com")!!.forwardTargets,
        )

        resolver.confirmSuccessfulForwardTarget(
            host = "steamcommunity.com",
            successfulTarget = "https://fast-community.rmbgame.net",
        )

        assertEquals(
            listOf("https://fast-community.rmbgame.net", "https://slow-community.rmbgame.net"),
            resolver.resolveRouteForHost("steamcommunity.com")!!.forwardTargets,
        )
        assertEquals(
            listOf("https://fast-community.rmbgame.net", "https://slow-community.rmbgame.net"),
            routeStore.persisted!!.route.forwardTargets,
        )
    }

    @Test
    fun interceptor_routesSteamApiRequestThroughWattForwardTarget() {
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
                              "MatchDomainNames": "store.steampowered.com;api.steampowered.com;login.steampowered.com",
                              "ListenDomainNames": "store.steampowered.com;api.steampowered.com;login.steampowered.com",
                              "ForwardDomainNames": "http://steamstore.rmbgame.net:${steamStoreForwardServer.port}",
                              "ProxyType": 0,
                              "IgnoreSSLCertVerification": true
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        steamStoreForwardServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"response":{"result":1}}""")
                .build(),
        )

        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamStoreWattToolkitRouteProfile,
            client = OkHttpClient.Builder().dns(dns).build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
        )
        val runtime = ExperimentalGithubDirectAccessRuntime(
            resolvers = listOf(resolver),
            hostnameVerifier = GithubDirectHostnameVerifier { host ->
                resolver.allowsUnsafeHostnameBypass(host)
            },
            directHttpClient = OkHttpClient.Builder()
                .dns(dns)
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )
        val client = OkHttpClient.Builder()
            .dns(dns)
            .addInterceptor(
                ExperimentalGithubDirectAccessInterceptor(
                    routeResolvers = runtime.resolvers,
                    directCallFactory = runtime.directHttpClient,
                ),
            )
            .build()

        client.newCall(
            Request.Builder()
                .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/")
                .post("itemcount=0".toRequestBody())
                .build(),
        ).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("api.steampowered.com", response.request.url.host)
        }

        val routeRequest = apiServer.takeRequest()
        assertEquals("/accelerator/projectgroups", routeRequest.url.encodedPath)

        val forwardedRequest = steamStoreForwardServer.takeRequest()
        assertEquals(
            "/ISteamRemoteStorage/GetPublishedFileDetails/v1/",
            forwardedRequest.url.encodedPath,
        )
        assertEquals("api.steampowered.com", forwardedRequest.headers["Host"])
    }

    @Test
    fun interceptor_failsOverToNextForwardTargetOnConnectionError() {
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
                              "MatchDomainNames": "api.steampowered.com",
                              "ListenDomainNames": "api.steampowered.com",
                              "ForwardDomainNames": "http://127.0.0.1:1;http://steamstore-fallback.test:${steamStoreForwardServer.port}",
                              "ProxyType": 0,
                              "IgnoreSSLCertVerification": true,
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
        steamStoreForwardServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("fallback-ok")
                .build(),
        )

        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamStoreWattToolkitRouteProfile,
            client = OkHttpClient.Builder().dns(dns).build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
            forwardTargetProbe = {
                WattToolkitForwardTargetProbe(successes = 1, attempts = 1, latencyMs = 1)
            },
        )
        val runtime = ExperimentalGithubDirectAccessRuntime(
            resolvers = listOf(resolver),
            hostnameVerifier = GithubDirectHostnameVerifier { host ->
                resolver.allowsUnsafeHostnameBypass(host)
            },
            directHttpClient = OkHttpClient.Builder()
                .dns(dns)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )
        val client = OkHttpClient.Builder()
            .dns(dns)
            .addInterceptor(
                ExperimentalGithubDirectAccessInterceptor(
                    routeResolvers = runtime.resolvers,
                    directCallFactory = runtime.directHttpClient,
                ),
            )
            .build()

        client.newCall(
            Request.Builder()
                .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/")
                .build(),
        ).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("api.steampowered.com", response.request.url.host)
        }

        val forwardedRequest = steamStoreForwardServer.takeRequest()
        assertEquals("/ISteamRemoteStorage/GetPublishedFileDetails/v1/", forwardedRequest.url.encodedPath)
        assertEquals("api.steampowered.com", forwardedRequest.headers["Host"])
    }

    @Test
    fun interceptor_remembersFailedWorkshopTargetAndSkipsItOnLaterDownload() {
        val badForwardServer = MockWebServer()
        val goodForwardServer = MockWebServer()
        badForwardServer.start()
        goodForwardServer.start()
        try {
            val badTarget = "http://bad-community.test:${badForwardServer.port}"
            val goodTarget = "http://good-community.test:${goodForwardServer.port}"
            val routePayload = """
                {
                  "🦓": [
                    {
                      "Items": [
                        {
                          "MatchDomainNames": "steamcommunity.com;www.steamcommunity.com",
                          "ListenDomainNames": "steamcommunity.com;www.steamcommunity.com",
                          "ForwardDomainNames": "$badTarget;$goodTarget",
                          "ProxyType": 0,
                          "IgnoreSSLCertVerification": true,
                          "Checked": true
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent()
            apiServer.enqueue(MockResponse.Builder().code(200).body(routePayload).build())
            apiServer.enqueue(MockResponse.Builder().code(200).body(routePayload).build())
            badForwardServer.enqueue(MockResponse.Builder().code(403).body("bad-route").build())
            goodForwardServer.enqueue(MockResponse.Builder().code(200).body("first-download").build())
            goodForwardServer.enqueue(MockResponse.Builder().code(200).body("second-download").build())

            val routeStore = object : WattToolkitGithubRouteStore {
                var persisted: PersistedWattToolkitGithubRoute? = PersistedWattToolkitGithubRoute(
                    route = WattToolkitGithubRoute(
                        logicalHosts = setOf("steamcommunity.com", "www.steamcommunity.com"),
                        forwardTargets = listOf(badTarget, goodTarget),
                    ),
                    cachedAtMs = 1_000L,
                )

                override fun load(): PersistedWattToolkitGithubRoute? = persisted

                override fun save(route: PersistedWattToolkitGithubRoute) {
                    persisted = route
                }

                override fun clear() {
                    persisted = null
                }
            }
            val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
            val resolver = WattToolkitGithubRouteResolver(
                routeProfile = SteamCommunityWattToolkitRouteProfile,
                client = OkHttpClient.Builder().dns(dns).build(),
                projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
                routeStore = routeStore,
                nowProvider = { 2_000L },
                forwardTargetProbe = { target ->
                    if (target == goodTarget) {
                        WattToolkitForwardTargetProbe(successes = 1, attempts = 1, latencyMs = 1L)
                    } else {
                        WattToolkitForwardTargetProbe.failed()
                    }
                },
                officialTargetProbe = { _, _ -> WattToolkitForwardTargetProbe.failed() },
                backgroundExecutor = Executor { },
            )
            val directClient = OkHttpClient.Builder()
                .dns(dns)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val client = OkHttpClient.Builder()
                .dns(dns)
                .retryOnConnectionFailure(false)
                .addInterceptor(
                    ExperimentalGithubDirectAccessInterceptor(
                        routeResolvers = listOf(resolver),
                        directCallFactory = directClient,
                    ),
                )
                .build()
            val request = Request.Builder()
                .url("https://steamcommunity.com/workshop/browse/?appid=646570")
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("first-download", response.body.string())
            }
            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("second-download", response.body.string())
            }

            assertEquals(1, badForwardServer.requestCount)
            assertEquals(2, goodForwardServer.requestCount)
            assertEquals(1, apiServer.requestCount)
            assertEquals(goodTarget, routeStore.persisted!!.route.forwardTargets.first())
        } finally {
            badForwardServer.close()
            goodForwardServer.close()
        }
    }

    @Test
    fun interceptor_concurrentWorkshopRequests_probeEveryServerAndAvoidFailed403Route() {
        val badForwardServer = MockWebServer()
        val goodForwardServer = MockWebServer()
        val timeoutForwardServer = MockWebServer()
        badForwardServer.start()
        goodForwardServer.start()
        timeoutForwardServer.start()
        try {
            val badTarget = "http://bad-community.test:${badForwardServer.port}"
            val goodTarget = "http://good-community.test:${goodForwardServer.port}"
            val timeoutTarget = "http://timeout-community.test:${timeoutForwardServer.port}"
            val targets = listOf(badTarget, goodTarget, timeoutTarget)
            val routePayload = """
                {
                  "🦓": [
                    {
                      "Items": [
                        {
                          "MatchDomainNames": "steamcommunity.com;www.steamcommunity.com",
                          "ListenDomainNames": "steamcommunity.com;www.steamcommunity.com",
                          "ForwardDomainNames": "$badTarget;$goodTarget;$timeoutTarget",
                          "ProxyType": 0,
                          "IgnoreSSLCertVerification": true,
                          "Checked": true
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent()
            repeat(8) {
                apiServer.enqueue(MockResponse.Builder().code(200).body(routePayload).build())
            }

            // This is the reported failure: the forward endpoint answers 403 while the
            // logical Host remains steamcommunity.com. It is an HTTP response, so the
            // current interceptor returns it instead of trying the next candidate.
            badForwardServer.enqueue(MockResponse.Builder().code(403).body("bad-route").build())
            goodForwardServer.enqueue(MockResponse.Builder().code(200).body("workshop-ok").build())
            timeoutForwardServer.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("too-late")
                    .bodyDelay(PROBE_TIMEOUT_MS * 4, TimeUnit.MILLISECONDS)
                    .build(),
            )
            repeat(8) {
                badForwardServer.enqueue(MockResponse.Builder().code(403).body("bad-route").build())
                goodForwardServer.enqueue(MockResponse.Builder().code(200).body("workshop-ok").build())
            }

            val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
            val probeResults = probeWorkshopForwardTargetsConcurrently(
                targets = targets,
                dns = dns,
            )
            assertEquals("http-403", probeResults.getValue(badTarget).outcome)
            assertEquals("success", probeResults.getValue(goodTarget).outcome)
            assertEquals("timeout", probeResults.getValue(timeoutTarget).outcome)

            val routeStore = object : WattToolkitGithubRouteStore {
                var persisted: PersistedWattToolkitGithubRoute? = PersistedWattToolkitGithubRoute(
                    route = WattToolkitGithubRoute(
                        logicalHosts = setOf("steamcommunity.com", "www.steamcommunity.com"),
                        forwardTargets = listOf(badTarget),
                    ),
                    cachedAtMs = 1_000L,
                )

                override fun load(): PersistedWattToolkitGithubRoute? = persisted

                override fun save(route: PersistedWattToolkitGithubRoute) {
                    persisted = route
                }

                override fun clear() {
                    persisted = null
                }
            }
            val resolver = WattToolkitGithubRouteResolver(
                routeProfile = SteamCommunityWattToolkitRouteProfile,
                client = OkHttpClient.Builder().dns(dns).build(),
                projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
                routeStore = routeStore,
                forwardTargetProbe = { target ->
                    val result = probeResults.getValue(target)
                    when (result.outcome) {
                        "success" -> WattToolkitForwardTargetProbe(3, 3, result.elapsedMs)
                        else -> WattToolkitForwardTargetProbe.failed()
                    }
                },
                backgroundExecutor = Executor { },
            )
            val directClient = OkHttpClient.Builder()
                .dns(dns)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val client = OkHttpClient.Builder()
                .dns(dns)
                .retryOnConnectionFailure(false)
                .dispatcher(
                    okhttp3.Dispatcher(
                        Executors.newFixedThreadPool(8),
                    ).apply {
                        maxRequests = 8
                        maxRequestsPerHost = 8
                    },
                )
                .addInterceptor(
                    ExperimentalGithubDirectAccessInterceptor(
                        routeResolvers = listOf(resolver),
                        directCallFactory = directClient,
                    ),
                )
                .build()

            val executor = Executors.newFixedThreadPool(8)
            try {
                val start = java.util.concurrent.CountDownLatch(1)
                val futures = (1..8).map { requestIndex ->
                    executor.submit<Pair<Int, String>> {
                        start.await(5, TimeUnit.SECONDS)
                        client.newCall(
                            Request.Builder()
                                .url("https://steamcommunity.com/workshop/browse/?appid=${646570 + requestIndex}")
                                .build(),
                        ).execute().use { response ->
                            response.code to response.body.string()
                        }
                    }
                }
                start.countDown()
                val responses = futures.map { it.get(20, TimeUnit.SECONDS) }

                // Until 403 is classified as a failed forward route, this assertion
                // reproduces the bug and documents the required launcher behavior.
                assertTrue("all browse requests must avoid the 403 route: $responses", responses.all { it.first == 200 })
                assertTrue(responses.all { it.second == "workshop-ok" })
            } finally {
                executor.shutdownNow()
            }

            assertTrue("the failing route must have been attempted", badForwardServer.requestCount > 0)
            assertTrue("the healthy route must serve every request", goodForwardServer.requestCount >= 8)
            repeat(goodForwardServer.requestCount) {
                assertEquals("steamcommunity.com", goodForwardServer.takeRequest().headers["Host"])
            }
            assertEquals(1, timeoutForwardServer.requestCount)
        } finally {
            badForwardServer.close()
            goodForwardServer.close()
            timeoutForwardServer.close()
        }
    }

    @Test
    fun interceptor_followsCdnRedirectWhenWattHasNoRouteForTheInitialHost() {
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
                              "MatchDomainNames": "st.dl.eccdnx.com",
                              "ListenDomainNames": "st.dl.eccdnx.com",
                              "ForwardDomainNames": "http://cdn.queniuqe.com:${steamStoreForwardServer.port}",
                              "ProxyType": 1,
                              "IgnoreSSLCertVerification": true
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        steamContentForwardServer.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader(
                    "Location",
                    "http://redirected-cdn.test:${steamStoreForwardServer.port}/manifest",
                )
                .build(),
        )
        steamStoreForwardServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("manifest-bytes")
                .build(),
        )

        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamContentCdnWattToolkitRouteProfile,
            client = OkHttpClient.Builder().dns(dns).build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
            backgroundExecutor = Executor { /* no background work in this unit test */ },
        )
        val runtime = ExperimentalGithubDirectAccessRuntime(
            resolvers = listOf(resolver),
            hostnameVerifier = GithubDirectHostnameVerifier { host ->
                resolver.allowsUnsafeHostnameBypass(host)
            },
            directHttpClient = OkHttpClient.Builder()
                .dns(dns)
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )
        val client = OkHttpClient.Builder()
            .dns(dns)
            .addInterceptor(
                ExperimentalGithubDirectAccessInterceptor(
                    routeResolvers = runtime.resolvers,
                    directCallFactory = runtime.directHttpClient,
                ),
            )
            .build()

        client.newCall(
            Request.Builder()
                .url("http://xz.pphimalayanrt.com:${steamContentForwardServer.port}/depot/646571/manifest")
                .build(),
        ).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("redirected-cdn.test", response.request.url.host)
            assertEquals("manifest-bytes", response.body.string())
        }

        assertEquals("/accelerator/projectgroups", apiServer.takeRequest(5, TimeUnit.SECONDS)?.url?.encodedPath)
        assertEquals("/depot/646571/manifest", steamContentForwardServer.takeRequest(5, TimeUnit.SECONDS)?.url?.encodedPath)
        assertEquals("/manifest", steamStoreForwardServer.takeRequest(5, TimeUnit.SECONDS)?.url?.encodedPath)
    }

    @Test
    fun steamPipeCdnRedirect_allowsHttpSteamContentDepotChunkDownload() {
        steamContentForwardServer.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader(
                    "Location",
                    "http://cache1-steamcontent.test.steamcontent.com:${steamStoreForwardServer.port}/depot/646570/chunk/abcdef",
                )
                .build(),
        )
        steamStoreForwardServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("chunk-bytes")
                .build(),
        )

        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val client = OkHttpClient.Builder()
            .dns(dns)
            .followRedirects(false)
            .followSslRedirects(false)
            .addHttpsOnlyTransport(::allowsSteamContentCdnHttp)
            .addInterceptor(
                CredentialSafeRedirectInterceptor(
                    requireHttps = true,
                    allowInsecureUrl = ::allowsSteamContentCdnHttp,
                ),
            )
            .build()

        client.newCall(
            Request.Builder()
                .url("http://st.dl.eccdnx.com:${steamContentForwardServer.port}/depot/646570/chunk/abcdef")
                .build(),
        ).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("cache1-steamcontent.test.steamcontent.com", response.request.url.host)
            assertEquals("chunk-bytes", response.body.string())
        }

        assertEquals("/depot/646570/chunk/abcdef", steamContentForwardServer.takeRequest().url.encodedPath)
        assertEquals("/depot/646570/chunk/abcdef", steamStoreForwardServer.takeRequest().url.encodedPath)
    }

    @Test
    fun interceptor_remembersFailedSteamPipeCdnTargetAndSkipsItOnLaterChunkDownload() {
        val badForwardServer = MockWebServer()
        val goodForwardServer = MockWebServer()
        badForwardServer.start()
        goodForwardServer.start()
        try {
            val badTarget = "http://bad-cdn.test:${badForwardServer.port}"
            val goodTarget = "http://good-cdn.test:${goodForwardServer.port}"
            val routePayload = """
                {
                  "🦓": [
                    {
                      "Items": [
                        {
                          "MatchDomainNames": "st.dl.eccdnx.com;xz.pphimalayanrt.com;dl.steam.clngaa.com;files.steam.nsclouds.cn",
                          "ListenDomainNames": "st.dl.eccdnx.com;xz.pphimalayanrt.com;dl.steam.clngaa.com;files.steam.nsclouds.cn",
                          "ForwardDomainNames": "$badTarget;$goodTarget",
                          "ProxyType": 1,
                          "IgnoreSSLCertVerification": true,
                          "Checked": false
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent()
            apiServer.enqueue(MockResponse.Builder().code(200).body(routePayload).build())
            badForwardServer.enqueue(MockResponse.Builder().code(404).body("bad-cdn").build())
            goodForwardServer.enqueue(MockResponse.Builder().code(200).body("first-chunk").build())
            goodForwardServer.enqueue(MockResponse.Builder().code(200).body("second-chunk").build())

            val routeStore = object : WattToolkitGithubRouteStore {
                var persisted: PersistedWattToolkitGithubRoute? = PersistedWattToolkitGithubRoute(
                    route = WattToolkitGithubRoute(
                        logicalHosts = setOf(
                            "st.dl.eccdnx.com",
                            "xz.pphimalayanrt.com",
                            "dl.steam.clngaa.com",
                            "files.steam.nsclouds.cn",
                        ),
                        forwardTargets = listOf(badTarget, goodTarget),
                    ),
                    cachedAtMs = 1_000L,
                )

                override fun load(): PersistedWattToolkitGithubRoute? = persisted

                override fun save(route: PersistedWattToolkitGithubRoute) {
                    persisted = route
                }

                override fun clear() {
                    persisted = null
                }
            }
            val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
            val resolver = WattToolkitGithubRouteResolver(
                routeProfile = SteamContentCdnWattToolkitRouteProfile,
                client = OkHttpClient.Builder().dns(dns).build(),
                projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
                routeStore = routeStore,
                nowProvider = { 2_000L },
                forwardTargetProbe = { target ->
                    if (target == goodTarget) {
                        WattToolkitForwardTargetProbe(successes = 1, attempts = 1, latencyMs = 1L)
                    } else {
                        WattToolkitForwardTargetProbe.failed()
                    }
                },
                officialTargetProbe = { _, _ -> WattToolkitForwardTargetProbe.failed() },
                backgroundExecutor = Executor { },
            )
            val directClient = OkHttpClient.Builder()
                .dns(dns)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val client = OkHttpClient.Builder()
                .dns(dns)
                .retryOnConnectionFailure(false)
                .addInterceptor(
                    ExperimentalGithubDirectAccessInterceptor(
                        routeResolvers = listOf(resolver),
                        directCallFactory = directClient,
                    ),
                )
                .build()
            val request = Request.Builder()
                .url("https://st.dl.eccdnx.com/depot/646570/chunk/abcdef")
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("first-chunk", response.body.string())
            }
            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("second-chunk", response.body.string())
            }

            assertEquals(1, badForwardServer.requestCount)
            assertEquals(2, goodForwardServer.requestCount)
            assertEquals(1, apiServer.requestCount)
            assertEquals(goodTarget, routeStore.persisted!!.route.forwardTargets.first())
        } finally {
            badForwardServer.close()
            goodForwardServer.close()
        }
    }

    @Test
    fun interceptor_routesSteamContentCdnRequestThroughWattForwardTarget() {
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
                              "MatchDomainNames": "st.dl.eccdnx.com;xz.pphimalayanrt.com;dl.steam.clngaa.com;files.steam.nsclouds.cn",
                              "ListenDomainNames": "st.dl.eccdnx.com;xz.pphimalayanrt.com;dl.steam.clngaa.com;files.steam.nsclouds.cn",
                              "ForwardDomainNames": "http://cdn.queniuqe.com:${steamContentForwardServer.port}",
                              "ProxyType": 1,
                              "IgnoreSSLCertVerification": true,
                              "Checked": false
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        steamContentForwardServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("manifest-bytes")
                .build(),
        )

        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamContentCdnWattToolkitRouteProfile,
            client = OkHttpClient.Builder().dns(dns).build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
        )
        val runtime = ExperimentalGithubDirectAccessRuntime(
            resolvers = listOf(resolver),
            hostnameVerifier = GithubDirectHostnameVerifier { host ->
                resolver.allowsUnsafeHostnameBypass(host)
            },
            directHttpClient = OkHttpClient.Builder()
                .dns(dns)
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )
        val client = OkHttpClient.Builder()
            .dns(dns)
            .addInterceptor(
                ExperimentalGithubDirectAccessInterceptor(
                    routeResolvers = runtime.resolvers,
                    directCallFactory = runtime.directHttpClient,
                ),
            )
            .build()

        client.newCall(
            Request.Builder()
                .url("https://st.dl.eccdnx.com/depot/646571/manifest/1616206291221819177/5")
                .build(),
        ).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("st.dl.eccdnx.com", response.request.url.host)
        }

        val routeRequest = apiServer.takeRequest()
        assertEquals("/accelerator/projectgroups", routeRequest.url.encodedPath)

        val forwardedRequest = steamContentForwardServer.takeRequest()
        assertEquals(
            "/depot/646571/manifest/1616206291221819177/5",
            forwardedRequest.url.encodedPath,
        )
        assertEquals("st.dl.eccdnx.com", forwardedRequest.headers["Host"])
    }

    /**
     * The acceleration interceptor answers routed requests on its own call factory instead of
     * [okhttp3.Interceptor.Chain.proceed], so OkHttp's BridgeInterceptor — which normally applies
     * the cookie jar — never runs. Without an explicit bridge the Steam session cookie is dropped
     * and Steam serves the logged-out view, which made login-gated workshop searches return nothing.
     */
    @Test
    fun interceptor_sendsCookieJarCookiesOnAcceleratedRequest() {
        enqueueSteamStoreRoute()
        steamStoreForwardServer.enqueue(MockResponse.Builder().code(200).body("ok").build())

        val jar = RecordingCookieJar()
        jar.seed(
            "https://api.steampowered.com/".toHttpUrl(),
            Cookie.Builder()
                .name("steamLoginSecure")
                .value("76561198000000000%7C%7Ctoken")
                .domain("steampowered.com")
                .path("/")
                .build(),
        )

        acceleratedClientWithCookieJar(jar).newCall(
            Request.Builder()
                .url("https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/")
                .build(),
        ).execute().use { response -> assertEquals(200, response.code) }

        apiServer.takeRequest()
        val forwarded = steamStoreForwardServer.takeRequest()
        assertEquals(
            "steamLoginSecure=76561198000000000%7C%7Ctoken",
            forwarded.headers["Cookie"],
        )
        // The jar must be consulted for the logical host, never the forward target's hostname.
        assertEquals(listOf("api.steampowered.com"), jar.loadedHosts)
    }

    /** `Set-Cookie` from an accelerated hop must be stored against the logical host. */
    @Test
    fun interceptor_persistsResponseCookiesAgainstLogicalHost() {
        enqueueSteamStoreRoute()
        steamStoreForwardServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Set-Cookie", "sessionid=abc123; Path=/")
                .body("ok")
                .build(),
        )

        val jar = RecordingCookieJar()

        acceleratedClientWithCookieJar(jar).newCall(
            Request.Builder()
                .url("https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/")
                .build(),
        ).execute().use { response -> assertEquals(200, response.code) }

        apiServer.takeRequest()
        steamStoreForwardServer.takeRequest()
        assertEquals(listOf("api.steampowered.com"), jar.savedHosts)
        assertEquals(
            listOf("sessionid=abc123"),
            jar.saved.map { cookie -> "${cookie.name}=${cookie.value}" },
        )
    }

    /** A client without a cookie jar must keep working and must not send a Cookie header. */
    @Test
    fun interceptor_omitsCookieHeaderWhenNoCookieJarIsConfigured() {
        enqueueSteamStoreRoute()
        steamStoreForwardServer.enqueue(MockResponse.Builder().code(200).body("ok").build())

        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamStoreWattToolkitRouteProfile,
            client = OkHttpClient.Builder().dns(dns).build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
        )
        val client = OkHttpClient.Builder()
            .dns(dns)
            .addInterceptor(
                ExperimentalGithubDirectAccessInterceptor(
                    routeResolvers = listOf(resolver),
                    directCallFactory = OkHttpClient.Builder()
                        .dns(dns)
                        .followRedirects(false)
                        .build(),
                ),
            )
            .build()

        client.newCall(
            Request.Builder()
                .url("https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/")
                .build(),
        ).execute().use { response -> assertEquals(200, response.code) }

        apiServer.takeRequest()
        assertNull(steamStoreForwardServer.takeRequest().headers["Cookie"])
    }

    private fun enqueueSteamStoreRoute() {
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
                              "MatchDomainNames": "store.steampowered.com;api.steampowered.com",
                              "ListenDomainNames": "store.steampowered.com;api.steampowered.com",
                              "ForwardDomainNames": "http://steamstore.rmbgame.net:${steamStoreForwardServer.port}",
                              "ProxyType": 0,
                              "IgnoreSSLCertVerification": true
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
    }

    private fun acceleratedClientWithCookieJar(jar: CookieJar): OkHttpClient {
        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamStoreWattToolkitRouteProfile,
            client = OkHttpClient.Builder().dns(dns).build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
        )
        val runtime = ExperimentalGithubDirectAccessRuntime(
            resolvers = listOf(resolver),
            hostnameVerifier = GithubDirectHostnameVerifier { host ->
                resolver.allowsUnsafeHostnameBypass(host)
            },
            directHttpClient = OkHttpClient.Builder()
                .dns(dns)
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )
        return OkHttpClient.Builder()
            .dns(dns)
            .addExperimentalGithubDirectAccess(runtime)
            .withAcceleratedCookieJar(jar)
            .build()
    }

    private fun probeWorkshopForwardTargetsConcurrently(
        targets: List<String>,
        dns: Dns,
    ): Map<String, WorkshopForwardProbeResult> {
        val executor = Executors.newFixedThreadPool(targets.size)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val futures = targets.associateWith { target ->
                executor.submit<WorkshopForwardProbeResult> {
                    start.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    probeWorkshopForwardTarget(target, dns)
                }
            }
            start.countDown()
            return futures.mapValues { (_, future) ->
                future.get(PROBE_TIMEOUT_MS * 3, TimeUnit.MILLISECONDS)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun probeWorkshopForwardTarget(
        target: String,
        dns: Dns,
    ): WorkshopForwardProbeResult {
        val startedAtNs = System.nanoTime()
        val client = OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val outcome = try {
            client.newCall(
                Request.Builder()
                    .url("$target/workshop/browse/?appid=646570")
                    .header("Host", "steamcommunity.com")
                    .build(),
            ).execute().use { response ->
                response.body.string()
                if (response.isSuccessful) "success" else "http-${response.code}"
            }
        } catch (error: java.io.InterruptedIOException) {
            "timeout"
        } catch (error: java.io.IOException) {
            "io-${error::class.simpleName}"
        }
        val elapsedMs = ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(1L)
        println("Workshop forward probe target=$target outcome=$outcome elapsedMs=$elapsedMs")
        return WorkshopForwardProbeResult(outcome = outcome, elapsedMs = elapsedMs)
    }

    private data class WorkshopForwardProbeResult(
        val outcome: String,
        val elapsedMs: Long,
    )

    private companion object {
        const val PROBE_TIMEOUT_MS = 250L
    }

    private class RecordingCookieJar : CookieJar {
        private val stored = mutableListOf<Pair<HttpUrl, Cookie>>()
        val loadedHosts = mutableListOf<String>()
        val savedHosts = mutableListOf<String>()
        val saved = mutableListOf<Cookie>()

        fun seed(url: HttpUrl, cookie: Cookie) {
            stored += url to cookie
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            loadedHosts += url.host
            return stored.filter { (storedUrl, _) -> storedUrl.host == url.host }
                .map { (_, cookie) -> cookie }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            savedHosts += url.host
            saved += cookies
        }
    }
}

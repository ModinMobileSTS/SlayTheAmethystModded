package io.stamethyst.backend.github

import java.net.InetAddress
import java.net.ProtocolException
import java.io.File
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WattToolkitAcceleratedHttpTest {
    private lateinit var apiServer: MockWebServer
    private lateinit var githubApiForwardServer: MockWebServer
    private lateinit var githubWebForwardServer: MockWebServer
    private lateinit var githubAssetForwardServer: MockWebServer

    @Before
    fun setUp() {
        apiServer = MockWebServer()
        githubApiForwardServer = MockWebServer()
        githubWebForwardServer = MockWebServer()
        githubAssetForwardServer = MockWebServer()
        apiServer.start()
        githubApiForwardServer.start()
        githubWebForwardServer.start()
        githubAssetForwardServer.start()
    }

    @After
    fun tearDown() {
        apiServer.close()
        githubApiForwardServer.close()
        githubWebForwardServer.close()
        githubAssetForwardServer.close()
        WattToolkitAcceleratedHttp.clearRuntimeCacheForTests()
    }

    @Test
    fun routeResolver_matchesGithubAssetHostsFromListenDomainNames() {
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
                              "MatchDomainNames": "githubusercontent.com;raw.github.com",
                              "ListenDomainNames": "raw.github.com;raw.githubusercontent.com;objects.githubusercontent.com;release-assets.githubusercontent.com",
                              "ForwardDomainNames": "http://githubusercontent.rmbgame.net:${githubAssetForwardServer.port}",
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

        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = GithubUserContentWattToolkitRouteProfile,
            client = OkHttpClient.Builder().build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
        )

        val route = resolver.resolveRouteForHost("objects.githubusercontent.com")

        assertNotNull(route)
        assertTrue(route!!.logicalHosts.contains("objects.githubusercontent.com"))
        assertTrue(route.logicalHosts.contains("release-assets.githubusercontent.com"))
        assertEquals(
            "githubusercontent.rmbgame.net",
            route.buildForwardedUrl(
                "https://objects.githubusercontent.com/github-production-release-asset-test/app-release.apk".toHttpUrl(),
            ).host,
        )
    }

    @Test
    fun requestClients_pickFallsBackToPlainClientWhenAccelerationIsNotAllowed() {
        var accelerationAllowed = true
        val plainClient = OkHttpClient.Builder().build()
        val acceleratedClient = OkHttpClient.Builder().build()
        val clients = GithubRequestClients(
            plainClient = plainClient,
            acceleratedClient = acceleratedClient,
            accelerationAllowedProvider = { accelerationAllowed },
        )

        assertSame(acceleratedClient, clients.pick(useAcceleration = true))
        assertSame(plainClient, clients.pick(useAcceleration = false))

        accelerationAllowed = false

        assertSame(plainClient, clients.pick(useAcceleration = true))
    }

    @Test
    fun routeResolver_comparesOfficialAndWattWithProbeResults() {
        val routePayload =
            """
            {
              "🦓": [
                {
                  "Items": [
                    {
                      "MatchDomainNames": "api.github.com",
                      "ForwardDomainNames": "forward.example.test",
                      "ProxyType": 0
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        apiServer.enqueue(MockResponse.Builder().code(200).body(routePayload).build())
        apiServer.enqueue(MockResponse.Builder().code(200).body(routePayload).build())

        val profile = WattToolkitRouteProfile(
            name = "probe-comparison",
            cacheFileName = "unused.json",
            supportedHosts = setOf("api.github.com"),
            bootstrapForwardTargets = emptyList(),
        )
        val forwardProbe = WattToolkitForwardTargetProbe(
            successes = 3,
            attempts = 3,
            latencyMs = 80L,
        )
        val fasterOfficialProbe = WattToolkitForwardTargetProbe(
            successes = 3,
            attempts = 3,
            latencyMs = 10L,
        )
        val slowerOfficialProbe = WattToolkitForwardTargetProbe(
            successes = 2,
            attempts = 3,
            latencyMs = 100L,
        )
        val firstResolver = WattToolkitGithubRouteResolver(
            routeProfile = profile,
            client = OkHttpClient.Builder().build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
            forwardTargetProbe = { forwardProbe },
            officialTargetProbe = { _, _ -> fasterOfficialProbe },
            backgroundExecutor = java.util.concurrent.Executor { },
        )
        val secondResolver = WattToolkitGithubRouteResolver(
            routeProfile = profile,
            client = OkHttpClient.Builder().build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
            forwardTargetProbe = { forwardProbe },
            officialTargetProbe = { _, _ -> slowerOfficialProbe },
            backgroundExecutor = java.util.concurrent.Executor { },
        )

        assertTrue(firstResolver.resolveRouteForHost("api.github.com")!!.isOfficial)
        assertFalse(secondResolver.resolveRouteForHost("api.github.com")!!.isOfficial)
    }

    @Test
    fun routeStore_persistsOfficialRouteWithoutForwardTargets() {
        val file = File.createTempFile("watt-official-route", ".json")
        try {
            val store = FileBackedWattToolkitGithubRouteStore(
                file = file,
                fallbackLogicalHosts = setOf("api.github.com"),
            )
            store.save(
                PersistedWattToolkitGithubRoute(
                    route = WattToolkitGithubRoute(
                        logicalHosts = setOf("api.github.com"),
                        forwardTargets = emptyList(),
                        isOfficial = true,
                    ),
                    cachedAtMs = 42L,
                ),
            )

            val restored = store.load()
            assertNotNull(restored)
            assertTrue(restored!!.route.isOfficial)
            assertTrue(restored.route.forwardTargets.isEmpty())
            assertEquals(42L, restored.cachedAtMs)
        } finally {
            file.delete()
        }
    }

    @Test
    fun secureOriginComparison_includesEffectiveHttpsPort() {
        val defaultPort = "https://github.example.test/releases".toHttpUrl()
        val explicitDefaultPort = "https://github.example.test:443/releases".toHttpUrl()
        val alternatePort = "https://github.example.test:8443/releases".toHttpUrl()

        assertTrue(defaultPort.hasSameSecureOrigin(explicitDefaultPort))
        assertFalse(defaultPort.hasSameSecureOrigin(alternatePort))
    }

    @Test
    fun crossOrigin307And308_redirectsDropBodyAndCredentials() {
        listOf(307, 308).forEach { responseCode ->
            val previous = Request.Builder()
                .url("https://github.example.test:8443/upload")
                .post("release-payload".toRequestBody())
                .header("Authorization", "Bearer secret")
                .header("Cookie", "session=secret")
                .build()
            val redirected = buildCredentialSafeRedirectRequest(
                previousLogicalRequest = previous,
                redirectUrl = "https://mirror.example.test:8443/upload".toHttpUrl(),
                responseCode = responseCode,
                preserveSensitiveHeaders = false,
            )

            assertEquals("GET", redirected.method)
            assertNull(redirected.body)
            assertNull(redirected.header("Authorization"))
            assertNull(redirected.header("Cookie"))
            assertNull(redirected.header("Content-Type"))
        }
    }

    @Test
    fun httpsRequiredRouteClient_rejectsCleartextBeforeNetwork() {
        val server = MockWebServer()
        server.start()
        try {
            val error = runCatching {
                defaultWattToolkitRouteClient(requireHttps = true)
                    .newCall(
                        Request.Builder()
                            .url(server.url("/route"))
                            .head()
                            .build(),
                    )
                    .execute()
            }.exceptionOrNull()

            assertTrue(error is ProtocolException)
            assertEquals(0, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun interceptor_runsSelectedOfficialRequestThroughOriginalClient() {
        enqueueGithubApiRoute("http://forward.test:${githubWebForwardServer.port}")
        githubApiForwardServer.enqueue(MockResponse.Builder().code(200).body("official-ok").build())
        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = officialPreferredResolver(dns)
        val client = OkHttpClient.Builder()
            .dns(dns)
            .addExperimentalGithubDirectAccess(
                ExperimentalGithubDirectAccessRuntime(
                    resolvers = listOf(resolver),
                    hostnameVerifier = GithubDirectHostnameVerifier { false },
                    directHttpClient = OkHttpClient.Builder().dns(dns).build(),
                ),
            )
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Original-Client", "yes").build())
            }
            .build()

        client.newCall(
            Request.Builder()
                .url("http://api.github.com:${githubApiForwardServer.port}/official")
                .build(),
        ).execute().use { response ->
            assertEquals("official-ok", response.body.string())
        }

        assertEquals("yes", githubApiForwardServer.takeRequest().headers["X-Original-Client"])
        assertEquals(0, githubWebForwardServer.requestCount)
    }

    @Test
    fun interceptor_fallsBackToFirstWattTargetWhenOfficialConnectionFails() {
        enqueueGithubApiRoute("http://forward.test:${githubWebForwardServer.port}")
        githubWebForwardServer.enqueue(MockResponse.Builder().code(200).body("watt-ok").build())
        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = officialPreferredResolver(dns)
        val client = OkHttpClient.Builder()
            .dns(dns)
            .retryOnConnectionFailure(false)
            .addExperimentalGithubDirectAccess(
                ExperimentalGithubDirectAccessRuntime(
                    resolvers = listOf(resolver),
                    hostnameVerifier = GithubDirectHostnameVerifier { false },
                    directHttpClient = OkHttpClient.Builder()
                        .dns(dns)
                        .retryOnConnectionFailure(false)
                        .build(),
                ),
            )
            .build()

        client.newCall(
            Request.Builder()
                .url("http://api.github.com:1/fallback")
                .build(),
        ).execute().use { response ->
            assertEquals("watt-ok", response.body.string())
        }

        val forwarded = githubWebForwardServer.takeRequest()
        assertEquals("/fallback", forwarded.url.encodedPath)
        assertEquals("api.github.com", forwarded.headers["Host"])
    }

    @Test
    fun interceptor_refreshesWattRouteAfterOfficialAndInitialWattFail() {
        enqueueGithubApiRoute("http://failed-forward.test:1")
        enqueueGithubApiRoute("http://forward.test:${githubWebForwardServer.port}")
        githubWebForwardServer.enqueue(MockResponse.Builder().code(200).body("refreshed-watt-ok").build())
        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = officialPreferredResolver(dns)
        val client = OkHttpClient.Builder()
            .dns(dns)
            .retryOnConnectionFailure(false)
            .addExperimentalGithubDirectAccess(
                ExperimentalGithubDirectAccessRuntime(
                    resolvers = listOf(resolver),
                    hostnameVerifier = GithubDirectHostnameVerifier { false },
                    directHttpClient = OkHttpClient.Builder()
                        .dns(dns)
                        .retryOnConnectionFailure(false)
                        .build(),
                ),
            )
            .build()

        client.newCall(
            Request.Builder()
                .url("http://api.github.com:1/refreshed-fallback")
                .build(),
        ).execute().use { response ->
            assertEquals("refreshed-watt-ok", response.body.string())
        }

        val forwarded = githubWebForwardServer.takeRequest()
        assertEquals("/refreshed-fallback", forwarded.url.encodedPath)
        assertEquals("api.github.com", forwarded.headers["Host"])
        assertEquals(2, apiServer.requestCount)
    }

    @Test
    fun interceptor_routesOfficialGithubRequestsAcrossReleaseRedirectChain() {
        val routePayload =
            """
            {
              "🦓": [
                {
                  "Items": [
                    {
                      "MatchDomainNames": "api.github.com",
                      "ForwardDomainNames": "http://githubapi.rmbgame.net:${githubApiForwardServer.port}",
                      "ProxyType": 0,
                      "IgnoreSSLCertVerification": true
                    },
                    {
                      "MatchDomainNames": "github.com",
                      "ForwardDomainNames": "http://github.rmbgame.net:${githubWebForwardServer.port}",
                      "ProxyType": 0,
                      "IgnoreSSLCertVerification": true
                    },
                    {
                      "MatchDomainNames": "githubusercontent.com;raw.github.com",
                      "ListenDomainNames": "raw.github.com;raw.githubusercontent.com;objects.githubusercontent.com;release-assets.githubusercontent.com",
                      "ForwardDomainNames": "http://githubusercontent.rmbgame.net:${githubAssetForwardServer.port}",
                      "ProxyType": 0,
                      "IgnoreSSLCertVerification": true
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        repeat(3) {
            apiServer.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body(routePayload)
                    .build(),
            )
        }
        githubApiForwardServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "tag_name": "v1.0.1",
                      "published_at": "2026-03-12T10:00:00Z",
                      "body": "# Update\n- test",
                      "assets": [
                        {
                          "name": "SlayTheAmethyst-stable-1.0.1.apk",
                          "browser_download_url": "https://github.com/ModinMobileSTS/SlayTheAmethystModded/releases/download/v1.0.1/SlayTheAmethyst-stable-1.0.1.apk"
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        githubWebForwardServer.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader(
                    "Location",
                    "https://objects.githubusercontent.com/github-production-release-asset-test/app-release.apk",
                )
                .build(),
        )
        githubAssetForwardServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Length", "1024")
                .build(),
        )

        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolvers = listOf(
            WattToolkitGithubRouteResolver(
                routeProfile = GithubApiWattToolkitRouteProfile,
                client = OkHttpClient.Builder().dns(dns).build(),
                projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
            ),
            WattToolkitGithubRouteResolver(
                routeProfile = GithubWebWattToolkitRouteProfile,
                client = OkHttpClient.Builder().dns(dns).build(),
                projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
            ),
            WattToolkitGithubRouteResolver(
                routeProfile = GithubUserContentWattToolkitRouteProfile,
                client = OkHttpClient.Builder().dns(dns).build(),
                projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
            ),
        )
        val runtime = ExperimentalGithubDirectAccessRuntime(
            resolvers = resolvers,
            hostnameVerifier = GithubDirectHostnameVerifier { host ->
                resolvers.any { resolver -> resolver.allowsUnsafeHostnameBypass(host) }
            },
            directHttpClient = OkHttpClient.Builder()
                .dns(dns)
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )
        val client = OkHttpClient.Builder()
            .dns(dns)
            .addExperimentalGithubDirectAccess(runtime)
            .build()

        client.newCall(
            Request.Builder()
                .url("https://api.github.com/repos/ModinMobileSTS/SlayTheAmethystModded/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build(),
        ).execute().use { response ->
            assertEquals(200, response.code)
            assertTrue(response.body.string().contains("\"tag_name\": \"v1.0.1\""))
        }

        client.newCall(
            Request.Builder()
                .url("https://github.com/ModinMobileSTS/SlayTheAmethystModded/releases/download/v1.0.1/SlayTheAmethyst-stable-1.0.1.apk")
                .head()
                .build(),
        ).execute().use { response ->
            assertEquals(200, response.code)
        }

        repeat(3) {
            apiServer.takeRequest()
        }

        val metadataRequest = githubApiForwardServer.takeRequest()
        assertEquals("api.github.com", metadataRequest.headers["Host"])
        assertEquals(
            "/repos/ModinMobileSTS/SlayTheAmethystModded/releases/latest",
            metadataRequest.url.encodedPath,
        )

        val releaseRequest = githubWebForwardServer.takeRequest()
        assertEquals("github.com", releaseRequest.headers["Host"])
        assertEquals(
            "/ModinMobileSTS/SlayTheAmethystModded/releases/download/v1.0.1/SlayTheAmethyst-stable-1.0.1.apk",
            releaseRequest.url.encodedPath,
        )

        val assetRequest = githubAssetForwardServer.takeRequest()
        assertEquals("objects.githubusercontent.com", assetRequest.headers["Host"])
        assertEquals(
            "/github-production-release-asset-test/app-release.apk",
            assetRequest.url.encodedPath,
        )
    }

    private fun enqueueGithubApiRoute(forwardTarget: String) {
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
                              "MatchDomainNames": "api.github.com",
                              "ForwardDomainNames": "$forwardTarget",
                              "ProxyType": 0
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

    private fun officialPreferredResolver(dns: Dns): WattToolkitGithubRouteResolver =
        WattToolkitGithubRouteResolver(
            routeProfile = GithubApiWattToolkitRouteProfile,
            client = OkHttpClient.Builder().dns(dns).build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
            forwardTargetProbe = {
                WattToolkitForwardTargetProbe(successes = 3, attempts = 3, latencyMs = 80L)
            },
            officialTargetProbe = { _, _ ->
                WattToolkitForwardTargetProbe(successes = 3, attempts = 3, latencyMs = 10L)
            },
            backgroundExecutor = java.util.concurrent.Executor { },
        )
}

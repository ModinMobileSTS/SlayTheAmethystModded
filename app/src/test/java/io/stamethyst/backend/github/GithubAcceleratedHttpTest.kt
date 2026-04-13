package io.stamethyst.backend.github

import java.net.InetAddress
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GithubAcceleratedHttpTest {
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
        GithubAcceleratedHttp.clearRuntimeCacheForTests()
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
}

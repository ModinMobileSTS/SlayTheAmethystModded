package io.stamethyst.backend.update

import io.stamethyst.backend.github.GithubRequestClients
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LauncherUpdateServiceCheckTest {
    private val requests = Collections.synchronizedList(mutableListOf<Request>())

    @Before
    fun setUp() {
        GithubMirrorHealthStore.resetForTests()
    }

    @After
    fun tearDown() {
        GithubMirrorHealthStore.resetForTests()
    }

    @Test
    fun minigameReusesMetadataRaceAndWaitsPastStaleMirror() {
        val installed = io.stamethyst.backend.minigame.SlingBreakReleasesTest.parse("v0.0.1", 1)
        val staleReturned = CountDownLatch(1)
        val clients = clients { request ->
            assertTrue(request.url.encodedPath.endsWith("/repos/Apricityx/slingbreak/releases"))
            assertEquals("100", request.url.queryParameter("per_page"))
            if (request.url.host == "ghproxy.vip") {
                staleReturned.countDown()
                "[${io.stamethyst.backend.minigame.SlingBreakReleasesTest.release("v0.0.1", 1)}]"
            } else {
                assertTrue(staleReturned.await(2, TimeUnit.SECONDS))
                "[${io.stamethyst.backend.minigame.SlingBreakReleasesTest.release("v0.0.2", 2)}]"
            }
        }
        val result = LauncherUpdateService.checkForUpdates(clients, installed.rawTagName,
            listOf(UpdateSource.GH_PROXY_VIP, UpdateSource.OFFICIAL),
            target = io.stamethyst.backend.minigame.SlingBreakReleases.target(installed)) as UpdateCheckExecutionResult.Success
        assertTrue(result.hasUpdate)
        assertEquals("v0.0.2", result.release.rawTagName)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.header("Range") == null })
    }

    @Test
    fun newerSourceReturnsWithoutWaitingForSlowSourceOrProbingDownload() {
        val slowStarted = CountDownLatch(1)
        val slowStopped = CountDownLatch(1)
        val clients = clients { request ->
            when (request.url.host) {
                "ghproxy.vip" -> {
                    slowStarted.countDown()
                    try {
                        CountDownLatch(1).await(10, TimeUnit.SECONDS)
                        throw IOException("Slow source was not cancelled")
                    } finally {
                        slowStopped.countDown()
                    }
                }
                else -> {
                    assertTrue(slowStarted.await(2, TimeUnit.SECONDS))
                    release("1.1.0")
                }
            }
        }

        val result = LauncherUpdateService.checkForUpdates(
            clients, "1.0.0", listOf(UpdateSource.GH_PROXY_VIP, UpdateSource.OFFICIAL),
            timeoutMillis = 3_000,
        ) as UpdateCheckExecutionResult.Success

        assertTrue(result.hasUpdate)
        assertEquals("1.1.0", result.release.normalizedVersion)
        assertEquals(UpdateSource.OFFICIAL, result.metadataSource)
        assertEquals("Release notes", result.release.notesText)
        assertTrue(slowStopped.await(2, TimeUnit.SECONDS))
        assertMetadataRequestsOnly(2)
        // Cancelling the losing request is not a source failure.
        assertFalse(GithubMirrorHealthStore.isCoolingDown(UpdateSource.GH_PROXY_VIP))
    }

    @Test
    fun staleSourceDoesNotHideUpdateFromAnotherSource() {
        val staleReturned = CountDownLatch(1)
        val clients = clients { request ->
            if (request.url.host == "ghproxy.vip") {
                staleReturned.countDown()
                release("1.0.0")
            } else {
                assertTrue(staleReturned.await(2, TimeUnit.SECONDS))
                release("1.2.0")
            }
        }

        val result = LauncherUpdateService.checkForUpdates(
            clients, "1.0.0", listOf(UpdateSource.GH_PROXY_VIP, UpdateSource.OFFICIAL),
        ) as UpdateCheckExecutionResult.Success

        assertTrue(result.hasUpdate)
        assertEquals("1.2.0", result.release.normalizedVersion)
        assertMetadataRequestsOnly(2)
    }

    @Test
    fun failedOrInvalidSourcesDoNotHideUpdate() {
        val clients = clients { request ->
            when (request.url.host) {
                "ghproxy.vip" -> throw IOException("Source offline")
                "gh.llkk.cc" -> "<html>Invalid mirror response</html>"
                else -> release("1.1.0")
            }
        }

        val result = LauncherUpdateService.checkForUpdates(
            clients, "1.0.0",
            listOf(UpdateSource.GH_PROXY_VIP, UpdateSource.GH_LLKK, UpdateSource.OFFICIAL),
        ) as UpdateCheckExecutionResult.Success

        assertTrue(result.hasUpdate)
        assertEquals(UpdateSource.OFFICIAL, result.metadataSource)
    }

    @Test
    fun noUpdateChecksAllSourcesAndKeepsHighestReportedVersion() {
        val clients = clients { request ->
            release(if (request.url.host == "ghproxy.vip") "1.0.0" else "0.9.0")
        }

        val result = LauncherUpdateService.checkForUpdates(
            clients, "1.0.0", listOf(UpdateSource.GH_PROXY_VIP, UpdateSource.OFFICIAL),
        ) as UpdateCheckExecutionResult.Success

        assertFalse(result.hasUpdate)
        assertEquals("1.0.0", result.release.normalizedVersion)
        assertMetadataRequestsOnly(2)
    }

    @Test
    fun allSourcesFailedReturnsFailure() {
        val result = LauncherUpdateService.checkForUpdates(
            clients { throw IOException("Source offline") }, "1.0.0",
            listOf(UpdateSource.GH_PROXY_VIP, UpdateSource.OFFICIAL),
        ) as UpdateCheckExecutionResult.Failure

        assertTrue(result.errorSummary.contains("ghproxy.vip"))
        assertTrue(result.errorSummary.contains("GitHub"))
        assertMetadataRequestsOnly(2)
    }

    @Test
    fun incompleteChecksTimeOutWithoutReportingLatest() {
        val stopped = CountDownLatch(1)
        val clients = clients { request ->
            if (request.url.host == "ghproxy.vip") {
                try {
                    CountDownLatch(1).await(10, TimeUnit.SECONDS)
                    release("1.0.0")
                } finally {
                    stopped.countDown()
                }
            } else {
                release("1.0.0")
            }
        }

        val result = LauncherUpdateService.checkForUpdates(
            clients, "1.0.0", listOf(UpdateSource.GH_PROXY_VIP, UpdateSource.OFFICIAL),
            timeoutMillis = 500,
        ) as UpdateCheckExecutionResult.Failure

        assertTrue(result.errorSummary.contains("timed out"))
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
    }

    private fun clients(respond: (Request) -> String): GithubRequestClients {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(respond(request).toResponseBody())
                .build()
        }.build()
        return GithubRequestClients(client, client)
    }

    private fun assertMetadataRequestsOnly(expectedCount: Int) {
        assertEquals(expectedCount, requests.size)
        requests.forEach { request ->
            assertEquals("GET", request.method)
            assertTrue(request.url.encodedPath.endsWith("/releases/latest"))
            assertEquals(null, request.header("Range"))
        }
    }

    private fun release(version: String): String = """
        {
          "tag_name": "v$version",
          "body": "Release notes",
          "assets": [{
            "name": "launcher.apk",
            "browser_download_url": "https://unreachable.invalid/launcher.apk"
          }]
        }
    """.trimIndent()
}

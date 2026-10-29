package io.stamethyst.backend.github

import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.backend.update.GithubMirrorHealthStore
import java.io.IOException
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After

class GithubResourceRequestsTest {
    private val plain = OkHttpClient()
    private val accelerated = OkHttpClient()
    private val clients = GithubRequestClients(plain, accelerated)
    private val url = "https://raw.githubusercontent.com/owner/repo/main/index.json"

    @Before fun setUp() = GithubMirrorHealthStore.resetForTests()
    @After fun tearDown() = GithubMirrorHealthStore.resetForTests()

    @Test fun preferredMirrorUsesProxyUrlAndPlainClient() {
        val result = GithubResourceRequests.run(clients, url, UpdateSource.GH_PROXY_VIP) { client, requestUrl ->
            assertSame(plain, client)
            assertEquals(UpdateSource.GH_PROXY_VIP.buildUrl(url), requestUrl)
            "valid"
        }
        assertEquals("valid", result)
    }

    @Test fun directFailureStillTriesMirrorsAndOfficial() {
        val attempts = mutableListOf<UpdateSource>()
        val failures = mutableListOf<UpdateSource>()
        val result = GithubResourceRequests.run(
            clients, url, UpdateSource.ACCELERATED_DIRECT,
            onAttempt = { attempts += it },
            onFailure = { source, _ -> failures += source },
        ) { client, requestUrl ->
            val source = attempts.last()
            assertEquals(source.buildUrl(url), requestUrl)
            assertSame(if (source.usesGithubAcceleration) accelerated else plain, client)
            if (source != UpdateSource.OFFICIAL) throw IOException("invalid response")
            "official"
        }
        assertEquals("official", result)
        assertTrue(attempts.containsAll(UpdateSource.githubResourceFallbackCandidates(UpdateSource.ACCELERATED_DIRECT)))
        assertEquals(attempts.dropLast(1), failures)
    }

    @Test fun bypassOnlyUsesOfficialPlainTransport() {
        val attempts = mutableListOf<UpdateSource>()
        GithubResourceRequests.run(clients, url, UpdateSource.GH_PROXY_COM,
            bypassAcceleratedLinks = true, onAttempt = { attempts += it },
        ) { client, requestUrl ->
            assertSame(plain, client)
            assertEquals(url, requestUrl)
        }
        assertEquals(listOf(UpdateSource.OFFICIAL), attempts)
    }

    @Test fun unrelatedImagesAndUploadUrlsNeverUseMirrorsOrWatt() {
        listOf("https://images.example.com/image.png", "https://example.tencentscf.com/upload").forEach { url ->
            GithubResourceRequests.run(clients, url, UpdateSource.ACCELERATED_DIRECT,
                onAttempt = { fail("Non-GitHub URL must not enter mirror fallback") },
            ) { client, requestUrl ->
                assertSame(plain, client)
                assertEquals(url, requestUrl)
            }
        }
    }

    @Test fun githubAvatarsUseTheSameMirrorPolicy() {
        val avatarUrl = "https://avatars.githubusercontent.com/u/123?v=4"
        GithubResourceRequests.run(clients, avatarUrl, UpdateSource.GH_LLKK) { client, requestUrl ->
            assertSame(plain, client)
            assertEquals(UpdateSource.GH_LLKK.buildUrl(avatarUrl), requestUrl)
        }
    }
}

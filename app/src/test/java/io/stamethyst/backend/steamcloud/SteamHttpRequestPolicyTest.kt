package io.stamethyst.backend.steamcloud

import io.stamethyst.backend.github.HttpRetrySafety
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class SteamHttpRequestPolicyTest {
    @Test fun publishedFileQueryFromDownloadCoreCanReplay() {
        val query = Request.Builder()
            .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/")
            .post("itemcount=1".toRequestBody()).build()
        assertEquals(HttpRetrySafety.READ_ONLY, prepareSteamHttpRequest(query).tag(HttpRetrySafety::class.java))
    }

    @Test fun unrelatedPostsAndExplicitNeverAreNotPromoted() {
        listOf(
            "https://api.steampowered.com/ISteamRemoteStorage/SubscribePublishedFile/v1/",
            "https://steamcommunity.com/comment/PublishedFile_Public/post/",
            "https://other.test/ISteamRemoteStorage/GetPublishedFileDetails/v1/",
        ).forEach { url ->
            val request = Request.Builder().url(url).post("data".toRequestBody()).build()
            assertSame(request, prepareSteamHttpRequest(request))
        }
        val never = Request.Builder()
            .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/")
            .post("query".toRequestBody()).tag(HttpRetrySafety::class.java, HttpRetrySafety.NEVER).build()
        assertSame(never, prepareSteamHttpRequest(never))
    }
}

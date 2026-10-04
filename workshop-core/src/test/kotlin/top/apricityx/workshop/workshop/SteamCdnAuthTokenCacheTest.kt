package top.apricityx.workshop.workshop

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamCdnAuthTokenCacheTest {
    @Test
    fun simultaneous403sOnlyIssueOneTokenRpc() = runBlocking {
        val cache = SteamCdnAuthTokenCache()
        var requests = 0
        val tokens = List(8) {
            async {
                cache.getOrLoad("cdn.test") {
                    requests++
                    delay(20)
                    "token"
                }
            }
        }.awaitAll()
        assertEquals(List(8) { "token" }, tokens)
        assertEquals(1, requests)
    }

    @Test
    fun independentHostsDoNotBlockEachOther() = runBlocking {
        val cache = SteamCdnAuthTokenCache()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        withTimeout(1_000) {
            val first = async {
                cache.getOrLoad("first.test") { firstEntered.complete(Unit); secondEntered.await(); "first" }
            }
            val second = async {
                cache.getOrLoad("second.test") { secondEntered.complete(Unit); firstEntered.await(); "second" }
            }
            assertEquals("first", first.await())
            assertEquals("second", second.await())
        }
    }

    @Test
    fun failedTokenRequestsAreNotCached() = runBlocking {
        val cache = SteamCdnAuthTokenCache()
        runCatching { cache.getOrLoad("cdn.test") { throw IOException("offline") } }
        assertNull(cache["cdn.test"])
        assertEquals("recovered", cache.getOrLoad("cdn.test") { "recovered" })
    }
}

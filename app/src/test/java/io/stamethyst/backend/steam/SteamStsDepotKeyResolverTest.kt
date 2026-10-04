package io.stamethyst.backend.steam

import io.stamethyst.config.CloudControlSettings
import io.stamethyst.config.CloudControlSteamDepotKey
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SteamStsDepotKeyResolverTest {
    private val candidate = SteamStsDepotCandidate(646570u, 646571u, 123uL, "public")
    private val keyHex = "01".repeat(32)

    private fun settings(key: String? = null) = CloudControlSettings(
        heartbeatIntervalSeconds = 30,
        heartbeatWsUrl = "wss://example.test",
        steamDepotKeys = key?.let { listOf(CloudControlSteamDepotKey(646570L, 646571L, it)) } ?: emptyList(),
    )

    @Test
    fun validStartupKeyDoesNotWaitForAnotherCloudRefresh() = runBlocking {
        val resolver = SteamStsDepotKeyResolver({ settings(keyHex) }, { error("Unexpected network refresh") })
        assertArrayEquals(ByteArray(32) { 1 }, resolver.cloudKey(candidate))
    }

    @Test
    fun missingOrInvalidKeyRefreshesOnceForTheWholeCandidateWalk() = runBlocking {
        var refreshes = 0
        val resolver = SteamStsDepotKeyResolver({ settings("invalid") }, { refreshes++; settings(keyHex) })
        assertArrayEquals(ByteArray(32) { 1 }, resolver.cloudKey(candidate))
        assertNull(resolver.cloudKey(candidate.copy(depotId = 646572u)))
        assertNull(resolver.cloudKey(candidate.copy(depotId = 646573u)))
        assertEquals(1, refreshes)
    }

    @Test
    fun observesKeysDeliveredByTheStartupBackgroundRefresh() = runBlocking {
        var current = settings()
        val resolver = SteamStsDepotKeyResolver({ current }, { settings() })
        assertNull(resolver.cloudKey(candidate))
        current = settings(keyHex)
        assertArrayEquals(ByteArray(32) { 1 }, resolver.cloudKey(candidate))
    }

    @Test
    fun obsoleteCachedKeyRefreshesAndRetriesAfterDecryptionFailure() = runBlocking {
        var refreshes = 0
        var downloads = 0
        val resolver = SteamStsDepotKeyResolver({ settings(keyHex) }, { refreshes++; settings("02".repeat(32)) })
        val result = resolver.withCloudKey(candidate) { key ->
            downloads++
            if (key!!.first() == 1.toByte()) throw IOException("decrypt failed", GeneralSecurityException("old key"))
            "downloaded"
        }
        assertEquals("downloaded", result)
        assertEquals(1, refreshes)
        assertEquals(2, downloads)
    }

    @Test
    fun networkFailureDoesNotTriggerCloudRefresh() = runBlocking {
        val resolver = SteamStsDepotKeyResolver({ settings(keyHex) }, { error("Unexpected refresh") })
        val failure = IOException("cdn unreachable")
        val result = runCatching { resolver.withCloudKey(candidate) { throw failure } }
        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun unchangedKeyDoesNotRetryForever() = runBlocking {
        var downloads = 0
        val resolver = SteamStsDepotKeyResolver({ settings(keyHex) }, { settings(keyHex) })
        val failure = GeneralSecurityException("still cannot decrypt")
        val result = runCatching { resolver.withCloudKey(candidate) { downloads++; throw failure } }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(1, downloads)
    }
}

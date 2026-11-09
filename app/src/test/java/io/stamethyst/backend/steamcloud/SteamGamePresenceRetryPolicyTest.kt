package io.stamethyst.backend.steamcloud

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.apricityx.workshop.steam.protocol.SteamAuthenticationException
import top.apricityx.workshop.steam.protocol.SteamSessionRevokedException

class SteamGamePresenceRetryPolicyTest {
    @Test
    fun repeatedFailuresBackOffToFiveMinutes() {
        val policy = SteamGamePresenceRetryPolicy()
        val delays = (1..7).map {
            policy.afterFailure(1_000L).remainingDelayMs(1_000L, recoveryRequested = false)
        }

        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L, 300_000L), delays)
    }

    @Test
    fun successfulRecoveryResetsBackoff() {
        val policy = SteamGamePresenceRetryPolicy()
        repeat(5) { policy.afterFailure(0L) }
        policy.reset()

        assertEquals(30_000L, policy.afterFailure(0L).remainingDelayMs(0L, recoveryRequested = false))
    }

    @Test
    fun foregroundRecoverySkipsLongBackoffButRespectsSharedLoginCooldown() {
        val policy = SteamGamePresenceRetryPolicy()
        repeat(5) { policy.afterFailure(0L) }
        val retry = policy.afterFailure(1_000L)

        assertEquals(300_000L, retry.remainingDelayMs(1_000L, recoveryRequested = false))
        assertEquals(30_000L, retry.remainingDelayMs(1_000L, recoveryRequested = true))
        assertEquals(0L, retry.remainingDelayMs(31_000L, recoveryRequested = true))
        assertEquals(0L, retry.remainingDelayMs(400_000L, recoveryRequested = false))
    }

    @Test
    fun credentialRejectionLogoutAndInterruptionAreTerminal() {
        assertFalse(SteamGamePresenceRetryPolicy.shouldRetry(SteamAuthenticationException(5, "Invalid password")))
        assertFalse(SteamGamePresenceRetryPolicy.shouldRetry(SteamAuthenticationException(27, "Expired")))
        assertFalse(SteamGamePresenceRetryPolicy.shouldRetry(SteamSessionRevokedException()))
        assertFalse(SteamGamePresenceRetryPolicy.shouldRetry(InterruptedException()))
    }

    @Test
    fun networkAndRateLimitFailuresRemainRetryable() {
        assertTrue(SteamGamePresenceRetryPolicy.shouldRetry(IOException("Disconnected")))
        assertTrue(SteamGamePresenceRetryPolicy.shouldRetry(SteamAuthenticationException(84, "Rate limited")))
    }

    @Test
    fun logonTimeoutIsRetryableInsteadOfStoppingService() = runBlocking {
        val error = try {
            withTimeout(1L) { delay(60_000L) }
            error("Expected timeout")
        } catch (error: Exception) {
            error
        }

        assertTrue(SteamGamePresenceRetryPolicy.shouldRetry(error))
    }
}

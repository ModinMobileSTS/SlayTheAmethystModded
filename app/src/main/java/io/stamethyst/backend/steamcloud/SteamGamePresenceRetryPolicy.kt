package io.stamethyst.backend.steamcloud

import java.util.concurrent.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import top.apricityx.workshop.steam.protocol.isSteamCmCredentialRejection

internal class SteamGamePresenceRetryPolicy {
    private var nextDelayMs = MIN_DELAY_MS

    fun afterFailure(nowMs: Long): RetryWindow {
        val delayMs = nextDelayMs
        nextDelayMs = (nextDelayMs * 2L).coerceAtMost(MAX_DELAY_MS)
        return RetryWindow(nowMs + MIN_DELAY_MS, nowMs + delayMs)
    }

    fun reset() {
        nextDelayMs = MIN_DELAY_MS
    }

    data class RetryWindow(private val earliestAtMs: Long, private val scheduledAtMs: Long) {
        fun remainingDelayMs(nowMs: Long, recoveryRequested: Boolean): Long =
            ((if (recoveryRequested) earliestAtMs else scheduledAtMs) - nowMs).coerceAtLeast(0L)
    }

    companion object {
        // Match the shared CM logon failure cooldown; resuming must not create a login storm.
        private const val MIN_DELAY_MS = 30_000L
        private const val MAX_DELAY_MS = 300_000L

        fun shouldRetry(error: Exception): Boolean =
            !error.isSteamCmCredentialRejection() &&
                error !is InterruptedException &&
                (error !is CancellationException || error is TimeoutCancellationException)
    }
}

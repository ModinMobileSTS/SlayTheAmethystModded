package io.stamethyst.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudStatusRefreshTest {
    @Test
    fun isSteamCloudStatusRefreshDue_waitsUntilTheRefreshIntervalExpires() {
        assertFalse(
            isSteamCloudStatusRefreshDue(
                lastCheckedAtMs = 100_000L,
                nowMs = 159_999L,
                refreshIntervalMs = 60_000L,
            )
        )

        assertTrue(
            isSteamCloudStatusRefreshDue(
                lastCheckedAtMs = 100_000L,
                nowMs = 160_000L,
                refreshIntervalMs = 60_000L,
            )
        )
    }

    @Test
    fun isSteamCloudStatusRefreshDue_refreshesWithoutHistoryOrAfterClockRollback() {
        assertTrue(
            isSteamCloudStatusRefreshDue(
                lastCheckedAtMs = null,
                nowMs = 100_000L,
                refreshIntervalMs = 60_000L,
            )
        )
        assertTrue(
            isSteamCloudStatusRefreshDue(
                lastCheckedAtMs = 100_001L,
                nowMs = 100_000L,
                refreshIntervalMs = 60_000L,
            )
        )
    }
}

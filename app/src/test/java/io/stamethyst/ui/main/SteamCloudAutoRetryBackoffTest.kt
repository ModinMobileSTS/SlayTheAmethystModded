package io.stamethyst.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class SteamCloudAutoRetryBackoffTest {
    @Test
    fun steamCloudAutoRetryDelaySeconds_usesExponentialBackoffCappedAtFiveMinutes() {
        assertEquals(5, steamCloudAutoRetryDelaySeconds(-1))
        assertEquals(5, steamCloudAutoRetryDelaySeconds(0))
        assertEquals(10, steamCloudAutoRetryDelaySeconds(1))
        assertEquals(20, steamCloudAutoRetryDelaySeconds(2))
        assertEquals(40, steamCloudAutoRetryDelaySeconds(3))
        assertEquals(80, steamCloudAutoRetryDelaySeconds(4))
        assertEquals(160, steamCloudAutoRetryDelaySeconds(5))
        assertEquals(300, steamCloudAutoRetryDelaySeconds(6))
        assertEquals(300, steamCloudAutoRetryDelaySeconds(20))
    }
}

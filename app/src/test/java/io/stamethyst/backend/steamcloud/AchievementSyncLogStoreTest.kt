package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementSyncLogStoreTest {
    @Test
    fun errorDetailsIncludesTypeMessageCauseAndLimitedStack() {
        val error = IllegalStateException(
            "Steam CM StoreUserStats failed: InvalidStat",
            IllegalArgumentException("refresh_token=secret-value"),
        )

        val details = AchievementSyncLogStore.errorDetails(error)

        assertTrue(details.contains("error_type=java.lang.IllegalStateException"))
        assertTrue(details.contains("error_message=Steam CM StoreUserStats failed: InvalidStat"))
        assertTrue(details.contains("cause_chain=java.lang.IllegalStateException:Steam CM StoreUserStats failed: InvalidStat"))
        assertTrue(details.contains("java.lang.IllegalArgumentException:refresh_token=<redacted>"))
        assertTrue(details.contains("stack=java.lang.IllegalStateException: Steam CM StoreUserStats failed: InvalidStat"))
        assertTrue(!details.contains("secret-value"))
    }
}

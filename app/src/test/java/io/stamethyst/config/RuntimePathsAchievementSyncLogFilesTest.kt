package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePathsAchievementSyncLogFilesTest {
    @Test
    fun isAchievementSyncLogFileName_matchesBaseAndRotatedFiles() {
        assertTrue(RuntimePaths.isAchievementSyncLogFileName("achievement_sync.log"))
        assertTrue(RuntimePaths.isAchievementSyncLogFileName("achievement_sync.log.1"))
        assertTrue(RuntimePaths.isAchievementSyncLogFileName("achievement_sync.log.2"))
        assertFalse(RuntimePaths.isAchievementSyncLogFileName("achievement_sync.txt"))
        assertFalse(RuntimePaths.isAchievementSyncLogFileName("latest.log"))
    }

    @Test
    fun compareAchievementSyncLogFileNames_ordersBaseBeforeRotations() {
        val sorted = listOf(
            "achievement_sync.log.2",
            "achievement_sync.log",
            "achievement_sync.log.10",
            "achievement_sync.log.1",
        ).sortedWith(RuntimePaths::compareAchievementSyncLogFileNames)

        assertEquals(
            listOf(
                "achievement_sync.log",
                "achievement_sync.log.1",
                "achievement_sync.log.2",
                "achievement_sync.log.10",
            ),
            sorted,
        )
    }
}

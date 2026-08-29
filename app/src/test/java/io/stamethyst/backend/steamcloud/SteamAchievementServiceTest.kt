package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.stamethyst.R

class SteamAchievementServiceTest {
    @Test
    fun bundledCatalog_containsEveryOfficialAchievementAndLocalIcons() {
        assertEquals(46, SteamAchievementCatalog.entries.size)
        assertEquals(46, SteamAchievementCatalog.apiNames.size)
        assertTrue(SteamAchievementCatalog.entries.all { entry ->
            entry.titleResId != 0 &&
                entry.descriptionResId != 0 &&
                entry.unlockedIconResId != 0 &&
                entry.lockedIconResId != 0
        })
    }

    @Test
    fun bundledCatalog_accountsForReversedBitmapFilenames() {
        val shrugItOff = SteamAchievementCatalog.entries.first { it.apiName == "shrug_it_off" }

        assertEquals(R.drawable.achievement_shrug_it_off_locked, shrugItOff.unlockedIconResId)
        assertEquals(R.drawable.achievement_shrug_it_off_unlocked, shrugItOff.lockedIconResId)
    }

    @Test
    fun bundledCatalog_containsKnownShrugItOffApiName() {
        assertEquals("shrug_it_off", SteamAchievementService.SHRUG_IT_OFF_API_NAME)
        assertEquals(
            1,
            SteamAchievementCatalog.entries.count {
                it.apiName == SteamAchievementService.SHRUG_IT_OFF_API_NAME
            },
        )
    }

    @Test
    fun stateSnapshot_usesBundledMetadataAndIgnoresUnknownAchievements() {
        val snapshot = SteamAchievementService.buildSnapshot(
            steamId64 = "76561198000000000",
            unlockedApiNames = setOf("guardian", "unknown_achievement"),
            fetchedAtMs = 789L,
            fromCache = false,
        )

        assertEquals(46, snapshot.achievements.size)
        assertEquals(1, snapshot.unlockedCount)
        val guardian = snapshot.achievements.first { it.apiName == "guardian" }
        assertEquals(R.string.steam_achievement_guardian_title, guardian.titleResId)
        assertEquals(R.string.steam_achievement_guardian_description, guardian.descriptionResId)
        assertTrue(guardian.unlocked)
        assertTrue(guardian.unlockedIconResId != guardian.lockedIconResId)
        assertFalse(snapshot.achievements.any { it.apiName == "unknown_achievement" })
    }

    @Test
    fun bitfieldConfirmedUnlockIsRenderedAsUnlocked() {
        val snapshot = SteamAchievementService.buildSnapshot(
            steamId64 = "76561198000000000",
            unlockedApiNames = setOf("shrug_it_off"),
            fetchedAtMs = 789L,
            fromCache = false,
        )

        val achievement = snapshot.achievements.first { it.apiName == "shrug_it_off" }
        assertTrue(achievement.unlocked)
    }

}

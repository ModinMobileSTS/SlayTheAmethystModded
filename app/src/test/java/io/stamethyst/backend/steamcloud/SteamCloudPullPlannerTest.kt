package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudPullPlannerTest {
    @Test
    fun buildPlan_alwaysReplacesPreferencesAndSaves() {
        val snapshot = SteamCloudManifestSnapshot(
            fetchedAtMs = 1L,
            fileCount = 1,
            preferencesCount = 1,
            savesCount = 0,
            entries = listOf(
                SteamCloudManifestEntry(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = "preferences/STSPlayer",
                    rootKind = SteamCloudRootKind.PREFERENCES,
                    rawSize = 123L,
                    timestamp = 456L,
                    machineName = "",
                    persistState = "Persisted"
                )
            ),
            warnings = emptyList()
        )

        val plan = SteamCloudPullPlanner.buildPlan(snapshot)

        assertEquals(snapshot.entries, plan.entries)
        assertTrue(plan.replaceRoots.contains(SteamCloudRootKind.PREFERENCES))
        assertTrue(plan.replaceRoots.contains(SteamCloudRootKind.SAVES))
        assertEquals(2, plan.replaceRoots.size)
    }
}

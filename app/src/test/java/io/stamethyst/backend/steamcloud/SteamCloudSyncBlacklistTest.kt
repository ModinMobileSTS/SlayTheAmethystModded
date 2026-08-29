package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudSyncBlacklistTest {
    @Test
    fun filterManifestSnapshot_filtersLiveAndTombstoneEntriesByTheSamePathRule() {
        val snapshot = SteamCloudManifestSnapshot(
            fetchedAtMs = 1L,
            fileCount = 1,
            preferencesCount = 1,
            savesCount = 0,
            entries = listOf(
                manifestEntry(
                    localRelativePath = "preferences/STSPlayer",
                    persistState = "Persisted",
                ),
                manifestEntry(
                    localRelativePath = "preferences/STSUnlocks",
                    persistState = "Deleted",
                ),
            ),
            warnings = emptyList(),
        )

        val filtered = SteamCloudSyncBlacklist.filterManifestSnapshot(
            snapshot = snapshot,
            configuredBlacklist = setOf("preferences/STSPlayer", "preferences/STSUnlocks"),
        )

        assertTrue(filtered.entries.isEmpty())
        assertTrue(filtered.tombstoneEntries.isEmpty())
        assertEquals(0, filtered.fileCount)
    }

    private fun manifestEntry(
        localRelativePath: String,
        persistState: String,
    ): SteamCloudManifestEntry {
        return SteamCloudManifestEntry(
            remotePath = "%GameInstall%$localRelativePath",
            localRelativePath = localRelativePath,
            rootKind = SteamCloudRootKind.PREFERENCES,
            rawSize = 100L,
            timestamp = 1L,
            machineName = "",
            persistState = persistState,
        )
    }
}

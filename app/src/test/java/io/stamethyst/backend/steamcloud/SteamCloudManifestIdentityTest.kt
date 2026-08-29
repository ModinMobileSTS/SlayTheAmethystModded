package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SteamCloudManifestIdentityTest {
    @Test
    fun identityIsStableAcrossEntryOrderAndFetchTime() {
        val first = snapshot(
            fetchedAtMs = 100L,
            entries = listOf(entry("preferences/STSPlayer", 3L), entry("saves/IRONCLAD.autosave", 4L)),
        )
        val reordered = snapshot(
            fetchedAtMs = 200L,
            entries = first.entries.reversed(),
        )

        assertEquals(
            SteamCloudManifestIdentity.compute(first),
            SteamCloudManifestIdentity.compute(reordered),
        )
    }

    @Test
    fun identityChangesWhenRemoteContentIdentityChanges() {
        val original = snapshot(100L, listOf(entry("preferences/STSPlayer", 3L)))
        val changed = snapshot(
            100L,
            listOf(entry("preferences/STSPlayer", 3L, sha1 = "2222222222222222222222222222222222222222")),
        )

        assertNotEquals(
            SteamCloudManifestIdentity.compute(original),
            SteamCloudManifestIdentity.compute(changed),
        )
    }

    @Test
    fun identityIncludesTombstones() {
        val live = entry("saves/IRONCLAD.autosave", 4L)
        val withoutTombstone = snapshot(100L, listOf(live))
        val withTombstone = SteamCloudManifestSnapshot(
            fetchedAtMs = 100L,
            fileCount = 1,
            preferencesCount = 0,
            savesCount = 1,
            entries = listOf(live),
            warnings = emptyList(),
            tombstoneEntries = listOf(
                entry("saves/DEFECT.autosave", 0L, persistState = "Deleted"),
            ),
        )

        assertNotEquals(
            SteamCloudManifestIdentity.compute(withoutTombstone),
            SteamCloudManifestIdentity.compute(withTombstone),
        )
    }

    private fun snapshot(
        fetchedAtMs: Long,
        entries: List<SteamCloudManifestEntry>,
    ) = SteamCloudManifestSnapshot(
        fetchedAtMs = fetchedAtMs,
        fileCount = entries.size,
        preferencesCount = entries.count { it.rootKind == SteamCloudRootKind.PREFERENCES },
        savesCount = entries.count { it.rootKind == SteamCloudRootKind.SAVES },
        entries = entries,
        warnings = emptyList(),
    )

    private fun entry(
        localPath: String,
        size: Long,
        sha1: String = "1111111111111111111111111111111111111111",
        persistState: String = "Persisted",
    ) = SteamCloudManifestEntry(
        remotePath = "%GameInstall%$localPath",
        localRelativePath = localPath,
        rootKind = if (localPath.startsWith("saves/")) {
            SteamCloudRootKind.SAVES
        } else {
            SteamCloudRootKind.PREFERENCES
        },
        rawSize = size,
        timestamp = 10L,
        machineName = "test",
        persistState = persistState,
        sha1 = sha1,
    )
}

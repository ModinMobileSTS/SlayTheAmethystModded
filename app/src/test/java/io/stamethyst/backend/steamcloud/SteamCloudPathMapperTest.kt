package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudPathMapperTest {
    @Test
    fun mapRemotePath_preservesModAndBackupNames() {
        val mapped = SteamCloudPathMapper.mapRemotePath(
            "%GameInstall%preferences/1_Tuner_CLASS.autosave.backUp"
        )

        requireNotNull(mapped)
        assertEquals(SteamCloudRootKind.PREFERENCES, mapped.rootKind)
        assertEquals(
            "preferences/1_Tuner_CLASS.autosave.backUp",
            mapped.localRelativePath
        )
    }

    @Test
    fun mapRemotePath_rejectsUnsupportedPrefixes() {
        val mapped = SteamCloudPathMapper.mapRemotePath("%GameInstall%runs/latest.run")

        assertNull(mapped)
    }

    @Test
    fun buildManifestSnapshot_ignoresUnsupportedPaths_andKeepsSupportedEntries() {
        val snapshot = SteamCloudPathMapper.buildManifestSnapshot(
            fetchedAtMs = 1234L,
            remoteEntries = listOf(
                SteamCloudClient.RemoteFileRecord(
                    "%GameInstall%preferences/STSPlayer",
                    128L,
                    100L,
                    "",
                    "Persisted"
                ),
                SteamCloudClient.RemoteFileRecord(
                    "%GameInstall%saves/WATCHER.autosave",
                    256L,
                    200L,
                    "",
                    "Persisted"
                ),
                SteamCloudClient.RemoteFileRecord(
                    "%GameInstall%runs/ignore-me",
                    512L,
                    300L,
                    "",
                    "Persisted"
                )
            )
        )

        assertEquals(2, snapshot.fileCount)
        assertEquals(1, snapshot.preferencesCount)
        assertEquals(1, snapshot.savesCount)
        assertEquals(2, snapshot.entries.size)
        assertTrue(snapshot.warnings.any { it.contains("%GameInstall%runs/ignore-me") })
    }
}

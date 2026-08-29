package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
                    "Persisted",
                    "sha-player"
                ),
                SteamCloudClient.RemoteFileRecord(
                    "%GameInstall%saves/WATCHER.autosave",
                    256L,
                    200L,
                    "",
                    "Persisted",
                    "sha-save"
                ),
                SteamCloudClient.RemoteFileRecord(
                    "%GameInstall%runs/ignore-me",
                    512L,
                    300L,
                    "",
                    "Persisted",
                    ""
                )
            )
        )

        assertEquals(2, snapshot.fileCount)
        assertEquals(1, snapshot.preferencesCount)
        assertEquals(1, snapshot.savesCount)
        assertEquals(2, snapshot.entries.size)
        assertEquals("sha-player", snapshot.entries[0].sha1)
        assertEquals("sha-save", snapshot.entries[1].sha1)
        assertTrue(snapshot.warnings.any { it.contains("%GameInstall%runs/ignore-me") })
    }

    @Test
    fun buildManifestSnapshot_filtersTombstonesFromEntriesButRetainsThemForPlanning() {
        val snapshot = SteamCloudPathMapper.buildManifestSnapshot(
            fetchedAtMs = 1234L,
            remoteEntries = listOf(
                SteamCloudClient.RemoteFileRecord(
                    "%GameInstall%preferences/STSPlayer",
                    128L,
                    100L,
                    "",
                    "Persisted",
                    "sha-player",
                ),
                SteamCloudClient.RemoteFileRecord(
                    "%GameInstall%saves/WATCHER.autosave",
                    256L,
                    200L,
                    "",
                    "REMOVED",
                    "sha-save",
                ),
            ),
        )

        assertEquals(1, snapshot.fileCount)
        assertEquals(listOf("preferences/STSPlayer"), snapshot.entries.map { it.localRelativePath })
        assertEquals(
            listOf("saves/WATCHER.autosave"),
            snapshot.tombstoneEntries.map { it.localRelativePath },
        )
        assertEquals(2, snapshot.entriesForPlanning.size)
        assertEquals(1, SteamCloudPullPlanner.buildPlan(snapshot).entries.size)
        assertTrue(SteamCloudPullPlanner.buildPlan(snapshot).entries.all { it.isLive })
    }

    @Test
    fun buildManifestSnapshot_recognizesProtocolPersistStateNames() {
        val snapshot = SteamCloudPathMapper.buildManifestSnapshot(
            fetchedAtMs = 1234L,
            remoteEntries = listOf(
                SteamCloudClient.RemoteFileRecord(
                    "%GameInstall%preferences/STSPlayer",
                    128L,
                    100L,
                    "",
                    "k_ECloudStoragePersistStatePersisted",
                    "sha-player",
                ),
                SteamCloudClient.RemoteFileRecord(
                    "%GameInstall%saves/WATCHER.autosave",
                    0L,
                    200L,
                    "",
                    "k_ECloudStoragePersistStateDeleted",
                    "",
                ),
            ),
        )

        assertEquals(listOf("preferences/STSPlayer"), snapshot.entries.map { it.localRelativePath })
        assertEquals(
            listOf("saves/WATCHER.autosave"),
            snapshot.tombstoneEntries.map { it.localRelativePath },
        )
    }

    @Test
    fun mapRemotePath_normalizesSteamBackslashSeparators() {
        val mapped = SteamCloudPathMapper.mapRemotePath(
            "%GameInstall%preferences\\STSPlayer"
        )

        requireNotNull(mapped)
        assertEquals("preferences/STSPlayer", mapped.localRelativePath)
    }

    @Test
    fun buildManifestSnapshot_rejectsDuplicateMappedPaths() {
        try {
            SteamCloudPathMapper.buildManifestSnapshot(
                fetchedAtMs = 1234L,
                remoteEntries = listOf(
                    SteamCloudClient.RemoteFileRecord(
                        "%GameInstall%preferences/STSPlayer",
                        128L,
                        100L,
                        "machine-a",
                        "Persisted",
                        "sha-a",
                    ),
                    SteamCloudClient.RemoteFileRecord(
                        "%GameInstall%preferences\\STSPlayer",
                        256L,
                        200L,
                        "machine-b",
                        "Persisted",
                        "sha-b",
                    ),
                ),
            )
            fail("Expected duplicate mapped paths to fail closed")
        } catch (error: SteamCloudIncompleteManifestException) {
            assertTrue(error.message.orEmpty().contains("duplicate", ignoreCase = true))
        }
    }

    @Test
    fun buildManifestSnapshot_rejectsUnknownPersistState() {
        try {
            SteamCloudPathMapper.buildManifestSnapshot(
                fetchedAtMs = 1234L,
                remoteEntries = listOf(
                    SteamCloudClient.RemoteFileRecord(
                        "%GameInstall%preferences/STSPlayer",
                        128L,
                        100L,
                        "machine-a",
                        "k_ECloudStoragePersistStateInvalid",
                        "sha-a",
                    ),
                ),
            )
            fail("Expected unknown persistence state to fail closed")
        } catch (error: SteamCloudIncompleteManifestException) {
            assertTrue(error.message.orEmpty().contains("persistence state", ignoreCase = true))
        }
    }

    @Test
    fun pathMappersRejectUnsafeAliases() {
        val unsafeLocalPaths = listOf(
            " preferences/STSPlayer",
            "preferences/STSPlayer ",
            "preferences\\STSPlayer",
            "preferences//STSPlayer",
            "preferences/./STSPlayer",
            "preferences/../STSPlayer",
            "preferences/C:/STSPlayer",
            "/preferences/STSPlayer",
            "C:/preferences/STSPlayer",
        )
        unsafeLocalPaths.forEach { path ->
            assertNull("Expected unsafe local path to be rejected: $path", SteamCloudPathMapper.mapLocalRelativePath(path))
        }

        val unsafeRemotePaths = listOf(
            " %GameInstall%preferences/STSPlayer",
            "%GameInstall%preferences/STSPlayer ",
            "%GameInstall%preferences//STSPlayer",
            "%GameInstall%preferences/./STSPlayer",
            "%GameInstall%preferences/../STSPlayer",
            "%GameInstall%preferences/C:/STSPlayer",
            "/absolute/STSPlayer",
            "C:/absolute/STSPlayer",
        )
        unsafeRemotePaths.forEach { path ->
            assertNull("Expected unsafe remote path to be rejected: $path", SteamCloudPathMapper.mapRemotePath(path))
        }

        assertNull(SteamCloudPathMapper.mapLocalRelativePath("preferences/"))
        assertNull(SteamCloudPathMapper.mapRemotePath("%GameInstall%preferences/"))
    }
}

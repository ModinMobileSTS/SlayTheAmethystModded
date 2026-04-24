package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertEquals
import org.junit.Test

class SteamCloudMirrorPlannerTest {
    @Test
    fun buildLocalMirrorPlan_uploadsEverySupportedLocalFile() {
        val plan = SteamCloudMirrorPlanner.buildLocalMirrorPlan(
            currentLocalEntries = listOf(
                localEntry(
                    localRelativePath = "preferences/STSPlayer",
                    sha256 = "local-a",
                ),
                localEntry(
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    sha256 = "local-b",
                ),
            ),
            currentRemoteSnapshot = remoteSnapshot(),
        )

        assertEquals(
            listOf(
                "%GameInstall%preferences/STSPlayer",
                "%GameInstall%saves/WATCHER.autosave",
            ),
            plan.uploadCandidates.map { it.remotePath }
        )
        assertEquals(emptyList<String>(), plan.deleteRemotePaths)
    }

    @Test
    fun buildLocalMirrorPlan_deletesRemoteOnlyFiles() {
        val plan = SteamCloudMirrorPlanner.buildLocalMirrorPlan(
            currentLocalEntries = listOf(
                localEntry(
                    localRelativePath = "preferences/STSPlayer",
                    sha256 = "local-a",
                )
            ),
            currentRemoteSnapshot = remoteSnapshot(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = "preferences/STSPlayer",
                    rawSize = 12L,
                    timestamp = 10L,
                ),
                remoteEntry(
                    remotePath = "%GameInstall%saves/WATCHER.autosave",
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    rawSize = 24L,
                    timestamp = 20L,
                ),
            ),
        )

        assertEquals(listOf("%GameInstall%preferences/STSPlayer"), plan.uploadCandidates.map { it.remotePath })
        assertEquals(listOf("%GameInstall%saves/WATCHER.autosave"), plan.deleteRemotePaths)
    }

    @Test
    fun buildLocalMirrorPlan_skipsExistingRemoteFilesWhenSha1Matches() {
        val plan = SteamCloudMirrorPlanner.buildLocalMirrorPlan(
            currentLocalEntries = listOf(
                localEntry(
                    localRelativePath = "preferences/1_STSDataVagabond",
                    fileSize = 154L,
                    sha256 = "local-sha256",
                    sha1 = "cd2bada30bd171d5b4b24783ff5a45ff46367eb6",
                )
            ),
            currentRemoteSnapshot = remoteSnapshot(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/1_STSDataVagabond",
                    localRelativePath = "preferences/1_STSDataVagabond",
                    rawSize = 154L,
                    timestamp = 10L,
                    sha1 = "CD2BADA30BD171D5B4B24783FF5A45FF46367EB6",
                ),
            ),
        )

        assertEquals(emptyList<String>(), plan.uploadCandidates.map { it.remotePath })
        assertEquals(emptyList<String>(), plan.deleteRemotePaths)
    }

    @Test
    fun buildLocalMirrorPlan_withBaseline_onlyUploadsPathsThatNeedLocalAuthoritativeChanges() {
        val baseline = SteamCloudSyncBaseline(
            syncedAtMs = 1L,
            localEntries = listOf(
                localEntry(
                    localRelativePath = "preferences/STSPlayer",
                    sha256 = "baseline-local-player",
                ),
                localEntry(
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    sha256 = "baseline-local-save",
                ),
            ),
            remoteEntries = listOf(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = "preferences/STSPlayer",
                    rawSize = 100L,
                    timestamp = 10L,
                ),
                remoteEntry(
                    remotePath = "%GameInstall%saves/WATCHER.autosave",
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    rawSize = 50L,
                    timestamp = 20L,
                ),
            ),
        )

        val plan = SteamCloudMirrorPlanner.buildLocalMirrorPlan(
            currentLocalEntries = listOf(
                localEntry(
                    localRelativePath = "preferences/STSPlayer",
                    sha256 = "baseline-local-player",
                ),
                localEntry(
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    sha256 = "updated-local-save",
                    fileSize = 60L,
                ),
            ),
            currentRemoteSnapshot = remoteSnapshot(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = "preferences/STSPlayer",
                    rawSize = 120L,
                    timestamp = 30L,
                ),
                remoteEntry(
                    remotePath = "%GameInstall%saves/WATCHER.autosave",
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    rawSize = 50L,
                    timestamp = 20L,
                ),
                remoteEntry(
                    remotePath = "%GameInstall%preferences/STSUnlocks",
                    localRelativePath = "preferences/STSUnlocks",
                    rawSize = 80L,
                    timestamp = 40L,
                ),
            ),
            baseline = baseline,
        )

        assertEquals(
            listOf(
                "%GameInstall%preferences/STSPlayer",
                "%GameInstall%saves/WATCHER.autosave",
            ),
            plan.uploadCandidates.map { it.remotePath }
        )
        assertEquals(
            listOf("%GameInstall%preferences/STSUnlocks"),
            plan.deleteRemotePaths
        )
    }

    private fun remoteSnapshot(vararg entries: SteamCloudManifestEntry): SteamCloudManifestSnapshot {
        return SteamCloudManifestSnapshot(
            fetchedAtMs = 1L,
            fileCount = entries.size,
            preferencesCount = entries.count { it.rootKind == SteamCloudRootKind.PREFERENCES },
            savesCount = entries.count { it.rootKind == SteamCloudRootKind.SAVES },
            entries = entries.toList(),
            warnings = emptyList(),
        )
    }

    private fun localEntry(
        localRelativePath: String,
        rootKind: SteamCloudRootKind = SteamCloudRootKind.PREFERENCES,
        fileSize: Long = 100L,
        lastModifiedMs: Long = 1L,
        sha256: String,
        sha1: String = "",
    ): SteamCloudLocalFileSnapshotEntry {
        return SteamCloudLocalFileSnapshotEntry(
            localRelativePath = localRelativePath,
            rootKind = rootKind,
            fileSize = fileSize,
            lastModifiedMs = lastModifiedMs,
            sha256 = sha256,
            sha1 = sha1,
        )
    }

    private fun remoteEntry(
        remotePath: String,
        localRelativePath: String,
        rootKind: SteamCloudRootKind = SteamCloudRootKind.PREFERENCES,
        rawSize: Long,
        timestamp: Long,
        sha1: String = "",
    ): SteamCloudManifestEntry {
        return SteamCloudManifestEntry(
            remotePath = remotePath,
            localRelativePath = localRelativePath,
            rootKind = rootKind,
            rawSize = rawSize,
            timestamp = timestamp,
            machineName = "",
            persistState = "Persisted",
            sha1 = sha1,
        )
    }
}

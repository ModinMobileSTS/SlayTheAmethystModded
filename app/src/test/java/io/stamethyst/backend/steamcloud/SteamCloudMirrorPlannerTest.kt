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
    ): SteamCloudLocalFileSnapshotEntry {
        return SteamCloudLocalFileSnapshotEntry(
            localRelativePath = localRelativePath,
            rootKind = rootKind,
            fileSize = fileSize,
            lastModifiedMs = lastModifiedMs,
            sha256 = sha256,
        )
    }

    private fun remoteEntry(
        remotePath: String,
        localRelativePath: String,
        rootKind: SteamCloudRootKind = SteamCloudRootKind.PREFERENCES,
        rawSize: Long,
        timestamp: Long,
    ): SteamCloudManifestEntry {
        return SteamCloudManifestEntry(
            remotePath = remotePath,
            localRelativePath = localRelativePath,
            rootKind = rootKind,
            rawSize = rawSize,
            timestamp = timestamp,
            machineName = "",
            persistState = "Persisted",
        )
    }
}

package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudDiffPlannerTest {
    @Test
    fun buildUploadPlan_withoutBaseline_marksExistingFilesAsConflicts() {
        val plan = SteamCloudDiffPlanner.buildUploadPlan(
            plannedAtMs = 1L,
            currentLocalEntries = listOf(
                localEntry(
                    localRelativePath = "preferences/STSPlayer",
                    sha256 = "local",
                )
            ),
            currentRemoteSnapshot = remoteSnapshot(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = "preferences/STSPlayer",
                    rawSize = 12L,
                    timestamp = 100L,
                )
            ),
            baseline = null,
        )

        assertFalse(plan.baselineConfigured)
        assertEquals(0, plan.uploadCandidates.size)
        assertEquals(1, plan.conflicts.size)
        assertEquals(SteamCloudConflictKind.BASELINE_REQUIRED, plan.conflicts.single().kind)
    }

    @Test
    fun buildUploadPlan_uploadsLocalOnlyChangesWhenRemoteDidNotChange() {
        val baseline = SteamCloudSyncBaseline(
            syncedAtMs = 1L,
            localEntries = listOf(
                localEntry(
                    localRelativePath = "preferences/STSPlayer",
                    sha256 = "baseline",
                )
            ),
            remoteEntries = listOf(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = "preferences/STSPlayer",
                    rawSize = 100L,
                    timestamp = 100L,
                )
            )
        )

        val plan = SteamCloudDiffPlanner.buildUploadPlan(
            plannedAtMs = 2L,
            currentLocalEntries = listOf(
                localEntry(
                    localRelativePath = "preferences/STSPlayer",
                    sha256 = "updated-local",
                    fileSize = 120L,
                )
            ),
            currentRemoteSnapshot = remoteSnapshot(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = "preferences/STSPlayer",
                    rawSize = 100L,
                    timestamp = 100L,
                )
            ),
            baseline = baseline,
        )

        assertTrue(plan.baselineConfigured)
        assertEquals(1, plan.uploadCandidates.size)
        assertEquals(SteamCloudUploadCandidateKind.MODIFIED_FILE, plan.uploadCandidates.single().kind)
        assertEquals(0, plan.conflicts.size)
        assertEquals(0, plan.remoteOnlyChanges.size)
    }

    @Test
    fun buildUploadPlan_tracksRemoteOnlyChangesSeparately() {
        val baseline = SteamCloudSyncBaseline(
            syncedAtMs = 1L,
            localEntries = listOf(
                localEntry(
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    sha256 = "same-local",
                )
            ),
            remoteEntries = listOf(
                remoteEntry(
                    remotePath = "%GameInstall%saves/WATCHER.autosave",
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    rawSize = 50L,
                    timestamp = 10L,
                )
            )
        )

        val plan = SteamCloudDiffPlanner.buildUploadPlan(
            plannedAtMs = 2L,
            currentLocalEntries = listOf(
                localEntry(
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    sha256 = "same-local",
                )
            ),
            currentRemoteSnapshot = remoteSnapshot(
                remoteEntry(
                    remotePath = "%GameInstall%saves/WATCHER.autosave",
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    rawSize = 60L,
                    timestamp = 20L,
                )
            ),
            baseline = baseline,
        )

        assertEquals(0, plan.uploadCandidates.size)
        assertEquals(0, plan.conflicts.size)
        assertEquals(1, plan.remoteOnlyChanges.size)
        assertEquals(
            SteamCloudRemoteOnlyChangeKind.MODIFIED_REMOTE_FILE,
            plan.remoteOnlyChanges.single().kind
        )
        assertEquals(2L, plan.remoteManifestFetchedAtMs)
    }

    @Test
    fun buildUploadPlan_keepsDisjointLocalAndRemoteChangesOutOfConflicts() {
        val baseline = SteamCloudSyncBaseline(
            syncedAtMs = 1L,
            localEntries = listOf(
                localEntry(
                    localRelativePath = "preferences/STSPlayer",
                    sha256 = "baseline-local",
                ),
                localEntry(
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    sha256 = "baseline-save",
                ),
            ),
            remoteEntries = listOf(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = "preferences/STSPlayer",
                    rawSize = 100L,
                    timestamp = 100L,
                ),
                remoteEntry(
                    remotePath = "%GameInstall%saves/WATCHER.autosave",
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    rawSize = 50L,
                    timestamp = 10L,
                ),
            )
        )

        val plan = SteamCloudDiffPlanner.buildUploadPlan(
            plannedAtMs = 2L,
            currentLocalEntries = listOf(
                localEntry(
                    localRelativePath = "preferences/STSPlayer",
                    sha256 = "updated-local",
                    fileSize = 120L,
                ),
                localEntry(
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    sha256 = "baseline-save",
                ),
            ),
            currentRemoteSnapshot = remoteSnapshot(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = "preferences/STSPlayer",
                    rawSize = 100L,
                    timestamp = 100L,
                ),
                remoteEntry(
                    remotePath = "%GameInstall%saves/WATCHER.autosave",
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    rawSize = 60L,
                    timestamp = 20L,
                ),
            ),
            baseline = baseline,
        )

        assertEquals(1, plan.uploadCandidates.size)
        assertEquals("preferences/STSPlayer", plan.uploadCandidates.single().localRelativePath)
        assertEquals(0, plan.conflicts.size)
        assertEquals(1, plan.remoteOnlyChanges.size)
        assertEquals("saves/WATCHER.autosave", plan.remoteOnlyChanges.single().localRelativePath)
    }

    @Test
    fun buildUploadPlan_marksBothSidesChangedAsConflict() {
        val baseline = SteamCloudSyncBaseline(
            syncedAtMs = 1L,
            localEntries = listOf(
                localEntry(localRelativePath = "preferences/STSPlayer", sha256 = "baseline")
            ),
            remoteEntries = listOf(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = "preferences/STSPlayer",
                    rawSize = 100L,
                    timestamp = 100L,
                )
            )
        )

        val plan = SteamCloudDiffPlanner.buildUploadPlan(
            plannedAtMs = 2L,
            currentLocalEntries = listOf(
                localEntry(localRelativePath = "preferences/STSPlayer", sha256 = "local-changed")
            ),
            currentRemoteSnapshot = remoteSnapshot(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = "preferences/STSPlayer",
                    rawSize = 200L,
                    timestamp = 200L,
                )
            ),
            baseline = baseline,
        )

        assertEquals(0, plan.uploadCandidates.size)
        assertEquals(1, plan.conflicts.size)
        assertEquals(SteamCloudConflictKind.BOTH_CHANGED, plan.conflicts.single().kind)
    }

    @Test
    fun buildUploadPlan_treatsMatchingCurrentSha1AsAlreadySynced() {
        val sha1 = "cd2bada30bd171d5b4b24783ff5a45ff46367eb6"
        val baseline = SteamCloudSyncBaseline(
            syncedAtMs = 1L,
            localEntries = listOf(
                localEntry(localRelativePath = "preferences/1_STSDataVagabond", sha256 = "baseline-local")
            ),
            remoteEntries = listOf(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/1_STSDataVagabond",
                    localRelativePath = "preferences/1_STSDataVagabond",
                    rawSize = 120L,
                    timestamp = 100L,
                    sha1 = "baseline-remote",
                )
            )
        )

        val plan = SteamCloudDiffPlanner.buildUploadPlan(
            plannedAtMs = 2L,
            currentLocalEntries = listOf(
                localEntry(
                    localRelativePath = "preferences/1_STSDataVagabond",
                    fileSize = 154L,
                    sha256 = "updated-local",
                    sha1 = sha1,
                )
            ),
            currentRemoteSnapshot = remoteSnapshot(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/1_STSDataVagabond",
                    localRelativePath = "preferences/1_STSDataVagabond",
                    rawSize = 154L,
                    timestamp = 200L,
                    sha1 = sha1.uppercase(),
                )
            ),
            baseline = baseline,
        )

        assertEquals(0, plan.uploadCandidates.size)
        assertEquals(0, plan.conflicts.size)
        assertEquals(0, plan.remoteOnlyChanges.size)
    }

    @Test
    fun buildUploadPlan_sameSizeMissingSha1_requiresBaselineConflict() {
        val plan = SteamCloudDiffPlanner.buildUploadPlan(
            plannedAtMs = 1L,
            currentLocalEntries = listOf(
                localEntry(
                    localRelativePath = "preferences/STSPlayer",
                    fileSize = 128L,
                    sha256 = "local-sha256",
                )
            ),
            currentRemoteSnapshot = remoteSnapshot(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = "preferences/STSPlayer",
                    rawSize = 128L,
                    timestamp = 1L,
                )
            ),
            baseline = null,
        )

        assertEquals(0, plan.uploadCandidates.size)
        assertEquals(1, plan.conflicts.size)
        assertEquals(SteamCloudConflictKind.BASELINE_REQUIRED, plan.conflicts.single().kind)
    }

    @Test
    fun buildUploadPlan_tombstoneIsDeletionChangeButNotDownloadCandidate() {
        val baseline = SteamCloudSyncBaseline(
            syncedAtMs = 1L,
            localEntries = listOf(
                localEntry(
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    sha256 = "same-local",
                )
            ),
            remoteEntries = listOf(
                remoteEntry(
                    remotePath = "%GameInstall%saves/WATCHER.autosave",
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    rawSize = 50L,
                    timestamp = 1L,
                )
            ),
        )

        val plan = SteamCloudDiffPlanner.buildUploadPlan(
            plannedAtMs = 2L,
            currentLocalEntries = listOf(
                localEntry(
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    sha256 = "same-local",
                )
            ),
            currentRemoteSnapshot = remoteSnapshot(
                remoteEntry(
                    remotePath = "%GameInstall%saves/WATCHER.autosave",
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    rawSize = 50L,
                    timestamp = 2L,
                    persistState = "dElEtEd",
                )
            ),
            baseline = baseline,
        )

        assertEquals(1, plan.remoteOnlyChanges.size)
        assertEquals(
            SteamCloudRemoteOnlyChangeKind.REMOTE_FILE_DELETED,
            plan.remoteOnlyChanges.single().kind,
        )
        assertEquals(null, plan.remoteOnlyChanges.single().currentRemote)
        assertEquals(0, plan.uploadCandidates.size)
    }

    @Test
    fun buildUploadPlan_recreatedLocalFileOverRetainedTombstoneIsNewUpload() {
        val tombstone = remoteEntry(
            remotePath = "%GameInstall%preferences/STSPlayer",
            localRelativePath = "preferences/STSPlayer",
            rawSize = 0L,
            timestamp = 2L,
            persistState = "k_ECloudStoragePersistStateDeleted",
        )
        val plan = SteamCloudDiffPlanner.buildUploadPlan(
            plannedAtMs = 3L,
            currentLocalEntries = listOf(
                localEntry(
                    localRelativePath = "preferences/STSPlayer",
                    fileSize = 3L,
                    sha256 = "new-local",
                    sha1 = "new-local-sha1",
                )
            ),
            currentRemoteSnapshot = remoteSnapshot(tombstone),
            baseline = SteamCloudSyncBaseline(
                syncedAtMs = 2L,
                localEntries = emptyList(),
                remoteEntries = listOf(tombstone),
            ),
        )

        assertEquals(1, plan.uploadCandidates.size)
        assertEquals(SteamCloudUploadCandidateKind.NEW_FILE, plan.uploadCandidates.single().kind)
        assertEquals(0, plan.conflicts.size)
        assertEquals(0, plan.remoteOnlyChanges.size)
    }

    @Test
    fun buildUploadPlan_detectsRemoteSha1ChangeWhenMetadataMatches() {
        val baseline = SteamCloudSyncBaseline(
            syncedAtMs = 1L,
            localEntries = listOf(
                localEntry(localRelativePath = "preferences/STSPlayer", sha256 = "same-local")
            ),
            remoteEntries = listOf(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = "preferences/STSPlayer",
                    rawSize = 100L,
                    timestamp = 100L,
                    sha1 = "old-remote-sha1",
                )
            )
        )

        val plan = SteamCloudDiffPlanner.buildUploadPlan(
            plannedAtMs = 2L,
            currentLocalEntries = listOf(
                localEntry(localRelativePath = "preferences/STSPlayer", sha256 = "same-local")
            ),
            currentRemoteSnapshot = remoteSnapshot(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = "preferences/STSPlayer",
                    rawSize = 100L,
                    timestamp = 100L,
                    sha1 = "new-remote-sha1",
                )
            ),
            baseline = baseline,
        )

        assertEquals(0, plan.uploadCandidates.size)
        assertEquals(0, plan.conflicts.size)
        assertEquals(1, plan.remoteOnlyChanges.size)
        assertEquals(
            SteamCloudRemoteOnlyChangeKind.MODIFIED_REMOTE_FILE,
            plan.remoteOnlyChanges.single().kind
        )
    }

    @Test
    fun buildUploadPlan_ignoresPreferenceLocalDeletes() {
        val baseline = SteamCloudSyncBaseline(
            syncedAtMs = 1L,
            localEntries = listOf(
                localEntry(localRelativePath = "preferences/STSPlayer", sha256 = "baseline")
            ),
            remoteEntries = listOf(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = "preferences/STSPlayer",
                    rawSize = 100L,
                    timestamp = 100L,
                )
            )
        )

        val plan = SteamCloudDiffPlanner.buildUploadPlan(
            plannedAtMs = 2L,
            currentLocalEntries = emptyList(),
            currentRemoteSnapshot = remoteSnapshot(
                remoteEntry(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = "preferences/STSPlayer",
                    rawSize = 100L,
                    timestamp = 100L,
                )
            ),
            baseline = baseline,
        )

        assertEquals(0, plan.uploadCandidates.size)
        assertEquals(0, plan.conflicts.size)
        assertEquals(0, plan.remoteDeleteCandidates.size)
        assertTrue(plan.warnings.any { it.contains("do not delete Steam Cloud files") })
    }

    @Test
    fun buildUploadPlan_deletesRemoteSaveWhenLocalSaveWasDeleted() {
        val baseline = SteamCloudSyncBaseline(
            syncedAtMs = 1L,
            localEntries = listOf(
                localEntry(
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    sha256 = "baseline",
                )
            ),
            remoteEntries = listOf(
                remoteEntry(
                    remotePath = "%GameInstall%saves/WATCHER.autosave",
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    rawSize = 100L,
                    timestamp = 100L,
                )
            )
        )

        val plan = SteamCloudDiffPlanner.buildUploadPlan(
            plannedAtMs = 2L,
            currentLocalEntries = emptyList(),
            currentRemoteSnapshot = remoteSnapshot(
                remoteEntry(
                    remotePath = "%GameInstall%saves/WATCHER.autosave",
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    rawSize = 100L,
                    timestamp = 100L,
                )
            ),
            baseline = baseline,
        )

        assertEquals(0, plan.uploadCandidates.size)
        assertEquals(0, plan.conflicts.size)
        assertEquals(0, plan.remoteOnlyChanges.size)
        assertEquals(1, plan.remoteDeleteCandidates.size)
        assertEquals(
            "%GameInstall%saves/WATCHER.autosave",
            plan.remoteDeleteCandidates.single().remotePath
        )
        assertEquals("saves/WATCHER.autosave", plan.remoteDeleteCandidates.single().localRelativePath)
        assertEquals(SteamCloudRootKind.SAVES, plan.remoteDeleteCandidates.single().rootKind)
        assertFalse(plan.warnings.any { it.contains("do not delete Steam Cloud files") })
    }

    private fun remoteSnapshot(vararg entries: SteamCloudManifestEntry): SteamCloudManifestSnapshot {
        return SteamCloudManifestSnapshot(
            fetchedAtMs = 2L,
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
        persistState: String = "Persisted",
        sha1: String = "",
    ): SteamCloudManifestEntry {
        return SteamCloudManifestEntry(
            remotePath = remotePath,
            localRelativePath = localRelativePath,
            rootKind = rootKind,
            rawSize = rawSize,
            timestamp = timestamp,
            machineName = "",
            persistState = persistState,
            sha1 = sha1,
        )
    }
}

package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudPushCoordinatorTest {
    @Test
    fun uploadBatchChangesAreVisible_acceptsMatchingUploadsAndDeletes() {
        assertTrue(
            SteamCloudPushCoordinator.uploadBatchChangesAreVisible(
                remoteEntries = listOf(remoteFile(UPLOAD_PATH, 3L, SHA1)),
                uploadCandidates = listOf(uploadCandidate(UPLOAD_PATH, 3L, SHA1.lowercase())),
                deleteRemotePaths = listOf(DELETE_PATH),
            )
        )
    }

    @Test
    fun uploadBatchChangesAreVisible_rejectsStaleUpload() {
        assertFalse(
            SteamCloudPushCoordinator.uploadBatchChangesAreVisible(
                remoteEntries = listOf(remoteFile(UPLOAD_PATH, 3L, "0000000000000000000000000000000000000000")),
                uploadCandidates = listOf(uploadCandidate(UPLOAD_PATH, 3L, SHA1)),
                deleteRemotePaths = emptyList(),
            )
        )
    }

    @Test
    fun uploadBatchChangesAreVisible_rejectsSizeOnlyMatchWhenCandidateHashIsMissing() {
        assertFalse(
            SteamCloudPushCoordinator.uploadBatchChangesAreVisible(
                remoteEntries = listOf(remoteFile(UPLOAD_PATH, 3L, SHA1)),
                uploadCandidates = listOf(uploadCandidate(UPLOAD_PATH, 3L, "")),
                deleteRemotePaths = emptyList(),
            )
        )
    }

    @Test
    fun uploadBatchChangesAreVisible_rejectsSizeOnlyMatchWhenRemoteHashIsMissing() {
        assertFalse(
            SteamCloudPushCoordinator.uploadBatchChangesAreVisible(
                remoteEntries = listOf(remoteFile(UPLOAD_PATH, 3L, "")),
                uploadCandidates = listOf(uploadCandidate(UPLOAD_PATH, 3L, SHA1)),
                deleteRemotePaths = emptyList(),
            )
        )
    }

    @Test
    fun uploadBatchChangesAreVisible_rejectsPendingDelete() {
        assertFalse(
            SteamCloudPushCoordinator.uploadBatchChangesAreVisible(
                remoteEntries = listOf(
                    remoteFile(UPLOAD_PATH, 3L, SHA1),
                    remoteFile(DELETE_PATH, 4L, SHA1),
                ),
                uploadCandidates = listOf(uploadCandidate(UPLOAD_PATH, 3L, SHA1)),
                deleteRemotePaths = listOf(DELETE_PATH),
            )
        )
    }

    @Test
    fun uploadBatchChangesAreVisible_acceptsExplicitDeleteTombstone() {
        assertTrue(
            SteamCloudPushCoordinator.uploadBatchChangesAreVisible(
                remoteEntries = listOf(
                    remoteFile(
                        remotePath = DELETE_PATH,
                        size = 0L,
                        sha1 = "",
                        persistState = "k_ECloudStoragePersistStateDeleted",
                    ),
                ),
                uploadCandidates = emptyList(),
                deleteRemotePaths = listOf(DELETE_PATH),
            )
        )
    }

    @Test
    fun uploadBatchChangesAreVisible_rejectsLiveAutosaveAwaitingExplicitDelete() {
        assertFalse(
            SteamCloudPushCoordinator.uploadBatchChangesAreVisible(
                remoteEntries = listOf(remoteFile(DELETE_PATH, 4L, SHA1)),
                uploadCandidates = emptyList(),
                deleteRemotePaths = listOf(DELETE_PATH),
            )
        )
    }

    @Test
    fun validateUploadPlanAgainstCurrentSnapshot_rejectsChangedNonzeroRemoteManifest() {
        val plan = uploadPlan(
            remoteManifestFetchedAtMs = 100L,
            candidate = uploadCandidate(
                remotePath = UPLOAD_PATH,
                size = 3L,
                sha1 = SHA1,
            )
        )
        val plannedSnapshot = remoteSnapshot(
            100L,
            remoteEntry(UPLOAD_PATH, "preferences/STSPlayer", 2L, SHA1),
        )
        val currentSnapshot = remoteSnapshot(
            200L,
            remoteEntry(UPLOAD_PATH, "preferences/STSPlayer", 4L, OTHER_SHA1),
        )

        var error: SteamCloudStalePlanException? = null
        try {
            SteamCloudPushCoordinator.validateUploadPlanAgainstCurrentSnapshot(
                plan = plan,
                currentRemoteSnapshot = currentSnapshot,
                currentLocalEntries = listOf(localEntry(size = 3L, sha1 = SHA1)),
                plannedRemoteSnapshot = plannedSnapshot,
            )
        } catch (caught: SteamCloudStalePlanException) {
            error = caught
        }

        assertTrue(error?.message.orEmpty().contains("stale"))
        assertTrue(error?.message.orEmpty().contains("manifest changed"))
    }

    @Test
    fun validateUploadPlanAgainstCurrentSnapshot_rejectsNonzeroTimestampMismatch() {
        val plan = uploadPlan(
            remoteManifestFetchedAtMs = 100L,
            candidate = uploadCandidate(UPLOAD_PATH, 3L, SHA1),
        )

        var error: SteamCloudStalePlanException? = null
        try {
            SteamCloudPushCoordinator.validateUploadPlanAgainstCurrentSnapshot(
                plan = plan,
                currentRemoteSnapshot = remoteSnapshot(
                    200L,
                    remoteEntry(UPLOAD_PATH, "preferences/STSPlayer", 2L, SHA1),
                ),
                currentLocalEntries = listOf(localEntry(size = 3L, sha1 = SHA1)),
                plannedRemoteSnapshot = remoteSnapshot(
                    101L,
                    remoteEntry(UPLOAD_PATH, "preferences/STSPlayer", 2L, SHA1),
                ),
            )
        } catch (caught: SteamCloudStalePlanException) {
            error = caught
        }

        assertTrue(error?.message.orEmpty().contains("timestamp"))
    }

    @Test
    fun validateUploadPlanAgainstCurrentSnapshot_rejectsRemoteMetadataMutation() {
        val plan = uploadPlan(
            remoteManifestFetchedAtMs = 100L,
            candidate = uploadCandidate(UPLOAD_PATH, 3L, SHA1),
        )

        var error: SteamCloudStalePlanException? = null
        try {
            SteamCloudPushCoordinator.validateUploadPlanAgainstCurrentSnapshot(
                plan = plan,
                currentRemoteSnapshot = remoteSnapshot(
                    200L,
                    remoteEntry(
                        remotePath = UPLOAD_PATH,
                        localRelativePath = "preferences/STSPlayer",
                        rawSize = 2L,
                        sha1 = SHA1,
                        timestamp = 11L,
                    ),
                ),
                currentLocalEntries = listOf(localEntry(size = 3L, sha1 = SHA1)),
                plannedRemoteSnapshot = remoteSnapshot(
                    100L,
                    remoteEntry(UPLOAD_PATH, "preferences/STSPlayer", 2L, SHA1),
                ),
            )
        } catch (caught: SteamCloudStalePlanException) {
            error = caught
        }

        assertTrue(error?.message.orEmpty().contains("manifest changed"))
    }

    @Test
    fun validateUploadPlanAgainstCurrentSnapshot_rejectsLocalMutation() {
        val plan = uploadPlan(
            remoteManifestFetchedAtMs = 0L,
            candidate = uploadCandidate(
                remotePath = UPLOAD_PATH,
                size = 3L,
                sha1 = SHA1,
            )
        )

        var error: SteamCloudStalePlanException? = null
        try {
            SteamCloudPushCoordinator.validateUploadPlanAgainstCurrentSnapshot(
                plan = plan,
                currentRemoteSnapshot = remoteSnapshot(
                    200L,
                    remoteEntry(UPLOAD_PATH, "preferences/STSPlayer", 2L, SHA1),
                ),
                currentLocalEntries = listOf(localEntry(size = 3L, sha1 = OTHER_SHA1)),
            )
        } catch (caught: SteamCloudStalePlanException) {
            error = caught
        }

        assertTrue(error?.message.orEmpty().contains("source changed"))
    }

    @Test
    fun buildReconciledBaseline_rejectsUploadedFileThatMutatedAfterCommit() {
        val candidate = uploadCandidate(UPLOAD_PATH, 3L, SHA1)
        val previousLocal = localEntry(size = 2L, sha1 = OTHER_SHA1)
        val previousRemote = remoteEntry(UPLOAD_PATH, "preferences/STSPlayer", 2L, OTHER_SHA1)
        var error: SteamCloudPushReconciliationException? = null
        try {
            SteamCloudPushCoordinator.buildReconciledBaseline(
                syncedAtMs = 300L,
                priorBaseline = SteamCloudSyncBaseline(
                    syncedAtMs = 100L,
                    localEntries = listOf(previousLocal),
                    remoteEntries = listOf(previousRemote),
                ),
                preUploadLocalEntries = listOf(localEntry(size = 3L, sha1 = SHA1)),
                preUploadRemoteSnapshot = remoteSnapshot(100L, previousRemote),
                currentLocalEntries = listOf(localEntry(size = 3L, sha1 = OTHER_SHA1)),
                currentRemoteSnapshot = remoteSnapshot(
                    300L,
                    remoteEntry(UPLOAD_PATH, "preferences/STSPlayer", 3L, SHA1),
                ),
                uploadCandidates = listOf(candidate),
                deleteCandidates = emptyList(),
            )
        } catch (caught: SteamCloudPushReconciliationException) {
            error = caught
        }

        assertTrue(error?.message.orEmpty().contains("changed locally"))
        assertTrue(error?.message.orEmpty().contains("previous baseline was retained"))
    }

    @Test
    fun buildReconciledBaseline_doesNotAdmitSizeOnlyUntouchedMatch() {
        val local = localEntry(size = 3L, sha1 = SHA1)
        val remote = remoteEntry(UPLOAD_PATH, "preferences/STSPlayer", 3L, "")
        val baseline = SteamCloudPushCoordinator.buildReconciledBaseline(
            syncedAtMs = 300L,
            priorBaseline = null,
            preUploadLocalEntries = listOf(local),
            preUploadRemoteSnapshot = remoteSnapshot(100L, remote),
            currentLocalEntries = listOf(local),
            currentRemoteSnapshot = remoteSnapshot(300L, remote),
            uploadCandidates = emptyList(),
            deleteCandidates = emptyList(),
        )

        assertTrue(baseline.localEntries.isEmpty())
        assertTrue(baseline.remoteEntries.isEmpty())
    }

    @Test
    fun buildReconciledBaseline_retainsExplicitDeleteTombstone() {
        val previousRemote = remoteEntry(
            DELETE_PATH,
            "saves/IRONCLAD.autosave",
            4L,
            SHA1,
        )
        val tombstone = remoteEntry(
            DELETE_PATH,
            "saves/IRONCLAD.autosave",
            0L,
            "",
            persistState = "k_ECloudStoragePersistStateDeleted",
        )

        val baseline = SteamCloudPushCoordinator.buildReconciledBaseline(
            syncedAtMs = 300L,
            priorBaseline = SteamCloudSyncBaseline(
                syncedAtMs = 100L,
                localEntries = emptyList(),
                remoteEntries = listOf(previousRemote),
            ),
            preUploadLocalEntries = emptyList(),
            preUploadRemoteSnapshot = remoteSnapshot(100L, previousRemote),
            currentLocalEntries = emptyList(),
            currentRemoteSnapshot = remoteSnapshot(300L, tombstone),
            uploadCandidates = emptyList(),
            deleteCandidates = listOf(
                SteamCloudRemoteDeleteCandidate(
                    remotePath = DELETE_PATH,
                    localRelativePath = "saves/IRONCLAD.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                )
            ),
        )

        assertTrue(baseline.localEntries.isEmpty())
        assertEquals(listOf(tombstone), baseline.remoteEntries)
    }

    @Test
    fun validateUploadPlanAgainstCurrentSnapshot_allowsTimestampZeroManualPlan() {
        val plan = uploadPlan(
            remoteManifestFetchedAtMs = 0L,
            candidate = uploadCandidate(
                remotePath = UPLOAD_PATH,
                size = 3L,
                sha1 = SHA1,
                kind = SteamCloudUploadCandidateKind.NEW_FILE,
            ),
        )

        SteamCloudPushCoordinator.validateUploadPlanAgainstCurrentSnapshot(
            plan = plan,
            currentRemoteSnapshot = remoteSnapshot(fetchedAtMs = 200L),
            currentLocalEntries = listOf(localEntry(size = 3L, sha1 = SHA1)),
        )
    }

    @Test
    fun validateUploadPlanAgainstCurrentSnapshot_allowsRecreatedFileOverSameTombstone() {
        val tombstone = remoteEntry(
            UPLOAD_PATH,
            "preferences/STSPlayer",
            0L,
            "",
            persistState = "k_ECloudStoragePersistStateDeleted",
        )
        val snapshot = remoteSnapshot(200L, tombstone)
        val local = localEntry(size = 3L, sha1 = SHA1)
        val plan = SteamCloudDiffPlanner.buildUploadPlan(
            plannedAtMs = 200L,
            currentLocalEntries = listOf(local),
            currentRemoteSnapshot = snapshot,
            baseline = SteamCloudSyncBaseline(
                syncedAtMs = 100L,
                localEntries = emptyList(),
                remoteEntries = listOf(tombstone),
            ),
        )

        assertEquals(SteamCloudUploadCandidateKind.NEW_FILE, plan.uploadCandidates.single().kind)
        SteamCloudPushCoordinator.validateUploadPlanAgainstCurrentSnapshot(
            plan = plan,
            currentRemoteSnapshot = snapshot,
            currentLocalEntries = listOf(local),
        )
    }

    private fun uploadPlan(
        remoteManifestFetchedAtMs: Long,
        candidate: SteamCloudUploadCandidate,
    ) = SteamCloudUploadPlan(
        plannedAtMs = 100L,
        remoteManifestFetchedAtMs = remoteManifestFetchedAtMs,
        baselineConfigured = true,
        uploadCandidates = listOf(candidate),
        conflicts = emptyList(),
        remoteOnlyChanges = emptyList(),
        remoteDeleteCandidates = emptyList(),
        warnings = emptyList(),
    )

    private fun localEntry(
        size: Long,
        sha1: String,
        sha256: String = "sha256",
        lastModifiedMs: Long = 0L,
    ) = SteamCloudLocalFileSnapshotEntry(
        localRelativePath = "preferences/STSPlayer",
        rootKind = SteamCloudRootKind.PREFERENCES,
        fileSize = size,
        lastModifiedMs = lastModifiedMs,
        sha256 = sha256,
        sha1 = sha1,
    )

    private fun remoteSnapshot(
        fetchedAtMs: Long,
        vararg entries: SteamCloudManifestEntry,
    ) = SteamCloudManifestSnapshot(
        fetchedAtMs = fetchedAtMs,
        fileCount = entries.size,
        preferencesCount = entries.count { it.rootKind == SteamCloudRootKind.PREFERENCES },
        savesCount = entries.count { it.rootKind == SteamCloudRootKind.SAVES },
        entries = entries.toList(),
        warnings = emptyList(),
    )

    private fun remoteEntry(
        remotePath: String,
        localRelativePath: String,
        rawSize: Long,
        sha1: String,
        timestamp: Long = 10L,
        machineName: String = "device",
        persistState: String = "Persisted",
    ) = SteamCloudManifestEntry(
        remotePath = remotePath,
        localRelativePath = localRelativePath,
        rootKind = if (localRelativePath.startsWith("saves/")) {
            SteamCloudRootKind.SAVES
        } else {
            SteamCloudRootKind.PREFERENCES
        },
        rawSize = rawSize,
        timestamp = timestamp,
        machineName = machineName,
        persistState = persistState,
        sha1 = sha1,
    )

    private fun uploadCandidate(
        remotePath: String,
        size: Long,
        sha1: String,
        kind: SteamCloudUploadCandidateKind = SteamCloudUploadCandidateKind.MODIFIED_FILE,
    ) = SteamCloudUploadCandidate(
        remotePath = remotePath,
        localRelativePath = "preferences/STSPlayer",
        rootKind = SteamCloudRootKind.PREFERENCES,
        fileSize = size,
        lastModifiedMs = 0L,
        sha256 = "sha256",
        sha1 = sha1,
        kind = kind,
    )

    private fun remoteFile(
        remotePath: String,
        size: Long,
        sha1: String,
        persistState: String = "Persisted",
    ) = SteamCloudClient.RemoteFileRecord(
        remotePath,
        size,
        0L,
        "device",
        persistState,
        sha1,
    )

    private companion object {
        const val UPLOAD_PATH = "%GameInstall%preferences/STSPlayer"
        const val DELETE_PATH = "%GameInstall%saves/IRONCLAD.autosave"
        const val SHA1 = "A9993E364706816ABA3E25717850C26C9CD0D89D"
        const val OTHER_SHA1 = "0000000000000000000000000000000000000000"
    }
}

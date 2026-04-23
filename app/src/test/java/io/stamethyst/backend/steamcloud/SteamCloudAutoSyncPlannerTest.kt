package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudAutoSyncPlannerTest {
    @Test
    fun planPreLaunchPull_pullsWhenOnlyRemoteChanged() {
        val decision = SteamCloudAutoSyncPlanner.planPreLaunchPull(
            uploadPlan(
                uploadCandidates = emptyList(),
                conflicts = emptyList(),
                remoteOnlyChanges = listOf(remoteOnlyChange("preferences/STSPlayer"))
            )
        )

        assertTrue(decision.shouldPull)
        assertFalse(decision.shouldBlockLaunch)
        assertNull(decision.blockReason)
    }

    @Test
    fun planPreLaunchPull_doesNothingWhenNothingChanged() {
        val decision = SteamCloudAutoSyncPlanner.planPreLaunchPull(uploadPlan())

        assertFalse(decision.shouldPull)
        assertFalse(decision.shouldBlockLaunch)
        assertNull(decision.blockReason)
    }

    @Test
    fun planPreLaunchPull_allowsLaunchWhenOnlyLocalChangesExist() {
        val decision = SteamCloudAutoSyncPlanner.planPreLaunchPull(
            uploadPlan(
                uploadCandidates = listOf(uploadCandidate("preferences/STSPlayer"))
            )
        )

        assertFalse(decision.shouldPull)
        assertFalse(decision.shouldBlockLaunch)
        assertNull(decision.blockReason)
    }

    @Test
    fun planPreLaunchPull_blocksWhenRemoteAndLocalChangesWouldCollide() {
        val decision = SteamCloudAutoSyncPlanner.planPreLaunchPull(
            uploadPlan(
                uploadCandidates = listOf(uploadCandidate("preferences/STSPlayer")),
                remoteOnlyChanges = listOf(remoteOnlyChange("preferences/STSSaveSlots"))
            )
        )

        assertFalse(decision.shouldPull)
        assertTrue(decision.shouldBlockLaunch)
        assertEquals(
            SteamCloudAutoSyncPlanner.LaunchBlockReason.LOCAL_CHANGES_PRESENT,
            decision.blockReason
        )
    }

    @Test
    fun planPreLaunchPull_blocksWhenConflictsExist() {
        val decision = SteamCloudAutoSyncPlanner.planPreLaunchPull(
            uploadPlan(
                conflicts = listOf(conflict("preferences/STSPlayer"))
            )
        )

        assertFalse(decision.shouldPull)
        assertTrue(decision.shouldBlockLaunch)
        assertEquals(
            SteamCloudAutoSyncPlanner.LaunchBlockReason.CONFLICTS_PRESENT,
            decision.blockReason
        )
    }

    @Test
    fun planCleanShutdownPush_pushesSafeLocalChanges() {
        val decision = SteamCloudAutoSyncPlanner.planCleanShutdownPush(
            uploadPlan(
                uploadCandidates = listOf(uploadCandidate("preferences/STSPlayer"))
            )
        )

        assertTrue(decision.shouldPush)
        assertFalse(decision.blockedByConflicts)
    }

    @Test
    fun planCleanShutdownPush_blocksOnConflicts() {
        val decision = SteamCloudAutoSyncPlanner.planCleanShutdownPush(
            uploadPlan(
                uploadCandidates = listOf(uploadCandidate("preferences/STSPlayer")),
                conflicts = listOf(conflict("preferences/STSPlayer"))
            )
        )

        assertFalse(decision.shouldPush)
        assertTrue(decision.blockedByConflicts)
    }

    private fun uploadPlan(
        uploadCandidates: List<SteamCloudUploadCandidate> = emptyList(),
        conflicts: List<SteamCloudConflict> = emptyList(),
        remoteOnlyChanges: List<SteamCloudRemoteOnlyChange> = emptyList(),
    ): SteamCloudUploadPlan {
        return SteamCloudUploadPlan(
            plannedAtMs = 1L,
            baselineConfigured = true,
            uploadCandidates = uploadCandidates,
            conflicts = conflicts,
            remoteOnlyChanges = remoteOnlyChanges,
            warnings = emptyList(),
        )
    }

    private fun uploadCandidate(localRelativePath: String): SteamCloudUploadCandidate {
        return SteamCloudUploadCandidate(
            remotePath = SteamCloudPathMapper.buildRemotePath(localRelativePath)
                ?: error("Unsupported path for test: $localRelativePath"),
            localRelativePath = localRelativePath,
            rootKind = SteamCloudPathMapper.mapLocalRelativePath(localRelativePath)?.rootKind
                ?: error("Unsupported path for test: $localRelativePath"),
            fileSize = 100L,
            lastModifiedMs = 1L,
            sha256 = "sha256",
            kind = SteamCloudUploadCandidateKind.MODIFIED_FILE,
        )
    }

    private fun conflict(localRelativePath: String): SteamCloudConflict {
        val mapped = SteamCloudPathMapper.mapLocalRelativePath(localRelativePath)
            ?: error("Unsupported path for test: $localRelativePath")
        return SteamCloudConflict(
            localRelativePath = localRelativePath,
            rootKind = mapped.rootKind,
            kind = SteamCloudConflictKind.BOTH_CHANGED,
            currentLocal = null,
            currentRemote = null,
            baselineLocal = null,
            baselineRemote = null,
        )
    }

    private fun remoteOnlyChange(localRelativePath: String): SteamCloudRemoteOnlyChange {
        val mapped = SteamCloudPathMapper.mapLocalRelativePath(localRelativePath)
            ?: error("Unsupported path for test: $localRelativePath")
        return SteamCloudRemoteOnlyChange(
            localRelativePath = localRelativePath,
            rootKind = mapped.rootKind,
            kind = SteamCloudRemoteOnlyChangeKind.MODIFIED_REMOTE_FILE,
            currentRemote = null,
            baselineRemote = null,
        )
    }
}

package io.stamethyst.backend.steamcloud

import org.junit.Assert.*
import org.junit.Test

class SteamCloudSyncPlannerTest {
    private fun local(path: String = "saves/IRONCLAD.autosave", content: String = "a") =
        SteamCloudLocalFileSnapshotEntry(path, SteamCloudRootKind.entries.first { path.startsWith(it.directoryName) },
            content.length.toLong(), 1, "sha256-$content", "sha1-$content")
    private fun remote(entry: SteamCloudLocalFileSnapshotEntry) = SteamCloudManifestEntry(
        requireNotNull(SteamCloudPathMapper.buildRemotePath(entry.localRelativePath)), entry.localRelativePath,
        entry.rootKind, entry.fileSize, 1, "pc", "Persisted", entry.sha1)
    private fun snapshot(vararg entries: SteamCloudManifestEntry) = SteamCloudManifestSnapshot(1, 0, 0, 0, entries.toList(), emptyList(), steamId64 = "1")
    private fun baseline(vararg entries: SteamCloudLocalFileSnapshotEntry) = SteamCloudSyncBaseline(1, entries.toList(), entries.map(::remote), "1")
    private fun plan(l: List<SteamCloudLocalFileSnapshotEntry>, r: SteamCloudManifestSnapshot,
        b: SteamCloudSyncBaseline? = null, mode: SteamCloudSyncMode = SteamCloudSyncMode.MERGE) =
        SteamCloudSyncPlanner.buildUploadPlan(2, l, r, b, mode)

    @Test fun firstSyncDifferentContentsRequireResolution() {
        val p = plan(listOf(local()), snapshot(remote(local(content = "b"))))
        assertEquals(SteamCloudConflictKind.BASELINE_REQUIRED, p.conflicts.single().kind)
    }
    @Test fun firstSyncMatchingContentsAreAlreadyAgreed() {
        val p = plan(listOf(local()), snapshot(remote(local())))
        assertTrue(p.conflicts.isEmpty()); assertTrue(p.uploadCandidates.isEmpty()); assertTrue(p.remoteOnlyChanges.isEmpty())
    }
    @Test fun firstSyncLocalOnlyUploads() { assertEquals(1, plan(listOf(local()), snapshot()).uploadCandidates.size) }
    @Test fun firstSyncRemoteOnlyDownloads() { assertEquals(1, plan(emptyList(), snapshot(remote(local()))).remoteOnlyChanges.size) }
    @Test fun localChangeUploadsAgainstAgreedBaseline() {
        val p = plan(listOf(local(content = "b")), snapshot(remote(local())), baseline(local()))
        assertEquals(1, p.uploadCandidates.size); assertTrue(p.conflicts.isEmpty())
    }
    @Test fun remoteChangeDownloadsAgainstAgreedBaseline() {
        val p = plan(listOf(local()), snapshot(remote(local(content = "b"))), baseline(local()))
        assertEquals(1, p.remoteOnlyChanges.size); assertTrue(p.conflicts.isEmpty())
    }
    @Test fun bothChangesConflict() {
        val p = plan(listOf(local(content = "b")), snapshot(remote(local(content = "c"))), baseline(local()))
        assertEquals(SteamCloudConflictKind.BOTH_CHANGED, p.conflicts.single().kind)
    }
    @Test fun bothChangesToSameContentConverge() {
        val p = plan(listOf(local(content = "b")), snapshot(remote(local(content = "b"))), baseline(local()))
        assertTrue(p.conflicts.isEmpty()); assertTrue(p.uploadCandidates.isEmpty())
    }
    @Test fun disjointChangesMerge() {
        val a = local(); val b = local("preferences/STSPlayer")
        val p = plan(listOf(a.copy(sha256 = "new", sha1 = "new"), b),
            snapshot(remote(a), remote(b.copy(sha1 = "remote"))), baseline(a, b))
        assertEquals(1, p.uploadCandidates.size); assertEquals(1, p.remoteOnlyChanges.size); assertTrue(p.conflicts.isEmpty())
    }
    @Test fun localSaveDeletionPropagates() {
        assertEquals(1, plan(emptyList(), snapshot(remote(local())), baseline(local())).remoteDeleteCandidates.size)
    }
    @Test fun preferenceDeletionIsNotPropagatedAutomatically() {
        val a = local("preferences/STSPlayer")
        val p = plan(emptyList(), snapshot(remote(a)), baseline(a))
        assertTrue(p.remoteDeleteCandidates.isEmpty()); assertFalse(p.warnings.isEmpty())
    }
    @Test fun forcedMirrorDeletesPreferencesToo() {
        val a = local("preferences/STSPlayer")
        assertEquals(1, plan(emptyList(), snapshot(remote(a)), mode = SteamCloudSyncMode.LOCAL_WINS).remoteDeleteCandidates.size)
    }
    @Test fun remoteDeletionPropagatesToLocal() {
        assertEquals(SteamCloudRemoteOnlyChangeKind.REMOTE_FILE_DELETED,
            plan(listOf(local()), snapshot(), baseline(local())).remoteOnlyChanges.single().kind)
    }
    @Test fun tombstoneIsEquivalentToRemoteAbsence() {
        val p = plan(listOf(local()), snapshot(remote(local()).copy(persistState = "Deleted")), baseline(local()))
        assertEquals(SteamCloudRemoteOnlyChangeKind.REMOTE_FILE_DELETED, p.remoteOnlyChanges.single().kind)
    }
    @Test fun deletionVersusModificationConflicts() {
        assertEquals(1, plan(emptyList(), snapshot(remote(local(content = "b"))), baseline(local())).conflicts.size)
        assertEquals(1, plan(listOf(local(content = "b")), snapshot(), baseline(local())).conflicts.size)
    }
    @Test fun divergentLegacyBaselineIsNotAgreement() {
        val corrupt = baseline(local()).copy(remoteEntries = listOf(remote(local(content = "b"))))
        assertEquals(1, plan(listOf(local()), snapshot(remote(local(content = "b"))), corrupt).conflicts.size)
    }
    @Test fun timestampDoesNotDetermineWinner() {
        val p = plan(listOf(local().copy(lastModifiedMs = 999)), snapshot(remote(local()).copy(timestamp = 0)), baseline(local()))
        assertTrue(p.uploadCandidates.isEmpty()); assertTrue(p.conflicts.isEmpty())
    }
    @Test fun explicitWinnersResolveConflictInEitherDirection() {
        val l = listOf(local()); val r = snapshot(remote(local(content = "b")))
        assertEquals(1, plan(l, r, mode = SteamCloudSyncMode.LOCAL_WINS).uploadCandidates.size)
        assertEquals(1, plan(l, r, mode = SteamCloudSyncMode.CLOUD_WINS).remoteOnlyChanges.size)
    }
    @Test fun reconcileDoesNotAdvanceUntouchedRemoteChange() {
        val b = baseline(local())
        val next = SteamCloudSyncPlanner.reconcileBaseline(b, listOf(local()), snapshot(remote(local(content = "b"))))
        assertEquals(b.localEntries, next.localEntries); assertEquals(b.remoteEntries, next.remoteEntries)
    }
    @Test fun reconcileAdvancesOnlyVerifiedContents() {
        val b = SteamCloudSyncPlanner.reconcileBaseline(null, listOf(local()), snapshot(remote(local())))
        assertEquals(listOf(local()), b.localEntries); assertEquals("1", b.steamId64)
    }
    @Test fun independentlyConvergedDeletionsRetireBaseline() {
        val next = SteamCloudSyncPlanner.reconcileBaseline(baseline(local()), emptyList(), snapshot())
        assertTrue(next.localEntries.isEmpty()); assertTrue(next.remoteEntries.isEmpty())
        val rebuilt = plan(listOf(local()), snapshot(), next)
        assertEquals(1, rebuilt.uploadCandidates.size); assertTrue(rebuilt.remoteOnlyChanges.isEmpty())
    }
    @Test fun reconcileNeverRetiresAnUnverifiedDeletion() {
        val next = SteamCloudSyncPlanner.reconcileBaseline(baseline(local()), listOf(local()), snapshot(), setOf(local().localRelativePath))
        assertEquals(1, next.localEntries.size)
    }
    @Test(expected = SteamCloudIncompleteManifestException::class) fun missingRemoteHashStopsPlanning() {
        plan(listOf(local()), snapshot(remote(local()).copy(sha1 = "")))
    }
    @Test(expected = SteamCloudIncompleteManifestException::class) fun duplicateMappingStopsPlanning() {
        plan(listOf(local()), snapshot(remote(local()), remote(local())))
    }
    @Test(expected = SteamCloudIncompleteManifestException::class) fun mismatchedRemoteMappingStopsPlanning() {
        plan(listOf(local()), snapshot(remote(local()).copy(localRelativePath = "saves/OTHER")))
    }
}

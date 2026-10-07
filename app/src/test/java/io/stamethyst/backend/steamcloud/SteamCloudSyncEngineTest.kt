package io.stamethyst.backend.steamcloud

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamCloudSyncEngineTest {
    @get:Rule val temp = TemporaryFolder()
    private val root get() = temp.root
    private val live get() = File(root, "live")
    private val frozen get() = File(root, "frozen")
    private val state get() = File(root, "steam-cloud")
    private val path = "saves/IRONCLAD.autosave"
    private fun put(root: File, path: String, text: String) = File(root, path).also { it.parentFile.mkdirs(); it.writeText(text) }
    private fun freeze() { live.mkdirs(); SteamCloudFileTransaction.copyPath(live, frozen) }
    private fun localEntries() = SteamCloudLocalSnapshotCollector.collect(live)
    private fun baseline(remote: FakeRemote) = SteamCloudSyncBaseline(1, localEntries(), remote.manifest().entries, "1")
    private fun savedBaseline() = Json.decodeFromString<SteamCloudSyncBaseline>(File(state, "sync-baseline.json").readText())
    private fun engine(remote: FakeRemote, blacklist: Set<String> = emptySet(),
        continueSync: () -> Boolean = { true }, mutate: ((() -> Unit) -> Unit) = { it() }) =
        SteamCloudSyncEngine(root, live, state, remote, blacklist, continueSync, mutate = mutate)

    @Test fun ordinaryUploadVerifiesAndAdvancesBaseline() {
        val remote = FakeRemote(); put(live, path, "old"); remote.files[path] = "old"
        val old = baseline(remote); put(live, path, "new"); freeze()
        val result = engine(remote).run(frozen, old, SteamCloudSyncMode.MERGE)
        assertEquals(1, result.uploaded); assertEquals("new", remote.files[path])
        assertEquals(localEntries(), savedBaseline().localEntries); assertFalse(File(state, "remote-pending-v2.json").exists())
    }
    @Test fun forcedUploadUsesSamePostCommitVerification() {
        val remote = FakeRemote(); put(live, path, "new"); remote.files[path] = "old"; remote.dropCommit = true; freeze()
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS); fail() }
        catch (_: SteamCloudPushReconciliationException) { }
        assertTrue(File(state, "remote-pending-v2.json").exists()); assertFalse(File(state, "sync-baseline.json").exists())
    }
    @Test fun unknownOutcomeIsNotAutomaticallyReplayed() {
        val remote = FakeRemote(); put(live, path, "new"); remote.files[path] = "old"; remote.dropCommit = true; freeze()
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS); fail() } catch (_: SteamCloudPushReconciliationException) { }
        remote.dropCommit = false
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE); fail() } catch (_: SteamCloudPushReconciliationException) { }
        assertEquals(1, remote.beginCount)
    }
    @Test fun thrownCompletionResponseCanStillBeVerified() {
        val remote = FakeRemote(); remote.throwAfterCommit = true; put(live, path, "new"); freeze()
        assertEquals(1, engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS).uploaded)
        assertEquals("new", remote.files[path]); assertFalse(File(state, "remote-pending-v2.json").exists())
    }
    @Test fun interruptedVerifiedCommitRecoversWithoutUploadReplay() {
        val remote = FakeRemote(); put(live, path, "new"); freeze()
        remote.failManifestAfterCommit = true
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS); fail() } catch (_: IOException) { }
        assertTrue(File(state, "remote-pending-v2.json").exists())
        remote.failManifestAfterCommit = false
        val result = engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE)
        assertEquals(0, result.uploaded); assertEquals(1, remote.beginCount); assertEquals("new", remote.files[path])
    }
    @Test fun explicitResolutionArchivesUncertainRecord() {
        val remote = FakeRemote(); put(live, path, "new"); remote.files[path] = "old"; remote.dropCommit = true; freeze()
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS); fail() } catch (_: SteamCloudPushReconciliationException) { }
        remote.dropCommit = false
        engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS)
        assertTrue(state.listFiles()!!.any { it.name.startsWith("uncertain-remote-") }); assertEquals("new", remote.files[path])
    }
    @Test fun pendingWriteForAnotherAccountCannotBeReplayedAutomatically() {
        val remote = FakeRemote(); put(live, path, "account-one"); remote.dropCommit = true; freeze()
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS); fail() }
        catch (_: SteamCloudPushReconciliationException) { }
        remote.steamId64 = "2"; remote.dropCommit = false; remote.files[path] = "account-two"
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE); fail() }
        catch (_: SteamCloudPushReconciliationException) { }
        assertEquals(1, remote.beginCount); assertEquals("account-two", remote.files[path])
        engine(remote).run(frozen, null, SteamCloudSyncMode.CLOUD_WINS)
        assertEquals("account-two", File(live, path).readText())
        assertEquals("2", savedBaseline().steamId64)
        assertTrue(state.listFiles()!!.any { it.name.startsWith("uncertain-remote-") })
    }
    @Test fun cloudWinnerDownloadsVerifiesAndPreservesOriginalBackup() {
        val remote = FakeRemote(); put(live, path, "old"); remote.files[path] = "new"; freeze()
        assertEquals(1, engine(remote).run(frozen, null, SteamCloudSyncMode.CLOUD_WINS).downloaded)
        assertEquals("new", File(live, path).readText())
        val backup = File(state, "history-v2").listFiles()!!.single()
        assertEquals("old", File(backup, "old/0").readText())
    }
    @Test fun downloadHashMismatchNeverChangesLiveSaves() {
        val remote = FakeRemote(); put(live, path, "old"); remote.files[path] = "new"; remote.corruptDownloads = true; freeze()
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.CLOUD_WINS); fail() } catch (_: IllegalStateException) { }
        assertEquals("old", File(live, path).readText()); assertEquals(0, remote.beginCount)
    }
    @Test fun localMutationDuringDownloadStopsApply() {
        val remote = FakeRemote(); put(live, path, "old"); remote.files[path] = "new"; freeze()
        try { engine(remote, mutate = { apply -> put(live, path, "game-write"); apply() })
            .run(frozen, null, SteamCloudSyncMode.CLOUD_WINS); fail() } catch (_: SteamCloudStalePlanException) { }
        assertEquals("game-write", File(live, path).readText())
    }
    @Test fun concurrentRemoteMutationStopsBeforeUpload() {
        val remote = FakeRemote(); put(live, path, "new"); remote.files[path] = "old"; freeze()
        remote.beforeManifest = { count -> if (count == 2) remote.files[path] = "other-device" }
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS); fail() } catch (_: SteamCloudStalePlanException) { }
        assertEquals(0, remote.beginCount); assertEquals("other-device", remote.files[path])
    }
    @Test fun concurrentRemoteMutationAfterCommitKeepsUncertainRecord() {
        val remote = FakeRemote(); put(live, path, "new"); freeze()
        remote.afterCommit = { remote.files[path] = "other-device" }
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS); fail() } catch (_: SteamCloudPushReconciliationException) { }
        assertTrue(File(state, "remote-pending-v2.json").exists()); assertFalse(File(state, "sync-baseline.json").exists())
    }
    @Test fun frozenUploadDoesNotReadNewGameWrites() {
        val remote = FakeRemote(); put(live, path, "frozen"); freeze(); put(live, path, "game-new")
        engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS)
        assertEquals("frozen", remote.files[path]); assertEquals("game-new", File(live, path).readText())
        assertEquals(SteamCloudLocalSnapshotCollector.collect(frozen), savedBaseline().localEntries)
    }
    @Test fun uploadCanCommitProfileMetadataWhileGameOwnsLiveLease() {
        val remote = FakeRemote(); put(live, path, "frozen"); freeze(); put(live, path, "game-new")
        engine(remote, mutate = { throw AssertionError("Upload must not acquire a second live-save lease") })
            .run(frozen, null, SteamCloudSyncMode.LOCAL_WINS, metadata = { work, projection ->
                val marker = File(work, "profile-marker").also { it.writeText("ready") }
                listOf(SteamCloudPathReplacement(File(projection, path), File(root, "profile/$path")),
                    SteamCloudPathReplacement(marker, File(root, "profile/.initialized")))
            })
        assertEquals("frozen", File(root, "profile/$path").readText())
        assertEquals("game-new", File(live, path).readText())
    }
    @Test fun launchFromInspectedUploadPlanUsesFrozenFilesAndPreservesNewGameWrites() {
        val remote = FakeRemote(); put(live, path, "old"); remote.files[path] = "old"
        val old = baseline(remote); put(live, path, "before-launch"); freeze()
        var launched = false
        val result = engine(remote, mutate = { throw AssertionError("Pure upload must not mutate live saves") })
            .run(frozen, old, SteamCloudSyncMode.MERGE, onPlan = { plan ->
                assertTrue(plan.conflicts.isEmpty()); assertTrue(plan.remoteOnlyChanges.isEmpty())
                assertEquals(1, plan.uploadCandidates.size)
                launched = true
                put(live, path, "game-after-launch")
            })
        assertTrue(launched); assertEquals(1, result.uploaded)
        assertEquals("before-launch", remote.files[path]); assertEquals("game-after-launch", File(live, path).readText())
        val next = SteamCloudSyncPlanner.buildUploadPlan(2, localEntries(), remote.manifest(), savedBaseline())
        assertTrue(next.conflicts.isEmpty()); assertEquals(1, next.uploadCandidates.size)
    }
    @Test fun immediateLaunchBeforeRemoteCheckStillAllowsFrozenUpload() {
        val remote = FakeRemote(); put(live, path, "old"); remote.files[path] = "old"
        val old = baseline(remote); put(live, path, "before-launch"); freeze()
        put(live, path, "game-new")
        val result = engine(remote, mutate = { fail("Upload must never reacquire the game lease") })
            .run(frozen, old, SteamCloudSyncMode.MERGE, shouldDeferLocalChanges = { true })
        assertEquals(1, result.uploaded); assertEquals("before-launch", remote.files[path])
        assertEquals("game-new", File(live, path).readText())
    }
    @Test fun immediateLaunchDefersMixedPlanBeforeAnyDownloadOrRemoteWrite() {
        val remote = FakeRemote(); val other = "preferences/STSPlayer"
        put(live, path, "old-save"); put(live, other, "old-pref")
        remote.files[path] = "old-save"; remote.files[other] = "old-pref"
        val old = baseline(remote); put(live, path, "new-save"); remote.files[other] = "new-pref"; freeze()
        try {
            engine(remote).run(frozen, old, SteamCloudSyncMode.MERGE, shouldDeferLocalChanges = { true })
            fail()
        } catch (_: SteamCloudLiveSaveInUseException) { }
        assertEquals(0, remote.beginCount); assertEquals(0, remote.downloadCount)
        assertEquals("old-save", remote.files[path]); assertEquals("old-pref", File(live, other).readText())
        assertFalse(File(state, "remote-pending-v2.json").exists())
    }
    @Test fun immediateLaunchDuringDownloadStopsMixedBatchBeforeRemoteWrites() {
        val remote = FakeRemote(); val other = "preferences/STSPlayer"
        put(live, path, "old-save"); put(live, other, "old-pref")
        remote.files[path] = "old-save"; remote.files[other] = "old-pref"
        val old = baseline(remote); put(live, path, "new-save"); remote.files[other] = "new-pref"; freeze()
        var launchRequested = false
        remote.afterDownload = { launchRequested = true; put(live, path, "game-new") }
        try {
            engine(remote).run(frozen, old, SteamCloudSyncMode.MERGE, shouldDeferLocalChanges = { launchRequested })
            fail()
        } catch (_: SteamCloudLiveSaveInUseException) { }
        assertEquals(1, remote.downloadCount); assertEquals(0, remote.beginCount)
        assertEquals("game-new", File(live, path).readText()); assertEquals("old-pref", File(live, other).readText())
        assertFalse(File(state, "remote-pending-v2.json").exists())
    }
    @Test fun launchRequestAtLocalCommitStopsCloudDeletionEvenBeforeGameLeaseIsAcquired() {
        val remote = FakeRemote(); put(live, path, "old"); remote.files[path] = "old"
        val old = baseline(remote); remote.files.remove(path); freeze()
        var launchRequested = false
        val guarded = SteamCloudSyncEngine(root, live, state, remote, emptySet(), transact = { commit ->
            launchRequested = true
            commit()
        })
        try {
            guarded.run(frozen, old, SteamCloudSyncMode.MERGE, shouldDeferLocalChanges = { launchRequested })
            fail()
        } catch (_: SteamCloudLiveSaveInUseException) { }
        assertEquals("old", File(live, path).readText()); assertEquals(0, remote.beginCount)
        assertFalse(File(state, "sync-baseline.json").exists())
    }
    @Test fun mixedPlanCannotLaunchJustBecauseItsCurrentStageIsUploading() {
        val remote = FakeRemote(); val other = "preferences/STSPlayer"
        put(live, path, "old-save"); put(live, other, "old-pref")
        remote.files[path] = "old-save"; remote.files[other] = "old-pref"
        val old = baseline(remote); put(live, path, "new-save"); remote.files[other] = "new-pref"; freeze()
        var gameOwnsLease = false
        val guarded = SteamCloudSyncEngine(root, live, state, remote, emptySet(), progress = { progress ->
            if (progress.phase == SteamCloudSyncPhase.UPLOADING) gameOwnsLease = true
        }, mutate = { apply ->
            if (gameOwnsLease) throw SteamCloudLiveSaveInUseException()
            apply()
        })
        try {
            guarded.run(frozen, old, SteamCloudSyncMode.MERGE, onPlan = { plan ->
                assertEquals(1, plan.remoteOnlyChanges.size)
                assertEquals(1, plan.uploadCandidates.size)
            })
            fail("Starting the game during a mixed sync must block local installation")
        } catch (_: SteamCloudLiveSaveInUseException) { }
        assertEquals("old-pref", File(live, other).readText())
        assertTrue(File(state, "remote-pending-v2.json").exists())
    }
    @Test fun downloadedFilesAndModeMetadataRollbackTogether() {
        val remote = FakeRemote(); put(live, path, "old"); remote.files[path] = "new"; freeze()
        val mode = put(root, "mode", "independent")
        val brokenEngine = SteamCloudSyncEngine(root, live, state, remote, emptySet(), transact = {
            throw IOException("transaction adapter failed")
        })
        try { brokenEngine.run(frozen, null, SteamCloudSyncMode.CLOUD_WINS, metadata = { work, _ ->
            listOf(SteamCloudPathReplacement(put(work, "mode", "cloud"), mode))
        }, metadataRequiresLiveLease = true); fail() } catch (_: IOException) { }
        assertEquals("old", File(live, path).readText()); assertEquals("independent", mode.readText())
    }
    @Test fun alternateProfileAndBaselineInstallInOneLocalTransaction() {
        val remote = FakeRemote(); put(live, path, "old-cloud"); put(frozen, path, "independent")
        engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS,
            metadata = { _, projected -> listOf(SteamCloudPathReplacement(File(projected, path), File(live, path))) },
            requiresLocalInstall = true)
        assertEquals("independent", File(live, path).readText()); assertEquals("independent", remote.files[path])
        assertEquals(localEntries(), savedBaseline().localEntries)
    }
    @Test fun failedAlternateProfileInstallCannotAutomaticallyUploadOldCloudFiles() {
        val remote = FakeRemote(); put(live, path, "old-cloud"); put(frozen, path, "independent")
        try {
            engine(remote, mutate = { throw IOException("local installation failed") }).run(frozen, null,
                SteamCloudSyncMode.LOCAL_WINS, metadata = { _, projected ->
                    listOf(SteamCloudPathReplacement(File(projected, path), File(live, path)))
                }, requiresLocalInstall = true)
            fail()
        } catch (_: IOException) { }
        assertEquals("independent", remote.files[path]); assertEquals("old-cloud", File(live, path).readText())
        frozen.deleteRecursively(); freeze()
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE); fail() }
        catch (_: SteamCloudPushReconciliationException) { }
        assertEquals(1, remote.beginCount)
        engine(remote).run(frozen, null, SteamCloudSyncMode.CLOUD_WINS)
        assertEquals("independent", File(live, path).readText()); assertEquals(1, remote.beginCount)
    }
    @Test fun disjointLocalAndRemoteChangesCommitTogether() {
        val remote = FakeRemote(); val other = "preferences/STSPlayer"
        put(live, path, "old-save"); put(live, other, "old-pref"); remote.files[path] = "old-save"; remote.files[other] = "old-pref"
        val old = baseline(remote); put(live, path, "new-save"); remote.files[other] = "new-pref"; freeze()
        val result = engine(remote).run(frozen, old, SteamCloudSyncMode.MERGE)
        assertEquals(1, result.uploaded); assertEquals(1, result.downloaded)
        assertEquals("new-save", remote.files[path]); assertEquals("new-pref", File(live, other).readText())
        assertEquals(localEntries(), savedBaseline().localEntries)
    }
    @Test fun uploadOnlyPreservesRemoteChangesForNextMerge() {
        val remote = FakeRemote(); val other = "preferences/STSPlayer"
        put(live, path, "old-save"); put(live, other, "old-pref"); remote.files[path] = "old-save"; remote.files[other] = "old-pref"
        val old = baseline(remote); put(live, path, "new-save"); remote.files[other] = "new-pref"; freeze()
        engine(remote).run(frozen, old, SteamCloudSyncMode.UPLOAD_ONLY)
        assertEquals("old-pref", File(live, other).readText())
        val next = SteamCloudSyncPlanner.buildUploadPlan(2, localEntries(), remote.manifest(), savedBaseline())
        assertEquals(other, next.remoteOnlyChanges.single().localRelativePath)
    }
    @Test fun forcedMirrorDeletesOnlyNonBlacklistedRemoteFiles() {
        val remote = FakeRemote(); remote.files[path] = "old"; remote.files["preferences/STSGameplaySettings"] = "pc-settings"
        freeze()
        val result = engine(remote, setOf("preferences/STSGameplaySettings")).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS)
        assertEquals(1, result.deleted); assertFalse(remote.files.containsKey(path)); assertEquals("pc-settings", remote.files["preferences/STSGameplaySettings"])
    }
    @Test fun remoteDeletionRemovesLocalFileAndBaselineEntry() {
        val remote = FakeRemote(); put(live, path, "old"); remote.files[path] = "old"
        val old = baseline(remote); remote.files.remove(path); freeze()
        engine(remote).run(frozen, old, SteamCloudSyncMode.MERGE)
        assertFalse(File(live, path).exists()); assertTrue(savedBaseline().localEntries.isEmpty())
    }
    @Test fun conflictsProduceNoRemoteWritesOrLocalReplacements() {
        val remote = FakeRemote(); put(live, path, "a"); remote.files[path] = "b"; freeze()
        assertEquals(1, engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE).plan.conflicts.size)
        assertEquals(0, remote.beginCount); assertEquals("a", File(live, path).readText())
        assertFalse(File(state, "sync-baseline.json").exists())
        val cached = Json.decodeFromString<SteamCloudManifestSnapshot>(File(state, "manifest.json").readText())
        assertEquals(remote.manifest().entries, cached.entries)
    }
    @Test fun changedPreviewIsRejected() {
        val remote = FakeRemote(); put(live, path, "a"); freeze()
        val preview = engine(remote).inspect(localEntries(), null)
        assertTrue(File(state, "manifest.json").isFile)
        remote.files[path] = "other"
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.UPLOAD_ONLY, preview); fail() } catch (_: SteamCloudStalePlanException) { }
        assertEquals(0, remote.beginCount)
    }
    @Test fun cancellationBeforeWritesLeavesBothSidesUntouched() {
        val remote = FakeRemote(); put(live, path, "a"); freeze()
        try { engine(remote, continueSync = { false }).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS); fail() }
        catch (_: CancellationException) { }
        assertEquals(0, remote.beginCount); assertTrue(remote.files.isEmpty())
    }
    @Test fun confirmedMergePreviewCanResolveWithLocalWinner() {
        val remote = FakeRemote(); put(live, path, "local"); remote.files[path] = "cloud"; freeze()
        val preview = engine(remote).inspect(localEntries(), null)
        assertEquals(1, preview.conflicts.size)
        val result = engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS, preview)
        assertEquals(1, result.uploaded); assertEquals("local", remote.files[path])
    }
    @Test fun confirmedMergePreviewCanResolveWithCloudWinner() {
        val remote = FakeRemote(); put(live, path, "local"); remote.files[path] = "cloud"; freeze()
        val preview = engine(remote).inspect(localEntries(), null)
        assertEquals(1, engine(remote).run(frozen, null, SteamCloudSyncMode.CLOUD_WINS, preview).downloaded)
        assertEquals("cloud", File(live, path).readText())
    }
    @Test fun changedUnconflictedLocalFileInvalidatesConfirmation() {
        val remote = FakeRemote(); val other = "preferences/STSPlayer"
        put(live, path, "local"); remote.files[path] = "cloud"
        put(live, other, "same"); remote.files[other] = "same"
        val preview = engine(remote).inspect(localEntries(), null)
        put(live, other, "changed-after-dialog"); freeze()
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS, preview); fail() }
        catch (_: SteamCloudStalePlanException) { }
        assertEquals(0, remote.beginCount); assertEquals("same", remote.files[other])
    }
    @Test fun changedCloudPreviewCannotBeUsedForForcedOverwrite() {
        val remote = FakeRemote(); put(live, path, "local"); remote.files[path] = "cloud"; freeze()
        val preview = engine(remote).inspect(localEntries(), null)
        remote.files[path] = "another-device"
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.CLOUD_WINS, preview); fail() }
        catch (_: SteamCloudStalePlanException) { }
        assertEquals("local", File(live, path).readText()); assertEquals(0, remote.beginCount)
    }
    @Test fun progressIncludesVerificationAndLocalInstallationAfterFileTransfers() {
        val remote = FakeRemote(); val other = "preferences/STSPlayer"
        put(live, path, "old"); put(live, other, "old"); remote.files[path] = "old"; remote.files[other] = "old"
        val old = baseline(remote); put(live, path, "new"); remote.files[other] = "new"; freeze()
        val phases = mutableListOf<SteamCloudSyncPhase>()
        SteamCloudSyncEngine(root, live, state, remote, emptySet(), progress = { phases += it.phase })
            .run(frozen, old, SteamCloudSyncMode.MERGE)
        assertTrue(phases.indexOf(SteamCloudSyncPhase.DOWNLOADING) < phases.indexOf(SteamCloudSyncPhase.UPLOADING))
        assertTrue(phases.indexOf(SteamCloudSyncPhase.UPLOADING) < phases.indexOf(SteamCloudSyncPhase.VERIFYING_REMOTE))
        assertEquals(SteamCloudSyncPhase.APPLYING_TO_LOCAL, phases.last())
    }
    @Test fun uploadFailureLeavesJournalAndDoesNotReplay() {
        val remote = FakeRemote(); put(live, path, "a"); freeze(); remote.failUpload = true
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS); fail() } catch (_: IOException) { }
        assertEquals(1, remote.abortCount); assertTrue(File(state, "remote-pending-v2.json").exists())
        remote.failUpload = false
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE); fail() } catch (_: SteamCloudPushReconciliationException) { }
        assertEquals(1, remote.beginCount)
    }

    private class FakeRemote : SteamCloudSyncTransport {
        val files = linkedMapOf<String, String>()
        private val uploads = linkedMapOf<String, String>()
        private val deletes = mutableSetOf<String>()
        var beginCount = 0; var abortCount = 0; var manifestCount = 0; var downloadCount = 0
        var dropCommit = false; var throwAfterCommit = false; var failManifestAfterCommit = false
        var corruptDownloads = false; var failUpload = false; private var committed = false
        var beforeManifest: (Int) -> Unit = {}; var afterCommit: () -> Unit = {}
        var afterDownload: () -> Unit = {}
        var steamId64 = "1"
        override fun manifest(): SteamCloudManifestSnapshot {
            beforeManifest(++manifestCount)
            if (committed && failManifestAfterCommit) throw IOException("manifest connection lost")
            val entries = files.map { (path, content) ->
                val bytes = content.toByteArray()
                val hash = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                SteamCloudManifestEntry(requireNotNull(SteamCloudPathMapper.buildRemotePath(path)), path,
                    requireNotNull(SteamCloudPathMapper.mapLocalRelativePath(path)).rootKind, bytes.size.toLong(), 1, "pc", "Persisted", hash)
            }
            return SteamCloudManifestSnapshot(1, entries.size, 0, 0, entries, emptyList(), steamId64 = steamId64)
        }
        override fun download(entry: SteamCloudManifestEntry, target: File) {
            downloadCount++
            target.parentFile.mkdirs(); target.writeText(if (corruptDownloads) "bad" else files.getValue(entry.localRelativePath))
            afterDownload()
        }
        override fun begin(uploads: List<String>, deletes: List<String>): Long {
            beginCount++; this.uploads.clear(); this.deletes.clear(); return 1
        }
        override fun upload(candidate: SteamCloudUploadCandidate, source: File, batch: Long) {
            if (failUpload) throw IOException("upload failed")
            uploads[candidate.localRelativePath] = source.readText()
        }
        override fun delete(path: String, batch: Long) { deletes += requireNotNull(SteamCloudPathMapper.mapRemotePath(path)).localRelativePath }
        override fun complete(batch: Long) {
            if (!dropCommit) { files.putAll(uploads); deletes.forEach(files::remove) }
            committed = true; afterCommit()
            if (throwAfterCommit) throw IOException("response lost after commit")
        }
        override fun abort(batch: Long) { abortCount++ }
        override fun close() { }
    }
}

package io.stamethyst.backend.steamcloud

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import io.stamethyst.ui.main.SteamCloudOperationEventGate
import io.stamethyst.ui.main.shouldRequestSteamCloudCheck
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
    private fun store(steamId: String = "1") = SteamCloudRemoteCommitStore(state, steamId)
    private val path = "saves/IRONCLAD.autosave"
    private fun put(root: File, path: String, text: String) = File(root, path).also { it.parentFile.mkdirs(); it.writeText(text) }
    private fun freeze() { live.mkdirs(); SteamCloudFileTransaction.copyPath(live, frozen) }
    private fun localEntries() = SteamCloudLocalSnapshotCollector.collect(live)
    private fun baseline(remote: FakeRemote) = SteamCloudSyncBaseline(1, localEntries(), remote.manifest().entries, "1")
    private fun savedBaseline() = Json.decodeFromString<SteamCloudSyncBaseline>(File(state, "sync-baseline.json").readText())
    private fun engine(remote: FakeRemote, blacklist: Set<String> = emptySet(),
        continueSync: () -> Boolean = { true }, mutate: ((() -> Unit) -> Unit) = { it() }) =
        SteamCloudSyncEngine(root, live, state, remote, store(remote.steamId64), blacklist, continueSync, mutate = mutate)

    @Test fun steadyMergeReadsOneFreshListingAndMakesNoCommit() {
        val remote = FakeRemote(); put(live, path, "same"); remote.files[path] = "same"; freeze()
        engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE)
        val before = remote.manifestCount
        val checked = SteamCloudSyncEngine(root, live, state, remote, store(), emptySet(), transact = {
            fail("An established agreement needs no commit")
        })
        checked.run(frozen, savedBaseline(), SteamCloudSyncMode.MERGE)
        assertEquals(1, remote.manifestCount - before)
        // A listing is evidence for this pass alone.
        remote.files[path] = "next device"
        assertEquals(1, engine(remote).run(frozen, savedBaseline(), SteamCloudSyncMode.MERGE).downloaded)
    }

    @Test fun steadyMergeRepairsPersistedManifestCountsEvenWhenEntriesAgree() {
        val remote = FakeRemote(); put(live, path, "same"); remote.files[path] = "same"; freeze()
        engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE)
        val file = File(state, "manifest.json")
        for ((key, expected) in mapOf("fileCount" to 1, "preferencesCount" to 0, "savesCount" to 1)) {
            val field = Regex("(\"$key\"\\s*:\\s*)\\d+")
            val corrupt = field.replace(file.readText()) { "${it.groupValues[1]}999" }
            assertNotEquals(file.readText(), corrupt)
            file.writeText(corrupt)
            engine(remote).run(frozen, savedBaseline(), SteamCloudSyncMode.MERGE)
            assertEquals(expected.toString(), field.find(file.readText())!!.value.substringAfter(':').trim())
        }
    }

    @Test fun returnDuringUnchangedListingStillGetsANewFreezeAndMerge() {
        val remote = FakeRemote(); put(live, path, "before"); remote.files[path] = "before"; freeze()
        engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE)
        val batch = SteamCloudReturnBatch("1", null)
        val reads = remote.manifestCount
        val first = engine(remote).run(frozen, savedBaseline(), SteamCloudSyncMode.MERGE, onPlan = {
            batch.attach(123); put(live, path, "at exit")
        })
        assertEquals(1, remote.manifestCount - reads)
        batch.finish(SteamCloudSyncProcessService.RESULT_PLAN_READY, first.plan, false, mergeAgain = {
            assertEquals(123L, it.returnedLaunch); freeze()
            assertEquals(1, engine(remote).run(frozen, savedBaseline(), it.mode).uploaded)
        }, publish = { fail("must consume return before publishing up to date") })
        assertEquals("at exit", remote.files[path])
    }

    @Test fun steadyMergeHonorsCancellationAfterCheckingMetadata() {
        val remote = FakeRemote(); freeze()
        engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE)
        var active = true
        val reads = remote.manifestCount
        assertThrows(CancellationException::class.java) {
            engine(remote, continueSync = { active }).run(frozen, savedBaseline(), SteamCloudSyncMode.MERGE,
                metadata = { _, _ -> throw AssertionError("committed") },
                profileIsCurrent = { active = false; true })
        }
        assertEquals(1, remote.manifestCount - reads)
    }

    @Test fun traceReplacesLastPassOnErrorAndNeverExportsFailureOrSaveText() {
        val remote = FakeRemote(); put(live, path, "private-save-body"); freeze()
        val traceFile = File(state, SteamCloudSyncTrace.FILE_NAME)
        val first = SteamCloudSyncTrace()
        val previous = first.record(state, SteamCloudSyncMode.MERGE, "1") {
            SteamCloudSyncEngine(root, live, state, remote, store(), emptySet(), trace = first)
                .run(frozen, null, SteamCloudSyncMode.MERGE)
        }
        assertTrue(traceFile.readText().contains("outcome=COMPLETED"))
        put(live, path, "next"); freeze()
        remote.onBegin = {
            val current = traceFile.readText()
            assertTrue(current.contains("outcome=RUNNING")); assertFalse(current.contains(first.operation.toString()))
            throw IOException("user credentials and /private/path")
        }
        val second = SteamCloudSyncTrace.service("untrusted-id/with-credentials", SteamCloudServicePass("secret-account",
            returnedLaunch = 42))
        val freezeId = java.util.UUID.randomUUID()
        assertThrows(IOException::class.java) {
            second.record(state, SteamCloudSyncMode.MERGE, "1") {
                second.frozen(freezeId)
                SteamCloudSyncEngine(root, live, state, remote, store(), emptySet(), trace = second)
                    .run(frozen, savedBaseline(), SteamCloudSyncMode.MERGE)
            }
        }
        val text = traceFile.readText()
        for (expected in listOf("outcome=FAILED_UNKNOWN", "enteredWrite=true", "pendingNow=true", "pendingBefore=false",
                "source=GAME_RETURN", "freezeId=$freezeId", "returnedLaunch=42", "manifestCalls=2", "localFiles=1",
                "remoteFiles=1", "phaseMs.WRITE=", "durationMs=")) assertTrue(expected, text.contains(expected))
        for (secret in listOf("credentials", "secret-account", "/private/path", path, "private-save-body", "COMPLETED"))
            assertFalse(secret, text.contains(secret))
        // Diagnostics directory unavailable: don't fail a good result or replace the real error.
        val unusable = File(live, path)
        val failed = IllegalArgumentException("original")
        assertSame(failed, assertThrows(IllegalArgumentException::class.java) {
            SteamCloudSyncTrace().record(unusable, SteamCloudSyncMode.MERGE, null) { throw failed }
        })
        assertSame(previous, SteamCloudSyncTrace().record(unusable, SteamCloudSyncMode.MERGE, null) { previous })
    }
    @Test fun ordinaryUploadVerifiesAndAdvancesBaseline() {
        val remote = FakeRemote(); put(live, path, "old"); remote.files[path] = "old"
        val old = baseline(remote); put(live, path, "new"); freeze()
        val result = engine(remote).run(frozen, old, SteamCloudSyncMode.MERGE)
        assertEquals(1, result.uploaded); assertEquals("new", remote.files[path])
        assertEquals(localEntries(), savedBaseline().localEntries); assertFalse(store().hasPending())
    }
    @Test fun forcedUploadUsesSamePostCommitVerification() {
        val remote = FakeRemote(); put(live, path, "new"); remote.files[path] = "old"; remote.dropCommit = true; freeze()
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS); fail() }
        catch (_: SteamCloudPushReconciliationException) { }
        assertTrue(store().hasPending()); assertFalse(File(state, "sync-baseline.json").exists())
    }
    @Test fun deniedFinalizerPreservesTypedFailureAndOnlyItsOwnersJournal() {
        val remote = FakeRemote(); put(live, path, "progress"); freeze()
        val denial = top.apricityx.workshop.steam.protocol.SteamServiceMethodException(
            "Cloud.CompleteAppUploadBatchBlocking#1", 15, "AccessDenied")
        remote.dropCommit = true; remote.afterCommit = { throw denial }
        val result = runCatching { engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE) }
        assertSame(denial, result.exceptionOrNull())
        val service = SteamCloudSyncProcessService
        val (code, category) = service.failureEvent(denial, SteamCloudSyncMode.MERGE, false)
        assertEquals(service.RESULT_FAILURE, code)
        assertEquals(SteamCloudFailureCategory.PERMISSION_DENIED, category)
        assertEquals(io.stamethyst.ui.main.MainScreenViewModel.SteamCloudIndicatorState.CONNECTION_FAILED,
            io.stamethyst.ui.main.steamCloudFailureState(false, service.requiresResolution(denial)))
        assertEquals(2, remote.manifestCount)
        assertTrue(store("1").hasPending()); assertFalse(store("2").hasPending())
        assertFalse(File(state, "sync-baseline.json").exists())
        // A fresh inspection may prove the content, not the fate of the finalizer. If still
        // unmatched, never promote the journal's expected bytes into a verified baseline.
        val uncertain = assertThrows(SteamCloudPushReconciliationException::class.java) {
            engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE)
        }
        assertTrue(service.requiresResolution(uncertain))
        assertEquals(1, remote.beginCount)
        assertFalse(File(state, "sync-baseline.json").exists())
    }

    @Test fun denialIsNeverReportedAsSuccessButNextPassCanVerifyActualContent() {
        val remote = FakeRemote(); put(live, path, "progress"); freeze()
        val denial = top.apricityx.workshop.steam.protocol.SteamServiceMethodException(
            "Cloud.CompleteAppUploadBatchBlocking#1", 15, null)
        remote.afterCommit = { throw denial } // Current content matches despite finalizer refusal.
        assertSame(denial, runCatching { engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE) }.exceptionOrNull())
        assertTrue(store().hasPending()); assertFalse(File(state, "sync-baseline.json").exists())
        assertEquals(0, engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE).uploaded)
        assertEquals(1, remote.beginCount); assertFalse(store().hasPending())
        assertEquals(localEntries(), savedBaseline().localEntries)
    }

    @Test fun ordinaryFilenameThatLooksLikeAnAuthResultCannotBecomeALoginRejection() {
        val remote = FakeRemote()
        put(live, "preferences/InvalidPassword", "local")
        remote.files["preferences/invalidpassword"] = "remote"
        freeze()
        val error = runCatching { engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE) }.exceptionOrNull()!!
        assertTrue(error.message.orEmpty().contains("InvalidPassword"))
        assertNotEquals(SteamCloudFailureCategory.AUTH_REJECTED, SteamCloudFailureClassifier.classify(error))
        assertEquals(0, remote.beginCount); assertFalse(store().hasPending())
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
        assertEquals("new", remote.files[path]); assertFalse(store().hasPending())
    }
    @Test fun completionThrottleKeepsPreviousBaselineAndSlot() {
        val remote = FakeRemote(); put(live, path, "before"); freeze()
        val slot = File(root, "profile/$path")
        val metadata = { _: File, projected: File -> listOf(SteamCloudPathReplacement(File(projected, path), slot)) }
        engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE, metadata = metadata)
        val before = File(state, "sync-baseline.json").readText()
        put(live, path, "current"); freeze()
        val error = top.apricityx.workshop.steam.protocol.SteamServiceMethodException("Cloud.CompleteAppUploadBatchBlocking#1", 84, null)
        remote.dropCommit = true
        remote.afterCommit = { throw error }
        val reads = remote.manifestCount
        val failure = runCatching { engine(remote).run(frozen, savedBaseline(), SteamCloudSyncMode.MERGE, metadata = metadata) }.exceptionOrNull()
        assertSame(error, failure)
        assertEquals(2, remote.manifestCount - reads) // No verification request into the throttle.
        assertEquals(before, File(state, "sync-baseline.json").readText())
        assertEquals("before", slot.readText())
        assertEquals("current", File(live, path).readText())
        assertTrue(store().hasPending())
        assertEquals(0, remote.abortCount)
    }
    @Test fun interruptedVerifiedCommitRecoversWithoutUploadReplay() {
        val remote = FakeRemote(); put(live, path, "new"); freeze()
        remote.failManifestAfterCommit = true
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS); fail() } catch (_: IOException) { }
        assertTrue(store().hasPending())
        remote.failManifestAfterCommit = false
        val result = engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE)
        assertEquals(0, result.uploaded); assertEquals(1, remote.beginCount); assertEquals("new", remote.files[path])
    }
    @Test fun verifiedJournalCannotTakeSteadyShortcutEvenWithUnchangedDiskMetadata() {
        val remote = FakeRemote(); put(live, path, "same"); remote.files[path] = "same"; freeze()
        engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE)
        val old = savedBaseline()
        store().prepareBeforeBegin(
            SteamCloudRemoteCommitStore.Pending(steamId64 = "1", localEntries = old.localEntries, priorBaseline = old,
                expectedRemoteEntries = old.remoteEntries, deletedPaths = emptySet()), null, {})
        val before = remote.manifestCount
        var installed = false
        engine(remote).run(frozen, old, SteamCloudSyncMode.MERGE,
            metadata = { _, projected -> installed = File(projected, path).readText() == "same"; emptyList() },
            profileIsCurrent = { throw AssertionError("cannot bypass recovery") })
        assertTrue(installed); assertEquals(3, remote.manifestCount - before); assertEquals(0, remote.beginCount)
        assertFalse(store().hasPending())
    }
    @Test fun failedVerifiedRecoveryKeepsJournalAndDoesNotReportCancellation() {
        val remote = FakeRemote(); put(live, path, "new"); freeze()
        remote.failManifestAfterCommit = true
        assertThrows(IOException::class.java) { engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE) }
        remote.failManifestAfterCommit = false
        val original = store().file.readText()
        val obstacle = File(state, "sync-baseline.json")
        assertTrue(obstacle.mkdir()) // Can't atomically replace a directory with baseline JSON.
        val error = assertThrows(SteamCloudJournalPersistenceException::class.java) {
            engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE)
        }
        assertEquals(SteamCloudSyncProcessService.RESULT_FAILURE to SteamCloudFailureCategory.JOURNAL_PERSISTENCE,
            SteamCloudSyncProcessService.failureEvent(error, SteamCloudSyncMode.MERGE, cancelled = true))
        assertEquals(original, store().file.readText())
        assertTrue(obstacle.delete())
        engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE)
        assertEquals(1, remote.beginCount); assertFalse(store().hasPending())
    }
    @Test fun explicitResolutionArchivesUncertainRecord() {
        val remote = FakeRemote(); put(live, path, "new"); remote.files[path] = "old"; remote.dropCommit = true; freeze()
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS); fail() } catch (_: SteamCloudPushReconciliationException) { }
        remote.dropCommit = false
        engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS)
        assertTrue(state.listFiles()!!.any { it.name.startsWith("uncertain-remote-") }); assertEquals("new", remote.files[path])
    }
    @Test fun otherAccountCanPullButCannotResolveOriginalAccountsUnknownWrite() = switchAccountWithPending(legacy = false)
    @Test fun legacyOwnerStillHasToReconcileAfterOtherAccountPulled() = switchAccountWithPending(legacy = true)
    private fun switchAccountWithPending(legacy: Boolean) {
        val remote = FakeRemote(); put(live, path, "account-one"); remote.dropCommit = true; freeze()
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS); fail() }
        catch (_: SteamCloudPushReconciliationException) { }
        val original = if (legacy) File(state, SteamCloudRemoteCommitStore.LEGACY_FILE_NAME).also {
            assertTrue(store().file.renameTo(it))
        } else store().file
        val before = original.readText()
        fun pass(mode: SteamCloudSyncMode, prior: SteamCloudSyncBaseline? = null) =
            SteamCloudSyncTrace().record(state, mode, remote.steamId64) { engine(remote).run(frozen, prior, mode) }
        val trace = File(state, SteamCloudSyncTrace.FILE_NAME)
        remote.steamId64 = "2"; remote.dropCommit = false; remote.files[path] = "account-two"
        val b = store("2")
        assertThrows(CancellationException::class.java) {
            engine(remote, continueSync = { !b.file.exists() }).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS)
        }
        assertEquals(before, original.readText()); assertFalse(b.hasPending())
        pass(SteamCloudSyncMode.CLOUD_WINS)
        assertEquals("account-two", File(live, path).readText())
        assertEquals("2", savedBaseline().steamId64)
        assertTrue(trace.readText().contains("pendingBefore=false\npendingNow=false"))
        assertEquals(before, original.readText()); assertFalse(state.listFiles()!!.any { it.name.startsWith("uncertain-remote-") })
        freeze()
        val reads = remote.manifestCount
        pass(SteamCloudSyncMode.MERGE, savedBaseline())
        assertEquals(1, remote.manifestCount - reads)
        remote.steamId64 = "1"; remote.files.clear()
        assertThrows(SteamCloudPushReconciliationException::class.java) {
            pass(SteamCloudSyncMode.MERGE)
        }
        assertTrue(trace.readText().contains("pendingBefore=true\npendingNow=true"))
        assertEquals(before, store().file.readText())
        assertEquals(1, remote.beginCount); assertTrue(remote.files.isEmpty())
    }
    @Test fun failedCleanupAfterCancellationProducesServiceFailureAndRecoveryDiagnostic() {
        val remote = FakeRemote(); put(live, path, "new"); freeze()
        // Some fsync failures themselves carry a cancellation/interrupt cause.
        val store = SteamCloudRemoteCommitStore(state, "1") { throw IOException("fsync", InterruptedException()) }
        val trace = SteamCloudSyncTrace()
        val mode = SteamCloudSyncMode.MERGE
        val error = assertThrows(SteamCloudJournalPersistenceException::class.java) {
            trace.record(state, mode, "1") {
                SteamCloudSyncEngine(root, live, state, remote, store, emptySet(), shouldContinue = { !store.file.exists() },
                    trace = trace).run(frozen, null, mode)
            }
        }
        assertEquals(0, remote.beginCount)
        assertEquals(SteamCloudSyncProcessService.RESULT_FAILURE to SteamCloudFailureCategory.JOURNAL_PERSISTENCE,
            SteamCloudSyncProcessService.failureEvent(error, mode, cancelled = true))
        val summary = File(state, SteamCloudSyncTrace.FILE_NAME).readText()
        for (line in listOf("enteredWrite=false", "pendingBefore=false", "pendingNow=unknown", "outcome=FAILED_JOURNAL_PERSISTENCE")) {
            assertTrue(line, summary.contains(line)); assertTrue(SteamCloudSyncTrace.exportable(line, false))
        }
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
        assertTrue(store().hasPending()); assertFalse(File(state, "sync-baseline.json").exists())
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

    @Test fun busyLauncherDeliversExitWhileItsFrozenUploadIsActive() = returnDuringFrozenUpload(recreate = false)
    @Test fun recreatedLauncherCanAttachAndDeliverExitWhileOldWorkerOwnsTheUpload() = returnDuringFrozenUpload(recreate = true)

    // Exercise the launcher request gate, operation attachment and service terminal decision with
    // real frozen trees, baseline commits and an in-memory remote. No Binder/Steam dependency.
    private fun returnDuringFrozenUpload(recreate: Boolean) {
        val remote = FakeRemote()
        put(live, path, "at launch")
        freeze()
        val batch = SteamCloudReturnBatch("1", initialReturn = null)
        var launcher = SteamCloudOperationEventGate().apply { begin("upload"); check(accept("upload", 1)) }
        val old = engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE, onPlan = {
            put(live, path, "at exit")
            if (recreate) {
                launcher = SteamCloudOperationEventGate()
                assertTrue(launcher.attach("upload")) // state query after Activity recreation
                assertTrue(launcher.accept("upload", 2))
            }
            assertFalse(shouldRequestSteamCloudCheck(null, busy = true, inFlight = true))
            assertTrue(shouldRequestSteamCloudCheck(100, busy = true, inFlight = true))
            launcher.begin("return-request")
            assertFalse(launcher.accept("upload", 3)) // stale event in transit, before service ACK
            batch.attach(100)
            assertTrue(launcher.attach("upload", "return-request"))
            assertTrue(launcher.accept("upload", 3))
        })
        assertEquals("at launch", remote.files[path])
        val terminals = mutableListOf<Int>()
        val completed = SteamCloudSyncProcessService.RESULT_AUTO_SYNC_COMPLETED
        batch.attach(null) // ordinary onResume
        batch.attach(100)  // duplicate before and during follow-up
        batch.finish(completed, old.plan, false, mergeAgain = { pass ->
            assertTrue(terminals.isEmpty())
            assertEquals("1", pass.steamId)
            assertNull(pass.expectedPlan)
            freeze()
            val fresh = engine(remote).run(frozen, savedBaseline(), pass.mode, onPlan = { batch.attach(100) })
            batch.finish(completed, fresh.plan, false, mergeAgain = { fail("replayed return") }, publish = terminals::add)
        }, publish = terminals::add)
        assertEquals("at exit", remote.files[path])
        assertEquals(2, remote.beginCount)
        assertTrue(launcher.accept("upload", 4))
        assertEquals(listOf(completed), terminals)
    }

    @Test fun exitAfterAnOverrideUsesFreshMergeAndPreservesNewConflict() {
        val remote = FakeRemote()
        remote.files[path] = "cloud before override"
        put(live, path, "confirmed local")
        freeze()
        val batch = SteamCloudReturnBatch("1", null)
        val old = engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS, onPlan = {
            put(live, path, "game exit")
            batch.attach(1)
        })
        remote.files[path] = "other device"
        batch.finish(SteamCloudSyncProcessService.RESULT_LOCAL_OVERRIDE_COMPLETED, old.plan, false, mergeAgain = { pass ->
            freeze()
            val fresh = engine(remote).run(frozen, savedBaseline(), pass.mode, pass.expectedPlan)
            assertEquals(1, fresh.plan.conflicts.size)
            batch.attach(2)
            val code = SteamCloudSyncProcessService.RESULT_PLAN_READY
            batch.finish(code, fresh.plan, false, mergeAgain = { fail("retried conflict") }, publish = { assertEquals(code, it) })
        }, publish = { fail("published stale success") })
        assertEquals(1, remote.beginCount)
        assertEquals("other device", remote.files[path])
        assertEquals("game exit", File(live, path).readText())
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
        assertFalse(store().hasPending())
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
        assertFalse(store().hasPending())
    }
    @Test fun launchRequestAtLocalCommitStopsCloudDeletionEvenBeforeGameLeaseIsAcquired() {
        val remote = FakeRemote(); put(live, path, "old"); remote.files[path] = "old"
        val old = baseline(remote); remote.files.remove(path); freeze()
        var launchRequested = false
        val guarded = SteamCloudSyncEngine(root, live, state, remote, store(), emptySet(), transact = { commit ->
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
        val guarded = SteamCloudSyncEngine(root, live, state, remote, store(), emptySet(), progress = { progress ->
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
        assertTrue(store().hasPending())
    }
    @Test fun downloadedFilesAndModeMetadataRollbackTogether() {
        val remote = FakeRemote(); put(live, path, "old"); remote.files[path] = "new"; freeze()
        val mode = put(root, "mode", "independent")
        val brokenEngine = SteamCloudSyncEngine(root, live, state, remote, store(), emptySet(), transact = {
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
        val preview = engine(remote).inspect(live, null)
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
    @Test fun interruptedAfterJournalBeforeBeginAllowsNextAutomaticUpload() {
        val remote = FakeRemote(); put(live, path, "new"); freeze()
        val journal = store().file
        try {
            assertThrows(CancellationException::class.java) {
                engine(remote, continueSync = {
                    if (journal.isFile) Thread.currentThread().interrupt()
                    true
                }).run(frozen, null, SteamCloudSyncMode.MERGE)
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertEquals(0, remote.beginCount); assertFalse(journal.exists()); assertTrue(remote.files.isEmpty())
        assertFalse(File(state, "sync-baseline.json").exists())
        assertEquals(1, engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE).uploaded)
        assertEquals("new", remote.files[path])
    }
    @Test fun newEngineCannotAssumeACrashedProcessStoppedBeforeBegin() {
        val remote = FakeRemote(); put(live, path, "new"); freeze()
        val journal = store().file
        val crash = Error("process stopped with durable intent")
        assertSame(crash, assertThrows(Error::class.java) {
            engine(remote, continueSync = { if (journal.isFile) throw crash else true })
                .run(frozen, null, SteamCloudSyncMode.MERGE)
        })
        val uncertain = journal.readText()
        assertThrows(SteamCloudPushReconciliationException::class.java) {
            engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE)
        }
        assertEquals(0, remote.beginCount); assertEquals(uncertain, journal.readText())
    }
    @Test fun cancellationInsideBeginWithoutBatchIdProtectsJournalEvenIfNextOverrideIsCancelled() {
        val remote = FakeRemote(); put(live, path, "new"); freeze()
        val journal = store().file
        remote.onBegin = { throw CancellationException("received begin but lost reply") }
        assertThrows(CancellationException::class.java) { engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE) }
        val uncertain = journal.readText()
        remote.onBegin = {}
        assertThrows(CancellationException::class.java) {
            engine(remote, continueSync = { !journal.exists() || journal.readText() == uncertain })
                .run(frozen, null, SteamCloudSyncMode.LOCAL_WINS)
        }
        assertEquals(uncertain, journal.readText())
        assertThrows(SteamCloudPushReconciliationException::class.java) {
            engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE)
        }
        assertEquals(1, remote.beginCount); assertEquals(0, remote.abortCount)
        assertEquals(1, engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS).uploaded)
        assertFalse(journal.exists()); assertEquals("new", remote.files[path])
    }
    @Test fun ambiguousNamesStopPreviewAndEverySyncBeforeTransfersOrInstallation() {
        val remote = FakeRemote()
        val localName = "preferences/MyMod"; val cloudName = "preferences/mymod"
        put(live, localName, "local"); remote.files[cloudName] = "cloud"; freeze()
        val guarded = engine(remote, mutate = { fail("must not install ambiguous paths") })
        assertThrows(SteamCloudIncompleteManifestException::class.java) { guarded.inspect(frozen, null) }
        for (mode in SteamCloudSyncMode.entries) {
            assertThrows(SteamCloudIncompleteManifestException::class.java) { guarded.run(frozen, null, mode) }
        }
        assertEquals(0, remote.beginCount); assertEquals(0, remote.downloadCount)
        assertEquals(mapOf(cloudName to "cloud"), remote.files); assertEquals("local", File(live, localName).readText())
        assertFalse(File(state, "sync-baseline.json").exists()); assertFalse(store().hasPending())
    }
    @Test fun emptyDirectoryAndProtectedFileAliasesCannotBeInstalledOver() {
        val remote = FakeRemote()
        val empty = File(live, "preferences/EmptyMod").also { assertTrue(it.mkdirs()) }
        val excluded = "saves/MyMod"
        put(live, excluded, "private"); put(live, path, "upload"); freeze()
        val guarded = engine(remote, setOf(excluded))
        for (cloudName in listOf("preferences/emptymod/nested", "preferences/EmptyMod", "saves/mymod")) {
            remote.files.clear(); remote.files[cloudName] = "cloud"
            assertThrows(SteamCloudIncompleteManifestException::class.java) { guarded.inspect(frozen, null) }
            assertThrows(SteamCloudIncompleteManifestException::class.java) {
                guarded.run(frozen, null, SteamCloudSyncMode.MERGE)
            }
        }
        assertTrue(empty.isDirectory); assertEquals("private", File(live, excluded).readText())
        assertEquals(0, remote.downloadCount); assertEquals(0, remote.beginCount)
    }
    @Test fun directoryIntroducedDuringDownloadIsRecheckedBeforeInstall() {
        val remote = FakeRemote(); remote.files[path] = "cloud"; freeze()
        val target = File(live, path)
        remote.afterDownload = { assertTrue(target.mkdirs()) }
        assertThrows(SteamCloudIncompleteManifestException::class.java) {
            engine(remote).run(frozen, null, SteamCloudSyncMode.CLOUD_WINS)
        }
        assertTrue(target.isDirectory); assertFalse(File(state, "sync-baseline.json").exists())
    }
    @Test fun failedExplicitCloudInstallDoesNotReleaseOriginalUncertainty() {
        val remote = FakeRemote(); put(live, path, "new"); remote.files[path] = "old"; freeze()
        remote.onBegin = { throw IOException("response lost") }
        assertThrows(IOException::class.java) { engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS) }
        val journal = store().file
        val original = journal.readText()
        assertThrows(CancellationException::class.java) {
            engine(remote, mutate = { throw CancellationException("before local install") })
                .run(frozen, null, SteamCloudSyncMode.CLOUD_WINS)
        }
        assertEquals(original, journal.readText())
        assertThrows(SteamCloudPushReconciliationException::class.java) {
            engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE)
        }
        engine(remote).run(frozen, null, SteamCloudSyncMode.CLOUD_WINS)
        assertFalse(journal.exists()); assertEquals(1, remote.beginCount)
        assertEquals("old", File(live, path).readText())
    }
    @Test fun confirmedMergePreviewCanResolveWithLocalWinner() {
        val remote = FakeRemote(); put(live, path, "local"); remote.files[path] = "cloud"; freeze()
        val preview = engine(remote).inspect(live, null)
        assertEquals(1, preview.conflicts.size)
        val result = engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS, preview)
        assertEquals(1, result.uploaded); assertEquals("local", remote.files[path])
    }
    @Test fun confirmedMergePreviewCanResolveWithCloudWinner() {
        val remote = FakeRemote(); put(live, path, "local"); remote.files[path] = "cloud"; freeze()
        val preview = engine(remote).inspect(live, null)
        assertEquals(1, engine(remote).run(frozen, null, SteamCloudSyncMode.CLOUD_WINS, preview).downloaded)
        assertEquals("cloud", File(live, path).readText())
    }
    @Test fun changedUnconflictedLocalFileInvalidatesConfirmation() {
        val remote = FakeRemote(); val other = "preferences/STSPlayer"
        put(live, path, "local"); remote.files[path] = "cloud"
        put(live, other, "same"); remote.files[other] = "same"
        val preview = engine(remote).inspect(live, null)
        put(live, other, "changed-after-dialog"); freeze()
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS, preview); fail() }
        catch (_: SteamCloudStalePlanException) { }
        assertEquals(0, remote.beginCount); assertEquals("same", remote.files[other])
    }
    @Test fun changedCloudPreviewCannotBeUsedForForcedOverwrite() {
        val remote = FakeRemote(); put(live, path, "local"); remote.files[path] = "cloud"; freeze()
        val preview = engine(remote).inspect(live, null)
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
        SteamCloudSyncEngine(root, live, state, remote, store(), emptySet(), progress = { phases += it.phase })
            .run(frozen, old, SteamCloudSyncMode.MERGE)
        assertTrue(phases.indexOf(SteamCloudSyncPhase.DOWNLOADING) < phases.indexOf(SteamCloudSyncPhase.UPLOADING))
        assertTrue(phases.indexOf(SteamCloudSyncPhase.UPLOADING) < phases.indexOf(SteamCloudSyncPhase.VERIFYING_REMOTE))
        val finalStages = listOf(
            SteamCloudSyncPhase.PREPARING_LOCAL_COMMIT,
            SteamCloudSyncPhase.RECHECKING_REMOTE,
            SteamCloudSyncPhase.WAITING_FOR_LOCAL_ACCESS,
            SteamCloudSyncPhase.RECHECKING_LOCAL,
            SteamCloudSyncPhase.SAVING_SYNC_STATE,
            SteamCloudSyncPhase.APPLYING_TO_LOCAL,
            SteamCloudSyncPhase.CLEANING_UP,
        )
        assertEquals(finalStages, phases.takeLast(finalStages.size))
    }
    @Test fun uploadOnlyReportsNetworkRecheckBeforeLocalCommitWithoutWaitingForSaveLease() {
        val remote = FakeRemote(); put(live, path, "new"); freeze()
        val phases = mutableListOf<SteamCloudSyncPhase>()
        var checkedFinalRemote = false
        remote.beforeManifest = {
            if (phases.lastOrNull() == SteamCloudSyncPhase.RECHECKING_REMOTE) checkedFinalRemote = true
        }
        SteamCloudSyncEngine(root, live, state, remote, store(), emptySet(), progress = { phases += it.phase },
            mutate = { fail("Upload-only sync must not acquire the live save lease") })
            .run(frozen, null, SteamCloudSyncMode.LOCAL_WINS)
        assertTrue(checkedFinalRemote)
        assertFalse(phases.contains(SteamCloudSyncPhase.WAITING_FOR_LOCAL_ACCESS))
        assertFalse(phases.contains(SteamCloudSyncPhase.APPLYING_TO_LOCAL))
        assertEquals(SteamCloudSyncPhase.CLEANING_UP, phases.last())
    }
    @Test fun uploadFailureLeavesJournalAndDoesNotReplay() {
        val remote = FakeRemote(); put(live, path, "a"); freeze(); remote.failUpload = true
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.LOCAL_WINS); fail() } catch (_: IOException) { }
        // A file RPC may have been accepted; do not follow a lost reply with a failure finalizer.
        assertEquals(0, remote.abortCount); assertTrue(store().hasPending())
        remote.failUpload = false
        try { engine(remote).run(frozen, null, SteamCloudSyncMode.MERGE); fail() } catch (_: SteamCloudPushReconciliationException) { }
        assertEquals(1, remote.beginCount)
    }

    internal class FakeRemote : SteamCloudSyncTransport {
        val files = linkedMapOf<String, String>()
        private val uploads = linkedMapOf<String, String>()
        private val deletes = mutableSetOf<String>()
        var beginCount = 0; var abortCount = 0; var manifestCount = 0; var downloadCount = 0
        var dropCommit = false; var throwAfterCommit = false; var failManifestAfterCommit = false
        var corruptDownloads = false; var failUpload = false; private var committed = false
        var beforeManifest: (Int) -> Unit = {}; var afterCommit: () -> Unit = {}
        var afterDownload: () -> Unit = {}
        var onBegin: () -> Unit = {}
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
            beginCount++; onBegin(); this.uploads.clear(); this.deletes.clear(); return 1
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

package io.stamethyst.backend.steamcloud

import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/** One algorithm for automatic merge, upload-only, and either explicit conflict resolution. */
internal class SteamCloudSyncEngine(
    private val storageRoot: File,
    private val liveRoot: File,
    private val stateRoot: File,
    private val transport: SteamCloudSyncTransport,
    private val pendingStore: SteamCloudRemoteCommitStore,
    private val blacklist: Set<String>,
    private val shouldContinue: () -> Boolean = { true },
    private val progress: (SteamCloudSyncProgress) -> Unit = {},
    private val mutate: ((() -> Unit) -> Unit) = { it() },
    private val transact: ((() -> Unit) -> Unit) = { it() },
    private val trace: SteamCloudSyncTrace? = null,
) {
    data class Result(val plan: SteamCloudUploadPlan, val uploaded: Int, val deleted: Int, val downloaded: Int)
    private val json = Json { prettyPrint = true }

    fun inspect(frozenRoot: File, baseline: SteamCloudSyncBaseline?): SteamCloudUploadPlan {
        checkCancelled()
        val directories = hashSetOf<String>()
        val local = collect(frozenRoot, directories)
        val remote = readRemote()
        val plan = preparePlan(local, remote, baseline, SteamCloudSyncMode.MERGE, directories).plan
        cacheManifest(remote)
        return plan
    }

    fun run(
        frozenRoot: File,
        baseline: SteamCloudSyncBaseline?,
        mode: SteamCloudSyncMode,
        expectedPlan: SteamCloudUploadPlan? = null,
        metadata: ((File, File) -> List<SteamCloudPathReplacement>)? = null,
        metadataRequiresLiveLease: Boolean = false,
        requiresLocalInstall: Boolean = false,
        onPlan: (SteamCloudUploadPlan) -> Unit = {},
        shouldDeferLocalChanges: () -> Boolean = { false },
        profileIsCurrent: ((List<SteamCloudLocalFileSnapshotEntry>) -> Boolean)? = null,
    ): Result {
        checkCancelled()
        trace?.phase(SteamCloudSyncTrace.Phase.SNAPSHOT)
        val directories = hashSetOf<String>()
        val local = collect(frozenRoot, directories)
        trace?.localFiles = local.size
        val originalLive = if (requiresLocalInstall) collect(liveRoot) else local
        val remote = readRemote()
        trace?.phase(SteamCloudSyncTrace.Phase.PLAN)
        // Confirm the inspected contents before recovery or any destructive operation.
        if (expectedPlan != null && (expectedPlan.plannedRemoteManifestIdentity != SteamCloudManifestIdentity.compute(remote) ||
                expectedPlan.plannedLocalIdentity != SteamCloudLocalSnapshotIdentity.compute(local))) {
            throw SteamCloudStalePlanException("Save contents changed since the synchronization preview; check again.")
        }
        val prepared = preparePlan(local, remote, SteamCloudSyncBlacklist.filterBaseline(baseline, blacklist), mode, directories)
        val (plan, prior, unresolved) = prepared
        onPlan(plan)
        if (plan.conflicts.isNotEmpty()) {
            cacheManifest(remote)
            return Result(plan, 0, 0, 0)
        }
        val downloads = if (mode == SteamCloudSyncMode.UPLOAD_ONLY) emptyList() else plan.remoteOnlyChanges
        fun requireLocalChangesAllowed() {
            if ((downloads.isNotEmpty() || metadataRequiresLiveLease || requiresLocalInstall) && shouldDeferLocalChanges()) {
                throw SteamCloudLiveSaveInUseException()
            }
        }
        requireLocalChangesAllowed()
        if (mode == SteamCloudSyncMode.MERGE && !prepared.hadPending && !requiresLocalInstall &&
            !metadataRequiresLiveLease && downloads.isEmpty() && plan.uploadCandidates.isEmpty() &&
            plan.remoteDeleteCandidates.isEmpty() && baseline != null) {
            trace?.phase(SteamCloudSyncTrace.Phase.CHECK_METADATA)
            // Compare the unfiltered disk baseline/cache and the actual slot, not a historical
            // "initialized" bit. This is evidence for this freeze and this listing alone.
            if (establishedAgreement(baseline, local, remote) &&
                (profileIsCurrent?.invoke(local) ?: (metadata == null))) {
                checkCancelled()
                return Result(plan, 0, 0, 0)
            }
        }
        val work = File(stateRoot, "work-v2/${UUID.randomUUID()}")
        check(work.mkdirs()) { "Cannot prepare sync workspace" }
        try {
            if (downloads.isNotEmpty()) report(SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                SteamCloudSyncPhase.DOWNLOADING, 0, downloads.size, "")
            downloads.forEachIndexed { index, change ->
                checkCancelled()
                report(SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL, SteamCloudSyncPhase.DOWNLOADING,
                    index, downloads.size, change.localRelativePath)
                change.currentRemote?.takeIf { it.isLive }?.let { entry ->
                    val target = File(work, "download/${change.localRelativePath}")
                    check(target.parentFile.mkdirs() || target.parentFile.isDirectory)
                    transport.download(entry, target)
                    // Do not rely on a particular transport to validate downloaded content.
                    val downloaded = SteamCloudLocalSnapshotCollector.collectFile(File(work, "download"), entry.localRelativePath)
                    check(SteamCloudSyncPlanner.matches(downloaded, entry)) { "Downloaded save failed verification" }
                }
                report(SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL, SteamCloudSyncPhase.DOWNLOADING,
                    index + 1, downloads.size, change.localRelativePath)
            }
            checkCancelled()
            // A launch request can arrive while downloading. Stop before starting a mixed remote
            // write batch; do not manufacture an uncertain cloud commit for deferred local work.
            requireLocalChangesAllowed()
            requireUnchanged(remote, readRemote())
            var finalRemote = remote
            val remoteWrites = plan.uploadCandidates.isNotEmpty() || plan.remoteDeleteCandidates.isNotEmpty()
            var journalToClear = unresolved
            unresolved?.let(pendingStore::preserveForExplicitResolution)
            if (remoteWrites) {
                trace?.phase(SteamCloudSyncTrace.Phase.PREPARE_WRITE)
                val expected = remote.entries.associateBy { it.localRelativePath }.toMutableMap()
                plan.remoteDeleteCandidates.forEach { expected.remove(it.localRelativePath) }
                plan.uploadCandidates.forEach { candidate ->
                    expected[candidate.localRelativePath] = SteamCloudManifestEntry(candidate.remotePath,
                        candidate.localRelativePath, candidate.rootKind, candidate.fileSize,
                        candidate.lastModifiedMs, "", "Persisted", candidate.sha1)
                }
                val pending = SteamCloudRemoteCommitStore.Pending(steamId64 = remote.steamId64,
                    localEntries = local, priorBaseline = prior, expectedRemoteEntries = expected.values.toList(),
                    deletedPaths = plan.remoteDeleteCandidates.mapTo(linkedSetOf()) { it.localRelativePath },
                    requiresLocalInstall = requiresLocalInstall)
                val uploadPaths = plan.uploadCandidates.map { it.remotePath }
                val deletePaths = plan.remoteDeleteCandidates.map { it.remotePath }
                pendingStore.prepareBeforeBegin(pending, unresolved, ::checkCancelled)
                journalToClear = pending
                // Only a known, idle batch can safely be aborted. A failed in-flight file RPC
                // (including BeginHTTPUpload/CommitHTTPUpload inside upload) may still execute.
                var idleBatch: Long? = null
                try {
                    trace?.enteredWrite = true
                    trace?.phase(SteamCloudSyncTrace.Phase.WRITE)
                    val batch = transport.begin(uploadPaths, deletePaths)
                    idleBatch = batch
                    if (plan.uploadCandidates.isNotEmpty()) report(SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                        SteamCloudSyncPhase.UPLOADING, 0, plan.uploadCandidates.size, "")
                    plan.uploadCandidates.forEachIndexed { index, candidate ->
                        checkCancelled()
                        idleBatch = null
                        transport.upload(candidate, File(frozenRoot, candidate.localRelativePath), batch)
                        idleBatch = batch
                        report(SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD, SteamCloudSyncPhase.UPLOADING,
                            index + 1, plan.uploadCandidates.size, candidate.localRelativePath)
                    }
                    plan.remoteDeleteCandidates.forEachIndexed { index, candidate ->
                        checkCancelled()
                        report(SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD, SteamCloudSyncPhase.DELETING_REMOTE,
                            index, plan.remoteDeleteCandidates.size, candidate.localRelativePath)
                        idleBatch = null
                        transport.delete(candidate.remotePath, batch)
                        idleBatch = batch
                    }
                    checkCancelled()
                    report(SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD, SteamCloudSyncPhase.VERIFYING_REMOTE, 0, 0, "")
                    idleBatch = null
                    var completionError: Exception? = null
                    try { transport.complete(batch) } catch (error: Exception) {
                        if (!SteamCloudFailureClassifier.mayVerifyFailedCompletion(error)) throw error
                        completionError = error
                    }
                    checkCancelled()
                    // Both normal and forced uploads use exactly the same post-commit verification.
                    finalRemote = readRemote()
                    if (!pendingStore.verify(pending, finalRemote)) {
                        throw SteamCloudPushReconciliationException("Cloud commit could not be verified; recheck before resolving.", completionError)
                    }
                } catch (error: Exception) {
                    if (SteamCloudFailureClassifier.classify(error) != SteamCloudFailureCategory.CANCELLED) {
                        idleBatch?.let { runCatching { transport.abort(it) } }
                    }
                    // The journal remains even after abort: other RPCs may have succeeded.
                    throw error
                }
            }
            report(SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL, SteamCloudSyncPhase.PREPARING_LOCAL_COMMIT, 0, 0, "")
            val finalLocal = local.associateBy { it.localRelativePath }.toMutableMap()
            val downloadedEntries = collect(File(work, "download")).associateBy { it.localRelativePath }
            downloads.forEach { change ->
                finalLocal.remove(change.localRelativePath)
                downloadedEntries[change.localRelativePath]?.let { finalLocal[change.localRelativePath] = it }
            }
            val deletedPaths = plan.remoteDeleteCandidates.mapTo(linkedSetOf()) { it.localRelativePath }
            deletedPaths += downloads.filter { it.currentRemote?.isLive != true }.map { it.localRelativePath }
            val nextBaseline = SteamCloudSyncPlanner.reconcileBaseline(prior, finalLocal.values.toList(), finalRemote, deletedPaths)
            val stagedBaseline = File(work, "baseline.json")
            SteamCloudAtomicFileStore.writeTextWithoutBackup(stagedBaseline, json.encodeToString(nextBaseline))
            val stagedManifest = File(work, "manifest.json")
            SteamCloudAtomicFileStore.writeTextWithoutBackup(stagedManifest, json.encodeToString(finalRemote))
            val projectedRoot = File(work, "projected")
            if (metadata != null) {
                SteamCloudFileTransaction.copyPath(frozenRoot, projectedRoot)
                downloads.forEach { change ->
                    val target = File(projectedRoot, change.localRelativePath)
                    if (target.exists() && !target.deleteRecursively()) throw java.io.IOException("Cannot prepare local projection")
                    val source = File(work, "download/${change.localRelativePath}")
                    if (source.exists()) SteamCloudFileTransaction.copyPath(source, target)
                }
            }
            val replacements = downloads.map { change ->
                SteamCloudPathReplacement(File(work, "download/${change.localRelativePath}").takeIf { it.exists() },
                    File(liveRoot, change.localRelativePath))
            } + listOf(SteamCloudPathReplacement(stagedBaseline, File(stateRoot, "sync-baseline.json")),
                SteamCloudPathReplacement(stagedManifest, File(stateRoot, "manifest.json")))
            // Do not hold the live-save lease across network RPCs. Steam exposes no conditional
            // write token here: this is an optimistic race check, not a distributed lock.
            report(SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL, SteamCloudSyncPhase.RECHECKING_REMOTE, 0, 0, "")
            requireUnchanged(finalRemote, readRemote())
            val apply = {
                checkCancelled()
                report(SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL, SteamCloudSyncPhase.RECHECKING_LOCAL, 0, 0, "")
                if (downloads.isNotEmpty() || requiresLocalInstall) {
                    val liveDirectories = hashSetOf<String>()
                    if (!sameLocalSet(originalLive, collect(liveRoot, liveDirectories))) {
                        throw SteamCloudStalePlanException("Local saves changed during download; no local files were replaced.")
                    }
                    if (downloads.isNotEmpty() && liveDirectories != directories) {
                        SteamCloudSyncPlanner.validatePathTree(downloads.map { it.localRelativePath }, liveDirectories)
                    }
                }
                report(SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL, SteamCloudSyncPhase.SAVING_SYNC_STATE, 0, 0, "")
                transact {
                    // The Android adapter serializes this check and commit with the control file
                    // update made by an immediate launch, in addition to the game's live lease.
                    requireLocalChangesAllowed()
                    report(SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        if (downloads.isNotEmpty() || requiresLocalInstall) SteamCloudSyncPhase.APPLYING_TO_LOCAL
                        else SteamCloudSyncPhase.SAVING_SYNC_STATE, 0, 0, "")
                    SteamCloudFileTransaction.execute(File(stateRoot, "transactions-v2"), storageRoot,
                        replacements + metadata?.invoke(work, projectedRoot).orEmpty(),
                        retainBackup = downloads.isNotEmpty() || requiresLocalInstall)
                }
            }
            if (downloads.isNotEmpty() || metadataRequiresLiveLease || requiresLocalInstall) {
                report(SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL, SteamCloudSyncPhase.WAITING_FOR_LOCAL_ACCESS, 0, 0, "")
                mutate(apply)
            } else apply()
            report(SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL, SteamCloudSyncPhase.CLEANING_UP, 0, 0, "")
            journalToClear?.let(pendingStore::clear)
            return Result(plan, plan.uploadCandidates.size, plan.remoteDeleteCandidates.size,
                downloads.count { it.currentRemote?.isLive == true })
        } finally { work.deleteRecursively() }
    }

    private data class PreparedPlan(val plan: SteamCloudUploadPlan, val prior: SteamCloudSyncBaseline?,
        val unresolved: SteamCloudRemoteCommitStore.Pending?, val hadPending: Boolean)

    private fun preparePlan(local: List<SteamCloudLocalFileSnapshotEntry>, remote: SteamCloudManifestSnapshot,
        baseline: SteamCloudSyncBaseline?, mode: SteamCloudSyncMode, directories: Set<String>): PreparedPlan {
        val pending = pendingStore.read()
        val recovered = pending?.takeIf {
            pendingStore.verify(it, remote) && (!it.requiresLocalInstall || sameLocalSet(it.localEntries, collect(liveRoot)))
        }?.let {
            SteamCloudSyncPlanner.reconcileBaseline(it.priorBaseline, it.localEntries, remote, it.deletedPaths)
        }
        val prior = recovered ?: baseline
        // Reject ambiguous paths before installing even a verified recovery baseline, or
        // allowing an explicit choice to supersede an unverified journal.
        val plan = SteamCloudSyncPlanner.buildUploadPlan(System.currentTimeMillis(), local, remote, prior, mode,
            directories, blacklist)
        if (recovered != null) {
            transact {
                pendingStore.finishVerifiedRecovery(requireNotNull(pending)) {
                    SteamCloudAtomicFileStore.writeTextWithoutBackup(File(stateRoot, "sync-baseline.json"), json.encodeToString(recovered))
                }
            }
        } else if (pending != null && mode != SteamCloudSyncMode.LOCAL_WINS && mode != SteamCloudSyncMode.CLOUD_WINS) {
            throw SteamCloudPushReconciliationException("An interrupted cloud write has an uncertain outcome; choose local or cloud explicitly after checking.")
        }
        return PreparedPlan(plan, prior, if (recovered == null) pending else null, pending != null)
    }

    private fun establishedAgreement(baseline: SteamCloudSyncBaseline, local: List<SteamCloudLocalFileSnapshotEntry>,
        remote: SteamCloudManifestSnapshot): Boolean {
        if (remote.steamId64.isBlank() || remote.steamId64 != baseline.steamId64 ||
            baseline.localEntries.size != local.size || baseline.remoteEntries.size != remote.entries.size ||
            local.size != remote.entries.size) return false
        val byPath = remote.entries.associateBy { it.localRelativePath }
        if (!local.all { SteamCloudSyncPlanner.matches(it, byPath[it.localRelativePath]) } ||
            baseline.localEntries.toSet() != local.toSet() || baseline.remoteEntries.toSet() != remote.entries.toSet()) return false
        val cached = try {
            val raw = json.parseToJsonElement(File(stateRoot, "manifest.json").readText()) as? JsonObject ?: return false
            // The snapshot normalizes its derived counts. Check the on-disk fields before
            // normalization so diagnostics never retain stale totals under the fast path.
            fun count(field: String) = (raw[field] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            if (count("fileCount") != remote.fileCount || count("preferencesCount") != remote.preferencesCount ||
                count("savesCount") != remote.savesCount) return false
            json.decodeFromJsonElement<SteamCloudManifestSnapshot>(raw)
        }
            catch (_: java.io.IOException) { return false }
            catch (_: IllegalArgumentException) { return false }
        // Ignore observation time only; stale tombstones, warnings or a different account
        // still need a cache refresh on the checked path.
        return cached.copy(fetchedAtMs = remote.fetchedAtMs) == remote
    }

    private fun readRemote(): SteamCloudManifestSnapshot {
        trace?.phase(SteamCloudSyncTrace.Phase.LIST)
        trace?.listing()
        val snapshot = transport.manifest()
        if (snapshot.steamId64 != pendingStore.steamId) throw SteamCloudStalePlanException("Cloud listing belongs to another account")
        return SteamCloudSyncBlacklist.filterManifestSnapshot(snapshot.also(SteamCloudSyncPlanner::validateManifest), blacklist)
            .also { trace?.remoteFiles = it.fileCount }
    }
    private fun cacheManifest(remote: SteamCloudManifestSnapshot) = transact {
        SteamCloudAtomicFileStore.writeTextWithoutBackup(File(stateRoot, "manifest.json"), json.encodeToString(remote))
    }
    private fun collect(root: File, directories: MutableSet<String>? = null) = SteamCloudSyncBlacklist.filterLocalEntries(
        SteamCloudLocalSnapshotCollector.collect(root, directories), blacklist)
    private fun requireUnchanged(expected: SteamCloudManifestSnapshot, actual: SteamCloudManifestSnapshot) {
        if (SteamCloudManifestIdentity.compute(expected) != SteamCloudManifestIdentity.compute(actual)) {
            throw SteamCloudStalePlanException("Cloud saves changed during synchronization; operation stopped.")
        }
    }
    private fun sameLocalSet(a: List<SteamCloudLocalFileSnapshotEntry>, b: List<SteamCloudLocalFileSnapshotEntry>): Boolean =
        a.size == b.size && a.all { entry -> SteamCloudSyncPlanner.sameLocal(entry, b.find { it.localRelativePath == entry.localRelativePath }) }
    private fun checkCancelled() { if (!shouldContinue() || Thread.currentThread().isInterrupted) throw CancellationException("Cloud sync cancelled") }
    private fun report(direction: SteamCloudSyncDirection, phase: SteamCloudSyncPhase, count: Int, total: Int, path: String) {
        trace?.progress(phase)
        progress(SteamCloudSyncProgress(direction, phase, count, total, path))
    }
}

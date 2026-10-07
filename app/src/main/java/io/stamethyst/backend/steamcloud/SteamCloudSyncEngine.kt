package io.stamethyst.backend.steamcloud

import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One algorithm for automatic merge, upload-only, and either explicit conflict resolution. */
internal class SteamCloudSyncEngine(
    private val storageRoot: File,
    private val liveRoot: File,
    private val stateRoot: File,
    private val transport: SteamCloudSyncTransport,
    private val blacklist: Set<String>,
    private val shouldContinue: () -> Boolean = { true },
    private val progress: (SteamCloudSyncProgress) -> Unit = {},
    private val mutate: ((() -> Unit) -> Unit) = { it() },
    private val transact: ((() -> Unit) -> Unit) = { it() },
) {
    data class Result(val plan: SteamCloudUploadPlan, val uploaded: Int, val deleted: Int, val downloaded: Int)
    private val pendingStore = SteamCloudRemoteCommitStore(stateRoot)
    private val json = Json { prettyPrint = true }

    fun inspect(local: List<SteamCloudLocalFileSnapshotEntry>, baseline: SteamCloudSyncBaseline?): SteamCloudUploadPlan {
        checkCancelled()
        val remote = readRemote()
        val recovered = recoverRemote(remote, baseline, false)
        cacheManifest(remote)
        return SteamCloudSyncPlanner.buildUploadPlan(System.currentTimeMillis(), local, remote, recovered)
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
    ): Result {
        checkCancelled()
        val local = collect(frozenRoot)
        val originalLive = if (requiresLocalInstall) collect(liveRoot) else local
        val remote = readRemote()
        // Confirm the inspected contents before recovery or any destructive operation.
        if (expectedPlan != null && (expectedPlan.plannedRemoteManifestIdentity != SteamCloudManifestIdentity.compute(remote) ||
                expectedPlan.plannedLocalIdentity != SteamCloudLocalSnapshotIdentity.compute(local))) {
            throw SteamCloudStalePlanException("Save contents changed since the synchronization preview; check again.")
        }
        val prior = recoverRemote(remote, baseline, mode == SteamCloudSyncMode.LOCAL_WINS || mode == SteamCloudSyncMode.CLOUD_WINS)
        val plan = SteamCloudSyncPlanner.buildUploadPlan(System.currentTimeMillis(), local, remote, prior, mode)
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
        val work = File(stateRoot, "work-v2/${UUID.randomUUID()}")
        check(work.mkdirs()) { "Cannot prepare sync workspace" }
        try {
            if (downloads.isNotEmpty()) report(SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                SteamCloudSyncPhase.DOWNLOADING, 0, downloads.size, "")
            downloads.forEachIndexed { index, change ->
                checkCancelled()
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
            if (remoteWrites) {
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
                pendingStore.write(pending)
                var batch: Long? = null
                var completionAttempted = false
                try {
                    checkCancelled()
                    batch = transport.begin(plan.uploadCandidates.map { it.remotePath }, plan.remoteDeleteCandidates.map { it.remotePath })
                    if (plan.uploadCandidates.isNotEmpty()) report(SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                        SteamCloudSyncPhase.UPLOADING, 0, plan.uploadCandidates.size, "")
                    plan.uploadCandidates.forEachIndexed { index, candidate ->
                        checkCancelled()
                        transport.upload(candidate, File(frozenRoot, candidate.localRelativePath), batch)
                        report(SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD, SteamCloudSyncPhase.UPLOADING,
                            index + 1, plan.uploadCandidates.size, candidate.localRelativePath)
                    }
                    plan.remoteDeleteCandidates.forEachIndexed { index, candidate ->
                        checkCancelled()
                        report(SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD, SteamCloudSyncPhase.DELETING_REMOTE,
                            index, plan.remoteDeleteCandidates.size, candidate.localRelativePath)
                        transport.delete(candidate.remotePath, batch)
                    }
                    checkCancelled()
                    report(SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD, SteamCloudSyncPhase.VERIFYING_REMOTE, 0, 0, "")
                    completionAttempted = true
                    var completionError: Exception? = null
                    try { transport.complete(batch) } catch (error: Exception) { completionError = error }
                    // Both normal and forced uploads use exactly the same post-commit verification.
                    finalRemote = readRemote()
                    if (!pendingStore.verify(pending, finalRemote)) {
                        throw SteamCloudPushReconciliationException("Cloud commit could not be verified; recheck before resolving.", completionError)
                    }
                } catch (error: Exception) {
                    if (!completionAttempted) batch?.let { runCatching { transport.abort(it) } }
                    // The journal remains even after abort: other RPCs may have succeeded.
                    throw error
                }
            }
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
            report(SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL, SteamCloudSyncPhase.FINALIZING, 0, 0, "")
            requireUnchanged(finalRemote, readRemote())
            val apply = {
                checkCancelled()
                if ((downloads.isNotEmpty() || requiresLocalInstall) && !sameLocalSet(originalLive, collect(liveRoot))) {
                    throw SteamCloudStalePlanException("Local saves changed during download; no local files were replaced.")
                }
                transact {
                    // The Android adapter serializes this check and commit with the control file
                    // update made by an immediate launch, in addition to the game's live lease.
                    requireLocalChangesAllowed()
                    report(SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        if (downloads.isNotEmpty() || requiresLocalInstall) SteamCloudSyncPhase.APPLYING_TO_LOCAL
                        else SteamCloudSyncPhase.FINALIZING, 0, 0, "")
                    SteamCloudFileTransaction.execute(File(stateRoot, "transactions-v2"), storageRoot,
                        replacements + metadata?.invoke(work, projectedRoot).orEmpty(),
                        retainBackup = downloads.isNotEmpty() || requiresLocalInstall)
                }
            }
            if (downloads.isNotEmpty() || metadataRequiresLiveLease || requiresLocalInstall) mutate(apply) else apply()
            pendingStore.clear()
            return Result(plan, plan.uploadCandidates.size, plan.remoteDeleteCandidates.size,
                downloads.count { it.currentRemote?.isLive == true })
        } finally { work.deleteRecursively() }
    }

    private fun recoverRemote(remote: SteamCloudManifestSnapshot, baseline: SteamCloudSyncBaseline?, explicit: Boolean): SteamCloudSyncBaseline? {
        val pending = pendingStore.read() ?: return baseline
        if (pendingStore.verify(pending, remote) &&
            (!pending.requiresLocalInstall || sameLocalSet(pending.localEntries, collect(liveRoot)))) {
            val recovered = SteamCloudSyncPlanner.reconcileBaseline(pending.priorBaseline, pending.localEntries, remote, pending.deletedPaths)
            transact {
                SteamCloudAtomicFileStore.writeTextWithoutBackup(File(stateRoot, "sync-baseline.json"), json.encodeToString(recovered))
                pendingStore.clear()
            }
            return recovered
        }
        if (!explicit) throw SteamCloudPushReconciliationException("An interrupted cloud write has an uncertain outcome; choose local or cloud explicitly after checking.")
        pendingStore.preserveForExplicitResolution()
        return baseline
    }

    private fun readRemote(): SteamCloudManifestSnapshot = SteamCloudSyncBlacklist.filterManifestSnapshot(
        transport.manifest().also(SteamCloudSyncPlanner::validateManifest), blacklist)
    private fun cacheManifest(remote: SteamCloudManifestSnapshot) = transact {
        SteamCloudAtomicFileStore.writeTextWithoutBackup(File(stateRoot, "manifest.json"), json.encodeToString(remote))
    }
    private fun collect(root: File) = SteamCloudSyncBlacklist.filterLocalEntries(SteamCloudLocalSnapshotCollector.collect(root), blacklist)
    private fun requireUnchanged(expected: SteamCloudManifestSnapshot, actual: SteamCloudManifestSnapshot) {
        if (SteamCloudManifestIdentity.compute(expected) != SteamCloudManifestIdentity.compute(actual)) {
            throw SteamCloudStalePlanException("Cloud saves changed during synchronization; operation stopped.")
        }
    }
    private fun sameLocalSet(a: List<SteamCloudLocalFileSnapshotEntry>, b: List<SteamCloudLocalFileSnapshotEntry>): Boolean =
        a.size == b.size && a.all { entry -> SteamCloudSyncPlanner.sameLocal(entry, b.find { it.localRelativePath == entry.localRelativePath }) }
    private fun checkCancelled() { if (!shouldContinue() || Thread.currentThread().isInterrupted) throw CancellationException("Cloud sync cancelled") }
    private fun report(direction: SteamCloudSyncDirection, phase: SteamCloudSyncPhase, count: Int, total: Int, path: String) {
        progress(SteamCloudSyncProgress(direction, phase, count, total, path))
    }
}

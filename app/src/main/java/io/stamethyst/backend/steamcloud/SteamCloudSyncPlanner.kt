package io.stamethyst.backend.steamcloud

import java.io.IOException

internal enum class SteamCloudSyncMode { MERGE, LOCAL_WINS, CLOUD_WINS, UPLOAD_ONLY }

/** Pure, content-based planner. No clocks are used to choose a winner. */
internal object SteamCloudSyncPlanner {
    fun buildUploadPlan(
        plannedAtMs: Long,
        currentLocalEntries: List<SteamCloudLocalFileSnapshotEntry>,
        currentRemoteSnapshot: SteamCloudManifestSnapshot,
        baseline: SteamCloudSyncBaseline?,
        mode: SteamCloudSyncMode = SteamCloudSyncMode.MERGE,
    ): SteamCloudUploadPlan {
        validateManifest(currentRemoteSnapshot)
        val local = currentLocalEntries.associateBy { it.localRelativePath }
        require(local.size == currentLocalEntries.size) { "Duplicate local save paths" }
        val remote = currentRemoteSnapshot.entriesForPlanning.associateBy { it.localRelativePath }
        val oldLocal = baseline?.localEntries?.associateBy { it.localRelativePath }.orEmpty()
        val oldRemote = baseline?.remoteEntries?.associateBy { it.localRelativePath }.orEmpty()
        val uploads = mutableListOf<SteamCloudUploadCandidate>()
        val deletes = mutableListOf<SteamCloudRemoteDeleteCandidate>()
        val downloads = mutableListOf<SteamCloudRemoteOnlyChange>()
        val conflicts = mutableListOf<SteamCloudConflict>()
        val warnings = currentRemoteSnapshot.warnings.toMutableList()
        val paths = (local.keys + remote.keys + oldLocal.keys + oldRemote.keys).sorted()
        for (path in paths) {
            val l = local[path]
            val r = remote[path]?.takeIf { it.isLive }
            val bl = oldLocal[path]
            val br = oldRemote[path]?.takeIf { it.isLive }
            val root = SteamCloudPathMapper.mapLocalRelativePath(path)?.rootKind
                ?: throw IOException("Unsupported save path: $path")
            if ((l == null && r == null) || matches(l, r)) continue
            val localChanged = !sameLocal(l, bl)
            val remoteChanged = !sameRemote(r, br)
            // A legacy baseline containing two different contents is not evidence of agreement.
            val baselineAgreed = (bl == null && br == null) || matches(bl, br)
            val decision = when (mode) {
                SteamCloudSyncMode.LOCAL_WINS -> 1
                SteamCloudSyncMode.CLOUD_WINS -> 2
                else -> when {
                    !baselineAgreed -> 0
                    localChanged && remoteChanged -> 0
                    localChanged -> 1
                    remoteChanged -> 2
                    else -> 0 // Never silently accept divergent but supposedly unchanged files.
                }
            }
            when (decision) {
                1 -> if (l != null) {
                    uploads += SteamCloudUploadCandidate(
                        remotePath = r?.remotePath ?: br?.remotePath
                            ?: requireNotNull(SteamCloudPathMapper.buildRemotePath(path)),
                        localRelativePath = path, rootKind = root, fileSize = l.fileSize,
                        lastModifiedMs = l.lastModifiedMs, sha256 = l.sha256, sha1 = l.sha1,
                        kind = if (r == null) SteamCloudUploadCandidateKind.NEW_FILE
                            else SteamCloudUploadCandidateKind.MODIFIED_FILE,
                    )
                } else if (r != null) {
                    if (root == SteamCloudRootKind.SAVES || mode == SteamCloudSyncMode.LOCAL_WINS) {
                        deletes += SteamCloudRemoteDeleteCandidate(r.remotePath, path, root)
                    } else {
                        warnings += SteamCloudUserWarning.IgnoredLocalDeletions(1).rawMessage()
                    }
                }
                2 -> downloads += SteamCloudRemoteOnlyChange(
                    path, root,
                    if (r == null) SteamCloudRemoteOnlyChangeKind.REMOTE_FILE_DELETED
                    else if (br == null) SteamCloudRemoteOnlyChangeKind.NEW_REMOTE_FILE
                    else SteamCloudRemoteOnlyChangeKind.MODIFIED_REMOTE_FILE,
                    r, oldRemote[path],
                )
                else -> conflicts += SteamCloudConflict(
                    path, root,
                    if (baseline == null || !baselineAgreed) SteamCloudConflictKind.BASELINE_REQUIRED
                    else SteamCloudConflictKind.BOTH_CHANGED,
                    l, remote[path], bl, oldRemote[path],
                )
            }
        }
        return SteamCloudUploadPlan(
            plannedAtMs, currentRemoteSnapshot.fetchedAtMs, baseline != null,
            uploads, conflicts, downloads, deletes, warnings.distinct(),
            SteamCloudManifestIdentity.compute(currentRemoteSnapshot),
            SteamCloudLocalSnapshotIdentity.compute(currentLocalEntries),
        )
    }

    fun matches(local: SteamCloudLocalFileSnapshotEntry?, remote: SteamCloudManifestEntry?): Boolean =
        local != null && remote?.isLive == true && local.fileSize == remote.rawSize &&
            local.sha1.isNotBlank() && remote.sha1.isNotBlank() &&
            local.sha1.equals(remote.sha1, ignoreCase = true)

    fun sameLocal(a: SteamCloudLocalFileSnapshotEntry?, b: SteamCloudLocalFileSnapshotEntry?): Boolean =
        if (a == null || b == null) a == b
        else a.fileSize == b.fileSize && a.sha256.isNotBlank() && a.sha256 == b.sha256

    fun sameRemote(a: SteamCloudManifestEntry?, b: SteamCloudManifestEntry?): Boolean =
        if (a == null || b == null) a == b
        else a.isLive == b.isLive && a.rawSize == b.rawSize && a.sha1.isNotBlank() &&
            a.sha1.equals(b.sha1, ignoreCase = true)

    fun validateManifest(snapshot: SteamCloudManifestSnapshot) {
        val paths = mutableSetOf<String>()
        snapshot.entriesForPlanning.forEach { entry ->
            val mapped = SteamCloudPathMapper.mapRemotePath(entry.remotePath)
            if (mapped == null || mapped.localRelativePath != entry.localRelativePath ||
                mapped.rootKind != entry.rootKind || !paths.add(entry.localRelativePath) ||
                !entry.hasKnownPersistState || (entry.isLive && (entry.sha1.isBlank() || entry.rawSize < 0))) {
                throw SteamCloudIncompleteManifestException("Unsafe or incomplete cloud listing: ${entry.remotePath}")
            }
        }
    }

    /** Advance only proven agreements; preserve pending remote changes on untouched paths. */
    fun reconcileBaseline(
        prior: SteamCloudSyncBaseline?,
        local: List<SteamCloudLocalFileSnapshotEntry>,
        remote: SteamCloudManifestSnapshot,
        deletedPaths: Set<String> = emptySet(),
    ): SteamCloudSyncBaseline {
        validateManifest(remote)
        val ls = prior?.localEntries?.associateBy { it.localRelativePath }?.toMutableMap() ?: linkedMapOf()
        val rs = prior?.remoteEntries?.associateBy { it.localRelativePath }?.toMutableMap() ?: linkedMapOf()
        val byPath = remote.entries.associateBy { it.localRelativePath }
        local.forEach { l ->
            val r = byPath[l.localRelativePath]
            if (matches(l, r)) { ls[l.localRelativePath] = l; rs[l.localRelativePath] = requireNotNull(r) }
        }
        val localPaths = local.mapTo(hashSetOf()) { it.localRelativePath }
        // Also retire independently converged deletions, not only files deleted by this operation.
        (ls.keys + rs.keys + deletedPaths).filter { it !in localPaths && it !in byPath }.forEach {
            ls.remove(it); rs.remove(it)
        }
        return SteamCloudSyncBaseline(System.currentTimeMillis(), ls.values.sortedBy { it.localRelativePath },
            rs.values.sortedBy { it.localRelativePath }, remote.steamId64)
    }
}

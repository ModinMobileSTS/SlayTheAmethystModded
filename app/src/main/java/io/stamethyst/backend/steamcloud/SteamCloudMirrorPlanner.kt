package io.stamethyst.backend.steamcloud

internal data class SteamCloudMirrorPlan(
    val uploadCandidates: List<SteamCloudUploadCandidate>,
    val deleteRemotePaths: List<String>,
)

internal object SteamCloudMirrorPlanner {
    fun buildLocalMirrorPlan(
        currentLocalEntries: List<SteamCloudLocalFileSnapshotEntry>,
        currentRemoteSnapshot: SteamCloudManifestSnapshot,
        baseline: SteamCloudSyncBaseline? = null,
    ): SteamCloudMirrorPlan {
        if (baseline != null) {
            return buildBaselineAwareLocalMirrorPlan(
                currentLocalEntries = currentLocalEntries,
                currentRemoteSnapshot = currentRemoteSnapshot,
                baseline = baseline,
            )
        }

        return buildFullLocalMirrorPlan(
            currentLocalEntries = currentLocalEntries,
            currentRemoteSnapshot = currentRemoteSnapshot,
        )
    }

    private fun buildFullLocalMirrorPlan(
        currentLocalEntries: List<SteamCloudLocalFileSnapshotEntry>,
        currentRemoteSnapshot: SteamCloudManifestSnapshot,
    ): SteamCloudMirrorPlan {
        val currentRemoteByPath = currentRemoteSnapshot.entries.associateBy { it.localRelativePath }
        val uploadCandidates = currentLocalEntries
            .sortedWith(compareBy<SteamCloudLocalFileSnapshotEntry>({ it.localRelativePath.lowercase() }, { it.localRelativePath }))
            .mapNotNull { localEntry ->
                val currentRemote = currentRemoteByPath[localEntry.localRelativePath]
                if (shouldSkipUploadBecauseRemoteMatches(localEntry, currentRemote)) {
                    return@mapNotNull null
                }
                val remotePath = currentRemote?.remotePath
                    ?: SteamCloudPathMapper.buildRemotePath(localEntry.localRelativePath)
                    ?: return@mapNotNull null
                SteamCloudUploadCandidate(
                    remotePath = remotePath,
                    localRelativePath = localEntry.localRelativePath,
                    rootKind = localEntry.rootKind,
                    fileSize = localEntry.fileSize,
                    lastModifiedMs = localEntry.lastModifiedMs,
                    sha256 = localEntry.sha256,
                    sha1 = localEntry.sha1,
                    kind = if (currentRemote != null) {
                        SteamCloudUploadCandidateKind.MODIFIED_FILE
                    } else {
                        SteamCloudUploadCandidateKind.NEW_FILE
                    },
                )
            }

        val localPaths = currentLocalEntries.mapTo(linkedSetOf()) { it.localRelativePath }
        val deleteRemotePaths = currentRemoteSnapshot.entries
            .asSequence()
            .filter { it.localRelativePath !in localPaths }
            .map { it.remotePath }
            .sortedWith(compareBy<String>({ it.lowercase() }, { it }))
            .toList()

        return SteamCloudMirrorPlan(
            uploadCandidates = uploadCandidates,
            deleteRemotePaths = deleteRemotePaths,
        )
    }

    private fun buildBaselineAwareLocalMirrorPlan(
        currentLocalEntries: List<SteamCloudLocalFileSnapshotEntry>,
        currentRemoteSnapshot: SteamCloudManifestSnapshot,
        baseline: SteamCloudSyncBaseline,
    ): SteamCloudMirrorPlan {
        val currentLocalByPath = currentLocalEntries.associateBy { it.localRelativePath }
        val currentRemoteByPath = currentRemoteSnapshot.entries.associateBy { it.localRelativePath }
        val baselineLocalByPath = baseline.localEntries.associateBy { it.localRelativePath }
        val baselineRemoteByPath = baseline.remoteEntries.associateBy { it.localRelativePath }
        val allPaths = linkedSetOf<String>().apply {
            addAll(baselineLocalByPath.keys)
            addAll(baselineRemoteByPath.keys)
            addAll(currentLocalByPath.keys)
            addAll(currentRemoteByPath.keys)
        }

        val uploadCandidates = mutableListOf<SteamCloudUploadCandidate>()
        val deleteRemotePaths = mutableListOf<String>()

        for (localRelativePath in allPaths.sortedWith(compareBy<String>({ it.lowercase() }, { it }))) {
            val currentLocal = currentLocalByPath[localRelativePath]
            val currentRemote = currentRemoteByPath[localRelativePath]
            val baselineLocal = baselineLocalByPath[localRelativePath]
            val baselineRemote = baselineRemoteByPath[localRelativePath]
            val rootKind = currentLocal?.rootKind
                ?: currentRemote?.rootKind
                ?: baselineLocal?.rootKind
                ?: baselineRemote?.rootKind
                ?: SteamCloudPathMapper.mapLocalRelativePath(localRelativePath)?.rootKind
                ?: continue

            if (currentLocal == null) {
                currentRemote?.remotePath?.let(deleteRemotePaths::add)
                continue
            }

            val remotePath = currentRemote?.remotePath
                ?: baselineRemote?.remotePath
                ?: SteamCloudPathMapper.buildRemotePath(localRelativePath)
                ?: continue

            val shouldUpload = when {
                currentRemote == null -> true
                else -> {
                    val localChanged = hasLocalChanged(baselineLocal, currentLocal)
                    val remoteChanged = hasRemoteChanged(baselineRemote, currentRemote)
                    localChanged || remoteChanged
                }
            }
            if (!shouldUpload) {
                continue
            }
            if (shouldSkipUploadBecauseRemoteMatches(currentLocal, currentRemote)) {
                continue
            }

            uploadCandidates += SteamCloudUploadCandidate(
                remotePath = remotePath,
                localRelativePath = localRelativePath,
                rootKind = rootKind,
                fileSize = currentLocal.fileSize,
                lastModifiedMs = currentLocal.lastModifiedMs,
                sha256 = currentLocal.sha256,
                sha1 = currentLocal.sha1,
                kind = if (currentRemote == null && baselineRemote == null) {
                    SteamCloudUploadCandidateKind.NEW_FILE
                } else {
                    SteamCloudUploadCandidateKind.MODIFIED_FILE
                },
            )
        }

        return SteamCloudMirrorPlan(
            uploadCandidates = uploadCandidates,
            deleteRemotePaths = deleteRemotePaths
                .sortedWith(compareBy<String>({ it.lowercase() }, { it })),
        )
    }

    private fun hasLocalChanged(
        baseline: SteamCloudLocalFileSnapshotEntry?,
        current: SteamCloudLocalFileSnapshotEntry?,
    ): Boolean {
        if (baseline == null && current == null) {
            return false
        }
        if (baseline == null || current == null) {
            return true
        }
        return baseline.fileSize != current.fileSize || baseline.sha256 != current.sha256
    }

    private fun hasRemoteChanged(
        baseline: SteamCloudManifestEntry?,
        current: SteamCloudManifestEntry?,
    ): Boolean {
        if (baseline == null && current == null) {
            return false
        }
        if (baseline == null || current == null) {
            return true
        }
        val sha1Changed = baseline.sha1.isNotBlank() &&
            current.sha1.isNotBlank() &&
            !baseline.sha1.equals(current.sha1, ignoreCase = true)
        return baseline.remotePath != current.remotePath
            || baseline.rawSize != current.rawSize
            || baseline.timestamp != current.timestamp
            || baseline.persistState != current.persistState
            || sha1Changed
    }

    private fun shouldSkipUploadBecauseRemoteMatches(
        local: SteamCloudLocalFileSnapshotEntry,
        remote: SteamCloudManifestEntry?,
    ): Boolean {
        if (remote == null) {
            return false
        }
        if (local.sha1.isBlank() || remote.sha1.isBlank()) {
            return false
        }
        return local.sha1.equals(remote.sha1, ignoreCase = true)
    }
}

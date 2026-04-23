package io.stamethyst.backend.steamcloud

internal object SteamCloudDiffPlanner {
    fun buildUploadPlan(
        plannedAtMs: Long,
        currentLocalEntries: List<SteamCloudLocalFileSnapshotEntry>,
        currentRemoteSnapshot: SteamCloudManifestSnapshot,
        baseline: SteamCloudSyncBaseline?,
    ): SteamCloudUploadPlan {
        val currentLocalByPath = currentLocalEntries.associateBy { it.localRelativePath }
        val currentRemoteByPath = currentRemoteSnapshot.entries.associateBy { it.localRelativePath }
        val baselineLocalByPath = baseline?.localEntries?.associateBy { it.localRelativePath }.orEmpty()
        val baselineRemoteByPath = baseline?.remoteEntries?.associateBy { it.localRelativePath }.orEmpty()
        val allPaths = linkedSetOf<String>().apply {
            addAll(baselineLocalByPath.keys)
            addAll(baselineRemoteByPath.keys)
            addAll(currentLocalByPath.keys)
            addAll(currentRemoteByPath.keys)
        }

        val uploadCandidates = mutableListOf<SteamCloudUploadCandidate>()
        val conflicts = mutableListOf<SteamCloudConflict>()
        val remoteOnlyChanges = mutableListOf<SteamCloudRemoteOnlyChange>()
        val warnings = mutableListOf<String>()
        var ignoredLocalDeletionCount = 0

        for (localRelativePath in allPaths.sortedWith(compareBy<String>({ it.lowercase() }, { it }))) {
            val currentLocal = currentLocalByPath[localRelativePath]
            val currentRemote = currentRemoteByPath[localRelativePath]
            val baselineLocal = baselineLocalByPath[localRelativePath]
            val baselineRemote = baselineRemoteByPath[localRelativePath]
            val rootKind = resolveRootKind(localRelativePath, currentLocal, currentRemote, baselineLocal, baselineRemote)
                ?: run {
                    warnings += "Ignored unsupported local path while planning upload: $localRelativePath"
                    continue
                }

            if (baseline == null && currentLocal != null && currentRemote != null) {
                conflicts += SteamCloudConflict(
                    localRelativePath = localRelativePath,
                    rootKind = rootKind,
                    kind = SteamCloudConflictKind.BASELINE_REQUIRED,
                    currentLocal = currentLocal,
                    currentRemote = currentRemote,
                    baselineLocal = null,
                    baselineRemote = null,
                )
                continue
            }

            val localChanged = hasLocalChanged(baselineLocal, currentLocal)
            val remoteChanged = hasRemoteChanged(baselineRemote, currentRemote)

            when {
                !localChanged && !remoteChanged -> Unit
                localChanged && remoteChanged -> {
                    conflicts += SteamCloudConflict(
                        localRelativePath = localRelativePath,
                        rootKind = rootKind,
                        kind = SteamCloudConflictKind.BOTH_CHANGED,
                        currentLocal = currentLocal,
                        currentRemote = currentRemote,
                        baselineLocal = baselineLocal,
                        baselineRemote = baselineRemote,
                    )
                }

                localChanged -> {
                    if (currentLocal == null) {
                        ignoredLocalDeletionCount++
                        continue
                    }
                    val remotePath = currentRemote?.remotePath
                        ?: baselineRemote?.remotePath
                        ?: SteamCloudPathMapper.buildRemotePath(localRelativePath)
                    if (remotePath == null) {
                        warnings += "Failed to map local file for Steam Cloud upload: $localRelativePath"
                        continue
                    }
                    uploadCandidates += SteamCloudUploadCandidate(
                        remotePath = remotePath,
                        localRelativePath = localRelativePath,
                        rootKind = rootKind,
                        fileSize = currentLocal.fileSize,
                        lastModifiedMs = currentLocal.lastModifiedMs,
                        sha256 = currentLocal.sha256,
                        kind = if (baselineRemote == null && currentRemote == null) {
                            SteamCloudUploadCandidateKind.NEW_FILE
                        } else {
                            SteamCloudUploadCandidateKind.MODIFIED_FILE
                        },
                    )
                }

                remoteChanged -> {
                    remoteOnlyChanges += SteamCloudRemoteOnlyChange(
                        localRelativePath = localRelativePath,
                        rootKind = rootKind,
                        kind = when {
                            baselineRemote == null && currentRemote != null ->
                                SteamCloudRemoteOnlyChangeKind.NEW_REMOTE_FILE
                            baselineRemote != null && currentRemote == null ->
                                SteamCloudRemoteOnlyChangeKind.REMOTE_FILE_DELETED
                            else ->
                                SteamCloudRemoteOnlyChangeKind.MODIFIED_REMOTE_FILE
                        },
                        currentRemote = currentRemote,
                        baselineRemote = baselineRemote,
                    )
                }
            }
        }

        if (baseline == null) {
            warnings += "No previous Steam Cloud sync baseline is saved yet. Existing files present on both sides are treated as conflicts until you complete a full pull once."
        }
        if (ignoredLocalDeletionCount > 0) {
            warnings += "Ignored $ignoredLocalDeletionCount local deletion(s) because Phase 2 uploads do not delete Steam Cloud files."
        }
        currentRemoteSnapshot.warnings.forEach { warnings += it }

        return SteamCloudUploadPlan(
            plannedAtMs = plannedAtMs,
            baselineConfigured = baseline != null,
            uploadCandidates = uploadCandidates,
            conflicts = conflicts,
            remoteOnlyChanges = remoteOnlyChanges,
            warnings = warnings.distinct(),
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
        return baseline.remotePath != current.remotePath
            || baseline.rawSize != current.rawSize
            || baseline.timestamp != current.timestamp
            || baseline.persistState != current.persistState
    }

    private fun resolveRootKind(
        localRelativePath: String,
        currentLocal: SteamCloudLocalFileSnapshotEntry?,
        currentRemote: SteamCloudManifestEntry?,
        baselineLocal: SteamCloudLocalFileSnapshotEntry?,
        baselineRemote: SteamCloudManifestEntry?,
    ): SteamCloudRootKind? {
        return currentLocal?.rootKind
            ?: currentRemote?.rootKind
            ?: baselineLocal?.rootKind
            ?: baselineRemote?.rootKind
            ?: SteamCloudPathMapper.mapLocalRelativePath(localRelativePath)?.rootKind
    }
}

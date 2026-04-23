package io.stamethyst.backend.steamcloud

internal data class SteamCloudMirrorPlan(
    val uploadCandidates: List<SteamCloudUploadCandidate>,
    val deleteRemotePaths: List<String>,
)

internal object SteamCloudMirrorPlanner {
    fun buildLocalMirrorPlan(
        currentLocalEntries: List<SteamCloudLocalFileSnapshotEntry>,
        currentRemoteSnapshot: SteamCloudManifestSnapshot,
    ): SteamCloudMirrorPlan {
        val currentRemoteByPath = currentRemoteSnapshot.entries.associateBy { it.localRelativePath }
        val uploadCandidates = currentLocalEntries
            .sortedWith(compareBy<SteamCloudLocalFileSnapshotEntry>({ it.localRelativePath.lowercase() }, { it.localRelativePath }))
            .mapNotNull { localEntry ->
                val remotePath = currentRemoteByPath[localEntry.localRelativePath]?.remotePath
                    ?: SteamCloudPathMapper.buildRemotePath(localEntry.localRelativePath)
                    ?: return@mapNotNull null
                SteamCloudUploadCandidate(
                    remotePath = remotePath,
                    localRelativePath = localEntry.localRelativePath,
                    rootKind = localEntry.rootKind,
                    fileSize = localEntry.fileSize,
                    lastModifiedMs = localEntry.lastModifiedMs,
                    sha256 = localEntry.sha256,
                    kind = if (currentRemoteByPath.containsKey(localEntry.localRelativePath)) {
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
}

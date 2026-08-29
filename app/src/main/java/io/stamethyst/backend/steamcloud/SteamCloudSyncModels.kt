package io.stamethyst.backend.steamcloud

import java.io.Serializable as JavaSerializable
import kotlinx.serialization.Serializable

@Serializable
data class SteamCloudLocalFileSnapshotEntry(
    val localRelativePath: String,
    val rootKind: SteamCloudRootKind,
    val fileSize: Long,
    val lastModifiedMs: Long,
    val sha256: String,
    val sha1: String = "",
) : JavaSerializable

@Serializable
data class SteamCloudSyncBaseline(
    val syncedAtMs: Long,
    val localEntries: List<SteamCloudLocalFileSnapshotEntry>,
    val remoteEntries: List<SteamCloudManifestEntry>,
    val steamId64: String = "",
) : JavaSerializable

enum class SteamCloudUploadCandidateKind {
    NEW_FILE,
    MODIFIED_FILE,
}

data class SteamCloudUploadCandidate(
    val remotePath: String,
    val localRelativePath: String,
    val rootKind: SteamCloudRootKind,
    val fileSize: Long,
    val lastModifiedMs: Long,
    val sha256: String,
    val sha1: String = "",
    val kind: SteamCloudUploadCandidateKind,
) : JavaSerializable

enum class SteamCloudConflictKind {
    BASELINE_REQUIRED,
    BOTH_CHANGED,
}

data class SteamCloudConflict(
    val localRelativePath: String,
    val rootKind: SteamCloudRootKind,
    val kind: SteamCloudConflictKind,
    val currentLocal: SteamCloudLocalFileSnapshotEntry?,
    val currentRemote: SteamCloudManifestEntry?,
    val baselineLocal: SteamCloudLocalFileSnapshotEntry?,
    val baselineRemote: SteamCloudManifestEntry?,
) : JavaSerializable

enum class SteamCloudRemoteOnlyChangeKind {
    NEW_REMOTE_FILE,
    MODIFIED_REMOTE_FILE,
    REMOTE_FILE_DELETED,
}

data class SteamCloudRemoteOnlyChange(
    val localRelativePath: String,
    val rootKind: SteamCloudRootKind,
    val kind: SteamCloudRemoteOnlyChangeKind,
    val currentRemote: SteamCloudManifestEntry?,
    val baselineRemote: SteamCloudManifestEntry?,
) : JavaSerializable

data class SteamCloudRemoteDeleteCandidate(
    val remotePath: String,
    val localRelativePath: String,
    val rootKind: SteamCloudRootKind,
) : JavaSerializable

data class SteamCloudUploadPlan(
    val plannedAtMs: Long,
    val remoteManifestFetchedAtMs: Long = 0L,
    val baselineConfigured: Boolean,
    val uploadCandidates: List<SteamCloudUploadCandidate>,
    val conflicts: List<SteamCloudConflict>,
    val remoteOnlyChanges: List<SteamCloudRemoteOnlyChange>,
    val remoteDeleteCandidates: List<SteamCloudRemoteDeleteCandidate>,
    val warnings: List<String>,
    val plannedRemoteManifestIdentity: String = "",
) : JavaSerializable {
    val uploadBytes: Long
        get() = uploadCandidates.sumOf { it.fileSize }
}

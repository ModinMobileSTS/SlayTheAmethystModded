package io.stamethyst.backend.steamcloud

import kotlinx.serialization.Serializable

internal const val STEAM_CLOUD_APP_ID: Int = 646570

@Serializable
enum class SteamCloudRootKind(val directoryName: String) {
    PREFERENCES("preferences"),
    SAVES("saves"),
}

enum class SteamCloudLoginChallengeKind {
    DEVICE_CONFIRMATION,
    DEVICE_CODE,
    EMAIL_CODE,
}

data class SteamCloudLoginChallenge(
    val kind: SteamCloudLoginChallengeKind,
    val emailHint: String = "",
    val previousCodeWasIncorrect: Boolean = false,
)

@Serializable
data class SteamCloudManifestEntry(
    val remotePath: String,
    val localRelativePath: String,
    val rootKind: SteamCloudRootKind,
    val rawSize: Long,
    val timestamp: Long,
    val machineName: String,
    val persistState: String,
    val sha1: String = "",
)

@Serializable
data class SteamCloudManifestSnapshot(
    val fetchedAtMs: Long,
    val fileCount: Int,
    val preferencesCount: Int,
    val savesCount: Int,
    val entries: List<SteamCloudManifestEntry>,
    val warnings: List<String>,
)

data class SteamCloudPullResult(
    val appliedFileCount: Int,
    val backupLabel: String?,
    val completedAtMs: Long,
    val summaryPath: String,
    val warnings: List<String>,
)

enum class SteamCloudSyncDirection {
    PUSH_LOCAL_TO_CLOUD,
    PULL_CLOUD_TO_LOCAL,
}

enum class SteamCloudSyncPhase {
    CONNECTING,
    LOGGING_ON,
    REFRESHING_MANIFEST,
    PREPARING_UPLOAD,
    UPLOADING,
    DOWNLOADING,
    BACKING_UP_LOCAL,
    APPLYING_TO_LOCAL,
    FINALIZING,
}

data class SteamCloudSyncProgress(
    val direction: SteamCloudSyncDirection,
    val phase: SteamCloudSyncPhase,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val currentPath: String = "",
    val progressPercent: Int? = null,
)

data class SteamCloudPushResult(
    val uploadedFileCount: Int,
    val uploadedBytes: Long,
    val deletedRemoteFileCount: Int = 0,
    val completedAtMs: Long,
    val summaryPath: String,
    val warnings: List<String>,
)

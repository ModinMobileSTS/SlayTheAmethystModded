package io.stamethyst.backend.steamcloud

import java.io.Serializable as JavaSerializable
import java.io.IOException
import java.util.Locale
import kotlinx.serialization.Serializable

const val STEAM_CLOUD_APP_ID: Int = 646570

@Serializable
enum class SteamCloudRootKind(val directoryName: String) {
    PREFERENCES("preferences"),
    SAVES("saves"),
}

enum class SteamCloudLoginChallengeKind {
    METHOD_SELECTION,
    DEVICE_CONFIRMATION,
    DEVICE_CODE,
    EMAIL_CODE,
}

enum class SteamCloudDeviceConfirmationDecision {
    APPROVE_ON_TRUSTED_DEVICE,
    USE_DEVICE_CODE,
}

data class SteamCloudLoginChallenge(
    val kind: SteamCloudLoginChallengeKind,
    val emailHint: String = "",
    val previousCodeWasIncorrect: Boolean = false,
    val deviceCodeAvailable: Boolean = false,
    val availableKinds: Set<SteamCloudLoginChallengeKind> = emptySet(),
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
) : JavaSerializable {
    private val persistStateKind: SteamCloudPersistStateKind
        get() = classifySteamCloudPersistState(persistState)

    internal val isLive: Boolean
        get() = persistStateKind == SteamCloudPersistStateKind.LIVE

    internal val isTombstone: Boolean
        get() = persistStateKind == SteamCloudPersistStateKind.TOMBSTONE

    internal val hasKnownPersistState: Boolean
        get() = persistStateKind != SteamCloudPersistStateKind.UNKNOWN
}

internal enum class SteamCloudPersistStateKind {
    LIVE,
    TOMBSTONE,
    UNKNOWN,
}

internal fun classifySteamCloudPersistState(persistState: String): SteamCloudPersistStateKind {
    val normalized = persistState.trim().lowercase(Locale.ROOT)
    return when {
        normalized == "persisted" ||
            normalized == "live" ||
            normalized.endsWith("persiststatepersisted") -> SteamCloudPersistStateKind.LIVE

        normalized == "deleted" ||
            normalized == "forgotten" ||
            normalized == "removed" ||
            normalized.endsWith("persiststatedeleted") ||
            normalized.endsWith("persiststateforgotten") -> SteamCloudPersistStateKind.TOMBSTONE

        else -> SteamCloudPersistStateKind.UNKNOWN
    }
}

internal fun steamCloudPersistStatesMatch(left: String, right: String): Boolean {
    val leftKind = classifySteamCloudPersistState(left)
    val rightKind = classifySteamCloudPersistState(right)
    return leftKind != SteamCloudPersistStateKind.UNKNOWN && leftKind == rightKind
}

internal class SteamCloudIncompleteManifestException(message: String) : IOException(message)

@Serializable
data class SteamCloudManifestSnapshot(
    val fetchedAtMs: Long,
    var fileCount: Int,
    var preferencesCount: Int,
    var savesCount: Int,
    var entries: List<SteamCloudManifestEntry>,
    val warnings: List<String>,
    var tombstoneEntries: List<SteamCloudManifestEntry> = emptyList(),
    val steamId64: String = "",
) : JavaSerializable {
    init {
        val allEntries = entries + tombstoneEntries
        require(allEntries.all { it.hasKnownPersistState }) {
            "Steam Cloud manifest contains an unknown persistence state."
        }
        entries = allEntries.filter { it.isLive }
        tombstoneEntries = allEntries.filter { it.isTombstone }
        fileCount = entries.size
        preferencesCount = entries.count { it.rootKind == SteamCloudRootKind.PREFERENCES }
        savesCount = entries.count { it.rootKind == SteamCloudRootKind.SAVES }
    }

    internal val entriesForPlanning: List<SteamCloudManifestEntry>
        get() = entries + tombstoneEntries
}

data class SteamCloudPullResult(
    val appliedFileCount: Int,
    val backupLabel: String?,
    val completedAtMs: Long,
    val summaryPath: String,
    val warnings: List<String>,
) : JavaSerializable

enum class SteamCloudSyncDirection {
    PUSH_LOCAL_TO_CLOUD,
    PULL_CLOUD_TO_LOCAL,
}

enum class SteamCloudSyncPhase {
    CONNECTING,
    LOGGING_ON,
    REFRESHING_MANIFEST,
    CREATING_UPLOAD_BATCH,
    PREPARING_UPLOAD,
    REQUESTING_UPLOAD_SLOT,
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

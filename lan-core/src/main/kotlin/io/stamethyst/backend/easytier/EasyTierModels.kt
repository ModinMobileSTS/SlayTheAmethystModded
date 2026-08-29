package io.stamethyst.backend.easytier

import java.io.Serializable as JavaSerializable
import kotlinx.serialization.Serializable

enum class EasyTierNetworkMode(val cloudControlValue: String) {
    Room("room"),
    Community("community");

    companion object {
        @JvmStatic
        fun fromCloudControl(value: String): EasyTierNetworkMode = when (value.trim().lowercase()) {
            "community" -> Community
            else -> Room
        }
    }
}

const val DEFAULT_EASYTIER_SHARED_ROOM_ID = "sts-public-lobby"
const val EASY_TIER_ROOM_DESCRIPTION_MAX_LENGTH = 120
const val EASY_TIER_ROOM_PASSWORD_MAX_LENGTH = 64
const val EASY_TIER_KICK_MESSAGE_MAX_LENGTH = 160

data class EasyTierResolvedConfig(
    val enabled: Boolean,
    val defaultMode: EasyTierNetworkMode,
    val roomApiBaseUrl: String,
    val webConsoleApiBaseUrl: String,
    val configServerUrl: String,
    val entryNodeUrl: String,
    val connectTimeoutSeconds: Int,
    val statusPollIntervalSeconds: Int,
    val allowSharedCommunityNetwork: Boolean,
) {
    val canConnect: Boolean get() = entryNodeUrl.isNotBlank()
}

@Serializable
data class EasyTierRoomSessionConfig(
    val sessionId: String,
    val roomId: String,
    val mode: EasyTierNetworkMode,
    val entryNodeUrl: String,
    val configServerUrl: String = "",
    val aclGroup: String = "",
    val networkSecret: String = "",
    val assignedIpv4Cidr: String = "",
    val macAddress: String = "",
    val sessionToken: String = "",
    val ownerToken: String = "",
    val expiresAtEpochSeconds: Long? = null,
)

@Serializable
data class EasyTierRoomMod(
    val name: String,
    val workshopId: String = "",
) {
    val isWorkshopMod: Boolean get() = workshopId.isNotBlank()
}

@Serializable
data class EasyTierRoomMember(
    val playerId: String,
    val displayName: String,
    val role: String,
    val online: Boolean,
    val gameState: String = "online",
    val assignedIpv4Cidr: String = "",
    val mods: List<EasyTierRoomMod> = emptyList(),
)

@Serializable
data class EasyTierRoomInfo(
    val roomId: String,
    val ownerPlayerId: String,
    val ownerDisplayName: String,
    val description: String = "",
    val mode: EasyTierNetworkMode,
    val allowNewJoins: Boolean,
    val hasPassword: Boolean = false,
    val closedAtMs: Long = 0L,
    val memberCount: Int,
    val inGameMemberCount: Int = 0,
    val roomState: String = "",
    val members: List<EasyTierRoomMember> = emptyList(),
)

@Serializable
data class EasyTierRoomListItem(
    val roomId: String,
    val ownerPlayerId: String,
    val ownerDisplayName: String,
    val description: String = "",
    val mode: EasyTierNetworkMode,
    val allowNewJoins: Boolean,
    val hasPassword: Boolean = false,
    val closedAtMs: Long = 0L,
    val memberCount: Int,
    val onlineMemberCount: Int = 0,
    val inGameMemberCount: Int = 0,
    val roomState: String = "",
    val lastSessionStartedAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

/** A single page of rooms from the Room API. */
data class EasyTierRoomListPage(
    val rooms: List<EasyTierRoomListItem>,
    val nextOffset: Int?,
)

@Serializable
data class EasyTierSessionStatusSnapshot(
    val sessionId: String,
    val roomId: String,
    val sessionState: String,
    val roomState: String,
    val peerCount: Int? = null,
    val assignedIpv4Cidr: String = "",
    val relayServerDescription: String = "",
    val kickMessage: String = "",
    val kickedAtMs: Long = 0L,
)

@Serializable
enum class EasyTierFailureCategory {
    None, VpnPermissionRequired, VpnPermissionDenied, VpnPermissionRevoked, ConfigMissing,
    SessionClosed, SessionKicked, SessionExpired, RoomClosed, RuntimeBridgePending,
    RuntimeBridgeUnavailable, BackgroundStartBlocked, Unknown,
}

@Serializable
enum class EasyTierConnectionStatus {
    IDLE, PERMISSION_REQUIRED, CONNECTING, SESSION_READY, CONNECTED, RECONNECTING,
    DISCONNECTING, DISCONNECTED, FAILED;

    val operationInFlight: Boolean
        get() = this == CONNECTING || this == RECONNECTING || this == DISCONNECTING
    val isActive: Boolean
        get() = this == CONNECTING || this == SESSION_READY || this == CONNECTED || this == RECONNECTING
}

/** Public, credential-free runtime state shared with the game JVM on Android and desktop. */
@Serializable
data class EasyTierConnectionSnapshot(
    val enabled: Boolean,
    val canConnect: Boolean,
    val status: EasyTierConnectionStatus,
    val mode: EasyTierNetworkMode,
    val failureCategory: EasyTierFailureCategory = EasyTierFailureCategory.None,
    val sessionId: String = "",
    val roomId: String = "",
    val entryNodeUrl: String = "",
    val configServerUrl: String = "",
    val aclGroup: String = "",
    val expiresAtEpochSeconds: Long? = null,
    val startedAtMs: Long? = null,
    val connectedAtMs: Long? = null,
    val lastUpdatedAtMs: Long = 0L,
    val lastErrorSummary: String = "",
    val diagnosticsSummaryPath: String = "",
    val assignedIpv4Cidr: String = "",
    val currentPlayerId: String = "",
    val roomOwnerPlayerId: String = "",
    val roomOwnerIpv4Cidr: String = "",
    val peerCount: Int? = null,
    val relayServerDescription: String = "",
    val lastSessionState: String = "",
    val lastRoomState: String = "",
    val userInitiated: Boolean = false,
) : JavaSerializable {
    val operationInFlight: Boolean get() = status.operationInFlight
    val isConnectionActive: Boolean get() = status.isActive
}

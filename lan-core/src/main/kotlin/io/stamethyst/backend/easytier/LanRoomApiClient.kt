package io.stamethyst.backend.easytier

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class LanRoomApiHttpException(
    val statusCode: Int,
    message: String,
    val errorCode: String = "",
) : IOException(message) {
    val isSessionMissing: Boolean get() = errorCode == "lan_session_not_found"
    val isPossiblyUnimplementedEndpoint: Boolean get() = statusCode == 404 && errorCode.isBlank()
}

data class LanClientIdentity(
    val version: String,
    val deviceSummary: String,
    val userAgentProduct: String = "SlayTheAmethyst",
) {
    val userAgent: String get() = "${userAgentProduct.trim().ifBlank { "SlayTheAmethyst" }}/${version.trim().ifBlank { "unknown" }}"
}

/** Cross-platform client for the launcher Room API. It deliberately receives all platform state. */
class LanRoomApiClient(
    private val roomApiBaseUrl: String,
    private val identity: LanClientIdentity,
    private val client: OkHttpClient = sharedHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun startSession(
        roomId: String,
        playerId: String,
        displayName: String,
        roomDescriptionWhenCreating: String = "",
        allowNewJoinsWhenCreating: Boolean? = null,
        createOnly: Boolean = false,
        sessionToken: String = "",
        ownerToken: String = "",
        macAddress: String = "",
        mods: List<EasyTierRoomMod> = emptyList(),
        password: String = "",
    ): EasyTierRoomSessionConfig {
        require(roomId.isNotBlank()) { "Room ID is required." }
        val body = StartSessionRequest(
            roomId.trim(), playerId.trim(), displayName.trim(), identity.version,
            identity.deviceSummary, roomDescriptionWhenCreating.trim().take(EASY_TIER_ROOM_DESCRIPTION_MAX_LENGTH),
            allowNewJoinsWhenCreating, createOnly, macAddress.trim(), mods,
            password.take(EASY_TIER_ROOM_PASSWORD_MAX_LENGTH),
        )
        return execute("start session", request("api", "lan", "session", "start")
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .credentials(sessionToken, ownerToken)
            .build()) { parseStart(it) }
    }

    fun stopSession(sessionId: String, sessionToken: String) {
        require(sessionId.isNotBlank() && sessionToken.isNotBlank()) { "Session credentials are required." }
        execute<Unit>("stop session", request("api", "lan", "session", "stop")
            .post(json.encodeToString(SessionIdRequest(sessionId.trim())).toRequestBody(JSON_MEDIA_TYPE))
            .sessionToken(sessionToken).build()) { Unit }
    }

    fun fetchSessionStatus(sessionId: String, sessionToken: String): EasyTierSessionStatusSnapshot =
        execute("session status", request("api", "lan", "session", "status", query = mapOf("sessionId" to sessionId.trim()))
            .sessionToken(sessionToken).get().build()) { parseStatus(it) }

    /** Runtime reports are the lease heartbeat. Call only after the local EasyTier process is alive. */
    fun reportSessionRuntime(
        sessionId: String,
        sessionToken: String,
        assignedIpv4Cidr: String,
        relayServerDescription: String = "",
    ): EasyTierSessionStatusSnapshot = execute(
        "session runtime", request("api", "lan", "session", "runtime")
            .post(json.encodeToString(RuntimeRequest(sessionId.trim(), assignedIpv4Cidr.trim(), relayServerDescription.trim()))
                .toRequestBody(JSON_MEDIA_TYPE)).sessionToken(sessionToken).build(),
    ) { parseStatus(it) }

    fun reportSessionGameState(sessionId: String, sessionToken: String, gameState: String) {
        require(gameState == "online" || gameState == "game") { "Invalid game state." }
        execute<Unit>("game state", request("api", "lan", "session", "game-state")
            .post(json.encodeToString(GameStateRequest(sessionId.trim(), gameState)).toRequestBody(JSON_MEDIA_TYPE))
            .sessionToken(sessionToken).build()) { Unit }
    }

    fun reportSessionMods(sessionId: String, sessionToken: String, mods: List<EasyTierRoomMod>) {
        execute<Unit>("reported mods", request("api", "lan", "session", "mods")
            .post(json.encodeToString(ModsRequest(sessionId.trim(), mods)).toRequestBody(JSON_MEDIA_TYPE))
            .sessionToken(sessionToken).build()) { Unit }
    }

    fun fetchRoomInfo(roomId: String): EasyTierRoomInfo = execute(
        "room info", request("api", "lan", "rooms", roomId.trim()).get().build(),
    ) { parseRoomInfo(it) }

    fun listRooms(limit: Int = 50): List<EasyTierRoomListItem> {
        val collected = mutableListOf<EasyTierRoomListItem>()
        var offset = 0
        repeat(200) {
            val page = execute("room list", request(
                "api", "lan", "rooms", query = mapOf("limit" to limit.coerceIn(1, 50).toString(), "offset" to offset.toString()),
            ).get().build()) { parseRoomList(it) }
            collected += page.rooms
            val next = page.nextOffset ?: return collected.distinctBy { room -> room.roomId }
            if (next <= offset) return collected.distinctBy { room -> room.roomId }
            offset = next
        }
        return collected.distinctBy { it.roomId }
    }

    fun lockRoom(roomId: String, ownerToken: String, sessionToken: String = ""): EasyTierRoomInfo =
        mutateRoom(roomId, ownerToken, sessionToken, "lock")
    fun unlockRoom(roomId: String, ownerToken: String, sessionToken: String = ""): EasyTierRoomInfo =
        mutateRoom(roomId, ownerToken, sessionToken, "unlock")
    fun closeRoom(roomId: String, ownerToken: String, sessionToken: String = ""): EasyTierRoomInfo =
        mutateRoom(roomId, ownerToken, sessionToken, "close")
    fun kickMember(roomId: String, ownerToken: String, sessionToken: String = "", targetPlayerId: String, message: String = ""): EasyTierRoomInfo =
        mutateRoom(roomId, ownerToken, sessionToken, "kick", targetPlayerId.trim(), message.trim().take(EASY_TIER_KICK_MESSAGE_MAX_LENGTH))

    private fun mutateRoom(roomId: String, ownerToken: String, sessionToken: String, action: String, targetPlayerId: String = "", message: String = ""): EasyTierRoomInfo {
        require(ownerToken.isNotBlank() || sessionToken.isNotBlank()) { "An active room owner session is required." }
        return execute("room action", request("api", "lan", "rooms", roomId.trim(), "action")
            .post(json.encodeToString(RoomActionRequest(action, targetPlayerId, message)).toRequestBody(JSON_MEDIA_TYPE))
            .credentials(sessionToken, ownerToken).build()) { parseRoomInfo(it) }
    }

    private fun request(vararg paths: String, query: Map<String, String> = emptyMap()): Request.Builder {
        require(roomApiBaseUrl.trim().isNotEmpty()) { "EasyTier room API base URL is unavailable." }
        val url = roomApiBaseUrl.trim().removeSuffix("/").toHttpUrl().newBuilder().apply {
            paths.forEach(::addPathSegment)
            query.forEach(::addQueryParameter)
        }.build()
        return Request.Builder().url(url).header("User-Agent", identity.userAgent).header("Accept", "application/json")
    }

    private fun Request.Builder.sessionToken(token: String): Request.Builder = apply {
        token.trim().takeIf(String::isNotEmpty)?.let { header("Authorization", "Bearer $it") }
    }

    private fun Request.Builder.credentials(sessionToken: String, ownerToken: String): Request.Builder =
        sessionToken(sessionToken).apply { ownerToken.trim().takeIf(String::isNotEmpty)?.let { header("X-Lan-Owner-Token", it) } }

    private fun <T> execute(operation: String, request: Request, parse: (String) -> T): T = client.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw LanRoomApiHttpException(response.code, errorMessage(operation, response.code, response.message, text), errorCode(text))
        parse(text)
    }

    private fun errorMessage(operation: String, status: Int, statusMessage: String, text: String): String =
        "EasyTier Room API $operation failed: HTTP $status${statusMessage.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()}" +
            (serverErrorMessage(text)?.let { " - $it" }.orEmpty())

    private fun errorCode(text: String): String = runCatching { json.decodeFromString<ApiErrorResponse>(text).error.trim() }.getOrDefault("")
        .takeUnless { it == "bad_request" || it == "internal_error" }.orEmpty()
    private fun serverErrorMessage(text: String): String? = runCatching { json.decodeFromString<ApiErrorResponse>(text).message.trim() }.getOrNull()
        ?.takeIf(String::isNotEmpty) ?: text.replace(Regex("\\s+"), " ").trim().takeIf(String::isNotEmpty)?.take(240)

    private fun parseStart(text: String): EasyTierRoomSessionConfig {
        val p = json.decodeFromString<StartSessionResponse>(text)
        return EasyTierRoomSessionConfig(p.sessionId.trim(), p.roomId.trim(), EasyTierNetworkMode.fromCloudControl(p.mode), p.entryNodeUrl.trim(), p.configServerUrl.trim(), p.aclGroup.trim(), p.networkSecret.trim(), p.assignedIpv4Cidr.trim(), p.macAddress.trim(), p.sessionToken.trim(), p.ownerToken.trim(), p.expiresAt)
    }
    private fun parseStatus(text: String): EasyTierSessionStatusSnapshot {
        val p = json.decodeFromString<SessionStatusResponse>(text)
        return EasyTierSessionStatusSnapshot(p.sessionId.trim(), p.roomId.trim(), p.sessionState.trim(), p.roomState.trim(), p.peerCount, p.assignedIpv4Cidr.trim(), p.relayServerDescription.trim(), p.kickMessage.trim(), p.kickedAtMs)
    }
    private fun parseRoomInfo(text: String): EasyTierRoomInfo {
        val p = json.decodeFromString<RoomInfoResponse>(text)
        return EasyTierRoomInfo(p.roomId.trim(), p.ownerPlayerId.trim(), p.ownerDisplayName.trim(), p.description.trim(), EasyTierNetworkMode.fromCloudControl(p.mode), p.allowNewJoins, p.hasPassword, p.closedAtMs, p.memberCount, p.inGameMemberCount, p.roomState.trim(), p.members.map { member -> EasyTierRoomMember(member.playerId.trim(), member.displayName.trim(), member.role.trim(), member.online, member.gameState.trim().ifBlank { "online" }, member.assignedIpv4Cidr.trim(), member.mods.map { EasyTierRoomMod(it.name.trim(), it.workshopId.trim()) }.filter { it.name.isNotBlank() }) })
    }
    private fun parseRoomList(text: String): RoomListPage {
        val p = json.decodeFromString<RoomListResponse>(text)
        return RoomListPage(p.rooms.map { room -> EasyTierRoomListItem(room.roomId.trim(), room.ownerPlayerId.trim(), room.ownerDisplayName.trim(), room.description.trim(), EasyTierNetworkMode.fromCloudControl(room.mode), room.allowNewJoins, room.hasPassword, room.closedAtMs, room.memberCount, room.onlineMemberCount, room.inGameMemberCount, room.roomState.trim(), room.lastSessionStartedAtMs, room.updatedAtMs) }, p.nextOffset?.takeIf { it >= 0 })
    }

    @Serializable private data class StartSessionRequest(val roomId: String, val playerId: String, val displayName: String, val clientVersion: String, val deviceSummary: String, val description: String, val allowNewJoins: Boolean?, val createOnly: Boolean, val macAddress: String, val mods: List<EasyTierRoomMod>, val password: String)
    @Serializable private data class SessionIdRequest(val sessionId: String)
    @Serializable private data class RuntimeRequest(val sessionId: String, val assignedIpv4Cidr: String, val relayServerDescription: String)
    @Serializable private data class GameStateRequest(val sessionId: String, val gameState: String)
    @Serializable private data class ModsRequest(val sessionId: String, val mods: List<EasyTierRoomMod>)
    @Serializable private data class RoomActionRequest(val action: String, val targetPlayerId: String = "", val message: String = "")
    @Serializable private data class ApiErrorResponse(val message: String = "", val error: String = "")
    @Serializable private data class StartSessionResponse(val sessionId: String, val roomId: String, val mode: String = "room", val entryNodeUrl: String, val configServerUrl: String = "", val aclGroup: String = "", val networkSecret: String = "", val assignedIpv4Cidr: String = "", val macAddress: String = "", val sessionToken: String = "", val ownerToken: String = "", @SerialName("expiresAt") val expiresAt: Long? = null)
    @Serializable private data class SessionStatusResponse(val sessionId: String, val roomId: String, val sessionState: String, val roomState: String, val peerCount: Int? = null, val assignedIpv4Cidr: String = "", val relayServerDescription: String = "", val kickMessage: String = "", val kickedAtMs: Long = 0)
    @Serializable private data class RoomInfoResponse(val roomId: String, val ownerPlayerId: String, val ownerDisplayName: String = "", val description: String = "", val mode: String = "room", val allowNewJoins: Boolean = false, val hasPassword: Boolean = false, val closedAtMs: Long = 0, val memberCount: Int = 0, val inGameMemberCount: Int = 0, val roomState: String = "", val members: List<RoomMemberResponse> = emptyList())
    @Serializable private data class RoomListResponse(val rooms: List<RoomListItemResponse> = emptyList(), val nextOffset: Int? = null)
    @Serializable private data class RoomListItemResponse(val roomId: String, val ownerPlayerId: String, val ownerDisplayName: String = "", val description: String = "", val mode: String = "room", val allowNewJoins: Boolean = false, val hasPassword: Boolean = false, val closedAtMs: Long = 0, val memberCount: Int = 0, val onlineMemberCount: Int = 0, val inGameMemberCount: Int = 0, val roomState: String = "", val lastSessionStartedAtMs: Long = 0, val updatedAtMs: Long = 0)
    @Serializable private data class RoomMemberResponse(val playerId: String, val displayName: String = "", val role: String = "", val online: Boolean = false, val gameState: String = "online", val assignedIpv4Cidr: String = "", val mods: List<RoomModResponse> = emptyList())
    @Serializable private data class RoomModResponse(val name: String = "", val workshopId: String = "")
    private data class RoomListPage(val rooms: List<EasyTierRoomListItem>, val nextOffset: Int?)
    private companion object { val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType() }
}

/**
 * Single shared client for every Room API call, with explicit timeouts.
 *
 * A bare [OkHttpClient] per instance meant two things: no timeouts at all beyond OkHttp's 10s
 * connect default, and a private connection pool per client. Callers that poll on a fixed interval
 * therefore reopened a TCP+TLS connection every tick while the server still held the previous one
 * for its keep-alive window, accumulating idle sockets instead of reusing one.
 *
 * The connect budget is deliberately below the launcher's 5s status poll interval so a stalled
 * connect cannot outlive the tick that scheduled it and push lease renewal past the server lease.
 */
private val sharedHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
}

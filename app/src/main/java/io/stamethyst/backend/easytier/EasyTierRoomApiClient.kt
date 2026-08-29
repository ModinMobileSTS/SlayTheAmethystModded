package io.stamethyst.backend.easytier

import android.content.Context
import android.os.Build
import io.stamethyst.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class EasyTierRoomApiHttpException(
    val statusCode: Int,
    message: String,
    /**
     * Machine-readable reason from the server's error body, when present.
     *
     * A bare 404 is ambiguous: it can mean "this server does not implement the endpoint" or "the
     * session you are renewing is gone". Treating the second case as the first permanently disabled
     * lease renewal, which guaranteed the session would then expire for real.
     */
    val errorCode: String = "",
) : IOException(message) {
    /** True when the server explicitly said the session no longer exists. */
    val isSessionMissing: Boolean
        get() = errorCode == ERROR_CODE_SESSION_NOT_FOUND

    /**
     * True when a 404 carries no server error code at all, which is the only case that still looks
     * like an endpoint the server does not implement.
     */
    val isPossiblyUnimplementedEndpoint: Boolean
        get() = statusCode == 404 && errorCode.isBlank()

    internal companion object {
        const val ERROR_CODE_SESSION_NOT_FOUND = "lan_session_not_found"
    }
}

internal class EasyTierRoomApiClient(
    private val context: Context,
    private val client: OkHttpClient = defaultHttpClient(),
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
        val config = EasyTierConfigRepository.current()
        val baseUrl = config.roomApiBaseUrl.trim()
        require(baseUrl.isNotEmpty()) { "EasyTier room API base URL is unavailable." }
        require(roomId.isNotBlank()) { "Room ID is required." }

        val requestBody = json.encodeToString(
            StartSessionRequest.serializer(),
            StartSessionRequest(
                roomId = roomId.trim(),
                playerId = playerId.trim(),
                displayName = displayName.trim(),
                clientVersion = BuildConfig.VERSION_NAME,
                deviceSummary = buildDeviceSummary(),
                description = roomDescriptionWhenCreating
                    .trim()
                    .take(EASY_TIER_ROOM_DESCRIPTION_MAX_LENGTH),
                allowNewJoins = allowNewJoinsWhenCreating,
                createOnly = createOnly,
                macAddress = macAddress.trim(),
                mods = mods,
                // Deliberately not trimmed: the server preserves whitespace in passwords, so
                // trimming here would make a password containing spaces impossible to send.
                password = password.take(EASY_TIER_ROOM_PASSWORD_MAX_LENGTH),
            )
        )
        val request = Request.Builder()
            .url(apiUrl(baseUrl, "api", "lan", "session", "start"))
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .applyLanCredentials(sessionToken, ownerToken)
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw EasyTierRoomApiHttpException(
                    statusCode = response.code,
                    message = buildHttpErrorMessage(
                        operation = "start session",
                        responseCode = response.code,
                        responseMessage = response.message,
                        responseText = responseText,
                    ),
                    errorCode = parseServerErrorCode(responseText),
                )
            }
            return parseStartSessionResponse(responseText)
        }
    }

    fun stopSession(sessionId: String, sessionToken: String) {
        val config = EasyTierConfigRepository.current()
        val baseUrl = config.roomApiBaseUrl.trim()
        require(baseUrl.isNotEmpty()) { "EasyTier room API base URL is unavailable." }
        require(sessionId.isNotBlank()) { "Session ID is required." }

        val requestBody = json.encodeToString(
            StopSessionRequest.serializer(),
            StopSessionRequest(sessionId = sessionId.trim())
        )
        val request = Request.Builder()
            .url(apiUrl(baseUrl, "api", "lan", "session", "stop"))
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .applyLanSessionToken(sessionToken)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseText = response.body?.string().orEmpty()
                throw EasyTierRoomApiHttpException(
                    statusCode = response.code,
                    message = buildHttpErrorMessage(
                        operation = "stop session",
                        responseCode = response.code,
                        responseMessage = response.message,
                        responseText = responseText,
                    ),
                    errorCode = parseServerErrorCode(responseText),
                )
            }
        }
    }

    fun fetchSessionStatus(sessionId: String, sessionToken: String): EasyTierSessionStatusSnapshot {
        val config = EasyTierConfigRepository.current()
        val baseUrl = config.roomApiBaseUrl.trim()
        require(baseUrl.isNotEmpty()) { "EasyTier room API base URL is unavailable." }
        require(sessionId.isNotBlank()) { "Session ID is required." }

        val request = Request.Builder()
            .url(
                apiUrl(
                    baseUrl,
                    "api",
                    "lan",
                    "session",
                    "status",
                    queryParameters = mapOf(
                        "sessionId" to sessionId.trim(),
                    ),
                )
            )
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .applyLanSessionToken(sessionToken)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw EasyTierRoomApiHttpException(
                    statusCode = response.code,
                    message = buildHttpErrorMessage(
                        operation = "session status",
                        responseCode = response.code,
                        responseMessage = response.message,
                        responseText = responseText,
                    ),
                    errorCode = parseServerErrorCode(responseText),
                )
            }
            return parseSessionStatusResponse(responseText)
        }
    }

    fun reportSessionRuntime(
        sessionId: String,
        sessionToken: String,
        assignedIpv4Cidr: String,
        relayServerDescription: String = "",
    ): EasyTierSessionStatusSnapshot {
        val config = EasyTierConfigRepository.current()
        val baseUrl = config.roomApiBaseUrl.trim()
        require(baseUrl.isNotEmpty()) { "EasyTier room API base URL is unavailable." }
        require(sessionId.isNotBlank()) { "Session ID is required." }
        require(assignedIpv4Cidr.isNotBlank()) { "Assigned IPv4 CIDR is required." }

        val requestBody = json.encodeToString(
            ReportSessionRuntimeRequest.serializer(),
            ReportSessionRuntimeRequest(
                sessionId = sessionId.trim(),
                assignedIpv4Cidr = assignedIpv4Cidr.trim(),
                relayServerDescription = relayServerDescription.trim(),
            )
        )
        val request = Request.Builder()
            .url(apiUrl(baseUrl, "api", "lan", "session", "runtime"))
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .applyLanSessionToken(sessionToken)
            .build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw EasyTierRoomApiHttpException(
                    statusCode = response.code,
                    message = buildHttpErrorMessage(
                        operation = "session runtime",
                        responseCode = response.code,
                        responseMessage = response.message,
                        responseText = responseText,
                    ),
                    errorCode = parseServerErrorCode(responseText),
                )
            }
            return parseSessionStatusResponse(responseText)
        }
    }

    fun reportSessionGameState(
        sessionId: String,
        sessionToken: String,
        gameState: String,
    ) {
        val config = EasyTierConfigRepository.current()
        val baseUrl = config.roomApiBaseUrl.trim()
        require(baseUrl.isNotEmpty()) { "EasyTier room API base URL is unavailable." }
        require(sessionId.isNotBlank()) { "Session ID is required." }
        require(sessionToken.isNotBlank()) { "Session token is required." }
        require(gameState == "online" || gameState == "game") { "Invalid game state." }

        val requestBody = json.encodeToString(
            ReportSessionGameStateRequest.serializer(),
            ReportSessionGameStateRequest(
                sessionId = sessionId.trim(),
                gameState = gameState,
            )
        )
        val request = Request.Builder()
            .url(apiUrl(baseUrl, "api", "lan", "session", "game-state"))
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .applyLanSessionToken(sessionToken)
            .build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw EasyTierRoomApiHttpException(
                    statusCode = response.code,
                    message = buildHttpErrorMessage(
                        operation = "game state",
                        responseCode = response.code,
                        responseMessage = response.message,
                        responseText = responseText,
                    ),
                    errorCode = parseServerErrorCode(responseText),
                )
            }
        }
    }

    fun reportSessionMods(
        sessionId: String,
        sessionToken: String,
        mods: List<EasyTierRoomMod>,
    ) {
        val config = EasyTierConfigRepository.current()
        val baseUrl = config.roomApiBaseUrl.trim()
        require(baseUrl.isNotEmpty()) { "EasyTier room API base URL is unavailable." }
        require(sessionId.isNotBlank()) { "Session ID is required." }
        require(sessionToken.isNotBlank()) { "Session token is required." }

        val requestBody = json.encodeToString(
            ReportSessionModsRequest.serializer(),
            ReportSessionModsRequest(
                sessionId = sessionId.trim(),
                mods = mods,
            )
        )
        val request = Request.Builder()
            .url(apiUrl(baseUrl, "api", "lan", "session", "mods"))
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .applyLanSessionToken(sessionToken)
            .build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw EasyTierRoomApiHttpException(
                    statusCode = response.code,
                    message = buildHttpErrorMessage(
                        operation = "reported mods",
                        responseCode = response.code,
                        responseMessage = response.message,
                        responseText = responseText,
                    ),
                    errorCode = parseServerErrorCode(responseText),
                )
            }
        }
    }

    fun fetchRoomInfo(roomId: String): EasyTierRoomInfo {
        val config = EasyTierConfigRepository.current()
        val baseUrl = config.roomApiBaseUrl.trim()
        require(baseUrl.isNotEmpty()) { "EasyTier room API base URL is unavailable." }
        require(roomId.isNotBlank()) { "Room ID is required." }

        val request = Request.Builder()
            .url(apiUrl(baseUrl, "api", "lan", "rooms", roomId.trim()))
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw EasyTierRoomApiHttpException(
                    statusCode = response.code,
                    message = buildHttpErrorMessage(
                        operation = "room info",
                        responseCode = response.code,
                        responseMessage = response.message,
                        responseText = responseText,
                    ),
                    errorCode = parseServerErrorCode(responseText),
                )
            }
            return parseRoomInfoResponse(responseText)
        }
    }

    fun listRooms(limit: Int = 50): List<EasyTierRoomListItem> {
        val config = EasyTierConfigRepository.current()
        val baseUrl = config.roomApiBaseUrl.trim()
        require(baseUrl.isNotEmpty()) { "EasyTier room API base URL is unavailable." }

        val resolvedLimit = limit.coerceIn(1, 50)
        val rooms = mutableListOf<EasyTierRoomListItem>()
        var offset = 0
        var pageCount = 0
        while (pageCount < 200) {
            val page = fetchRoomListPage(baseUrl, resolvedLimit, offset)
            rooms += page.rooms
            val nextOffset = page.nextOffset ?: break
            if (nextOffset <= offset) {
                break
            }
            offset = nextOffset
            pageCount += 1
        }
        return rooms.distinctBy { room -> room.roomId }
    }

    private fun fetchRoomListPage(
        baseUrl: String,
        limit: Int,
        offset: Int,
    ): EasyTierRoomListPage {
        val request = Request.Builder()
            .url(
                apiUrl(
                    baseUrl,
                    "api",
                    "lan",
                    "rooms",
                    queryParameters = mapOf(
                        "limit" to limit.toString(),
                        "offset" to offset.toString(),
                    ),
                )
            )
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw EasyTierRoomApiHttpException(
                    statusCode = response.code,
                    message = buildHttpErrorMessage(
                        operation = "room list",
                        responseCode = response.code,
                        responseMessage = response.message,
                        responseText = responseText,
                    ),
                    errorCode = parseServerErrorCode(responseText),
                )
            }
            return parseRoomListPage(responseText)
        }
    }

    private fun buildHttpErrorMessage(
        operation: String,
        responseCode: Int,
        responseMessage: String,
        responseText: String,
    ): String = buildString {
        append("EasyTier Room API ")
        append(operation)
        append(" failed: HTTP ")
        append(responseCode)
        responseMessage.takeIf { it.isNotBlank() }?.let {
            append(' ').append(it)
        }
        summarizeServerError(responseText)?.let {
            append(" - ").append(it)
        }
    }

    private fun summarizeServerError(responseText: String): String? {
        val normalized = responseText.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) {
            return null
        }
        val structured = runCatching {
            json.decodeFromString(ApiErrorResponse.serializer(), responseText)
        }.getOrNull()
        return (structured?.message?.trim().takeUnless { it.isNullOrBlank() }
            ?: structured?.error?.trim().takeUnless { it.isNullOrBlank() }
            ?: normalized).take(240)
    }

    /**
     * Extracts the server's machine-readable error code, or an empty string when the body is
     * missing, unparseable, or carries only the generic placeholder codes.
     */
    private fun parseServerErrorCode(responseText: String): String {
        if (responseText.isBlank()) {
            return ""
        }
        val structured = runCatching {
            json.decodeFromString(ApiErrorResponse.serializer(), responseText)
        }.getOrNull() ?: return ""
        val code = structured.error?.trim().orEmpty()
        // These are the fallbacks the server emits when it has nothing specific to say, so they
        // carry no more information than the status code itself.
        return if (code == "bad_request" || code == "internal_error") "" else code
    }

    fun lockRoom(
        roomId: String,
        ownerToken: String,
        sessionToken: String = "",
    ): EasyTierRoomInfo = mutateRoom(roomId, ownerToken, sessionToken, "lock")

    fun unlockRoom(
        roomId: String,
        ownerToken: String,
        sessionToken: String = "",
    ): EasyTierRoomInfo = mutateRoom(roomId, ownerToken, sessionToken, "unlock")

    fun closeRoom(
        roomId: String,
        ownerToken: String,
        sessionToken: String = "",
    ): EasyTierRoomInfo = mutateRoom(roomId, ownerToken, sessionToken, "close")

    fun kickMember(
        roomId: String,
        ownerToken: String,
        sessionToken: String = "",
        targetPlayerId: String,
        message: String = "",
    ): EasyTierRoomInfo {
        require(targetPlayerId.isNotBlank()) { "Target player ID is required." }
        return mutateRoom(
            roomId = roomId,
            ownerToken = ownerToken,
            sessionToken = sessionToken,
            action = "kick",
            targetPlayerId = targetPlayerId.trim(),
            kickMessage = message.trim().take(EASY_TIER_KICK_MESSAGE_MAX_LENGTH),
        )
    }

    private fun mutateRoom(
        roomId: String,
        ownerToken: String,
        sessionToken: String,
        action: String,
        targetPlayerId: String = "",
        kickMessage: String = "",
    ): EasyTierRoomInfo {
        val config = EasyTierConfigRepository.current()
        val baseUrl = config.roomApiBaseUrl.trim()
        require(baseUrl.isNotEmpty()) { "EasyTier room API base URL is unavailable." }
        require(roomId.isNotBlank()) { "Room ID is required." }
        require(ownerToken.isNotBlank() || sessionToken.isNotBlank()) {
            "An active room owner session is required."
        }

        val requestBody = json.encodeToString(
            UpdateRoomRequest.serializer(),
            UpdateRoomRequest(
                action = action,
                targetPlayerId = targetPlayerId,
                message = kickMessage,
            )
        )
        val request = Request.Builder()
            .url(apiUrl(baseUrl, "api", "lan", "rooms", roomId.trim(), "action"))
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .applyLanCredentials(sessionToken, ownerToken)
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw EasyTierRoomApiHttpException(
                    statusCode = response.code,
                    message = buildHttpErrorMessage(
                        operation = "room action",
                        responseCode = response.code,
                        responseMessage = response.message,
                        responseText = responseText,
                    ),
                    errorCode = parseServerErrorCode(responseText),
                )
            }
            return parseRoomInfoResponse(responseText)
        }
    }

    internal fun parseStartSessionResponse(responseText: String): EasyTierRoomSessionConfig {
        val payload = json.decodeFromString(StartSessionResponse.serializer(), responseText)
        return EasyTierRoomSessionConfig(
            sessionId = payload.sessionId.trim(),
            roomId = payload.roomId.trim(),
            mode = EasyTierNetworkMode.fromCloudControl(payload.mode),
            entryNodeUrl = payload.entryNodeUrl.trim(),
            configServerUrl = payload.configServerUrl.trim(),
            aclGroup = payload.aclGroup.trim(),
            networkSecret = payload.networkSecret.trim(),
            assignedIpv4Cidr = payload.assignedIpv4Cidr.trim(),
            macAddress = payload.macAddress.trim(),
            sessionToken = payload.sessionToken.trim(),
            ownerToken = payload.ownerToken.trim(),
            expiresAtEpochSeconds = payload.expiresAtEpochSeconds,
        )
    }

    internal fun parseSessionStatusResponse(responseText: String): EasyTierSessionStatusSnapshot {
        val payload = json.decodeFromString(SessionStatusResponse.serializer(), responseText)
        return EasyTierSessionStatusSnapshot(
            sessionId = payload.sessionId.trim(),
            roomId = payload.roomId.trim(),
            sessionState = payload.sessionState.trim(),
            roomState = payload.roomState.trim(),
            peerCount = payload.peerCount,
            assignedIpv4Cidr = payload.assignedIpv4Cidr.trim(),
            relayServerDescription = payload.relayServerDescription.trim(),
            kickMessage = payload.kickMessage.trim(),
            kickedAtMs = payload.kickedAtMs,
        )
    }

    internal fun parseRoomInfoResponse(responseText: String): EasyTierRoomInfo {
        val payload = json.decodeFromString(RoomInfoResponse.serializer(), responseText)
        return EasyTierRoomInfo(
            roomId = payload.roomId.trim(),
            ownerPlayerId = payload.ownerPlayerId.trim(),
            ownerDisplayName = payload.ownerDisplayName.trim(),
            description = payload.description.trim(),
            mode = EasyTierNetworkMode.fromCloudControl(payload.mode),
            allowNewJoins = payload.allowNewJoins,
            hasPassword = payload.hasPassword,
            closedAtMs = payload.closedAtMs,
            memberCount = payload.memberCount,
            inGameMemberCount = payload.inGameMemberCount,
            roomState = payload.roomState.trim(),
            members = payload.members.map { member ->
                EasyTierRoomMember(
                    playerId = member.playerId.trim(),
                    displayName = member.displayName.trim(),
                    role = member.role.trim(),
                    online = member.online,
                    gameState = member.gameState.trim().ifBlank { "online" },
                    assignedIpv4Cidr = member.assignedIpv4Cidr.trim(),
                    mods = member.mods.map { mod ->
                        EasyTierRoomMod(
                            name = mod.name.trim(),
                            workshopId = mod.workshopId.trim(),
                        )
                    }.filter { mod -> mod.name.isNotBlank() },
                )
            },
        )
    }

    internal fun parseRoomListResponse(responseText: String): List<EasyTierRoomListItem> {
        return parseRoomListPage(responseText).rooms
    }

    internal fun parseRoomListPage(responseText: String): EasyTierRoomListPage {
        val payload = json.decodeFromString(RoomListResponse.serializer(), responseText)
        return EasyTierRoomListPage(
            rooms = payload.rooms.map { room ->
            EasyTierRoomListItem(
                roomId = room.roomId.trim(),
                ownerPlayerId = room.ownerPlayerId.trim(),
                ownerDisplayName = room.ownerDisplayName.trim(),
                description = room.description.trim(),
                mode = EasyTierNetworkMode.fromCloudControl(room.mode),
                allowNewJoins = room.allowNewJoins,
                hasPassword = room.hasPassword,
                closedAtMs = room.closedAtMs,
                memberCount = room.memberCount,
                onlineMemberCount = room.onlineMemberCount,
                inGameMemberCount = room.inGameMemberCount,
                roomState = room.roomState.trim(),
                lastSessionStartedAtMs = room.lastSessionStartedAtMs,
                updatedAtMs = room.updatedAtMs,
            )
            },
            nextOffset = payload.nextOffset?.takeIf { it >= 0 },
        )
    }

    private fun buildDeviceSummary(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val parts = listOf(
            manufacturer.takeIf { it.isNotEmpty() },
            model.takeIf { it.isNotEmpty() },
            "sdk${Build.VERSION.SDK_INT}",
        )
        return parts.joinToString(" ").trim().ifEmpty { "android" }
    }

    private fun apiUrl(
        baseUrl: String,
        vararg pathSegments: String,
        queryParameters: Map<String, String> = emptyMap(),
    ): HttpUrl {
        val builder = baseUrl.trim().removeSuffix("/").toHttpUrl().newBuilder()
        pathSegments.forEach { segment ->
            builder.addPathSegment(segment)
        }
        queryParameters.forEach { (name, value) ->
            builder.addQueryParameter(name, value)
        }
        return builder.build()
    }

    private fun Request.Builder.applyLanCredentials(
        sessionToken: String,
        ownerToken: String,
    ): Request.Builder = applyLanSessionToken(sessionToken).applyLanOwnerToken(ownerToken)

    private fun Request.Builder.applyLanSessionToken(sessionToken: String): Request.Builder = apply {
        sessionToken.trim().takeIf { it.isNotEmpty() }?.let { token ->
            header("Authorization", "Bearer $token")
        }
    }

    private fun Request.Builder.applyLanOwnerToken(ownerToken: String): Request.Builder = apply {
        ownerToken.trim().takeIf { it.isNotEmpty() }?.let { token ->
            header("X-Lan-Owner-Token", token)
        }
    }

    @Serializable
    private data class StartSessionRequest(
        val roomId: String,
        val playerId: String,
        val displayName: String,
        val clientVersion: String,
        val deviceSummary: String,
        val description: String = "",
        val allowNewJoins: Boolean? = null,
        val createOnly: Boolean = false,
        val macAddress: String = "",
        val mods: List<EasyTierRoomMod> = emptyList(),
        val password: String = "",
    )

    @Serializable
    private data class ApiErrorResponse(
        val message: String = "",
        val error: String = "",
    )

    @Serializable
    private data class StopSessionRequest(
        val sessionId: String,
    )

    @Serializable
    private data class ReportSessionRuntimeRequest(
        val sessionId: String,
        val assignedIpv4Cidr: String,
        val relayServerDescription: String = "",
    )

    @Serializable
    private data class ReportSessionGameStateRequest(
        val sessionId: String,
        val gameState: String,
    )

    @Serializable
    private data class ReportSessionModsRequest(
        val sessionId: String,
        val mods: List<EasyTierRoomMod>,
    )

    @Serializable
    private data class UpdateRoomRequest(
        val action: String,
        val targetPlayerId: String = "",
        val message: String = "",
    )

    @Serializable
    private data class StartSessionResponse(
        val sessionId: String,
        val roomId: String,
        val mode: String = "room",
        val entryNodeUrl: String,
        val configServerUrl: String = "",
        val aclGroup: String = "",
        val networkSecret: String = "",
        val assignedIpv4Cidr: String = "",
        val macAddress: String = "",
        val sessionToken: String = "",
        val ownerToken: String = "",
        @SerialName("expiresAt")
        val expiresAtEpochSeconds: Long? = null,
    )

    @Serializable
    private data class SessionStatusResponse(
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
    private data class RoomInfoResponse(
        val roomId: String,
        val ownerPlayerId: String,
        val ownerDisplayName: String = "",
        val description: String = "",
        val mode: String = "room",
        val allowNewJoins: Boolean = false,
        val hasPassword: Boolean = false,
        val closedAtMs: Long = 0L,
        val memberCount: Int = 0,
        val inGameMemberCount: Int = 0,
        val roomState: String = "",
        val members: List<RoomMemberResponse> = emptyList(),
    )

    @Serializable
    private data class RoomListResponse(
        val rooms: List<RoomListItemResponse> = emptyList(),
        val nextOffset: Int? = null,
    )

    @Serializable
    private data class RoomListItemResponse(
        val roomId: String,
        val ownerPlayerId: String,
        val ownerDisplayName: String = "",
        val description: String = "",
        val mode: String = "room",
        val allowNewJoins: Boolean = false,
        val hasPassword: Boolean = false,
        val closedAtMs: Long = 0L,
        val memberCount: Int = 0,
        val onlineMemberCount: Int = 0,
        val inGameMemberCount: Int = 0,
        val roomState: String = "",
        val lastSessionStartedAtMs: Long = 0L,
        val updatedAtMs: Long = 0L,
    )

    @Serializable
    private data class RoomMemberResponse(
        val playerId: String,
        val displayName: String = "",
        val role: String = "",
        val online: Boolean = false,
        val gameState: String = "online",
        val assignedIpv4Cidr: String = "",
        val mods: List<RoomModResponse> = emptyList(),
    )

    @Serializable
    private data class RoomModResponse(
        val name: String = "",
        val workshopId: String = "",
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Connect budget for the Room API. Deliberately shorter than the 5s status poll interval so
         * a stalled connect cannot outlive the tick that scheduled it.
         *
         * OkHttp's default is 10s, which is longer than the poll interval: a single unreachable
         * connect stretched one iteration past the next, so the effective renewal cadence drifted
         * far beyond the 90s server lease and the session expired while the client thought it was
         * still polling.
         */
        private const val CONNECT_TIMEOUT_SECONDS = 4L

        /** Read budget. The Room API only returns small JSON documents, so this is generous. */
        private const val READ_TIMEOUT_SECONDS = 8L

        private const val WRITE_TIMEOUT_SECONDS = 8L

        /**
         * Hard ceiling on a whole call including retries and redirects. OkHttp has no call timeout
         * by default, so without this a request that keeps making slow progress can outlive several
         * poll intervals even when the individual connect/read budgets are respected.
         */
        private const val CALL_TIMEOUT_SECONDS = 12L

        /**
         * Single shared client for every Room API call.
         *
         * This must stay a singleton. Constructing a client per call gave each request its own
         * connection pool, so the 5s poll loop reopened a TCP+TLS connection every tick while the
         * server held the previous one for its 72s keep-alive window. Each polling device therefore
         * accumulated roughly a dozen idle server sockets instead of reusing one, which is what
         * saturated the upstream tunnel and turned ordinary polls into connect timeouts.
         */
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // Keep-alive is the point of sharing the client; the pool default is kept so idle
                // connections are reused across poll iterations.
                .retryOnConnectionFailure(true)
                .build()
        }

        internal fun defaultHttpClient(): OkHttpClient = sharedClient
    }
}

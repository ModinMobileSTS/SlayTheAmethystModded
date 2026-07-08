package top.apricityx.workshop.steam.protocol

import java.io.Closeable
import java.time.Instant

data class CmServer(
    val endpoint: String,
    val type: String,
    val websocketUri: String = SteamPacketCodec.buildWebSocketUri(endpoint),
)

data class CdnServer(
    val type: String,
    val sourceId: Int,
    val cellId: Int,
    val load: Int,
    val weightedLoad: Float,
    val numEntriesInClientList: Int,
    val steamChinaOnly: Boolean,
    val host: String,
    val vHost: String,
    val useAsProxy: Boolean,
    val proxyRequestPathTemplate: String?,
    val httpsSupport: String,
    val allowedAppIds: List<UInt>,
    val priorityClass: UInt,
) {
    private val normalizedHttpsSupport = httpsSupport.trim().lowercase()

    val supportsHttps: Boolean = normalizedHttpsSupport == "mandatory" || normalizedHttpsSupport == "optional"
    val requiresHttps: Boolean = normalizedHttpsSupport == "mandatory"
    val port: Int = if (supportsHttps) 443 else 80
    val secureScheme: String = if (supportsHttps) "https" else "http"

    fun requestEndpoints(): List<CdnRequestEndpoint> = buildList {
        add(
            CdnRequestEndpoint(
                scheme = secureScheme,
                port = port,
            ),
        )
        if (supportsHttps && !requiresHttps) {
            add(
                CdnRequestEndpoint(
                    scheme = "http",
                    port = 80,
                ),
            )
        }
    }
}

data class CdnRequestEndpoint(
    val scheme: String,
    val port: Int,
)

data class SessionContext(
    val sessionId: Int,
    val steamId: Long,
    val cellId: UInt,
    val heartbeatSeconds: Int,
)

data class CdnAuthToken(
    val token: String,
    val expiration: Instant,
)

data class SteamAccountSession(
    val accountName: String,
    val steamId: Long,
    val refreshToken: String,
    val shouldRememberPassword: Boolean = true,
    val machineName: String = DEFAULT_MACHINE_NAME,
)

data class SteamAppProductInfo(
    val appId: UInt,
    val changeNumber: UInt,
    val missingToken: Boolean,
    val buffer: ByteArray,
)

data class SteamAuthSessionDetails(
    val username: String,
    val password: String,
    val guardData: String? = null,
    val isPersistentSession: Boolean = true,
    val deviceFriendlyName: String = DEFAULT_MACHINE_NAME,
    val websiteId: String = DEFAULT_WEBSITE_ID,
    val clientOsType: Int = DEFAULT_CLIENT_OS_TYPE,
)

data class SteamGuardChallenge(
    val type: SteamGuardChallengeType,
    val message: String? = null,
)

enum class SteamGuardChallengeType {
    None,
    EmailCode,
    DeviceCode,
    DeviceConfirmation,
    EmailConfirmation,
    MachineToken,
    LegacyMachineAuth,
    Unknown,
}

data class SteamAuthPollResult(
    val steamId: Long,
    val accountName: String,
    val refreshToken: String,
    val accessToken: String,
    val newGuardData: String? = null,
)

data class SteamWebAccessTokens(
    val accessToken: String,
    val refreshToken: String? = null,
)

data class SteamPacket(
    val emsg: Int,
    val header: top.apricityx.workshop.steam.proto.CMsgProtoBufHeader,
    val body: ByteArray,
)

open class SteamProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SteamServiceMethodException(
    val methodName: String,
    val resultCode: Int,
    val steamMessage: String?,
    cause: Throwable? = null,
) : SteamProtocolException(
    message = buildString {
        append("Steam service request failed: ")
        append(methodName)
        append(" EResult=")
        append(resultCode)
        if (!steamMessage.isNullOrBlank()) {
            append(" (")
            append(steamMessage)
            append(")")
        }
    },
    cause = cause,
)

class SteamAuthenticationException(
    val resultCode: Int,
    message: String,
    cause: Throwable? = null,
) : SteamProtocolException(message, cause)

internal fun buildSteamAuthenticationErrorMessage(
    prefix: String,
    resultCode: Int,
    detail: String? = null,
): String =
    buildString {
        append(prefix)
        append(": ")
        append(steamAuthenticationResultDescription(resultCode) ?: "Steam authentication failed")
        append(" (EResult=")
        append(resultCode)
        append(")")
        detail
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { extra ->
                append(": ")
                append(extra)
            }
    }

internal fun steamAuthenticationResultDescription(resultCode: Int): String? =
    when (resultCode) {
        5 -> "账号名或密码错误"
        8 -> "Steam 拒绝了当前认证请求参数"
        15 -> "Steam 拒绝了这次认证请求"
        20 -> "Steam 认证服务暂时不可用"
        63 -> "需要邮箱中的 Steam Guard 验证码"
        65 -> "Steam Guard 验证码错误"
        66 -> "该账号当前无法使用邮箱验证码登录"
        74 -> "需要先完成邮箱验证后才能登录"
        84 -> "请求过于频繁，请稍后再试"
        85 -> "需要 Steam 手机令牌确认或动态码"
        87 -> "登录尝试过于频繁，请稍后再试"
        88 -> "Steam 手机令牌动态码错误"
        else -> null
    }

interface SteamCmSession : Closeable {
    suspend fun connect(servers: List<CmServer>)
    suspend fun connectAnonymous(servers: List<CmServer>): SessionContext
    suspend fun connectWithRefreshToken(
        servers: List<CmServer>,
        account: SteamAccountSession,
    ): SessionContext
    suspend fun <T : com.google.protobuf.MessageLite> callServiceMethod(
        methodName: String,
        request: com.google.protobuf.MessageLite,
        parser: com.google.protobuf.Parser<T>,
    ): T
    suspend fun requestDepotDecryptionKey(appId: UInt, depotId: UInt): ByteArray
    suspend fun requestAppProductInfo(appId: UInt): SteamAppProductInfo

    val currentSession: kotlinx.coroutines.flow.StateFlow<SessionContext?>
}

const val DEFAULT_MACHINE_NAME = "Android Workshop"
const val DEFAULT_WEBSITE_ID = "Client"
const val DEFAULT_CLIENT_OS_TYPE = -500

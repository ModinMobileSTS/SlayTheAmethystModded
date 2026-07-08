package top.apricityx.workshop.steam.protocol

import top.apricityx.workshop.steam.proto.CAuthentication_AccessToken_GenerateForApp_Request
import top.apricityx.workshop.steam.proto.CAuthentication_AccessToken_GenerateForApp_Response
import top.apricityx.workshop.steam.proto.CAuthentication_AllowedConfirmation
import top.apricityx.workshop.steam.proto.CAuthentication_BeginAuthSessionViaCredentials_Request
import top.apricityx.workshop.steam.proto.CAuthentication_BeginAuthSessionViaCredentials_Response
import top.apricityx.workshop.steam.proto.CAuthentication_DeviceDetails
import top.apricityx.workshop.steam.proto.CAuthentication_GetPasswordRSAPublicKey_Request
import top.apricityx.workshop.steam.proto.CAuthentication_GetPasswordRSAPublicKey_Response
import top.apricityx.workshop.steam.proto.CAuthentication_PollAuthSessionStatus_Request
import top.apricityx.workshop.steam.proto.CAuthentication_PollAuthSessionStatus_Response
import top.apricityx.workshop.steam.proto.CAuthentication_RefreshToken_Revoke_Request
import top.apricityx.workshop.steam.proto.CAuthentication_RefreshToken_Revoke_Response
import top.apricityx.workshop.steam.proto.CAuthentication_UpdateAuthSessionWithSteamGuardCode_Request
import top.apricityx.workshop.steam.proto.CAuthentication_UpdateAuthSessionWithSteamGuardCode_Response
import top.apricityx.workshop.steam.proto.EAuthSessionGuardType
import top.apricityx.workshop.steam.proto.EAuthTokenPlatformType
import top.apricityx.workshop.steam.proto.ESessionPersistence
import top.apricityx.workshop.steam.proto.ETokenRenewalType
import kotlinx.coroutines.delay
import java.io.Closeable
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher

class SteamAuthenticationClient(
    private val directoryClient: SteamDirectoryClient,
    private val sessionFactory: () -> SteamCmSession,
) {
    suspend fun beginAuthSession(
        details: SteamAuthSessionDetails,
        debugLogger: ((String) -> Unit)? = null,
    ): SteamCredentialAuthSession {
        val cmServers = directoryClient.loadServers()
        val session = sessionFactory()
        try {
            debugLogger.log("Protocol: loaded ${cmServers.size} CM server candidate(s) for credential auth.")
            session.connect(cmServers)
            debugLogger.log("Protocol: connected to Steam CM for credential auth.")
            debugLogger.log("Protocol: requesting RSA public key account=${details.username.maskForLog()}.")
            val publicKey = session.callServiceMethod(
                methodName = "Authentication.GetPasswordRSAPublicKey#1",
                request = CAuthentication_GetPasswordRSAPublicKey_Request.newBuilder()
                    .setAccountName(details.username)
                    .build(),
                parser = CAuthentication_GetPasswordRSAPublicKey_Response.parser(),
            )
            debugLogger.log(
                "Protocol: received RSA public key timestamp=${publicKey.timestamp} modulusBytes=${publicKey.publickeyMod.length / 2}.",
            )
            val encryptedPassword = encryptPassword(details.password, publicKey)
            debugLogger.log("Protocol: encrypted password length=${encryptedPassword.length}.")

            debugLogger.log(
                "Protocol: beginning auth session websiteId=${details.websiteId} guardDataPresent=${!details.guardData.isNullOrBlank()} deviceName=${details.deviceFriendlyName}.",
            )
            val beginResponse = session.callServiceMethod(
                methodName = "Authentication.BeginAuthSessionViaCredentials#1",
                request = buildBeginAuthSessionRequest(
                    details = details,
                    encryptedPassword = encryptedPassword,
                    encryptionTimestamp = publicKey.timestamp,
                ),
                parser = CAuthentication_BeginAuthSessionViaCredentials_Response.parser(),
            )

            val challenges = beginResponse.allowedConfirmationsList
                .map(::mapChallenge)
                .sortedBy(SteamGuardChallenge::sortOrder)
            debugLogger.log(
                "Protocol: auth session started steamId=${beginResponse.steamid} clientId=${beginResponse.clientId} intervalSeconds=${beginResponse.interval} challenges=${challenges.summaryForLog()}.",
            )

            return SteamCredentialAuthSession(
                session = session,
                steamId = beginResponse.steamid,
                clientId = beginResponse.clientId,
                requestId = beginResponse.requestId.toByteArray(),
                pollingIntervalMillis = (beginResponse.interval * 1_000f).toLong().coerceAtLeast(1_000L),
                challenges = challenges,
                debugLogger = debugLogger,
            )
        } catch (error: Throwable) {
            debugLogger.log("Protocol: credential auth failed ${error::class.java.simpleName}: ${error.message.orEmpty()}")
            session.close()
            throw error.asAuthenticationException("Steam 登录失败")
        }
    }

    suspend fun generateAccessTokenForApp(
        account: SteamAccountSession,
        allowRenewal: Boolean,
        debugLogger: ((String) -> Unit)? = null,
    ): SteamWebAccessTokens {
        val cmServers = directoryClient.loadServers()
        return sessionFactory().use { session ->
            try {
                debugLogger.log(
                    "Protocol: generating access token steamId=${account.steamId} account=${account.accountName.maskForLog()} allowRenewal=$allowRenewal cmServers=${cmServers.size}.",
                )
                session.connectWithRefreshToken(cmServers, account)
                debugLogger.log("Protocol: connected to Steam CM with refresh token.")
                val response = session.callServiceMethod(
                    methodName = "Authentication.GenerateAccessTokenForApp#1",
                    request = CAuthentication_AccessToken_GenerateForApp_Request.newBuilder()
                        .setRefreshToken(account.refreshToken)
                        .setSteamid(account.steamId)
                        .setRenewalType(
                            if (allowRenewal) {
                                ETokenRenewalType.k_ETokenRenewalType_Allow
                            } else {
                                ETokenRenewalType.k_ETokenRenewalType_None
                            },
                        )
                        .build(),
                    parser = CAuthentication_AccessToken_GenerateForApp_Response.parser(),
                )
                debugLogger.log(
                    "Protocol: generated access token accessLength=${response.accessToken.length} refreshUpdated=${response.refreshToken.isNotBlank()}.",
                )
                SteamWebAccessTokens(
                    accessToken = response.accessToken,
                    refreshToken = response.refreshToken.takeIf(String::isNotBlank),
                )
            } catch (error: Throwable) {
                debugLogger.log("Protocol: GenerateAccessTokenForApp failed ${error::class.java.simpleName}: ${error.message.orEmpty()}")
                throw error.asAuthenticationException("生成 Steam Web Token 失败")
            }
        }
    }

    suspend fun revokeRefreshToken(
        account: SteamAccountSession,
        tokenId: ULong,
        debugLogger: ((String) -> Unit)? = null,
    ) {
        val cmServers = directoryClient.loadServers()
        sessionFactory().use { session ->
            try {
                debugLogger.log(
                    "Protocol: revoking refresh token tokenId=$tokenId steamId=${account.steamId} cmServers=${cmServers.size}.",
                )
                session.connectWithRefreshToken(cmServers, account)
                debugLogger.log("Protocol: connected to Steam CM for refresh-token revocation.")
                session.callServiceMethod(
                    methodName = "Authentication.RevokeRefreshToken#1",
                    request = CAuthentication_RefreshToken_Revoke_Request.newBuilder()
                        .setTokenId(tokenId.toLong())
                        .setSteamid(account.steamId)
                        .build(),
                    parser = CAuthentication_RefreshToken_Revoke_Response.parser(),
                )
                debugLogger.log("Protocol: refresh token revoked successfully.")
            } catch (error: Throwable) {
                debugLogger.log("Protocol: RevokeRefreshToken failed ${error::class.java.simpleName}: ${error.message.orEmpty()}")
                throw error.asAuthenticationException("撤销 Steam Refresh Token 失败")
            }
        }
    }
}

class SteamCredentialAuthSession internal constructor(
    private val session: SteamCmSession,
    val steamId: Long,
    private val clientId: Long,
    private val requestId: ByteArray,
    val pollingIntervalMillis: Long,
    val challenges: List<SteamGuardChallenge>,
    private val debugLogger: ((String) -> Unit)? = null,
) : Closeable {
    suspend fun submitGuardCode(
        type: SteamGuardChallengeType,
        code: String,
    ) {
        try {
            debugLogger.log("Protocol: submitting Steam Guard code type=${type.name} codeLength=${code.length}.")
            session.callServiceMethod(
                methodName = "Authentication.UpdateAuthSessionWithSteamGuardCode#1",
                request = CAuthentication_UpdateAuthSessionWithSteamGuardCode_Request.newBuilder()
                    .setClientId(clientId)
                    .setSteamid(steamId)
                    .setCode(code)
                    .setCodeType(type.toProto())
                    .build(),
                parser = CAuthentication_UpdateAuthSessionWithSteamGuardCode_Response.parser(),
            )
            debugLogger.log("Protocol: Steam Guard code accepted by service.")
        } catch (error: Throwable) {
            debugLogger.log("Protocol: UpdateAuthSessionWithSteamGuardCode failed ${error::class.java.simpleName}: ${error.message.orEmpty()}")
            throw error.asAuthenticationException("提交 Steam Guard 验证码失败")
        }
    }

    suspend fun pollStatus(): SteamAuthPollResult? {
        try {
            debugLogger.log("Protocol: polling auth session status clientId=$clientId.")
            val response = session.callServiceMethod(
                methodName = "Authentication.PollAuthSessionStatus#1",
                request = CAuthentication_PollAuthSessionStatus_Request.newBuilder()
                    .setClientId(clientId)
                    .setRequestId(com.google.protobuf.ByteString.copyFrom(requestId))
                    .build(),
                parser = CAuthentication_PollAuthSessionStatus_Response.parser(),
            )
            if (response.refreshToken.isBlank()) {
                debugLogger.log("Protocol: auth session still pending.")
                return null
            }
            debugLogger.log(
                "Protocol: auth session completed account=${response.accountName.maskForLog()} refreshLength=${response.refreshToken.length} accessLength=${response.accessToken.length} guardDataUpdated=${response.newGuardData.isNotBlank()}.",
            )
            return SteamAuthPollResult(
                steamId = steamId,
                accountName = response.accountName,
                refreshToken = response.refreshToken,
                accessToken = response.accessToken,
                newGuardData = response.newGuardData.takeIf(String::isNotBlank),
            )
        } catch (error: Throwable) {
            debugLogger.log("Protocol: PollAuthSessionStatus failed ${error::class.java.simpleName}: ${error.message.orEmpty()}")
            throw error.asAuthenticationException("轮询 Steam 登录状态失败")
        }
    }

    suspend fun awaitResult(): SteamAuthPollResult {
        debugLogger.log("Protocol: waiting for auth result pollIntervalMs=$pollingIntervalMillis.")
        var attempts = 0
        while (true) {
            attempts += 1
            debugLogger.log("Protocol: auth poll attempt=$attempts.")
            pollStatus()?.let { result ->
                debugLogger.log("Protocol: auth result received after $attempts poll attempt(s).")
                return result
            }
            delay(pollingIntervalMillis)
        }
    }

    override fun close() {
        session.close()
    }
}

private fun SteamGuardChallenge.sortOrder(): Int =
    when (type) {
        SteamGuardChallengeType.None -> 0
        SteamGuardChallengeType.DeviceConfirmation -> 1
        SteamGuardChallengeType.DeviceCode -> 2
        SteamGuardChallengeType.EmailCode -> 3
        SteamGuardChallengeType.EmailConfirmation -> 4
        SteamGuardChallengeType.MachineToken -> 5
        SteamGuardChallengeType.LegacyMachineAuth -> 6
        SteamGuardChallengeType.Unknown -> 7
    }

private fun mapChallenge(source: CAuthentication_AllowedConfirmation): SteamGuardChallenge =
    SteamGuardChallenge(
        type = when (source.confirmationType) {
            EAuthSessionGuardType.k_EAuthSessionGuardType_None -> SteamGuardChallengeType.None
            EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode -> SteamGuardChallengeType.EmailCode
            EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode -> SteamGuardChallengeType.DeviceCode
            EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceConfirmation -> SteamGuardChallengeType.DeviceConfirmation
            EAuthSessionGuardType.k_EAuthSessionGuardType_EmailConfirmation -> SteamGuardChallengeType.EmailConfirmation
            EAuthSessionGuardType.k_EAuthSessionGuardType_MachineToken -> SteamGuardChallengeType.MachineToken
            EAuthSessionGuardType.k_EAuthSessionGuardType_LegacyMachineAuth -> SteamGuardChallengeType.LegacyMachineAuth
            else -> SteamGuardChallengeType.Unknown
        },
        message = source.associatedMessage.takeIf(String::isNotBlank),
    )

private fun SteamGuardChallengeType.toProto(): EAuthSessionGuardType =
    when (this) {
        SteamGuardChallengeType.None -> EAuthSessionGuardType.k_EAuthSessionGuardType_None
        SteamGuardChallengeType.EmailCode -> EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode
        SteamGuardChallengeType.DeviceCode -> EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode
        SteamGuardChallengeType.DeviceConfirmation -> EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceConfirmation
        SteamGuardChallengeType.EmailConfirmation -> EAuthSessionGuardType.k_EAuthSessionGuardType_EmailConfirmation
        SteamGuardChallengeType.MachineToken -> EAuthSessionGuardType.k_EAuthSessionGuardType_MachineToken
        SteamGuardChallengeType.LegacyMachineAuth -> EAuthSessionGuardType.k_EAuthSessionGuardType_LegacyMachineAuth
        SteamGuardChallengeType.Unknown -> EAuthSessionGuardType.k_EAuthSessionGuardType_Unknown
    }

private fun encryptPassword(
    password: String,
    publicKey: CAuthentication_GetPasswordRSAPublicKey_Response,
): String {
    val modulus = BigInteger(1, decodeHex(publicKey.publickeyMod))
    val exponent = BigInteger(1, decodeHex(publicKey.publickeyExp))
    val keySpec = RSAPublicKeySpec(modulus, exponent)
    val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
    cipher.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(keySpec))
    return Base64.getEncoder().encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)))
}

private fun decodeHex(value: String): ByteArray {
    require(value.length % 2 == 0) { "Invalid hex input length" }
    return ByteArray(value.length / 2) { index ->
        val offset = index * 2
        value.substring(offset, offset + 2).toInt(16).toByte()
    }
}

private fun List<SteamGuardChallenge>.summaryForLog(): String =
    if (isEmpty()) {
        "none"
    } else {
        joinToString(separator = ",") { challenge ->
            buildString {
                append(challenge.type.name)
                challenge.message
                    ?.takeIf(String::isNotBlank)
                    ?.let {
                        append("(message)")
                    }
            }
        }
    }

private fun String?.maskForLog(): String =
    this
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { value ->
            when {
                value.length <= 2 -> "*".repeat(value.length)
                else -> "${value.first()}***${value.last()}"
            }
        }
        ?: "-"

private fun ((String) -> Unit)?.log(line: String) {
    this?.invoke(line)
}

internal fun buildBeginAuthSessionRequest(
    details: SteamAuthSessionDetails,
    encryptedPassword: String,
    encryptionTimestamp: Long,
): CAuthentication_BeginAuthSessionViaCredentials_Request {
    val builder = CAuthentication_BeginAuthSessionViaCredentials_Request.newBuilder()
        .setAccountName(details.username)
        .setEncryptedPassword(encryptedPassword)
        .setEncryptionTimestamp(encryptionTimestamp)
        .setPersistence(
            if (details.isPersistentSession) {
                ESessionPersistence.k_ESessionPersistence_Persistent
            } else {
                ESessionPersistence.k_ESessionPersistence_Ephemeral
            },
        )
        .setWebsiteId(details.websiteId)
        .setDeviceDetails(
            CAuthentication_DeviceDetails.newBuilder()
                .setDeviceFriendlyName(details.deviceFriendlyName)
                .setPlatformType(EAuthTokenPlatformType.k_EAuthTokenPlatformType_SteamClient)
                .setOsType(details.clientOsType)
                .build(),
        )

    details.guardData
        ?.takeIf(String::isNotBlank)
        ?.let(builder::setGuardData)

    return builder.build()
}

private fun Throwable.asAuthenticationException(prefix: String): SteamAuthenticationException =
    when (this) {
        is SteamAuthenticationException -> this
        is SteamServiceMethodException -> SteamAuthenticationException(
            resultCode = resultCode,
            message = buildSteamAuthenticationErrorMessage(
                prefix = prefix,
                resultCode = resultCode,
                detail = steamMessage,
            ),
            cause = this,
        )

        else -> SteamAuthenticationException(
            resultCode = 2,
            message = listOfNotNull(prefix, message).joinToString(": "),
            cause = this,
        )
    }

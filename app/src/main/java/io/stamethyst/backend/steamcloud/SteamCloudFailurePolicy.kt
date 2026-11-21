package io.stamethyst.backend.steamcloud

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import top.apricityx.workshop.steam.protocol.SteamAuthenticationException
import top.apricityx.workshop.steam.protocol.SteamProtocolException
import top.apricityx.workshop.steam.protocol.SteamServerUnavailableException
import top.apricityx.workshop.steam.protocol.SteamServiceMethodException
import top.apricityx.workshop.steam.protocol.isSteamCmCredentialRejection

enum class SteamCloudFailureCategory {
    TRANSIENT_NETWORK,
    AUTH_REJECTED,
    PERMISSION_DENIED,
    REQUEST_REJECTED,
    RATE_LIMITED,
    CLOUD_CONFLICT,
    JOURNAL_PERSISTENCE,
    CANCELLED,
    MISSING_AUTH,
    UNKNOWN,
}

internal object SteamCloudFailureClassifier {
    private const val CREDENTIALS = "Authentication.BeginAuthSessionViaCredentials#1"

    fun credentialRequestResult(error: Throwable): Int? =
        generateSequence(error) { it.cause }.take(12).filterIsInstance<SteamServiceMethodException>()
            .firstOrNull()?.takeIf { it.methodName == CREDENTIALS }?.resultCode

    fun classify(error: Throwable): SteamCloudFailureCategory {
        val causes = generateSequence(error) { current ->
            current.cause?.takeUnless { it === current }
        }.take(12).toList()
        val description = causes.joinToString("\n") { current ->
            "${current.javaClass.name}: ${current.message.orEmpty()}"
        }
        val rpc = causes.filterIsInstance<SteamServiceMethodException>().firstOrNull()

        return when {
            causes.any { it is SteamCloudJournalPersistenceException } -> SteamCloudFailureCategory.JOURNAL_PERSISTENCE
            causes.any { (it is CancellationException && it !is TimeoutCancellationException) || it is InterruptedException } ->
                SteamCloudFailureCategory.CANCELLED
            causes.any { it is SteamCloudPushReconciliationException || it is SteamCloudStalePlanException || it is SteamCloudRecoveryException } ->
                SteamCloudFailureCategory.CLOUD_CONFLICT
            causes.any { it is TimeoutCancellationException || it is SocketTimeoutException || it is TimeoutException } ->
                SteamCloudFailureCategory.TRANSIENT_NETWORK

            // A typed reply belongs to its particular method. Details (and wrapper messages)
            // can contain server text, save paths, etc.; neither is credential evidence.
            rpc != null -> when {
                rpc.resultCode == 84 -> SteamCloudFailureCategory.RATE_LIMITED
                rpc.resultCode == 15 -> SteamCloudFailureCategory.PERMISSION_DENIED
                rpc.methodName == CREDENTIALS && rpc.resultCode == 5 -> SteamCloudFailureCategory.AUTH_REJECTED
                rpc.resultCode in listOf(5, 8) -> SteamCloudFailureCategory.REQUEST_REJECTED
                rpc.resultCode in listOf(10, 16, 20, 29, 71, 108) ||
                    (rpc.methodName == "Cloud.CompleteAppUploadBatchBlocking#1" && rpc.resultCode == 2) ->
                    SteamCloudFailureCategory.TRANSIENT_NETWORK
                else -> SteamCloudFailureCategory.UNKNOWN
            }
            causes.any { it is SteamAuthenticationException && it.isCmLogonFailure && it.isSteamCmCredentialRejection() } ->
                SteamCloudFailureCategory.AUTH_REJECTED
            causes.any { it is SteamCloudRateLimitCooldownException } || hasEResult(description, 84) || containsAny(
                description,
                "RateLimitExceeded",
                "rate limit",
                "too many requests",
                "request too frequently",
                "请求过于频繁",
                "請求過於頻繁",
            ) -> SteamCloudFailureCategory.RATE_LIMITED

            causes.any { it is SteamCloudCredentialsMissingException } ->
                SteamCloudFailureCategory.MISSING_AUTH

            causes.any { it is SteamServerUnavailableException } ||
                containsAny(description, "Steam server unavailable for request", "ClientServerUnavailable") ||
                hasEResult(description, 20) -> SteamCloudFailureCategory.TRANSIENT_NETWORK

            hasEResult(description, 108) || containsAny(
                description,
                "BeginHTTPUpload failed: DuplicateRequest",
            ) -> SteamCloudFailureCategory.TRANSIENT_NETWORK

            // CompleteAppUploadBatch returning EResult.Fail (2) is a known transient race where
            // Steam's backend hasn't finished committing the batch yet.  The upload itself
            // succeeded; this is a protocol-layer false-negative.
            containsAny(description, "completeappuploadbatch") && hasEResult(description, 2) ->
                SteamCloudFailureCategory.TRANSIENT_NETWORK

            causes.any { cause ->
                cause is ConnectException ||
                    cause is UnknownHostException ||
                    cause is SocketException
            } || containsAny(
                description,
                "unexpected transport abort",
                "unexpected disconnect",
                "client or session is no longer active",
                "watchdog: no response",
                "connection timed out",
            ) -> SteamCloudFailureCategory.TRANSIENT_NETWORK

            else -> SteamCloudFailureCategory.UNKNOWN
        }
    }

    fun isCmLogonFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(12).any { it is SteamProtocolException && it.isCmLogonFailure }

    /** A lost completion reply can be verified by content. A definite refusal must be
     * reported to this caller even if content already matches (e.g. another device wrote it). */
    fun mayVerifyFailedCompletion(error: Throwable): Boolean {
        val causes = generateSequence(error) { it.cause }.take(12)
        if (isCmLogonFailure(error) || causes.any { it is SteamCloudRateLimitCooldownException } ||
            classify(error) == SteamCloudFailureCategory.CANCELLED) return false
        val rpc = causes.filterIsInstance<SteamServiceMethodException>().firstOrNull() ?: return true
        return rpc.methodName == SteamCloudWriteRpc.COMPLETE_BATCH.method && rpc.resultCode in setOf(
            2,  // Blocking finalizer can return Fail while the batch is still settling.
            16, // Timeout
            71, // RemoteCallFailed
        )
    }

    private fun containsAny(description: String, vararg needles: String): Boolean =
        needles.any { needle -> description.contains(needle, ignoreCase = true) }

    private fun hasEResult(description: String, code: Int): Boolean =
        Regex("EResult\\s*=\\s*$code(?!\\d)", RegexOption.IGNORE_CASE).containsMatchIn(description)
}

internal class SteamCloudCredentialsMissingException(message: String) : IllegalStateException(message)

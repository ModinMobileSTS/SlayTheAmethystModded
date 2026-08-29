package io.stamethyst.backend.steamcloud

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

enum class SteamCloudFailureCategory {
    TRANSIENT_NETWORK,
    AUTH_REJECTED,
    RATE_LIMITED,
    CLOUD_CONFLICT,
    CANCELLED,
    MISSING_AUTH,
    UNKNOWN,
}

internal object SteamCloudFailureClassifier {
    fun classify(error: Throwable): SteamCloudFailureCategory {
        val causes = generateSequence(error) { current ->
            current.cause?.takeUnless { it === current }
        }.take(12).toList()
        val description = causes.joinToString("\n") { current ->
            "${current.javaClass.name}: ${current.message.orEmpty()}"
        }

        return when {
            causes.any { it is CancellationException || it is InterruptedException } ->
                SteamCloudFailureCategory.CANCELLED

            hasEResult(description, 84) || containsAny(
                description,
                "RateLimitExceeded",
                "rate limit",
                "too many requests",
                "request too frequently",
                "请求过于频繁",
                "請求過於頻繁",
            ) -> SteamCloudFailureCategory.RATE_LIMITED

            hasEResult(description, 5) || containsAny(
                description,
                "InvalidPassword",
                "invalid credentials",
                "invalid password",
            ) -> SteamCloudFailureCategory.AUTH_REJECTED

            causes.any { it is SteamCloudCredentialsMissingException } ->
                SteamCloudFailureCategory.MISSING_AUTH

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
                cause is SocketTimeoutException ||
                    cause is ConnectException ||
                    cause is UnknownHostException ||
                    cause is SocketException ||
                    cause is TimeoutException
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

    private fun containsAny(description: String, vararg needles: String): Boolean =
        needles.any { needle -> description.contains(needle, ignoreCase = true) }

    private fun hasEResult(description: String, code: Int): Boolean =
        Regex("EResult\\s*=\\s*$code(?!\\d)", RegexOption.IGNORE_CASE).containsMatchIn(description)
}

internal class SteamCloudCredentialsMissingException(message: String) : IllegalStateException(message)

internal object SteamAuthenticationCircuitBreaker {
    private val open = AtomicBoolean(false)

    fun trip(category: SteamCloudFailureCategory): Boolean {
        if (category != SteamCloudFailureCategory.AUTH_REJECTED &&
            category != SteamCloudFailureCategory.RATE_LIMITED
        ) {
            return false
        }
        return open.compareAndSet(false, true)
    }

    fun reset() {
        open.set(false)
    }

    fun isOpen(): Boolean = open.get()
}

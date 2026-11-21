package io.stamethyst.ui.settings.steamcloud

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.common.*
import io.stamethyst.ui.settings.core.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.sections.*
import io.stamethyst.ui.settings.services.*

import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import io.stamethyst.backend.steamcloud.SteamCloudFailureClassifier
import io.stamethyst.backend.steamcloud.SteamCloudFailureCategory

internal enum class SteamCloudErrorKind {
    USER_CANCELLED,
    AUTH_CONNECTION_CANCELLED,
    AUTH_WATCHDOG_DISCONNECT,
    INVALID_CREDENTIALS,
    UPLOAD_DISCONNECT,
    OTHER,
}

internal object SteamCloudErrorClassifier {
    fun classify(error: Throwable): SteamCloudErrorKind {
        val causeChain = unwrapCauseChain(error).toList()
        if (causeChain.any(::isSteamCloudAuthConnectionCancellation)) {
            return SteamCloudErrorKind.AUTH_CONNECTION_CANCELLED
        }

        val messages = causeChain.mapNotNull { current ->
            current.message?.trim()?.takeIf { it.isNotEmpty() }
        }
        val message = messages.firstOrNull().orEmpty()
        val firstCause = causeChain.firstOrNull()
        if (firstCause is CancellationException && isExplicitUserCancellation(firstCause)) {
            return SteamCloudErrorKind.USER_CANCELLED
        }
        if (firstCause is CancellationException) {
            return SteamCloudErrorKind.AUTH_CONNECTION_CANCELLED
        }
        if (SteamCloudFailureClassifier.classify(error) == SteamCloudFailureCategory.AUTH_REJECTED) {
            return SteamCloudErrorKind.INVALID_CREDENTIALS
        }
        if (isSteamCloudUploadDisconnect(message)) {
            return SteamCloudErrorKind.UPLOAD_DISCONNECT
        }
        if (isSteamCloudAuthWatchdogDisconnect(message)) {
            return SteamCloudErrorKind.AUTH_WATCHDOG_DISCONNECT
        }
        if (isSteamCloudAuthCompletionTimeout(message)) {
            return SteamCloudErrorKind.AUTH_WATCHDOG_DISCONNECT
        }
        return SteamCloudErrorKind.OTHER
    }

    fun meaningfulCause(error: Throwable): Throwable {
        return unwrapCauseChain(error).firstOrNull { current ->
            current.message?.trim()?.isNotEmpty() == true
        } ?: unwrapCauseChain(error).first()
    }

    private fun unwrapCauseChain(error: Throwable): Sequence<Throwable> {
        return sequence {
            var current = unwrapAsyncThrowable(error)
            while (true) {
                yield(current)
                val next = current.cause?.takeUnless { it === current } ?: break
                current = unwrapAsyncThrowable(next)
            }
        }
    }

    private fun unwrapAsyncThrowable(error: Throwable): Throwable {
        var current = error
        while (true) {
            val cause = when (current) {
                is ExecutionException -> current.cause
                is CompletionException -> current.cause
                else -> null
            }
            if (cause == null || cause === current) {
                return current
            }
            current = cause
        }
    }

    private fun isSteamCloudAuthConnectionCancellation(error: Throwable): Boolean {
        if (error !is CancellationException) {
            return false
        }
        val normalized = error.message.orEmpty().lowercase(Locale.US)
        return normalized.contains("channel was cancelled") ||
            normalized.contains("channel was canceled")
    }

    private fun isExplicitUserCancellation(error: CancellationException): Boolean {
        val normalized = error.message.orEmpty().lowercase(Locale.US)
        return normalized.contains("cancelled by user") ||
            normalized.contains("canceled by user") ||
            normalized.contains("login restarted") ||
            normalized.contains("credentials cleared") ||
            normalized.contains("settings screen cleared")
    }

    private fun isSteamCloudAuthWatchdogDisconnect(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        return normalized.contains("steam disconnected") &&
            normalized.contains("steam auth completion") &&
            normalized.contains("watchdog")
    }

    private fun isSteamCloudAuthCompletionTimeout(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        return normalized.contains("timed out waiting for steam auth completion")
    }

    private fun isSteamCloudUploadDisconnect(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        return normalized.contains("beginhttpupload") &&
            (normalized.contains("steam disconnected") ||
                normalized.contains("client or session is no longer active"))
    }
}

internal fun shouldRetrySteamCloudCredentialLoginWithoutGuardData(error: Throwable): Boolean =
    SteamCloudFailureClassifier.classify(error) in setOf(SteamCloudFailureCategory.AUTH_REJECTED,
        SteamCloudFailureCategory.REQUEST_REJECTED) && SteamCloudFailureClassifier.credentialRequestResult(error) in setOf(5, 8)


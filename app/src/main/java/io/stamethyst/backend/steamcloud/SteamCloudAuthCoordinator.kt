package io.stamethyst.backend.steamcloud

import android.content.Context
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
import top.apricityx.workshop.steam.protocol.SteamAuthenticationClient
import top.apricityx.workshop.steam.protocol.SteamAuthSessionDetails
import top.apricityx.workshop.steam.protocol.SteamCredentialAuthSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.steam.protocol.SteamGuardChallenge
import top.apricityx.workshop.steam.protocol.SteamGuardChallengeType

internal object SteamCloudAuthCoordinator {
    private const val AUTH_COMPLETION_TIMEOUT_MS = 4L * 60L * 1000L
    private const val LOGIN_CANCELLED_MESSAGE = "Steam Cloud login cancelled by user."

    interface AuthPrompt {
        fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String>

        fun getEmailCode(
            email: String?,
            previousCodeWasIncorrect: Boolean,
        ): CompletableFuture<String>

        fun getDeviceConfirmationDecision(
            deviceCodeAvailable: Boolean,
        ): CompletableFuture<SteamCloudDeviceConfirmationDecision>

        fun getChallengeSelection(
            challenges: List<SteamGuardChallenge>,
        ): CompletableFuture<SteamGuardChallengeType>
    }

    class CancellationHandle {
        private val cancelled = AtomicBoolean(false)
        private val session = AtomicReference<SteamCredentialAuthSession?>()
        private val loginAttemptId = AtomicReference<String?>(null)
        private val cancellationCleanupScheduled = AtomicBoolean(false)
        @Volatile
        private var onLoginAttemptCancelled: ((String) -> Unit)? = null

        val isCancelled: Boolean
            get() = cancelled.get()

        fun cancel() {
            val wasCancelled = cancelled.getAndSet(true)
            session.getAndSet(null)?.close()
            if (!wasCancelled) {
                scheduleLoginAttemptCleanup()
            }
        }

        internal fun bindLoginAttempt(
            attemptId: String,
            onCancelled: (String) -> Unit,
        ) {
            loginAttemptId.set(attemptId)
            onLoginAttemptCancelled = onCancelled
            if (cancelled.get()) {
                scheduleLoginAttemptCleanup()
            }
        }

        internal fun attach(session: SteamCredentialAuthSession) {
            this.session.set(session)
            if (cancelled.get()) {
                this.session.compareAndSet(session, null)
                session.close()
                throw CancellationException(LOGIN_CANCELLED_MESSAGE)
            }
        }

        internal fun detach(session: SteamCredentialAuthSession) {
            this.session.compareAndSet(session, null)
        }

        internal fun throwIfCancellationRequested() {
            if (cancelled.get()) {
                throw CancellationException(LOGIN_CANCELLED_MESSAGE)
            }
        }

        internal fun <T> runIfActive(block: () -> T): T {
            throwIfCancellationRequested()
            return block()
        }

        private fun scheduleLoginAttemptCleanup() {
            val attemptId = loginAttemptId.get()?.trim().orEmpty()
            val callback = onLoginAttemptCancelled ?: return
            if (attemptId.isNotEmpty() && cancellationCleanupScheduled.compareAndSet(false, true)) {
                callback(attemptId)
            }
        }
    }

    data class AuthResult(
        val accountName: String,
        val refreshToken: String,
        val guardData: String,
        val steamId64: String,
        val diagnosticsStartedAtMs: Long,
        val diagnosticsCompletedAtMs: Long,
        val diagnosticsSnapshot: SteamCloudClient.DiagnosticsSnapshot,
    )

    @Throws(Exception::class)
    fun authenticateWithCredentials(
        context: Context,
        username: String,
        password: String,
        existingGuardData: String,
        prompt: AuthPrompt,
        cancellationHandle: CancellationHandle = CancellationHandle(),
    ): AuthResult {
        val startedAtMs = System.currentTimeMillis()
        val normalizedUsername = username.trim()
        val normalizedGuardData = existingGuardData.trim().ifBlank { null }
        // Credential login keeps a dedicated CM transport: it must not ride (or
        // invalidate) the shared connection while establishing new credentials.
        val client = SteamCloudClient(context, SteamCloudClient.DownloadLimits.defaults(), false)
        try {
            client.use {
                client.beginOperationDiagnostics(
                    "credentials_login",
                    normalizedUsername,
                    !normalizedGuardData.isNullOrEmpty(),
                )
                val authMaterial = authenticateWithProtocolClient(
                    context = context,
                    username = normalizedUsername,
                    password = password,
                    guardData = normalizedGuardData,
                    prompt = prompt,
                    diagnosticsClient = client,
                    cancellationHandle = cancellationHandle,
                )
                val steamId64 = authMaterial.steamId64.trim()
                if (!isValidSteamId64(steamId64)) {
                    throw IllegalStateException("Steam 登录成功，但未能解析 SteamID64。请重新登录后再试。")
                }
                val completedAtMs = System.currentTimeMillis()
                val diagnosticsSnapshot = client.snapshotDiagnostics()
                SteamCloudDiagnosticsStore.writeSummary(
                    context = context,
                    operation = "credentials_login",
                    outcome = "SUCCESS",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = completedAtMs,
                    diagnostics = diagnosticsSnapshot,
                    extraLines = listOf(
                        "Refresh token received: ${authMaterial.refreshToken.length} chars",
                        "Guard data returned: ${if (authMaterial.guardData.isNullOrBlank()) "no" else "yes"}",
                        "SteamID64 resolved: ${if (steamId64.isBlank()) "no" else "yes"}",
                        "Resolved SteamID64 value: ${steamId64.ifBlank { "<blank>" }}",
                    ),
                )
                return AuthResult(
                    accountName = authMaterial.accountName,
                    refreshToken = authMaterial.refreshToken,
                    guardData = authMaterial.guardData ?: "",
                    steamId64 = steamId64,
                    diagnosticsStartedAtMs = startedAtMs,
                    diagnosticsCompletedAtMs = completedAtMs,
                    diagnosticsSnapshot = diagnosticsSnapshot,
                )
            }
        } catch (error: Throwable) {
            val cancelled = cancellationHandle.isCancelled || isCancellation(error)
            runCatching {
                SteamCloudDiagnosticsStore.writeSummary(
                    context = context,
                    operation = "credentials_login",
                    outcome = if (cancelled) "CANCELLED" else "FAILED",
                    accountName = normalizedUsername,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = client.snapshotDiagnostics(),
                    failureSummary = error.message,
                    error = error,
                    extraLines = listOf(
                        "Existing guard data provided: ${if (normalizedGuardData.isNullOrBlank()) "no" else "yes"}",
                    ),
                )
            }
            throw error
        }
    }

    @Throws(Exception::class)
    private fun authenticateWithProtocolClient(
        context: Context,
        username: String,
        password: String,
        guardData: String?,
        prompt: AuthPrompt,
        diagnosticsClient: SteamCloudClient,
        cancellationHandle: CancellationHandle,
    ): SteamCloudClient.AuthMaterial = runBlocking {
        cancellationHandle.throwIfCancellationRequested()
        val httpClient = SteamCloudAcceleratedHttp.createClient(
            context = context,
            connectTimeoutMs = 40_000L,
            readTimeoutMs = 60_000L,
            callTimeoutMs = 60_000L,
        )
        val authenticationClient = SteamAuthenticationClient(
            directoryClient = SteamDirectoryClient(httpClient),
            sessionFactory = { OkHttpSteamCmSession(httpClient) },
        )
        val protocolEvents = mutableListOf<String>()
        val debugLogger: (String) -> Unit = { line ->
            val normalized = line.replace('\r', ' ').replace('\n', ' ').trim()
            if (normalized.isNotEmpty()) {
                protocolEvents += normalized
                diagnosticsClient.recordProtocolAuthDiagnostic(normalized)
            }
        }

        authenticationClient.beginAuthSession(
            details = SteamAuthSessionDetails(
                username = username,
                password = password,
                guardData = guardData,
            ),
            debugLogger = debugLogger,
        ).use { session ->
            cancellationHandle.attach(session)
            try {
                val challenges = session.challenges
                val challengeSummary = summarizeChallenges(challenges)
                val lastPrompt = AtomicReference("<not requested>")
                val supportedChallenges = supportedChallengeOptions(challenges)
                val selectedChallenge = selectChallenge(prompt, supportedChallenges)
                diagnosticsClient.recordProtocolAuthDiagnostic(
                    "selected_challenge=${selectedChallenge.type.name}"
                )
                cancellationHandle.throwIfCancellationRequested()

                when (selectedChallenge.type) {
                    SteamGuardChallengeType.None -> Unit
                    SteamGuardChallengeType.DeviceConfirmation -> {
                        lastPrompt.set("device_confirmation")
                        diagnosticsClient.recordProtocolAuthDiagnostic("auth_prompt device_confirmation")
                        when (
                            prompt.getDeviceConfirmationDecision(
                                challenges.any { it.type == SteamGuardChallengeType.DeviceCode }
                            ).get()
                        ) {
                            SteamCloudDeviceConfirmationDecision.APPROVE_ON_TRUSTED_DEVICE -> Unit
                            SteamCloudDeviceConfirmationDecision.USE_DEVICE_CODE -> {
                                if (challenges.none { it.type == SteamGuardChallengeType.DeviceCode }) {
                                    throw IllegalStateException("Steam 未提供可用的 Steam Guard 2FA 验证码方式。")
                                }
                                submitGuardCodeWithPromptRetry(
                                    session = session,
                                    challengeType = SteamGuardChallengeType.DeviceCode,
                                    promptState = lastPrompt,
                                    diagnosticsClient = diagnosticsClient,
                                    codeProvider = { previousCodeWasIncorrect ->
                                        prompt.getDeviceCode(previousCodeWasIncorrect)
                                    },
                                )
                            }
                        }
                    }

                    SteamGuardChallengeType.DeviceCode -> submitGuardCodeWithPromptRetry(
                        session = session,
                        challengeType = SteamGuardChallengeType.DeviceCode,
                        promptState = lastPrompt,
                        diagnosticsClient = diagnosticsClient,
                        codeProvider = { previousCodeWasIncorrect ->
                            prompt.getDeviceCode(previousCodeWasIncorrect)
                        },
                    )

                    SteamGuardChallengeType.EmailCode -> submitGuardCodeWithPromptRetry(
                        session = session,
                        challengeType = SteamGuardChallengeType.EmailCode,
                        promptState = lastPrompt,
                        diagnosticsClient = diagnosticsClient,
                        codeProvider = { previousCodeWasIncorrect ->
                            prompt.getEmailCode(selectedChallenge.message.orEmpty(), previousCodeWasIncorrect)
                        },
                    )

                    else -> throw IllegalStateException(
                        "Steam 登录需要当前启动器尚不支持的验证方式：${selectedChallenge.type.name}"
                    )
                }

                cancellationHandle.throwIfCancellationRequested()
                val pollResult = awaitAuthResultWithTimeout(session)
                cancellationHandle.throwIfCancellationRequested()
                val steamId64 = pollResult.steamId.toString()
                diagnosticsClient.applyProtocolAuthDiagnostics(
                    steamId64,
                    challengeSummary,
                    lastPrompt.get(),
                    !pollResult.newGuardData.isNullOrBlank(),
                )
                diagnosticsClient.recordProtocolAuthDiagnostic(
                    "completed account=${pollResult.accountName} steamId64=$steamId64 " +
                        "refreshTokenReceived=${pollResult.refreshToken.isNotBlank()} " +
                        "guardDataUpdated=${!pollResult.newGuardData.isNullOrBlank()} " +
                        "events=${protocolEvents.size}"
                )
                SteamCloudClient.AuthMaterial(
                    pollResult.accountName,
                    pollResult.refreshToken,
                    pollResult.newGuardData ?: guardData,
                    steamId64,
                )
            } catch (error: Throwable) {
                cancellationHandle.throwIfCancellationRequested()
                throw error
            } finally {
                cancellationHandle.detach(session)
            }
        }
    }

    @Throws(Exception::class)
    private suspend fun submitGuardCodeWithPromptRetry(
        session: SteamCredentialAuthSession,
        challengeType: SteamGuardChallengeType,
        promptState: AtomicReference<String>,
        diagnosticsClient: SteamCloudClient,
        codeProvider: (previousCodeWasIncorrect: Boolean) -> CompletableFuture<String>,
    ) {
        var previousCodeWasIncorrect = false
        while (true) {
            val promptDescription = describePrompt(challengeType, previousCodeWasIncorrect)
            promptState.set(promptDescription)
            diagnosticsClient.recordProtocolAuthDiagnostic(
                "auth_prompt type=${challengeType.name} retry=$previousCodeWasIncorrect"
            )
            val code = codeProvider(previousCodeWasIncorrect).get().trim()
            if (code.isBlank()) {
                throw IllegalStateException("Steam Guard 验证码为空。")
            }
            try {
                session.submitGuardCode(challengeType, code)
                return
            } catch (error: Throwable) {
                if (!isSteamGuardCodeMismatch(error)) {
                    throw error
                }
                previousCodeWasIncorrect = true
            }
        }
    }

    @Throws(Exception::class)
    private suspend fun awaitAuthResultWithTimeout(session: SteamCredentialAuthSession) =
        try {
            withTimeout(AUTH_COMPLETION_TIMEOUT_MS) {
                session.awaitResult()
            }
        } catch (error: TimeoutCancellationException) {
            throw TimeoutException(
                "Timed out waiting for Steam auth completion after ${AUTH_COMPLETION_TIMEOUT_MS}ms."
            ).apply {
                initCause(error)
            }
        }

    private fun describePrompt(
        challengeType: SteamGuardChallengeType,
        previousCodeWasIncorrect: Boolean,
    ): String =
        when (challengeType) {
            SteamGuardChallengeType.DeviceCode ->
                "device_code" + if (previousCodeWasIncorrect) " (retry)" else " (initial)"

            SteamGuardChallengeType.EmailCode ->
                "email_code" + if (previousCodeWasIncorrect) " (retry)" else " (initial)"

            else -> challengeType.name
        }

    private fun supportedChallengeOptions(challenges: List<SteamGuardChallenge>): List<SteamGuardChallenge> =
        challenges
            .filter { challenge ->
                challenge.type == SteamGuardChallengeType.None ||
                    challenge.type == SteamGuardChallengeType.DeviceConfirmation ||
                    challenge.type == SteamGuardChallengeType.DeviceCode ||
                    challenge.type == SteamGuardChallengeType.EmailCode
            }
            .distinctBy(SteamGuardChallenge::type)
            .let { supported ->
                if (supported.any { it.type == SteamGuardChallengeType.None }) {
                    listOf(SteamGuardChallenge(SteamGuardChallengeType.None))
                } else {
                    supported
                }
            }
            .ifEmpty { listOf(SteamGuardChallenge(SteamGuardChallengeType.None)) }

    private fun selectChallenge(
        prompt: AuthPrompt,
        challenges: List<SteamGuardChallenge>,
    ): SteamGuardChallenge {
        if (challenges.size == 1) {
            return challenges.single()
        }
        val selectedType = prompt.getChallengeSelection(challenges).get()
        return challenges.firstOrNull { it.type == selectedType }
            ?: throw IllegalStateException("Steam 返回了未提供的验证方式：${selectedType.name}")
    }

    private fun summarizeChallenges(challenges: List<SteamGuardChallenge>): String =
        challenges.ifEmpty { listOf(SteamGuardChallenge(SteamGuardChallengeType.Unknown)) }
            .joinToString(", ") { challenge ->
                if (challenge.message.isNullOrBlank()) {
                    challenge.type.name
                } else {
                    "${challenge.type.name}(message)"
                }
            }

    private fun isSteamGuardCodeMismatch(error: Throwable): Boolean =
        generateSequence(error) { current -> current.cause?.takeUnless { it === current } }
            .mapNotNull { current -> current.message?.lowercase() }
            .any { message ->
                message.contains("eresult=65") ||
                    message.contains("eresult=88") ||
                    message.contains("twofactorcodemismatch") ||
                    message.contains("invalidloginauthcode")
            }

    private fun isCancellation(error: Throwable): Boolean =
        generateSequence(error) { current -> current.cause?.takeUnless { it === current } }
            .any { current -> current is CancellationException }

    private fun isValidSteamId64(value: String): Boolean =
        value.toULongOrNull()?.let { it > 0uL } == true
}

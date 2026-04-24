package io.stamethyst.backend.steamcloud

import android.content.Context

internal object SteamCloudAuthCoordinator {
    data class AuthResult(
        val accountName: String,
        val refreshToken: String,
        val guardData: String,
        val steamId64: String,
    )

    @Throws(Exception::class)
    fun authenticateWithCredentials(
        context: Context,
        username: String,
        password: String,
        existingGuardData: String,
        prompt: SteamCloudClient.AuthPrompt,
    ): AuthResult {
        val startedAtMs = System.currentTimeMillis()
        val normalizedUsername = username.trim()
        val normalizedGuardData = existingGuardData.trim().ifBlank { null }
        val client = SteamCloudClient(context)
        try {
            client.use {
                client.beginOperationDiagnostics(
                    "credentials_login",
                    normalizedUsername,
                    !normalizedGuardData.isNullOrEmpty(),
                )
                client.start()
                val authMaterial = client.authenticateWithCredentials(
                    normalizedUsername,
                    password,
                    normalizedGuardData,
                    prompt,
                )
                val steamId64 = runCatching {
                    client.logOnWithRefreshToken(authMaterial.accountName, authMaterial.refreshToken)
                    client.currentSteamId64
                }.getOrDefault("")
                SteamCloudDiagnosticsStore.writeSummary(
                    context = context,
                    operation = "credentials_login",
                    outcome = "SUCCESS",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = client.snapshotDiagnostics(),
                    extraLines = listOf(
                        "Refresh token received: ${authMaterial.refreshToken.length} chars",
                        "Guard data returned: ${if (authMaterial.guardData.isNullOrBlank()) "no" else "yes"}",
                        "SteamID64 resolved: ${if (steamId64.isBlank()) "no" else "yes"}",
                    ),
                )
                return AuthResult(
                    accountName = authMaterial.accountName,
                    refreshToken = authMaterial.refreshToken,
                    guardData = authMaterial.guardData ?: "",
                    steamId64 = steamId64,
                )
            }
        } catch (error: Throwable) {
            runCatching {
                SteamCloudDiagnosticsStore.writeSummary(
                    context = context,
                    operation = "credentials_login",
                    outcome = "FAILED",
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
}

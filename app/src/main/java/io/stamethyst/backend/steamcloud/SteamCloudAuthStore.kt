package io.stamethyst.backend.steamcloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object SteamCloudAuthStore {
    private const val LEGACY_PREFS_NAME = "steam_cloud_auth"
    private const val AUTH_STATE_FILE_NAME = "steam-cloud-auth-state.v1"
    private const val AUTH_STATE_MAGIC = "STAMETHYST_STEAM_AUTH_V1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "io.stamethyst.steamcloud.auth.v1"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val TAG = "SteamCloudAuthStore"
    private const val AUTH_STATE_READ_ATTEMPTS = 3
    private const val AUTH_STATE_READ_RETRY_DELAY_MS = 120L

    private val authenticatedData = AUTH_STATE_MAGIC.toByteArray(Charsets.UTF_8)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    data class SavedAuthMaterial(
        val accountName: String,
        val refreshToken: String,
        val guardData: String,
        val steamId64: String,
        val credentialRevision: Long,
    )

    data class SavedLoginCredentials(
        val username: String,
        val password: String,
    )

    data class AuthSnapshot(
        val accountName: String,
        val refreshTokenConfigured: Boolean,
        val guardDataConfigured: Boolean,
        val steamId64: String,
        val personaName: String,
        val avatarUrl: String,
        val lastAuthAtMs: Long?,
        val lastManifestAtMs: Long?,
        val lastPullAtMs: Long?,
        val lastPushAtMs: Long?,
        val lastError: String,
    ) {
        val isComplete: Boolean
            get() = accountName.isNotBlank() &&
                refreshTokenConfigured &&
                isValidSteamId64(steamId64)
    }

    data class AuthSnapshotRead(
        val snapshot: AuthSnapshot,
        val readFailed: Boolean,
    )

    data class CachedWebAccessToken(
        val accessToken: String,
        val expiresAtMs: Long,
    )

    @Serializable
    private data class StoredState(
        val schemaVersion: Int = 1,
        val revision: Long = 0L,
        val credentialRevision: Long = 0L,
        val activeLoginAttemptId: String = "",
        val committedLoginAttemptId: String = "",
        val loginUsername: String = "",
        val loginPassword: String = "",
        val accountName: String = "",
        val refreshToken: String = "",
        val guardData: String = "",
        val steamId64: String = "",
        val personaName: String = "",
        val avatarUrl: String = "",
        val lastAuthAtMs: Long? = null,
        val lastManifestAtMs: Long? = null,
        val lastPullAtMs: Long? = null,
        val lastPushAtMs: Long? = null,
        val lastError: String = "",
        val webAccessToken: String = "",
        val webAccessTokenExpiresAtMs: Long = 0L,
        val webAccessTokenSteamId64: String = "",
        val webAccessTokenRefreshFingerprint: String = "",
    )

    fun readAuthMaterial(context: Context): SavedAuthMaterial? =
        readStateOrNull(context)?.toAuthMaterialOrNull()

    fun readSavedLoginCredentials(context: Context): SavedLoginCredentials {
        val state = readStateOrNull(context) ?: return SavedLoginCredentials("", "")
        return SavedLoginCredentials(
            username = state.loginUsername.trim(),
            password = state.loginPassword,
        )
    }

    fun saveLoginCredentials(
        context: Context,
        username: String,
        password: String,
    ) {
        mutateState(context) { state ->
            state.copy(
                loginUsername = username.trim(),
                loginPassword = password,
            )
        }
    }

    fun readSnapshot(context: Context): AuthSnapshot =
        readSnapshotWithStatus(context).snapshot

    /**
     * Reads the auth snapshot and reports whether every read attempt failed.
     *
     * A successful load always returns a state (a logged-out account reads as a
     * blank one), so null/throwing reads only happen on transient errors such as
     * KeyStore contention while the `:steamcloud` process rewrites the encrypted
     * state file during a sync. Callers use [AuthSnapshotRead.readFailed] to keep
     * the last known login state instead of flashing "not signed in".
     */
    fun readSnapshotWithStatus(
        context: Context,
        attempts: Int = AUTH_STATE_READ_ATTEMPTS,
    ): AuthSnapshotRead {
        var lastError: Throwable? = null
        val attemptCount = attempts.coerceAtLeast(1)
        repeat(attemptCount) { attempt ->
            if (attempt > 0) {
                try {
                    Thread.sleep(AUTH_STATE_READ_RETRY_DELAY_MS * attempt)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return unreadableSnapshotRead(interrupted)
                }
            }
            runCatching { loadState(context) }
                .onSuccess { state ->
                    return AuthSnapshotRead(snapshot = state.toAuthSnapshot(), readFailed = false)
                }
                .onFailure { error ->
                    lastError = error
                }
        }
        val error = lastError
        if (error != null) {
            Log.w(TAG, "Steam Cloud auth state is unavailable; treating credentials as absent.", error)
        }
        return unreadableSnapshotRead(error)
    }

    private fun unreadableSnapshotRead(error: Throwable?): AuthSnapshotRead =
        AuthSnapshotRead(
            snapshot = AuthSnapshot(
                accountName = "",
                refreshTokenConfigured = false,
                guardDataConfigured = false,
                steamId64 = "",
                personaName = "",
                avatarUrl = "",
                lastAuthAtMs = null,
                lastManifestAtMs = null,
                lastPullAtMs = null,
                lastPushAtMs = null,
                lastError = error?.message ?: "",
            ),
            readFailed = true,
        )

    private fun StoredState.toAuthSnapshot(): AuthSnapshot =
        AuthSnapshot(
            accountName = accountName.trim(),
            refreshTokenConfigured = refreshToken.isNotBlank(),
            guardDataConfigured = guardData.isNotBlank(),
            steamId64 = steamId64.trim(),
            personaName = personaName.trim(),
            avatarUrl = avatarUrl.trim(),
            lastAuthAtMs = lastAuthAtMs?.takeIf { it > 0L },
            lastManifestAtMs = lastManifestAtMs?.takeIf { it > 0L },
            lastPullAtMs = lastPullAtMs?.takeIf { it > 0L },
            lastPushAtMs = lastPushAtMs?.takeIf { it > 0L },
            lastError = lastError.trim(),
        )

    fun beginLoginAttempt(context: Context): String {
        val attemptId = UUID.randomUUID().toString()
        val appContext = appContext(context)
        SteamCloudOperationMutex.runExclusive(appContext) {
            val state = loadStateForExplicitReset(appContext)
            writeState(
                appContext,
                state.copy(
                    revision = state.revision + 1L,
                    activeLoginAttemptId = attemptId,
                ),
            )
        }
        return attemptId
    }

    fun finishLoginAttempt(
        context: Context,
        attemptId: String,
    ) {
        val normalizedAttemptId = attemptId.trim()
        if (normalizedAttemptId.isEmpty()) {
            return
        }
        mutateState(context) { state ->
            when (resolveLoginAttemptFinish(
                activeAttemptId = state.activeLoginAttemptId,
                finishingAttemptId = normalizedAttemptId,
            )) {
                LoginAttemptFinishAction.CLEAR_ACTIVE ->
                    state.copy(activeLoginAttemptId = "")

                LoginAttemptFinishAction.IGNORE -> null
            }
        }
    }

    fun recordAuthSuccess(
        context: Context,
        loginAttemptId: String,
        cancellationHandle: SteamCloudAuthCoordinator.CancellationHandle,
        accountName: String,
        refreshToken: String,
        guardData: String,
        steamId64: String,
        personaName: String = "",
        avatarUrl: String = "",
    ) {
        val normalizedAttemptId = loginAttemptId.trim()
        val normalizedSteamId = steamId64.trim()
        require(normalizedAttemptId.isNotEmpty()) { "Steam login attempt ID is required." }
        require(isValidSteamId64(normalizedSteamId)) {
            "SteamID64 is required before Steam Cloud auth can be saved."
        }

        SteamCloudOperationMutex.runExclusive(context) {
            cancellationHandle.runIfActive {
                val state = loadStateExclusive(appContext(context))
                if (state.activeLoginAttemptId != normalizedAttemptId) {
                    throw CancellationException("Steam login attempt is no longer active.")
                }
                writeState(
                    context = appContext(context),
                    state = state.copy(
                        revision = state.revision + 1L,
                        credentialRevision = state.credentialRevision + 1L,
                        activeLoginAttemptId = "",
                        committedLoginAttemptId = normalizedAttemptId,
                        accountName = accountName.trim(),
                        refreshToken = refreshToken.trim(),
                        guardData = guardData.trim(),
                        steamId64 = normalizedSteamId,
                        personaName = personaName.trim(),
                        avatarUrl = avatarUrl.trim(),
                        lastAuthAtMs = System.currentTimeMillis(),
                        lastManifestAtMs = null,
                        lastPullAtMs = null,
                        lastPushAtMs = null,
                        lastError = "",
                        webAccessToken = "",
                        webAccessTokenExpiresAtMs = 0L,
                        webAccessTokenSteamId64 = "",
                        webAccessTokenRefreshFingerprint = "",
                    ),
                )
            }
        }
    }

    fun readCachedWebAccessToken(
        context: Context,
        steamId: Long,
        refreshToken: String,
        nowMs: Long = System.currentTimeMillis(),
        minimumRemainingLifetimeMs: Long,
    ): CachedWebAccessToken? {
        val state = readStateOrNull(context) ?: return null
        if (
            state.webAccessToken.isBlank() ||
            state.webAccessTokenExpiresAtMs <= nowMs + minimumRemainingLifetimeMs ||
            state.webAccessTokenSteamId64 != steamId.toString() ||
            state.webAccessTokenRefreshFingerprint != refreshToken.fingerprintForCacheScope()
        ) {
            return null
        }
        return CachedWebAccessToken(
            accessToken = state.webAccessToken,
            expiresAtMs = state.webAccessTokenExpiresAtMs,
        )
    }

    fun cacheWebAccessToken(
        context: Context,
        steamId: Long,
        refreshToken: String,
        accessToken: String,
        expiresAtMs: Long,
    ) {
        require(accessToken.isNotBlank()) { "Steam web access token must not be blank." }
        require(expiresAtMs > System.currentTimeMillis()) {
            "Steam web access token must expire in the future."
        }
        mutateState(context) { state ->
            val auth = state.toAuthMaterialOrNull()
            if (auth == null ||
                auth.steamId64 != steamId.toString() ||
                auth.refreshToken != refreshToken.trim()
            ) {
                return@mutateState null
            }
            state.copy(
                webAccessToken = accessToken.trim(),
                webAccessTokenExpiresAtMs = expiresAtMs,
                webAccessTokenSteamId64 = steamId.toString(),
                webAccessTokenRefreshFingerprint = refreshToken.fingerprintForCacheScope(),
            )
        }
    }

    fun recordProfile(
        context: Context,
        steamId64: String,
        personaName: String,
        avatarUrl: String,
    ) {
        val normalizedSteamId = steamId64.trim()
        mutateState(context) { state ->
            state.takeIf { it.toAuthMaterialOrNull()?.steamId64 == normalizedSteamId }
                ?.copy(
                    personaName = personaName.trim(),
                    avatarUrl = avatarUrl.trim(),
                )
        }
    }

    fun recordManifestSuccess(context: Context, fetchedAtMs: Long) {
        mutateAuthenticatedState(context) { state ->
            state.copy(lastManifestAtMs = fetchedAtMs, lastError = "")
        }
    }

    fun recordPullSuccess(context: Context, completedAtMs: Long) {
        mutateAuthenticatedState(context) { state ->
            state.copy(lastPullAtMs = completedAtMs, lastError = "")
        }
    }

    fun recordPushSuccess(context: Context, completedAtMs: Long) {
        mutateAuthenticatedState(context) { state ->
            state.copy(lastPushAtMs = completedAtMs, lastError = "")
        }
    }

    fun recordFailure(
        context: Context,
        errorMessage: String,
        expectedAuth: SavedAuthMaterial? = null,
    ) {
        mutateState(context) { state ->
            val currentAuth = state.toAuthMaterialOrNull() ?: return@mutateState null
            if (expectedAuth != null && currentAuth != expectedAuth) {
                return@mutateState null
            }
            state.copy(lastError = errorMessage.trim())
        }
    }

    fun clearGuardData(context: Context) {
        mutateAuthenticatedState(context) { state -> state.copy(guardData = "") }
    }

    fun clear(context: Context) {
        val appContext = appContext(context)
        SteamCloudOperationMutex.runExclusive(appContext) {
            val previousState = runCatching { loadStateExclusive(appContext) }.getOrNull()
            val clearedState = StoredState(
                revision = (previousState?.revision ?: 0L) + 1L,
                credentialRevision = (previousState?.credentialRevision ?: 0L) + 1L,
            )
            writeStateAfterExplicitReset(appContext, clearedState)
            clearLegacyPreferences(appContext)
        }
    }

    private fun mutateAuthenticatedState(
        context: Context,
        transform: (StoredState) -> StoredState,
    ) {
        mutateState(context) { state ->
            state.takeIf { it.toAuthMaterialOrNull() != null }?.let(transform)
        }
    }

    private fun mutateState(
        context: Context,
        transform: (StoredState) -> StoredState?,
    ) {
        val appContext = appContext(context)
        SteamCloudOperationMutex.runExclusive(appContext) {
            val state = loadStateExclusive(appContext)
            val transformed = transform(state) ?: return@runExclusive
            writeState(
                appContext,
                transformed.copy(revision = state.revision + 1L),
            )
        }
    }

    private fun loadState(context: Context): StoredState {
        val appContext = appContext(context)
        val file = authStateFile(appContext)
        if (file.isFile) {
            return readState(file)
        }
        return SteamCloudOperationMutex.runExclusive(appContext) {
            loadStateExclusive(appContext)
        }
    }

    private fun readStateOrNull(context: Context): StoredState? =
        runCatching { loadState(context) }
            .onFailure { error ->
                Log.w(TAG, "Steam Cloud auth state is unavailable; treating credentials as absent.", error)
            }
            .getOrNull()

    private fun loadStateExclusive(context: Context): StoredState {
        val file = authStateFile(context)
        if (file.isFile) {
            return readState(file)
        }

        val migratedState = readLegacyState(context)
        writeState(context, migratedState)
        clearLegacyPreferences(context)
        return migratedState
    }

    private fun loadStateForExplicitReset(context: Context): StoredState =
        runCatching { loadStateExclusive(context) }
            .getOrElse { error ->
                Log.w(TAG, "Replacing unreadable Steam Cloud auth state after an explicit user action.", error)
                val replacement = StoredState(revision = 1L)
                writeStateAfterExplicitReset(context, replacement)
                clearLegacyPreferences(context)
                replacement
            }

    private fun writeStateAfterExplicitReset(context: Context, state: StoredState) {
        try {
            writeState(context, state)
        } catch (error: Throwable) {
            if (!error.hasSecurityCause()) {
                throw error
            }
            resetStateEncryptionKey()
            writeState(context, state)
        }
    }

    private fun readState(file: File): StoredState {
        val lines = file.readLines(Charsets.UTF_8)
        if (lines.size != 3 || lines[0] != AUTH_STATE_MAGIC) {
            throw IOException("Steam Cloud auth state has an unsupported format.")
        }
        val key = existingStateEncryptionKey()
            ?: throw IOException("Steam Cloud auth encryption key is unavailable.")
        val iv = Base64.decode(lines[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(lines[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(authenticatedData)
        val plaintext = cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        return json.decodeFromString<StoredState>(plaintext).also { state ->
            if (state.schemaVersion != 1 || state.revision < 0L || state.credentialRevision < 0L) {
                throw IOException("Steam Cloud auth state metadata is invalid.")
            }
        }
    }

    private fun writeState(context: Context, state: StoredState) {
        val plaintext = json.encodeToString(state).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, stateEncryptionKey())
        cipher.updateAAD(authenticatedData)
        val ciphertext = cipher.doFinal(plaintext)
        val envelope = buildString {
            append(AUTH_STATE_MAGIC).append('\n')
            append(Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).append('\n')
            append(Base64.encodeToString(ciphertext, Base64.NO_WRAP)).append('\n')
        }
        SteamCloudAtomicFileStore.writeTextWithoutBackup(
            authStateFile(context),
            envelope,
            Charsets.UTF_8,
        )
    }

    private fun stateEncryptionKey(): SecretKey {
        existingStateEncryptionKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun existingStateEncryptionKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
    }

    private fun resetStateEncryptionKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            keyStore.deleteEntry(KEYSTORE_ALIAS)
        }
    }

    private fun readLegacyState(context: Context): StoredState {
        val legacyFile = legacyPreferencesFile(context)
        if (!legacyFile.isFile) {
            return StoredState(revision = 1L)
        }

        val prefs = createLegacyEncryptedPreferences(context)
        val accountName = prefs.getString("account_name", null)?.trim().orEmpty()
        val refreshToken = prefs.getString("refresh_token", null)?.trim().orEmpty()
        val steamId64 = prefs.getString("steam_id_64", null)?.trim().orEmpty()
        val hasCompleteAuth = accountName.isNotBlank() &&
            refreshToken.isNotBlank() &&
            isValidSteamId64(steamId64)
        return StoredState(
            revision = 1L,
            credentialRevision = if (hasCompleteAuth) 1L else 0L,
            accountName = accountName,
            refreshToken = refreshToken,
            guardData = prefs.getString("guard_data", null)?.trim().orEmpty(),
            steamId64 = steamId64,
            personaName = prefs.getString("persona_name", null)?.trim().orEmpty(),
            avatarUrl = prefs.getString("avatar_url", null)?.trim().orEmpty(),
            lastAuthAtMs = prefs.optionalLong("last_auth_at_ms"),
            lastManifestAtMs = prefs.optionalLong("last_manifest_at_ms"),
            lastPullAtMs = prefs.optionalLong("last_pull_at_ms"),
            lastPushAtMs = prefs.optionalLong("last_push_at_ms"),
            lastError = prefs.getString("last_error", null)?.trim().orEmpty(),
            webAccessToken = prefs.getString("web_access_token", null)?.trim().orEmpty(),
            webAccessTokenExpiresAtMs = prefs.getLong("web_access_token_expires_at_ms", 0L),
            webAccessTokenSteamId64 = prefs.getString("web_access_token_steam_id_64", null)?.trim().orEmpty(),
            webAccessTokenRefreshFingerprint = prefs
                .getString("web_access_token_refresh_fingerprint", null)
                ?.trim()
                .orEmpty(),
        )
    }

    private fun createLegacyEncryptedPreferences(context: Context) =
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
            .let { masterKey ->
                EncryptedSharedPreferences.create(
                    context,
                    LEGACY_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }

    private fun clearLegacyPreferences(context: Context) {
        val legacyFile = legacyPreferencesFile(context)
        val legacyBackupFile = File(legacyFile.parentFile, legacyFile.name + ".bak")
        if (!legacyFile.exists() && !legacyBackupFile.exists()) {
            return
        }
        runCatching {
            createLegacyEncryptedPreferences(context).edit().clear().commit()
        }.onFailure { error ->
            Log.w(TAG, "Unable to clear migrated Steam Cloud preferences.", error)
        }
        if (!context.deleteSharedPreferences(LEGACY_PREFS_NAME)) {
            Log.w(TAG, "Migrated Steam Cloud preferences could not be deleted.")
        }
        legacyBackupFile.delete()
    }

    private fun StoredState.toAuthMaterialOrNull(): SavedAuthMaterial? {
        val normalizedAccountName = accountName.trim()
        val normalizedRefreshToken = refreshToken.trim()
        val normalizedSteamId = steamId64.trim()
        if (normalizedAccountName.isBlank() ||
            normalizedRefreshToken.isBlank() ||
            !isValidSteamId64(normalizedSteamId)
        ) {
            return null
        }
        return SavedAuthMaterial(
            accountName = normalizedAccountName,
            refreshToken = normalizedRefreshToken,
            guardData = guardData.trim(),
            steamId64 = normalizedSteamId,
            credentialRevision = credentialRevision,
        )
    }

    private fun StoredState.withoutCredentials(
        credentialRevision: Long,
        activeLoginAttemptId: String = "",
    ): StoredState = copy(
        credentialRevision = credentialRevision,
        activeLoginAttemptId = activeLoginAttemptId,
        committedLoginAttemptId = "",
        loginUsername = "",
        loginPassword = "",
        accountName = "",
        refreshToken = "",
        guardData = "",
        steamId64 = "",
        personaName = "",
        avatarUrl = "",
        lastAuthAtMs = null,
        lastManifestAtMs = null,
        lastPullAtMs = null,
        lastPushAtMs = null,
        lastError = "",
        webAccessToken = "",
        webAccessTokenExpiresAtMs = 0L,
        webAccessTokenSteamId64 = "",
        webAccessTokenRefreshFingerprint = "",
    )

    private fun authStateFile(context: Context): File {
        val noBackupDirectory = runCatching { context.noBackupFilesDir }.getOrNull()
            ?: File(context.filesDir, "no_backup")
        return File(noBackupDirectory, AUTH_STATE_FILE_NAME)
    }

    private fun legacyPreferencesFile(context: Context): File {
        val dataDirectory = runCatching { context.applicationInfo.dataDir }.getOrNull()
            ?.let(::File)
            ?: context.filesDir.parentFile
            ?: context.filesDir
        return File(File(dataDirectory, "shared_prefs"), "$LEGACY_PREFS_NAME.xml")
    }

    private fun appContext(context: Context): Context = context.applicationContext ?: context

    private fun isValidSteamId64(value: String): Boolean =
        value.toULongOrNull()?.let { it > 0uL } == true

    private fun String.fingerprintForCacheScope(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    private fun Throwable.hasSecurityCause(): Boolean =
        generateSequence(this) { current -> current.cause?.takeUnless { it === current } }
            .any { it is GeneralSecurityException }

    private fun android.content.SharedPreferences.optionalLong(key: String): Long? {
        if (!contains(key)) {
            return null
        }
        return getLong(key, 0L).takeIf { it > 0L }
    }
}

internal enum class LoginAttemptFinishAction {
    CLEAR_ACTIVE,
    IGNORE,
}

internal fun resolveLoginAttemptFinish(
    activeAttemptId: String,
    finishingAttemptId: String,
): LoginAttemptFinishAction = when {
    activeAttemptId == finishingAttemptId -> LoginAttemptFinishAction.CLEAR_ACTIVE
    else -> LoginAttemptFinishAction.IGNORE
}

internal fun reusableGuardDataForCredentials(
    savedAccountName: String,
    savedGuardData: String,
    requestedUsername: String,
): String {
    val normalizedGuardData = savedGuardData.trim()
    if (normalizedGuardData.isBlank()) {
        return ""
    }
    val normalizedSavedAccountName = savedAccountName.trim()
    val normalizedRequestedUsername = requestedUsername.trim()
    if (normalizedSavedAccountName.isBlank() || normalizedRequestedUsername.isBlank()) {
        return ""
    }
    return normalizedGuardData.takeIf {
        normalizedSavedAccountName.equals(normalizedRequestedUsername, ignoreCase = true)
    }.orEmpty()
}

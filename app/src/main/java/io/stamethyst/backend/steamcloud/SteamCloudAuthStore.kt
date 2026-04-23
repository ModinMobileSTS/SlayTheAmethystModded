package io.stamethyst.backend.steamcloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal object SteamCloudAuthStore {
    private const val PREFS_NAME = "steam_cloud_auth"
    private const val KEY_ACCOUNT_NAME = "account_name"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_GUARD_DATA = "guard_data"
    private const val KEY_LAST_AUTH_AT_MS = "last_auth_at_ms"
    private const val KEY_LAST_MANIFEST_AT_MS = "last_manifest_at_ms"
    private const val KEY_LAST_PULL_AT_MS = "last_pull_at_ms"
    private const val KEY_LAST_PUSH_AT_MS = "last_push_at_ms"
    private const val KEY_LAST_ERROR = "last_error"

    data class SavedAuthMaterial(
        val accountName: String,
        val refreshToken: String,
        val guardData: String,
    )

    data class AuthSnapshot(
        val accountName: String,
        val refreshTokenConfigured: Boolean,
        val guardDataConfigured: Boolean,
        val lastAuthAtMs: Long?,
        val lastManifestAtMs: Long?,
        val lastPullAtMs: Long?,
        val lastPushAtMs: Long?,
        val lastError: String,
    )

    fun readAuthMaterial(context: Context): SavedAuthMaterial? {
        val prefs = prefs(context)
        val accountName = prefs.getString(KEY_ACCOUNT_NAME, null)?.trim().orEmpty()
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)?.trim().orEmpty()
        if (accountName.isBlank() || refreshToken.isBlank()) {
            return null
        }
        return SavedAuthMaterial(
            accountName = accountName,
            refreshToken = refreshToken,
            guardData = prefs.getString(KEY_GUARD_DATA, null)?.trim().orEmpty(),
        )
    }

    fun readSnapshot(context: Context): AuthSnapshot {
        val prefs = prefs(context)
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)?.trim().orEmpty()
        val guardData = prefs.getString(KEY_GUARD_DATA, null)?.trim().orEmpty()
        return AuthSnapshot(
            accountName = prefs.getString(KEY_ACCOUNT_NAME, null)?.trim().orEmpty(),
            refreshTokenConfigured = refreshToken.isNotBlank(),
            guardDataConfigured = guardData.isNotBlank(),
            lastAuthAtMs = prefs.optionalLong(KEY_LAST_AUTH_AT_MS),
            lastManifestAtMs = prefs.optionalLong(KEY_LAST_MANIFEST_AT_MS),
            lastPullAtMs = prefs.optionalLong(KEY_LAST_PULL_AT_MS),
            lastPushAtMs = prefs.optionalLong(KEY_LAST_PUSH_AT_MS),
            lastError = prefs.getString(KEY_LAST_ERROR, null)?.trim().orEmpty(),
        )
    }

    fun recordAuthSuccess(
        context: Context,
        accountName: String,
        refreshToken: String,
        guardData: String,
    ) {
        prefs(context)
            .edit()
            .putString(KEY_ACCOUNT_NAME, accountName.trim())
            .putString(KEY_REFRESH_TOKEN, refreshToken.trim())
            .putString(KEY_GUARD_DATA, guardData.trim())
            .putLong(KEY_LAST_AUTH_AT_MS, System.currentTimeMillis())
            .remove(KEY_LAST_MANIFEST_AT_MS)
            .remove(KEY_LAST_PULL_AT_MS)
            .remove(KEY_LAST_PUSH_AT_MS)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordManifestSuccess(context: Context, fetchedAtMs: Long) {
        prefs(context)
            .edit()
            .putLong(KEY_LAST_MANIFEST_AT_MS, fetchedAtMs)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordPullSuccess(context: Context, completedAtMs: Long) {
        prefs(context)
            .edit()
            .putLong(KEY_LAST_PULL_AT_MS, completedAtMs)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordPushSuccess(context: Context, completedAtMs: Long) {
        prefs(context)
            .edit()
            .putLong(KEY_LAST_PUSH_AT_MS, completedAtMs)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordFailure(context: Context, errorMessage: String) {
        prefs(context)
            .edit()
            .putString(KEY_LAST_ERROR, errorMessage.trim())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context)
            .edit()
            .clear()
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun SharedPreferences.optionalLong(key: String): Long? {
        if (!contains(key)) {
            return null
        }
        return getLong(key, 0L).takeIf { it > 0L }
    }
}

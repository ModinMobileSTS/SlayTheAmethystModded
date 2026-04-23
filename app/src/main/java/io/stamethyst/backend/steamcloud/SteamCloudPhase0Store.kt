package io.stamethyst.backend.steamcloud

import android.content.Context

internal object SteamCloudPhase0Store {
    private const val PREFS_NAME = "steam_cloud_phase0"
    private const val KEY_ACCOUNT_NAME = "account_name"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_PROXY_URL = "proxy_url"
    private const val KEY_LAST_PROBE_AT_MS = "last_probe_at_ms"
    private const val KEY_LAST_PROBE_SUCCESS = "last_probe_success"
    private const val KEY_LAST_FILE_COUNT = "last_file_count"
    private const val KEY_LAST_PREFERENCES_COUNT = "last_preferences_count"
    private const val KEY_LAST_SAVES_COUNT = "last_saves_count"
    private const val KEY_LAST_OUTPUT_PATH = "last_output_path"
    private const val KEY_LAST_LISTING_PATH = "last_listing_path"
    private const val KEY_LAST_ERROR = "last_error"

    data class Credentials(
        val accountName: String,
        val refreshToken: String,
        val proxyUrl: String,
    )

    data class Snapshot(
        val accountName: String,
        val refreshTokenLength: Int,
        val proxyUrl: String,
        val lastProbeAtMs: Long?,
        val lastProbeSuccess: Boolean?,
        val lastFileCount: Int?,
        val lastPreferencesCount: Int?,
        val lastSavesCount: Int?,
        val lastOutputPath: String,
        val lastListingPath: String,
        val lastError: String,
    ) {
        val hasRefreshToken: Boolean
            get() = refreshTokenLength > 0
    }

    fun readCredentials(context: Context): Credentials? {
        val prefs = prefs(context)
        val accountName = prefs.getString(KEY_ACCOUNT_NAME, null)?.trim().orEmpty()
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)?.trim().orEmpty()
        if (accountName.isEmpty() || refreshToken.isEmpty()) {
            return null
        }
        return Credentials(
            accountName = accountName,
            refreshToken = refreshToken,
            proxyUrl = prefs.getString(KEY_PROXY_URL, null)?.trim().orEmpty(),
        )
    }

    fun readSnapshot(context: Context): Snapshot {
        val prefs = prefs(context)
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)?.trim().orEmpty()
        val lastProbeAtMs = prefs.takeIf { it.contains(KEY_LAST_PROBE_AT_MS) }
            ?.getLong(KEY_LAST_PROBE_AT_MS, 0L)
            ?.takeIf { it > 0L }
        val lastProbeSuccess = prefs.takeIf { it.contains(KEY_LAST_PROBE_SUCCESS) }
            ?.getBoolean(KEY_LAST_PROBE_SUCCESS, false)
        val lastFileCount = prefs.takeIf { it.contains(KEY_LAST_FILE_COUNT) }
            ?.getInt(KEY_LAST_FILE_COUNT, 0)
        val lastPreferencesCount = prefs.takeIf { it.contains(KEY_LAST_PREFERENCES_COUNT) }
            ?.getInt(KEY_LAST_PREFERENCES_COUNT, 0)
        val lastSavesCount = prefs.takeIf { it.contains(KEY_LAST_SAVES_COUNT) }
            ?.getInt(KEY_LAST_SAVES_COUNT, 0)
        return Snapshot(
            accountName = prefs.getString(KEY_ACCOUNT_NAME, null)?.trim().orEmpty(),
            refreshTokenLength = refreshToken.length,
            proxyUrl = prefs.getString(KEY_PROXY_URL, null)?.trim().orEmpty(),
            lastProbeAtMs = lastProbeAtMs,
            lastProbeSuccess = lastProbeSuccess,
            lastFileCount = lastFileCount,
            lastPreferencesCount = lastPreferencesCount,
            lastSavesCount = lastSavesCount,
            lastOutputPath = prefs.getString(KEY_LAST_OUTPUT_PATH, null)?.trim().orEmpty(),
            lastListingPath = prefs.getString(KEY_LAST_LISTING_PATH, null)?.trim().orEmpty(),
            lastError = prefs.getString(KEY_LAST_ERROR, null)?.trim().orEmpty(),
        )
    }

    fun saveCredentials(
        context: Context,
        accountName: String,
        refreshToken: String?,
        proxyUrl: String,
    ) {
        val normalizedAccountName = accountName.trim()
        val normalizedRefreshToken = refreshToken?.trim()
        val normalizedProxyUrl = proxyUrl.trim()
        prefs(context)
            .edit()
            .putString(KEY_ACCOUNT_NAME, normalizedAccountName)
            .putString(KEY_PROXY_URL, normalizedProxyUrl)
            .apply {
                if (normalizedRefreshToken != null) {
                    putString(KEY_REFRESH_TOKEN, normalizedRefreshToken)
                }
            }
            .apply()
    }

    fun clearCredentials(context: Context) {
        prefs(context)
            .edit()
            .remove(KEY_ACCOUNT_NAME)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_PROXY_URL)
            .apply()
    }

    fun recordSuccess(
        context: Context,
        result: SteamCloudPhase0ManifestProbe.Result,
    ) {
        prefs(context)
            .edit()
            .putLong(KEY_LAST_PROBE_AT_MS, result.completedAtMs)
            .putBoolean(KEY_LAST_PROBE_SUCCESS, true)
            .putInt(KEY_LAST_FILE_COUNT, result.fileCount)
            .putInt(KEY_LAST_PREFERENCES_COUNT, result.preferencesCount)
            .putInt(KEY_LAST_SAVES_COUNT, result.savesCount)
            .putString(KEY_LAST_OUTPUT_PATH, result.summaryFile.absolutePath)
            .putString(KEY_LAST_LISTING_PATH, result.listingFile.absolutePath)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordFailure(
        context: Context,
        errorMessage: String,
        outputPath: String? = null,
    ) {
        prefs(context)
            .edit()
            .putLong(KEY_LAST_PROBE_AT_MS, System.currentTimeMillis())
            .putBoolean(KEY_LAST_PROBE_SUCCESS, false)
            .putString(KEY_LAST_ERROR, errorMessage.trim())
            .apply {
                if (!outputPath.isNullOrBlank()) {
                    putString(KEY_LAST_OUTPUT_PATH, outputPath.trim())
                } else {
                    remove(KEY_LAST_OUTPUT_PATH)
                }
                remove(KEY_LAST_LISTING_PATH)
            }
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

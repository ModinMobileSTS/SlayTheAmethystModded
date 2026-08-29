package io.stamethyst.backend.steamcloud

import android.content.Context
import android.util.Log
import io.stamethyst.config.RuntimePaths
import java.io.File

object SteamCloudLegacySensitiveDataCleanup {
    private const val TAG = "SteamCloudLegacyCleanup"
    private val retiredPreferenceName = "steam_cloud_phase" + 0
    private val retiredOutputDirectoryName = "steam-cloud-phase" + 0

    @JvmStatic
    fun clear(context: Context) {
        val appContext = context.applicationContext ?: context
        runCatching { appContext.deleteSharedPreferences(retiredPreferenceName) }
            .onFailure { error ->
                Log.w(TAG, "Unable to remove retired Steam Cloud credential storage.", error)
            }
        val sharedPreferencesDirectory = File(appContext.applicationInfo.dataDir, "shared_prefs")
        File(sharedPreferencesDirectory, "$retiredPreferenceName.xml.bak").delete()
        val outputDirectory = File(
            RuntimePaths.storageRoot(appContext),
            retiredOutputDirectoryName,
        )
        if (outputDirectory.exists() && !outputDirectory.deleteRecursively()) {
            Log.w(TAG, "Unable to remove retired Steam Cloud diagnostic output.")
        }
    }
}

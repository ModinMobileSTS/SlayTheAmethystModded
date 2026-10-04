package io.stamethyst.backend.steam

import io.stamethyst.config.CloudControlSettings
import java.security.GeneralSecurityException

/** Startup loads bundled/cached settings already. Only missing keys need a blocking refresh. */
internal class SteamStsDepotKeyResolver(
    private val currentSettings: () -> CloudControlSettings,
    private val refreshSettings: suspend () -> CloudControlSettings,
) {
    private var refreshedSettings: CloudControlSettings? = null

    suspend fun cloudKey(candidate: SteamStsDepotCandidate): ByteArray? {
        refreshedSettings?.key(candidate)?.let { return it }
        currentSettings().key(candidate)?.let { return it }
        return refreshOnce().key(candidate)
    }

    suspend fun <T> withCloudKey(candidate: SteamStsDepotCandidate, download: suspend (ByteArray?) -> T): T {
        val key = cloudKey(candidate)
        try {
            return download(key)
        } catch (error: Exception) {
            // A syntactically valid cached key can still be obsolete. Do not turn a
            // CDN/network failure into another cloud-control request, however.
            if (generateSequence<Throwable>(error) { it.cause }.none { it is GeneralSecurityException }) throw error
            val updated = refreshOnce().key(candidate)
            if (updated == null || updated.contentEquals(key)) throw error
            return download(updated)
        }
    }

    private suspend fun refreshOnce(): CloudControlSettings =
        refreshedSettings ?: refreshSettings().also { refreshedSettings = it }

    private fun CloudControlSettings.key(candidate: SteamStsDepotCandidate): ByteArray? =
        steamDepotKeyBytes(candidate.appId, candidate.depotId)
}

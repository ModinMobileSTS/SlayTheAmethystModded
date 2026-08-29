package io.stamethyst.backend.steamcloud

import java.security.MessageDigest
import java.util.Locale

internal object SteamCloudManifestIdentity {
    fun compute(snapshot: SteamCloudManifestSnapshot): String {
        val digest = MessageDigest.getInstance("SHA-256")
        snapshot.entriesForPlanning
            .sortedWith(
                compareBy<SteamCloudManifestEntry>(
                    { normalizeRemotePath(it.remotePath) },
                    { it.localRelativePath },
                )
            )
            .forEach { entry ->
                digest.updateString(normalizeRemotePath(entry.remotePath))
                digest.updateString(entry.localRelativePath)
                digest.updateString(entry.rootKind.name)
                digest.updateLong(entry.rawSize)
                digest.updateLong(entry.timestamp)
                digest.updateString(entry.machineName.trim().lowercase(Locale.ROOT))
                digest.updateString(entry.persistState.trim().lowercase(Locale.ROOT))
                digest.updateString(entry.sha1.trim().lowercase(Locale.ROOT))
            }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
    }

    private fun normalizeRemotePath(path: String): String =
        path.trim().replace('\\', '/').lowercase(Locale.ROOT)

    private fun MessageDigest.updateString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        updateInt(bytes.size)
        update(bytes)
    }

    private fun MessageDigest.updateLong(value: Long) {
        for (shift in 56 downTo 0 step 8) {
            update((value ushr shift).toByte())
        }
    }

    private fun MessageDigest.updateInt(value: Int) {
        for (shift in 24 downTo 0 step 8) {
            update((value ushr shift).toByte())
        }
    }
}

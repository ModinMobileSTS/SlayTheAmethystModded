package io.stamethyst.backend.steamcloud

import java.security.MessageDigest

/** Content identity for a confirmation, including unchanged files and deletions. */
internal object SteamCloudLocalSnapshotIdentity {
    fun compute(entries: List<SteamCloudLocalFileSnapshotEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.sortedBy { it.localRelativePath }.forEach { entry ->
            listOf(entry.localRelativePath, entry.fileSize.toString(), entry.sha256).forEach { value ->
                digest.update(value.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

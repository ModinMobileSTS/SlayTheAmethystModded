package io.stamethyst.backend.steamcloud

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal class SteamCloudStalePlanException(message: String) : IOException(message)
internal class SteamCloudPushReconciliationException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Written BEFORE remote writes. Unknown outcomes are inspected, never replayed automatically. */
internal class SteamCloudRemoteCommitStore(private val root: File) {
    @Serializable data class Pending(
        val operationId: String = UUID.randomUUID().toString(),
        val steamId64: String,
        val localEntries: List<SteamCloudLocalFileSnapshotEntry>,
        val priorBaseline: SteamCloudSyncBaseline?,
        val expectedRemoteEntries: List<SteamCloudManifestEntry>,
        val deletedPaths: Set<String>,
        val requiresLocalInstall: Boolean = false,
    )
    private val json = Json { ignoreUnknownKeys = false; prettyPrint = true }
    private val file = File(root, "remote-pending-v2.json")
    fun read(): Pending? = if (!file.exists()) null else json.decodeFromString(file.readText())
    fun write(pending: Pending) = SteamCloudAtomicFileStore.writeTextWithoutBackup(file, json.encodeToString(pending))
    fun clear() {
        if (file.exists()) {
            if (!file.delete()) throw IOException("Cannot clear remote transaction: $file")
            SteamCloudAtomicFileStore.syncDirectory(root)
        }
    }
    fun preserveForExplicitResolution() {
        if (file.exists() && !file.renameTo(File(root, "uncertain-remote-${UUID.randomUUID()}.json"))) {
            throw IOException("Cannot preserve uncertain remote commit")
        }
        SteamCloudAtomicFileStore.syncDirectory(root)
    }
    fun verify(pending: Pending, remote: SteamCloudManifestSnapshot): Boolean {
        SteamCloudSyncPlanner.validateManifest(remote)
        if (pending.steamId64 != remote.steamId64) return false
        val current = remote.entries.associateBy { it.localRelativePath }
        val expected = pending.expectedRemoteEntries.associateBy { it.localRelativePath }
        return current.keys == expected.keys && expected.all { (path, entry) ->
            SteamCloudSyncPlanner.sameRemote(entry, current[path])
        }
    }
}

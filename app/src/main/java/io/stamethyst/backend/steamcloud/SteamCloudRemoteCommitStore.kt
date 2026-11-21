package io.stamethyst.backend.steamcloud

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal class SteamCloudStalePlanException(message: String) : IOException(message)
internal class SteamCloudPushReconciliationException(message: String, cause: Throwable? = null) : IOException(message, cause)
internal class SteamCloudJournalPersistenceException(cause: Exception) :
    IOException("Cannot safely access or persist the cloud write journal; recovery is required.", cause)

/** Written BEFORE remote writes. Unknown outcomes are inspected, never replayed automatically. */
internal class SteamCloudRemoteCommitStore(
    private val root: File,
    val steamId: String,
    private val syncDirectory: (File) -> Unit = SteamCloudAtomicFileStore::syncDirectory,
) {
    @Serializable data class Pending(
        @Required val operationId: String = UUID.randomUUID().toString(),
        val steamId64: String,
        val localEntries: List<SteamCloudLocalFileSnapshotEntry>,
        val priorBaseline: SteamCloudSyncBaseline?,
        val expectedRemoteEntries: List<SteamCloudManifestEntry>,
        val deletedPaths: Set<String>,
        val requiresLocalInstall: Boolean = false,
    )
    private val json = Json { ignoreUnknownKeys = false; prettyPrint = true }
    companion object {
        const val LEGACY_FILE_NAME = "remote-pending-v2.json"
        fun checkedSteamId(value: String): String {
            require(value.toULongOrNull()?.takeIf { it > 0uL }?.toString() == value) { "Invalid SteamID64" }
            return value
        }
    }
    val file = File(root, "remote-pending-v3-${checkedSteamId(steamId)}.json")
    private val legacy = File(root, LEGACY_FILE_NAME)

    private inline fun <T> persist(block: () -> T): T = try { block() }
        catch (e: SteamCloudJournalPersistenceException) { throw e }
        catch (e: Exception) { throw SteamCloudJournalPersistenceException(e) }

    // exists() also returns false on some I/O failures. Only ENOENT is evidence of absence.
    private fun present(path: File): Boolean = try {
        check(Files.readAttributes(path.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isRegularFile)
        true
    } catch (_: NoSuchFileException) { false }
    private fun decode(path: File) = json.decodeFromString<Pending>(path.readText()).also {
        checkedSteamId(it.steamId64)
        check(UUID.fromString(it.operationId).toString() == it.operationId)
        check(it.priorBaseline == null || it.priorBaseline.steamId64 == it.steamId64)
    }
    private fun scoped() = if (present(file)) decode(file).also { check(it.steamId64 == steamId) } else null

    /** Diagnostics must not migrate, resolve, or mistake another account's intent for this one. */
    fun hasPending(): Boolean = persist { scoped() != null || (present(legacy) && decode(legacy).steamId64 == steamId) }

    /** Under the operation mutex. Only the legacy owner moves its readable journal. Atomic
     * same-directory rename preserves the bytes; never overwrite even a damaged scoped file. */
    fun read(): Pending? = persist {
        val current = scoped()
        if (!present(legacy)) return@persist current
        val old = decode(legacy)
        if (old.steamId64 != steamId) return@persist current
        check(current == null) { "Both legacy and account journals exist; keeping both for recovery" }
        Files.move(legacy.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(root)
        old
    }
    private fun write(pending: Pending) = persist {
        check(pending.steamId64 == steamId)
        SteamCloudAtomicFileStore.writeTextWithoutBackup(file, json.encodeToString(pending))
    }

    /** Called under the operation lock. The first write RPC must be OUTSIDE this scope. */
    fun prepareBeforeBegin(pending: Pending, previous: Pending?, checkCancelled: () -> Unit) {
        persist {
            check(pending.steamId64 == steamId && read() == previous) { "Cloud write journal changed before begin" }
        }
        var installed = false
        try {
            write(pending)
            installed = true
            checkCancelled()
        } catch (error: Exception) {
            // Even a failed write can have installed the new file (rename followed by fsync).
            // Only undo our own intent, and never lose an older, still-uncertain operation.
            val interrupted = Thread.interrupted()
            try {
                persist {
                    val actual = scoped()
                    if (actual == pending) {
                        if (previous == null) clear(pending) else write(previous)
                    } else {
                        // A failed initial write may have left its predecessor untouched.
                        // Otherwise we cannot prove cleanup; preserve the unexpected record.
                        check(!installed && actual == previous) { "Cloud write journal changed during cleanup" }
                    }
                }
            } catch (cleanup: Exception) {
                cleanup.addSuppressed(error)
                throw cleanup
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
            throw error
        }
    }

    fun clear(expected: Pending) = persist {
        check(expected.steamId64 == steamId && scoped() == expected)
        Files.delete(file.toPath())
        syncDirectory(root)
    }
    fun finishVerifiedRecovery(expected: Pending, saveBaseline: () -> Unit) = persist {
        saveBaseline()
        clear(expected)
    }
    fun preserveForExplicitResolution(pending: Pending) = persist {
        // Keep the active journal until atomically replaced by a new intent, or until
        // a cloud-wins local install actually commits. Process death must not unblock merge.
        check(pending.steamId64 == steamId)
        val archive = File(root, "uncertain-remote-$steamId-${UUID.fromString(pending.operationId)}.json")
        SteamCloudAtomicFileStore.writeTextWithoutBackup(archive, json.encodeToString(pending))
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

package io.stamethyst.backend.steamcloud

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal data class SteamCloudPathReplacement(
    val stagedPath: File?,
    val targetPath: File,
    val rollbackJsonKeys: Set<String> = emptySet(),
)

internal class SteamCloudRecoveryException(val recoveryRoot: File, cause: Throwable) : IOException(
    "Save recovery is required; original data preserved in ${recoveryRoot.absolutePath}", cause,
)

/** Write-ahead, copy-before-replace transaction, including metadata in the same recovery unit.
 * Recovery is idempotent: backups are never consumed until COMMITTED/ROLLED_BACK is durable.
 */
internal class SteamCloudFileTransaction private constructor(
    private val directory: File,
    private val allowedRoot: File,
    private var journal: Journal,
) {
    @Serializable internal data class Entry(val target: String, val hadOriginal: Boolean,
        val hasReplacement: Boolean, val rollbackJsonKeys: Set<String> = emptySet())
    @Serializable internal data class Journal(val state: String, val entries: List<Entry>, val retainBackup: Boolean = false)

    fun apply(afterReplacement: (Int) -> Unit = {}) {
        check(journal.state == "PREPARED")
        write(journal.copy(state = "APPLYING"))
        journal.entries.forEachIndexed { i, entry ->
            val source = File(directory, "new/$i")
            if (entry.hasReplacement && !source.exists()) throw IOException("Staged transaction data is missing: $source")
            replace(resolve(entry.target), source.takeIf { entry.hasReplacement }, i)
            afterReplacement(i)
        }
        write(journal.copy(state = "APPLIED"))
    }

    fun commit(retainBackup: Boolean = false) {
        check(journal.state == "APPLIED")
        write(journal.copy(state = "COMMITTED", retainBackup = retainBackup))
        // Cleanup failure must not turn a durable success into a reported sync failure.
        finishCommitted()
    }

    private fun finishCommitted() {
        if (journal.retainBackup) {
            val history = File(directory.parentFile.parentFile, "history-v2")
            if (history.isDirectory || history.mkdirs()) directory.renameTo(File(history, directory.name))
        } else directory.deleteRecursively()
    }

    fun rollback() {
        if (journal.state == "COMMITTED" || journal.state == "ROLLED_BACK") return
        if (journal.state != "PREPARED") {
            write(journal.copy(state = "ROLLING_BACK"))
            journal.entries.forEachIndexed { i, entry ->
                val backup = File(directory, "old/$i")
                if (entry.hadOriginal && !backup.exists()) throw IOException("Recovery backup missing: $backup")
                val target = resolve(entry.target)
                val restore = if (entry.rollbackJsonKeys.isNotEmpty() && backup.isFile && target.isFile) {
                    // Mode belongs to the save transaction. A later disable/background toggle
                    // does not: preserve those independently updated controls during recovery.
                    val original = json.parseToJsonElement(backup.readText()).jsonObject
                    val merged = json.parseToJsonElement(target.readText()).jsonObject.toMutableMap()
                    entry.rollbackJsonKeys.forEach { key ->
                        merged.remove(key)
                        original[key]?.let { merged[key] = it }
                    }
                    File(directory, "restore-$i.json").also {
                        SteamCloudAtomicFileStore.writeTextWithoutBackup(it, JsonObject(merged).toString())
                    }
                } else backup.takeIf { entry.hadOriginal }
                replace(target, restore, i)
            }
        }
        write(journal.copy(state = "ROLLED_BACK"))
        directory.deleteRecursively()
    }

    private fun replace(target: File, source: File?, index: Int) {
        val install = File(target.parentFile, ".${target.name}.sync-install-${directory.name}-$index")
        if (install.exists() && !install.deleteRecursively()) throw IOException("Cannot clear $install")
        if (source != null) copyPath(source, install)
        if (target.exists() && !target.deleteRecursively()) throw IOException("Cannot replace $target")
        if (source != null && !install.renameTo(target)) throw IOException("Cannot install $target")
        if (target.parentFile.exists()) syncDirectory(target.parentFile)
    }

    private fun resolve(path: String): File = checkedTarget(allowedRoot, File(allowedRoot, path))
    private fun write(next: Journal) {
        SteamCloudAtomicFileStore.writeTextWithoutBackup(File(directory, "journal.json"), json.encodeToString(next))
        journal = next
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = false }
        fun prepare(parent: File, allowedRoot: File, replacements: List<SteamCloudPathReplacement>): SteamCloudFileTransaction {
            require(replacements.isNotEmpty())
            val directory = File(parent, UUID.randomUUID().toString())
            check(directory.mkdirs()) { "Cannot create transaction: $directory" }
            val targets = replacements.map { checkedTarget(allowedRoot, it.targetPath) }
            require(targets.distinct().size == targets.size) { "Duplicate transaction targets" }
            targets.forEach { target ->
                require(targets.none { it != target && target.toPath().startsWith(it.toPath()) }) { "Overlapping transaction targets" }
            }
            // No live changes occur until all old and new data and the journal are durable.
            val entries = replacements.mapIndexed { i, replacement ->
                val target = targets[i]
                if (target.exists()) copyPath(target, File(directory, "old/$i"))
                replacement.stagedPath?.let { source ->
                    if (!source.exists()) throw IOException("Staged save is missing: $source")
                    copyPath(source, File(directory, "new/$i"))
                }
                Entry(target.relativeTo(allowedRoot.canonicalFile).invariantSeparatorsPath, target.exists(),
                    replacement.stagedPath != null, replacement.rollbackJsonKeys)
            }
            syncDirectory(directory)
            syncDirectory(parent)
            return SteamCloudFileTransaction(directory, allowedRoot.canonicalFile, Journal("PREPARED", entries)).also {
                it.write(it.journal)
            }
        }

        fun recoverAll(parent: File, allowedRoot: File) {
            if (!parent.exists()) return
            val directories = parent.listFiles() ?: throw IOException("Cannot enumerate recovery transactions")
            directories.filter { it.isDirectory }.forEach { directory ->
                try {
                    val file = File(directory, "journal.json")
                    // An interrupted prepare never modified targets.
                    if (!file.exists()) { directory.deleteRecursively(); return@forEach }
                    val journal = json.decodeFromString<Journal>(file.readText())
                    require(journal.state in setOf("PREPARED", "APPLYING", "APPLIED", "COMMITTED", "ROLLING_BACK", "ROLLED_BACK"))
                    journal.entries.forEach { checkedTarget(allowedRoot, File(allowedRoot, it.target)) }
                    val transaction = SteamCloudFileTransaction(directory, allowedRoot.canonicalFile, journal)
                    if (journal.state == "COMMITTED") transaction.finishCommitted()
                    else if (journal.state == "ROLLED_BACK") directory.deleteRecursively()
                    else transaction.rollback()
                } catch (error: Exception) {
                    throw SteamCloudRecoveryException(directory, error)
                }
            }
        }

        fun execute(parent: File, allowedRoot: File, replacements: List<SteamCloudPathReplacement>,
            retainBackup: Boolean = false, validate: () -> Unit = {}) {
            val transaction = prepare(parent, allowedRoot, replacements)
            try { transaction.apply(); validate(); transaction.commit(retainBackup) }
            catch (error: Exception) {
                // Interrupted FileChannels cannot fsync. Cancellation is honored before apply,
                // but rollback must be able to durably restore data before propagating it.
                val interrupted = Thread.interrupted()
                try { transaction.rollback() }
                catch (recovery: Exception) { recovery.addSuppressed(error); throw SteamCloudRecoveryException(transaction.directory, recovery) }
                finally { if (interrupted) Thread.currentThread().interrupt() }
                throw error
            }
        }

        fun copyPath(source: File, target: File) {
            if (Files.isSymbolicLink(source.toPath())) throw IOException("Save symlinks are not supported: $source")
            check(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "Cannot create ${target.parentFile}" }
            if (source.isDirectory) {
                check(target.isDirectory || target.mkdirs())
                (source.listFiles() ?: throw IOException("Cannot enumerate $source")).forEach { copyPath(it, File(target, it.name)) }
                syncDirectory(target)
            } else if (source.isFile) {
                source.inputStream().use { input -> FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() } }
                target.setLastModified(source.lastModified())
            } else throw IOException("Missing or unsupported save path: $source")
            syncDirectory(target.parentFile)
        }

        private fun syncDirectory(directory: File) {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }

        private fun checkedTarget(root: File, target: File): File {
            val canonical = target.canonicalFile
            require(canonical != root.canonicalFile && canonical.toPath().startsWith(root.canonicalFile.toPath())) { "Unsafe transaction target: $target" }
            require(target.absoluteFile.toPath().normalize() == canonical.toPath()) { "Symlink transaction target: $target" }
            return canonical
        }
    }
}

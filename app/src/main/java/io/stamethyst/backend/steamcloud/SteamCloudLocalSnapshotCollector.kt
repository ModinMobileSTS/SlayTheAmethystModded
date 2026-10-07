package io.stamethyst.backend.steamcloud

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.nio.file.Files
import java.util.Locale

internal object SteamCloudLocalSnapshotCollector {
    /** Verify only the newly downloaded file, not every preceding download. */
    @Throws(IOException::class)
    fun collectFile(stsRoot: File, localRelativePath: String): SteamCloudLocalFileSnapshotEntry {
        val mapped = SteamCloudPathMapper.mapLocalRelativePath(localRelativePath)
            ?: throw IOException("Unsafe managed save path: $localRelativePath")
        var file = stsRoot
        if (Files.isSymbolicLink(file.toPath())) throw IOException("Save symlinks are not supported: $file")
        localRelativePath.split('/').forEach { segment ->
            file = File(file, segment)
            if (Files.isSymbolicLink(file.toPath())) throw IOException("Save symlinks are not supported: $file")
        }
        if (!file.isFile) throw IOException("Missing or unsupported save file: $file")
        return snapshot(file, mapped.rootKind, mapped.localRelativePath)
    }

    @Throws(IOException::class)
    fun collect(stsRoot: File): List<SteamCloudLocalFileSnapshotEntry> {
        val entries = mutableListOf<SteamCloudLocalFileSnapshotEntry>()
        SteamCloudRootKind.entries.forEach { rootKind ->
            collectRootEntries(
                rootKind = rootKind,
                rootDir = File(stsRoot, rootKind.directoryName),
                sink = entries,
            )
        }
        return entries.sortedWith(
            compareBy<SteamCloudLocalFileSnapshotEntry>(
                { it.localRelativePath.lowercase(Locale.ROOT) },
                { it.localRelativePath },
            )
        )
    }

    private fun collectRootEntries(
        rootKind: SteamCloudRootKind,
        rootDir: File,
        sink: MutableList<SteamCloudLocalFileSnapshotEntry>,
    ) {
        if (Files.isSymbolicLink(rootDir.toPath())) throw IOException("Save symlinks are not supported: $rootDir")
        if (!rootDir.exists()) {
            return
        }
        if (!rootDir.isDirectory) {
            throw IOException("Steam Cloud local root is not a directory: ${rootDir.absolutePath}")
        }
        val files = mutableListOf<File>()
        collectFiles(rootDir, files)
        files
            .sortedWith(compareBy<File>({ it.relativeTo(rootDir).path.lowercase(Locale.ROOT) }, { it.path }))
            .forEach { file ->
            val relativeSuffix = file.relativeTo(rootDir).invariantSeparatorsPath
            if (relativeSuffix.isBlank()) {
                return@forEach
            }
            sink += snapshot(file, rootKind, rootKind.directoryName + "/" + relativeSuffix)
        }
    }

    private fun collectFiles(directory: File, sink: MutableList<File>) {
        val children = directory.listFiles()
            ?: throw IOException("Failed to enumerate Steam Cloud local directory: ${directory.absolutePath}")
        children.forEach { child ->
            if (Files.isSymbolicLink(child.toPath())) throw IOException("Save symlinks are not supported: $child")
            when {
                child.isDirectory -> collectFiles(child, sink)
                child.isFile -> sink += child
                else -> throw IOException(
                    "Steam Cloud local path disappeared or has an unsupported type: ${child.absolutePath}"
                )
            }
        }
    }

    private fun snapshot(file: File, rootKind: SteamCloudRootKind, localRelativePath: String): SteamCloudLocalFileSnapshotEntry {
        val digests = digestFile(file)
        return SteamCloudLocalFileSnapshotEntry(localRelativePath, rootKind, file.length(),
            file.lastModified().coerceAtLeast(0L), digests.sha256, digests.sha1)
    }

    private fun digestFile(file: File): FileDigests {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val sha1 = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                sha256.update(buffer, 0, read)
                sha1.update(buffer, 0, read)
            }
        }
        return FileDigests(
            sha256 = digestToHex(sha256),
            sha1 = digestToHex(sha1),
        )
    }

    private fun digestToHex(digest: MessageDigest): String =
        digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(Locale.US, byte.toInt() and 0xFF)
        }

    private data class FileDigests(
        val sha256: String,
        val sha1: String,
    )
}

package io.stamethyst.backend.steamcloud

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

internal object SteamCloudLocalSnapshotCollector {
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
            val fileDigests = digestFile(file)
            sink += SteamCloudLocalFileSnapshotEntry(
                localRelativePath = rootKind.directoryName + "/" + relativeSuffix,
                rootKind = rootKind,
                fileSize = file.length(),
                lastModifiedMs = file.lastModified().coerceAtLeast(0L),
                sha256 = fileDigests.sha256,
                sha1 = fileDigests.sha1,
            )
        }
    }

    private fun collectFiles(directory: File, sink: MutableList<File>) {
        val children = directory.listFiles()
            ?: throw IOException("Failed to enumerate Steam Cloud local directory: ${directory.absolutePath}")
        children.forEach { child ->
            when {
                child.isDirectory -> collectFiles(child, sink)
                child.isFile -> sink += child
                else -> throw IOException(
                    "Steam Cloud local path disappeared or has an unsupported type: ${child.absolutePath}"
                )
            }
        }
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

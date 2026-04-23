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
        if (!rootDir.isDirectory) {
            return
        }
        val files = rootDir.walkTopDown()
            .filter { it.isFile }
            .toList()
            .sortedWith(compareBy<File>({ it.relativeTo(rootDir).path.lowercase(Locale.ROOT) }, { it.path }))
        for (file in files) {
            val relativeSuffix = file.relativeTo(rootDir).invariantSeparatorsPath
            if (relativeSuffix.isBlank()) {
                continue
            }
            sink += SteamCloudLocalFileSnapshotEntry(
                localRelativePath = rootKind.directoryName + "/" + relativeSuffix,
                rootKind = rootKind,
                fileSize = file.length(),
                lastModifiedMs = file.lastModified().coerceAtLeast(0L),
                sha256 = sha256Hex(file),
            )
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(Locale.US, byte.toInt() and 0xFF)
        }
    }
}

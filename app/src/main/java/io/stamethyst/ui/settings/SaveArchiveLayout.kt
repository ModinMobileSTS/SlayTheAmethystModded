package io.stamethyst.ui.settings

import java.io.File
import java.util.Locale

internal object SaveArchiveLayout {
    private val supportedTopLevelDirs = listOf(
        "preferences",
        "perference",
        "perferences",
        "saves",
        "runs",
        "metrics",
        "home",
        "sendToDevs",
        "sendtodevs",
        "multiplayer",
        "multiple"
    )

    private val supportedTopLevelDirSet = supportedTopLevelDirs
        .map { it.lowercase(Locale.ROOT) }
        .toSet()

    fun existingSourceDirectories(stsRoot: File): List<File> {
        return supportedTopLevelDirs
            .asSequence()
            .map { File(stsRoot, it) }
            .filter { it.isDirectory }
            .toList()
    }

    fun supportedDirectoryDisplayText(): String = supportedTopLevelDirs.joinToString(", ")

    fun resolveImportablePath(rawEntryName: String?): String? {
        val normalizedPath = normalizeEntryName(rawEntryName) ?: return null
        if (normalizedPath.startsWith("__MACOSX/")) {
            return null
        }

        val relativePath = stripArchivePrefix(normalizedPath) ?: return null
        if (relativePath.startsWith("__MACOSX/")) {
            return null
        }

        val topLevelDir = topLevelDirectory(relativePath) ?: return null
        if (!isSupportedTopLevelDir(topLevelDir)) {
            return null
        }
        return relativePath
    }

    fun topLevelDirectory(path: String): String? {
        val normalizedPath = normalizeEntryName(path) ?: return null
        return normalizedPath.substringBefore('/').takeIf { it.isNotEmpty() }
    }

    fun buildArchiveEntryName(sourceRoot: File, sourceFile: File): String {
        val relativePath = sourceFile.toRelativeString(sourceRoot).replace('\\', '/')
        return "sts/${sourceRoot.name}/$relativePath"
    }

    private fun stripArchivePrefix(normalizedPath: String): String? {
        val segments = normalizedPath
            .split('/')
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            return null
        }

        if (isSupportedTopLevelDir(segments.first())) {
            return segments.joinToString("/")
        }

        val filesStsIndex = segments.indexOfAdjacentPair("files", "sts")
        if (filesStsIndex >= 0 && filesStsIndex + 2 < segments.size) {
            return segments.drop(filesStsIndex + 2).joinToString("/")
        }

        val stsIndex = segments.indexOfFirst { it.equals("sts", ignoreCase = true) }
        if (stsIndex >= 0 && stsIndex + 1 < segments.size) {
            return segments.drop(stsIndex + 1).joinToString("/")
        }

        return null
    }

    private fun normalizeEntryName(rawEntryName: String?): String? {
        var path = rawEntryName?.replace('\\', '/') ?: return null
        while (path.startsWith("/")) {
            path = path.substring(1)
        }
        if (path.isEmpty() || path.contains("../")) {
            return null
        }
        return path
    }

    private fun isSupportedTopLevelDir(folderName: String): Boolean {
        return supportedTopLevelDirSet.contains(folderName.lowercase(Locale.ROOT))
    }

    private fun List<String>.indexOfAdjacentPair(first: String, second: String): Int {
        for (index in 0 until size - 1) {
            if (this[index].equals(first, ignoreCase = true) &&
                this[index + 1].equals(second, ignoreCase = true)
            ) {
                return index
            }
        }
        return -1
    }
}

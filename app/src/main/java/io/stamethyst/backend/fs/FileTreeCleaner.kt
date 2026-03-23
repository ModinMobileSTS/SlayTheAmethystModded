package io.stamethyst.backend.fs

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

internal object FileTreeCleaner {
    private const val MAX_REMAINING_SUMMARY_COUNT = 6

    fun deleteRecursively(file: File?): Boolean {
        if (Thread.currentThread().isInterrupted) {
            return false
        }
        if (file == null || !existsNoFollowLinks(file)) {
            return true
        }

        var deleted = true
        if (isDirectoryNoFollowLinks(file)) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    if (Thread.currentThread().isInterrupted) {
                        return false
                    }
                    deleted = deleted && deleteRecursively(child)
                }
            }
        }

        return deletePath(file) && deleted
    }

    fun summarizeRemainingEntries(directory: File, maxEntries: Int = MAX_REMAINING_SUMMARY_COUNT): String? {
        val remaining = directory.listFiles() ?: return null
        if (remaining.isEmpty()) {
            return null
        }
        val visible = remaining
            .take(maxEntries)
            .joinToString(", ") { entry ->
                entry.name.ifBlank { entry.absolutePath }
            }
        val extraCount = remaining.size - maxEntries
        return if (extraCount > 0) {
            "$visible, +$extraCount more"
        } else {
            visible
        }
    }

    private fun deletePath(file: File): Boolean {
        return try {
            Files.deleteIfExists(file.toPath()) || !existsNoFollowLinks(file)
        } catch (_: IOException) {
            deleteWithLegacyApi(file)
        } catch (_: SecurityException) {
            deleteWithLegacyApi(file)
        }
    }

    private fun deleteWithLegacyApi(file: File): Boolean {
        return try {
            file.delete() || !existsNoFollowLinks(file)
        } catch (_: Throwable) {
            !existsNoFollowLinks(file)
        }
    }

    private fun existsNoFollowLinks(file: File): Boolean {
        return try {
            Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        } catch (_: Throwable) {
            file.exists()
        }
    }

    private fun isDirectoryNoFollowLinks(file: File): Boolean {
        return try {
            Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        } catch (_: Throwable) {
            file.isDirectory
        }
    }
}

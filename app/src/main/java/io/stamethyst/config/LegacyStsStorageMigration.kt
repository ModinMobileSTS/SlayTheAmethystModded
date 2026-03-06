package io.stamethyst.config

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayDeque

object LegacyStsStorageMigration {
    private const val PREFS_NAME = "sts_storage_migration"
    private const val PREF_KEY_COMPLETED_TARGET_PATH = "legacy_private_to_external_completed_target_path"

    data class Result(
        val scannedFileCount: Int,
        val copiedFileCount: Int,
        val copiedByteCount: Long,
        val sourceRootPath: String,
        val targetRootPath: String
    )

    private data class CopyStats(
        var copiedFileCount: Int = 0,
        var copiedByteCount: Long = 0L
    )

    @JvmStatic
    @Throws(IOException::class)
    fun migrateIfNeeded(context: Context): Result? {
        if (!RuntimePaths.usesExternalStsStorage(context)) {
            return null
        }

        val sourceRoot = RuntimePaths.legacyInternalStsRoot(context)
        val targetRoot = RuntimePaths.stsRoot(context)
        if (sourceRoot.absolutePath == targetRoot.absolutePath || !sourceRoot.exists()) {
            return null
        }
        if (sourceRoot.isFile) {
            throw IOException("Legacy sts root is not a directory: ${sourceRoot.absolutePath}")
        }

        val scannedFileCount = countFiles(sourceRoot)
        if (scannedFileCount <= 0) {
            sourceRoot.deleteRecursively()
            markCompleted(context, targetRoot)
            return null
        }

        if (isCompletedForTarget(context, targetRoot) && countFiles(targetRoot) > 0) {
            return null
        }

        ensureDirectory(targetRoot)
        val stats = CopyStats()
        mergeDirectories(sourceRoot, targetRoot, stats)
        deleteRecursivelyBestEffort(sourceRoot)
        markCompleted(context, targetRoot)

        return Result(
            scannedFileCount = scannedFileCount,
            copiedFileCount = stats.copiedFileCount,
            copiedByteCount = stats.copiedByteCount,
            sourceRootPath = sourceRoot.absolutePath,
            targetRootPath = targetRoot.absolutePath
        )
    }

    private fun isCompletedForTarget(context: Context, targetRoot: File): Boolean {
        val storedTarget = prefs(context).getString(PREF_KEY_COMPLETED_TARGET_PATH, null)?.trim()
        return !storedTarget.isNullOrEmpty() && storedTarget == targetRoot.absolutePath
    }

    private fun markCompleted(context: Context, targetRoot: File) {
        prefs(context).edit()
            .putString(PREF_KEY_COMPLETED_TARGET_PATH, targetRoot.absolutePath)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Throws(IOException::class)
    private fun mergeDirectories(sourceDir: File, targetDir: File, stats: CopyStats) {
        ensureDirectory(targetDir)
        val children = sourceDir.listFiles() ?: return
        children.forEach { sourceChild ->
            val targetChild = File(targetDir, sourceChild.name)
            if (sourceChild.isDirectory) {
                if (targetChild.isFile && !targetChild.delete()) {
                    throw IOException("Failed to replace file with directory: ${targetChild.absolutePath}")
                }
                mergeDirectories(sourceChild, targetChild, stats)
                return@forEach
            }
            if (!sourceChild.isFile) {
                return@forEach
            }

            if (targetChild.isDirectory && !targetChild.deleteRecursively()) {
                throw IOException("Failed to replace directory with file: ${targetChild.absolutePath}")
            }
            if (!shouldCopyFile(sourceChild, targetChild)) {
                return@forEach
            }
            copyFile(sourceChild, targetChild)
            stats.copiedFileCount++
            stats.copiedByteCount += sourceChild.length().coerceAtLeast(0L)
        }
    }

    private fun shouldCopyFile(sourceFile: File, targetFile: File): Boolean {
        if (!targetFile.isFile) {
            return true
        }

        val sourceModified = sourceFile.lastModified()
        val targetModified = targetFile.lastModified()
        if (sourceModified > 0L && targetModified > 0L && sourceModified != targetModified) {
            return sourceModified > targetModified
        }
        return sourceFile.length() != targetFile.length()
    }

    @Throws(IOException::class)
    private fun copyFile(sourceFile: File, targetFile: File) {
        val parent = targetFile.parentFile
        if (parent != null) {
            ensureDirectory(parent)
        }
        FileInputStream(sourceFile).use { input ->
            FileOutputStream(targetFile, false).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (read == 0) {
                        continue
                    }
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
        val modifiedAt = sourceFile.lastModified()
        if (modifiedAt > 0L) {
            targetFile.setLastModified(modifiedAt)
        }
    }

    @Throws(IOException::class)
    private fun ensureDirectory(dir: File) {
        if (dir.exists()) {
            if (!dir.isDirectory) {
                throw IOException("Expected directory but found file: ${dir.absolutePath}")
            }
            return
        }
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Failed to create directory: ${dir.absolutePath}")
        }
    }

    private fun countFiles(root: File): Int {
        if (!root.exists()) {
            return 0
        }
        if (root.isFile) {
            return 1
        }

        var count = 0
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            children.forEach { child ->
                if (child.isDirectory) {
                    stack.add(child)
                } else if (child.isFile) {
                    count++
                }
            }
        }
        return count
    }

    private fun deleteRecursivelyBestEffort(target: File) {
        if (!target.exists()) {
            return
        }
        runCatching {
            target.deleteRecursively()
        }
    }
}

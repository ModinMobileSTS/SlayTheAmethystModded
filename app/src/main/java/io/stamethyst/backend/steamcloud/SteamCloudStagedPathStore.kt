package io.stamethyst.backend.steamcloud

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

internal data class SteamCloudStagedPathReplacement(
    val stagedPath: File,
    val targetPath: File,
)

internal object SteamCloudStagedPathStore {
    fun apply(
        replacements: List<SteamCloudStagedPathReplacement>,
        rollbackRoot: File,
    ): SteamCloudApplyTransaction {
        if (rollbackRoot.exists() || !rollbackRoot.mkdirs()) {
            throw IOException("Failed to create rollback directory: ${rollbackRoot.absolutePath}")
        }

        val operations = mutableListOf<AppliedPathOperation>()
        val transaction = SteamCloudApplyTransaction {
            val failures = mutableListOf<Throwable>()
            operations.asReversed().forEach { operation ->
                runCatching { rollback(operation) }.onFailure(failures::add)
            }
            failures
        }

        try {
            replacements.forEachIndexed { index, replacement ->
                val backup = File(rollbackRoot, index.toString())
                val operation = AppliedPathOperation(
                    target = replacement.targetPath,
                    backup = backup,
                    hadOriginal = replacement.targetPath.exists(),
                )
                operations += operation
                if (operation.hadOriginal) {
                    movePath(operation.target, operation.backup) {
                        operation.backupReady = true
                    }
                }
                if (replacement.stagedPath.exists()) {
                    movePath(replacement.stagedPath, operation.target)
                }
            }
        } catch (error: Throwable) {
            val rollbackFailures = transaction.rollback().failures
            if (rollbackFailures.isNotEmpty()) {
                throw reconciliationFailure(rollbackRoot, error, rollbackFailures)
            }
            throw error
        }
        return transaction
    }

    fun rollbackAfterFailure(
        transaction: SteamCloudApplyTransaction,
        rollbackRoot: File,
        original: Throwable,
    ): Throwable {
        val failures = transaction.rollback().failures
        return if (failures.isEmpty()) {
            original
        } else {
            reconciliationFailure(rollbackRoot, original, failures)
        }
    }

    fun hasRecoveryData(root: File): Boolean = when {
        !root.exists() -> false
        root.isFile -> true
        else -> containsRecoveryFile(root)
    }

    private fun containsRecoveryFile(directory: File): Boolean {
        val children = directory.listFiles()
            ?: throw IOException("Failed to enumerate recovery directory: ${directory.absolutePath}")
        return children.any { child ->
            child.isFile || (child.isDirectory && containsRecoveryFile(child))
        }
    }

    fun copyPath(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.isDirectory && !target.mkdirs()) {
                throw IOException("Failed to create directory: ${target.absolutePath}")
            }
            val children = source.listFiles()
                ?: throw IOException("Failed to enumerate directory: ${source.absolutePath}")
            children.forEach { child ->
                copyPath(child, File(target, child.name))
            }
            return
        }

        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output -> input.copyTo(output) }
        }
        if (source.lastModified() > 0L) {
            target.setLastModified(source.lastModified())
        }
    }

    private fun rollback(operation: AppliedPathOperation) {
        if (operation.hadOriginal) {
            if (!operation.backupReady) {
                if (operation.backup.exists() && !operation.backup.deleteRecursively()) {
                    throw IOException("Failed to remove partial rollback path: ${operation.backup.absolutePath}")
                }
                if (!operation.target.exists()) {
                    throw IOException("Original path and complete rollback backup are both missing: ${operation.target.absolutePath}")
                }
                return
            }
            if (!operation.backup.exists()) {
                throw IOException("Rollback path is missing: ${operation.backup.absolutePath}")
            }
            if (operation.target.exists() && !operation.target.deleteRecursively()) {
                throw IOException("Failed to remove applied path: ${operation.target.absolutePath}")
            }
            movePath(operation.backup, operation.target)
            return
        }

        if (operation.target.exists() && !operation.target.deleteRecursively()) {
            throw IOException("Failed to remove newly applied path: ${operation.target.absolutePath}")
        }
    }

    private fun movePath(
        source: File,
        target: File,
        onTargetReady: () -> Unit = {},
    ) {
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        if (target.exists() && !target.deleteRecursively()) {
            throw IOException("Failed to replace path: ${target.absolutePath}")
        }
        if (source.renameTo(target)) {
            onTargetReady()
            return
        }
        copyPath(source, target)
        onTargetReady()
        if (!source.deleteRecursively()) {
            throw IOException("Failed to delete source path after copy: ${source.absolutePath}")
        }
    }

    private fun reconciliationFailure(
        rollbackRoot: File,
        original: Throwable,
        failures: List<Throwable>,
    ): SteamCloudReconciliationException {
        val rollbackError = IOException(
            "Rollback was incomplete: ${failures.joinToString("; ") { it.message ?: it.javaClass.simpleName }}",
            original,
        )
        failures.forEach(rollbackError::addSuppressed)
        val recoveryDataPreserved = runCatching { hasRecoveryData(rollbackRoot) }
            .getOrElse { inspectionError ->
                rollbackError.addSuppressed(inspectionError)
                true
            }
        return SteamCloudReconciliationException(
            recoveryRoot = rollbackRoot,
            recoveryDataPreserved = recoveryDataPreserved,
            cause = rollbackError,
        )
    }

    private class AppliedPathOperation(
        val target: File,
        val backup: File,
        val hadOriginal: Boolean,
    ) {
        var backupReady: Boolean = false
    }
}

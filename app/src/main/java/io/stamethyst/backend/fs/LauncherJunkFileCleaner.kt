package io.stamethyst.backend.fs

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.util.LinkedHashMap

internal object LauncherJunkFileCleaner {
    private const val MOD_IMPORT_SESSIONS_DIR_NAME = "mod-import-sessions"
    private const val MOD_IMPORT_PREVIEW_DIR_NAME = "mod-import-preview"

    data class CleanupResult(
        val deletedTargetCount: Int,
        val failedTargetCount: Int,
        val deletedBytes: Long
    )

    @JvmStatic
    fun clear(context: Context): CleanupResult = clearTargets(buildCleanupTargets(context))

    internal fun buildCleanupTargets(context: Context): List<File> {
        val targets = LinkedHashMap<String, File>()
        fun add(file: File) {
            val key = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
            targets.putIfAbsent(key, file)
        }

        RuntimePaths.knownMtsPatchCacheArtifacts(context).forEach(::add)
        add(File(context.cacheDir, MOD_IMPORT_SESSIONS_DIR_NAME))
        add(File(context.cacheDir, MOD_IMPORT_PREVIEW_DIR_NAME))
        add(File(context.cacheDir, "native-market-staging"))
        add(File(context.cacheDir, "runtime-staging"))
        context.externalCacheDir?.let { externalCacheDir ->
            add(File(externalCacheDir, MOD_IMPORT_SESSIONS_DIR_NAME))
            add(File(externalCacheDir, MOD_IMPORT_PREVIEW_DIR_NAME))
        }
        add(RuntimePaths.workshopImportSessionsRoot(context))
        add(RuntimePaths.workshopQuickStartSteamRoot(context))
        add(RuntimePaths.workshopStsJarImportRoot(context))
        add(RuntimePaths.nativeMarketStagingRoot(context))
        add(RuntimePaths.runtimeStagingDir(context))
        add(RuntimePaths.jvmTempRoot(context))
        add(RuntimePaths.feedbackWorkingRoot(context))
        add(RuntimePaths.transientFilesRoot(context))
        return targets.values.toList()
    }

    internal fun clearTargets(targets: List<File>): CleanupResult {
        var deletedTargetCount = 0
        var failedTargetCount = 0
        var deletedBytes = 0L

        targets.forEach { target ->
            if (!target.exists()) {
                return@forEach
            }

            val targetBytes = measureBytes(target)
            FileTreeCleaner.deleteRecursively(target)
            if (!target.exists()) {
                deletedTargetCount++
                deletedBytes += targetBytes
            } else {
                failedTargetCount++
            }
        }

        return CleanupResult(
            deletedTargetCount = deletedTargetCount,
            failedTargetCount = failedTargetCount,
            deletedBytes = deletedBytes
        )
    }

    private fun measureBytes(target: File): Long {
        if (!target.exists()) {
            return 0L
        }
        if (target.isFile) {
            return target.length().coerceAtLeast(0L)
        }
        return target.walkBottomUp()
            .filter(File::isFile)
            .sumOf { file -> file.length().coerceAtLeast(0L) }
    }
}

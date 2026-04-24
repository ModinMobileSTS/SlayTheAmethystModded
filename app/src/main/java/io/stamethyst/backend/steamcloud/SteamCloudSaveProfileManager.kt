package io.stamethyst.backend.steamcloud

import android.content.Context
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.SteamCloudSaveMode
import java.io.File
import java.io.IOException

internal object SteamCloudSaveProfileManager {
    private const val PROFILE_ROOT_DIR_NAME = "steam-cloud-save-profiles"

    @Throws(IOException::class)
    fun switchMode(
        context: Context,
        fromMode: SteamCloudSaveMode,
        toMode: SteamCloudSaveMode,
    ) {
        if (fromMode == toMode) {
            return
        }
        saveActiveProfile(context, fromMode)
        restoreProfile(context, toMode)
    }

    @Throws(IOException::class)
    fun saveActiveProfile(context: Context, mode: SteamCloudSaveMode) {
        val targetProfileRoot = profileDir(context, mode)
        if (!targetProfileRoot.isDirectory && !targetProfileRoot.mkdirs()) {
            throw IOException("Failed to create save profile directory: ${targetProfileRoot.absolutePath}")
        }
        val syncBlacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(context)
        SteamCloudRootKind.entries.forEach { rootKind ->
            replacePath(
                source = File(RuntimePaths.stsRoot(context), rootKind.directoryName),
                target = File(targetProfileRoot, rootKind.directoryName),
                deleteTargetWhenSourceMissing = true,
                excludedRelativeSuffixes = SteamCloudSyncBlacklist.relativeSuffixesForRoot(
                    rootKind = rootKind,
                    configuredBlacklist = syncBlacklist,
                ),
            )
        }
    }

    @Throws(IOException::class)
    fun restoreProfile(context: Context, mode: SteamCloudSaveMode) {
        val sourceProfileRoot = profileDir(context, mode)
        val syncBlacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(context)
        SteamCloudRootKind.entries.forEach { rootKind ->
            replacePath(
                source = File(sourceProfileRoot, rootKind.directoryName),
                target = File(RuntimePaths.stsRoot(context), rootKind.directoryName),
                deleteTargetWhenSourceMissing = true,
                excludedRelativeSuffixes = SteamCloudSyncBlacklist.relativeSuffixesForRoot(
                    rootKind = rootKind,
                    configuredBlacklist = syncBlacklist,
                ),
                preservedTargetRelativeSuffixes = SteamCloudSyncBlacklist.relativeSuffixesForRoot(
                    rootKind = rootKind,
                    configuredBlacklist = syncBlacklist,
                ),
            )
        }
    }

    fun profileRoot(context: Context, mode: SteamCloudSaveMode): File {
        return profileDir(context, mode)
    }

    fun profileHasRegularFiles(context: Context, mode: SteamCloudSaveMode): Boolean {
        val syncBlacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(context)
        return SteamCloudRootKind.entries.any { rootKind ->
            containsRegularFile(
                file = File(profileDir(context, mode), rootKind.directoryName),
                excludedRelativeSuffixes = SteamCloudSyncBlacklist.relativeSuffixesForRoot(
                    rootKind = rootKind,
                    configuredBlacklist = syncBlacklist,
                ),
            )
        }
    }

    private fun profileDir(context: Context, mode: SteamCloudSaveMode): File {
        return File(File(RuntimePaths.storageRoot(context), PROFILE_ROOT_DIR_NAME), mode.persistedValue)
    }

    private fun containsRegularFile(
        file: File,
        excludedRelativeSuffixes: Set<String>,
        relativeSuffix: String = "",
    ): Boolean {
        if (!file.exists()) {
            return false
        }
        val normalizedRelativeSuffix = relativeSuffix.replace('\\', '/')
        if (normalizedRelativeSuffix.isNotBlank() &&
            normalizedRelativeSuffix in excludedRelativeSuffixes
        ) {
            return false
        }
        if (file.isFile) {
            return true
        }
        return file.listFiles()?.any { child ->
            val childRelativeSuffix = if (normalizedRelativeSuffix.isBlank()) {
                child.name
            } else {
                normalizedRelativeSuffix + "/" + child.name
            }
            containsRegularFile(
                file = child,
                excludedRelativeSuffixes = excludedRelativeSuffixes,
                relativeSuffix = childRelativeSuffix,
            )
        } == true
    }

    @Throws(IOException::class)
    private fun replacePath(
        source: File,
        target: File,
        deleteTargetWhenSourceMissing: Boolean,
        excludedRelativeSuffixes: Set<String> = emptySet(),
        preservedTargetRelativeSuffixes: Set<String> = emptySet(),
    ) {
        if (excludedRelativeSuffixes.isEmpty() && preservedTargetRelativeSuffixes.isEmpty()) {
            replacePathWithoutPreserving(source, target, deleteTargetWhenSourceMissing)
            return
        }

        if (!source.exists() && !deleteTargetWhenSourceMissing && preservedTargetRelativeSuffixes.isEmpty()) {
            return
        }

        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        val tempRoot = File(
            requireNotNull(parent) { "Profile target parent directory is missing." },
            ".${target.name}.tmp-${System.currentTimeMillis()}-${System.nanoTime()}"
        )
        if (!tempRoot.mkdirs()) {
            throw IOException("Failed to create temporary profile directory: ${tempRoot.absolutePath}")
        }
        val backupRoot = File(tempRoot, "backup")
        val hadOriginal = target.exists()

        try {
            if (hadOriginal) {
                movePath(target, backupRoot)
            }
            if (source.exists()) {
                copyPathExcluding(
                    source = source,
                    target = target,
                    excludedRelativeSuffixes = excludedRelativeSuffixes,
                )
            }
            restoreSelectedPaths(
                sourceRoot = backupRoot,
                targetRoot = target,
                relativeSuffixes = preservedTargetRelativeSuffixes,
            )
        } catch (error: Throwable) {
            if (target.exists() && !target.deleteRecursively()) {
                throw IOException("Failed to roll back profile target: ${target.absolutePath}", error)
            }
            if (hadOriginal && backupRoot.exists()) {
                movePath(backupRoot, target)
            }
            throw error
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Throws(IOException::class)
    private fun replacePathWithoutPreserving(
        source: File,
        target: File,
        deleteTargetWhenSourceMissing: Boolean,
    ) {
        if (!source.exists()) {
            if (deleteTargetWhenSourceMissing && target.exists() && !target.deleteRecursively()) {
                throw IOException("Failed to delete path: ${target.absolutePath}")
            }
            return
        }

        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        if (target.exists() && !target.deleteRecursively()) {
            throw IOException("Failed to replace path: ${target.absolutePath}")
        }
        copyPath(source, target)
    }

    @Throws(IOException::class)
    private fun movePath(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        if (target.exists() && !target.deleteRecursively()) {
            throw IOException("Failed to replace path: ${target.absolutePath}")
        }
        if (source.renameTo(target)) {
            return
        }
        copyPath(source, target)
        if (!source.deleteRecursively()) {
            throw IOException("Failed to delete source path after copy: ${source.absolutePath}")
        }
    }

    @Throws(IOException::class)
    private fun copyPath(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.isDirectory && !target.mkdirs()) {
                throw IOException("Failed to create directory: ${target.absolutePath}")
            }
            source.listFiles()?.forEach { child ->
                copyPath(child, File(target, child.name))
            }
            return
        }

        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        target.setLastModified(source.lastModified())
    }

    @Throws(IOException::class)
    private fun copyPathExcluding(
        source: File,
        target: File,
        excludedRelativeSuffixes: Set<String>,
        relativeSuffix: String = "",
    ): Boolean {
        val normalizedRelativeSuffix = relativeSuffix.replace('\\', '/')
        if (normalizedRelativeSuffix.isNotBlank() &&
            normalizedRelativeSuffix in excludedRelativeSuffixes
        ) {
            return false
        }
        if (source.isDirectory) {
            var copiedAny = false
            source.listFiles()?.forEach { child ->
                val childRelativeSuffix = if (normalizedRelativeSuffix.isBlank()) {
                    child.name
                } else {
                    normalizedRelativeSuffix + "/" + child.name
                }
                copiedAny = copyPathExcluding(
                    source = child,
                    target = File(target, child.name),
                    excludedRelativeSuffixes = excludedRelativeSuffixes,
                    relativeSuffix = childRelativeSuffix,
                ) || copiedAny
            }
            return copiedAny
        }
        copyPath(source, target)
        return true
    }

    @Throws(IOException::class)
    private fun restoreSelectedPaths(
        sourceRoot: File,
        targetRoot: File,
        relativeSuffixes: Set<String>,
    ) {
        if (!sourceRoot.exists() || relativeSuffixes.isEmpty()) {
            return
        }
        relativeSuffixes.forEach { relativeSuffix ->
            val source = File(sourceRoot, relativeSuffix.replace('/', File.separatorChar))
            if (!source.exists()) {
                return@forEach
            }
            val target = File(targetRoot, relativeSuffix.replace('/', File.separatorChar))
            if (target.exists() && !target.deleteRecursively()) {
                throw IOException("Failed to replace preserved profile path: ${target.absolutePath}")
            }
            copyPath(source, target)
        }
    }
}

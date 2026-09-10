package io.stamethyst.backend.easytier

import android.content.Context
import io.stamethyst.config.RuntimePaths
import io.stamethyst.backend.resources.ExternalResourcePackService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

internal object EasyTierNativeLibraryLoader {
    private const val CODE_CACHE_DIR_NAME = "easytier-native"
    private val libraryFileNames = listOf(
        "libeasytier_ffi.so",
        "libeasytier_android_jni.so"
    )

    /**
     * Libraries the staged objects depend on by plain soname. They are staged
     * side by side, so a `DT_RUNPATH` of `$ORIGIN` is enough for bionic to find
     * them; see [EasyTierNativeElfPatcher] for why the prebuilts need that.
     */
    private val siblingLibraryNames = libraryFileNames.toSet()
    private val loadLock = Any()

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) {
            return
        }
        synchronized(loadLock) {
            if (loaded) {
                return
            }
            val sourceLibraryFiles = requireLibraryFiles(
                resolveLibraryDir(context)
            )
            val libraryFiles = stageLibraryFilesForLoading(context, sourceLibraryFiles)
            // Android's linker does not reliably permit executable mappings from
            // the writable resource-pack directory. codeCacheDir is the approved
            // private location for dynamically loaded native code.
            libraryFiles.forEach { libraryFile -> System.load(libraryFile.canonicalPath) }
            loaded = true
        }
    }

    internal fun requireLibraryFiles(libraryDir: File): List<File> =
        libraryFileNames.map { fileName ->
            File(libraryDir, fileName).also(::requireLibraryFile)
        }

    internal fun resolveLibraryDir(context: Context): File {
        val installedDir = RuntimePaths.externalNativeLibDir(context)
        if (hasRequiredLibraries(installedDir)) {
            return installedDir
        }
        ExternalResourcePackService.installNativeLibraries(context)
        return installedDir
    }

    internal fun hasRequiredLibraries(directory: File): Boolean {
        return libraryFileNames.all { fileName ->
            val file = File(directory, fileName)
            file.isFile && file.length() > 0L
        }
    }

    @Throws(IOException::class)
    internal fun stageLibraryFilesForLoading(
        context: Context,
        sourceLibraryFiles: List<File>
    ): List<File> {
        val targetDir = File(context.codeCacheDir, CODE_CACHE_DIR_NAME)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Failed to create EasyTier native code cache: ${targetDir.absolutePath}")
        }
        return sourceLibraryFiles.map { source ->
            val target = File(targetDir, source.name)
            if (isCachedLibraryCurrent(source, target)) {
                repairCachedLibrary(source, target)
            } else {
                stageLibraryAtomically(source, target)
            }
        }
    }

    /**
     * Repairs a copy staged by an earlier launcher build. The repair changes
     * neither size nor timestamp, so [isCachedLibraryCurrent] cannot tell a
     * repaired copy from an unrepaired one and the check has to run every time.
     */
    private fun repairCachedLibrary(source: File, target: File): File {
        val repaired = EasyTierNativeElfPatcher.ensureSiblingDependencyLookup(
            target,
            siblingLibraryNames
        )
        if (repaired && source.lastModified() > 0L) {
            // Writing bumped the timestamp; realign it so the copy stays cached.
            target.setLastModified(source.lastModified())
        }
        return target
    }

    internal fun isCachedLibraryCurrent(source: File, target: File): Boolean =
        target.isFile &&
            target.length() == source.length() &&
            target.lastModified() == source.lastModified()

    @Throws(IOException::class)
    private fun stageLibraryAtomically(source: File, target: File): File {
        val temporary = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporary, false).use { output -> input.copyTo(output) }
            }
            // Must run before the timestamp is aligned with the source, otherwise
            // the repair would be redone on every launch. The edit keeps the file
            // size unchanged, so it cannot invalidate the staleness check either.
            EasyTierNativeElfPatcher.ensureSiblingDependencyLookup(temporary, siblingLibraryNames)
            if (!temporary.setExecutable(true, false)) {
                throw IOException("Failed to mark EasyTier native library executable: ${temporary.absolutePath}")
            }
            if (source.lastModified() > 0L) {
                temporary.setLastModified(source.lastModified())
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Failed to replace EasyTier native library: ${target.absolutePath}")
            }
            if (!temporary.renameTo(target)) {
                throw IOException("Failed to install EasyTier native library: ${target.absolutePath}")
            }
            return target
        } finally {
            if (temporary.exists()) {
                temporary.delete()
            }
        }
    }

    private fun requireLibraryFile(file: File) {
        if (!file.isFile || file.length() <= 0L) {
            throw UnsatisfiedLinkError(
                "EasyTier native runtime is missing from the installed resource pack: ${file.absolutePath}"
            )
        }
    }
}

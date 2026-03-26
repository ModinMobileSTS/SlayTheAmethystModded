package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.LinkedHashSet

internal object NativeLibraryPathResolver {
    fun collectAdditionalSearchDirectories(context: Context): List<File> {
        val directories = LinkedHashSet<String>()
        addDirectoryAndLibraryChildren(directories, RuntimePaths.gdxPatchNativesDir(context))
        return directories.map(::File)
    }

    fun buildJavaLibraryPath(
        context: Context,
        javaHome: File,
        appNativeLibraryDir: String
    ): String {
        val directories = LinkedHashSet<String>()
        addRuntimeLibraryDirectories(directories, javaHome)
        addDirectory(directories, File(appNativeLibraryDir))
        collectAdditionalSearchDirectories(context).forEach { directory ->
            addDirectory(directories, directory)
        }
        return directories.joinToString(File.pathSeparator)
    }

    internal fun collectDirectoriesWithSharedLibraries(root: File): List<File> {
        if (!root.isDirectory) {
            return emptyList()
        }

        val directories = ArrayList<File>()
        val pending = ArrayDeque<File>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            val children = current.listFiles() ?: continue
            if (children.any { child -> child.isFile && child.name.endsWith(".so", ignoreCase = true) }) {
                directories.add(current)
            }
            children
                .asSequence()
                .filter(File::isDirectory)
                .forEach(pending::addLast)
        }
        return directories
    }

    private fun addRuntimeLibraryDirectories(directories: LinkedHashSet<String>, javaHome: File) {
        val runtimeLibDir = findRuntimeLibraryDirectory(javaHome) ?: return
        addDirectory(directories, runtimeLibDir)
        addDirectory(directories, File(runtimeLibDir, "jli"))
        addDirectory(directories, File(runtimeLibDir, "server"))
        addDirectory(directories, File(runtimeLibDir, "client"))
    }

    private fun findRuntimeLibraryDirectory(javaHome: File): File? {
        val candidates = listOf(
            "lib/aarch64",
            "lib/arm64",
            "lib"
        )
        candidates.forEach { candidate ->
            val dir = File(javaHome, candidate)
            if (!dir.isDirectory) {
                return@forEach
            }
            if (File(dir, "server/libjvm.so").isFile || File(dir, "client/libjvm.so").isFile) {
                return dir
            }
        }
        return null
    }

    private fun addDirectoryAndLibraryChildren(
        directories: LinkedHashSet<String>,
        root: File
    ) {
        addDirectory(directories, root)
        collectDirectoriesWithSharedLibraries(root).forEach { directory ->
            addDirectory(directories, directory)
        }
    }

    private fun addDirectory(directories: LinkedHashSet<String>, directory: File) {
        if (!directory.isDirectory) {
            return
        }
        directories.add(directory.absolutePath)
    }
}

package io.stamethyst.backend.launch

import android.content.Context
import android.util.Log
import net.kdt.pojavlaunch.utils.JREUtils
import java.io.File
import java.util.LinkedHashSet

internal object AdditionalNativeLibraryPreloader {
    private const val TAG = "STS-JVM"

    // Keep the preload order narrow and explicit so startup remains predictable.
    private val preloadLibraryNames = listOf(
        "libjnijavacpp.so",
        "libtensorflow_cc.so",
        "libtensorflow_framework.so.2",
        "libtensorflow.so.2",
        "libjnitensorflow.so",
        "libtensorflow_jni.so"
    )

    fun preload(context: Context) {
        val searchedDirectories = NativeLibraryPathResolver.collectAdditionalSearchDirectories(context)
        if (searchedDirectories.isEmpty()) {
            return
        }

        val loadedPaths = LinkedHashSet<String>()
        preloadLibraryNames.forEach { libraryName ->
            val resolved = resolveLibrary(searchedDirectories, libraryName) ?: return@forEach
            if (!loadedPaths.add(resolved.absolutePath)) {
                return@forEach
            }
            val loaded = JREUtils.dlopen(resolved.absolutePath)
            Log.i(
                TAG,
                "Preloaded extra native library name=$libraryName loaded=$loaded path=${resolved.absolutePath}"
            )
        }
    }

    private fun resolveLibrary(
        directories: List<File>,
        libraryName: String
    ): File? {
        directories.forEach { directory ->
            val candidate = File(directory, libraryName)
            if (candidate.isFile()) {
                return candidate
            }
        }
        return null
    }
}

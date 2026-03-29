package io.stamethyst.backend.launch

import android.content.Context
import android.util.Log
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.nio.file.Files

internal object AdditionalNativeLibraryPreloader {
    private const val TAG = "STS-JVM"

    private val compatibilityAliases = linkedMapOf(
        "libtensorflow_framework.so" to "libtensorflow_framework.so.2",
        "libtensorflow.so" to "libtensorflow.so.2"
    )

    fun preload(context: Context) {
        ensureCompatibilityAliases(RuntimePaths.nativeMarketActiveDir(context))
    }

    internal fun buildCompatibilityAliasPlan(availableNames: Set<String>): Map<String, String> {
        val plannedAliases = LinkedHashMap<String, String>()
        compatibilityAliases.forEach { (aliasName, targetName) ->
            if (!availableNames.contains(aliasName) && availableNames.contains(targetName)) {
                plannedAliases[aliasName] = targetName
            }
        }
        return plannedAliases
    }

    private fun ensureCompatibilityAliases(directory: File) {
        if (!directory.isDirectory) {
            return
        }
        val availableNames = directory.listFiles()
            ?.filter(File::isFile)
            ?.map(File::getName)
            ?.toSet()
            .orEmpty()
        buildCompatibilityAliasPlan(availableNames).forEach { (aliasName, targetName) ->
            val targetFile = File(directory, targetName)
            val aliasFile = File(directory, aliasName)
            if (!targetFile.isFile() || aliasFile.exists()) {
                return@forEach
            }
            val created = createCompatibilityAlias(aliasFile, targetFile)
            Log.i(
                TAG,
                "Prepared TensorFlow compatibility alias alias=${aliasFile.name} target=${targetFile.name} created=$created"
            )
        }
    }

    private fun createCompatibilityAlias(aliasFile: File, targetFile: File): Boolean {
        return try {
            Files.createSymbolicLink(aliasFile.toPath(), targetFile.toPath().fileName)
            true
        } catch (_: Throwable) {
            runCatching {
                Files.createLink(aliasFile.toPath(), targetFile.toPath())
                true
            }.getOrDefault(false)
        }
    }
}

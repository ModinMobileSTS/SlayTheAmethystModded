package io.stamethyst.backend.mods

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale

internal object OptionalModStorageCoordinator {
    @JvmStatic
    @Throws(IOException::class)
    fun ensureOptionalModLibraryReady(context: Context) {
        val libraryDir = RuntimePaths.optionalModsLibraryDir(context)
        ensureDirectory(libraryDir)
        val migrationMarker = RuntimePaths.optionalModsLibraryMigrationMarker(context)
        if (migrationMarker.isFile) {
            return
        }
        migrateLegacyOptionalMods(
            legacyRuntimeModsDir = RuntimePaths.modsDir(context),
            libraryDir = libraryDir,
            enabledModsConfig = RuntimePaths.enabledModsConfig(context),
            priorityModsConfig = RuntimePaths.priorityModsConfig(context),
            normalizeSelectionPath = { raw ->
                RuntimePaths.normalizeLegacyStsPath(context, raw)
            }
        )
        writeMigrationMarker(migrationMarker)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun syncEnabledOptionalModsToRuntime(context: Context) {
        ensureOptionalModLibraryReady(context)
        syncRuntimeOptionalMods(
            runtimeModsDir = RuntimePaths.modsDir(context),
            enabledLibraryFiles = ModManager.listEnabledOptionalModFiles(context)
        )
    }

    @Throws(IOException::class)
    internal fun migrateLegacyOptionalMods(
        legacyRuntimeModsDir: File,
        libraryDir: File,
        enabledModsConfig: File,
        priorityModsConfig: File,
        normalizeSelectionPath: ((String) -> String?)? = null
    ) {
        ensureDirectory(libraryDir)
        if (!legacyRuntimeModsDir.isDirectory) {
            return
        }
        val legacyOptionalFiles = listOptionalJarFiles(legacyRuntimeModsDir)
        if (legacyOptionalFiles.isEmpty()) {
            return
        }

        val movedPaths = LinkedHashMap<String, String>()
        legacyOptionalFiles.forEach { source ->
            val target = buildUniqueImportTarget(libraryDir, source.name)
            moveFileReplacing(source, target)
            movedPaths[source.absolutePath] = target.absolutePath
        }
        rewriteSelectionConfig(enabledModsConfig, movedPaths, normalizeSelectionPath)
        rewriteSelectionConfig(priorityModsConfig, movedPaths, normalizeSelectionPath)
    }

    @Throws(IOException::class)
    internal fun syncRuntimeOptionalMods(
        runtimeModsDir: File,
        enabledLibraryFiles: List<File>
    ) {
        ensureDirectory(runtimeModsDir)

        val expectedNames = enabledLibraryFiles.mapTo(LinkedHashSet()) { it.name }
        runtimeModsDir.listFiles().orEmpty().forEach { existing ->
            if (!existing.isFile) {
                return@forEach
            }
            if (!existing.name.lowercase(Locale.ROOT).endsWith(".jar")) {
                return@forEach
            }
            if (isReservedJarName(existing.name)) {
                return@forEach
            }
            if (!expectedNames.contains(existing.name)) {
                if (!existing.delete()) {
                    throw IOException("Failed to delete runtime mod file: ${existing.absolutePath}")
                }
            }
        }

        enabledLibraryFiles.forEach { source ->
            syncFileIfChanged(source, File(runtimeModsDir, source.name))
        }
    }

    private fun rewriteSelectionConfig(
        configFile: File,
        movedPaths: Map<String, String>,
        normalizeSelectionPath: ((String) -> String?)?
    ) {
        if (!configFile.isFile || movedPaths.isEmpty()) {
            return
        }
        val lines = try {
            configFile.readLines(StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            return
        }
        var changed = false
        val rewritten = lines.map { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                return@map line
            }
            if (!looksLikePathToken(trimmed)) {
                return@map line
            }
            val normalizedPath = normalizeSelectionPath?.invoke(trimmed)?.trim().orEmpty().ifBlank { trimmed }
            val targetPath = movedPaths[normalizedPath] ?: return@map if (normalizedPath != trimmed) {
                changed = true
                normalizedPath
            } else {
                line
            }
            changed = true
            targetPath
        }
        if (!changed) {
            return
        }
        val parent = configFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        configFile.writeText(rewritten.joinToString(separator = "\n"), StandardCharsets.UTF_8)
    }

    private fun writeMigrationMarker(markerFile: File) {
        val parent = markerFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        OutputStreamWriter(FileOutputStream(markerFile, false), StandardCharsets.UTF_8).use { writer ->
            writer.write("ok")
            writer.write('\n'.code)
        }
    }

    private fun listOptionalJarFiles(dir: File): List<File> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .asSequence()
            .filter { it.isFile }
            .filter { it.name.lowercase(Locale.ROOT).endsWith(".jar") }
            .filterNot { isReservedJarName(it.name) }
            .sortedWith(compareBy<File>({ it.name.lowercase(Locale.ROOT) }, { it.name }, { it.absolutePath }))
            .toList()
    }

    @Throws(IOException::class)
    private fun syncFileIfChanged(source: File, target: File) {
        if (!source.isFile) {
            throw IOException("Library mod file missing: ${source.absolutePath}")
        }
        val parent = target.parentFile
        if (parent != null) {
            ensureDirectory(parent)
        }
        if (target.isFile &&
            target.length() == source.length() &&
            target.lastModified() >= source.lastModified()
        ) {
            return
        }

        val temp = File(
            parent ?: target.absoluteFile.parentFile ?: throw IOException("Target has no parent"),
            ".${target.name}.${System.nanoTime()}.tmp"
        )
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temp, false).use { output ->
                    input.copyTo(output)
                }
            }
            temp.setLastModified(source.lastModified())
            if (target.exists() && !target.delete()) {
                throw IOException("Failed to replace runtime mod file: ${target.absolutePath}")
            }
            if (!temp.renameTo(target)) {
                throw IOException("Failed to move ${temp.absolutePath} -> ${target.absolutePath}")
            }
        } finally {
            if (temp.exists()) {
                temp.delete()
            }
        }
    }

    @Throws(IOException::class)
    private fun moveFileReplacing(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null) {
            ensureDirectory(parent)
        }
        if (!source.exists()) {
            throw IOException("Source file not found: ${source.absolutePath}")
        }
        if (source.renameTo(target)) {
            return
        }
        try {
            Os.rename(source.absolutePath, target.absolutePath)
        } catch (error: ErrnoException) {
            throw IOException(
                "Failed to move ${source.absolutePath} -> ${target.absolutePath}",
                error
            )
        }
    }

    @Throws(IOException::class)
    private fun ensureDirectory(dir: File) {
        if (dir.isDirectory) {
            return
        }
        if (dir.exists() && !dir.isDirectory) {
            throw IOException("Expected directory but found file: ${dir.absolutePath}")
        }
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Failed to create directory: ${dir.absolutePath}")
        }
    }

    private fun looksLikePathToken(token: String): Boolean {
        return token.contains('/') || token.contains('\\')
    }

    private fun isReservedJarName(fileName: String): Boolean {
        val normalized = fileName.lowercase(Locale.ROOT)
        return "basemod.jar" == normalized || "stslib.jar" == normalized
    }

    private fun sanitizeImportedJarFileName(requestedFileName: String?): String {
        val raw = requestedFileName?.trim().orEmpty()
        val leafName = if (raw.isEmpty()) {
            "mod.jar"
        } else {
            File(raw).name
        }
        var sanitized = leafName
            .replace('/', '_')
            .replace('\\', '_')
            .trim()
        if (sanitized.isEmpty() || sanitized == "." || sanitized == "..") {
            sanitized = "mod.jar"
        }
        if (!sanitized.lowercase(Locale.ROOT).endsWith(".jar")) {
            sanitized += ".jar"
        }
        return sanitized
    }

    private fun buildUniqueImportTarget(dir: File, preferredName: String): File {
        val normalizedName = sanitizeImportedJarFileName(preferredName)
        val baseName = removeJarSuffix(normalizedName).ifBlank { "mod" }
        var index = 1
        while (true) {
            val candidateName = if (index == 1) {
                "$baseName.jar"
            } else {
                "$baseName ($index).jar"
            }
            val candidate = File(dir, candidateName)
            if (!candidate.exists() && !isReservedJarName(candidate.name)) {
                return candidate
            }
            index++
        }
    }

    private fun removeJarSuffix(fileName: String): String {
        return if (fileName.lowercase(Locale.ROOT).endsWith(".jar")) {
            fileName.substring(0, fileName.length - 4)
        } else {
            fileName
        }
    }
}

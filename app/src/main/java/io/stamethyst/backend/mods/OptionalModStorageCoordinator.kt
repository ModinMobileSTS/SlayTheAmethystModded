package io.stamethyst.backend.mods

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import io.stamethyst.backend.diag.MemoryDiagnosticsLogger
import io.stamethyst.backend.fs.FileTreeCleaner
import io.stamethyst.backend.workshop.WorkshopMetadataStore
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.Locale

internal object OptionalModStorageCoordinator {
    private const val INTERRUPTED_IMPORT_CLEANUP_GRACE_MS = 60L * 60L * 1000L

    private val importArtifactLock = Any()

    @JvmStatic
    @Throws(IOException::class)
    fun ensureOptionalModLibraryReady(context: Context) {
        val libraryDir = RuntimePaths.optionalModsLibraryDir(context)
        ensureDirectory(libraryDir)
        withImportArtifactLock {
            cleanupInterruptedImports(libraryDir)
        }
        val migrationMarker = RuntimePaths.optionalModsLibraryMigrationMarker(context)
        if (migrationMarker.isFile) {
            return
        }
        val movedPaths = migrateLegacyOptionalMods(
            legacyRuntimeModsDir = RuntimePaths.modsDir(context),
            libraryDir = libraryDir,
            enabledModsConfig = RuntimePaths.enabledModsConfig(context),
            priorityModsConfig = RuntimePaths.priorityModsConfig(context),
            normalizeSelectionPath = { raw ->
                RuntimePaths.normalizeLegacyStsPath(context, raw)
            }
        )
        rebindWorkshopMetadata(context, movedPaths)
        writeMigrationMarker(migrationMarker)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun syncEnabledOptionalModsToRuntime(context: Context) {
        prepareMtsModFileList(context)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun prepareMtsModFileList(context: Context) {
        prepareMtsModFileList(context, null)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun prepareMtsModFileList(
        context: Context,
        launchSnapshot: ModManager.LaunchModSnapshot?
    ): ModManager.LaunchModSnapshot {
        ensureOptionalModLibraryReady(context)
        val runtimeModsDir = RuntimePaths.modsDir(context)
        val movedPaths = salvageLegacyOptionalModsForRuntimeCleanup(
            legacyRuntimeModsDir = runtimeModsDir,
            libraryDir = RuntimePaths.optionalModsLibraryDir(context),
            enabledModsConfig = RuntimePaths.enabledModsConfig(context),
            priorityModsConfig = RuntimePaths.priorityModsConfig(context),
            normalizeSelectionPath = { raw ->
                RuntimePaths.normalizeLegacyStsPath(context, raw)
            }
        )
        rebindWorkshopMetadata(context, movedPaths)
        val snapshot = launchSnapshot ?: ModManager.buildLaunchModSnapshot(context)
        val enabledLibraryFiles = snapshot.enabledLibraryFiles
        val launchModFiles = snapshot.launchModFiles
        MemoryDiagnosticsLogger.logModSnapshot(
            context = context,
            event = "mts_mod_file_list_prepare_started",
            launchMode = "mts",
            enabledLibraryFiles = enabledLibraryFiles,
            runtimeModFiles = launchModFiles
        )
        writeMtsModFileList(RuntimePaths.mtsModFileList(context), launchModFiles)
        deleteLegacyRuntimeModsDir(runtimeModsDir)
        MemoryDiagnosticsLogger.logModSnapshot(
            context = context,
            event = "mts_mod_file_list_prepare_completed",
            launchMode = "mts",
            enabledLibraryFiles = enabledLibraryFiles,
            runtimeModFiles = launchModFiles
        )
        return snapshot
    }

    @Throws(IOException::class)
    internal fun migrateLegacyOptionalMods(
        legacyRuntimeModsDir: File,
        libraryDir: File,
        enabledModsConfig: File,
        priorityModsConfig: File,
        normalizeSelectionPath: ((String) -> String?)? = null
    ): Map<String, String> {
        ensureDirectory(libraryDir)
        if (!legacyRuntimeModsDir.isDirectory) {
            return emptyMap()
        }
        val legacyOptionalFiles = listOptionalJarFiles(legacyRuntimeModsDir)
        if (legacyOptionalFiles.isEmpty()) {
            return emptyMap()
        }

        val movedPaths = LinkedHashMap<String, String>()
        legacyOptionalFiles.forEach { source ->
            val target = buildUniqueImportTarget(libraryDir, source.name)
            moveFileReplacing(source, target)
            movedPaths[source.absolutePath] = target.absolutePath
        }
        rewriteSelectionConfig(enabledModsConfig, movedPaths, normalizeSelectionPath)
        rewriteSelectionConfig(priorityModsConfig, movedPaths, normalizeSelectionPath)
        return movedPaths
    }

    @Throws(IOException::class)
    internal fun salvageLegacyOptionalModsForRuntimeCleanup(
        legacyRuntimeModsDir: File,
        libraryDir: File,
        enabledModsConfig: File,
        priorityModsConfig: File,
        normalizeSelectionPath: ((String) -> String?)? = null
    ): Map<String, String> {
        ensureDirectory(libraryDir)
        if (!legacyRuntimeModsDir.isDirectory) {
            return emptyMap()
        }
        val legacyOptionalFiles = listOptionalJarFiles(legacyRuntimeModsDir)
        if (legacyOptionalFiles.isEmpty()) {
            return emptyMap()
        }

        val movedPaths = LinkedHashMap<String, String>()
        legacyOptionalFiles.forEach { source ->
            val equivalentLibraryFile = findEquivalentLibraryFile(libraryDir, source)
            if (equivalentLibraryFile != null) {
                movedPaths[source.absolutePath] = equivalentLibraryFile.absolutePath
                if (!source.delete()) {
                    throw IOException("Failed to delete legacy duplicate mod file: ${source.absolutePath}")
                }
                return@forEach
            }
            val target = buildUniqueImportTarget(libraryDir, source.name)
            moveFileReplacing(source, target)
            movedPaths[source.absolutePath] = target.absolutePath
        }
        rewriteSelectionConfig(enabledModsConfig, movedPaths, normalizeSelectionPath)
        rewriteSelectionConfig(priorityModsConfig, movedPaths, normalizeSelectionPath)
        return movedPaths
    }

    private fun rebindWorkshopMetadata(context: Context, movedPaths: Map<String, String>) {
        if (movedPaths.isEmpty()) return
        val metadataStore = WorkshopMetadataStore(context)
        movedPaths.forEach { (oldPath, newPath) ->
            metadataStore.rebindLocalJarPaths(listOf(oldPath), newPath)
        }
    }

    @Throws(IOException::class)
    internal fun writeMtsModFileList(fileList: File, launchModFiles: List<File>) {
        val parent = fileList.parentFile
        if (parent != null) {
            ensureDirectory(parent)
        }
        val temp = File(
            parent ?: fileList.absoluteFile.parentFile ?: throw IOException("MTS file list has no parent"),
            ".${fileList.name}.${System.nanoTime()}.tmp"
        )
        try {
            OutputStreamWriter(FileOutputStream(temp, false), StandardCharsets.UTF_8).use { writer ->
                launchModFiles.forEach { file ->
                    writer.write(file.absolutePath)
                    writer.write('\n'.code)
                }
            }
            if (fileList.exists() && !fileList.delete()) {
                throw IOException("Failed to replace MTS mod file list: ${fileList.absolutePath}")
            }
            if (!temp.renameTo(fileList)) {
                throw IOException("Failed to move ${temp.absolutePath} -> ${fileList.absolutePath}")
            }
        } finally {
            if (temp.exists()) {
                temp.delete()
            }
        }
    }

    @Throws(IOException::class)
    internal fun deleteLegacyRuntimeModsDir(runtimeModsDir: File) {
        if (!runtimeModsDir.exists()) {
            return
        }
        if (!FileTreeCleaner.deleteRecursively(runtimeModsDir) || runtimeModsDir.exists()) {
            val remaining = FileTreeCleaner.summarizeRemainingEntries(runtimeModsDir)
            val suffix = remaining?.let { ": $it" }.orEmpty()
            throw IOException("Failed to delete legacy runtime mods directory: ${runtimeModsDir.absolutePath}$suffix")
        }
    }

    internal fun <T> withImportArtifactLock(block: () -> T): T {
        return synchronized(importArtifactLock) {
            block()
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
            val separatorIndex = trimmed.indexOf('\t')
            val rawPathToken = if (separatorIndex >= 0) {
                trimmed.substring(0, separatorIndex).trim()
            } else {
                trimmed
            }
            if (!looksLikePathToken(rawPathToken)) {
                return@map line
            }
            val normalizedPath = normalizeSelectionPath?.invoke(rawPathToken)?.trim().orEmpty().ifBlank { rawPathToken }
            val rewrittenSuffix = if (separatorIndex >= 0) {
                trimmed.substring(separatorIndex)
            } else {
                ""
            }
            val targetPath = movedPaths[normalizedPath] ?: return@map if (normalizedPath != rawPathToken) {
                changed = true
                normalizedPath + rewrittenSuffix
            } else {
                line
            }
            changed = true
            targetPath + rewrittenSuffix
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

    private fun cleanupInterruptedImports(libraryDir: File) {
        val files = libraryDir.listFiles() ?: return
        val nowMs = System.currentTimeMillis()
        files.forEach { file ->
            if (!file.isFile) {
                return@forEach
            }
            val name = file.name
            if (isInterruptedImportArtifact(name) && isStaleInterruptedImportArtifact(file, nowMs)) {
                file.delete()
            }
        }
    }

    private fun isInterruptedImportArtifact(name: String): Boolean {
        return name.endsWith(".importing.marker") ||
            (name.contains(".importing") && name.startsWith("."))
    }

    private fun isStaleInterruptedImportArtifact(file: File, nowMs: Long): Boolean {
        val lastModified = file.lastModified()
        return lastModified <= 0L || nowMs - lastModified >= INTERRUPTED_IMPORT_CLEANUP_GRACE_MS
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
        return "basemod.jar" == normalized ||
            "stslib.jar" == normalized ||
            "amethystruntimecompat.jar" == normalized ||
            "ramsaver.jar" == normalized
    }

    private fun findEquivalentLibraryFile(libraryDir: File, source: File): File? {
        val candidates = listOptionalJarFiles(libraryDir)
        candidates.firstOrNull { candidate ->
            candidate.name == source.name && filesHaveSameContent(candidate, source)
        }?.let { return it }
        return candidates.firstOrNull { candidate ->
            filesHaveSameContent(candidate, source)
        }
    }

    private fun filesHaveSameContent(left: File, right: File): Boolean {
        if (!left.isFile || !right.isFile || left.length() != right.length()) {
            return false
        }
        return try {
            var sameContent = true
            left.inputStream().use { leftInput ->
                right.inputStream().use { rightInput ->
                    val leftBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val rightBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (sameContent) {
                        val leftRead = leftInput.read(leftBuffer)
                        val rightRead = rightInput.read(rightBuffer)
                        if (leftRead != rightRead) {
                            sameContent = false
                            break
                        }
                        for (index in 0 until leftRead) {
                            if (leftBuffer[index] != rightBuffer[index]) {
                                sameContent = false
                                break
                            }
                        }
                        if (leftRead < 0) {
                            break
                        }
                    }
                }
            }
            sameContent
        } catch (_: Throwable) {
            false
        }
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

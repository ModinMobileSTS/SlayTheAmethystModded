package io.stamethyst.ui.settings

import android.app.Activity
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import io.stamethyst.backend.mods.ModAtlasFilterCompatPatcher
import io.stamethyst.config.RuntimePaths
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.mods.ModManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class SaveImportResult(
    val importedFiles: Int,
    val backupLabel: String?
)

internal data class ModImportResult(
    val modId: String,
    val modName: String,
    val patchedAtlasEntries: Int,
    val patchedFilterLines: Int
) {
    val wasAtlasPatched: Boolean
        get() = patchedFilterLines > 0
}

private data class SaveArchiveScanResult(
    val importableFiles: Int,
    val targetTopLevelDirs: Set<String>
)

internal object SettingsFileService {
    class ReservedModImportException(
        @JvmField val blockedComponent: String
    ) : IOException("Import blocked for built-in component: $blockedComponent")

    private const val RESERVED_COMPONENT_BASEMOD = "BaseMod"
    private const val RESERVED_COMPONENT_STSLIB = "StSLib"
    private const val RESERVED_COMPONENT_MTS = "ModTheSpire"
    private const val MTS_LOADER_ENTRY = "com/evacipated/cardcrawl/modthespire/Loader.class"

    private val SAVE_IMPORT_TOP_LEVEL_DIRS = arrayOf(
        "betaPreferences",
        "betapreferences",
        "betaPerferences",
        "betaperferences",
        "perference",
        "preferences",
        "perferences",
        "saves",
        "runs",
        "metrics",
        "home",
        "sendToDevs",
        "sendtodevs",
        "multiplayer",
        "multiple"
    )

    private val SAVE_EXPORT_FOLDER_MAPPINGS = arrayOf(
        "multiplayer" to "multiple",
        "saves" to "saves",
        "perference" to "perference",
        "runs" to "runs"
    )

    fun buildSaveExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-saves-export-${formatter.format(Date())}.zip"
    }

    @Throws(IOException::class)
    fun resolveLatestLogShareUri(host: Activity): Uri {
        val logFile = RuntimePaths.latestLog(host)
        if (!logFile.isFile) {
            throw IOException("No log file found: ${logFile.absolutePath}")
        }
        return FileProvider.getUriForFile(host, "${host.packageName}.fileprovider", logFile)
    }

    @Throws(IOException::class)
    fun exportSaveBundle(host: Activity, uri: Uri): Int {
        val stsRoot = RuntimePaths.stsRoot(host)
        host.contentResolver.openOutputStream(uri).use { output ->
            if (output == null) {
                throw IOException("Unable to open destination file")
            }
            ZipOutputStream(output).use { zipOutput ->
                var exportedCount = 0
                for ((sourceFolder, archiveFolder) in SAVE_EXPORT_FOLDER_MAPPINGS) {
                    val sourceRoot = resolveSaveExportSourceFolder(stsRoot, sourceFolder) ?: continue
                    exportedCount += exportSaveFolderToZip(zipOutput, sourceRoot, archiveFolder)
                }
                if (exportedCount <= 0) {
                    val entry = ZipEntry("sts/README.txt")
                    zipOutput.putNextEntry(entry)
                    val message = "No save files found yet.\n" +
                        "Expected folders under: ${stsRoot.absolutePath}\n" +
                        "Folders: multiplayer, saves, perference, runs\n"
                    zipOutput.write(message.toByteArray(StandardCharsets.UTF_8))
                    zipOutput.closeEntry()
                }
                return exportedCount
            }
        }
    }

    fun copyUriToFile(host: Activity, uri: Uri, targetFile: File) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: $parent")
        }
        host.contentResolver.openInputStream(uri).use { input ->
            if (input == null) {
                throw IOException("Unable to open file from picker")
            }
            FileOutputStream(targetFile, false).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    @Throws(IOException::class)
    fun importModJar(host: Activity, uri: Uri): ModImportResult {
        val modsDir = RuntimePaths.modsDir(host)
        if (!modsDir.exists() && !modsDir.mkdirs()) {
            throw IOException("Failed to create mods directory")
        }

        val displayName = resolveDisplayName(host, uri)
        val tempFile = File(modsDir, ".import-${System.nanoTime()}.tmp.jar")
        copyUriToFile(host, uri, tempFile)
        try {
            val manifest = try {
                ModJarSupport.readModManifest(tempFile)
            } catch (error: Throwable) {
                if (isLikelyModTheSpireJar(tempFile, displayName)) {
                    throw ReservedModImportException(RESERVED_COMPONENT_MTS)
                }
                throw error
            }

            val modId = ModManager.normalizeModId(manifest.modId)
            if (modId.isBlank()) {
                throw IOException("modid is empty")
            }
            val blockedComponent = resolveReservedComponent(modId)
            if (blockedComponent != null) {
                throw ReservedModImportException(blockedComponent)
            }

            val patchResult = ModAtlasFilterCompatPatcher.patchMipMapFiltersInPlace(tempFile)
            val targetFile = ModManager.resolveStorageFileForModId(host, modId)
            moveFileReplacing(tempFile, targetFile)
            val modName = manifest.name.trim().ifBlank { modId }
            return ModImportResult(
                modId = modId,
                modName = modName,
                patchedAtlasEntries = patchResult.patchedAtlasEntries,
                patchedFilterLines = patchResult.patchedFilterLines
            )
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    fun resolveDisplayName(host: Activity, uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = host.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    val value = cursor.getString(index)
                    if (!value.isNullOrBlank()) {
                        return value
                    }
                }
            }
            "unknown.jar"
        } catch (_: Throwable) {
            "unknown.jar"
        } finally {
            cursor?.close()
        }
    }

    fun buildReservedModImportMessage(blockedComponents: Collection<String>): String {
        val uniqueComponents = LinkedHashSet<String>()
        blockedComponents.forEach { component ->
            normalizeReservedComponentName(component)?.let { uniqueComponents.add(it) }
        }
        if (uniqueComponents.isEmpty()) {
            uniqueComponents.add(RESERVED_COMPONENT_BASEMOD)
            uniqueComponents.add(RESERVED_COMPONENT_STSLIB)
            uniqueComponents.add(RESERVED_COMPONENT_MTS)
        }

        return buildString {
            append("已拒绝导入以下内置核心组件：\n")
            uniqueComponents.forEach { component ->
                append("- ").append(component).append('\n')
            }
            append("\n")
            append("BaseMod、StSLib、ModTheSpire 已内置并由启动器管理，请不要手动导入。")
        }
    }

    fun buildAtlasPatchImportSummaryMessage(patchedResults: Collection<ModImportResult>): String {
        val mergedByModId = LinkedHashMap<String, ModImportResult>()
        for (result in patchedResults) {
            if (!result.wasAtlasPatched) {
                continue
            }
            val existing = mergedByModId[result.modId]
            if (existing == null) {
                mergedByModId[result.modId] = result
            } else {
                mergedByModId[result.modId] = existing.copy(
                    modName = if (existing.modName.isNotBlank()) existing.modName else result.modName,
                    patchedAtlasEntries = existing.patchedAtlasEntries + result.patchedAtlasEntries,
                    patchedFilterLines = existing.patchedFilterLines + result.patchedFilterLines
                )
            }
        }

        if (mergedByModId.isEmpty()) {
            return "本次导入没有发现需要离线修补的 atlas。"
        }

        return buildString {
            append("以下模组在导入时已离线修补 atlas 过滤设置：\n")
            for (result in mergedByModId.values) {
                append("- ")
                append(result.modName.ifBlank { result.modId })
                append("（modid: ")
                append(result.modId)
                append("）")
                append('\n')
                append("  已修补 atlas: ")
                append(result.patchedAtlasEntries)
                append("，替换 filter 行: ")
                append(result.patchedFilterLines)
                append('\n')
            }
            append('\n')
            append("修补规则：将包含 mipmap 的 filter 行改为 Linear,Linear。")
        }.trimEnd()
    }

    @Throws(IOException::class)
    fun isLegacyJibaoSaveArchive(host: Activity, uri: Uri): Boolean {
        var hasImportableFiles = false
        var hasFlatPreferencesStyleRootEntries = false
        var hasNonPreferencesTargets = false

        host.contentResolver.openInputStream(uri).use { rawInput ->
            if (rawInput == null) {
                throw IOException("Unable to open selected archive")
            }
            java.util.zip.ZipInputStream(rawInput).use { zipInput ->
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    val rawPath = normalizeRawArchiveEntryPath(entry.name) ?: continue

                    if (!entry.isDirectory
                        && rawPath.indexOf('/') < 0
                        && shouldTreatFlatArchiveFileAsPreferences(rawPath)
                    ) {
                        hasFlatPreferencesStyleRootEntries = true
                    }

                    val mappedPath = resolveImportableArchivePath(rawPath) ?: continue
                    val mappedTopLevel = extractTopLevelDirectory(mappedPath)?.lowercase(Locale.ROOT)
                        ?: continue
                    if (entry.isDirectory) {
                        continue
                    }

                    hasImportableFiles = true
                    if (mappedTopLevel != "preferences") {
                        hasNonPreferencesTargets = true
                    }
                }
            }
        }

        return hasImportableFiles
            && hasFlatPreferencesStyleRootEntries
            && !hasNonPreferencesTargets
    }

    @Throws(IOException::class)
    fun importSaveArchive(host: Activity, uri: Uri): SaveImportResult {
        val stsRoot = RuntimePaths.stsRoot(host)
        if (!stsRoot.exists() && !stsRoot.mkdirs()) {
            throw IOException("Failed to create save root: ${stsRoot.absolutePath}")
        }

        val scanResult = scanSaveArchive(host, uri)
        if (scanResult.importableFiles <= 0) {
            throw IOException("Archive did not contain importable save files")
        }

        val backupLabel = backupExistingSavesToDownloads(host, stsRoot)
        clearExistingSaveTargets(stsRoot, scanResult.targetTopLevelDirs)
        val importedFiles = extractSaveArchive(host, uri, stsRoot)
        if (importedFiles <= 0) {
            throw IOException("Archive did not contain importable save files")
        }
        return SaveImportResult(
            importedFiles = importedFiles,
            backupLabel = backupLabel
        )
    }

    @Throws(IOException::class)
    private fun moveFileReplacing(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        if (target.exists() && !target.delete()) {
            throw IOException("Failed to replace existing file: ${target.absolutePath}")
        }
        if (source.renameTo(target)) {
            return
        }

        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        if (!source.delete()) {
            throw IOException("Failed to clean temp file: ${source.absolutePath}")
        }
    }

    @Throws(IOException::class)
    private fun scanSaveArchive(host: Activity, uri: Uri): SaveArchiveScanResult {
        var importableFiles = 0
        val targetTopLevelDirs = LinkedHashSet<String>()
        host.contentResolver.openInputStream(uri).use { rawInput ->
            if (rawInput == null) {
                throw IOException("Unable to open selected archive")
            }
            java.util.zip.ZipInputStream(rawInput).use { zipInput ->
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    val mappedPath = resolveImportableArchivePath(entry.name)
                    if (mappedPath.isNullOrEmpty()) {
                        continue
                    }
                    extractTopLevelDirectory(mappedPath)?.let { targetTopLevelDirs.add(it) }
                    if (entry.isDirectory) {
                        continue
                    }
                    importableFiles++
                }
            }
        }
        return SaveArchiveScanResult(
            importableFiles = importableFiles,
            targetTopLevelDirs = targetTopLevelDirs
        )
    }

    @Throws(IOException::class)
    private fun backupExistingSavesToDownloads(host: Activity, stsRoot: File): String? {
        val sourceFiles = collectSaveFilesForBackup(stsRoot)
        if (sourceFiles.isEmpty()) {
            return null
        }

        val backupFileName = buildSaveBackupFileName()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backupExistingSavesToScopedDownloads(host, stsRoot, sourceFiles, backupFileName)
            "Download/$backupFileName"
        } else {
            backupExistingSavesToLegacyDownloads(stsRoot, sourceFiles, backupFileName)
        }
    }

    private fun collectSaveFilesForBackup(stsRoot: File): List<File> {
        val files = ArrayList<File>()
        for (folderName in SAVE_IMPORT_TOP_LEVEL_DIRS) {
            collectRegularFiles(File(stsRoot, folderName), files)
        }
        return files
    }

    private fun collectRegularFiles(root: File, sink: MutableList<File>) {
        if (!root.exists()) {
            return
        }
        if (root.isFile) {
            sink.add(root)
            return
        }
        val children = root.listFiles() ?: return
        for (child in children) {
            collectRegularFiles(child, sink)
        }
    }

    @Throws(IOException::class)
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun backupExistingSavesToScopedDownloads(
        host: Activity,
        stsRoot: File,
        sourceFiles: List<File>,
        backupFileName: String
    ) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, backupFileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val backupUri = host.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create backup archive in Downloads")

        var success = false
        try {
            host.contentResolver.openOutputStream(backupUri).use { output ->
                if (output == null) {
                    throw IOException("Unable to open backup archive destination")
                }
                writeSaveFilesToZip(output, stsRoot, sourceFiles)
            }
            success = true
        } finally {
            if (success) {
                val pendingValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                host.contentResolver.update(backupUri, pendingValues, null, null)
            } else {
                host.contentResolver.delete(backupUri, null, null)
            }
        }
    }

    @Suppress("DEPRECATION")
    @Throws(IOException::class)
    private fun backupExistingSavesToLegacyDownloads(
        stsRoot: File,
        sourceFiles: List<File>,
        backupFileName: String
    ): String {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("Downloads directory is unavailable")
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw IOException("Failed to create Downloads directory: ${downloadsDir.absolutePath}")
        }

        val backupFile = File(downloadsDir, backupFileName)
        FileOutputStream(backupFile, false).use { output ->
            writeSaveFilesToZip(output, stsRoot, sourceFiles)
        }
        return backupFile.absolutePath
    }

    @Throws(IOException::class)
    private fun writeSaveFilesToZip(output: OutputStream, stsRoot: File, sourceFiles: List<File>) {
        ZipOutputStream(output).use { zipOutput ->
            for (sourceFile in sourceFiles) {
                writeFileToZip(zipOutput, stsRoot, sourceFile)
            }
        }
    }

    private fun buildSaveBackupFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-saves-backup-${formatter.format(Date())}.zip"
    }

    @Throws(IOException::class)
    private fun clearExistingSaveTargets(stsRoot: File, targetTopLevelDirs: Set<String>) {
        val clearTargets = resolveClearTargets(targetTopLevelDirs)
        for (folderName in clearTargets) {
            val target = File(stsRoot, folderName)
            if (!target.exists()) {
                continue
            }
            if (!target.deleteRecursively()) {
                throw IOException("Failed to clear old save path: ${target.absolutePath}")
            }
        }
    }

    @Throws(IOException::class)
    private fun extractSaveArchive(host: Activity, uri: Uri, stsRoot: File): Int {
        val rootCanonical = stsRoot.canonicalPath
        var importedFiles = 0

        host.contentResolver.openInputStream(uri).use { rawInput ->
            if (rawInput == null) {
                throw IOException("Unable to open selected archive")
            }
            java.util.zip.ZipInputStream(rawInput).use { zipInput ->
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    val mappedPath = resolveImportableArchivePath(entry.name)
                    if (mappedPath.isNullOrEmpty()) {
                        continue
                    }

                    val output = File(stsRoot, mappedPath)
                    val outputCanonical = output.canonicalPath
                    if (outputCanonical != rootCanonical
                        && !outputCanonical.startsWith("$rootCanonical${File.separator}")
                    ) {
                        throw IOException("Unsafe archive entry: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        if (!output.exists() && !output.mkdirs()) {
                            throw IOException("Failed to create directory: ${output.absolutePath}")
                        }
                        continue
                    }

                    val parent = output.parentFile
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw IOException("Failed to create directory: ${parent.absolutePath}")
                    }

                    FileOutputStream(output, false).use { out ->
                        while (true) {
                            val read = zipInput.read(buffer)
                            if (read <= 0) {
                                break
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                    importedFiles++
                }
            }
        }
        return importedFiles
    }

    private fun resolveImportableArchivePath(rawEntryName: String?): String? {
        val mappedPath = mapArchiveEntryPath(rawEntryName) ?: return null
        val normalizedPath = normalizeImportTargetPath(mappedPath) ?: return null
        if (normalizedPath.equals("desktop-1.0.jar", ignoreCase = true)) {
            return null
        }
        if (normalizedPath.startsWith("__MACOSX/")) {
            return null
        }
        return normalizedPath
    }

    private fun mapArchiveEntryPath(rawEntryName: String?): String? {
        if (rawEntryName == null) {
            return null
        }

        var path = rawEntryName.replace('\\', '/')
        while (path.startsWith("/")) {
            path = path.substring(1)
        }
        if (path.isEmpty() || path.contains("../")) {
            return null
        }

        val filesSts = path.indexOf("files/sts/")
        path = if (filesSts >= 0) {
            path.substring(filesSts + "files/sts/".length)
        } else if (path.startsWith("sts/")) {
            path.substring("sts/".length)
        } else {
            val nestedSts = path.indexOf("/sts/")
            if (nestedSts >= 0) {
                path.substring(nestedSts + "/sts/".length)
            } else {
                stripWrapperFolder(path)
            }
        }

        while (path.startsWith("/")) {
            path = path.substring(1)
        }
        if (path.isEmpty() || path.contains("../")) {
            return null
        }
        return path
    }

    private fun normalizeRawArchiveEntryPath(rawEntryName: String?): String? {
        if (rawEntryName == null) {
            return null
        }
        var path = rawEntryName.replace('\\', '/')
        while (path.startsWith("/")) {
            path = path.substring(1)
        }
        if (path.isEmpty() || path.contains("../")) {
            return null
        }
        return path
    }

    private fun stripWrapperFolder(path: String): String {
        val firstSlash = path.indexOf('/')
        if (firstSlash <= 0 || firstSlash >= path.length - 1) {
            return path
        }
        val first = path.substring(0, firstSlash).lowercase(Locale.ROOT)
        val remainder = path.substring(firstSlash + 1)
        var second = remainder
        val secondSlash = remainder.indexOf('/')
        if (secondSlash > 0) {
            second = remainder.substring(0, secondSlash)
        }
        second = second.lowercase(Locale.ROOT)

        return if (!isLikelySaveTopLevel(first) && isLikelySaveTopLevel(second)) {
            remainder
        } else {
            path
        }
    }

    private fun normalizeImportTargetPath(path: String): String? {
        var normalizedPath = path.replace('\\', '/')
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1)
        }
        if (normalizedPath.isEmpty() || normalizedPath.contains("../")) {
            return null
        }

        val firstSlash = normalizedPath.indexOf('/')
        val folder = if (firstSlash >= 0) {
            normalizedPath.substring(0, firstSlash)
        } else {
            normalizedPath
        }
        val rest = if (firstSlash >= 0 && firstSlash < normalizedPath.length - 1) {
            normalizedPath.substring(firstSlash + 1)
        } else {
            ""
        }

        if (firstSlash < 0 && shouldTreatFlatArchiveFileAsPreferences(folder)) {
            return "preferences/$folder"
        }

        val mappedFolder = when (folder.lowercase(Locale.ROOT)) {
            "preferences",
            "perference",
            "perferences",
            "betapreferences",
            "betaperferences" -> "preferences"
            "multiplayer",
            "multiple" -> "multiplayer"
            else -> folder
        }
        return if (rest.isEmpty()) {
            mappedFolder
        } else {
            "$mappedFolder/$rest"
        }
    }

    private fun shouldTreatFlatArchiveFileAsPreferences(fileName: String): Boolean {
        val normalized = fileName.trim()
        if (normalized.isEmpty()) {
            return false
        }
        val lower = normalized.lowercase(Locale.ROOT)
        if (isLikelySaveTopLevel(lower)) {
            return false
        }
        if (lower == "desktop-1.0.jar") {
            return false
        }
        if (lower.startsWith("sts")) {
            return true
        }
        if (lower == "mtssettings" || lower.startsWith("the_")) {
            return true
        }
        if (!lower.endsWith(".backup")) {
            return false
        }
        val baseName = lower.removeSuffix(".backup")
        return baseName.startsWith("sts")
            || baseName.startsWith("the_")
            || baseName == "mtssettings"
    }

    private fun resolveClearTargets(targetTopLevelDirs: Set<String>): Set<String> {
        if (targetTopLevelDirs.isEmpty()) {
            return SAVE_IMPORT_TOP_LEVEL_DIRS.toSet()
        }

        val clearTargets = LinkedHashSet<String>()
        for (rawTopLevel in targetTopLevelDirs) {
            val topLevel = rawTopLevel.trim()
            if (topLevel.isEmpty()) {
                continue
            }
            when (topLevel.lowercase(Locale.ROOT)) {
                "preferences",
                "perference",
                "perferences",
                "betapreferences",
                "betaperferences" -> {
                    clearTargets.add("preferences")
                    clearTargets.add("perference")
                    clearTargets.add("perferences")
                    clearTargets.add("betaPreferences")
                    clearTargets.add("betapreferences")
                    clearTargets.add("betaPerferences")
                    clearTargets.add("betaperferences")
                }

                "multiplayer",
                "multiple" -> {
                    clearTargets.add("multiplayer")
                    clearTargets.add("multiple")
                }

                "sendtodevs",
                "sendtodev" -> {
                    clearTargets.add("sendToDevs")
                    clearTargets.add("sendtodevs")
                }

                else -> clearTargets.add(topLevel)
            }
        }
        return clearTargets
    }

    private fun extractTopLevelDirectory(path: String): String? {
        var normalizedPath = path.replace('\\', '/')
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1)
        }
        if (normalizedPath.isEmpty()) {
            return null
        }
        val firstSlash = normalizedPath.indexOf('/')
        return if (firstSlash >= 0) {
            normalizedPath.substring(0, firstSlash)
        } else {
            normalizedPath
        }
    }

    private fun isLikelySaveTopLevel(folder: String): Boolean {
        return folder == "betapreferences"
            || folder == "perference"
            || folder == "preferences"
            || folder == "perferences"
            || folder == "betaperferences"
            || folder == "saves"
            || folder == "sendtodevs"
            || folder == "runs"
            || folder == "metrics"
            || folder == "home"
            || folder == "multiplayer"
            || folder == "multiple"
    }

    private fun resolveReservedComponent(modId: String): String? {
        return when (ModManager.normalizeModId(modId)) {
            ModManager.MOD_ID_BASEMOD -> RESERVED_COMPONENT_BASEMOD
            ModManager.MOD_ID_STSLIB -> RESERVED_COMPONENT_STSLIB
            "modthespire" -> RESERVED_COMPONENT_MTS
            else -> null
        }
    }

    private fun normalizeReservedComponentName(rawComponent: String?): String? {
        val normalized = rawComponent?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (normalized.isEmpty()) {
            return null
        }
        return when (normalized) {
            RESERVED_COMPONENT_BASEMOD.lowercase(Locale.ROOT),
            "basemod.jar",
            ModManager.MOD_ID_BASEMOD -> RESERVED_COMPONENT_BASEMOD

            RESERVED_COMPONENT_STSLIB.lowercase(Locale.ROOT),
            "stslib.jar",
            ModManager.MOD_ID_STSLIB -> RESERVED_COMPONENT_STSLIB

            RESERVED_COMPONENT_MTS.lowercase(Locale.ROOT),
            "modthespire.jar",
            "modthespire" -> RESERVED_COMPONENT_MTS

            else -> rawComponent.trim()
        }
    }

    private fun isLikelyModTheSpireJar(jarFile: File, displayName: String): Boolean {
        if (looksLikeModTheSpireName(displayName)) {
            return true
        }
        return try {
            ZipFile(jarFile).use { zipFile ->
                zipFile.getEntry(MTS_LOADER_ENTRY) != null
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun looksLikeModTheSpireName(displayName: String?): Boolean {
        val normalized = displayName?.trim()?.lowercase(Locale.ROOT) ?: return false
        if (normalized.isEmpty()) {
            return false
        }
        return (normalized == "modthespire.jar")
            || (normalized.endsWith(".jar") && normalized.contains("modthespire"))
    }

    private fun resolveSaveExportSourceFolder(stsRoot: File, sourceFolder: String): File? {
        val candidates = when (sourceFolder.lowercase(Locale.ROOT)) {
            "multiplayer" -> arrayOf("multiplayer", "multiple")
            "perference" -> arrayOf("perference", "preferences", "perferences")
            else -> arrayOf(sourceFolder)
        }
        for (candidateName in candidates) {
            val candidate = File(stsRoot, candidateName)
            if (candidate.exists()) {
                return candidate
            }
        }
        return null
    }

    @Throws(IOException::class)
    private fun exportSaveFolderToZip(zipOutput: ZipOutputStream, sourceRoot: File, archiveRoot: String): Int {
        if (!sourceRoot.exists()) {
            return 0
        }
        val sourceFiles = ArrayList<File>()
        collectRegularFiles(sourceRoot, sourceFiles)
        var exportedCount = 0
        for (sourceFile in sourceFiles) {
            val entryName = buildSaveExportEntryName(sourceRoot, archiveRoot, sourceFile)
            writeFileToZip(zipOutput, sourceFile, entryName)
            exportedCount++
        }
        return exportedCount
    }

    @Throws(IOException::class)
    private fun buildSaveExportEntryName(sourceRoot: File, archiveRoot: String, sourceFile: File): String {
        val sourceRootPath = sourceRoot.canonicalPath
        val sourceFilePath = sourceFile.canonicalPath
        val relativePath = if (sourceFilePath.startsWith("$sourceRootPath${File.separator}")) {
            sourceFilePath.substring(sourceRootPath.length + 1)
        } else {
            sourceFile.name
        }
        val normalizedRelativePath = relativePath.replace('\\', '/')
        return "sts/$archiveRoot/$normalizedRelativePath"
    }

    @Throws(IOException::class)
    private fun writeFileToZip(zipOutput: ZipOutputStream, stsRoot: File, sourceFile: File) {
        val rootPath = stsRoot.canonicalPath
        val filePath = sourceFile.canonicalPath
        val relativePath = if (filePath.startsWith("$rootPath${File.separator}")) {
            filePath.substring(rootPath.length + 1)
        } else {
            sourceFile.name
        }
        val entryName = "sts/${relativePath.replace('\\', '/')}"
        writeFileToZip(zipOutput, sourceFile, entryName)
    }

    @Throws(IOException::class)
    private fun writeFileToZip(zipOutput: ZipOutputStream, sourceFile: File, entryName: String) {
        val entry = ZipEntry(entryName)
        if (sourceFile.lastModified() > 0) {
            entry.time = sourceFile.lastModified()
        }
        zipOutput.putNextEntry(entry)
        FileInputStream(sourceFile).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                zipOutput.write(buffer, 0, read)
            }
        }
        zipOutput.closeEntry()
    }

}

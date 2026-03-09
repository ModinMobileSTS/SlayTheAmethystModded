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
import io.stamethyst.backend.diag.DiagnosticsProcessClient
import io.stamethyst.backend.launch.JvmLogRotationManager
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.FrierenModCompatPatcher
import io.stamethyst.backend.mods.ModAtlasFilterCompatPatcher
import io.stamethyst.backend.mods.ModManifestRootCompatPatcher
import io.stamethyst.backend.mods.MtsLaunchManifestValidator
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
    val patchedFilterLines: Int,
    val patchedManifestRootEntries: Int = 0,
    val patchedManifestRootPrefix: String = "",
    val patchedFrierenAntiPirateMethod: Boolean = false
) {
    val wasAtlasPatched: Boolean
        get() = patchedFilterLines > 0
    val wasManifestRootPatched: Boolean
        get() = patchedManifestRootEntries > 0
    val wasFrierenAntiPiratePatched: Boolean
        get() = patchedFrierenAntiPirateMethod
    val hasCompatibilityPatches: Boolean
        get() = wasAtlasPatched || wasManifestRootPatched || wasFrierenAntiPiratePatched
}

internal data class ModBatchImportResult(
    val importedCount: Int,
    val errors: List<String>,
    val blockedComponents: List<String>,
    val compressedArchives: List<String>,
    val patchedResults: List<ModImportResult>
) {
    val failedCount: Int
        get() = errors.size
    val blockedCount: Int
        get() = blockedComponents.size
    val compressedArchiveCount: Int
        get() = compressedArchives.size
    val firstError: String?
        get() = errors.firstOrNull()
}

private data class SaveArchiveScanResult(
    val importableFiles: Int,
    val targetTopLevelDirs: Set<String>
)

private data class ModExportSource(
    val entryName: String,
    val file: File? = null,
    val assetPath: String? = null
)

internal object SettingsFileService {
    class ReservedModImportException(
        @JvmField val blockedComponent: String
    ) : IOException("Import blocked for built-in component: $blockedComponent")

    private const val RESERVED_COMPONENT_BASEMOD = "BaseMod"
    private const val RESERVED_COMPONENT_STSLIB = "StSLib"
    private const val RESERVED_COMPONENT_MTS = "ModTheSpire"
    private const val FRIEREN_MOD_ID = "frierenmod"
    private const val MTS_LOADER_ENTRY = "com/evacipated/cardcrawl/modthespire/Loader.class"
    private val MOD_IMPORT_ARCHIVE_EXTENSIONS = arrayOf(
        ".zip",
        ".rar",
        ".7z",
        ".tar",
        ".tgz",
        ".tar.gz",
        ".gz",
        ".bz2",
        ".tar.bz2",
        ".xz",
        ".tar.xz",
        ".zst",
        ".tar.zst",
        ".lz4",
        ".tar.lz4"
    )
    private val MOD_IMPORT_ARCHIVE_MIME_TYPES = setOf(
        "application/zip",
        "application/x-zip",
        "application/x-zip-compressed",
        "multipart/x-zip",
        "application/vnd.rar",
        "application/rar",
        "application/x-rar",
        "application/x-rar-compressed",
        "application/x-7z-compressed",
        "application/x-tar",
        "application/gzip",
        "application/x-gzip",
        "application/x-bzip2",
        "application/x-xz",
        "application/zstd",
        "application/x-zstd"
    )

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

    fun buildJvmLogExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-jvm-logs-export-${formatter.format(Date())}.zip"
    }

    fun buildModsExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-mods-export-${formatter.format(Date())}.zip"
    }

    @Throws(IOException::class)
    fun resolveJvmLogsShareUri(host: Activity): Uri {
        val archiveFile = DiagnosticsProcessClient.buildJvmLogShareArchive(host).archiveFile
        return FileProvider.getUriForFile(host, "${host.packageName}.fileprovider", archiveFile)
    }

    @Throws(IOException::class)
    fun exportJvmLogBundle(host: Activity, uri: Uri): Int {
        return DiagnosticsProcessClient.exportJvmLogBundle(host, uri)
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

    @Throws(IOException::class)
    fun exportModsBundle(host: Activity, uri: Uri): Int {
        host.contentResolver.openOutputStream(uri).use { output ->
            if (output == null) {
                throw IOException("Unable to open destination file")
            }
            ZipOutputStream(output).use { zipOutput ->
                val sources = collectModExportSources(host)
                if (sources.isEmpty()) {
                    val entry = ZipEntry("mods/README.txt")
                    zipOutput.putNextEntry(entry)
                    val message = "No mod jars found yet.\n" +
                        "Expected files:\n" +
                        "- ${RuntimePaths.importedMtsJar(host).absolutePath}\n" +
                        "- ${RuntimePaths.modsDir(host).absolutePath}\n"
                    zipOutput.write(message.toByteArray(StandardCharsets.UTF_8))
                    zipOutput.closeEntry()
                    return 0
                }

                sources.forEach { source ->
                    val entryName = "mods/${source.entryName}"
                    val file = source.file
                    if (file != null) {
                        writeFileToZip(zipOutput, file, entryName)
                        return@forEach
                    }
                    val assetPath = source.assetPath
                    if (!assetPath.isNullOrBlank()) {
                        writeAssetToZip(host, zipOutput, assetPath, entryName)
                    }
                }
                return sources.size
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
            var patchedManifestRootEntries = 0
            var patchedManifestRootPrefix = ""
            if (CompatibilitySettings.isModManifestRootCompatEnabled(host)) {
                val patchResult = ModManifestRootCompatPatcher.patchNestedManifestRootInPlace(tempFile)
                patchedManifestRootEntries = patchResult.patchedFileEntries
                patchedManifestRootPrefix = patchResult.sourceRootPrefix
            }

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
            val launchModId = try {
                MtsLaunchManifestValidator.resolveLaunchModId(tempFile).trim()
            } catch (_: Throwable) {
                ""
            }

            var patchedAtlasEntries = 0
            var patchedFilterLines = 0
            if (CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(host)) {
                val patchResult = ModAtlasFilterCompatPatcher.patchMipMapFiltersInPlace(tempFile)
                patchedAtlasEntries = patchResult.patchedAtlasEntries
                patchedFilterLines = patchResult.patchedFilterLines
            }
            var patchedFrierenAntiPirateMethod = false
            if (CompatibilitySettings.isFrierenModCompatEnabled(host)
                && modId == FRIEREN_MOD_ID
            ) {
                patchedFrierenAntiPirateMethod =
                    FrierenModCompatPatcher.patchAntiPirateInPlace(tempFile).patchedAntiPirateMethod
            }
            ModManager.removeExistingOptionalModsForImport(
                context = host,
                normalizedModId = modId,
                launchModId = launchModId,
                excludedPath = tempFile.absolutePath
            )
            val requestedFileName = if (displayName.isNotBlank()) displayName else "$modId.jar"
            val targetFile = ModManager.resolveStorageFileForImportedMod(host, requestedFileName)
            moveFileReplacing(tempFile, targetFile)
            val modName = manifest.name.trim().ifBlank { modId }
            return ModImportResult(
                modId = modId,
                modName = modName,
                patchedAtlasEntries = patchedAtlasEntries,
                patchedFilterLines = patchedFilterLines,
                patchedManifestRootEntries = patchedManifestRootEntries,
                patchedManifestRootPrefix = patchedManifestRootPrefix,
                patchedFrierenAntiPirateMethod = patchedFrierenAntiPirateMethod
            )
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    @Throws(IOException::class)
    private fun writeJvmLogsBundle(host: Activity, output: OutputStream): Int {
        val logFiles = JvmLogRotationManager.listLogFiles(host)
        ZipOutputStream(output).use { zipOutput ->
            writeTextEntry(
                zipOutput,
                "sts/jvm_logs/device_info.txt",
                buildJvmLogDeviceInfo(host)
            )
            if (logFiles.isEmpty()) {
                val message = "No JVM logs found.\n" +
                    "Expected files:\n" +
                    "- ${RuntimePaths.latestLog(host).absolutePath}\n" +
                    "- ${RuntimePaths.jvmLogsDir(host).absolutePath}\n"
                writeTextEntry(zipOutput, "sts/jvm_logs/README.txt", message)
                return 0
            }
            for (logFile in logFiles) {
                writeFileToZip(zipOutput, logFile, "sts/jvm_logs/${logFile.name}")
            }
            return logFiles.size
        }
    }

    fun importModJars(host: Activity, uris: Collection<Uri>): ModBatchImportResult {
        var imported = 0
        val errors = ArrayList<String>()
        val blockedComponents = LinkedHashSet<String>()
        val compressedArchives = LinkedHashSet<String>()
        val patchedMods = LinkedHashMap<String, ModImportResult>()

        for (uri in uris) {
            val displayName = resolveDisplayName(host, uri)
            if (isLikelyCompressedArchive(host, uri, displayName)) {
                compressedArchives.add(displayName)
                continue
            }
            try {
                val result = importModJar(host, uri)
                imported++
                if (result.hasCompatibilityPatches) {
                    val existing = patchedMods[result.modId]
                    if (existing == null) {
                        patchedMods[result.modId] = result
                    } else {
                        patchedMods[result.modId] = existing.copy(
                            modName = if (existing.modName.isNotBlank()) existing.modName else result.modName,
                            patchedAtlasEntries = existing.patchedAtlasEntries + result.patchedAtlasEntries,
                            patchedFilterLines = existing.patchedFilterLines + result.patchedFilterLines,
                            patchedManifestRootEntries = existing.patchedManifestRootEntries + result.patchedManifestRootEntries,
                            patchedManifestRootPrefix = if (existing.patchedManifestRootPrefix.isNotBlank()) {
                                existing.patchedManifestRootPrefix
                            } else {
                                result.patchedManifestRootPrefix
                            },
                            patchedFrierenAntiPirateMethod = existing.patchedFrierenAntiPirateMethod ||
                                result.patchedFrierenAntiPirateMethod
                        )
                    }
                }
            } catch (error: Throwable) {
                if (error is ReservedModImportException) {
                    blockedComponents.add(error.blockedComponent)
                } else {
                    errors.add("$displayName: ${error.message}")
                }
            }
        }

        return ModBatchImportResult(
            importedCount = imported,
            errors = errors,
            blockedComponents = blockedComponents.toList(),
            compressedArchives = compressedArchives.toList(),
            patchedResults = patchedMods.values.toList()
        )
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

    fun isLikelyCompressedArchive(
        host: Activity,
        uri: Uri,
        displayName: String = resolveDisplayName(host, uri)
    ): Boolean {
        if (looksLikeCompressedArchiveName(displayName)) {
            return true
        }
        val normalizedMime = host.contentResolver.getType(uri)
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return false
        return normalizedMime in MOD_IMPORT_ARCHIVE_MIME_TYPES
    }

    fun buildCompressedArchiveImportMessage(archiveDisplayNames: Collection<String>): String {
        val uniqueNames = LinkedHashSet<String>()
        archiveDisplayNames.forEach { rawName ->
            val normalized = rawName.trim()
            if (normalized.isNotEmpty()) {
                uniqueNames.add(normalized)
            }
        }
        return buildString {
            append("检测到以下文件是压缩包，不能直接作为模组导入：\n")
            if (uniqueNames.isEmpty()) {
                append("- 未知文件\n")
            } else {
                uniqueNames.forEach { name ->
                    append("- ").append(name).append('\n')
                }
            }
            append("\n请先解压压缩包，选择其中的 .jar 模组文件再导入。")
        }.trimEnd()
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

    fun buildManifestRootPatchImportSummaryMessage(patchedResults: Collection<ModImportResult>): String {
        val mergedByModId = LinkedHashMap<String, ModImportResult>()
        for (result in patchedResults) {
            if (!result.wasManifestRootPatched) {
                continue
            }
            val existing = mergedByModId[result.modId]
            if (existing == null) {
                mergedByModId[result.modId] = result
            } else {
                mergedByModId[result.modId] = existing.copy(
                    modName = if (existing.modName.isNotBlank()) existing.modName else result.modName,
                    patchedManifestRootEntries = existing.patchedManifestRootEntries + result.patchedManifestRootEntries,
                    patchedManifestRootPrefix = if (existing.patchedManifestRootPrefix.isNotBlank()) {
                        existing.patchedManifestRootPrefix
                    } else {
                        result.patchedManifestRootPrefix
                    }
                )
            }
        }

        if (mergedByModId.isEmpty()) {
            return "本次导入没有发现需要修补的 ModTheSpire.json 根路径问题。"
        }

        return buildString {
            append("以下模组在导入时已自动修补 ModTheSpire.json 根路径：\n")
            for (result in mergedByModId.values) {
                append("- ")
                append(result.modName.ifBlank { result.modId })
                append("（modid: ")
                append(result.modId)
                append("）")
                append('\n')
                append("  已迁移文件: ")
                append(result.patchedManifestRootEntries)
                val normalizedPrefix = normalizeManifestRootPrefixForDisplay(result.patchedManifestRootPrefix)
                if (normalizedPrefix.isNotEmpty()) {
                    append("，原外层目录: ")
                    append(normalizedPrefix)
                }
                append('\n')
            }
            append('\n')
            append("修补规则：将“外层目录/ModTheSpire.json”结构扁平化到 jar 根目录。")
        }.trimEnd()
    }

    fun buildFrierenPatchImportSummaryMessage(patchedResults: Collection<ModImportResult>): String {
        val mergedByModId = LinkedHashMap<String, ModImportResult>()
        for (result in patchedResults) {
            if (!result.wasFrierenAntiPiratePatched) {
                continue
            }
            val existing = mergedByModId[result.modId]
            if (existing == null) {
                mergedByModId[result.modId] = result
            } else {
                mergedByModId[result.modId] = existing.copy(
                    modName = if (existing.modName.isNotBlank()) existing.modName else result.modName,
                    patchedFrierenAntiPirateMethod = existing.patchedFrierenAntiPirateMethod ||
                        result.patchedFrierenAntiPirateMethod
                )
            }
        }

        if (mergedByModId.isEmpty()) {
            return "本次导入没有发现需要修补的 FrierenMod 启动阻塞逻辑。"
        }

        return buildString {
            append("以下模组在导入时已自动修补 FrierenMod 启动阻塞逻辑：\n")
            for (result in mergedByModId.values) {
                append("- ")
                append(result.modName.ifBlank { result.modId })
                append("（modid: ")
                append(result.modId)
                append("）")
                append('\n')
                append("  已移除 FrierenMod/utils/AntiPirateHelper.antiPirate() 的桌面弹窗阻塞逻辑")
                append('\n')
            }
            append('\n')
            append("修补规则：将 antiPirate() 方法改为空实现，避免 Android 启动卡在 97%。")
        }.trimEnd()
    }

    private fun normalizeManifestRootPrefixForDisplay(prefix: String?): String {
        var normalized = prefix?.trim().orEmpty().replace('\\', '/')
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1)
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.dropLast(1)
        }
        return normalized
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

    private fun looksLikeCompressedArchiveName(displayName: String?): Boolean {
        val normalized = displayName?.trim()?.lowercase(Locale.ROOT) ?: return false
        if (normalized.isEmpty() || normalized.endsWith(".jar")) {
            return false
        }
        return MOD_IMPORT_ARCHIVE_EXTENSIONS.any { extension ->
            normalized.endsWith(extension)
        }
    }

    private fun collectModExportSources(host: Activity): List<ModExportSource> {
        val sources = LinkedHashMap<String, ModExportSource>()

        fun addFile(file: File?) {
            if (file == null || !file.isFile) {
                return
            }
            val entryName = file.name.trim()
            if (entryName.isEmpty()) {
                return
            }
            sources.putIfAbsent(entryName, ModExportSource(entryName = entryName, file = file))
        }

        fun addAssetIfMissing(entryName: String, assetPath: String) {
            val normalizedEntryName = entryName.trim()
            if (normalizedEntryName.isEmpty() || sources.containsKey(normalizedEntryName)) {
                return
            }
            if (!hasAsset(host, assetPath)) {
                return
            }
            sources[normalizedEntryName] = ModExportSource(
                entryName = normalizedEntryName,
                assetPath = assetPath
            )
        }

        addFile(RuntimePaths.importedMtsJar(host))

        val modFiles = RuntimePaths.modsDir(host)
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.filter { it.name.endsWith(".jar", ignoreCase = true) }
            ?.sortedWith(compareBy<File>({ it.name.lowercase(Locale.ROOT) }, { it.name }))
            ?.toList()
            .orEmpty()
        modFiles.forEach(::addFile)

        addAssetIfMissing("ModTheSpire.jar", "components/mods/ModTheSpire.jar")
        addAssetIfMissing("BaseMod.jar", "components/mods/BaseMod.jar")
        addAssetIfMissing("StSLib.jar", "components/mods/StSLib.jar")

        return sources.values.toList()
    }

    private fun hasAsset(host: Activity, assetPath: String): Boolean {
        return try {
            host.assets.open(assetPath).use { true }
        } catch (_: Throwable) {
            false
        }
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

    private fun buildJvmLogDeviceInfo(host: Activity): String = buildString {
        val launcherVersion = resolveLauncherVersion(host)
        append("launcher.package=").append(host.packageName).append('\n')
        append("launcher.versionName=").append(launcherVersion.first).append('\n')
        append("launcher.versionCode=").append(launcherVersion.second).append('\n')
        append("device.manufacturer=").append(normalizeInfoValue(Build.MANUFACTURER)).append('\n')
        append("device.brand=").append(normalizeInfoValue(Build.BRAND)).append('\n')
        append("device.model=").append(normalizeInfoValue(Build.MODEL)).append('\n')
        append("device.device=").append(normalizeInfoValue(Build.DEVICE)).append('\n')
        append("device.product=").append(normalizeInfoValue(Build.PRODUCT)).append('\n')
        append("device.hardware=").append(normalizeInfoValue(Build.HARDWARE)).append('\n')
        append("android.release=").append(normalizeInfoValue(Build.VERSION.RELEASE)).append('\n')
        append("android.sdkInt=").append(Build.VERSION.SDK_INT).append('\n')
        append("android.securityPatch=").append(normalizeInfoValue(Build.VERSION.SECURITY_PATCH)).append('\n')
        append("device.abis=").append(Build.SUPPORTED_ABIS.joinToString(", ").ifBlank { "unknown" }).append('\n')
        append("device.fingerprint=").append(normalizeInfoValue(Build.FINGERPRINT)).append('\n')
    }

    private fun normalizeInfoValue(value: String?): String {
        return value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "unknown"
    }

    @Suppress("DEPRECATION")
    private fun resolveLauncherVersion(host: Activity): Pair<String, String> {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                host.packageManager.getPackageInfo(
                    host.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                host.packageManager.getPackageInfo(host.packageName, 0)
            }
            val versionName = normalizeInfoValue(packageInfo.versionName)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                packageInfo.versionCode.toString()
            }
            versionName to versionCode
        } catch (_: Throwable) {
            "unknown" to "unknown"
        }
    }

    @Throws(IOException::class)
    private fun writeTextEntry(zipOutput: ZipOutputStream, entryName: String, content: String) {
        val entry = ZipEntry(entryName)
        zipOutput.putNextEntry(entry)
        zipOutput.write(content.toByteArray(StandardCharsets.UTF_8))
        zipOutput.closeEntry()
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

    @Throws(IOException::class)
    private fun writeAssetToZip(
        host: Activity,
        zipOutput: ZipOutputStream,
        assetPath: String,
        entryName: String
    ) {
        val entry = ZipEntry(entryName)
        zipOutput.putNextEntry(entry)
        host.assets.open(assetPath).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                if (read == 0) {
                    continue
                }
                zipOutput.write(buffer, 0, read)
            }
        }
        zipOutput.closeEntry()
    }

}

package io.stamethyst.ui.settings.files

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.common.*
import io.stamethyst.ui.settings.core.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.sections.*
import io.stamethyst.ui.settings.services.*
import io.stamethyst.ui.settings.steamcloud.*

import android.app.Activity
import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.system.ErrnoException
import android.system.Os
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import io.stamethyst.R
import io.stamethyst.backend.diag.DiagnosticsArchiveBuilder
import io.stamethyst.backend.diag.DiagnosticsProcessClient
import io.stamethyst.backend.file_interactive.FileShareCompat
import io.stamethyst.backend.launch.JvmLogRotationManager
import io.stamethyst.backend.mods.ImportedModPatchInfo
import io.stamethyst.backend.resources.RuntimeResourceProvider
import io.stamethyst.backend.steamcloud.SteamCloudLiveSaveLease
import io.stamethyst.backend.steamcloud.SteamCloudStagedPathReplacement
import io.stamethyst.backend.steamcloud.SteamCloudStagedPathStore
import io.stamethyst.config.RuntimePaths
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.ui.main.ModAliasStore
import io.stamethyst.ui.main.normalizeModExportFileName
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
import java.util.zip.ZipOutputStream

internal data class SaveImportResult(
    val importedFiles: Int,
    val backupLabel: String?
)

private data class SaveArchiveScanResult(
    val importableFiles: Int,
    val targetTopLevelDirs: Set<String>
)

private data class ModExportSource(
    val entryName: String,
    val file: File? = null,
    val assetPath: String? = null
)

internal fun interface ArchiveExportProgressCallback {
    fun onProgress(percent: Int)
}

private fun interface ZipEntryWriteProgressCallback {
    fun onBytesWritten(byteCount: Long)
}

internal object SettingsFileService {
    fun buildSaveExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-saves-export-${formatter.format(Date())}.zip"
    }

    fun buildJvmLogExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-jvm-logs-export-${formatter.format(Date())}.zip"
    }

    fun buildPerformanceLogExportFileName(): String {
        return DiagnosticsArchiveBuilder.buildPerformanceExportFileName()
    }

    fun buildModsExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-mods-export-${formatter.format(Date())}.zip"
    }

    @Throws(IOException::class)
    fun resolveJvmLogsShareUri(host: Activity): Uri {
        val archiveFile = DiagnosticsProcessClient.buildJvmLogShareArchive(host).archiveFile
        return FileShareCompat.resolveShareUri(host, archiveFile)
    }

    @Throws(IOException::class)
    fun exportJvmLogBundle(host: Activity, uri: Uri): Int {
        return DiagnosticsProcessClient.exportJvmLogBundle(host, uri)
    }

    @Throws(IOException::class)
    fun exportPerformanceLogBundle(host: Activity, uri: Uri): Int {
        return DiagnosticsProcessClient.exportPerformanceLogBundle(host, uri)
    }

    @Throws(IOException::class)
    fun exportSaveBundle(host: Activity, uri: Uri): Int {
        val stsRoot = RuntimePaths.stsRoot(host)
        val sourceRoots = SaveArchiveLayout.existingSourceDirectories(stsRoot)
        host.contentResolver.openOutputStream(uri).use { output ->
            if (output == null) {
                throw IOException("Unable to open destination file")
            }
            ZipOutputStream(output).use { zipOutput ->
                val exportedCount = writeSaveDirectoriesToZip(zipOutput, sourceRoots)
                if (exportedCount <= 0) {
                    val entry = ZipEntry("sts/README.txt")
                    zipOutput.putNextEntry(entry)
                    val message = "No save files found yet.\n" +
                        "Expected folders under: ${stsRoot.absolutePath}\n" +
                        "Folders: ${SaveArchiveLayout.supportedDirectoryDisplayText()}\n"
                    zipOutput.write(message.toByteArray(StandardCharsets.UTF_8))
                    zipOutput.closeEntry()
                }
                return exportedCount
            }
        }
    }

    @Throws(IOException::class)
    fun exportModsBundle(
        host: Activity,
        uri: Uri,
        progressCallback: ArchiveExportProgressCallback? = null
    ): Int {
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
                        "- ${RuntimePaths.optionalModsLibraryDir(host).absolutePath}\n"
                    zipOutput.write(message.toByteArray(StandardCharsets.UTF_8))
                    zipOutput.closeEntry()
                    reportArchiveExportProgress(progressCallback, 100)
                    return 0
                }

                reportArchiveExportProgress(progressCallback, 0)
                val totalSources = sources.size.coerceAtLeast(1)
                sources.forEachIndexed { index, source ->
                    val entryName = "mods/${source.entryName}"
                    val startPercent = (index * 100) / totalSources
                    val endPercent = ((index + 1) * 100) / totalSources
                    reportArchiveExportProgress(progressCallback, startPercent)
                    val file = source.file
                    if (file != null) {
                        val totalBytes = file.length().coerceAtLeast(1L)
                        var writtenBytes = 0L
                        var lastReportedPercent = startPercent
                        writeFileToZip(
                            zipOutput = zipOutput,
                            sourceFile = file,
                            entryName = entryName,
                            progressCallback = ZipEntryWriteProgressCallback { byteCount ->
                                writtenBytes += byteCount
                                if (endPercent > startPercent) {
                                    val mappedProgress = startPercent + (
                                        (writtenBytes.coerceAtMost(totalBytes) * (endPercent - startPercent).toLong()) /
                                            totalBytes
                                        ).toInt()
                                    if (mappedProgress > lastReportedPercent) {
                                        lastReportedPercent = mappedProgress
                                        reportArchiveExportProgress(progressCallback, mappedProgress)
                                    }
                                }
                            }
                        )
                    } else {
                        val assetPath = source.assetPath
                        if (!assetPath.isNullOrBlank()) {
                            writeAssetToZip(host, zipOutput, assetPath, entryName)
                        }
                    }
                    reportArchiveExportProgress(progressCallback, endPercent)
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
    fun importUriToFileAtomically(
        host: Activity,
        uri: Uri,
        targetFile: File,
        validator: ((File) -> Unit)? = null
    ) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        val tempFile = File(
            parent ?: targetFile.absoluteFile.parentFile ?: throw IOException("Target has no parent"),
            ".${targetFile.name}.${System.nanoTime()}.import.tmp"
        )
        try {
            copyUriToFile(host, uri, tempFile)
            validator?.invoke(tempFile)
            replaceFileAtomically(tempFile, targetFile)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    @Throws(IOException::class)
    fun importFileToFileAtomically(
        sourceFile: File,
        targetFile: File,
        validator: ((File) -> Unit)? = null
    ) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        if (!sourceFile.isFile || sourceFile.length() == 0L) {
            throw IOException("Source file not found or empty: ${sourceFile.absolutePath}")
        }
        val tempFile = File(
            parent ?: targetFile.absoluteFile.parentFile ?: throw IOException("Target has no parent"),
            ".${targetFile.name}.${System.nanoTime()}.import.tmp"
        )
        try {
            sourceFile.inputStream().use { input ->
                FileOutputStream(tempFile, false).use { output ->
                    input.copyTo(output)
                }
            }
            validator?.invoke(tempFile)
            replaceFileAtomically(tempFile, targetFile)
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
            var exportedCount = 0
            writeTextEntry(
                zipOutput,
                "sts/jvm_logs/device_info.txt",
                buildJvmLogDeviceInfo(host)
            )
            val auditFile = RuntimePaths.performanceLaunchAuditLog(host)
            if (logFiles.isEmpty() && !auditFile.isFile) {
                val message = "No JVM logs found.\n" +
                    "Expected files:\n" +
                    "- ${RuntimePaths.latestLog(host).absolutePath}\n" +
                    "- ${RuntimePaths.jvmLogsDir(host).absolutePath}\n"
                writeTextEntry(zipOutput, "sts/jvm_logs/README.txt", message)
                return 0
            }
            for (logFile in logFiles) {
                writeFileToZip(zipOutput, logFile, "sts/jvm_logs/${logFile.name}")
                exportedCount++
            }
            if (auditFile.isFile) {
                writeFileToZip(zipOutput, auditFile, "sts/jvm_logs/${auditFile.name}")
                exportedCount++
            }
            return exportedCount
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

    fun buildModImportPatchDetailMessage(
        context: Context,
        patchInfo: ImportedModPatchInfo
    ): String {
        val sections = ArrayList<String>()
        fun addSection(titleResId: Int, detail: String, rule: String) {
            sections.add(
                buildString {
                    append(context.getString(titleResId))
                    append('\n')
                    append(detail.trim())
                    append('\n')
                    append(rule.trim())
                }
            )
        }

        if (patchInfo.wasAtlasPatched) {
            addSection(
                titleResId = R.string.main_mod_patch_section_atlas_title,
                detail = context.getString(
                    R.string.mod_import_atlas_message_item_detail,
                    patchInfo.patchedAtlasEntries,
                    patchInfo.patchedFilterLines
                ),
                rule = context.getString(R.string.mod_import_atlas_message_rule)
            )
        }
        if (patchInfo.wasAtlasDownscaled) {
            addSection(
                titleResId = R.string.main_mod_patch_section_downscale_title,
                detail = context.getString(
                    R.string.mod_import_atlas_downscale_message_item_detail,
                    patchInfo.downscaledAtlasEntries,
                    patchInfo.downscaledAtlasPageEntries,
                    formatRuntimeMemorySaved(patchInfo.downscaledAtlasRuntimeMemorySavedMb)
                ),
                rule = context.getString(R.string.mod_import_atlas_downscale_message_rule)
            )
        }
        if (patchInfo.wasManifestRootPatched) {
            val detail = buildString {
                append(
                    context.getString(
                        R.string.mod_import_manifest_message_item_detail,
                        patchInfo.patchedManifestRootEntries
                    ).trim()
                )
                val normalizedPrefix =
                    normalizeManifestRootPrefixForDisplay(patchInfo.patchedManifestRootPrefix)
                if (normalizedPrefix.isNotEmpty()) {
                    append(
                        context.getString(
                            R.string.mod_import_manifest_message_item_prefix,
                            normalizedPrefix
                        )
                    )
                }
            }
            addSection(
                titleResId = R.string.main_mod_patch_section_manifest_title,
                detail = detail,
                rule = context.getString(R.string.mod_import_manifest_message_rule)
            )
        }
        if (patchInfo.wasFrierenAntiPiratePatched) {
            addSection(
                titleResId = R.string.main_mod_patch_section_frieren_title,
                detail = context.getString(R.string.mod_import_frieren_message_item_detail),
                rule = context.getString(R.string.mod_import_frieren_message_rule)
            )
        }
        if (patchInfo.wasDownfallPatched) {
            addSection(
                titleResId = R.string.main_mod_patch_section_downfall_title,
                detail = context.getString(
                    R.string.mod_import_downfall_message_item_detail,
                    patchInfo.patchedDownfallClassEntries,
                    patchInfo.patchedDownfallMerchantClassEntries,
                    patchInfo.patchedDownfallHexaghostBodyClassEntries,
                    patchInfo.patchedDownfallBossMechanicPanelClassEntries
                ),
                rule = context.getString(R.string.mod_import_downfall_message_rule)
            )
        }
        if (patchInfo.wasVupShionPatched) {
            addSection(
                titleResId = R.string.main_mod_patch_section_vupshion_title,
                detail = context.getString(R.string.mod_import_vupshion_message_item_detail),
                rule = context.getString(R.string.mod_import_vupshion_message_rule)
            )
        }
        if (patchInfo.wasChaofanModPatched) {
            addSection(
                titleResId = R.string.main_mod_patch_section_chaofanmod_title,
                detail = context.getString(R.string.mod_import_chaofanmod_message_item_detail),
                rule = context.getString(R.string.mod_import_chaofanmod_message_rule)
            )
        }
        if (patchInfo.wasJacketNoAnoKoPatched) {
            addSection(
                titleResId = R.string.main_mod_patch_section_jacketnoanoko_title,
                detail = context.getString(
                    R.string.mod_import_jacketnoanoko_message_item_detail,
                    patchInfo.patchedJacketNoAnoKoShaderEntries,
                    patchInfo.patchedJacketNoAnoKoDesktopVersionDirectives,
                    patchInfo.patchedJacketNoAnoKoFragmentPrecisionBlocks
                ),
                rule = context.getString(R.string.mod_import_jacketnoanoko_message_rule)
            )
        }
        if (patchInfo.wasOriRenderShaderPatched) {
            addSection(
                titleResId = R.string.main_mod_patch_section_ori_title,
                detail = context.getString(
                    R.string.mod_import_ori_message_item_detail,
                    patchInfo.patchedOriShaderEntries,
                    patchInfo.patchedOriGaussianBlurShaderEntries,
                    patchInfo.patchedOriBoxBlurShaderEntries,
                    patchInfo.patchedOriTextureSamplesBefore,
                    patchInfo.patchedOriTextureSamplesAfter
                ),
                rule = context.getString(R.string.mod_import_ori_message_rule)
            )
        }
        return sections.joinToString(separator = "\n\n")
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
    fun importSaveArchive(
        host: Activity,
        uri: Uri,
        targetRoot: File = RuntimePaths.stsRoot(host),
    ): SaveImportResult {
        val transactionParent = targetRoot.parentFile
            ?: throw IOException("Save root parent is unavailable: ${targetRoot.absolutePath}")
        if (!transactionParent.isDirectory && !transactionParent.mkdirs()) {
            throw IOException("Failed to create save root parent: ${transactionParent.absolutePath}")
        }
        val transactionRoot = File(
            transactionParent,
            ".save-import-${System.currentTimeMillis()}-${System.nanoTime()}",
        )
        val archiveFile = File(transactionRoot, "source.zip")
        val stagingRoot = File(transactionRoot, "staging")
        val rollbackRoot = File(transactionRoot, "rollback")
        if (!transactionRoot.mkdirs()) {
            throw IOException("Failed to create save import transaction directory: ${transactionRoot.absolutePath}")
        }

        var preserveRecoveryData = false
        try {
            copyUriToFile(host, uri, archiveFile)
            val scanResult = scanSaveArchive(archiveFile)
            if (scanResult.importableFiles <= 0) {
                throw IOException("Archive did not contain importable save files")
            }
            if (!stagingRoot.mkdirs()) {
                throw IOException("Failed to create save import staging directory: ${stagingRoot.absolutePath}")
            }
            val extracted = extractSaveArchive(archiveFile, stagingRoot)
            if (extracted.importableFiles <= 0 || extracted != scanResult) {
                throw IOException(
                    "Save archive changed while importing " +
                        "(expected ${scanResult.importableFiles} files in " +
                        "${scanResult.targetTopLevelDirs.sorted()}, extracted " +
                        "${extracted.importableFiles} files in ${extracted.targetTopLevelDirs.sorted()})"
                )
            }

            val applyImport = {
                val backupLabel = backupExistingSavesToDownloads(host, targetRoot)
                SteamCloudStagedPathStore.apply(
                    replacements = scanResult.targetTopLevelDirs.map { directoryName ->
                        SteamCloudStagedPathReplacement(
                            stagedPath = File(stagingRoot, directoryName),
                            targetPath = File(targetRoot, directoryName),
                        )
                    },
                    rollbackRoot = rollbackRoot,
                )
                SaveImportResult(importedFiles = extracted.importableFiles, backupLabel = backupLabel)
            }

            return if (targetRoot.canonicalFile == RuntimePaths.stsRoot(host).canonicalFile) {
                SteamCloudLiveSaveLease.runMutation(host, applyImport)
            } else {
                applyImport()
            }
        } catch (error: Throwable) {
            preserveRecoveryData = error is io.stamethyst.backend.steamcloud.SteamCloudReconciliationException &&
                error.recoveryDataPreserved
            throw error
        } finally {
            stagingRoot.deleteRecursively()
            if (!preserveRecoveryData) {
                rollbackRoot.deleteRecursively()
                transactionRoot.deleteRecursively()
            }
        }
    }

    @Throws(IOException::class)
    private fun replaceFileAtomically(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        if (!source.exists()) {
            throw IOException("Source file not found: ${source.absolutePath}")
        }
        try {
            Os.rename(source.absolutePath, target.absolutePath)
        } catch (error: ErrnoException) {
            throw IOException(
                "Failed to atomically replace ${target.absolutePath} with ${source.absolutePath}",
                error
            )
        }
    }

    @Throws(IOException::class)
    private fun scanSaveArchive(archiveFile: File): SaveArchiveScanResult {
        var importableFiles = 0
        val targetTopLevelDirs = LinkedHashSet<String>()
        FileInputStream(archiveFile).use { rawInput ->
            java.util.zip.ZipInputStream(rawInput).use { zipInput ->
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    val importablePath = SaveArchiveLayout.resolveImportablePath(entry.name)
                        ?.let(SaveArchiveLayout::normalizeImportTargetPath)
                    if (importablePath.isNullOrEmpty()) {
                        continue
                    }
                    SaveArchiveLayout.topLevelDirectory(importablePath)?.let { targetTopLevelDirs.add(it) }
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
        return SettingsSaveBackupService.backupExistingSavesToDownloads(host, stsRoot)
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

    private fun containsRegularFiles(root: File): Boolean {
        if (!root.exists()) {
            return false
        }
        if (root.isFile) {
            return true
        }
        val children = root.listFiles() ?: return false
        for (child in children) {
            if (containsRegularFiles(child)) {
                return true
            }
        }
        return false
    }

    @Throws(IOException::class)
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun backupExistingSavesToScopedDownloads(
        host: Activity,
        sourceRoots: List<File>,
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
                writeSaveDirectoriesToZip(output, sourceRoots)
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
        sourceRoots: List<File>,
        backupFileName: String
    ): String {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("Downloads directory is unavailable")
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw IOException("Failed to create Downloads directory: ${downloadsDir.absolutePath}")
        }

        val backupFile = File(downloadsDir, backupFileName)
        FileOutputStream(backupFile, false).use { output ->
            writeSaveDirectoriesToZip(output, sourceRoots)
        }
        return backupFile.absolutePath
    }

    @Throws(IOException::class)
    private fun writeSaveDirectoriesToZip(output: OutputStream, sourceRoots: List<File>) {
        ZipOutputStream(output).use { zipOutput ->
            writeSaveDirectoriesToZip(zipOutput, sourceRoots)
        }
    }

    @Throws(IOException::class)
    private fun writeSaveDirectoriesToZip(zipOutput: ZipOutputStream, sourceRoots: List<File>): Int {
        val writtenEntries = LinkedHashSet<String>()
        var exportedCount = 0
        for (sourceRoot in sourceRoots) {
            exportedCount += exportSaveFolderToZip(zipOutput, sourceRoot, writtenEntries)
        }
        return exportedCount
    }

    private fun buildSaveBackupFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-saves-backup-${formatter.format(Date())}.zip"
    }

    @Throws(IOException::class)
    private fun extractSaveArchive(archiveFile: File, stsRoot: File): SaveArchiveScanResult {
        val rootCanonical = stsRoot.canonicalPath
        var importedFiles = 0
        val targetTopLevelDirs = LinkedHashSet<String>()

        FileInputStream(archiveFile).use { rawInput ->
            java.util.zip.ZipInputStream(rawInput).use { zipInput ->
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    val importablePath = SaveArchiveLayout.resolveImportablePath(entry.name)
                        ?.let(SaveArchiveLayout::normalizeImportTargetPath)
                    if (importablePath.isNullOrEmpty()) {
                        continue
                    }
                    SaveArchiveLayout.topLevelDirectory(importablePath)
                        ?.let(targetTopLevelDirs::add)

                    val output = File(stsRoot, importablePath)
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
        return SaveArchiveScanResult(
            importableFiles = importedFiles,
            targetTopLevelDirs = targetTopLevelDirs,
        )
    }

    private fun collectModExportSources(host: Activity): List<ModExportSource> {
        val sources = LinkedHashMap<String, ModExportSource>()
        val aliases = ModAliasStore.loadAliases(host)

        fun addFile(file: File?, preferredEntryName: String? = null) {
            if (file == null || !file.isFile) {
                return
            }
            val entryName = allocateUniqueModEntryName(
                existingEntryNames = sources.keys,
                requestedEntryName = normalizeModExportFileName(
                    preferredName = preferredEntryName ?: file.name,
                    fallbackFileName = file.name
                )
            )
            if (entryName.isEmpty()) {
                return
            }
            sources[entryName] = ModExportSource(entryName = entryName, file = file)
        }

        fun addAssetIfMissing(entryName: String, assetPath: String, installedFile: File? = null) {
            if (installedFile?.isFile == true) {
                return
            }
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
        ModManager.listInstalledMods(host)
            .asSequence()
            .filter { it.installed }
            .filter { it.jarFile.isFile }
            .sortedWith(
                compareBy<ModManager.InstalledMod>(
                    { it.jarFile.name.lowercase(Locale.ROOT) },
                    { it.jarFile.name },
                    { it.jarFile.absolutePath }
                )
            )
            .forEach { mod ->
                val alias = ModAliasStore.resolveAlias(mod.jarFile.absolutePath, aliases)
                addFile(
                    file = mod.jarFile,
                    preferredEntryName = alias.ifBlank { mod.name.ifBlank { mod.jarFile.name } }
                )
            }

        addAssetIfMissing("ModTheSpire.jar", "components/mods/ModTheSpire.jar", RuntimePaths.importedMtsJar(host))
        addAssetIfMissing("BaseMod.jar", "components/mods/BaseMod.jar", RuntimePaths.importedBaseModJar(host))
        addAssetIfMissing("StSLib.jar", "components/mods/StSLib.jar", RuntimePaths.importedStsLibJar(host))
        addAssetIfMissing(
            "AmethystRuntimeCompat.jar",
            "components/mods/AmethystRuntimeCompat.jar",
            RuntimePaths.importedAmethystRuntimeCompatJar(host)
        )
        addAssetIfMissing(
            "AmethystFloatingTools.jar",
            "components/mods/AmethystFloatingTools.jar",
            RuntimePaths.importedAmethystFloatingToolsJar(host)
        )
        addAssetIfMissing(
            "RamSaver.jar",
            "components/mods/RamSaver.jar",
            RuntimePaths.importedRamSaverJar(host)
        )

        return sources.values.toList()
    }

    private fun allocateUniqueModEntryName(
        existingEntryNames: Set<String>,
        requestedEntryName: String
    ): String {
        val normalized = requestedEntryName.trim().ifBlank { "mod-export.jar" }
        if (!existingEntryNames.contains(normalized)) {
            return normalized
        }
        val dotIndex = normalized.lastIndexOf('.')
        val baseName = if (dotIndex > 0) normalized.substring(0, dotIndex) else normalized
        val extension = if (dotIndex > 0) normalized.substring(dotIndex) else ""
        var index = 2
        while (true) {
            val candidate = "$baseName ($index)$extension"
            if (!existingEntryNames.contains(candidate)) {
                return candidate
            }
            index++
        }
    }

    private fun hasAsset(host: Activity, assetPath: String): Boolean {
        return RuntimeResourceProvider(host).exists(assetPath)
    }

    @Throws(IOException::class)
    private fun exportSaveFolderToZip(
        zipOutput: ZipOutputStream,
        sourceRoot: File,
        writtenEntries: MutableSet<String>
    ): Int {
        if (!sourceRoot.exists()) {
            return 0
        }
        val sourceFiles = ArrayList<File>()
        collectRegularFiles(sourceRoot, sourceFiles)
        sourceFiles.sortWith(compareBy<File>({ it.path.lowercase(Locale.ROOT) }, { it.path }))
        var exportedCount = 0
        for (sourceFile in sourceFiles) {
            val entryName = SaveArchiveLayout.buildArchiveEntryName(sourceRoot, sourceFile)
            if (!writtenEntries.add(entryName)) {
                continue
            }
            writeFileToZip(zipOutput, sourceFile, entryName)
            exportedCount++
        }
        return exportedCount
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
    private fun writeFileToZip(
        zipOutput: ZipOutputStream,
        sourceFile: File,
        entryName: String,
        progressCallback: ZipEntryWriteProgressCallback? = null
    ) {
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
                progressCallback?.onBytesWritten(read.toLong())
            }
        }
        zipOutput.closeEntry()
    }

    @Throws(IOException::class)
    private fun writeAssetToZip(
        host: Activity,
        zipOutput: ZipOutputStream,
        assetPath: String,
        entryName: String,
        progressCallback: ZipEntryWriteProgressCallback? = null
    ) {
        val entry = ZipEntry(entryName)
        zipOutput.putNextEntry(entry)
        RuntimeResourceProvider(host).open(assetPath).use { input ->
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
                progressCallback?.onBytesWritten(read.toLong())
            }
        }
        zipOutput.closeEntry()
    }

    private fun reportArchiveExportProgress(
        progressCallback: ArchiveExportProgressCallback?,
        percent: Int
    ) {
        progressCallback?.onProgress(percent.coerceIn(0, 100))
    }

    private fun formatRuntimeMemorySaved(megabytes: Int): String {
        return if (megabytes > 0) {
            "$megabytes MB"
        } else {
            "<1 MB"
        }
    }

}

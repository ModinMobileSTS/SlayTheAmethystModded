package io.stamethyst.ui.settings

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
import io.stamethyst.backend.diag.DiagnosticsProcessClient
import io.stamethyst.backend.file_interactive.FileShareCompat
import io.stamethyst.backend.launch.JvmLogRotationManager
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.DownfallImportCompatPatcher
import io.stamethyst.backend.mods.FrierenModCompatPatcher
import io.stamethyst.backend.mods.ImportedModPatchInfo
import io.stamethyst.backend.mods.ImportedModPatchRegistry
import io.stamethyst.backend.mods.AtlasOfflineDownscaleStrategy
import io.stamethyst.backend.mods.JacketNoAnoKoModCompatPatcher
import io.stamethyst.backend.mods.ModAtlasFilterCompatPatcher
import io.stamethyst.backend.mods.ModAtlasOfflineDownscalePatcher
import io.stamethyst.backend.mods.MtsLaunchManifestValidator
import io.stamethyst.backend.mods.OptionalModStorageCoordinator
import io.stamethyst.backend.mods.VupShionModCompatPatcher
import io.stamethyst.config.RuntimePaths
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.main.MainFolderStateStore
import io.stamethyst.ui.main.resolveAssignedFolderId
import io.stamethyst.ui.main.resolveModStoragePathCandidates
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

internal data class ModImportResult(
    val modId: String,
    val modName: String,
    val patchedAtlasEntries: Int,
    val patchedFilterLines: Int,
    val downscaledAtlasEntries: Int = 0,
    val downscaledAtlasPageEntries: Int = 0,
    val patchedManifestRootEntries: Int = 0,
    val patchedManifestRootPrefix: String = "",
    val patchedFrierenAntiPirateMethod: Boolean = false,
    val patchedDownfallClassEntries: Int = 0,
    val patchedDownfallMerchantClassEntries: Int = 0,
    val patchedDownfallHexaghostBodyClassEntries: Int = 0,
    val patchedDownfallBossMechanicPanelClassEntries: Int = 0,
    val patchedVupShionWebButtonConstructor: Boolean = false,
    val patchedJacketNoAnoKoShaderEntries: Int = 0,
    val patchedJacketNoAnoKoDesktopVersionDirectives: Int = 0,
    val patchedJacketNoAnoKoFragmentPrecisionBlocks: Int = 0
) {
    val wasAtlasPatched: Boolean
        get() = patchedFilterLines > 0
    val wasAtlasDownscaled: Boolean
        get() = downscaledAtlasPageEntries > 0
    val wasManifestRootPatched: Boolean
        get() = patchedManifestRootEntries > 0
    val wasFrierenAntiPiratePatched: Boolean
        get() = patchedFrierenAntiPirateMethod
    val wasDownfallPatched: Boolean
        get() = patchedDownfallClassEntries > 0
    val wasVupShionPatched: Boolean
        get() = patchedVupShionWebButtonConstructor
    val wasJacketNoAnoKoPatched: Boolean
        get() = patchedJacketNoAnoKoShaderEntries > 0
    val hasCompatibilityPatches: Boolean
        get() = wasAtlasPatched ||
            wasAtlasDownscaled ||
            wasManifestRootPatched ||
            wasFrierenAntiPiratePatched ||
            wasDownfallPatched ||
            wasVupShionPatched ||
            wasJacketNoAnoKoPatched
}

internal data class ModBatchImportResult(
    val importedCount: Int,
    val errors: List<String>,
    val blockedComponents: List<String>,
    val compressedArchives: List<String>,
    val invalidModJars: List<InvalidModImportFailure>,
    val patchedResults: List<ModImportResult>
) {
    val failedCount: Int
        get() = errors.size + invalidModJars.size
    val blockedCount: Int
        get() = blockedComponents.size
    val compressedArchiveCount: Int
        get() = compressedArchives.size
    val firstError: String?
        get() = errors.firstOrNull() ?: invalidModJars.firstOrNull()?.let { failure ->
            val reason = failure.reason.trim()
            if (reason.isNotEmpty()) {
                "${failure.displayName}: $reason"
            } else {
                failure.displayName
            }
        }
}

internal data class InvalidModImportFailure(
    val displayName: String,
    val reason: String
)

internal data class ModImportIdentityPreview(
    val normalizedModId: String,
    val displayModId: String,
    val displayName: String
)

internal data class ModImportAtlasDownscalePreview(
    val modId: String,
    val modName: String,
    val downscaledAtlasEntries: Int,
    val downscaledAtlasPageEntries: Int
) {
    val willDownscale: Boolean
        get() = downscaledAtlasPageEntries > 0
}

internal data class DuplicateModImportConflict(
    val normalizedModId: String,
    val displayModId: String,
    val importingDisplayNames: List<String>,
    val existingDisplayNames: List<String>
)

internal data class DuplicateModImportReplaceOptions(
    val moveToPreviousFolder: Boolean = false,
    val renameToPreviousFileName: Boolean = false
)

internal data class ExistingDuplicateModImportSource(
    val storagePath: String,
    val fileName: String,
    val assignedFolderId: String? = null
)

internal data class DuplicateModImportReusePlan(
    val targetFileName: String? = null,
    val assignedFolderId: String? = null,
    val sourceStoragePaths: List<String> = emptyList()
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
    class ReservedModImportException(
        @JvmField val blockedComponent: String
    ) : IOException("Import blocked for built-in component: $blockedComponent")

    class InvalidModImportException(
        @JvmField val displayName: String,
        @JvmField val reason: String
    ) : IOException(reason)

    private const val DOWNFALL_MOD_ID = "downfall"
    private const val FRIEREN_MOD_ID = "frierenmod"
    private const val VUPSHION_MOD_ID = "vupshionmod"
    private const val JACKETNOANOKO_MOD_ID = "jacketnoanokomod"
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
        return FileShareCompat.resolveShareUri(host, archiveFile)
    }

    @Throws(IOException::class)
    fun exportJvmLogBundle(host: Activity, uri: Uri): Int {
        return DiagnosticsProcessClient.exportJvmLogBundle(host, uri)
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
    fun importModJar(
        host: Activity,
        uri: Uri,
        replaceExistingDuplicates: Boolean = false,
        duplicateReplaceOptions: DuplicateModImportReplaceOptions = DuplicateModImportReplaceOptions(),
        importAtlasDownscaleStrategy: AtlasOfflineDownscaleStrategy? = null
    ): ModImportResult {
        OptionalModStorageCoordinator.ensureOptionalModLibraryReady(host)
        val modsDir = RuntimePaths.optionalModsLibraryDir(host)
        if (!modsDir.exists() && !modsDir.mkdirs()) {
            throw IOException("Failed to create mods directory")
        }

        val tempFile = File(modsDir, ".import-${System.nanoTime()}.tmp.jar")
        val displayName = JarImportInspectionService.prepareImportedJar(host, uri, tempFile)
        try {
            val inspection = JarImportInspectionService.inspectPreparedModJar(host, tempFile, displayName)
            val manifest = inspection.manifest
            if (manifest == null) {
                if (JarImportInspectionService.isLikelyModTheSpireJar(tempFile, displayName)) {
                    throw ReservedModImportException(JarImportInspectionService.RESERVED_COMPONENT_MTS)
                }
                throw InvalidModImportException(
                    displayName = displayName,
                    reason = inspection.parseError.orEmpty()
                )
            }

            var patchedManifestRootEntries = inspection.patchedManifestRootEntries
            var patchedManifestRootPrefix = inspection.patchedManifestRootPrefix
            val modId = inspection.normalizedModId
            if (modId.isBlank()) {
                throw IOException("modid is empty")
            }
            val blockedComponent = inspection.reservedComponent
            if (blockedComponent != null) {
                throw ReservedModImportException(blockedComponent)
            }
            val launchModId = try {
                MtsLaunchManifestValidator.resolveLaunchModId(tempFile).trim()
            } catch (_: Throwable) {
                ""
            }
            val duplicateReusePlan = if (replaceExistingDuplicates) {
                buildDuplicateModImportReusePlan(
                    existingSources = resolveExistingDuplicateModImportSources(
                        host = host,
                        normalizedModId = modId,
                        excludedStoragePath = tempFile.absolutePath
                    ),
                    options = duplicateReplaceOptions
                )
            } else {
                DuplicateModImportReusePlan()
            }

            var patchedAtlasEntries = 0
            var patchedFilterLines = 0
            if (CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(host)) {
                val patchResult = ModAtlasFilterCompatPatcher.patchMipMapFiltersInPlace(tempFile)
                patchedAtlasEntries = patchResult.patchedAtlasEntries
                patchedFilterLines = patchResult.patchedFilterLines
            }
            var downscaledAtlasEntries = 0
            var downscaledAtlasPageEntries = 0
            if (importAtlasDownscaleStrategy != null) {
                val patchResult = ModAtlasOfflineDownscalePatcher.patchOversizedAtlasPagesInPlace(
                    tempFile,
                    importAtlasDownscaleStrategy
                )
                downscaledAtlasEntries = patchResult.patchedAtlasEntries
                downscaledAtlasPageEntries = patchResult.downscaledPageEntries
            }
            var patchedFrierenAntiPirateMethod = false
            if (CompatibilitySettings.isFrierenModCompatEnabled(host)
                && modId == FRIEREN_MOD_ID
            ) {
                patchedFrierenAntiPirateMethod =
                    FrierenModCompatPatcher.patchAntiPirateInPlace(tempFile).patchedAntiPirateMethod
            }
            var patchedDownfallClassEntries = 0
            var patchedDownfallMerchantClassEntries = 0
            var patchedDownfallHexaghostBodyClassEntries = 0
            var patchedDownfallBossMechanicPanelClassEntries = 0
            if (CompatibilitySettings.isDownfallImportCompatEnabled(host)
                && modId == DOWNFALL_MOD_ID
            ) {
                val patchResult = DownfallImportCompatPatcher.patchInPlace(tempFile)
                patchedDownfallClassEntries = patchResult.patchedClassEntries
                patchedDownfallMerchantClassEntries = patchResult.patchedMerchantClassEntries
                patchedDownfallHexaghostBodyClassEntries = patchResult.patchedHexaghostBodyClassEntries
                patchedDownfallBossMechanicPanelClassEntries =
                    patchResult.patchedBossMechanicPanelClassEntries
            }
            var patchedVupShionWebButtonConstructor = false
            if (CompatibilitySettings.isVupShionModCompatEnabled(host)
                && modId == VUPSHION_MOD_ID
            ) {
                patchedVupShionWebButtonConstructor =
                    VupShionModCompatPatcher.patchInPlace(tempFile).hasAnyPatch
            }
            var patchedJacketNoAnoKoShaderEntries = 0
            var patchedJacketNoAnoKoDesktopVersionDirectives = 0
            var patchedJacketNoAnoKoFragmentPrecisionBlocks = 0
            if (CompatibilitySettings.isJacketNoAnoKoModCompatEnabled(host)
                && modId == JACKETNOANOKO_MOD_ID
            ) {
                val patchResult = JacketNoAnoKoModCompatPatcher.patchInPlace(tempFile)
                patchedJacketNoAnoKoShaderEntries = patchResult.patchedShaderEntries
                patchedJacketNoAnoKoDesktopVersionDirectives =
                    patchResult.removedDesktopVersionDirectives
                patchedJacketNoAnoKoFragmentPrecisionBlocks =
                    patchResult.insertedFragmentPrecisionBlocks
            }
            if (replaceExistingDuplicates) {
                ModManager.removeExistingOptionalModsForImport(
                    context = host,
                    normalizedModId = modId,
                    launchModId = launchModId,
                    excludedPath = tempFile.absolutePath
                )
            }
            val requestedFileName = duplicateReusePlan.targetFileName
                ?.takeIf { it.isNotBlank() }
                ?: if (displayName.isNotBlank()) displayName else "$modId.jar"
            val targetFile = ModManager.resolveStorageFileForImportedMod(host, requestedFileName)
            moveFileReplacing(tempFile, targetFile)
            if (!duplicateReusePlan.assignedFolderId.isNullOrBlank()) {
                runCatching {
                    applyDuplicateModImportFolderReuse(
                        host = host,
                        normalizedModId = modId,
                        sourceStoragePaths = duplicateReusePlan.sourceStoragePaths,
                        targetStoragePath = targetFile.absolutePath,
                        assignedFolderId = duplicateReusePlan.assignedFolderId
                    )
                }
            }
            val modName = manifest.name.trim().ifBlank { modId }
            val result = ModImportResult(
                modId = modId,
                modName = modName,
                patchedAtlasEntries = patchedAtlasEntries,
                patchedFilterLines = patchedFilterLines,
                downscaledAtlasEntries = downscaledAtlasEntries,
                downscaledAtlasPageEntries = downscaledAtlasPageEntries,
                patchedManifestRootEntries = patchedManifestRootEntries,
                patchedManifestRootPrefix = patchedManifestRootPrefix,
                patchedFrierenAntiPirateMethod = patchedFrierenAntiPirateMethod,
                patchedDownfallClassEntries = patchedDownfallClassEntries,
                patchedDownfallMerchantClassEntries = patchedDownfallMerchantClassEntries,
                patchedDownfallHexaghostBodyClassEntries = patchedDownfallHexaghostBodyClassEntries,
                patchedDownfallBossMechanicPanelClassEntries =
                    patchedDownfallBossMechanicPanelClassEntries,
                patchedVupShionWebButtonConstructor = patchedVupShionWebButtonConstructor,
                patchedJacketNoAnoKoShaderEntries = patchedJacketNoAnoKoShaderEntries,
                patchedJacketNoAnoKoDesktopVersionDirectives =
                    patchedJacketNoAnoKoDesktopVersionDirectives,
                patchedJacketNoAnoKoFragmentPrecisionBlocks =
                    patchedJacketNoAnoKoFragmentPrecisionBlocks
            )
            runCatching {
                ImportedModPatchRegistry.put(
                    context = host,
                    storagePath = targetFile.absolutePath,
                    patchInfo = result.toImportedModPatchInfo()
                )
            }
            return result
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

    fun importModJars(
        host: Activity,
        uris: Collection<Uri>,
        replaceExistingDuplicates: Boolean = false,
        duplicateReplaceOptions: DuplicateModImportReplaceOptions = DuplicateModImportReplaceOptions(),
        importAtlasDownscaleStrategy: AtlasOfflineDownscaleStrategy? = null
    ): ModBatchImportResult {
        var imported = 0
        val errors = ArrayList<String>()
        val blockedComponents = LinkedHashSet<String>()
        val compressedArchives = LinkedHashSet<String>()
        val invalidModJars = ArrayList<InvalidModImportFailure>()
        val patchedMods = LinkedHashMap<String, ModImportResult>()

        for (uri in uris) {
            val displayName = resolveDisplayName(host, uri)
            if (isLikelyCompressedArchive(host, uri, displayName)) {
                compressedArchives.add(displayName)
                continue
            }
            try {
                val result = importModJar(
                    host = host,
                    uri = uri,
                    replaceExistingDuplicates = replaceExistingDuplicates,
                    duplicateReplaceOptions = duplicateReplaceOptions,
                    importAtlasDownscaleStrategy = importAtlasDownscaleStrategy
                )
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
                            downscaledAtlasEntries =
                                existing.downscaledAtlasEntries + result.downscaledAtlasEntries,
                            downscaledAtlasPageEntries =
                                existing.downscaledAtlasPageEntries + result.downscaledAtlasPageEntries,
                            patchedManifestRootEntries = existing.patchedManifestRootEntries + result.patchedManifestRootEntries,
                            patchedManifestRootPrefix = if (existing.patchedManifestRootPrefix.isNotBlank()) {
                                existing.patchedManifestRootPrefix
                            } else {
                                result.patchedManifestRootPrefix
                            },
                            patchedFrierenAntiPirateMethod = existing.patchedFrierenAntiPirateMethod ||
                                result.patchedFrierenAntiPirateMethod,
                            patchedDownfallClassEntries = existing.patchedDownfallClassEntries +
                                result.patchedDownfallClassEntries,
                            patchedDownfallMerchantClassEntries =
                                existing.patchedDownfallMerchantClassEntries +
                                    result.patchedDownfallMerchantClassEntries,
                            patchedDownfallHexaghostBodyClassEntries =
                                existing.patchedDownfallHexaghostBodyClassEntries +
                                    result.patchedDownfallHexaghostBodyClassEntries,
                            patchedDownfallBossMechanicPanelClassEntries =
                                existing.patchedDownfallBossMechanicPanelClassEntries +
                                    result.patchedDownfallBossMechanicPanelClassEntries,
                            patchedVupShionWebButtonConstructor =
                                existing.patchedVupShionWebButtonConstructor ||
                                    result.patchedVupShionWebButtonConstructor,
                            patchedJacketNoAnoKoShaderEntries =
                                existing.patchedJacketNoAnoKoShaderEntries +
                                    result.patchedJacketNoAnoKoShaderEntries,
                            patchedJacketNoAnoKoDesktopVersionDirectives =
                                existing.patchedJacketNoAnoKoDesktopVersionDirectives +
                                    result.patchedJacketNoAnoKoDesktopVersionDirectives,
                            patchedJacketNoAnoKoFragmentPrecisionBlocks =
                                existing.patchedJacketNoAnoKoFragmentPrecisionBlocks +
                                    result.patchedJacketNoAnoKoFragmentPrecisionBlocks
                        )
                    }
                }
            } catch (error: Throwable) {
                if (error is ReservedModImportException) {
                    blockedComponents.add(error.blockedComponent)
                } else if (error is InvalidModImportException) {
                    invalidModJars.add(
                        InvalidModImportFailure(
                            displayName = error.displayName,
                            reason = error.reason
                        )
                    )
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
            invalidModJars = invalidModJars,
            patchedResults = patchedMods.values.toList()
        )
    }

    fun findImportAtlasDownscaleCandidates(
        host: Activity,
        uris: Collection<Uri>
    ): List<ModImportAtlasDownscalePreview> {
        val mergedByModId = LinkedHashMap<String, ModImportAtlasDownscalePreview>()
        for (uri in uris) {
            val preview = inspectImportAtlasDownscaleCandidate(
                host,
                uri
            ) ?: continue
            val existing = mergedByModId[preview.modId]
            if (existing == null) {
                mergedByModId[preview.modId] = preview
            } else {
                mergedByModId[preview.modId] = existing.copy(
                    modName = if (existing.modName.isNotBlank()) existing.modName else preview.modName,
                    downscaledAtlasEntries =
                        existing.downscaledAtlasEntries + preview.downscaledAtlasEntries,
                    downscaledAtlasPageEntries =
                        existing.downscaledAtlasPageEntries + preview.downscaledAtlasPageEntries
                )
            }
        }
        return mergedByModId.values.toList()
    }

    fun findDuplicateModImportConflicts(
        host: Activity,
        uris: Collection<Uri>
    ): List<DuplicateModImportConflict> {
        if (uris.isEmpty()) {
            return emptyList()
        }
        val incomingMods = ArrayList<ModImportIdentityPreview>()
        uris.forEach { uri ->
            inspectImportableModJarIdentity(host, uri)?.let { incomingMods.add(it) }
        }
        if (incomingMods.isEmpty()) {
            return emptyList()
        }
        return collectDuplicateModImportConflicts(
            existingByModId = buildExistingModImportLookup(host),
            incomingMods = incomingMods
        )
    }

    internal fun collectDuplicateModImportConflicts(
        existingByModId: Map<String, List<String>>,
        incomingMods: Collection<ModImportIdentityPreview>
    ): List<DuplicateModImportConflict> {
        if (incomingMods.isEmpty()) {
            return emptyList()
        }
        val incomingByModId = LinkedHashMap<String, MutableList<ModImportIdentityPreview>>()
        incomingMods.forEach { preview ->
            val normalizedModId = ModManager.normalizeModId(preview.normalizedModId)
            if (normalizedModId.isEmpty()) {
                return@forEach
            }
            incomingByModId.getOrPut(normalizedModId) { ArrayList() }.add(preview)
        }
        if (incomingByModId.isEmpty()) {
            return emptyList()
        }

        val conflicts = ArrayList<DuplicateModImportConflict>()
        incomingByModId.forEach { (normalizedModId, previews) ->
            val existingNames = existingByModId[normalizedModId]
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            if (existingNames.isEmpty() && previews.size <= 1) {
                return@forEach
            }
            val displayModId = previews.firstOrNull { it.displayModId.isNotBlank() }
                ?.displayModId
                ?.trim()
                .orEmpty()
                .ifBlank { normalizedModId }
            val importingNames = previews
                .map { it.displayName.trim() }
                .filter { it.isNotEmpty() }
            conflicts.add(
                DuplicateModImportConflict(
                    normalizedModId = normalizedModId,
                    displayModId = displayModId,
                    importingDisplayNames = importingNames,
                    existingDisplayNames = existingNames
                )
            )
        }
        return conflicts.sortedWith(
            compareBy<DuplicateModImportConflict>(
                { it.normalizedModId },
                { it.displayModId.lowercase(Locale.ROOT) }
            )
        )
    }

    internal fun buildDuplicateModImportReusePlan(
        existingSources: Collection<ExistingDuplicateModImportSource>,
        options: DuplicateModImportReplaceOptions
    ): DuplicateModImportReusePlan {
        if (existingSources.isEmpty()) {
            return DuplicateModImportReusePlan()
        }
        if (!options.moveToPreviousFolder && !options.renameToPreviousFileName) {
            return DuplicateModImportReusePlan()
        }

        val normalizedSources = existingSources
            .asSequence()
            .mapNotNull { source ->
                val storagePath = source.storagePath.trim()
                if (storagePath.isEmpty()) {
                    return@mapNotNull null
                }
                val fileName = source.fileName.trim().ifBlank { File(storagePath).name.trim() }
                if (fileName.isEmpty()) {
                    return@mapNotNull null
                }
                ExistingDuplicateModImportSource(
                    storagePath = storagePath,
                    fileName = fileName,
                    assignedFolderId = source.assignedFolderId?.trim()?.ifEmpty { null }
                )
            }
            .distinctBy { it.storagePath }
            .sortedWith(
                compareBy<ExistingDuplicateModImportSource>(
                    { isEphemeralImportFileName(it.fileName) },
                    { it.fileName.lowercase(Locale.ROOT) },
                    { it.fileName },
                    { it.storagePath }
                )
            )
            .toList()
        if (normalizedSources.isEmpty()) {
            return DuplicateModImportReusePlan()
        }

        val renameSource = normalizedSources.firstOrNull()
        val folderSource = normalizedSources.firstOrNull { !it.assignedFolderId.isNullOrBlank() }
        return DuplicateModImportReusePlan(
            targetFileName = if (options.renameToPreviousFileName) {
                renameSource?.fileName
            } else {
                null
            },
            assignedFolderId = if (options.moveToPreviousFolder) {
                folderSource?.assignedFolderId
            } else {
                null
            },
            sourceStoragePaths = normalizedSources.map { it.storagePath }
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

    fun buildCompressedArchiveImportMessage(
        context: Context,
        archiveDisplayNames: Collection<String>
    ): String {
        val uniqueNames = LinkedHashSet<String>()
        archiveDisplayNames.forEach { rawName ->
            val normalized = rawName.trim()
            if (normalized.isNotEmpty()) {
                uniqueNames.add(normalized)
            }
        }
        return buildString {
            append(context.getString(R.string.mod_import_archive_message_intro))
            if (uniqueNames.isEmpty()) {
                append(context.getString(R.string.mod_import_archive_message_unknown_file))
            } else {
                uniqueNames.forEach { name ->
                    append("- ").append(name).append('\n')
                }
            }
            append(context.getString(R.string.mod_import_archive_message_outro))
        }.trimEnd()
    }

    fun buildDuplicateModImportMessage(
        context: Context,
        conflicts: Collection<DuplicateModImportConflict>
    ): String {
        if (conflicts.isEmpty()) {
            return context.getString(R.string.mod_import_duplicate_message_empty)
        }
        val cancelLabel = context.getString(R.string.mod_import_dialog_duplicate_cancel)
        val replaceExistingLabel = context.getString(R.string.mod_import_dialog_duplicate_replace_existing)
        val keepBothLabel = context.getString(R.string.mod_import_dialog_duplicate_keep_both)
        val listSeparator = context.getString(R.string.mod_import_list_separator)
        return buildString {
            append(context.getString(R.string.mod_import_duplicate_message_intro))
            conflicts.forEach { conflict ->
                append('\n')
                append(context.getString(R.string.mod_import_duplicate_message_modid_prefix))
                append(conflict.displayModId.ifBlank { conflict.normalizedModId })
                append('\n')
                append(context.getString(R.string.mod_import_duplicate_message_importing_prefix))
                append(conflict.importingDisplayNames.distinct().joinToString(listSeparator))
                append('\n')
                val existingNames = conflict.existingDisplayNames.distinct()
                if (existingNames.isNotEmpty()) {
                    append(context.getString(R.string.mod_import_duplicate_message_existing_prefix))
                    append(existingNames.joinToString(listSeparator))
                    append('\n')
                }
            }
            append(
                context.getString(
                    R.string.mod_import_duplicate_message_outro,
                    cancelLabel,
                    replaceExistingLabel,
                    keepBothLabel
                )
            )
        }.trimEnd()
    }

    fun buildReservedModImportMessage(
        context: Context,
        blockedComponents: Collection<String>
    ): String {
        val uniqueComponents = LinkedHashSet<String>()
        blockedComponents.forEach { component ->
            normalizeReservedComponentName(component)?.let { uniqueComponents.add(it) }
        }
        if (uniqueComponents.isEmpty()) {
            uniqueComponents.add(JarImportInspectionService.RESERVED_COMPONENT_BASEMOD)
            uniqueComponents.add(JarImportInspectionService.RESERVED_COMPONENT_STSLIB)
            uniqueComponents.add(JarImportInspectionService.RESERVED_COMPONENT_MTS)
            uniqueComponents.add(JarImportInspectionService.RESERVED_COMPONENT_AMETHYST_RUNTIME_COMPAT)
        }

        return buildString {
            append(context.getString(R.string.mod_import_reserved_message_intro))
            uniqueComponents.forEach { component ->
                append("- ").append(component).append('\n')
            }
            append(context.getString(R.string.mod_import_reserved_message_outro))
        }
    }

    fun buildInvalidModImportMessage(
        context: Context,
        failures: Collection<InvalidModImportFailure>
    ): String {
        val normalizedFailures = LinkedHashMap<String, String>()
        failures.forEach { failure ->
            val displayName = failure.displayName.trim()
                .ifBlank { context.getString(R.string.mod_import_invalid_message_unknown_file) }
            val reason = failure.reason.trim()
                .ifBlank { context.getString(R.string.mod_import_error_unknown) }
            normalizedFailures.putIfAbsent(displayName, reason)
        }

        return buildString {
            append(context.getString(R.string.mod_import_invalid_message_intro))
            if (normalizedFailures.isEmpty()) {
                append("\n- ")
                append(context.getString(R.string.mod_import_invalid_message_unknown_file))
                append('\n')
                append(context.getString(R.string.mod_import_preview_manifest_read_failed, context.getString(R.string.mod_import_error_unknown)))
            } else {
                normalizedFailures.forEach { (displayName, reason) ->
                    append('\n')
                    append("- ").append(displayName)
                    append('\n')
                    append(context.getString(R.string.mod_import_preview_manifest_read_failed, reason))
                }
            }
            append('\n')
            append(context.getString(R.string.mod_import_invalid_message_outro))
        }.trimEnd()
    }

    fun buildAtlasPatchImportSummaryMessage(
        context: Context,
        patchedResults: Collection<ModImportResult>
    ): String {
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
            return context.getString(R.string.mod_import_atlas_message_none)
        }

        return buildString {
            append(context.getString(R.string.mod_import_atlas_message_intro))
            for (result in mergedByModId.values) {
                append("- ")
                append(
                    context.getString(
                        R.string.mod_import_summary_item_title,
                        result.modName.ifBlank { result.modId },
                        result.modId
                    )
                )
                append('\n')
                append(
                    context.getString(
                        R.string.mod_import_atlas_message_item_detail,
                        result.patchedAtlasEntries,
                        result.patchedFilterLines
                    )
                )
                append('\n')
            }
            append(context.getString(R.string.mod_import_atlas_message_rule))
        }.trimEnd()
    }

    fun buildAtlasDownscaleImportSummaryMessage(
        context: Context,
        patchedResults: Collection<ModImportResult>
    ): String {
        val mergedByModId = LinkedHashMap<String, ModImportResult>()
        for (result in patchedResults) {
            if (!result.wasAtlasDownscaled) {
                continue
            }
            val existing = mergedByModId[result.modId]
            if (existing == null) {
                mergedByModId[result.modId] = result
            } else {
                mergedByModId[result.modId] = existing.copy(
                    modName = if (existing.modName.isNotBlank()) existing.modName else result.modName,
                    downscaledAtlasEntries =
                        existing.downscaledAtlasEntries + result.downscaledAtlasEntries,
                    downscaledAtlasPageEntries =
                        existing.downscaledAtlasPageEntries + result.downscaledAtlasPageEntries
                )
            }
        }

        if (mergedByModId.isEmpty()) {
            return context.getString(R.string.mod_import_atlas_downscale_message_none)
        }

        return buildString {
            append(context.getString(R.string.mod_import_atlas_downscale_message_intro))
            for (result in mergedByModId.values) {
                append("- ")
                append(
                    context.getString(
                        R.string.mod_import_summary_item_title,
                        result.modName.ifBlank { result.modId },
                        result.modId
                    )
                )
                append('\n')
                append(
                    context.getString(
                        R.string.mod_import_atlas_downscale_message_item_detail,
                        result.downscaledAtlasEntries,
                        result.downscaledAtlasPageEntries
                    )
                )
                append('\n')
            }
            append(context.getString(R.string.mod_import_atlas_downscale_message_rule))
        }.trimEnd()
    }

    fun buildAtlasDownscaleImportConfirmationMessage(
        context: Context,
        previews: Collection<ModImportAtlasDownscalePreview>
    ): String {
        if (previews.none { it.willDownscale }) {
            return context.getString(R.string.mod_import_atlas_downscale_message_none)
        }
        return buildString {
            append(context.getString(R.string.mod_import_atlas_downscale_confirm_message_intro))
            previews.filter { it.willDownscale }.forEach { preview ->
                append("- ")
                append(
                    context.getString(
                        R.string.mod_import_summary_item_title,
                        preview.modName.ifBlank { preview.modId },
                        preview.modId
                    )
                )
                append('\n')
                append(
                    context.getString(
                        R.string.mod_import_atlas_downscale_confirm_message_item_detail,
                        preview.downscaledAtlasEntries,
                        preview.downscaledAtlasPageEntries
                    )
                )
                append('\n')
            }
            append('\n')
            append(context.getString(R.string.mod_import_atlas_downscale_confirm_message_pros))
            append('\n')
            append(context.getString(R.string.mod_import_atlas_downscale_confirm_message_cons))
            append('\n')
            append(context.getString(R.string.mod_import_atlas_downscale_confirm_message_outro))
        }.trimEnd()
    }

    fun buildManifestRootPatchImportSummaryMessage(
        context: Context,
        patchedResults: Collection<ModImportResult>
    ): String {
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
            return context.getString(R.string.mod_import_manifest_message_none)
        }

        return buildString {
            append(context.getString(R.string.mod_import_manifest_message_intro))
            for (result in mergedByModId.values) {
                append("- ")
                append(
                    context.getString(
                        R.string.mod_import_summary_item_title,
                        result.modName.ifBlank { result.modId },
                        result.modId
                    )
                )
                append('\n')
                append(
                    context.getString(
                        R.string.mod_import_manifest_message_item_detail,
                        result.patchedManifestRootEntries
                    )
                )
                val normalizedPrefix = normalizeManifestRootPrefixForDisplay(result.patchedManifestRootPrefix)
                if (normalizedPrefix.isNotEmpty()) {
                    append(
                        context.getString(
                            R.string.mod_import_manifest_message_item_prefix,
                            normalizedPrefix
                        )
                    )
                }
                append('\n')
            }
            append(context.getString(R.string.mod_import_manifest_message_rule))
        }.trimEnd()
    }

    fun buildFrierenPatchImportSummaryMessage(
        context: Context,
        patchedResults: Collection<ModImportResult>
    ): String {
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
            return context.getString(R.string.mod_import_frieren_message_none)
        }

        return buildString {
            append(context.getString(R.string.mod_import_frieren_message_intro))
            for (result in mergedByModId.values) {
                append("- ")
                append(
                    context.getString(
                        R.string.mod_import_summary_item_title,
                        result.modName.ifBlank { result.modId },
                        result.modId
                    )
                )
                append('\n')
                append(context.getString(R.string.mod_import_frieren_message_item_detail))
                append('\n')
            }
            append(context.getString(R.string.mod_import_frieren_message_rule))
        }.trimEnd()
    }

    fun buildDownfallPatchImportSummaryMessage(
        context: Context,
        patchedResults: Collection<ModImportResult>
    ): String {
        val mergedByModId = LinkedHashMap<String, ModImportResult>()
        for (result in patchedResults) {
            if (!result.wasDownfallPatched) {
                continue
            }
            val existing = mergedByModId[result.modId]
            if (existing == null) {
                mergedByModId[result.modId] = result
            } else {
                mergedByModId[result.modId] = existing.copy(
                    modName = if (existing.modName.isNotBlank()) existing.modName else result.modName,
                    patchedDownfallClassEntries = existing.patchedDownfallClassEntries +
                        result.patchedDownfallClassEntries,
                    patchedDownfallMerchantClassEntries =
                        existing.patchedDownfallMerchantClassEntries +
                            result.patchedDownfallMerchantClassEntries,
                    patchedDownfallHexaghostBodyClassEntries =
                        existing.patchedDownfallHexaghostBodyClassEntries +
                            result.patchedDownfallHexaghostBodyClassEntries,
                    patchedDownfallBossMechanicPanelClassEntries =
                        existing.patchedDownfallBossMechanicPanelClassEntries +
                            result.patchedDownfallBossMechanicPanelClassEntries
                )
            }
        }

        if (mergedByModId.isEmpty()) {
            return context.getString(R.string.mod_import_downfall_message_none)
        }

        return buildString {
            append(context.getString(R.string.mod_import_downfall_message_intro))
            for (result in mergedByModId.values) {
                append("- ")
                append(
                    context.getString(
                        R.string.mod_import_summary_item_title,
                        result.modName.ifBlank { result.modId },
                        result.modId
                    )
                )
                append('\n')
                append(
                    context.getString(
                        R.string.mod_import_downfall_message_item_detail,
                        result.patchedDownfallClassEntries,
                        result.patchedDownfallMerchantClassEntries,
                        result.patchedDownfallHexaghostBodyClassEntries,
                        result.patchedDownfallBossMechanicPanelClassEntries
                    )
                )
                append('\n')
            }
            append(context.getString(R.string.mod_import_downfall_message_rule))
        }.trimEnd()
    }

    fun buildVupShionPatchImportSummaryMessage(
        context: Context,
        patchedResults: Collection<ModImportResult>
    ): String {
        val mergedByModId = LinkedHashMap<String, ModImportResult>()
        for (result in patchedResults) {
            if (!result.wasVupShionPatched) {
                continue
            }
            val existing = mergedByModId[result.modId]
            if (existing == null) {
                mergedByModId[result.modId] = result
            } else {
                mergedByModId[result.modId] = existing.copy(
                    modName = if (existing.modName.isNotBlank()) existing.modName else result.modName,
                    patchedVupShionWebButtonConstructor =
                        existing.patchedVupShionWebButtonConstructor ||
                            result.patchedVupShionWebButtonConstructor
                )
            }
        }

        if (mergedByModId.isEmpty()) {
            return context.getString(R.string.mod_import_vupshion_message_none)
        }

        return buildString {
            append(context.getString(R.string.mod_import_vupshion_message_intro))
            for (result in mergedByModId.values) {
                append("- ")
                append(
                    context.getString(
                        R.string.mod_import_summary_item_title,
                        result.modName.ifBlank { result.modId },
                        result.modId
                    )
                )
                append('\n')
                append(context.getString(R.string.mod_import_vupshion_message_item_detail))
                append('\n')
            }
            append(context.getString(R.string.mod_import_vupshion_message_rule))
        }.trimEnd()
    }

    fun buildJacketNoAnoKoPatchImportSummaryMessage(
        context: Context,
        patchedResults: Collection<ModImportResult>
    ): String {
        val mergedByModId = LinkedHashMap<String, ModImportResult>()
        for (result in patchedResults) {
            if (!result.wasJacketNoAnoKoPatched) {
                continue
            }
            val existing = mergedByModId[result.modId]
            if (existing == null) {
                mergedByModId[result.modId] = result
            } else {
                mergedByModId[result.modId] = existing.copy(
                    modName = if (existing.modName.isNotBlank()) existing.modName else result.modName,
                    patchedJacketNoAnoKoShaderEntries =
                        existing.patchedJacketNoAnoKoShaderEntries +
                            result.patchedJacketNoAnoKoShaderEntries,
                    patchedJacketNoAnoKoDesktopVersionDirectives =
                        existing.patchedJacketNoAnoKoDesktopVersionDirectives +
                            result.patchedJacketNoAnoKoDesktopVersionDirectives,
                    patchedJacketNoAnoKoFragmentPrecisionBlocks =
                        existing.patchedJacketNoAnoKoFragmentPrecisionBlocks +
                            result.patchedJacketNoAnoKoFragmentPrecisionBlocks
                )
            }
        }

        if (mergedByModId.isEmpty()) {
            return context.getString(R.string.mod_import_jacketnoanoko_message_none)
        }

        return buildString {
            append(context.getString(R.string.mod_import_jacketnoanoko_message_intro))
            for (result in mergedByModId.values) {
                append("- ")
                append(
                    context.getString(
                        R.string.mod_import_summary_item_title,
                        result.modName.ifBlank { result.modId },
                        result.modId
                    )
                )
                append('\n')
                append(
                    context.getString(
                        R.string.mod_import_jacketnoanoko_message_item_detail,
                        result.patchedJacketNoAnoKoShaderEntries,
                        result.patchedJacketNoAnoKoDesktopVersionDirectives,
                        result.patchedJacketNoAnoKoFragmentPrecisionBlocks
                    )
                )
                append('\n')
            }
            append(context.getString(R.string.mod_import_jacketnoanoko_message_rule))
        }.trimEnd()
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
                    patchInfo.downscaledAtlasPageEntries
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

    private fun buildExistingModImportLookup(host: Activity): Map<String, List<String>> {
        val existingByModId = LinkedHashMap<String, MutableList<String>>()
        ModManager.listInstalledMods(host).forEach { mod ->
            if (mod.required || !mod.jarFile.isFile) {
                return@forEach
            }
            val normalizedModId = ModManager.normalizeModId(mod.modId).ifBlank {
                ModManager.normalizeModId(mod.manifestModId)
            }
            if (normalizedModId.isEmpty()) {
                return@forEach
            }
            existingByModId.getOrPut(normalizedModId) { ArrayList() }.add(mod.jarFile.name)
        }
        return existingByModId
    }

    private fun resolveExistingDuplicateModImportSources(
        host: Activity,
        normalizedModId: String,
        excludedStoragePath: String? = null
    ): List<ExistingDuplicateModImportSource> {
        val targetModId = ModManager.normalizeModId(normalizedModId)
        if (targetModId.isEmpty()) {
            return emptyList()
        }
        val excludedPath = excludedStoragePath?.trim().orEmpty()
        val folderStateStore = MainFolderStateStore().apply { ensureLoaded(host) }
        val validFolderIds = folderStateStore.folders.map { it.id }.toHashSet()
        return ModManager.listInstalledMods(host)
            .asSequence()
            .filterNot { it.required }
            .mapNotNull { mod ->
                val candidateModId = ModManager.normalizeModId(mod.modId).ifBlank {
                    ModManager.normalizeModId(mod.manifestModId)
                }
                if (candidateModId != targetModId || !mod.jarFile.isFile) {
                    return@mapNotNull null
                }
                val storagePath = mod.jarFile.absolutePath.trim()
                if (storagePath.isEmpty()) {
                    return@mapNotNull null
                }
                if (excludedPath.isNotEmpty() && storagePath == excludedPath) {
                    return@mapNotNull null
                }
                if (isEphemeralImportFileName(mod.jarFile.name)) {
                    return@mapNotNull null
                }
                val folderId = resolveAssignedFolderId(
                    mod = mod.toModItemUi(storagePath),
                    folderAssignments = folderStateStore.assignments,
                    validFolderIds = validFolderIds
                )
                ExistingDuplicateModImportSource(
                    storagePath = storagePath,
                    fileName = mod.jarFile.name,
                    assignedFolderId = folderId
                )
            }
            .sortedWith(
                compareBy<ExistingDuplicateModImportSource>(
                    { it.fileName.lowercase(Locale.ROOT) },
                    { it.fileName },
                    { it.storagePath }
                )
            )
            .toList()
    }

    private fun applyDuplicateModImportFolderReuse(
        host: Activity,
        normalizedModId: String,
        sourceStoragePaths: Collection<String>,
        targetStoragePath: String,
        assignedFolderId: String
    ) {
        val targetPath = targetStoragePath.trim()
        val folderId = assignedFolderId.trim()
        if (targetPath.isEmpty() || folderId.isEmpty()) {
            return
        }
        val folderStateStore = MainFolderStateStore().apply { ensureLoaded(host) }
        if (folderStateStore.folders.none { it.id == folderId }) {
            return
        }

        var changed = false
        sourceStoragePaths.forEach { sourcePath ->
            resolveModStoragePathCandidates(sourcePath).forEach { candidate ->
                if (folderStateStore.assignments.remove(candidate) != null) {
                    changed = true
                }
            }
        }
        val normalizedKey = ModManager.normalizeModId(normalizedModId)
        if (normalizedKey.isNotEmpty() && folderStateStore.assignments.remove(normalizedKey) != null) {
            changed = true
        }
        if (folderStateStore.assignments[targetPath] != folderId) {
            folderStateStore.assignments[targetPath] = folderId
            changed = true
        }
        if (changed) {
            folderStateStore.persist(host)
        }
    }

    private fun isEphemeralImportFileName(fileName: String?): Boolean {
        val normalized = fileName?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalized.isEmpty()) {
            return false
        }
        return normalized.startsWith(".import-") && normalized.endsWith(".tmp.jar")
    }

    private fun inspectImportableModJarIdentity(
        host: Activity,
        uri: Uri
    ): ModImportIdentityPreview? {
        val displayName = resolveDisplayName(host, uri)
        if (isLikelyCompressedArchive(host, uri, displayName)) {
            return null
        }
        val scratchDir = File(host.cacheDir, "mod-import-preview")
        if (!scratchDir.exists() && !scratchDir.mkdirs()) {
            throw IOException("Failed to create preview directory: ${scratchDir.absolutePath}")
        }
        val tempFile = File(scratchDir, ".preview-${System.nanoTime()}.jar")
        return try {
            val preparedDisplayName = JarImportInspectionService.prepareImportedJar(host, uri, tempFile)
            val inspection = JarImportInspectionService.inspectPreparedModJar(host, tempFile, preparedDisplayName)
            val manifest = inspection.manifest ?: run {
                return null
            }
            val normalizedModId = inspection.normalizedModId
            if (normalizedModId.isEmpty()) {
                return null
            }
            if (inspection.reservedComponent != null) {
                return null
            }
            ModImportIdentityPreview(
                normalizedModId = normalizedModId,
                displayModId = manifest.modId.trim().ifBlank { normalizedModId },
                displayName = preparedDisplayName.ifBlank { "$normalizedModId.jar" }
            )
        } catch (_: Throwable) {
            null
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun ModManager.InstalledMod.toModItemUi(storagePath: String): ModItemUi {
        return ModItemUi(
            modId = modId,
            manifestModId = manifestModId,
            storagePath = storagePath,
            name = name,
            version = version,
            description = description,
            dependencies = dependencies,
            required = required,
            installed = installed,
            enabled = enabled,
            explicitPriority = explicitPriority,
            effectivePriority = effectivePriority
        )
    }

    private fun inspectImportAtlasDownscaleCandidate(
        host: Activity,
        uri: Uri
    ): ModImportAtlasDownscalePreview? {
        val displayName = resolveDisplayName(host, uri)
        if (isLikelyCompressedArchive(host, uri, displayName)) {
            return null
        }
        val scratchDir = File(host.cacheDir, "mod-import-preview")
        if (!scratchDir.exists() && !scratchDir.mkdirs()) {
            throw IOException("Failed to create preview directory: ${scratchDir.absolutePath}")
        }
        val tempFile = File(scratchDir, ".preview-downscale-${System.nanoTime()}.jar")
        return try {
            val preparedDisplayName = JarImportInspectionService.prepareImportedJar(host, uri, tempFile)
            val inspection = JarImportInspectionService.inspectPreparedModJar(
                host,
                tempFile,
                preparedDisplayName
            )
            val manifest = inspection.manifest ?: return null
            val modId = inspection.normalizedModId.ifBlank { return null }
            if (inspection.reservedComponent != null) {
                return null
            }
            val patchResult = ModAtlasOfflineDownscalePatcher.inspectOversizedAtlasPages(
                tempFile,
                AtlasOfflineDownscaleStrategy.previewCandidates()
            )
            if (!patchResult.hasPatchedChanges) {
                return null
            }
            ModImportAtlasDownscalePreview(
                modId = modId,
                modName = manifest.name.trim().ifBlank { modId },
                downscaledAtlasEntries = patchResult.patchedAtlasEntries,
                downscaledAtlasPageEntries = patchResult.downscaledPageEntries
            )
        } catch (_: Throwable) {
            null
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun ModImportResult.toImportedModPatchInfo(): ImportedModPatchInfo {
        return ImportedModPatchInfo(
            modId = modId,
            modName = modName,
            patchedAtlasEntries = patchedAtlasEntries,
            patchedFilterLines = patchedFilterLines,
            downscaledAtlasEntries = downscaledAtlasEntries,
            downscaledAtlasPageEntries = downscaledAtlasPageEntries,
            patchedManifestRootEntries = patchedManifestRootEntries,
            patchedManifestRootPrefix = patchedManifestRootPrefix,
            patchedFrierenAntiPirateMethod = patchedFrierenAntiPirateMethod,
            patchedDownfallClassEntries = patchedDownfallClassEntries,
            patchedDownfallMerchantClassEntries = patchedDownfallMerchantClassEntries,
            patchedDownfallHexaghostBodyClassEntries = patchedDownfallHexaghostBodyClassEntries,
            patchedDownfallBossMechanicPanelClassEntries =
                patchedDownfallBossMechanicPanelClassEntries,
            patchedVupShionWebButtonConstructor = patchedVupShionWebButtonConstructor,
            patchedJacketNoAnoKoShaderEntries = patchedJacketNoAnoKoShaderEntries,
            patchedJacketNoAnoKoDesktopVersionDirectives =
                patchedJacketNoAnoKoDesktopVersionDirectives,
            patchedJacketNoAnoKoFragmentPrecisionBlocks =
                patchedJacketNoAnoKoFragmentPrecisionBlocks
        )
    }

    @Throws(IOException::class)
    fun importSaveArchive(
        host: Activity,
        uri: Uri,
        targetRoot: File = RuntimePaths.stsRoot(host),
    ): SaveImportResult {
        if (!targetRoot.exists() && !targetRoot.mkdirs()) {
            throw IOException("Failed to create save root: ${targetRoot.absolutePath}")
        }

        val scanResult = scanSaveArchive(host, uri)
        if (scanResult.importableFiles <= 0) {
            throw IOException("Archive did not contain importable save files")
        }

        val backupLabel = backupExistingSavesToDownloads(host, targetRoot)
        clearExistingSaveTargets(targetRoot, scanResult.targetTopLevelDirs)
        val importedFiles = extractSaveArchive(host, uri, targetRoot)
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
        replaceFileAtomically(source, target)
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
                    val importablePath = SaveArchiveLayout.resolveImportablePath(entry.name)
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
    private fun clearExistingSaveTargets(stsRoot: File, targetTopLevelDirs: Set<String>) {
        for (folderName in targetTopLevelDirs) {
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
                    val importablePath = SaveArchiveLayout.resolveImportablePath(entry.name)
                    if (importablePath.isNullOrEmpty()) {
                        continue
                    }

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
        return importedFiles
    }

    private fun normalizeReservedComponentName(rawComponent: String?): String? {
        val normalized = rawComponent?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (normalized.isEmpty()) {
            return null
        }
        return when (normalized) {
            JarImportInspectionService.RESERVED_COMPONENT_BASEMOD.lowercase(Locale.ROOT),
            "basemod.jar",
            ModManager.MOD_ID_BASEMOD -> JarImportInspectionService.RESERVED_COMPONENT_BASEMOD

            JarImportInspectionService.RESERVED_COMPONENT_STSLIB.lowercase(Locale.ROOT),
            "stslib.jar",
            ModManager.MOD_ID_STSLIB -> JarImportInspectionService.RESERVED_COMPONENT_STSLIB

            JarImportInspectionService.RESERVED_COMPONENT_MTS.lowercase(Locale.ROOT),
            "modthespire.jar",
            "modthespire" -> JarImportInspectionService.RESERVED_COMPONENT_MTS

            JarImportInspectionService.RESERVED_COMPONENT_AMETHYST_RUNTIME_COMPAT.lowercase(Locale.ROOT),
            "amethystruntimecompat.jar",
            ModManager.MOD_ID_AMETHYST_RUNTIME_COMPAT ->
                JarImportInspectionService.RESERVED_COMPONENT_AMETHYST_RUNTIME_COMPAT

            else -> rawComponent.trim()
        }
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
        ModManager.listInstalledMods(host)
            .asSequence()
            .filter { it.installed }
            .map { it.jarFile }
            .filter { it.isFile }
            .sortedWith(compareBy<File>({ it.name.lowercase(Locale.ROOT) }, { it.name }, { it.absolutePath }))
            .forEach(::addFile)

        addAssetIfMissing("ModTheSpire.jar", "components/mods/ModTheSpire.jar")
        addAssetIfMissing("BaseMod.jar", "components/mods/BaseMod.jar")
        addAssetIfMissing("StSLib.jar", "components/mods/StSLib.jar")
        addAssetIfMissing("AmethystRuntimeCompat.jar", "components/mods/AmethystRuntimeCompat.jar")

        return sources.values.toList()
    }

    private fun hasAsset(host: Activity, assetPath: String): Boolean {
        return try {
            host.assets.open(assetPath).use { true }
        } catch (_: Throwable) {
            false
        }
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

}

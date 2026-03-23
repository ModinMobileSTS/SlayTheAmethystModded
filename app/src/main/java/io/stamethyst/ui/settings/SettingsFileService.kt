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
import androidx.core.content.FileProvider
import io.stamethyst.R
import io.stamethyst.backend.diag.DiagnosticsProcessClient
import io.stamethyst.backend.launch.JvmLogRotationManager
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.DownfallImportCompatPatcher
import io.stamethyst.backend.mods.FrierenModCompatPatcher
import io.stamethyst.backend.mods.ModAtlasFilterCompatPatcher
import io.stamethyst.backend.mods.ModManifestRootCompatPatcher
import io.stamethyst.backend.mods.MtsLaunchManifestValidator
import io.stamethyst.backend.mods.OptionalModStorageCoordinator
import io.stamethyst.backend.mods.VupShionModCompatPatcher
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
    val patchedFrierenAntiPirateMethod: Boolean = false,
    val patchedDownfallClassEntries: Int = 0,
    val patchedDownfallMerchantClassEntries: Int = 0,
    val patchedDownfallHexaghostBodyClassEntries: Int = 0,
    val patchedDownfallBossMechanicPanelClassEntries: Int = 0,
    val patchedVupShionWebButtonConstructor: Boolean = false
) {
    val wasAtlasPatched: Boolean
        get() = patchedFilterLines > 0
    val wasManifestRootPatched: Boolean
        get() = patchedManifestRootEntries > 0
    val wasFrierenAntiPiratePatched: Boolean
        get() = patchedFrierenAntiPirateMethod
    val wasDownfallPatched: Boolean
        get() = patchedDownfallClassEntries > 0
    val wasVupShionPatched: Boolean
        get() = patchedVupShionWebButtonConstructor
    val hasCompatibilityPatches: Boolean
        get() = wasAtlasPatched ||
            wasManifestRootPatched ||
            wasFrierenAntiPiratePatched ||
            wasDownfallPatched ||
            wasVupShionPatched
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

internal data class DuplicateModImportConflict(
    val normalizedModId: String,
    val displayModId: String,
    val importingDisplayNames: List<String>,
    val existingDisplayNames: List<String>
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

    private const val RESERVED_COMPONENT_BASEMOD = "BaseMod"
    private const val RESERVED_COMPONENT_STSLIB = "StSLib"
    private const val RESERVED_COMPONENT_MTS = "ModTheSpire"
    private const val DOWNFALL_MOD_ID = "downfall"
    private const val FRIEREN_MOD_ID = "frierenmod"
    private const val VUPSHION_MOD_ID = "vupshionmod"
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
        replaceExistingDuplicates: Boolean = false
    ): ModImportResult {
        OptionalModStorageCoordinator.ensureOptionalModLibraryReady(host)
        val modsDir = RuntimePaths.optionalModsLibraryDir(host)
        if (!modsDir.exists() && !modsDir.mkdirs()) {
            throw IOException("Failed to create mods directory")
        }

        val displayName = resolveDisplayName(host, uri)
        val tempFile = File(modsDir, ".import-${System.nanoTime()}.tmp.jar")
        copyUriToFile(host, uri, tempFile)
        try {
            var patchedManifestRootEntries = 0
            var patchedManifestRootPrefix = ""
            val manifest = try {
                if (CompatibilitySettings.isModManifestRootCompatEnabled(host)) {
                    val patchResult = ModManifestRootCompatPatcher.patchNestedManifestRootInPlace(tempFile)
                    patchedManifestRootEntries = patchResult.patchedFileEntries
                    patchedManifestRootPrefix = patchResult.sourceRootPrefix
                }
                ModJarSupport.readModManifest(tempFile)
            } catch (error: Throwable) {
                if (isLikelyModTheSpireJar(tempFile, displayName)) {
                    throw ReservedModImportException(RESERVED_COMPONENT_MTS)
                }
                throw InvalidModImportException(
                    displayName = displayName,
                    reason = error.message.orEmpty()
                )
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
            if (replaceExistingDuplicates) {
                ModManager.removeExistingOptionalModsForImport(
                    context = host,
                    normalizedModId = modId,
                    launchModId = launchModId,
                    excludedPath = tempFile.absolutePath
                )
            }
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
                patchedFrierenAntiPirateMethod = patchedFrierenAntiPirateMethod,
                patchedDownfallClassEntries = patchedDownfallClassEntries,
                patchedDownfallMerchantClassEntries = patchedDownfallMerchantClassEntries,
                patchedDownfallHexaghostBodyClassEntries = patchedDownfallHexaghostBodyClassEntries,
                patchedDownfallBossMechanicPanelClassEntries =
                    patchedDownfallBossMechanicPanelClassEntries,
                patchedVupShionWebButtonConstructor = patchedVupShionWebButtonConstructor
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

    fun importModJars(
        host: Activity,
        uris: Collection<Uri>,
        replaceExistingDuplicates: Boolean = false
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
                    replaceExistingDuplicates = replaceExistingDuplicates
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
                                    result.patchedVupShionWebButtonConstructor
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
            uniqueComponents.add(RESERVED_COMPONENT_BASEMOD)
            uniqueComponents.add(RESERVED_COMPONENT_STSLIB)
            uniqueComponents.add(RESERVED_COMPONENT_MTS)
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
            copyUriToFile(host, uri, tempFile)
            if (CompatibilitySettings.isModManifestRootCompatEnabled(host)) {
                ModManifestRootCompatPatcher.patchNestedManifestRootInPlace(tempFile)
            }
            val manifest = try {
                ModJarSupport.readModManifest(tempFile)
            } catch (_: Throwable) {
                return null
            }
            val normalizedModId = ModManager.normalizeModId(manifest.modId)
            if (normalizedModId.isEmpty()) {
                return null
            }
            if (resolveReservedComponent(normalizedModId) != null) {
                return null
            }
            ModImportIdentityPreview(
                normalizedModId = normalizedModId,
                displayModId = manifest.modId.trim().ifBlank { normalizedModId },
                displayName = displayName.ifBlank { "$normalizedModId.jar" }
            )
        } catch (_: Throwable) {
            null
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
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
        val sourceRoots = SaveArchiveLayout.existingSourceDirectories(stsRoot)
        if (sourceRoots.isEmpty() || sourceRoots.none(::containsRegularFiles)) {
            return null
        }

        val backupFileName = buildSaveBackupFileName()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backupExistingSavesToScopedDownloads(host, sourceRoots, backupFileName)
            "Download/$backupFileName"
        } else {
            backupExistingSavesToLegacyDownloads(sourceRoots, backupFileName)
        }
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

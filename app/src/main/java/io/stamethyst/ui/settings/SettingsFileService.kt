package io.stamethyst.ui.settings

import android.app.Activity
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.stamethyst.backend.core.RuntimePaths
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
import java.util.Arrays
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class SaveImportResult(
    val importedFiles: Int,
    val backupLabel: String?
)

internal object SettingsFileService {
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
    fun createShareDebugBundleFile(host: Activity): File {
        val shareDir = File(host.cacheDir, "share")
        if (!shareDir.exists() && !shareDir.mkdirs()) {
            throw IOException("Failed to create share directory: ${shareDir.absolutePath}")
        }
        val shareFile = File(shareDir, buildDebugExportFileName())
        val stsRoot = RuntimePaths.stsRoot(host)
        FileOutputStream(shareFile, false).use { output ->
            ZipOutputStream(output).use { zipOutput ->
                writeDebugBundleToZip(host, zipOutput, stsRoot)
            }
        }
        return shareFile
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
    fun importModJar(host: Activity, uri: Uri): String {
        val modsDir = RuntimePaths.modsDir(host)
        if (!modsDir.exists() && !modsDir.mkdirs()) {
            throw IOException("Failed to create mods directory")
        }

        val tempFile = File(modsDir, ".import-${System.nanoTime()}.tmp.jar")
        copyUriToFile(host, uri, tempFile)
        try {
            val modId = ModManager.normalizeModId(ModJarSupport.resolveModId(tempFile))
            if (modId.isBlank()) {
                throw IOException("modid is empty")
            }
            if (ModManager.MOD_ID_BASEMOD == modId) {
                ModJarSupport.validateBaseModJar(tempFile)
            } else if (ModManager.MOD_ID_STSLIB == modId) {
                ModJarSupport.validateStsLibJar(tempFile)
            }
            val targetFile = ModManager.resolveStorageFileForModId(host, modId)
            moveFileReplacing(tempFile, targetFile)
            return modId
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

    @Throws(IOException::class)
    fun importSaveArchive(host: Activity, uri: Uri): SaveImportResult {
        val stsRoot = RuntimePaths.stsRoot(host)
        if (!stsRoot.exists() && !stsRoot.mkdirs()) {
            throw IOException("Failed to create save root: ${stsRoot.absolutePath}")
        }

        val importableCount = countImportableSaveEntries(host, uri)
        if (importableCount <= 0) {
            throw IOException("Archive did not contain importable save files")
        }

        val backupLabel = backupExistingSavesToDownloads(host, stsRoot)
        clearExistingSaveTargets(stsRoot)
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
    private fun countImportableSaveEntries(host: Activity, uri: Uri): Int {
        var importableFiles = 0
        host.contentResolver.openInputStream(uri).use { rawInput ->
            if (rawInput == null) {
                throw IOException("Unable to open selected archive")
            }
            java.util.zip.ZipInputStream(rawInput).use { zipInput ->
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    val mappedPath = resolveImportableArchivePath(entry.name)
                    if (mappedPath.isNullOrEmpty() || entry.isDirectory) {
                        continue
                    }
                    importableFiles++
                }
            }
        }
        return importableFiles
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
    private fun clearExistingSaveTargets(stsRoot: File) {
        for (folderName in SAVE_IMPORT_TOP_LEVEL_DIRS) {
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

    private fun buildDebugExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-debug-${formatter.format(Date())}.zip"
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

    private fun collectDebugBundleFiles(host: Activity, stsRoot: File): List<File> {
        val debugFiles = ArrayList<File>()
        addDebugFileIfExists(debugFiles, RuntimePaths.latestLog(host))
        addDebugFileIfExists(debugFiles, File(stsRoot, "jvm_output.log"))
        addDebugFileIfExists(debugFiles, RuntimePaths.lastCrashReport(host))
        addDebugFileIfExists(debugFiles, RuntimePaths.enabledModsConfig(host))

        val hsErrFiles = stsRoot.listFiles { _, name ->
            name != null && name.startsWith("hs_err_pid") && name.endsWith(".log")
        }
        if (hsErrFiles != null && hsErrFiles.isNotEmpty()) {
            Arrays.sort(hsErrFiles) { a, b -> b.lastModified().compareTo(a.lastModified()) }
            for (hsErrFile in hsErrFiles) {
                addDebugFileIfExists(debugFiles, hsErrFile)
            }
        }
        return debugFiles
    }

    @Throws(IOException::class)
    private fun writeDebugBundleToZip(host: Activity, zipOutput: ZipOutputStream, stsRoot: File): Int {
        val debugFiles = collectDebugBundleFiles(host, stsRoot)
        var exportedCount = 0
        for (file in debugFiles) {
            writeFileToZip(zipOutput, stsRoot, file)
            exportedCount++
        }
        if (exportedCount <= 0) {
            val entry = ZipEntry("sts/README.txt")
            zipOutput.putNextEntry(entry)
            val message = "No debug log files found yet.\n" +
                "Expected paths under: ${stsRoot.absolutePath}\n" +
                "Files: latestlog.txt, jvm_output.log, hs_err_pid*.log, last_crash_report.txt\n"
            zipOutput.write(message.toByteArray(StandardCharsets.UTF_8))
            zipOutput.closeEntry()
        }
        return exportedCount
    }

    @Throws(IOException::class)
    private fun writeFileToZip(zipOutput: ZipOutputStream, stsRoot: File, sourceFile: File) {
        val entryName = buildDebugEntryName(stsRoot, sourceFile)
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

    @Throws(IOException::class)
    private fun buildDebugEntryName(stsRoot: File, sourceFile: File): String {
        val rootPath = stsRoot.canonicalPath
        val filePath = sourceFile.canonicalPath
        val relativePath = if (filePath.startsWith("$rootPath${File.separator}")) {
            filePath.substring(rootPath.length + 1)
        } else {
            sourceFile.name
        }
        return "sts/${relativePath.replace('\\', '/')}"
    }

    private fun addDebugFileIfExists(files: MutableList<File>, file: File?) {
        if (file != null && file.isFile && file.length() > 0) {
            files.add(file)
        }
    }
}

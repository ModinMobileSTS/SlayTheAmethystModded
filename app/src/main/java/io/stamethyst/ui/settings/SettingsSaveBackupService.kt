package io.stamethyst.ui.settings

import android.app.Activity
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object SettingsSaveBackupService {
    @Throws(IOException::class)
    fun backupExistingSavesToDownloads(host: Activity, stsRoot: File): String? {
        val sourceRoots = SaveArchiveLayout.existingSourceDirectories(stsRoot)
        if (sourceRoots.isEmpty() || sourceRoots.none(::containsRegularFiles)) {
            return null
        }

        val backupFileName = buildSaveBackupFileName()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backupExistingSavesToScopedDownloads(host, sourceRoots, backupFileName, null)
            "Download/$backupFileName"
        } else {
            backupExistingSavesToLegacyDownloads(sourceRoots, backupFileName, null)
        }
    }

    @Throws(IOException::class)
    fun backupSaveProfileToDownloads(
        host: Activity,
        sourceRoot: File,
        backupFileName: String,
        relativeSubdirectory: String,
    ): String {
        val sourceRoots = SaveArchiveLayout.existingSourceDirectories(sourceRoot)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backupExistingSavesToScopedDownloads(
                host = host,
                sourceRoots = sourceRoots,
                backupFileName = backupFileName,
                relativeSubdirectory = relativeSubdirectory,
            )
            "Download/$relativeSubdirectory/$backupFileName"
        } else {
            backupExistingSavesToLegacyDownloads(
                sourceRoots = sourceRoots,
                backupFileName = backupFileName,
                relativeSubdirectory = relativeSubdirectory,
            )
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
        backupFileName: String,
        relativeSubdirectory: String?,
    ) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, backupFileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                buildDownloadsRelativePath(relativeSubdirectory)
            )
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
        backupFileName: String,
        relativeSubdirectory: String?,
    ): String {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("Downloads directory is unavailable")
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw IOException("Failed to create Downloads directory: ${downloadsDir.absolutePath}")
        }

        val targetDir = relativeSubdirectory
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { File(downloadsDir, it) }
            ?: downloadsDir
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Failed to create backup directory: ${targetDir.absolutePath}")
        }

        val backupFile = File(targetDir, backupFileName)
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

    private fun buildDownloadsRelativePath(relativeSubdirectory: String?): String {
        val suffix = relativeSubdirectory
            ?.trim()
            ?.replace('\\', '/')
            ?.trim('/')
            ?.takeIf { it.isNotEmpty() }
            ?: return Environment.DIRECTORY_DOWNLOADS
        return Environment.DIRECTORY_DOWNLOADS + "/" + suffix
    }

    @Throws(IOException::class)
    private fun exportSaveFolderToZip(
        zipOutput: ZipOutputStream,
        sourceRoot: File,
        writtenEntries: MutableSet<String>,
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

    @Throws(IOException::class)
    private fun writeFileToZip(
        zipOutput: ZipOutputStream,
        sourceFile: File,
        entryName: String,
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
            }
        }
        zipOutput.closeEntry()
    }
}

package io.stamethyst.backend.mods

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.LinkedHashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class DuplicateZipNormalizationResult(
    val totalEntries: Int,
    val uniqueEntries: Int,
    val duplicateEntriesRemoved: Int
) {
    val changed: Boolean
        get() = duplicateEntriesRemoved > 0
}

internal object DuplicateZipEntryNormalizer {
    @Throws(IOException::class)
    fun normalizeInPlaceIfNeeded(zipFile: File): DuplicateZipNormalizationResult {
        if (!zipFile.isFile) {
            throw IOException("Zip file not found: ${zipFile.absolutePath}")
        }

        val scanResult = scan(zipFile)
        if (scanResult.duplicateEntriesRemoved <= 0) {
            return scanResult
        }

        rewriteKeepingFirstEntry(zipFile)
        return scanResult
    }

    private fun scan(zipFile: File): DuplicateZipNormalizationResult {
        val seenNames = LinkedHashSet<String>()
        var totalEntries = 0
        var duplicateEntriesRemoved = 0

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zipInput ->
            while (true) {
                val entry = zipInput.nextEntry ?: break
                totalEntries++
                if (!seenNames.add(entry.name)) {
                    duplicateEntriesRemoved++
                }
                zipInput.closeEntry()
            }
        }

        return DuplicateZipNormalizationResult(
            totalEntries = totalEntries,
            uniqueEntries = seenNames.size,
            duplicateEntriesRemoved = duplicateEntriesRemoved
        )
    }

    @Throws(IOException::class)
    private fun rewriteKeepingFirstEntry(zipFile: File) {
        val tempFile = File(zipFile.absolutePath + ".dedup.tmp")
        val seenNames = LinkedHashSet<String>()
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zipInput ->
                FileOutputStream(tempFile, false).use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        while (true) {
                            val entry = zipInput.nextEntry ?: break
                            val entryName = entry.name
                            if (!seenNames.add(entryName)) {
                                zipInput.closeEntry()
                                continue
                            }

                            val outEntry = ZipEntry(entryName)
                            if (entry.time > 0L) {
                                outEntry.time = entry.time
                            }
                            entry.comment?.let { outEntry.comment = it }
                            entry.extra?.let { outEntry.extra = it }
                            zipOut.putNextEntry(outEntry)
                            if (!entry.isDirectory) {
                                JarFileIoUtils.copyStream(zipInput, zipOut)
                            }
                            zipOut.closeEntry()
                            zipInput.closeEntry()
                        }
                    }
                }
            }

            if (zipFile.exists() && !zipFile.delete()) {
                throw IOException("Failed to replace ${zipFile.absolutePath}")
            }
            if (!tempFile.renameTo(zipFile)) {
                throw IOException("Failed to move ${tempFile.absolutePath} -> ${zipFile.absolutePath}")
            }
            zipFile.setLastModified(System.currentTimeMillis())
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}

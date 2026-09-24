package io.stamethyst.backend.mods

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.LinkedHashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

internal data class DuplicateZipNormalizationResult(
    val totalEntries: Int,
    val uniqueEntries: Int,
    val duplicateEntriesRemoved: Int,
    val rewritten: Boolean = duplicateEntriesRemoved > 0
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

        val platformScanResult = scanWithPlatformZipFile(zipFile)
        if (platformScanResult != null && platformScanResult.duplicateEntriesRemoved <= 0) {
            return platformScanResult.copy(rewritten = false)
        }

        val scanResult = platformScanResult ?: scanWithArchiveInputStream(zipFile)
        rewriteKeepingFirstEntry(zipFile)
        return scanResult.copy(rewritten = true)
    }

    private fun scanWithPlatformZipFile(zipFile: File): DuplicateZipNormalizationResult? {
        return try {
            val seenNames = LinkedHashSet<String>()
            var totalEntries = 0
            var duplicateEntriesRemoved = 0
            ZipFile(zipFile).use { platformZip ->
                val entries = platformZip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    totalEntries++
                    if (!seenNames.add(entry.name)) {
                        duplicateEntriesRemoved++
                    }
                }
            }
            DuplicateZipNormalizationResult(
                totalEntries = totalEntries,
                uniqueEntries = seenNames.size,
                duplicateEntriesRemoved = duplicateEntriesRemoved
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun scanWithArchiveInputStream(zipFile: File): DuplicateZipNormalizationResult {
        val seenNames = LinkedHashSet<String>()
        var totalEntries = 0
        var duplicateEntriesRemoved = 0

        ZipArchiveInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zipInput ->
            while (true) {
                val entry = zipInput.nextZipEntry ?: break
                totalEntries++
                if (!seenNames.add(entry.name)) {
                    duplicateEntriesRemoved++
                }
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
            ZipArchiveInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zipInput ->
                FileOutputStream(tempFile, false).use { outputStream ->
                    ZipArchiveOutputStream(outputStream).use { zipOut ->
                        while (true) {
                            val entry = zipInput.nextZipEntry ?: break
                            val entryName = entry.name
                            if (!seenNames.add(entryName)) {
                                continue
                            }

                            val outEntry = ZipArchiveEntry(entryName)
                            if (entry.time > 0L) {
                                outEntry.time = entry.time
                            }
                            zipOut.putArchiveEntry(outEntry)
                            if (!entry.isDirectory) {
                                JarFileIoUtils.copyStream(zipInput, zipOut)
                            }
                            zipOut.closeArchiveEntry()
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

    /**
     * Writes a duplicate-free copy of [source] to [target] without touching the original.
     *
     * Android's `java.util.zip.ZipFile` rejects archives with duplicate entry names (the shipped
     * `desktop-1.0.jar` has one), which makes such jars unusable as an ECJ classpath entry.
     * `ZipInputStream` reads sequentially and tolerates the duplicates, so this rebuilds a jar that
     * `ZipFile` can open. Used to prepare on-device compilation classpaths.
     */
    @Throws(IOException::class)
    fun copyDeduplicated(source: File, target: File) {
        val seenNames = LinkedHashSet<String>()
        ZipInputStream(BufferedInputStream(FileInputStream(source))).use { zipInput ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(target, false))).use { zipOut ->
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    if (!seenNames.add(entry.name)) {
                        continue
                    }
                    val outEntry = ZipEntry(entry.name)
                    if (entry.time >= 0L) {
                        outEntry.time = entry.time
                    }
                    zipOut.putNextEntry(outEntry)
                    if (!entry.isDirectory) {
                        JarFileIoUtils.copyStream(zipInput, zipOut)
                    }
                    zipOut.closeEntry()
                }
            }
        }
    }

    /**
     * Creates a duplicate-free classpath overlay containing only class entries.
     *
     * A parent mod may be hundreds of megabytes because of textures, audio, or other resources.
     * ECJ and CFR need bytecode for symbol resolution, not those resources, so copying only classes
     * avoids turning a duplicate-entry workaround into a second full-size archive.
     */
    @Throws(IOException::class)
    fun copyClassEntries(source: File, target: File) {
        val seenNames = LinkedHashSet<String>()
        ZipArchiveInputStream(BufferedInputStream(FileInputStream(source))).use { zipInput ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(target, false))).use { zipOut ->
                while (true) {
                    val entry = zipInput.nextZipEntry ?: break
                    if (entry.isDirectory || !entry.name.endsWith(".class", ignoreCase = true)) continue
                    if (!seenNames.add(entry.name)) continue
                    val outEntry = ZipEntry(entry.name)
                    if (entry.time >= 0L) outEntry.time = entry.time
                    zipOut.putNextEntry(outEntry)
                    JarFileIoUtils.copyStream(zipInput, zipOut)
                    zipOut.closeEntry()
                }
            }
        }
    }
}

package io.stamethyst.backend.mods

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.LinkedHashSet
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class ManifestRootPatchResult(
    val scannedFileEntries: Int,
    val patchedFileEntries: Int,
    val sourceRootPrefix: String
) {
    val hasPatchedChanges: Boolean
        get() = patchedFileEntries > 0
}

internal object ModManifestRootCompatPatcher {
    private const val MANIFEST_FILE_NAME = "ModTheSpire.json"
    private const val META_INF_PREFIX = "META-INF/"

    private data class PatchPlan(
        val scannedFileEntries: Int,
        val patchedFileEntries: Int,
        val sourceRootPrefix: String
    )

    @Throws(IOException::class)
    fun patchNestedManifestRootInPlace(modJar: File): ManifestRootPatchResult {
        if (!modJar.isFile) {
            throw IOException("Mod jar not found: ${modJar.absolutePath}")
        }

        val plan = ZipFile(modJar).use { zipFile ->
            createPatchPlan(zipFile)
        }
        if (plan.patchedFileEntries <= 0 || plan.sourceRootPrefix.isEmpty()) {
            return ManifestRootPatchResult(
                scannedFileEntries = plan.scannedFileEntries,
                patchedFileEntries = 0,
                sourceRootPrefix = ""
            )
        }

        rewriteJarRemovingPrefix(modJar, plan.sourceRootPrefix)
        return ManifestRootPatchResult(
            scannedFileEntries = plan.scannedFileEntries,
            patchedFileEntries = plan.patchedFileEntries,
            sourceRootPrefix = plan.sourceRootPrefix
        )
    }

    private fun createPatchPlan(zipFile: ZipFile): PatchPlan {
        if (hasRootManifest(zipFile)) {
            return PatchPlan(
                scannedFileEntries = countFileEntries(zipFile),
                patchedFileEntries = 0,
                sourceRootPrefix = ""
            )
        }

        val sourcePrefix = findSingleNestedManifestPrefix(zipFile) ?: return PatchPlan(
            scannedFileEntries = countFileEntries(zipFile),
            patchedFileEntries = 0,
            sourceRootPrefix = ""
        )

        var scannedFileEntries = 0
        var patchedFileEntries = 0
        val outputNames = LinkedHashSet<String>()
        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) {
                continue
            }
            val normalizedName = normalizeEntryName(entry.name)
            if (normalizedName.isEmpty()) {
                continue
            }
            scannedFileEntries++

            if (!startsWithIgnoreCase(normalizedName, sourcePrefix)
                && !startsWithIgnoreCase(normalizedName, META_INF_PREFIX)
            ) {
                return PatchPlan(
                    scannedFileEntries = scannedFileEntries,
                    patchedFileEntries = 0,
                    sourceRootPrefix = ""
                )
            }

            val outputName = resolveOutputName(normalizedName, sourcePrefix)
            if (outputName.isEmpty()) {
                return PatchPlan(
                    scannedFileEntries = scannedFileEntries,
                    patchedFileEntries = 0,
                    sourceRootPrefix = ""
                )
            }
            if (!outputNames.add(outputName.lowercase(Locale.ROOT))) {
                return PatchPlan(
                    scannedFileEntries = scannedFileEntries,
                    patchedFileEntries = 0,
                    sourceRootPrefix = ""
                )
            }
            if (!normalizedName.equals(outputName, ignoreCase = false)) {
                patchedFileEntries++
            }
        }

        return PatchPlan(
            scannedFileEntries = scannedFileEntries,
            patchedFileEntries = patchedFileEntries,
            sourceRootPrefix = sourcePrefix
        )
    }

    private fun hasRootManifest(zipFile: ZipFile): Boolean {
        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) {
                continue
            }
            val normalizedName = normalizeEntryName(entry.name)
            if (normalizedName.equals(MANIFEST_FILE_NAME, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun countFileEntries(zipFile: ZipFile): Int {
        var count = 0
        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory) {
                count++
            }
        }
        return count
    }

    private fun findSingleNestedManifestPrefix(zipFile: ZipFile): String? {
        var prefix: String? = null
        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) {
                continue
            }
            val normalizedName = normalizeEntryName(entry.name)
            val marker = "/$MANIFEST_FILE_NAME"
            if (!normalizedName.lowercase(Locale.ROOT).endsWith(marker.lowercase(Locale.ROOT))) {
                continue
            }
            val slashIndex = normalizedName.lastIndexOf('/')
            if (slashIndex <= 0) {
                continue
            }
            val currentPrefix = normalizedName.substring(0, slashIndex + 1)
            if (startsWithIgnoreCase(currentPrefix, META_INF_PREFIX)) {
                continue
            }
            if (prefix == null) {
                prefix = currentPrefix
            } else if (!prefix.equals(currentPrefix, ignoreCase = true)) {
                return null
            }
        }
        return prefix
    }

    @Throws(IOException::class)
    private fun rewriteJarRemovingPrefix(modJar: File, sourcePrefix: String) {
        val tempJar = File(modJar.absolutePath + ".manifestrootfix.tmp")
        val writtenEntries = LinkedHashSet<String>()
        try {
            ZipFile(modJar).use { zipFile ->
                FileOutputStream(tempJar, false).use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        val entries = zipFile.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.isDirectory) {
                                continue
                            }
                            val normalizedName = normalizeEntryName(entry.name)
                            if (normalizedName.isEmpty()) {
                                continue
                            }
                            val outputName = resolveOutputName(normalizedName, sourcePrefix)
                            if (outputName.isEmpty()) {
                                continue
                            }
                            val dedupKey = outputName.lowercase(Locale.ROOT)
                            if (!writtenEntries.add(dedupKey)) {
                                continue
                            }

                            val outEntry = ZipEntry(outputName)
                            if (entry.time > 0) {
                                outEntry.time = entry.time
                            }
                            zipOut.putNextEntry(outEntry)
                            zipFile.getInputStream(entry).use { input ->
                                JarFileIoUtils.copyStream(input, zipOut)
                            }
                            zipOut.closeEntry()
                        }
                    }
                }
            }

            if (modJar.exists() && !modJar.delete()) {
                throw IOException("Failed to replace ${modJar.absolutePath}")
            }
            if (!tempJar.renameTo(modJar)) {
                throw IOException("Failed to move ${tempJar.absolutePath} -> ${modJar.absolutePath}")
            }
            modJar.setLastModified(System.currentTimeMillis())
        } finally {
            if (tempJar.exists()) {
                tempJar.delete()
            }
        }
    }

    private fun resolveOutputName(normalizedName: String, sourcePrefix: String): String {
        if (startsWithIgnoreCase(normalizedName, sourcePrefix)) {
            return normalizedName.substring(sourcePrefix.length)
        }
        return normalizedName
    }

    private fun normalizeEntryName(name: String?): String {
        if (name == null) {
            return ""
        }
        var normalized = name.replace('\\', '/')
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1)
        }
        return normalized.trim()
    }

    private fun startsWithIgnoreCase(text: String, prefix: String): Boolean {
        if (prefix.isEmpty()) {
            return false
        }
        if (text.length < prefix.length) {
            return false
        }
        return text.substring(0, prefix.length).equals(prefix, ignoreCase = true)
    }
}

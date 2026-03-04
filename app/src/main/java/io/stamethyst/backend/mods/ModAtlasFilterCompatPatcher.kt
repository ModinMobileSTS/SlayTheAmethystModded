package io.stamethyst.backend.mods

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class AtlasFilterPatchResult(
    val scannedAtlasEntries: Int,
    val patchedAtlasEntries: Int,
    val patchedFilterLines: Int
) {
    val hasPatchedChanges: Boolean
        get() = patchedFilterLines > 0
}

internal object ModAtlasFilterCompatPatcher {
    private val ATLAS_FILTER_MIPMAP_LINE_REGEX = Regex(
        "(?im)^([ \\t]*)filter\\s*:\\s*[^\\r\\n]*mipmap[^\\r\\n]*$"
    )

    @Throws(IOException::class)
    fun patchMipMapFiltersInPlace(modJar: File): AtlasFilterPatchResult {
        if (!modJar.isFile) {
            throw IOException("Mod jar not found: ${modJar.absolutePath}")
        }

        val replacements: MutableMap<String, ByteArray> = HashMap()
        var scannedAtlasEntries = 0
        var patchedAtlasEntries = 0
        var patchedFilterLines = 0

        ZipFile(modJar).use { zipFile ->
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) {
                    continue
                }
                val entryName = entry.name
                if (!entryName.lowercase(Locale.ROOT).endsWith(".atlas")) {
                    continue
                }
                scannedAtlasEntries++
                val atlasText = JarFileIoUtils.readEntry(zipFile, entry)
                val patch = patchAtlasText(atlasText)
                if (patch.replacedLineCount <= 0) {
                    continue
                }
                replacements[entryName] = patch.text.toByteArray(StandardCharsets.UTF_8)
                patchedAtlasEntries++
                patchedFilterLines += patch.replacedLineCount
            }
        }

        if (replacements.isNotEmpty()) {
            rewriteJarWithReplacements(modJar, replacements)
        }

        return AtlasFilterPatchResult(
            scannedAtlasEntries = scannedAtlasEntries,
            patchedAtlasEntries = patchedAtlasEntries,
            patchedFilterLines = patchedFilterLines
        )
    }

    private data class AtlasTextPatchResult(
        val text: String,
        val replacedLineCount: Int
    )

    private fun patchAtlasText(source: String): AtlasTextPatchResult {
        var replacedLineCount = 0
        val patchedText = ATLAS_FILTER_MIPMAP_LINE_REGEX.replace(source) { match ->
            replacedLineCount++
            "${match.groupValues[1]}filter: Linear,Linear"
        }
        return AtlasTextPatchResult(
            text = patchedText,
            replacedLineCount = replacedLineCount
        )
    }

    @Throws(IOException::class)
    private fun rewriteJarWithReplacements(modJar: File, replacements: Map<String, ByteArray>) {
        val tempJar = File(modJar.absolutePath + ".atlaspatch.tmp")
        val seenNames: MutableSet<String> = HashSet()
        try {
            ZipFile(modJar).use { zipFile ->
                FileOutputStream(tempJar, false).use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        val entries = zipFile.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val name = entry.name
                            if (!seenNames.add(name)) {
                                continue
                            }

                            val outEntry = ZipEntry(name)
                            if (entry.time > 0) {
                                outEntry.time = entry.time
                            }
                            zipOut.putNextEntry(outEntry)
                            if (!entry.isDirectory) {
                                val replacement = replacements[name]
                                if (replacement != null) {
                                    zipOut.write(replacement)
                                } else {
                                    zipFile.getInputStream(entry).use { input ->
                                        JarFileIoUtils.copyStream(input, zipOut)
                                    }
                                }
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
}

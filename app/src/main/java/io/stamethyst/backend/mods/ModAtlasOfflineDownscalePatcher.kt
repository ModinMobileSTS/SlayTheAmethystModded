package io.stamethyst.backend.mods

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream
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
import kotlin.math.roundToInt

internal data class AtlasOfflineDownscaleResult(
    val scannedAtlasEntries: Int,
    val patchedAtlasEntries: Int,
    val downscaledPageEntries: Int
) {
    val hasPatchedChanges: Boolean
        get() = downscaledPageEntries > 0
}

internal object ModAtlasOfflineDownscalePatcher {
    private const val MAX_OUTPUT_EDGE_PX = 512
    private val NUMERIC_TUPLE_LINE_REGEX = Regex(
        "^([ \\t]*[^:]+:\\s*)(-?\\d+(?:\\s*,\\s*-?\\d+)+)(\\s*)$"
    )

    private data class AtlasPageSpan(
        val pageNameLine: String,
        val pageNameIndex: Int,
        val headerStartIndex: Int,
        val headerEndIndexExclusive: Int,
        val endIndexExclusive: Int
    )

    @Throws(IOException::class)
    fun patchOversizedAtlasPagesInPlace(modJar: File): AtlasOfflineDownscaleResult {
        if (!modJar.isFile) {
            throw IOException("Mod jar not found: ${modJar.absolutePath}")
        }

        val replacements: MutableMap<String, ByteArray> = HashMap()
        var scannedAtlasEntries = 0
        var patchedAtlasEntries = 0
        var downscaledPageEntries = 0

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
                val plan = buildPatchPlan(zipFile, entryName, atlasText) ?: continue
                replacements[entryName] = plan.patchedAtlasText.toByteArray(StandardCharsets.UTF_8)
                replacements.putAll(plan.imageReplacements)
                patchedAtlasEntries++
                downscaledPageEntries += plan.downscaledPageEntries
            }
        }

        if (replacements.isNotEmpty()) {
            rewriteJarWithReplacements(modJar, replacements)
        }

        return AtlasOfflineDownscaleResult(
            scannedAtlasEntries = scannedAtlasEntries,
            patchedAtlasEntries = patchedAtlasEntries,
            downscaledPageEntries = downscaledPageEntries
        )
    }

    private data class PatchPlan(
        val patchedAtlasText: String,
        val imageReplacements: Map<String, ByteArray>,
        val downscaledPageEntries: Int
    )

    private data class PageTransform(
        val entryName: String,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val targetWidth: Int,
        val targetHeight: Int,
        val replacementBytes: ByteArray
    ) {
        val scale: Float = targetWidth.toFloat() / sourceWidth.toFloat()
    }

    private data class BitmapBounds(
        val width: Int,
        val height: Int
    )

    internal fun collectAtlasPageEntryNames(
        atlasEntryName: String,
        atlasText: String
    ): List<String> {
        val normalizedText = atlasText.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalizedText.split('\n')
        val pageEntries = ArrayList<String>()
        var index = 0
        while (true) {
            val page = findNextAtlasPageSpan(lines, index) ?: break
            pageEntries += resolveAtlasPageEntryName(atlasEntryName, page.pageNameLine.trim())
            index = page.endIndexExclusive
        }
        return pageEntries
    }

    internal fun rewriteAtlasTextForPageScales(
        atlasEntryName: String,
        atlasText: String,
        pageScales: Map<String, Float>
    ): String {
        val normalizedPageScales = pageScales.mapKeys { it.key.lowercase(Locale.ROOT) }
        return patchAtlasText(
            atlasEntryName = atlasEntryName,
            atlasText = atlasText.replace("\r\n", "\n").replace('\r', '\n'),
            pageScales = normalizedPageScales
        )
    }

    private fun buildPatchPlan(
        zipFile: ZipFile,
        atlasEntryName: String,
        atlasText: String
    ): PatchPlan? {
        val pageTransforms = LinkedHashMap<String, PageTransform>()
        val normalizedText = atlasText.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalizedText.split('\n')
        var index = 0

        while (true) {
            val page = findNextAtlasPageSpan(lines, index) ?: break
            index = page.endIndexExclusive
            val resolvedEntryName = resolveAtlasPageEntryName(atlasEntryName, page.pageNameLine.trim())
            val transform = createPageTransform(zipFile, resolvedEntryName) ?: continue
            pageTransforms[resolvedEntryName.lowercase(Locale.ROOT)] = transform
        }

        if (pageTransforms.isEmpty()) {
            return null
        }

        val patchedText = patchAtlasText(
            atlasEntryName = atlasEntryName,
            atlasText = normalizedText,
            pageScales = pageTransforms.mapValues { (_, transform) -> transform.scale }
        )
        return PatchPlan(
            patchedAtlasText = patchedText,
            imageReplacements = pageTransforms.values.associate { transform ->
                transform.entryName to transform.replacementBytes
            },
            downscaledPageEntries = pageTransforms.size
        )
    }

    private fun patchAtlasText(
        atlasEntryName: String,
        atlasText: String,
        pageScales: Map<String, Float>
    ): String {
        val lines = atlasText.split('\n')
        val output = ArrayList<String>(lines.size)
        var index = 0

        while (true) {
            val page = findNextAtlasPageSpan(lines, index) ?: break
            while (index < page.pageNameIndex) {
                output.add(lines[index])
                index++
            }

            val pageEntryName = resolveAtlasPageEntryName(atlasEntryName, page.pageNameLine.trim())
                .lowercase(Locale.ROOT)
            val pageScale = pageScales[pageEntryName]
            output.add(lines[page.pageNameIndex])
            index = page.headerStartIndex

            while (index < page.headerEndIndexExclusive) {
                output.add(scaleNumericTupleLine(lines[index], pageScale))
                index++
            }

            while (index < page.endIndexExclusive) {
                val line = lines[index]
                if (isIndentedAtlasLine(line)) {
                    output.add(scaleNumericTupleLine(line, pageScale))
                } else {
                    output.add(line)
                }
                index++
            }
        }

        while (index < lines.size) {
            output.add(lines[index])
            index++
        }

        return output.joinToString("\n")
    }

    private fun scaleNumericTupleLine(line: String, pageScale: Float?): String {
        if (pageScale == null) {
            return line
        }
        val match = NUMERIC_TUPLE_LINE_REGEX.matchEntire(line) ?: return line
        val prefix = match.groupValues[1]
        val tupleValues = match.groupValues[2]
            .split(',')
            .map { value -> value.trim().toIntOrNull() ?: return line }
        val scaledValues = tupleValues.map { value -> scaleAtlasInt(value, pageScale) }
        return prefix + scaledValues.joinToString(", ") + match.groupValues[3]
    }

    private fun findNextAtlasPageSpan(lines: List<String>, startIndex: Int): AtlasPageSpan? {
        var index = startIndex
        while (index < lines.size && lines[index].isBlank()) {
            index++
        }
        if (index >= lines.size) {
            return null
        }

        val pageNameIndex = index
        val pageNameLine = lines[index]
        index++

        val headerStartIndex = index
        while (index < lines.size && lines[index].isNotBlank() && isAtlasHeaderPropertyLine(lines[index])) {
            index++
        }
        val headerEndIndexExclusive = index

        while (index < lines.size && lines[index].isNotBlank()) {
            index++
            while (index < lines.size && lines[index].isNotBlank() && isIndentedAtlasLine(lines[index])) {
                index++
            }
        }

        return AtlasPageSpan(
            pageNameLine = pageNameLine,
            pageNameIndex = pageNameIndex,
            headerStartIndex = headerStartIndex,
            headerEndIndexExclusive = headerEndIndexExclusive,
            endIndexExclusive = index
        )
    }

    private fun isAtlasHeaderPropertyLine(line: String): Boolean {
        return !isIndentedAtlasLine(line) && line.contains(':')
    }

    private fun isIndentedAtlasLine(line: String): Boolean {
        return line.firstOrNull()?.isWhitespace() == true
    }

    private fun scaleAtlasInt(value: Int, scale: Float): Int {
        if (value == 0) {
            return 0
        }
        val scaled = (value.toFloat() * scale).roundToInt()
        return when {
            value > 0 && scaled <= 0 -> 1
            value < 0 && scaled >= 0 -> -1
            else -> scaled
        }
    }

    private fun createPageTransform(
        zipFile: ZipFile,
        entryName: String
    ): PageTransform? {
        val entry = findEntryExactIgnoreCase(zipFile, entryName) ?: return null
        val imageBytes = JarFileIoUtils.readEntryBytes(zipFile, entry)
        val bounds = decodeBitmapBounds(imageBytes) ?: return null
        if (bounds.width <= MAX_OUTPUT_EDGE_PX && bounds.height <= MAX_OUTPUT_EDGE_PX) {
            return null
        }

        val scale = minOf(
            MAX_OUTPUT_EDGE_PX.toFloat() / bounds.width.toFloat(),
            MAX_OUTPUT_EDGE_PX.toFloat() / bounds.height.toFloat()
        )
        if (scale >= 1.0f) {
            return null
        }

        val targetWidth = (bounds.width.toFloat() * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bounds.height.toFloat() * scale).roundToInt().coerceAtLeast(1)
        val replacementBytes = downscaleImageBytes(
            imageBytes = imageBytes,
            entryName = entryName,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        ) ?: return null

        return PageTransform(
            entryName = entry.name,
            sourceWidth = bounds.width,
            sourceHeight = bounds.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            replacementBytes = replacementBytes
        )
    }

    private fun decodeBitmapBounds(imageBytes: ByteArray): BitmapBounds? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) {
            return null
        }
        return BitmapBounds(width, height)
    }

    private fun downscaleImageBytes(
        imageBytes: ByteArray,
        entryName: String,
        targetWidth: Int,
        targetHeight: Int
    ): ByteArray? {
        val decodeOptions = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sourceBitmap = BitmapFactory.decodeByteArray(
            imageBytes,
            0,
            imageBytes.size,
            decodeOptions
        ) ?: return null

        val scaledBitmap = Bitmap.createScaledBitmap(sourceBitmap, targetWidth, targetHeight, true)
        try {
            if (scaledBitmap === sourceBitmap) {
                return imageBytes
            }
            return encodeBitmapBytes(scaledBitmap, entryName)
        } finally {
            if (scaledBitmap !== sourceBitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
            if (!sourceBitmap.isRecycled) {
                sourceBitmap.recycle()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun encodeBitmapBytes(bitmap: Bitmap, entryName: String): ByteArray? {
        val extension = entryName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val format = when (extension) {
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
            "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                Bitmap.CompressFormat.WEBP
            }
            else -> Bitmap.CompressFormat.PNG
        }
        val quality = if (format == Bitmap.CompressFormat.JPEG) 95 else 100
        return ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(format, quality, output)) {
                return null
            }
            output.toByteArray()
        }
    }

    private fun resolveAtlasPageEntryName(atlasEntryName: String, pageName: String): String {
        val normalizedPageName = pageName.replace('\\', '/').removePrefix("/")
        if (normalizedPageName.contains('/')) {
            return normalizedPageName
        }
        val separatorIndex = atlasEntryName.lastIndexOf('/')
        return if (separatorIndex >= 0) {
            atlasEntryName.substring(0, separatorIndex + 1) + normalizedPageName
        } else {
            normalizedPageName
        }
    }

    private fun findEntryExactIgnoreCase(zipFile: ZipFile, entryName: String): ZipEntry? {
        val target = entryName.replace('\\', '/').lowercase(Locale.ROOT)
        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.name.replace('\\', '/').lowercase(Locale.ROOT) == target) {
                return entry
            }
        }
        return null
    }

    @Throws(IOException::class)
    private fun rewriteJarWithReplacements(modJar: File, replacements: Map<String, ByteArray>) {
        val tempJar = File(modJar.absolutePath + ".atlasdownscale.tmp")
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

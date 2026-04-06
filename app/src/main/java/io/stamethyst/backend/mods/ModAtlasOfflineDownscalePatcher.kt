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

internal enum class AtlasOfflineDownscaleMode {
    PERCENTAGE,
    MAX_EDGE
}

internal data class AtlasOfflineDownscaleStrategy(
    val mode: AtlasOfflineDownscaleMode,
    val value: Int
) {
    companion object {
        private val PERCENTAGE_OPTIONS = intArrayOf(25, 50, 75)
        private val MAX_EDGE_OPTIONS = intArrayOf(512, 1024, 2048)
        const val CANDIDATE_PREVIEW_MAX_EDGE_PX = 512
        const val DEFAULT_PERCENTAGE = 75
        const val DEFAULT_MAX_EDGE_PX = 1024

        fun percentage(percent: Int): AtlasOfflineDownscaleStrategy {
            return AtlasOfflineDownscaleStrategy(
                mode = AtlasOfflineDownscaleMode.PERCENTAGE,
                value = normalizeValue(AtlasOfflineDownscaleMode.PERCENTAGE, percent)
            )
        }

        fun maxEdge(maxEdgePx: Int): AtlasOfflineDownscaleStrategy {
            return AtlasOfflineDownscaleStrategy(
                mode = AtlasOfflineDownscaleMode.MAX_EDGE,
                value = normalizeValue(AtlasOfflineDownscaleMode.MAX_EDGE, maxEdgePx)
            )
        }

        fun previewCandidates(): AtlasOfflineDownscaleStrategy {
            return maxEdge(CANDIDATE_PREVIEW_MAX_EDGE_PX)
        }

        fun percentageOptions(): IntArray {
            return PERCENTAGE_OPTIONS.copyOf()
        }

        fun maxEdgeOptions(): IntArray {
            return MAX_EDGE_OPTIONS.copyOf()
        }

        private fun normalizeValue(mode: AtlasOfflineDownscaleMode, rawValue: Int): Int {
            val options = when (mode) {
                AtlasOfflineDownscaleMode.PERCENTAGE -> PERCENTAGE_OPTIONS
                AtlasOfflineDownscaleMode.MAX_EDGE -> MAX_EDGE_OPTIONS
            }
            return options.minByOrNull { option ->
                kotlin.math.abs(option - rawValue)
            } ?: options.first()
        }
    }
}

internal object ModAtlasOfflineDownscalePatcher {
    const val DEFAULT_MAX_OUTPUT_EDGE_PX = 512
    private val NUMERIC_TUPLE_LINE_REGEX = Regex(
        "^([ \\t]*[^:]+:\\s*)(-?\\d+(?:\\s*,\\s*-?\\d+)+)(\\s*)$"
    )
    private val PAGE_HEADER_SCALE_KEYS = hashSetOf("size")
    private val REGION_BLOCK_SCALE_KEYS =
        hashSetOf("xy", "size", "bounds", "split", "pad", "orig", "offset")

    private data class AtlasPageSpan(
        val pageNameLine: String,
        val pageNameIndex: Int,
        val headerStartIndex: Int,
        val headerEndIndexExclusive: Int,
        val endIndexExclusive: Int
    )

    @Throws(IOException::class)
    fun inspectOversizedAtlasPages(
        modJar: File,
        strategy: AtlasOfflineDownscaleStrategy =
            AtlasOfflineDownscaleStrategy.maxEdge(DEFAULT_MAX_OUTPUT_EDGE_PX)
    ): AtlasOfflineDownscaleResult {
        if (!modJar.isFile) {
            throw IOException("Mod jar not found: ${modJar.absolutePath}")
        }

        var scannedAtlasEntries = 0
        var patchedAtlasEntries = 0
        var downscaledPageEntries = 0

        ZipFile(modJar).use { zipFile ->
            val zipIndex = buildZipIndex(zipFile)
            for (entry in zipIndex.atlasEntries) {
                val entryName = entry.name
                scannedAtlasEntries++
                val atlasText = JarFileIoUtils.readEntry(zipFile, entry)
                if (!isLikelySpineAtlas(entryName, zipIndex.entriesByNormalizedName.keys)) {
                    continue
                }
                val pageScales = collectPageScales(
                    zipFile = zipFile,
                    entriesByNormalizedName = zipIndex.entriesByNormalizedName,
                    atlasEntryName = entryName,
                    atlasText = atlasText,
                    strategy = strategy
                )
                if (pageScales.isEmpty()) {
                    continue
                }
                patchedAtlasEntries++
                downscaledPageEntries += pageScales.size
            }
        }

        return AtlasOfflineDownscaleResult(
            scannedAtlasEntries = scannedAtlasEntries,
            patchedAtlasEntries = patchedAtlasEntries,
            downscaledPageEntries = downscaledPageEntries
        )
    }

    @Throws(IOException::class)
    fun patchOversizedAtlasPagesInPlace(
        modJar: File,
        strategy: AtlasOfflineDownscaleStrategy =
            AtlasOfflineDownscaleStrategy.maxEdge(DEFAULT_MAX_OUTPUT_EDGE_PX)
    ): AtlasOfflineDownscaleResult {
        if (!modJar.isFile) {
            throw IOException("Mod jar not found: ${modJar.absolutePath}")
        }

        val replacements: MutableMap<String, ByteArray> = HashMap()
        var scannedAtlasEntries = 0
        var patchedAtlasEntries = 0
        var downscaledPageEntries = 0

        ZipFile(modJar).use { zipFile ->
            val zipIndex = buildZipIndex(zipFile)
            for (entry in zipIndex.atlasEntries) {
                val entryName = entry.name
                scannedAtlasEntries++
                val atlasText = JarFileIoUtils.readEntry(zipFile, entry)
                if (!isLikelySpineAtlas(entryName, zipIndex.entriesByNormalizedName.keys)) {
                    continue
                }
                val plan = buildPatchPlan(
                    zipFile = zipFile,
                    entriesByNormalizedName = zipIndex.entriesByNormalizedName,
                    atlasEntryName = entryName,
                    atlasText = atlasText,
                    strategy = strategy
                ) ?: continue
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

    private data class ZipIndex(
        val entriesByNormalizedName: Map<String, ZipEntry>,
        val atlasEntries: List<ZipEntry>
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

    internal fun isLikelySpineAtlas(atlasEntryName: String, entryNames: Collection<String>): Boolean {
        val normalizedAtlasEntryName = normalizeZipEntryName(atlasEntryName)
        val suffixIndex = normalizedAtlasEntryName.lastIndexOf(".atlas")
        if (suffixIndex <= 0) {
            return false
        }
        val stem = normalizedAtlasEntryName.substring(0, suffixIndex)
        val normalizedEntryNames = if (entryNames is Set<String>) {
            entryNames
        } else {
            entryNames.mapTo(HashSet()) { normalizeZipEntryName(it) }
        }
        return normalizedEntryNames.contains(stem + ".json") ||
            normalizedEntryNames.contains(stem + ".skel") ||
            normalizedEntryNames.contains(stem + ".skel.txt")
    }

    private fun buildPatchPlan(
        zipFile: ZipFile,
        entriesByNormalizedName: Map<String, ZipEntry>,
        atlasEntryName: String,
        atlasText: String,
        strategy: AtlasOfflineDownscaleStrategy
    ): PatchPlan? {
        val pageScales = collectPageScales(
            zipFile = zipFile,
            entriesByNormalizedName = entriesByNormalizedName,
            atlasEntryName = atlasEntryName,
            atlasText = atlasText,
            strategy = strategy
        )
        if (pageScales.isEmpty()) {
            return null
        }

        val pageTransforms = LinkedHashMap<String, PageTransform>()
        val normalizedText = atlasText.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalizedText.split('\n')
        var index = 0

        while (true) {
            val page = findNextAtlasPageSpan(lines, index) ?: break
            index = page.endIndexExclusive
            val resolvedEntryName = resolveAtlasPageEntryName(atlasEntryName, page.pageNameLine.trim())
            if (!pageScales.containsKey(resolvedEntryName.lowercase(Locale.ROOT))) {
                continue
            }
            val transform = createPageTransform(
                zipFile,
                entriesByNormalizedName,
                resolvedEntryName,
                strategy
            ) ?: continue
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

    private fun collectPageScales(
        zipFile: ZipFile,
        entriesByNormalizedName: Map<String, ZipEntry>,
        atlasEntryName: String,
        atlasText: String,
        strategy: AtlasOfflineDownscaleStrategy
    ): Map<String, Float> {
        val pageScales = LinkedHashMap<String, Float>()
        val normalizedText = atlasText.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalizedText.split('\n')
        var index = 0

        while (true) {
            val page = findNextAtlasPageSpan(lines, index) ?: break
            index = page.endIndexExclusive
            val resolvedEntryName = resolveAtlasPageEntryName(atlasEntryName, page.pageNameLine.trim())
            val pageScale = inspectPageScale(
                zipFile,
                entriesByNormalizedName,
                resolvedEntryName,
                strategy
            )
                ?: continue
            pageScales[resolvedEntryName.lowercase(Locale.ROOT)] = pageScale
        }

        return pageScales
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
                output.add(scalePageHeaderLine(lines[index], pageScale))
                index++
            }

            while (index < page.endIndexExclusive) {
                val line = lines[index]
                if (isIndentedAtlasLine(line)) {
                    output.add(scaleRegionLine(line, pageScale))
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

    private fun scalePageHeaderLine(line: String, pageScale: Float?): String {
        return scaleAtlasPropertyLine(line, pageScale, PAGE_HEADER_SCALE_KEYS)
    }

    private fun scaleRegionLine(line: String, pageScale: Float?): String {
        return scaleAtlasPropertyLine(line, pageScale, REGION_BLOCK_SCALE_KEYS)
    }

    private fun scaleAtlasPropertyLine(
        line: String,
        pageScale: Float?,
        allowedKeys: Set<String>
    ): String {
        val key = extractAtlasPropertyKey(line) ?: return line
        if (!allowedKeys.contains(key)) return line
        return scaleNumericTupleLine(line, pageScale)
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

    private fun extractAtlasPropertyKey(line: String): String? {
        val colonIndex = line.indexOf(':')
        if (colonIndex <= 0) {
            return null
        }
        return line.substring(0, colonIndex).trim().lowercase(Locale.ROOT)
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
        entriesByNormalizedName: Map<String, ZipEntry>,
        entryName: String,
        strategy: AtlasOfflineDownscaleStrategy
    ): PageTransform? {
        val entry = entriesByNormalizedName[normalizeZipEntryName(entryName)] ?: return null
        val imageBytes = JarFileIoUtils.readEntryBytes(zipFile, entry)
        val bounds = decodeBitmapBounds(imageBytes) ?: return null
        val scale = computeDownscaleScale(bounds, strategy) ?: return null

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

    private fun inspectPageScale(
        zipFile: ZipFile,
        entriesByNormalizedName: Map<String, ZipEntry>,
        entryName: String,
        strategy: AtlasOfflineDownscaleStrategy
    ): Float? {
        val entry = entriesByNormalizedName[normalizeZipEntryName(entryName)] ?: return null
        val imageBytes = JarFileIoUtils.readEntryBytes(zipFile, entry)
        val bounds = decodeBitmapBounds(imageBytes) ?: return null
        return computeDownscaleScale(bounds, strategy)
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

    private fun computeDownscaleScale(
        bounds: BitmapBounds,
        strategy: AtlasOfflineDownscaleStrategy
    ): Float? {
        return when (strategy.mode) {
            AtlasOfflineDownscaleMode.PERCENTAGE -> {
                if (bounds.width <= AtlasOfflineDownscaleStrategy.CANDIDATE_PREVIEW_MAX_EDGE_PX &&
                    bounds.height <= AtlasOfflineDownscaleStrategy.CANDIDATE_PREVIEW_MAX_EDGE_PX
                ) {
                    return null
                }
                (strategy.value.toFloat() / 100f).takeIf { it < 1.0f }
            }

            AtlasOfflineDownscaleMode.MAX_EDGE -> {
                if (bounds.width <= strategy.value && bounds.height <= strategy.value) {
                    return null
                }
                val scale = minOf(
                    strategy.value.toFloat() / bounds.width.toFloat(),
                    strategy.value.toFloat() / bounds.height.toFloat()
                )
                scale.takeIf { it < 1.0f }
            }
        }
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

    private fun buildZipIndex(zipFile: ZipFile): ZipIndex {
        val entriesByNormalizedName = LinkedHashMap<String, ZipEntry>()
        val atlasEntries = ArrayList<ZipEntry>()
        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) {
                continue
            }
            val normalizedName = normalizeZipEntryName(entry.name)
            entriesByNormalizedName.putIfAbsent(normalizedName, entry)
            if (normalizedName.endsWith(".atlas")) {
                atlasEntries += entry
            }
        }
        return ZipIndex(
            entriesByNormalizedName = entriesByNormalizedName,
            atlasEntries = atlasEntries
        )
    }

    private fun normalizeZipEntryName(entryName: String): String {
        return entryName.replace('\\', '/').lowercase(Locale.ROOT)
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

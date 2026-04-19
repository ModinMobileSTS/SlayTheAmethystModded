package io.stamethyst.backend.mods

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class JacketNoAnoKoModCompatPatchResult(
    val patchedShaderEntries: Int,
    val removedDesktopVersionDirectives: Int,
    val insertedFragmentPrecisionBlocks: Int
) {
    val hasAnyPatch: Boolean
        get() = patchedShaderEntries > 0
}

internal object JacketNoAnoKoModCompatPatcher {
    private const val SHADER_ROOT = "jacketnoanokomodresources/shaders/"
    private val SHADER_EXTENSIONS = setOf(".fs", ".vs")
    private val FRAGMENT_SHADER_EXTENSIONS = setOf(".fs")
    private val FLOAT_PRECISION_PATTERN =
        Pattern.compile("(?m)^\\s*precision\\s+(?:lowp|mediump|highp)\\s+float\\s*;")
    private val INT_PRECISION_PATTERN =
        Pattern.compile("(?m)^\\s*precision\\s+(?:lowp|mediump|highp)\\s+int\\s*;")

    @Throws(IOException::class)
    fun patchInPlace(modJar: File): JacketNoAnoKoModCompatPatchResult {
        if (!modJar.isFile) {
            throw IOException("Mod jar not found: ${modJar.absolutePath}")
        }

        val replacements = LinkedHashMap<String, ByteArray>()
        var removedDesktopVersionDirectives = 0
        var insertedFragmentPrecisionBlocks = 0

        ZipFile(modJar).use { zipFile ->
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !isTargetShaderEntry(entry.name)) {
                    continue
                }
                val originalSource = JarFileIoUtils.readEntry(zipFile, entry)
                val patchResult = patchShaderSource(entry.name, originalSource) ?: continue
                replacements[entry.name] =
                    patchResult.patchedSource.toByteArray(StandardCharsets.UTF_8)
                removedDesktopVersionDirectives += patchResult.removedDesktopVersionDirectives
                insertedFragmentPrecisionBlocks += patchResult.insertedFragmentPrecisionBlocks
            }
        }

        if (replacements.isNotEmpty()) {
            rewriteJarWithReplacements(modJar, replacements)
        }

        return JacketNoAnoKoModCompatPatchResult(
            patchedShaderEntries = replacements.size,
            removedDesktopVersionDirectives = removedDesktopVersionDirectives,
            insertedFragmentPrecisionBlocks = insertedFragmentPrecisionBlocks
        )
    }

    private data class ShaderPatchResult(
        val patchedSource: String,
        val removedDesktopVersionDirectives: Int,
        val insertedFragmentPrecisionBlocks: Int
    )

    private data class SourceTransformResult(
        val source: String,
        val changed: Boolean
    )

    private fun isTargetShaderEntry(entryName: String): Boolean {
        val normalized = entryName.trim().lowercase(Locale.ROOT)
        if (!normalized.startsWith(SHADER_ROOT)) {
            return false
        }
        return SHADER_EXTENSIONS.any { extension ->
            normalized.endsWith(extension)
        }
    }

    private fun isFragmentShaderEntry(entryName: String): Boolean {
        val normalized = entryName.trim().lowercase(Locale.ROOT)
        return FRAGMENT_SHADER_EXTENSIONS.any { extension ->
            normalized.endsWith(extension)
        }
    }

    private fun patchShaderSource(entryName: String, source: String): ShaderPatchResult? {
        if (source.isEmpty()) {
            return null
        }

        val strippedVersion = stripLeadingDesktopVersionDirective(source)
        val precisionPatched = if (isFragmentShaderEntry(entryName)) {
            ensureFragmentPrecision(strippedVersion.source)
        } else {
            SourceTransformResult(strippedVersion.source, false)
        }

        if (!strippedVersion.changed && !precisionPatched.changed) {
            return null
        }

        return ShaderPatchResult(
            patchedSource = precisionPatched.source,
            removedDesktopVersionDirectives = if (strippedVersion.changed) 1 else 0,
            insertedFragmentPrecisionBlocks = if (precisionPatched.changed) 1 else 0
        )
    }

    private fun stripLeadingDesktopVersionDirective(source: String): SourceTransformResult {
        if (source.isEmpty()) {
            return SourceTransformResult(source, false)
        }

        val versionIndex = findLeadingVersionDirectiveIndex(source)
        if (versionIndex < 0) {
            return SourceTransformResult(source, false)
        }

        val lineEnd = skipLine(source, versionIndex)
        val directiveLine = source.substring(versionIndex, lineEnd).trim()
        if (!isDesktopVersionDirective(directiveLine)) {
            return SourceTransformResult(source, false)
        }

        return SourceTransformResult(
            source = source.removeRange(versionIndex, lineEnd),
            changed = true
        )
    }

    private fun ensureFragmentPrecision(source: String): SourceTransformResult {
        if (source.isEmpty()) {
            return SourceTransformResult(source, false)
        }

        val missingFloatPrecision = !FLOAT_PRECISION_PATTERN.matcher(source).find()
        val missingIntPrecision = !INT_PRECISION_PATTERN.matcher(source).find()
        if (!missingFloatPrecision && !missingIntPrecision) {
            return SourceTransformResult(source, false)
        }

        val lineSeparator = detectLineSeparator(source)
        val insertIndex = findInsertIndex(source)
        val patched = StringBuilder(source.length + 160)
        patched.append(source, 0, insertIndex)
        if (insertIndex > 0) {
            val previous = source[insertIndex - 1]
            if (previous != '\n' && previous != '\r') {
                patched.append(lineSeparator)
            }
        }
        appendPrecisionBlock(
            out = patched,
            lineSeparator = lineSeparator,
            missingFloatPrecision = missingFloatPrecision,
            missingIntPrecision = missingIntPrecision
        )
        patched.append(source, insertIndex, source.length)
        return SourceTransformResult(
            source = patched.toString(),
            changed = true
        )
    }

    private fun appendPrecisionBlock(
        out: StringBuilder,
        lineSeparator: String,
        missingFloatPrecision: Boolean,
        missingIntPrecision: Boolean
    ) {
        out.append("#ifdef GL_ES").append(lineSeparator)
        out.append("#ifdef GL_FRAGMENT_PRECISION_HIGH").append(lineSeparator)
        if (missingFloatPrecision) {
            out.append("precision highp float;").append(lineSeparator)
        }
        if (missingIntPrecision) {
            out.append("precision highp int;").append(lineSeparator)
        }
        out.append("#else").append(lineSeparator)
        if (missingFloatPrecision) {
            out.append("precision mediump float;").append(lineSeparator)
        }
        if (missingIntPrecision) {
            out.append("precision mediump int;").append(lineSeparator)
        }
        out.append("#endif").append(lineSeparator)
        out.append("#endif").append(lineSeparator)
    }

    private fun findLeadingVersionDirectiveIndex(source: String): Int {
        var cursor = 0
        if (source[0] == '\uFEFF') {
            cursor = 1
        }
        val candidate = skipTrivia(source, cursor)
        return if (startsWithDirective(source, candidate, "#version")) {
            candidate
        } else {
            -1
        }
    }

    private fun isDesktopVersionDirective(line: String): Boolean {
        val tokens = line
            .trim()
            .split(Regex("\\s+"))
            .filter { token -> token.isNotBlank() }
        if (tokens.size < 2 || !tokens[0].equals("#version", ignoreCase = true)) {
            return false
        }
        if (tokens.any { token -> token.equals("es", ignoreCase = true) }) {
            return false
        }
        val version = tokens[1].toIntOrNull() ?: return false
        return version >= 110
    }

    private fun findInsertIndex(source: String): Int {
        var cursor = 0
        if (source[0] == '\uFEFF') {
            cursor = 1
        }
        cursor = skipTrivia(source, cursor)
        if (startsWithDirective(source, cursor, "#version")) {
            cursor = skipLine(source, cursor)
        }
        while (true) {
            val next = skipTrivia(source, cursor)
            if (!startsWithDirective(source, next, "#extension")) {
                return next
            }
            cursor = skipLine(source, next)
        }
    }

    private fun skipTrivia(source: String, start: Int): Int {
        var index = start
        while (index < source.length) {
            val current = source[index]
            if (current.isWhitespace()) {
                index++
                continue
            }
            if (current == '/' && index + 1 < source.length) {
                val next = source[index + 1]
                if (next == '/') {
                    index = skipLine(source, index)
                    continue
                }
                if (next == '*') {
                    val end = source.indexOf("*/", index + 2)
                    if (end < 0) {
                        return source.length
                    }
                    index = end + 2
                    continue
                }
            }
            break
        }
        return index
    }

    private fun skipLine(source: String, start: Int): Int {
        var index = start
        while (index < source.length) {
            when (val current = source[index++]) {
                '\n' -> return index
                '\r' -> {
                    if (index < source.length && source[index] == '\n') {
                        index++
                    }
                    return index
                }
            }
        }
        return source.length
    }

    private fun startsWithDirective(source: String, index: Int, directive: String): Boolean {
        if (index < 0 || index + directive.length > source.length) {
            return false
        }
        if (!source.regionMatches(index, directive, 0, directive.length, ignoreCase = false)) {
            return false
        }
        val nextIndex = index + directive.length
        return nextIndex >= source.length || source[nextIndex].isWhitespace()
    }

    private fun detectLineSeparator(source: String): String {
        val newlineIndex = source.indexOf('\n')
        return if (newlineIndex > 0 && source[newlineIndex - 1] == '\r') {
            "\r\n"
        } else {
            "\n"
        }
    }

    @Throws(IOException::class)
    private fun rewriteJarWithReplacements(modJar: File, replacements: Map<String, ByteArray>) {
        val tempJar = File(modJar.absolutePath + ".jacketnoanokopatch.tmp")
        val seenNames = LinkedHashSet<String>()
        try {
            ZipFile(modJar).use { zipFile ->
                FileOutputStream(tempJar, false).use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        val entries = zipFile.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val entryName = entry.name
                            if (!seenNames.add(entryName)) {
                                continue
                            }

                            val outEntry = ZipEntry(entryName)
                            if (entry.time > 0) {
                                outEntry.time = entry.time
                            }
                            zipOut.putNextEntry(outEntry)
                            if (!entry.isDirectory) {
                                val replacement = replacements[entryName]
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

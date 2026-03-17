package io.stamethyst.backend.crash

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

data class LatestLogCrashSummary(
    val detail: String,
    val marker: String
)

object LatestLogCrashDetector {
    private const val MAX_READ_BYTES = 256 * 1024
    private val strongCrashMarkers = listOf(
        "Game crashed.",
        "Exception occurred in CardCrawlGame render method!",
        "Exception in thread \"LWJGL Application\""
    )
    private val exceptionHeadlineRegex =
        Regex("^(?:Caused by:\\s*)?[A-Za-z0-9_$.]+(?:Exception|Error)(?::.*)?$")

    @JvmStatic
    fun detect(context: Context): LatestLogCrashSummary? = detect(RuntimePaths.latestLog(context))

    @JvmStatic
    fun detect(logFile: File): LatestLogCrashSummary? {
        if (!logFile.isFile) {
            return null
        }
        val rawText = try {
            readTailText(logFile)
        } catch (_: Throwable) {
            ""
        }
        if (rawText.isBlank()) {
            return null
        }
        val lines = rawText.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) {
            return null
        }
        val markerIndex = lines.indexOfLast { line ->
            strongCrashMarkers.any { marker -> line.contains(marker, ignoreCase = true) }
        }
        if (markerIndex == -1) {
            return null
        }
        val marker = lines[markerIndex].trim()
        val detail = extractDetail(lines, markerIndex).ifBlank { marker }
        return LatestLogCrashSummary(
            detail = detail,
            marker = marker
        )
    }

    @JvmStatic
    fun readLastNonBlankLine(context: Context): String? = readLastNonBlankLine(RuntimePaths.latestLog(context))

    @JvmStatic
    fun readLastNonBlankLine(logFile: File): String? {
        if (!logFile.isFile) {
            return null
        }
        val rawText = try {
            readTailText(logFile)
        } catch (_: Throwable) {
            ""
        }
        if (rawText.isBlank()) {
            return null
        }
        return rawText.lineSequence()
            .map { it.trimEnd() }
            .lastOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun extractDetail(lines: List<String>, markerIndex: Int): String {
        val causeIndex = findCauseSection(lines, markerIndex)
        val primarySearchStart = causeIndex?.plus(1) ?: markerIndex + 1
        val primarySearchEnd = if (causeIndex != null) {
            lines.lastIndex
        } else {
            minOf(lines.lastIndex, markerIndex + 32)
        }
        val exceptionIndex = findExceptionHeadline(lines, primarySearchStart, primarySearchEnd)
            ?: findExceptionHeadline(lines, maxOf(0, markerIndex - 6), minOf(lines.lastIndex, markerIndex + 24))
            ?: return ""
        val exceptionLine = normalizeExceptionLine(lines[exceptionIndex])
        val frameLine = findStackFrame(lines, exceptionIndex + 1, minOf(lines.lastIndex, exceptionIndex + 10))
        return if (frameLine.isNullOrBlank()) {
            exceptionLine
        } else {
            "$exceptionLine at ${normalizeStackFrame(frameLine)}"
        }
    }

    private fun findCauseSection(lines: List<String>, markerIndex: Int): Int? {
        for (index in markerIndex until lines.size) {
            if (lines[index].trim().equals("Cause:", ignoreCase = true)) {
                return index
            }
        }
        return null
    }

    private fun findExceptionHeadline(lines: List<String>, startIndex: Int, endIndex: Int): Int? {
        if (startIndex > endIndex || startIndex !in lines.indices) {
            return null
        }
        for (index in startIndex..endIndex) {
            if (exceptionHeadlineRegex.matches(lines[index].trim())) {
                return index
            }
        }
        return null
    }

    private fun findStackFrame(lines: List<String>, startIndex: Int, endIndex: Int): String? {
        if (startIndex > endIndex || startIndex !in lines.indices) {
            return null
        }
        for (index in startIndex..endIndex) {
            val trimmed = lines[index].trim()
            if (trimmed.startsWith("at ")) {
                return trimmed
            }
        }
        return null
    }

    private fun normalizeExceptionLine(line: String): String {
        return line.trim()
            .removePrefix("Caused by:")
            .trim()
    }

    private fun normalizeStackFrame(line: String): String {
        return line.trim()
            .removePrefix("at ")
            .trim()
            .substringBefore(" ~[")
            .substringBefore(" [")
            .trim()
    }

    private fun readTailText(logFile: File): String {
        RandomAccessFile(logFile, "r").use { raf ->
            val length = raf.length()
            if (length <= 0L) {
                return ""
            }
            val bytesToRead = minOf(length, MAX_READ_BYTES.toLong()).toInt()
            raf.seek(length - bytesToRead)
            val buffer = ByteArray(bytesToRead)
            raf.readFully(buffer)
            return String(buffer, StandardCharsets.UTF_8)
        }
    }
}

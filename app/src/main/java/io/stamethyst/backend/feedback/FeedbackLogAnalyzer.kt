package io.stamethyst.backend.feedback

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

data class FeedbackLogSummary(
    val interestingLines: List<String>,
    val tailLines: List<String>
)

internal object FeedbackLogAnalyzer {
    private const val MAX_READ_BYTES = 256 * 1024
    private const val MAX_TAIL_LINES = 120
    private const val MAX_INTERESTING_LINES = 24
    private val interestingKeywords = listOf(
        "exception",
        "error",
        "fatal",
        "crash",
        "caused by",
        "outofmemory",
        "oom",
        "sigsegv",
        "anr"
    )

    fun summarizeLatestLog(logFile: File): FeedbackLogSummary {
        if (!logFile.isFile) {
            return FeedbackLogSummary(
                interestingLines = emptyList(),
                tailLines = emptyList()
            )
        }

        val rawText = try {
            readTailText(logFile)
        } catch (_: Throwable) {
            ""
        }
        if (rawText.isBlank()) {
            return FeedbackLogSummary(
                interestingLines = emptyList(),
                tailLines = emptyList()
            )
        }

        val tailLines = rawText.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(MAX_TAIL_LINES)
        val interestingLines = tailLines
            .asSequence()
            .filter { line: String ->
                val lowered = line.lowercase()
                interestingKeywords.any { keyword -> lowered.contains(keyword) }
            }
            .distinct()
            .toList()
            .takeLast(MAX_INTERESTING_LINES)

        return FeedbackLogSummary(
            interestingLines = interestingLines,
            tailLines = tailLines
        )
    }

    private fun readTailText(logFile: File): String {
        RandomAccessFile(logFile, "r").use { raf ->
            val length = raf.length()
            if (length <= 0L) {
                return ""
            }
            val bytesToRead = minOf(length, MAX_READ_BYTES.toLong()).toInt()
            val startOffset = length - bytesToRead
            raf.seek(startOffset)
            val buffer = ByteArray(bytesToRead)
            raf.readFully(buffer)
            return String(buffer, StandardCharsets.UTF_8)
        }
    }
}

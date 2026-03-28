package io.stamethyst.backend.crash

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

data class LatestLogCleanShutdownSummary(
    val quitRequestedFromMenu: Boolean,
    val marker: String
)

object LatestLogCleanShutdownDetector {
    private const val MAX_READ_BYTES = 256 * 1024
    private const val SHUTDOWN_MARKER = "Game shutting down..."
    private const val CLEAN_SHUTDOWN_MARKER = "Flushing logs to disk. Clean shutdown successful."
    private const val QUIT_BUTTON_MARKER = "Quit Game button clicked!"

    @JvmStatic
    fun detect(context: Context): LatestLogCleanShutdownSummary? = detect(RuntimePaths.latestLog(context))

    @JvmStatic
    fun shouldSuppressCrashReport(summary: LatestLogCleanShutdownSummary?): Boolean {
        return summary?.quitRequestedFromMenu == true
    }

    @JvmStatic
    fun detect(logFile: File): LatestLogCleanShutdownSummary? {
        if (!logFile.isFile) {
            return null
        }
        if (LatestLogCrashDetector.detect(logFile) != null) {
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

        val cleanShutdownIndex = lines.indexOfLast { line ->
            line.contains(CLEAN_SHUTDOWN_MARKER, ignoreCase = true)
        }
        if (cleanShutdownIndex == -1) {
            return null
        }

        val shutdownIndex = findLastIndexBefore(lines, cleanShutdownIndex) { line ->
            line.contains(SHUTDOWN_MARKER, ignoreCase = true)
        }
        if (shutdownIndex == -1) {
            return null
        }

        val quitButtonIndex = findLastIndexBefore(lines, cleanShutdownIndex) { line ->
            line.contains(QUIT_BUTTON_MARKER, ignoreCase = true)
        }
        return LatestLogCleanShutdownSummary(
            quitRequestedFromMenu = quitButtonIndex in 0..shutdownIndex,
            marker = lines[cleanShutdownIndex].trim()
        )
    }

    private fun findLastIndexBefore(
        lines: List<String>,
        endExclusive: Int,
        predicate: (String) -> Boolean
    ): Int {
        val lastCandidate = minOf(lines.lastIndex, endExclusive - 1)
        for (index in lastCandidate downTo 0) {
            if (predicate(lines[index])) {
                return index
            }
        }
        return -1
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

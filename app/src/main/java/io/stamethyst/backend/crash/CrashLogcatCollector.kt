package io.stamethyst.backend.crash

import android.content.Context
import android.util.Log
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit

object CrashLogcatCollector {
    private const val TAG = "CrashLogcatCollector"
    private const val ALL_LINES = 50000
    private const val CRASH_LINES = 15000
    private const val EVENTS_LINES = 15000
    private const val PID_CONTEXT_RADIUS = 180
    private const val HIGHLIGHT_CONTEXT_RADIUS = 3
    private const val HIGHLIGHT_MAX_LINES_PER_SOURCE = 320
    private const val HIGHLIGHT_TAIL_LINES = 140
    private const val LOGCAT_TIMEOUT_SECONDS = 20L

    @JvmStatic
    fun captureSnapshots(context: Context, summary: ProcessExitSummary?): List<File> {
        val stsRoot = RuntimePaths.stsRoot(context)
        val capturedFiles = ArrayList<File>()
        val all = captureLogcatSnapshot(stsRoot, "logcat_snapshot.txt", "all", ALL_LINES)
        if (all != null) {
            capturedFiles.add(all)
        }
        val crash = captureLogcatSnapshot(stsRoot, "logcat_crash_snapshot.txt", "crash", CRASH_LINES)
        if (crash != null) {
            capturedFiles.add(crash)
        }
        val events = captureLogcatSnapshot(stsRoot, "logcat_events_snapshot.txt", "events", EVENTS_LINES)
        if (events != null) {
            capturedFiles.add(events)
        }
        var pidSnapshot: File? = null
        if (summary != null && summary.pid > 0 && all != null) {
            pidSnapshot = extractPidContextSnapshot(all, summary.pid)
            if (pidSnapshot != null) {
                capturedFiles.add(pidSnapshot)
            }
        }
        val highlights = buildCrashHighlights(stsRoot, summary, all, crash, events, pidSnapshot)
        if (highlights != null) {
            capturedFiles.add(highlights)
        }
        return capturedFiles
    }

    private fun captureLogcatSnapshot(
        stsRoot: File,
        fileName: String,
        bufferName: String,
        lines: Int
    ): File? {
        val outputFile = File(stsRoot, fileName)
        return try {
            val process = ProcessBuilder(
                "logcat",
                "-d",
                "-b",
                bufferName,
                "-v",
                "threadtime",
                "-t",
                lines.toString()
            )
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .start()
            val finished = process.waitFor(LOGCAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
                outputFile.delete()
                return null
            }
            if (process.exitValue() != 0 || !outputFile.isFile || outputFile.length() <= 0L) {
                outputFile.delete()
                return null
            }
            outputFile
        } catch (error: Throwable) {
            outputFile.delete()
            Log.w(TAG, "Failed to capture $fileName from logcat buffer=$bufferName", error)
            null
        }
    }

    private fun extractPidContextSnapshot(source: File, pid: Int): File? {
        val lines = try {
            source.readLines(StandardCharsets.UTF_8)
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to read ${source.absolutePath}", error)
            return null
        }
        if (lines.isEmpty()) {
            return null
        }
        val hitIndexes = ArrayList<Int>()
        val pidToken = " $pid "
        for (index in lines.indices) {
            if (lines[index].contains(pidToken)) {
                hitIndexes.add(index)
            }
        }
        if (hitIndexes.isEmpty()) {
            return null
        }

        val includeIndexes = LinkedHashSet<Int>()
        for (hit in hitIndexes) {
            val start = maxOf(0, hit - PID_CONTEXT_RADIUS)
            val end = minOf(lines.size - 1, hit + PID_CONTEXT_RADIUS)
            for (i in start..end) {
                includeIndexes.add(i)
            }
        }
        val selected = ArrayList<String>(includeIndexes.size + 4)
        selected.add("# pid=$pid")
        selected.add("# source=${source.name}")
        selected.add("")
        for (index in includeIndexes.sorted()) {
            selected.add(lines[index])
        }
        if (selected.size <= 3) {
            return null
        }
        val outputFile = File(source.parentFile, "logcat_pid_${pid}_snapshot.txt")
        return try {
            outputFile.writeText(selected.joinToString("\n"), StandardCharsets.UTF_8)
            outputFile
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to write ${outputFile.absolutePath}", error)
            null
        }
    }

    private fun buildCrashHighlights(
        stsRoot: File,
        summary: ProcessExitSummary?,
        allSnapshot: File?,
        crashSnapshot: File?,
        eventsSnapshot: File?,
        pidSnapshot: File?
    ): File? {
        val outputFile = File(stsRoot, "crash_highlights.txt")
        val highlightPatterns = listOf(
            "FATAL EXCEPTION",
            "Fatal signal",
            "Exception in thread",
            "Process io.stamethyst has died",
            "runtime.cc:",
            "SIGABRT",
            "SIGSEGV",
            "SIGBUS",
            "SIGILL",
            "SIGFPE",
            "OutOfMemoryError",
            "Native crash",
            "java.lang.",
            "Caused by:",
            "Abort message:",
            "backtrace:",
            "tombstoned",
            "am_crash",
            "am_native_crash",
            "am_anr",
            "forced jvm crash for diagnostics verification",
            "Forced crash requested via amethyst.debug.force_jvm_crash",
            "REASON_CRASH",
            "REASON_CRASH_NATIVE",
            "REASON_SIGNALED"
        )
        val lines = ArrayList<String>(1024)
        lines.add("time=${CrashEventLogStore.nowString()}")
        if (summary != null) {
            lines.add("exit_reason=${summary.reasonName}")
            lines.add("exit_status=${summary.status}")
            lines.add("pid=${summary.pid}")
            lines.add("timestamp=${summary.timestamp}")
            if (summary.description.isNotBlank()) {
                lines.add("description=${summary.description}")
            }
        }
        lines.add("")

        val added = LinkedHashSet<String>()
        collectHighlightsFromSnapshot(pidSnapshot, "pid", summary, highlightPatterns, added)
        collectHighlightsFromSnapshot(crashSnapshot, "crash", summary, highlightPatterns, added)
        collectHighlightsFromSnapshot(allSnapshot, "all", summary, highlightPatterns, added)
        collectHighlightsFromSnapshot(eventsSnapshot, "events", summary, highlightPatterns, added)

        if (added.isNotEmpty()) {
            lines.addAll(added)
        } else {
            lines.add("# no direct crash pattern match found; appended recent tails")
            lines.add("")
            appendTailSnapshot(pidSnapshot, "pid", HIGHLIGHT_TAIL_LINES, lines)
            appendTailSnapshot(crashSnapshot, "crash", HIGHLIGHT_TAIL_LINES, lines)
            appendTailSnapshot(allSnapshot, "all", HIGHLIGHT_TAIL_LINES, lines)
            appendTailSnapshot(eventsSnapshot, "events", HIGHLIGHT_TAIL_LINES / 2, lines)
            if (lines.size <= 2) {
                outputFile.delete()
                return null
            }
        }
        return try {
            outputFile.writeText(lines.joinToString("\n"), StandardCharsets.UTF_8)
            outputFile
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to write ${outputFile.absolutePath}", error)
            null
        }
    }

    private fun collectHighlightsFromSnapshot(
        source: File?,
        sourceTag: String,
        summary: ProcessExitSummary?,
        highlightPatterns: List<String>,
        sink: MutableSet<String>
    ) {
        val rawLines = readSnapshotLines(source) ?: return
        if (rawLines.isEmpty()) {
            return
        }

        val summaryPid = summary?.pid ?: -1
        val hitIndexes = ArrayList<Int>()
        for (index in rawLines.indices) {
            val line = rawLines[index]
            if (line.isBlank()) {
                continue
            }
            val patternHit = highlightPatterns.any { pattern ->
                line.contains(pattern, ignoreCase = true)
            }
            val pidHit = summaryPid > 0 && (
                line.contains(" $summaryPid ") ||
                    line.contains("pid=$summaryPid", ignoreCase = true) ||
                    line.contains("pid: $summaryPid", ignoreCase = true)
                )
            if (patternHit || pidHit) {
                hitIndexes.add(index)
            }
        }
        if (hitIndexes.isEmpty()) {
            return
        }

        val includeIndexes = LinkedHashSet<Int>()
        for (hit in hitIndexes) {
            val start = maxOf(0, hit - HIGHLIGHT_CONTEXT_RADIUS)
            val end = minOf(rawLines.size - 1, hit + HIGHLIGHT_CONTEXT_RADIUS)
            for (i in start..end) {
                includeIndexes.add(i)
            }
            if (includeIndexes.size >= HIGHLIGHT_MAX_LINES_PER_SOURCE) {
                break
            }
        }
        var appended = 0
        for (index in includeIndexes.sorted()) {
            val line = rawLines[index].trimEnd()
            if (line.isEmpty()) {
                continue
            }
            sink.add("[$sourceTag:${index + 1}] $line")
            appended++
            if (appended >= HIGHLIGHT_MAX_LINES_PER_SOURCE) {
                break
            }
        }
    }

    private fun appendTailSnapshot(
        source: File?,
        sourceTag: String,
        maxLines: Int,
        sink: MutableList<String>
    ) {
        val rawLines = readSnapshotLines(source) ?: return
        if (rawLines.isEmpty()) {
            return
        }
        val start = maxOf(0, rawLines.size - maxLines)
        sink.add("[$sourceTag:tail]")
        for (index in start until rawLines.size) {
            val line = rawLines[index].trimEnd()
            if (line.isEmpty()) {
                continue
            }
            sink.add("[$sourceTag:${index + 1}] $line")
        }
        sink.add("")
    }

    private fun readSnapshotLines(source: File?): List<String>? {
        if (source == null || !source.isFile || source.length() <= 0L) {
            return null
        }
        return try {
            source.readLines(StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }
}

package io.stamethyst.backend.crash

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.nio.charset.StandardCharsets

object SignalCrashDumpReader {
    private const val MAX_SUMMARY_LINES = 10
    private const val MAX_SUMMARY_CHARS = 1200

    @JvmStatic
    fun read(context: Context): String? = read(RuntimePaths.jvmSignalDump(context))

    @JvmStatic
    fun read(file: File): String? {
        if (!file.isFile || file.length() <= 0L) {
            return null
        }
        val text = try {
            file.readText(StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            return null
        }
        return text.trim().takeIf { it.isNotEmpty() }
    }

    @JvmStatic
    fun readSummary(context: Context): String? = readSummary(RuntimePaths.jvmSignalDump(context))

    @JvmStatic
    fun readSummary(file: File): String? {
        val content = read(file) ?: return null
        val normalized = content
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .take(MAX_SUMMARY_LINES)
            .joinToString("\n")
            .trim()
        if (normalized.isEmpty()) {
            return null
        }
        return if (normalized.length <= MAX_SUMMARY_CHARS) {
            normalized
        } else {
            normalized.take(MAX_SUMMARY_CHARS).trimEnd() + "..."
        }
    }
}

package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

object ExpectedGameExitNotice {
    private const val VALID_WINDOW_MS = 60_000L

    @JvmStatic
    fun markExpectedGameExit(context: Context, source: String) {
        val markerFile = RuntimePaths.expectedGameExitMarker(context)
        val safeSource = source
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(64)
            .ifBlank { "unknown" }
        write(markerFile, "timestampMs=${System.currentTimeMillis()}\nsource=$safeSource\n")
    }

    @JvmStatic
    fun isExpectedGameExitRecent(context: Context, launchStartedAtMs: Long): Boolean {
        return read(RuntimePaths.expectedGameExitMarker(context), launchStartedAtMs) != null
    }

    @JvmStatic
    fun consumeExpectedGameExitIfRecent(context: Context, launchStartedAtMs: Long): Boolean {
        val markerFile = RuntimePaths.expectedGameExitMarker(context)
        val isRecent = read(markerFile, launchStartedAtMs) != null
        clear(markerFile)
        return isRecent
    }

    @JvmStatic
    fun clearExpectedGameExit(context: Context) {
        clear(RuntimePaths.expectedGameExitMarker(context))
    }

    internal fun parseMarkerTimestamp(text: String?): Long? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return null
        }
        trimmed.toLongOrNull()?.let { return it }
        trimmed.lineSequence().forEach { line ->
            val separatorIndex = line.indexOf('=')
            if (separatorIndex <= 0) {
                return@forEach
            }
            val key = line.substring(0, separatorIndex).trim().lowercase(Locale.ROOT)
            if (key == "timestampms" || key == "timestamp") {
                return line.substring(separatorIndex + 1).trim().toLongOrNull()
            }
        }
        return null
    }

    private fun read(markerFile: File, launchStartedAtMs: Long): Long? {
        if (!markerFile.isFile) {
            return null
        }
        val timestampMs = try {
            parseMarkerTimestamp(markerFile.readText(StandardCharsets.UTF_8))
                ?: markerFile.lastModified().takeIf { it > 0L }
        } catch (_: Throwable) {
            markerFile.lastModified().takeIf { it > 0L }
        } ?: return null
        if (timestampMs < launchStartedAtMs) {
            return null
        }
        val ageMs = System.currentTimeMillis() - timestampMs
        return timestampMs.takeIf { ageMs in 0L..VALID_WINDOW_MS }
    }

    private fun clear(markerFile: File) {
        try {
            if (markerFile.exists()) {
                markerFile.delete()
            }
        } catch (_: Throwable) {
        }
    }

    private fun write(markerFile: File, content: String) {
        try {
            val parent = markerFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return
            }
            markerFile.writeText(content, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
        }
    }
}

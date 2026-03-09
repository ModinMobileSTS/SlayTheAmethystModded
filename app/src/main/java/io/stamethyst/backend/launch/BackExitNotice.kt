package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.nio.charset.StandardCharsets

object BackExitNotice {
    private const val EXPECTED_BACK_EXIT_VALID_WINDOW_MS = 30_000L
    private const val EXPECTED_BACK_EXIT_MARKER_FILE_NAME = ".expected_back_exit_marker"
    private const val EXPECTED_BACK_EXIT_RESTART_MARKER_FILE_NAME =
        ".expected_back_exit_restart_marker"
    @Volatile
    private var launcherReturnHandledInProcess = false

    @JvmStatic
    fun markExpectedBackExit(context: Context) {
        val now = System.currentTimeMillis()
        launcherReturnHandledInProcess = false
        LauncherConfig.markExpectedBackExit(context)
        writeMarker(expectedBackExitMarker(context), now)
        clearMarker(expectedBackExitRestartMarker(context))
    }

    @JvmStatic
    fun isExpectedBackExitRecent(context: Context): Boolean {
        return isMarkerRecent(expectedBackExitMarker(context)) ||
            LauncherConfig.isExpectedBackExitRecent(context)
    }

    @JvmStatic
    fun markExpectedBackExitRestartScheduled(context: Context) {
        val now = System.currentTimeMillis()
        LauncherConfig.markExpectedBackExitRestartScheduled(context)
        writeMarker(expectedBackExitRestartMarker(context), now)
    }

    @JvmStatic
    fun isExpectedBackExitRestartScheduledRecent(context: Context): Boolean {
        return isMarkerRecent(expectedBackExitRestartMarker(context)) ||
            LauncherConfig.isExpectedBackExitRestartScheduledRecent(context)
    }

    @JvmStatic
    fun consumeExpectedBackExitIfRecent(context: Context): Boolean {
        val isRecent = isMarkerRecent(expectedBackExitMarker(context)) ||
            LauncherConfig.isExpectedBackExitRecent(context)
        LauncherConfig.consumeExpectedBackExitIfRecent(context)
        clearMarker(expectedBackExitMarker(context))
        clearMarker(expectedBackExitRestartMarker(context))
        return isRecent
    }

    @JvmStatic
    fun markLauncherReturnHandledInProcess() {
        launcherReturnHandledInProcess = true
    }

    @JvmStatic
    fun isLauncherReturnHandledInProcess(): Boolean {
        return launcherReturnHandledInProcess
    }

    private fun expectedBackExitMarker(context: Context): File {
        return File(RuntimePaths.componentRoot(context), EXPECTED_BACK_EXIT_MARKER_FILE_NAME)
    }

    private fun expectedBackExitRestartMarker(context: Context): File {
        return File(
            RuntimePaths.componentRoot(context),
            EXPECTED_BACK_EXIT_RESTART_MARKER_FILE_NAME
        )
    }

    private fun isMarkerRecent(file: File): Boolean {
        val markerTimestampMs = readMarkerTimestamp(file)
        if (markerTimestampMs <= 0L) {
            return false
        }
        val deltaMs = System.currentTimeMillis() - markerTimestampMs
        return deltaMs >= 0L && deltaMs <= EXPECTED_BACK_EXIT_VALID_WINDOW_MS
    }

    private fun readMarkerTimestamp(file: File): Long {
        if (!file.isFile) {
            return -1L
        }
        return try {
            file.readText(StandardCharsets.UTF_8).trim().toLongOrNull()
                ?: file.lastModified().takeIf { it > 0L }
                ?: -1L
        } catch (_: Throwable) {
            file.lastModified().takeIf { it > 0L } ?: -1L
        }
    }

    private fun writeMarker(file: File, timestampMs: Long) {
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return
            }
            file.writeText(timestampMs.toString(), StandardCharsets.UTF_8)
        } catch (_: Throwable) {
        }
    }

    private fun clearMarker(file: File) {
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Throwable) {
        }
    }
}

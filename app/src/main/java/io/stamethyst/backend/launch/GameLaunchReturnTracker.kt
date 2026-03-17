package io.stamethyst.backend.launch

import android.app.ActivityManager
import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.nio.charset.StandardCharsets

internal object GameLaunchReturnTracker {
    private const val PENDING_GAME_LAUNCH_MARKER_FILE_NAME = ".pending_game_launch"
    private const val GAME_PROCESS_SUFFIX = ":game"

    fun markGameLaunchStarted(context: Context, startedAtMs: Long = System.currentTimeMillis()): Long {
        writeMarker(pendingGameLaunchMarker(context), startedAtMs)
        return startedAtMs
    }

    fun readPendingGameLaunchStartedAt(context: Context): Long? {
        val markerFile = pendingGameLaunchMarker(context)
        if (!markerFile.isFile) {
            return null
        }
        return try {
            markerFile.readText(StandardCharsets.UTF_8).trim().toLongOrNull()
        } catch (_: Throwable) {
            null
        }?.takeIf { it > 0L }
    }

    fun clearPendingGameLaunch(context: Context) {
        clearMarker(pendingGameLaunchMarker(context))
    }

    fun isGameProcessRunning(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val targetProcessName = context.packageName + GAME_PROCESS_SUFFIX
        return try {
            activityManager.runningAppProcesses
                ?.any { process -> process.processName == targetProcessName }
                ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun pendingGameLaunchMarker(context: Context): File {
        return File(RuntimePaths.componentRoot(context), PENDING_GAME_LAUNCH_MARKER_FILE_NAME)
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

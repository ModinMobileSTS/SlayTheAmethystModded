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
                ?.any { process ->
                    process.processName == targetProcessName &&
                        isTrackedGameProcessAlive(process.pid, process.importance)
                }
                ?: false
        } catch (_: Throwable) {
            false
        }
    }

    internal fun isTrackedGameProcessAlive(pid: Int, importance: Int): Boolean {
        if (pid <= 0) {
            return false
        }
        // Ignore cached/empty processes that Android keeps around after the game
        // activity has already finished, otherwise the launcher stays stuck in
        // "game is still running" state.
        return importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
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

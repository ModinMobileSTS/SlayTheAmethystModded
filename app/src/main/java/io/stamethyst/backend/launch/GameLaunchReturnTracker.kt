package io.stamethyst.backend.launch

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.nio.charset.StandardCharsets

internal object GameLaunchReturnTracker {
    private const val PENDING_GAME_LAUNCH_MARKER_FILE_NAME = ".pending_game_launch"
    private const val GAME_PROCESS_SUFFIX = ":game"
    private const val PROCESS_EXIT_WAIT_TIMEOUT_MS = 2_500L
    private const val PROCESS_EXIT_POLL_INTERVAL_MS = 80L

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

    fun isGameProcessRunning(context: Context, includeCached: Boolean = false): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return try {
            activityManager.runningAppProcesses
                ?.any { process ->
                    isTrackedGameProcess(
                        processName = process.processName,
                        packageName = context.packageName,
                        pid = process.pid,
                        importance = process.importance,
                        includeCached = includeCached
                    )
                }
                ?: false
        } catch (_: Throwable) {
            false
        }
    }

    fun terminateTrackedGameProcess(context: Context, includeCached: Boolean = false): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val targetPids = try {
            activityManager.runningAppProcesses
                ?.asSequence()
                ?.filter { process ->
                    isTrackedGameProcess(
                        processName = process.processName,
                        packageName = context.packageName,
                        pid = process.pid,
                        importance = process.importance,
                        includeCached = includeCached
                    )
                }
                ?.map { it.pid }
                ?.distinct()
                ?.toList()
                .orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        if (targetPids.isEmpty()) {
            return false
        }
        var killed = false
        targetPids.forEach { pid ->
            runCatching {
                android.os.Process.killProcess(pid)
                killed = true
            }
        }
        return killed
    }

    fun terminateTrackedGameProcessAndWait(
        context: Context,
        timeoutMs: Long = PROCESS_EXIT_WAIT_TIMEOUT_MS,
        pollIntervalMs: Long = PROCESS_EXIT_POLL_INTERVAL_MS
    ): Boolean {
        val safePollIntervalMs = pollIntervalMs.coerceAtLeast(20L)
        val deadlineMs = SystemClock.uptimeMillis() + timeoutMs.coerceAtLeast(safePollIntervalMs)
        do {
            terminateTrackedGameProcess(context, includeCached = true)
            if (!isGameProcessRunning(context, includeCached = true)) {
                return true
            }
            if (SystemClock.uptimeMillis() >= deadlineMs) {
                break
            }
            try {
                Thread.sleep(safePollIntervalMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        } while (true)
        return !isGameProcessRunning(context, includeCached = true)
    }

    internal fun isTrackedGameProcess(
        processName: String?,
        packageName: String,
        pid: Int,
        importance: Int,
        includeCached: Boolean = false
    ): Boolean {
        return processName == packageName + GAME_PROCESS_SUFFIX &&
            isTrackedGameProcessAlive(pid, importance, includeCached)
    }

    internal fun isTrackedGameProcessAlive(
        pid: Int,
        importance: Int,
        includeCached: Boolean = false
    ): Boolean {
        if (pid <= 0) {
            return false
        }
        // Ignore cached/empty processes that Android keeps around after the game
        // activity has already finished, otherwise the launcher stays stuck in
        // "game is still running" state.
        return includeCached || importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
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

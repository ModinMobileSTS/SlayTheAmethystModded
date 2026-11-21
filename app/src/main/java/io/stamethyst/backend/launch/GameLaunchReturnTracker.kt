package io.stamethyst.backend.launch

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import io.stamethyst.config.RuntimePaths
import io.stamethyst.backend.steamcloud.SteamCloudGameReturn
import io.stamethyst.backend.steamcloud.SteamCloudLaunchFence
import io.stamethyst.backend.steamcloud.SteamCloudAtomicFileStore
import java.io.File
import java.nio.charset.StandardCharsets

internal object GameLaunchReturnTracker {
    private const val PENDING_GAME_LAUNCH_MARKER_FILE_NAME = ".pending_game_launch"
    private const val GAME_PROCESS_SUFFIX = ":game"
    private const val PROCESS_EXIT_WAIT_TIMEOUT_MS = 2_500L
    private const val PROCESS_EXIT_POLL_INTERVAL_MS = 80L
    private const val FRESH_LAUNCH_HANDOFF_GRACE_MS = 10_000L

    @Synchronized fun markGameLaunchStarted(context: Context, startedAtMs: Long = System.currentTimeMillis(), fence: String? = null): Long {
        SteamCloudAtomicFileStore.writeTextWithoutBackup(pendingGameLaunchMarker(context),
            "$startedAtMs\n${fence.orEmpty()}\n${SteamCloudGameReturn.launchScope(context).orEmpty()}")
        return startedAtMs
    }

    fun readPendingGameLaunchStartedAt(context: Context): Long? {
        val markerFile = pendingGameLaunchMarker(context)
        if (!markerFile.isFile) {
            return null
        }
        return try {
            markerFile.readLines(StandardCharsets.UTF_8).firstOrNull()?.trim()?.toLongOrNull()
        } catch (_: Throwable) {
            null
        }?.takeIf { it > 0L }
    }

    fun pendingFence(context: Context): String? = pendingGameLaunchMarker(context).takeIf { it.isFile }
        ?.readLines(StandardCharsets.UTF_8)?.getOrNull(1)?.takeIf { it.isNotEmpty() }

    @Synchronized fun cleanReturn(context: Context, launch: Long): SteamCloudGameReturn.Pending? {
        val lines = pendingGameLaunchMarker(context).takeIf { it.isFile }?.readLines() ?: return null
        if (lines.firstOrNull()?.toLongOrNull() != launch) return null
        SteamCloudLaunchFence.release(context, lines.getOrNull(1))
        val scope = lines.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: return null
        return SteamCloudGameReturn.Pending(launch, scope).also { SteamCloudGameReturn.record(context, it) }
    }

    @Synchronized fun clearPendingGameLaunch(context: Context, launch: Long?) {
        if (launch == null || readPendingGameLaunchStartedAt(context) != launch) return
        SteamCloudLaunchFence.release(context, pendingFence(context))
        clearMarker(pendingGameLaunchMarker(context))
    }

    internal fun isWithinFreshLaunchHandoffWindow(
        startedAtMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (startedAtMs <= 0L) {
            return false
        }
        val elapsedMs = nowMs - startedAtMs
        return elapsedMs in 0 until FRESH_LAUNCH_HANDOFF_GRACE_MS
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

    private fun clearMarker(file: File) {
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Throwable) {
        }
    }
}

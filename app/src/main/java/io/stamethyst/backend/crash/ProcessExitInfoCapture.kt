package io.stamethyst.backend.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import io.stamethyst.config.RuntimePaths
import java.nio.charset.StandardCharsets

object ProcessExitInfoCapture {
    private const val GAME_PROCESS_SUFFIX = ":game"

    @JvmStatic
    fun captureLatestProcessExitInfo(context: Context): ProcessExitSummary? {
        return captureLatestProcessExitInfo(context, launchedAfterTimestampMs = 0L)
    }

    @JvmStatic
    fun captureLatestProcessExitInfo(
        context: Context,
        launchedAfterTimestampMs: Long
    ): ProcessExitSummary? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        return captureLatestProcessExitInfoApi30(context, launchedAfterTimestampMs)
    }

    @JvmStatic
    fun peekLatestInterestingProcessExitInfo(context: Context): ProcessExitSummary? {
        return peekLatestInterestingProcessExitInfo(context, launchedAfterTimestampMs = 0L)
    }

    @JvmStatic
    fun peekLatestInterestingProcessExitInfo(
        context: Context,
        launchedAfterTimestampMs: Long
    ): ProcessExitSummary? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        return peekLatestInterestingProcessExitInfoApi30(context, launchedAfterTimestampMs)
    }

    @JvmStatic
    fun markLatestInterestingProcessExitInfoHandled(context: Context) {
        markLatestInterestingProcessExitInfoHandled(context, launchedAfterTimestampMs = 0L)
    }

    @JvmStatic
    fun markLatestInterestingProcessExitInfoHandled(
        context: Context,
        launchedAfterTimestampMs: Long
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        markLatestInterestingProcessExitInfoHandledApi30(context, launchedAfterTimestampMs)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureLatestProcessExitInfoApi30(
        context: Context,
        launchedAfterTimestampMs: Long
    ): ProcessExitSummary? {
        val latest = resolveLatestInterestingExitInfo(context, launchedAfterTimestampMs) ?: return null

        val markerValue = buildExitMarker(latest)
        if (!isNewExitMarker(context, markerValue)) {
            return null
        }
        persistExitMarker(context, markerValue)

        return buildSummary(latest)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun peekLatestInterestingProcessExitInfoApi30(
        context: Context,
        launchedAfterTimestampMs: Long
    ): ProcessExitSummary? {
        val latest = resolveLatestInterestingExitInfo(context, launchedAfterTimestampMs) ?: return null
        return buildSummary(latest)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun markLatestInterestingProcessExitInfoHandledApi30(
        context: Context,
        launchedAfterTimestampMs: Long
    ) {
        val latest = resolveLatestInterestingExitInfo(context, launchedAfterTimestampMs) ?: return
        persistExitMarker(context, buildExitMarker(latest))
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun buildSummary(latest: ApplicationExitInfo): ProcessExitSummary {
        val processName = latest.processName.trim()
        return ProcessExitSummary(
            pid = latest.pid,
            processName = processName,
            reason = latest.reason,
            reasonName = reasonName(latest.reason),
            status = latest.status,
            timestamp = latest.timestamp,
            description = latest.description?.trim().orEmpty(),
            isSignal = latest.reason == ApplicationExitInfo.REASON_SIGNALED
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun resolveLatestInterestingExitInfo(
        context: Context,
        launchedAfterTimestampMs: Long
    ): ApplicationExitInfo? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val reasons = try {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 24)
        } catch (_: Throwable) {
            return null
        }
        if (reasons.isNullOrEmpty()) {
            return null
        }
        val gameProcessName = context.packageName + GAME_PROCESS_SUFFIX
        return reasons
            .asSequence()
            .sortedByDescending { it.timestamp }
            .firstOrNull { exitInfo ->
                isInterestingExitReason(exitInfo) &&
                    exitInfo.timestamp >= launchedAfterTimestampMs &&
                    exitInfo.processName == gameProcessName
            }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun buildExitMarker(exitInfo: ApplicationExitInfo): String {
        return buildString(96) {
            append(exitInfo.pid).append(':')
            append(exitInfo.timestamp).append(':')
            append(exitInfo.reason).append(':')
            append(exitInfo.status)
        }
    }

    private fun isNewExitMarker(context: Context, markerValue: String): Boolean {
        return try {
            val markerFile = RuntimePaths.lastExitMarker(context)
            if (!markerFile.isFile) {
                return true
            }
            markerFile.readText(StandardCharsets.UTF_8).trim() != markerValue
        } catch (_: Throwable) {
            true
        }
    }

    private fun persistExitMarker(context: Context, markerValue: String) {
        try {
            val markerFile = RuntimePaths.lastExitMarker(context)
            val parent = markerFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return
            }
            markerFile.writeText(markerValue, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String {
        return when (reason) {
            ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
            ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "REASON_DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF"
            ApplicationExitInfo.REASON_FREEZER -> "REASON_FREEZER"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "REASON_INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY"
            ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "REASON_PACKAGE_STATE_CHANGE"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "REASON_PACKAGE_UPDATED"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "REASON_PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED"
            ApplicationExitInfo.REASON_UNKNOWN -> "REASON_UNKNOWN"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "REASON_USER_STOPPED"
            else -> "REASON_$reason"
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun isInterestingExitReason(exitInfo: ApplicationExitInfo): Boolean {
        return when (exitInfo.reason) {
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_SIGNALED -> true
            else -> false
        }
    }
}

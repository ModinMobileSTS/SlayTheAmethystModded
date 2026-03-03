package io.stamethyst.backend.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import io.stamethyst.config.RuntimePaths
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

object ProcessExitInfoCapture {
    private const val TAG = "ProcessExitInfoCapture"
    private const val EXIT_TRACE_MAX_BYTES = 1024 * 1024

    @JvmStatic
    fun captureLatestProcessExitInfo(context: Context, stage: String): ProcessExitSummary? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val reasons = try {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 24)
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to query process exit history", error)
            return null
        }
        if (reasons.isNullOrEmpty()) {
            return null
        }
        val latest = reasons
            .asSequence()
            .sortedByDescending { it.timestamp }
            .firstOrNull { isInterestingExitReason(it) } ?: return null

        val markerValue = buildExitMarker(latest)
        if (!isNewExitMarker(context, markerValue)) {
            return null
        }
        persistExitMarker(context, markerValue)

        val reasonName = reasonName(latest.reason)
        val description = latest.description?.trim().orEmpty()
        val infoText = buildExitInfoText(stage, latest)
        CrashEventLogStore.writeFileSafely(RuntimePaths.lastExitInfo(context), infoText)
        CrashEventLogStore.appendRaw(context, infoText + "\n")

        val traceText = readExitTrace(latest)
        if (!traceText.isNullOrBlank()) {
            CrashEventLogStore.writeFileSafely(RuntimePaths.lastExitTrace(context), traceText)
            CrashEventLogStore.appendRaw(
                context,
                buildString {
                    append("time=").append(CrashEventLogStore.nowString()).append('\n')
                    append("stage=").append(stage).append('\n')
                    append("type=process_exit_trace").append('\n')
                    append("trace_file=").append(RuntimePaths.lastExitTrace(context).name).append('\n')
                    append("trace_size=").append(traceText.toByteArray(StandardCharsets.UTF_8).size).append('\n')
                    append('\n')
                }
            )
        }
        return ProcessExitSummary(
            pid = latest.pid,
            reason = latest.reason,
            reasonName = reasonName,
            status = latest.status,
            timestamp = latest.timestamp,
            description = description
        )
    }

    private fun buildExitInfoText(stage: String, exitInfo: ApplicationExitInfo): String {
        val out = StringBuilder(512)
        out.append("time=").append(CrashEventLogStore.nowString()).append('\n')
        out.append("stage=").append(stage).append('\n')
        out.append("type=process_exit_info").append('\n')
        out.append("pid=").append(exitInfo.pid).append('\n')
        out.append("realUid=").append(exitInfo.realUid).append('\n')
        out.append("reason=").append(exitInfo.reason).append('\n')
        out.append("reasonName=").append(reasonName(exitInfo.reason)).append('\n')
        out.append("status=").append(exitInfo.status).append('\n')
        out.append("importance=").append(exitInfo.importance).append('\n')
        out.append("pss=").append(exitInfo.pss).append('\n')
        out.append("rss=").append(exitInfo.rss).append('\n')
        out.append("timestamp=").append(exitInfo.timestamp).append('\n')
        out.append("description=").append(exitInfo.description?.trim().orEmpty()).append('\n')
        val summary = exitInfo.processStateSummary
        if (summary != null && summary.isNotEmpty()) {
            out.append("processStateSummaryHex=").append(summary.toHexString()).append('\n')
        }
        out.append("traceAvailable=").append(exitInfo.traceInputStream != null).append('\n')
        out.append('\n')
        return out.toString()
    }

    private fun readExitTrace(exitInfo: ApplicationExitInfo): String? {
        val input = try {
            exitInfo.traceInputStream
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to open exit trace stream", error)
            null
        } ?: return null
        return try {
            input.use { stream ->
                stream.readUpTo(EXIT_TRACE_MAX_BYTES).toString(StandardCharsets.UTF_8)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to read exit trace stream", error)
            null
        }
    }

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
        CrashEventLogStore.writeFileSafely(RuntimePaths.lastExitMarker(context), markerValue)
    }

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

    private fun isInterestingExitReason(exitInfo: ApplicationExitInfo): Boolean {
        return when (exitInfo.reason) {
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_SIGNALED -> true
            else -> false
        }
    }

    private fun ByteArray.toHexString(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val value = b.toInt() and 0xFF
            out.append(value.toString(16).padStart(2, '0'))
        }
        return out.toString()
    }

    private fun InputStream.readUpTo(maxBytes: Int): ByteArray {
        if (maxBytes <= 0) {
            return ByteArray(0)
        }
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream(minOf(maxBytes, 8192))
        var total = 0
        while (total < maxBytes) {
            val toRead = minOf(buffer.size, maxBytes - total)
            val read = read(buffer, 0, toRead)
            if (read <= 0) {
                break
            }
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }
}

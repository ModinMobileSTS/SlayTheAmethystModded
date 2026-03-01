package io.stamethyst.backend.crash

import android.content.Context
import android.util.Log
import io.stamethyst.backend.core.RuntimePaths
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Arrays
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

object CrashDiagnostics {
    private const val TAG = "CrashDiagnostics"
    private val backgroundCaptureRunning = AtomicBoolean(false)

    @JvmStatic
    fun clear(context: Context) {
        CrashEventLogStore.clear(context)
    }

    @JvmStatic
    fun recordLaunchResult(
        context: Context,
        stage: String,
        code: Int,
        isSignal: Boolean,
        detail: String?
    ) {
        CrashEventLogStore.recordLaunchResult(context, stage, code, isSignal, detail)
    }

    @JvmStatic
    fun recordThrowable(context: Context, stage: String, error: Throwable) {
        CrashEventLogStore.recordThrowable(context, stage, error)
    }

    @JvmStatic
    fun captureLatestProcessExitInfo(context: Context, stage: String): ProcessExitSummary? {
        return ProcessExitInfoCapture.captureLatestProcessExitInfo(context, stage)
    }

    @JvmStatic
    fun scheduleSnapshotCapture(context: Context, summary: ProcessExitSummary?) {
        if (!backgroundCaptureRunning.compareAndSet(false, true)) {
            return
        }
        val appContext = context.applicationContext
        val captureSummary = summary
        Thread(
            {
                try {
                    captureSnapshotsNow(appContext, captureSummary)
                } catch (error: Throwable) {
                    Log.w(TAG, "Background crash snapshot capture failed", error)
                } finally {
                    backgroundCaptureRunning.set(false)
                }
            },
            "CrashSnapshotCapture"
        ).apply {
            isDaemon = true
            start()
        }
    }

    @JvmStatic
    fun captureSnapshotsNow(context: Context, summary: ProcessExitSummary?): List<File> {
        return CrashLogcatCollector.captureSnapshots(context, summary)
    }

    @JvmStatic
    fun collectDebugBundleFiles(context: Context): List<File> {
        val stsRoot = RuntimePaths.stsRoot(context)
        val summary = readLastExitInfoSummary(context)
        captureSnapshotsNow(context, summary)

        val debugFiles = ArrayList<File>()
        addIfExists(debugFiles, RuntimePaths.latestLog(context))
        addIfExists(debugFiles, File(stsRoot, "jvm_output.log"))
        addIfExists(debugFiles, RuntimePaths.bootBridgeEventsFile(context))
        addIfExists(debugFiles, RuntimePaths.lastCrashReport(context))
        addIfExists(debugFiles, RuntimePaths.lastExitInfo(context))
        addIfExists(debugFiles, RuntimePaths.lastExitTrace(context))
        addIfExists(debugFiles, RuntimePaths.lastSignalStack(context))
        addIfExists(debugFiles, RuntimePaths.enabledModsConfig(context))
        addIfExists(debugFiles, File(stsRoot, "logcat_snapshot.txt"))
        addIfExists(debugFiles, File(stsRoot, "logcat_crash_snapshot.txt"))
        addIfExists(debugFiles, File(stsRoot, "logcat_events_snapshot.txt"))
        addIfExists(debugFiles, File(stsRoot, "crash_highlights.txt"))

        if (summary != null && summary.pid > 0) {
            addIfExists(debugFiles, File(stsRoot, "logcat_pid_${summary.pid}_snapshot.txt"))
        }

        val hsErrFiles = stsRoot.listFiles { _, name ->
            name != null && name.startsWith("hs_err_pid") && name.endsWith(".log")
        }
        if (hsErrFiles != null && hsErrFiles.isNotEmpty()) {
            Arrays.sort(hsErrFiles) { a, b -> b.lastModified().compareTo(a.lastModified()) }
            for (hsErr in hsErrFiles) {
                addIfExists(debugFiles, hsErr)
            }
        }
        return debugFiles
    }

    @JvmStatic
    fun expectedDebugFileHint(): String {
        return "latestlog.txt, jvm_output.log, boot_bridge_events.log, hs_err_pid*.log, " +
            "last_crash_report.txt, last_exit_info.txt, last_exit_trace.txt, last_signal_stack.txt, " +
            "logcat_snapshot.txt, logcat_crash_snapshot.txt, logcat_events_snapshot.txt, " +
            "logcat_pid_<pid>_snapshot.txt, crash_highlights.txt"
    }

    private fun readLastExitInfoSummary(context: Context): ProcessExitSummary? {
        val file = RuntimePaths.lastExitInfo(context)
        if (!file.isFile || file.length() <= 0L) {
            return null
        }
        return try {
            val map = LinkedHashMap<String, String>()
            file.readLines(StandardCharsets.UTF_8).forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) {
                    return@forEach
                }
                val split = line.indexOf('=')
                if (split <= 0 || split >= line.length - 1) {
                    return@forEach
                }
                map[line.substring(0, split)] = line.substring(split + 1)
            }
            val pid = map["pid"]?.toIntOrNull() ?: return null
            val reason = map["reason"]?.toIntOrNull() ?: -1
            val status = map["status"]?.toIntOrNull() ?: -1
            val timestamp = map["timestamp"]?.toLongOrNull() ?: 0L
            ProcessExitSummary(
                pid = pid,
                reason = reason,
                reasonName = map["reasonName"].orEmpty(),
                status = status,
                timestamp = timestamp,
                description = map["description"].orEmpty()
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun addIfExists(files: MutableList<File>, file: File?) {
        if (file != null && file.isFile && file.length() > 0L) {
            files.add(file)
        }
    }
}

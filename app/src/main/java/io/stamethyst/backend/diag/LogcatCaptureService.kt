package io.stamethyst.backend.diag

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import io.stamethyst.config.RuntimePaths
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogcatCaptureService : Service() {
    companion object {
        const val ACTION_START_CAPTURE = "io.stamethyst.action.START_LOGCAT_CAPTURE"
        const val ACTION_STOP_CAPTURE = "io.stamethyst.action.STOP_LOGCAT_CAPTURE"
        const val ACTION_STOP_AND_CLEAR_CAPTURE = "io.stamethyst.action.STOP_AND_CLEAR_LOGCAT_CAPTURE"
        const val EXTRA_SESSION_STARTED_AT_MS = "io.stamethyst.extra.SESSION_STARTED_AT_MS"

        private const val LOGCAT_ROTATE_KB = 768L * 1024L
        private const val LOGCAT_ROTATE_FILES = 4
        private const val TRACKED_PROCESS_REFRESH_INTERVAL_MS = 250L
        private const val STALE_PID_GRACE_MS = 750L
        private const val FAILED_SESSION_RESTART_COOLDOWN_MS = 5_000L

        private val LOGCAT_BASE_COMMAND = listOf(
            "logcat",
            "-v", "threadtime",
            "-b", "main",
            "-b", "system",
            "-b", "crash",
            "-b", "events"
        )

        private val SYSTEM_LOGCAT_FILTER_SPECS = listOf(
            "ActivityManager:I",
            "ActivityTaskManager:I",
            "ProcessList:I",
            "lmkd:I",
            "libprocessgroup:I",
            "SurfaceFlinger:I",
            "BLASTBufferQueue:I",
            "BufferQueueProducer:I",
            "WindowManager:I",
            "DEBUG:I",
            "libc:I",
            "AndroidRuntime:I",
            "*:S"
        )

        internal fun buildSystemLogcatCommandForCapture(): List<String> {
            return LOGCAT_BASE_COMMAND + SYSTEM_LOGCAT_FILTER_SPECS
        }

        internal fun buildPidLogcatCommandForCapture(pid: Int): List<String> {
            return LOGCAT_BASE_COMMAND + listOf(
                "--pid",
                pid.toString(),
                "*:I"
            )
        }

        internal fun isTrackedProcessName(processName: String, packageName: String): Boolean {
            return processName == packageName || processName.startsWith("$packageName:")
        }

        internal fun formatTimestamp(timestampMs: Long): String {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(Date(timestampMs))
        }
    }

    @Volatile
    private var captureThread: Thread? = null

    @Volatile
    private var activeCaptureCoordinator: ActiveCaptureCoordinator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val sessionStartedAtMs = intent.getLongExtra(EXTRA_SESSION_STARTED_AT_MS, 0L)
                startCapture(sessionStartedAtMs)
                return START_STICKY
            }

            ACTION_STOP_CAPTURE -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_STOP_AND_CLEAR_CAPTURE -> {
                stopCapture(waitForThread = true)
                clearCaptureFiles()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        stopCapture(waitForThread = false)
        super.onDestroy()
    }

    private fun startCapture(sessionStartedAtMs: Long) {
        stopCapture(waitForThread = true)
        RuntimePaths.ensureBaseDirs(applicationContext)
        clearCaptureFiles()
        val worker = Thread(
            {
                runCapture(sessionStartedAtMs)
            },
            "STS-Logcat"
        )
        captureThread = worker
        worker.start()
    }

    private fun stopCapture() {
        stopCapture(waitForThread = true)
    }

    private fun stopCapture(waitForThread: Boolean) {
        val thread = captureThread
        captureThread = null
        activeCaptureCoordinator?.requestStop()
        thread?.interrupt()
        if (waitForThread && thread != null && thread !== Thread.currentThread()) {
            runCatching {
                thread.join(800L)
            }
        }
    }

    private fun runCapture(sessionStartedAtMs: Long) {
        val appWriter = RollingTextLogWriter(
            baseFile = RuntimePaths.logcatAppCaptureLog(applicationContext),
            maxBytesPerFile = LOGCAT_ROTATE_KB,
            maxFiles = LOGCAT_ROTATE_FILES
        )
        val systemWriter = RollingTextLogWriter(
            baseFile = RuntimePaths.logcatSystemCaptureLog(applicationContext),
            maxBytesPerFile = LOGCAT_ROTATE_KB,
            maxFiles = LOGCAT_ROTATE_FILES
        )
        val coordinator = ActiveCaptureCoordinator(
            trackedProcessSupplier = { resolveTrackedAppProcesses() },
            appWriter = appWriter,
            systemWriter = systemWriter
        )
        activeCaptureCoordinator = coordinator
        try {
            appWriter.appendLine(buildCaptureBanner("app logcat capture started", sessionStartedAtMs))
            systemWriter.appendLine(buildCaptureBanner("system logcat capture started", sessionStartedAtMs))
            coordinator.start()
            while (captureThread === Thread.currentThread()) {
                coordinator.refreshTrackedAppSessions()
                Thread.sleep(TRACKED_PROCESS_REFRESH_INTERVAL_MS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (throwable: Throwable) {
            val message = "logcat capture failure: ${throwable.javaClass.simpleName}: " +
                (throwable.message ?: "unknown")
            appWriter.appendLine("=== $message ===")
            systemWriter.appendLine("=== $message ===")
        } finally {
            coordinator.close(waitForSessions = true)
            activeCaptureCoordinator = null
            runCatching {
                appWriter.appendLine(buildCaptureBanner("app logcat capture stopped"))
            }
            runCatching {
                systemWriter.appendLine(buildCaptureBanner("system logcat capture stopped"))
            }
            appWriter.close()
            systemWriter.close()
            if (captureThread === Thread.currentThread()) {
                captureThread = null
                stopSelf()
            }
        }
    }

    private fun buildCaptureBanner(event: String, sessionStartedAtMs: Long = 0L): String {
        return buildString {
            append("=== ")
            append(event)
            append(" at ")
            append(formatTimestamp(System.currentTimeMillis()))
            if (sessionStartedAtMs > 0L) {
                append(" (launchStartedAtMs=").append(sessionStartedAtMs).append(')')
            }
            append(" ===")
        }
    }

    private fun clearCaptureFiles() {
        RuntimePaths.listLogcatCaptureFiles(applicationContext).forEach { file ->
            if (file.exists()) {
                runCatching { file.delete() }
            }
        }
    }

    private fun resolveTrackedAppProcesses(): Set<TrackedAppProcess> {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptySet()
        val appPackageName = packageName
        return try {
            activityManager.runningAppProcesses
                ?.asSequence()
                ?.filter { process ->
                    process.pid > 0 &&
                        process.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED &&
                        isTrackedProcessName(process.processName.orEmpty(), appPackageName)
                }
                ?.sortedWith(compareBy<ActivityManager.RunningAppProcessInfo> {
                    it.processName.orEmpty()
                }.thenBy { it.pid })
                ?.map { process ->
                    TrackedAppProcess(
                        pid = process.pid,
                        processName = process.processName.orEmpty()
                    )
                }
                ?.toCollection(linkedSetOf())
                .orEmpty()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    internal data class TrackedAppProcess(
        val pid: Int,
        val processName: String
    )

    internal class ActiveCaptureCoordinator(
        private val trackedProcessSupplier: () -> Set<TrackedAppProcess>,
        private val appWriter: RollingTextLogWriter,
        systemWriter: RollingTextLogWriter,
        private val nowProviderMs: () -> Long = { SystemClock.elapsedRealtime() }
    ) {
        private val systemSession = LogcatCommandSession(
            sessionName = "system diagnostic tags",
            command = buildSystemLogcatCommandForCapture(),
            writer = systemWriter
        )
        private val appSessionsByPid = LinkedHashMap<Int, ActivePidSession>()

        @Volatile
        private var stopRequested = false

        @Volatile
        private var closed = false

        fun start() {
            systemSession.start()
            refreshTrackedAppSessions()
        }

        @Synchronized
        fun refreshTrackedAppSessions() {
            if (stopRequested || closed) {
                return
            }

            val nowMs = nowProviderMs()
            val trackedProcessesByPid = trackedProcessSupplier().associateBy { it.pid }

            trackedProcessesByPid.values.forEach { process ->
                val existing = appSessionsByPid[process.pid]
                if (existing != null && existing.logcatSession.isRunning()) {
                    existing.lastSeenAtMs = nowMs
                    return@forEach
                }
                if (existing != null &&
                    nowMs - existing.lastStartedAtMs < FAILED_SESSION_RESTART_COOLDOWN_MS
                ) {
                    existing.lastSeenAtMs = nowMs
                    return@forEach
                }

                val session = LogcatCommandSession(
                    sessionName = "app pid=${process.pid} (${process.processName})",
                    command = buildPidLogcatCommandForCapture(process.pid),
                    writer = appWriter
                )
                appSessionsByPid[process.pid] = ActivePidSession(
                    process = process,
                    logcatSession = session,
                    lastSeenAtMs = nowMs,
                    lastStartedAtMs = nowMs
                )
                session.start()
            }

            val sessionsToStop = ArrayList<LogcatCommandSession>()
            val iterator = appSessionsByPid.values.iterator()
            while (iterator.hasNext()) {
                val activeSession = iterator.next()
                val isStillTracked = trackedProcessesByPid.containsKey(activeSession.process.pid)
                if (isStillTracked) {
                    continue
                }
                if (activeSession.logcatSession.isRunning() &&
                    nowMs - activeSession.lastSeenAtMs < STALE_PID_GRACE_MS
                ) {
                    continue
                }
                iterator.remove()
                sessionsToStop.add(activeSession.logcatSession)
            }
            sessionsToStop.forEach { session ->
                session.stop(waitForThread = true)
            }
        }

        @Synchronized
        fun requestStop() {
            if (stopRequested || closed) {
                return
            }
            stopRequested = true
            systemSession.stop(waitForThread = false)
            appSessionsByPid.values.forEach { activeSession ->
                activeSession.logcatSession.stop(waitForThread = false)
            }
        }

        fun close(waitForSessions: Boolean) {
            val sessionsToStop = synchronized(this) {
                if (closed) {
                    return
                }
                closed = true
                stopRequested = true
                buildList {
                    add(systemSession)
                    appSessionsByPid.values.forEach { activeSession ->
                        add(activeSession.logcatSession)
                    }
                }.also {
                    appSessionsByPid.clear()
                }
            }
            sessionsToStop.forEach { session ->
                session.stop(waitForThread = waitForSessions)
            }
        }

        private data class ActivePidSession(
            val process: TrackedAppProcess,
            val logcatSession: LogcatCommandSession,
            var lastSeenAtMs: Long,
            var lastStartedAtMs: Long
        )
    }

    internal class LogcatCommandSession(
        private val sessionName: String,
        private val command: List<String>,
        private val writer: RollingTextLogWriter
    ) {
        @Volatile
        private var workerThread: Thread? = null

        @Volatile
        private var process: Process? = null

        @Volatile
        private var stopRequested = false

        fun start() {
            if (workerThread != null) {
                return
            }
            stopRequested = false
            val worker = Thread(
                {
                    runSession()
                },
                "STS-Logcat-" + sessionName
                    .replace(' ', '_')
                    .replace('(', '_')
                    .replace(')', '_')
                    .replace(':', '_')
            )
            workerThread = worker
            worker.start()
        }

        fun stop(waitForThread: Boolean) {
            stopRequested = true
            process?.destroy()
            val thread = workerThread
            thread?.interrupt()
            if (waitForThread && thread != null && thread !== Thread.currentThread()) {
                runCatching {
                    thread.join(800L)
                }
            }
        }

        fun isRunning(): Boolean = workerThread?.isAlive == true

        private fun runSession() {
            writer.appendLine(buildLifecycleLine("started"))
            try {
                val startedProcess = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                process = startedProcess
                BufferedReader(
                    InputStreamReader(startedProcess.inputStream, StandardCharsets.UTF_8)
                ).use { reader ->
                    while (!stopRequested) {
                        val line = reader.readLine() ?: break
                        writer.appendLine(line)
                    }
                }
                val exitCode = try {
                    startedProcess.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    startedProcess.destroy()
                    -1
                }
                writer.appendLine(
                    buildLifecycleLine(
                        event = if (stopRequested) "stopped" else "exited",
                        extra = "exitCode=$exitCode"
                    )
                )
            } catch (throwable: Throwable) {
                writer.appendLine(
                    buildLifecycleLine(
                        event = "failure",
                        extra = "${throwable.javaClass.simpleName}: ${throwable.message ?: "unknown"}"
                    )
                )
            } finally {
                process?.destroy()
                process = null
                workerThread = null
            }
        }

        private fun buildLifecycleLine(event: String, extra: String? = null): String {
            return buildString {
                append("=== ")
                append(sessionName)
                append(' ')
                append(event)
                append(" at ")
                append(formatTimestamp(System.currentTimeMillis()))
                if (!extra.isNullOrBlank()) {
                    append(" (").append(extra).append(')')
                }
                append(" ===")
            }
        }
    }
}

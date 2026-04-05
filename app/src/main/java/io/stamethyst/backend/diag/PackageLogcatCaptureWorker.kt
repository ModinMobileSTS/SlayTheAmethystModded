package io.stamethyst.backend.diag

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

internal data class PackageLogcatCaptureConfig(
    val captureLabel: String,
    val appBaseFile: File,
    val systemBaseFile: File,
    val listCaptureFiles: () -> List<File>,
    val trackedProcessMatcher: (processName: String, packageName: String) -> Boolean,
    val clearCaptureFilesOnStart: Boolean,
    val stopWhenNoTrackedProcessesIdleMs: Long? = null,
    val maxBytesPerFile: Long = 768L * 1024L,
    val maxFiles: Int = 4
)

internal class PackageLogcatCaptureWorker(
    private val applicationContext: Context,
    private val config: PackageLogcatCaptureConfig,
    private val onCaptureFinished: () -> Unit
) {
    companion object {
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

        internal fun isTrackedPackageProcessName(processName: String, packageName: String): Boolean {
            return processName == packageName || processName.startsWith("$packageName:")
        }

        private fun formatTimestamp(timestampMs: Long): String {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(Date(timestampMs))
        }
    }

    @Volatile
    private var captureThread: Thread? = null

    @Volatile
    private var activeCaptureCoordinator: ActiveCaptureCoordinator? = null

    fun start(sessionStartedAtMs: Long = 0L, restartIfRunning: Boolean = true) {
        if (!restartIfRunning && isRunning()) {
            return
        }
        stop(waitForThread = true)
        if (config.clearCaptureFilesOnStart) {
            clearCaptureFiles()
        }
        val worker = Thread(
            {
                runCapture(sessionStartedAtMs)
            },
            buildWorkerThreadName()
        )
        captureThread = worker
        worker.start()
    }

    fun stop(waitForThread: Boolean) {
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

    fun stopAndClear() {
        stop(waitForThread = true)
        clearCaptureFiles()
    }

    fun isRunning(): Boolean = captureThread?.isAlive == true

    private fun buildWorkerThreadName(): String {
        return "STS-Logcat-" + config.captureLabel
            .replace(' ', '_')
            .replace(':', '_')
    }

    private fun runCapture(sessionStartedAtMs: Long) {
        val appWriter = RollingTextLogWriter(
            baseFile = config.appBaseFile,
            maxBytesPerFile = config.maxBytesPerFile,
            maxFiles = config.maxFiles
        )
        val systemWriter = RollingTextLogWriter(
            baseFile = config.systemBaseFile,
            maxBytesPerFile = config.maxBytesPerFile,
            maxFiles = config.maxFiles
        )
        val coordinator = ActiveCaptureCoordinator(
            trackedProcessSupplier = { resolveTrackedAppProcesses() },
            appWriter = appWriter,
            systemWriter = systemWriter
        )
        activeCaptureCoordinator = coordinator
        var noTrackedSinceMs: Long? = null
        try {
            appWriter.appendLine(buildCaptureBanner("started", sessionStartedAtMs))
            systemWriter.appendLine(buildCaptureBanner("started", sessionStartedAtMs))
            coordinator.start()
            while (captureThread === Thread.currentThread()) {
                val trackedProcessCount = coordinator.refreshTrackedAppSessions()
                val idleStopMs = config.stopWhenNoTrackedProcessesIdleMs
                if (idleStopMs != null) {
                    val nowMs = SystemClock.elapsedRealtime()
                    if (trackedProcessCount > 0) {
                        noTrackedSinceMs = null
                    } else {
                        val idleStartedAtMs = noTrackedSinceMs ?: nowMs.also {
                            noTrackedSinceMs = it
                        }
                        if (nowMs - idleStartedAtMs >= idleStopMs) {
                            break
                        }
                    }
                }
                Thread.sleep(TRACKED_PROCESS_REFRESH_INTERVAL_MS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (throwable: Throwable) {
            val message = "${config.captureLabel} failure: ${throwable.javaClass.simpleName}: " +
                (throwable.message ?: "unknown")
            appWriter.appendLine("=== $message ===")
            systemWriter.appendLine("=== $message ===")
        } finally {
            coordinator.close(waitForSessions = true)
            activeCaptureCoordinator = null
            runCatching {
                appWriter.appendLine(buildCaptureBanner("stopped"))
            }
            runCatching {
                systemWriter.appendLine(buildCaptureBanner("stopped"))
            }
            appWriter.close()
            systemWriter.close()
            if (captureThread === Thread.currentThread()) {
                captureThread = null
                onCaptureFinished()
            }
        }
    }

    private fun buildCaptureBanner(event: String, sessionStartedAtMs: Long = 0L): String {
        return buildString {
            append("=== ")
            append(config.captureLabel)
            append(' ')
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
        config.listCaptureFiles().forEach { file ->
            if (file.exists()) {
                runCatching { file.delete() }
            }
        }
    }

    private fun resolveTrackedAppProcesses(): Set<TrackedAppProcess> {
        val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptySet()
        val appPackageName = applicationContext.packageName
        return try {
            activityManager.runningAppProcesses
                ?.asSequence()
                ?.filter { process ->
                    process.pid > 0 &&
                        process.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED &&
                        config.trackedProcessMatcher(process.processName.orEmpty(), appPackageName)
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
        fun refreshTrackedAppSessions(): Int {
            if (stopRequested || closed) {
                return 0
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
            return trackedProcessesByPid.size
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

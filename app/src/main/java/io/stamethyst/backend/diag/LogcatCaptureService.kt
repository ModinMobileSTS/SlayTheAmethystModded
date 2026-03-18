package io.stamethyst.backend.diag

import android.app.Service
import android.content.Intent
import android.os.IBinder
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

        internal fun buildLogcatCommandForCapture(): List<String> {
            return listOf(
                "logcat",
                "-v", "threadtime",
                "-b", "main",
                "-b", "system",
                "-b", "crash",
                "-b", "events",
                "*:I"
            )
        }
    }

    @Volatile
    private var captureThread: Thread? = null

    @Volatile
    private var captureProcess: Process? = null

    @Volatile
    private var activeSessionStartedAtMs: Long = 0L

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
        activeSessionStartedAtMs = sessionStartedAtMs
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
        captureProcess?.destroy()
        captureProcess = null
        thread?.interrupt()
        if (waitForThread && thread != null && thread !== Thread.currentThread()) {
            runCatching {
                thread.join(800L)
            }
        }
    }

    private fun runCapture(sessionStartedAtMs: Long) {
        val logFile = RuntimePaths.logcatCaptureLog(applicationContext)
        val writer = RollingTextLogWriter(
            baseFile = logFile,
            maxBytesPerFile = LOGCAT_ROTATE_KB,
            maxFiles = LOGCAT_ROTATE_FILES
        )
        try {
            writer.appendLine(
                buildString {
                    append("=== logcat capture started at ")
                    append(formatTimestamp(System.currentTimeMillis()))
                    if (sessionStartedAtMs > 0L) {
                        append(" (launchStartedAtMs=").append(sessionStartedAtMs).append(')')
                    }
                    append(" ===")
                }
            )
            val process = ProcessBuilder(buildLogcatCommandForCapture())
                .redirectErrorStream(true)
                .start()
            captureProcess = process
            BufferedReader(
                InputStreamReader(process.inputStream, StandardCharsets.UTF_8)
            ).use { reader ->
                while (captureThread === Thread.currentThread()) {
                    val line = reader.readLine() ?: break
                    writer.appendLine(line)
                }
            }
            val exitCode = try {
                process.waitFor()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                process.destroy()
                -1
            }
            if (captureThread === Thread.currentThread()) {
                writer.appendLine(
                    "=== logcat capture stopped at ${formatTimestamp(System.currentTimeMillis())} " +
                        "(exitCode=$exitCode) ==="
                )
            }
        } catch (throwable: Throwable) {
            writer.appendLine(
                "=== logcat capture failure: ${throwable.javaClass.simpleName}: " +
                    "${throwable.message ?: "unknown"} ==="
            )
        } finally {
            captureProcess?.destroy()
            captureProcess = null
            writer.close()
            if (captureThread === Thread.currentThread()) {
                captureThread = null
                stopSelf()
            }
        }
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            .format(Date(timestampMs))
    }

    private fun clearCaptureFiles() {
        RuntimePaths.listLogcatCaptureFiles(applicationContext).forEach { file ->
            if (file.exists()) {
                runCatching { file.delete() }
            }
        }
    }
}

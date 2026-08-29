package io.stamethyst.backend.diag

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import java.io.PrintWriter
import java.io.StringWriter

class DiagnosticsProcessService : Service() {
    companion object {
        const val ACTION_EXPORT_JVM_LOG_BUNDLE = "io.stamethyst.action.EXPORT_JVM_LOG_BUNDLE"
        const val ACTION_BUILD_JVM_LOG_SHARE = "io.stamethyst.action.BUILD_JVM_LOG_SHARE"
        const val ACTION_BUILD_CRASH_SHARE = "io.stamethyst.action.BUILD_CRASH_SHARE"
        const val ACTION_EXPORT_PERFORMANCE_LOG_BUNDLE = "io.stamethyst.action.EXPORT_PERFORMANCE_LOG_BUNDLE"
        const val ACTION_BUILD_PERFORMANCE_LOG_SHARE = "io.stamethyst.action.BUILD_PERFORMANCE_LOG_SHARE"
        /** No ResultReceiver required. Writes archive to staging dir, then writes sentinel. */
        const val ACTION_ADB_STAGE_JVM_LOG = "io.stamethyst.action.ADB_STAGE_JVM_LOG"

        /** Staging sub-directory inside filesDir, accessible via run-as. */
        const val ADB_STAGING_DIR = "sts-logs-staging"
        /** Archive file name inside ADB_STAGING_DIR. */
        const val ADB_STAGING_ZIP = "export.zip"
        /** Written after success; content = entry count as decimal string. */
        const val ADB_STAGING_SENTINEL_READY = "ready"
        /** Written after failure; content = error message. */
        const val ADB_STAGING_SENTINEL_ERROR = "error"

        const val EXTRA_RESULT_RECEIVER = "io.stamethyst.extra.RESULT_RECEIVER"
        const val EXTRA_DESTINATION_URI = "io.stamethyst.extra.DESTINATION_URI"
        const val EXTRA_OUTPUT_PATH = "io.stamethyst.extra.OUTPUT_PATH"
        const val EXTRA_ENTRY_COUNT = "io.stamethyst.extra.ENTRY_COUNT"
        const val EXTRA_ERROR_CLASS = "io.stamethyst.extra.ERROR_CLASS"
        const val EXTRA_ERROR_MESSAGE = "io.stamethyst.extra.ERROR_MESSAGE"
        const val EXTRA_ERROR_STACKTRACE = "io.stamethyst.extra.ERROR_STACKTRACE"
        const val EXTRA_CRASH_CODE = "io.stamethyst.extra.CRASH_CODE"
        const val EXTRA_CRASH_IS_SIGNAL = "io.stamethyst.extra.CRASH_IS_SIGNAL"
        const val EXTRA_CRASH_DETAIL = "io.stamethyst.extra.CRASH_DETAIL"

        const val RESULT_SUCCESS = 1
        const val RESULT_FAILURE = 2
    }

    @Volatile
    private var workerThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val safeIntent = intent ?: return START_NOT_STICKY
        if (safeIntent.action == ACTION_ADB_STAGE_JVM_LOG) {
            // adb-triggered path: no ResultReceiver, signal via staging sentinel files
            if (workerThread != null) return START_NOT_STICKY
            val thread = Thread({ runAdbStage(startId) }, "STS-Diag-Adb")
            workerThread = thread
            thread.start()
            return START_NOT_STICKY
        }
        val receiver = extractResultReceiver(safeIntent) ?: return START_NOT_STICKY
        if (workerThread != null) {
            receiver.send(
                RESULT_FAILURE,
                errorBundle(IllegalStateException("Diagnostics process is already busy"))
            )
            return START_NOT_STICKY
        }

        val thread = Thread({ runRequest(startId, safeIntent, receiver) }, "STS-Diag")
        workerThread = thread
        thread.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val thread = workerThread
        workerThread = null
        thread?.interrupt()
        super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun runAdbStage(startId: Int) {
        val stagingDir = java.io.File(applicationContext.filesDir, ADB_STAGING_DIR)
        stagingDir.mkdirs()
        val zipFile = java.io.File(stagingDir, ADB_STAGING_ZIP)
        val readySentinel = java.io.File(stagingDir, ADB_STAGING_SENTINEL_READY)
        val errorSentinel = java.io.File(stagingDir, ADB_STAGING_SENTINEL_ERROR)
        // Clear previous run artifacts
        zipFile.delete(); readySentinel.delete(); errorSentinel.delete()
        try {
            val entryCount = java.io.FileOutputStream(zipFile, false).use { out ->
                DiagnosticsArchiveBuilder.writeDiagnosticsBundlePublic(applicationContext, out)
            }
            readySentinel.writeText(entryCount.toString())
        } catch (t: Throwable) {
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            errorSentinel.writeText(sw.toString())
        } finally {
            if (Thread.currentThread() === workerThread) workerThread = null
            stopSelfResult(startId)
        }
    }

    private fun runRequest(
        startId: Int,
        intent: Intent,
        receiver: ResultReceiver
    ) {
        try {
            val resultData = when (intent.action) {
                ACTION_EXPORT_JVM_LOG_BUNDLE -> {
                    val destination = extractDestinationUri(intent)
                        ?: throw IllegalArgumentException("Missing export destination URI")
                    val exportedCount = DiagnosticsArchiveBuilder.exportJvmLogBundle(
                        applicationContext,
                        destination
                    )
                    Bundle().apply {
                        putInt(EXTRA_ENTRY_COUNT, exportedCount)
                    }
                }

                ACTION_BUILD_JVM_LOG_SHARE -> {
                    val result = DiagnosticsArchiveBuilder.createJvmLogShareArchive(applicationContext)
                    Bundle().apply {
                        putString(EXTRA_OUTPUT_PATH, result.archiveFile.absolutePath)
                        putInt(EXTRA_ENTRY_COUNT, result.entryCount)
                    }
                }

                ACTION_BUILD_CRASH_SHARE -> {
                    val crashContext = CrashArchiveContext(
                        code = intent.getIntExtra(EXTRA_CRASH_CODE, -1),
                        isSignal = intent.getBooleanExtra(EXTRA_CRASH_IS_SIGNAL, false),
                        detail = intent.getStringExtra(EXTRA_CRASH_DETAIL)
                    )
                    val result = DiagnosticsArchiveBuilder.createCrashShareArchive(
                        applicationContext,
                        crashContext
                    )
                    Bundle().apply {
                        putString(EXTRA_OUTPUT_PATH, result.archiveFile.absolutePath)
                        putInt(EXTRA_ENTRY_COUNT, result.entryCount)
                    }
                }

                ACTION_EXPORT_PERFORMANCE_LOG_BUNDLE -> {
                    val destination = extractDestinationUri(intent)
                        ?: throw IllegalArgumentException("Missing export destination URI")
                    val exportedCount = DiagnosticsArchiveBuilder.exportPerformanceDiagnosticsBundle(
                        applicationContext,
                        destination
                    )
                    Bundle().apply {
                        putInt(EXTRA_ENTRY_COUNT, exportedCount)
                    }
                }

                ACTION_BUILD_PERFORMANCE_LOG_SHARE -> {
                    val result = DiagnosticsArchiveBuilder.createPerformanceShareArchive(applicationContext)
                    Bundle().apply {
                        putString(EXTRA_OUTPUT_PATH, result.archiveFile.absolutePath)
                        putInt(EXTRA_ENTRY_COUNT, result.entryCount)
                    }
                }

                else -> throw IllegalArgumentException("Unsupported diagnostics action: ${intent.action}")
            }
            receiver.send(RESULT_SUCCESS, resultData)
        } catch (throwable: Throwable) {
            receiver.send(RESULT_FAILURE, errorBundle(throwable))
        } finally {
            if (Thread.currentThread() === workerThread) {
                workerThread = null
            }
            stopSelfResult(startId)
        }
    }

    private fun extractDestinationUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DESTINATION_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DESTINATION_URI)
        }
    }

    private fun extractResultReceiver(intent: Intent): ResultReceiver? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_RECEIVER)
        }
    }

    private fun errorBundle(throwable: Throwable): Bundle {
        val stackTraceWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stackTraceWriter))
        return Bundle().apply {
            putString(EXTRA_ERROR_CLASS, throwable.javaClass.name)
            putString(EXTRA_ERROR_MESSAGE, throwable.message)
            putString(EXTRA_ERROR_STACKTRACE, stackTraceWriter.toString())
        }
    }
}

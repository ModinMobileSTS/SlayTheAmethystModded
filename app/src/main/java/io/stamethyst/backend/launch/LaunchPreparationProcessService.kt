package io.stamethyst.backend.launch

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter

class LaunchPreparationProcessService : Service() {
    companion object {
        const val ACTION_PREPARE = "io.stamethyst.action.PREPARE_LAUNCH"
        const val ACTION_CANCEL = "io.stamethyst.action.CANCEL_PREPARE_LAUNCH"
        const val EXTRA_LAUNCH_MODE = "io.stamethyst.extra.LAUNCH_MODE"
        const val EXTRA_RESULT_RECEIVER = "io.stamethyst.extra.RESULT_RECEIVER"

        const val RESULT_PROGRESS = 1
        const val RESULT_SUCCESS = 2
        const val RESULT_FAILURE = 3
        const val RESULT_CANCELLED = 4

        const val EXTRA_PROGRESS = "io.stamethyst.extra.PROGRESS"
        const val EXTRA_MESSAGE = "io.stamethyst.extra.MESSAGE"
        const val EXTRA_ERROR_CLASS = "io.stamethyst.extra.ERROR_CLASS"
        const val EXTRA_ERROR_MESSAGE = "io.stamethyst.extra.ERROR_MESSAGE"
        const val EXTRA_ERROR_STACKTRACE = "io.stamethyst.extra.ERROR_STACKTRACE"
    }

    @Volatile
    private var workerThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val safeIntent = intent ?: return START_NOT_STICKY
        when (safeIntent.action) {
            ACTION_CANCEL -> {
                workerThread?.interrupt()
                stopSelfResult(startId)
            }

            ACTION_PREPARE -> {
                val receiver = extractResultReceiver(safeIntent)
                val launchMode = safeIntent.getStringExtra(EXTRA_LAUNCH_MODE).orEmpty()
                if (receiver == null || launchMode.isBlank()) {
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                if (workerThread != null) {
                    receiver.send(
                        RESULT_FAILURE,
                        errorBundle(
                            IllegalStateException("Launch preparation already running")
                        )
                    )
                    return START_NOT_STICKY
                }

                val thread = Thread(
                    {
                        runPreparation(
                            startId = startId,
                            launchMode = launchMode,
                            resultReceiver = receiver
                        )
                    },
                    "STS-Prep"
                )
                workerThread = thread
                thread.start()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val thread = workerThread
        workerThread = null
        thread?.interrupt()
        super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun runPreparation(
        startId: Int,
        launchMode: String,
        resultReceiver: ResultReceiver
    ) {
        try {
            LaunchPreparationService.prepare(applicationContext, launchMode) { percent, message ->
                if (Thread.currentThread().isInterrupted) {
                    throw IOException("Launch preparation cancelled")
                }
                resultReceiver.send(
                    RESULT_PROGRESS,
                    Bundle().apply {
                        putInt(EXTRA_PROGRESS, percent)
                        putString(EXTRA_MESSAGE, message)
                    }
                )
            }
            resultReceiver.send(RESULT_SUCCESS, Bundle.EMPTY)
        } catch (throwable: Throwable) {
            resultReceiver.send(
                if (isCancellation(throwable)) RESULT_CANCELLED else RESULT_FAILURE,
                errorBundle(throwable)
            )
        } finally {
            if (Thread.currentThread() === workerThread) {
                workerThread = null
            }
            stopSelfResult(startId)
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

    private fun isCancellation(throwable: Throwable): Boolean {
        if (throwable is InterruptedException) {
            return true
        }
        val message = throwable.message?.lowercase().orEmpty()
        return message.contains("cancel")
    }
}

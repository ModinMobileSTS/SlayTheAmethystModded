package io.stamethyst.backend.diag

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object DiagnosticsProcessClient {
    private const val WAIT_SLICE_MS = 150L

    private sealed interface DiagnosticsResult {
        data class Success(val data: Bundle) : DiagnosticsResult
        data class Failure(val exception: IOException) : DiagnosticsResult
    }

    fun exportJvmLogBundle(context: Context, destination: Uri): Int {
        val result = execute(context) { serviceIntent ->
            serviceIntent.action = DiagnosticsProcessService.ACTION_EXPORT_JVM_LOG_BUNDLE
            serviceIntent.putExtra(DiagnosticsProcessService.EXTRA_DESTINATION_URI, destination)
        }
        return result.getInt(DiagnosticsProcessService.EXTRA_ENTRY_COUNT, 0)
    }

    fun buildJvmLogShareArchive(context: Context): DiagnosticsArchiveResult {
        val result = execute(context) { serviceIntent ->
            serviceIntent.action = DiagnosticsProcessService.ACTION_BUILD_JVM_LOG_SHARE
        }
        return parseArchiveResult(result)
    }

    fun buildCrashShareArchive(
        context: Context,
        crashContext: CrashArchiveContext
    ): DiagnosticsArchiveResult {
        val result = execute(context) { serviceIntent ->
            serviceIntent.action = DiagnosticsProcessService.ACTION_BUILD_CRASH_SHARE
            serviceIntent.putExtra(DiagnosticsProcessService.EXTRA_CRASH_CODE, crashContext.code)
            serviceIntent.putExtra(
                DiagnosticsProcessService.EXTRA_CRASH_IS_SIGNAL,
                crashContext.isSignal
            )
            serviceIntent.putExtra(DiagnosticsProcessService.EXTRA_CRASH_DETAIL, crashContext.detail)
        }
        return parseArchiveResult(result)
    }

    private fun parseArchiveResult(result: Bundle): DiagnosticsArchiveResult {
        val outputPath = result.getString(DiagnosticsProcessService.EXTRA_OUTPUT_PATH)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IOException("Diagnostics process returned an empty archive path")
        val archiveFile = File(outputPath)
        if (!archiveFile.isFile) {
            throw IOException("Diagnostics archive missing: $outputPath")
        }
        return DiagnosticsArchiveResult(
            archiveFile = archiveFile,
            entryCount = result.getInt(DiagnosticsProcessService.EXTRA_ENTRY_COUNT, 0)
        )
    }

    private fun execute(
        context: Context,
        configureIntent: (Intent) -> Unit
    ): Bundle {
        val appContext = context.applicationContext
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<DiagnosticsResult?>()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                when (resultCode) {
                    DiagnosticsProcessService.RESULT_SUCCESS -> {
                        if (resultRef.compareAndSet(null, DiagnosticsResult.Success(resultData ?: Bundle.EMPTY))) {
                            latch.countDown()
                        }
                    }

                    DiagnosticsProcessService.RESULT_FAILURE -> {
                        val exception = buildRemoteFailure(resultData)
                        if (resultRef.compareAndSet(null, DiagnosticsResult.Failure(exception))) {
                            latch.countDown()
                        }
                    }
                }
            }
        }

        val serviceIntent = Intent(appContext, DiagnosticsProcessService::class.java).apply {
            putExtra(DiagnosticsProcessService.EXTRA_RESULT_RECEIVER, receiver)
        }
        configureIntent(serviceIntent)

        val component = appContext.startService(serviceIntent)
        if (component == null) {
            throw IOException("Failed to start diagnostics process service")
        }

        while (true) {
            try {
                if (latch.await(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)) {
                    break
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Diagnostics operation interrupted", interrupted)
            }
        }

        return when (val result = resultRef.get()) {
            is DiagnosticsResult.Success -> result.data
            is DiagnosticsResult.Failure -> throw result.exception
            null -> throw IOException("Diagnostics process exited without a terminal result")
        }
    }

    private fun buildRemoteFailure(bundle: Bundle?): IOException {
        val className = bundle?.getString(DiagnosticsProcessService.EXTRA_ERROR_CLASS).orEmpty()
        val message = bundle?.getString(DiagnosticsProcessService.EXTRA_ERROR_MESSAGE).orEmpty()
        val stackTrace = bundle?.getString(DiagnosticsProcessService.EXTRA_ERROR_STACKTRACE).orEmpty()
        val summary = buildString {
            append(if (className.isNotBlank()) className else "DiagnosticsProcessService")
            if (message.isNotBlank()) {
                append(": ").append(message)
            }
            if (stackTrace.isNotBlank()) {
                append('\n').append(stackTrace.trim())
            }
        }.ifBlank {
            "Diagnostics process failed"
        }
        return IOException(summary)
    }
}

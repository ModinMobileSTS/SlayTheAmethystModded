package io.stamethyst.backend.launch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object LaunchPreparationProcessClient {
    private const val WAIT_SLICE_MS = 150L

    private sealed interface PreparationResult {
        data object Success : PreparationResult
        data object Cancelled : PreparationResult
        data class Failure(val exception: IOException) : PreparationResult
    }

    fun prepare(
        context: Context,
        launchMode: String,
        progressCallback: StartupProgressCallback?,
        throwIfCancelled: () -> Unit
    ) {
        throwIfCancelled()

        val appContext = context.applicationContext
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<PreparationResult?>()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                when (resultCode) {
                    LaunchPreparationProcessService.RESULT_PROGRESS -> {
                        progressCallback?.onProgress(
                            resultData?.getInt(LaunchPreparationProcessService.EXTRA_PROGRESS) ?: 0,
                            resultData?.getString(LaunchPreparationProcessService.EXTRA_MESSAGE)
                                .orEmpty()
                        )
                    }

                    LaunchPreparationProcessService.RESULT_SUCCESS -> {
                        if (resultRef.compareAndSet(null, PreparationResult.Success)) {
                            latch.countDown()
                        }
                    }

                    LaunchPreparationProcessService.RESULT_CANCELLED -> {
                        if (resultRef.compareAndSet(null, PreparationResult.Cancelled)) {
                            latch.countDown()
                        }
                    }

                    LaunchPreparationProcessService.RESULT_FAILURE -> {
                        val exception = buildRemoteFailure(resultData)
                        if (resultRef.compareAndSet(null, PreparationResult.Failure(exception))) {
                            latch.countDown()
                        }
                    }
                }
            }
        }

        val startIntent = Intent(appContext, LaunchPreparationProcessService::class.java).apply {
            action = LaunchPreparationProcessService.ACTION_PREPARE
            putExtra(LaunchPreparationProcessService.EXTRA_LAUNCH_MODE, launchMode)
            putExtra(LaunchPreparationProcessService.EXTRA_RESULT_RECEIVER, receiver)
        }
        val component = appContext.startService(startIntent)
        if (component == null) {
            throw IOException("Failed to start launch preparation service")
        }

        try {
            while (true) {
                throwIfCancelled()
                if (latch.await(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)) {
                    break
                }
            }
            throwIfCancelled()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            cancel(appContext)
            throw IOException("Launch preparation cancelled", interrupted)
        } catch (throwable: Throwable) {
            cancel(appContext)
            throw throwable
        }

        when (val result = resultRef.get()) {
            PreparationResult.Success -> Unit
            PreparationResult.Cancelled ->
                throw IOException("Launch preparation cancelled")
            is PreparationResult.Failure ->
                throw result.exception
            null ->
                throw IOException(
                    "Launch preparation process exited without a terminal result"
                )
        }
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, LaunchPreparationProcessService::class.java).apply {
            action = LaunchPreparationProcessService.ACTION_CANCEL
        }
        try {
            appContext.startService(intent)
        } catch (_: Throwable) {
        }
    }

    private fun buildRemoteFailure(bundle: Bundle?): IOException {
        val className = bundle?.getString(LaunchPreparationProcessService.EXTRA_ERROR_CLASS)
            .orEmpty()
        val message = bundle?.getString(LaunchPreparationProcessService.EXTRA_ERROR_MESSAGE)
            .orEmpty()
        val stackTrace = bundle?.getString(LaunchPreparationProcessService.EXTRA_ERROR_STACKTRACE)
            .orEmpty()
        val summary = buildString {
            append(
                if (className.isNotBlank()) className else "LaunchPreparationProcessService"
            )
            if (message.isNotBlank()) {
                append(": ")
                append(message)
            }
            if (stackTrace.isNotBlank()) {
                append('\n')
                append(stackTrace.trim())
            }
        }.ifBlank {
            "Launch preparation failed in :prep process"
        }
        return IOException(summary)
    }
}

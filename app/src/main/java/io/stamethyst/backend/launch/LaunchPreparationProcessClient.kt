package io.stamethyst.backend.launch

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import io.stamethyst.backend.diag.MemoryDiagnosticsLogger
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal object LaunchPreparationProcessClient {
    private const val WAIT_SLICE_MS = 150L
    private const val PREP_PROCESS_SUFFIX = ":prep"
    private const val PREP_PROCESS_START_GRACE_MS = 2_000L
    private const val PREP_PROCESS_MISSING_TIMEOUT_MS = 1_500L
    private const val PREP_PROCESS_STALL_TIMEOUT_MS = 45_000L
    private const val PREP_PROCESS_MAX_MISSING_RECOVERY_ATTEMPTS = 2
    private const val PREP_PROCESS_RECOVERY_COOLDOWN_MS = 300L
    private const val PREP_PROCESS_FAILURE_MARKER = "STS_PREP_PROCESS_FAILURE"

    internal const val PREP_PROCESS_FAILURE_REASON_MISSING = "missing"
    internal const val PREP_PROCESS_FAILURE_REASON_STALLED = "stalled"

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
        var recoveryAttempt = 0
        while (true) {
            throwIfCancelled()
            try {
                if (recoveryAttempt == 0) {
                    prepareOnce(
                        context = context,
                        launchMode = launchMode,
                        progressCallback = progressCallback,
                        throwIfCancelled = throwIfCancelled
                    )
                } else {
                    recoverAfterPrepMissing(
                        context = context,
                        launchMode = launchMode,
                        progressCallback = progressCallback,
                        throwIfCancelled = throwIfCancelled
                    )
                }
                return
            } catch (error: IOException) {
                val shouldRecover = recoveryAttempt < PREP_PROCESS_MAX_MISSING_RECOVERY_ATTEMPTS &&
                    containsPrepProcessFailureReason(error, PREP_PROCESS_FAILURE_REASON_MISSING)
                if (!shouldRecover) {
                    throw error
                }
                recoveryAttempt += 1
                MemoryDiagnosticsLogger.logEvent(
                    context = context.applicationContext,
                    event = "launch_preparation_process_restart_requested",
                    extras = linkedMapOf<String, Any?>(
                        "launchMode" to launchMode,
                        "attempt" to recoveryAttempt,
                        "reason" to PREP_PROCESS_FAILURE_REASON_MISSING,
                        "message" to error.message
                    ),
                    includeMemorySnapshot = false
                )
                SystemClock.sleep(PREP_PROCESS_RECOVERY_COOLDOWN_MS)
            }
        }
    }

    private fun prepareOnce(
        context: Context,
        launchMode: String,
        progressCallback: StartupProgressCallback?,
        throwIfCancelled: () -> Unit
    ) {
        throwIfCancelled()
        val appContext = context.applicationContext
        val startedAtMs = SystemClock.uptimeMillis()
        val lastSignalAtMs = AtomicLong(startedAtMs)
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<PreparationResult?>()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                lastSignalAtMs.set(SystemClock.uptimeMillis())
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
        val component = try {
            appContext.startService(startIntent)
        } catch (error: Throwable) {
            throw IOException("Failed to start launch preparation service", error)
        }
        if (component == null) {
            throw IOException("Failed to start launch preparation service")
        }

        try {
            var prepProcessMissingSinceMs = -1L
            while (true) {
                throwIfCancelled()
                if (latch.await(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)) {
                    break
                }
                val now = SystemClock.uptimeMillis()
                if (now - lastSignalAtMs.get() >= PREP_PROCESS_STALL_TIMEOUT_MS) {
                    MemoryDiagnosticsLogger.logEvent(
                        context = appContext,
                        event = "launch_preparation_wait_timeout",
                        extras = linkedMapOf<String, Any?>(
                            "launchMode" to launchMode,
                            "reason" to PREP_PROCESS_FAILURE_REASON_STALLED,
                            "elapsedMs" to now - startedAtMs,
                            "lastSignalAgeMs" to now - lastSignalAtMs.get(),
                            "prepProcessRunning" to isPrepProcessRunning(appContext)
                        ),
                        includeMemorySnapshot = false
                    )
                    cancel(appContext)
                    throw buildPrepProcessFailure(
                        PREP_PROCESS_FAILURE_REASON_STALLED
                    )
                }
                if (now - startedAtMs < PREP_PROCESS_START_GRACE_MS) {
                    continue
                }
                if (isPrepProcessRunning(appContext)) {
                    prepProcessMissingSinceMs = -1L
                    continue
                }
                if (prepProcessMissingSinceMs < 0L) {
                    prepProcessMissingSinceMs = now
                    continue
                }
                if (now - prepProcessMissingSinceMs >= PREP_PROCESS_MISSING_TIMEOUT_MS) {
                    MemoryDiagnosticsLogger.logEvent(
                        context = appContext,
                        event = "launch_preparation_wait_timeout",
                        extras = linkedMapOf<String, Any?>(
                            "launchMode" to launchMode,
                            "reason" to PREP_PROCESS_FAILURE_REASON_MISSING,
                            "elapsedMs" to now - startedAtMs,
                            "lastSignalAgeMs" to now - lastSignalAtMs.get(),
                            "prepProcessMissingDurationMs" to now - prepProcessMissingSinceMs,
                            "prepProcessRunning" to false
                        ),
                        includeMemorySnapshot = false
                    )
                    cancel(appContext)
                    throw buildPrepProcessFailure(
                        PREP_PROCESS_FAILURE_REASON_MISSING
                    )
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

    internal fun isPrepProcessFailureMessage(message: String?): Boolean {
        return prepProcessFailureReason(message) != null
    }

    internal fun prepProcessFailureReason(message: String?): String? {
        val text = message?.trim().orEmpty()
        val markerIndex = text.indexOf("$PREP_PROCESS_FAILURE_MARKER:")
        if (markerIndex < 0) {
            return null
        }
        return text
            .substring(markerIndex + PREP_PROCESS_FAILURE_MARKER.length + 1)
            .substringBefore('\n')
            .trim()
            .removePrefix(":")
            .ifEmpty { null }
    }

    internal fun buildPrepProcessFailure(reason: String): IOException {
        val normalizedReason = reason.trim().ifEmpty { "unknown" }
        return IOException("$PREP_PROCESS_FAILURE_MARKER:$normalizedReason")
    }

    internal fun isPrepProcessStartFailure(throwable: Throwable): Boolean {
        return generateSequence(throwable) { it.cause }
            .mapNotNull { error -> error.message?.trim() }
            .any { message -> message == "Failed to start launch preparation service" }
    }

    internal fun buildPrepRecoveryFailure(
        restartFailure: Throwable,
        originalPrepFailure: IOException
    ): IOException {
        val message = restartFailure.message?.trim().orEmpty()
            .ifEmpty { "Failed to recover launch preparation service" }
        return IOException(message, originalPrepFailure).also { wrapped ->
            if (restartFailure !== originalPrepFailure) {
                wrapped.addSuppressed(restartFailure)
            }
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

    private fun isPrepProcessRunning(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return try {
            activityManager.runningAppProcesses
                ?.any { process ->
                    process.processName == context.packageName + PREP_PROCESS_SUFFIX &&
                        process.pid > 0 &&
                        process.importance <
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
                }
                ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun containsPrepProcessFailureReason(
        throwable: Throwable,
        reason: String
    ): Boolean {
        return generateSequence(throwable) { it.cause }
            .mapNotNull { error -> prepProcessFailureReason(error.message) }
            .any { failureReason -> failureReason == reason }
    }

    private fun recoverAfterPrepMissing(
        context: Context,
        launchMode: String,
        progressCallback: StartupProgressCallback?,
        throwIfCancelled: () -> Unit
    ) {
        val originalMissingFailure = buildPrepProcessFailure(PREP_PROCESS_FAILURE_REASON_MISSING)
        try {
            prepareOnce(
                context = context,
                launchMode = launchMode,
                progressCallback = progressCallback,
                throwIfCancelled = throwIfCancelled
            )
            return
        } catch (restartFailure: IOException) {
            if (!isPrepProcessStartFailure(restartFailure)) {
                throw restartFailure
            }
            MemoryDiagnosticsLogger.logEvent(
                context = context.applicationContext,
                event = "launch_preparation_process_inline_recovery_requested",
                extras = linkedMapOf<String, Any?>(
                    "launchMode" to launchMode,
                    "message" to restartFailure.message
                ),
                includeMemorySnapshot = false
            )
            try {
                prepareInProcess(
                    context = context,
                    launchMode = launchMode,
                    progressCallback = progressCallback,
                    throwIfCancelled = throwIfCancelled
                )
            } catch (inlineFailure: IOException) {
                throw buildPrepRecoveryFailure(inlineFailure, originalMissingFailure)
            } catch (inlineFailure: Throwable) {
                throw buildPrepRecoveryFailure(inlineFailure, originalMissingFailure)
            }
        }
    }

    private fun prepareInProcess(
        context: Context,
        launchMode: String,
        progressCallback: StartupProgressCallback?,
        throwIfCancelled: () -> Unit
    ) {
        throwIfCancelled()
        val guardedProgressCallback = if (progressCallback == null) {
            null
        } else {
            StartupProgressCallback { percent, message ->
                throwIfCancelled()
                progressCallback.onProgress(percent, message)
            }
        }
        val appContext = context.applicationContext
        MemoryDiagnosticsLogger.logEvent(
            context = appContext,
            event = "launch_preparation_process_inline_recovery_started",
            extras = linkedMapOf<String, Any?>("launchMode" to launchMode),
            includeMemorySnapshot = false
        )
        try {
            LaunchPreparationService.prepare(appContext, launchMode, guardedProgressCallback)
        } catch (error: IOException) {
            MemoryDiagnosticsLogger.logEvent(
                context = appContext,
                event = "launch_preparation_process_inline_recovery_failed",
                extras = linkedMapOf<String, Any?>(
                    "launchMode" to launchMode,
                    "errorClass" to error.javaClass.name,
                    "message" to error.message
                ),
                includeMemorySnapshot = false
            )
            throw error
        }
        MemoryDiagnosticsLogger.logEvent(
            context = appContext,
            event = "launch_preparation_process_inline_recovery_completed",
            extras = linkedMapOf<String, Any?>("launchMode" to launchMode),
            includeMemorySnapshot = false
        )
    }
}

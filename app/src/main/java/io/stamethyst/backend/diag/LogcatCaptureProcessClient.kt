package io.stamethyst.backend.diag

import android.content.Context
import android.content.Intent

internal object LogcatCaptureProcessClient {
    fun startCapture(context: Context, sessionStartedAtMs: Long) {
        val serviceIntent = Intent(context.applicationContext, LogcatCaptureService::class.java).apply {
            action = LogcatCaptureService.ACTION_START_CAPTURE
            putExtra(LogcatCaptureService.EXTRA_SESSION_STARTED_AT_MS, sessionStartedAtMs)
        }
        runCatching {
            context.applicationContext.startService(serviceIntent)
        }
    }

    fun stopCapture(context: Context) {
        val serviceIntent = Intent(context.applicationContext, LogcatCaptureService::class.java).apply {
            action = LogcatCaptureService.ACTION_STOP_CAPTURE
        }
        runCatching {
            context.applicationContext.startService(serviceIntent)
        }
    }

    fun stopAndClearCapture(context: Context) {
        val serviceIntent = Intent(context.applicationContext, LogcatCaptureService::class.java).apply {
            action = LogcatCaptureService.ACTION_STOP_AND_CLEAR_CAPTURE
        }
        runCatching {
            context.applicationContext.startService(serviceIntent)
        }
    }
}

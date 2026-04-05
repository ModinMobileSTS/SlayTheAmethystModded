package io.stamethyst.backend.diag

import android.content.Context
import android.content.Intent

internal object LauncherLogcatCaptureProcessClient {
    fun startCapture(context: Context) {
        val serviceIntent = Intent(
            context.applicationContext,
            LauncherLogcatCaptureService::class.java
        ).apply {
            action = LauncherLogcatCaptureService.ACTION_START_CAPTURE
        }
        runCatching {
            context.applicationContext.startService(serviceIntent)
        }
    }

    fun stopCapture(context: Context) {
        val serviceIntent = Intent(
            context.applicationContext,
            LauncherLogcatCaptureService::class.java
        ).apply {
            action = LauncherLogcatCaptureService.ACTION_STOP_CAPTURE
        }
        runCatching {
            context.applicationContext.startService(serviceIntent)
        }
    }

    fun stopAndClearCapture(context: Context) {
        val serviceIntent = Intent(
            context.applicationContext,
            LauncherLogcatCaptureService::class.java
        ).apply {
            action = LauncherLogcatCaptureService.ACTION_STOP_AND_CLEAR_CAPTURE
        }
        runCatching {
            context.applicationContext.startService(serviceIntent)
        }
    }
}

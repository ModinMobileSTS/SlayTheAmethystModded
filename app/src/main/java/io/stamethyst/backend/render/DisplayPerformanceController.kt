package io.stamethyst.backend.render

import android.app.Activity
import android.os.Build
import android.os.PowerManager

object DisplayPerformanceController {
    fun applySustainedPerformanceMode(activity: Activity, enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }
        val powerManager = activity.getSystemService(PowerManager::class.java) ?: return
        if (!powerManager.isSustainedPerformanceModeSupported) {
            return
        }
        try {
            activity.window.setSustainedPerformanceMode(enabled)
        } catch (_: Throwable) {}
    }
}

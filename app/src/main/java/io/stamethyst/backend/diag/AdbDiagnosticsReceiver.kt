package io.stamethyst.backend.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Exported broadcast receiver that lets adb trigger DiagnosticsProcessService
 * without requiring the service itself to be exported.
 *
 * Usage:
 *   adb shell am broadcast -a io.stamethyst.action.ADB_STAGE_JVM_LOG \
 *       -n io.stamethyst/.backend.diag.AdbDiagnosticsReceiver
 */
class AdbDiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DiagnosticsProcessService.ACTION_ADB_STAGE_JVM_LOG) return
        val serviceIntent = Intent(context, DiagnosticsProcessService::class.java).apply {
            action = DiagnosticsProcessService.ACTION_ADB_STAGE_JVM_LOG
        }
        context.startService(serviceIntent)
    }
}

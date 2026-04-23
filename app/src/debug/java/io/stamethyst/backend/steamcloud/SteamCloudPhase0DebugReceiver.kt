package io.stamethyst.backend.steamcloud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class SteamCloudPhase0DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, SteamCloudPhase0DebugService::class.java).apply {
            intent.extras?.let(::putExtras)
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i(TAG, "Queued Steam Cloud Phase 0 debug foreground service from adb broadcast.")
        } catch (error: Throwable) {
            val summary = error.message?.trim()?.takeIf { it.isNotEmpty() }
                ?: error.javaClass.simpleName
            SteamCloudPhase0Store.recordFailure(context.applicationContext, summary)
            Log.e(TAG, "Failed to start Steam Cloud Phase 0 debug service: $summary", error)
        }
    }

    private companion object {
        private const val TAG = "SteamCloudPhase0Rx"
    }
}

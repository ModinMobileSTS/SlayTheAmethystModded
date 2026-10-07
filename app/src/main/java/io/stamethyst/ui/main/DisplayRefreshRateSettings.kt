package io.stamethyst.ui.main

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Android has no public per-app refresh-rate settings intent; use display settings. */
internal fun openDisplayRefreshRateSettings(context: Context) {
    for (action in listOf(Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS)) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Some OEMs do not expose display settings; fall back to the settings home.
        } catch (_: SecurityException) {
            // An OEM settings activity may reject launches from third-party apps.
        }
    }
}

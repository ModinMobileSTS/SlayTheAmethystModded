package io.stamethyst

import android.app.Activity
import android.content.pm.ActivityInfo

/** Keeps the game task landscape without overriding the geometry of vendor freeform windows. */
internal object GameOrientationPolicy {
    internal fun resolveRequestedOrientation(isInMultiWindowMode: Boolean): Int {
        return if (isInMultiWindowMode) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    fun apply(activity: Activity, isInMultiWindowMode: Boolean) {
        val requestedOrientation = resolveRequestedOrientation(isInMultiWindowMode)
        if (activity.requestedOrientation != requestedOrientation) {
            activity.requestedOrientation = requestedOrientation
        }
    }
}

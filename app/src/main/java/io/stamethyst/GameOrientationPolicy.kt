package io.stamethyst

import android.app.Activity
import android.content.pm.ActivityInfo

/** Keeps the game task landscape without overriding the geometry of vendor freeform windows. */
internal object GameOrientationPolicy {
    private fun applyRequestedOrientation(activity: Activity, requestedOrientation: Int) {
        if (activity.requestedOrientation != requestedOrientation) {
            activity.requestedOrientation = requestedOrientation
        }
    }

    internal fun resolveRequestedOrientation(isInMultiWindowMode: Boolean): Int {
        return if (isInMultiWindowMode) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    fun apply(activity: Activity, isInMultiWindowMode: Boolean) {
        applyRequestedOrientation(activity, resolveRequestedOrientation(isInMultiWindowMode))
    }

    fun applyBootOverlayOrientation(activity: Activity, isInMultiWindowMode: Boolean) {
        val requestedOrientation = if (isInMultiWindowMode) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        applyRequestedOrientation(activity, requestedOrientation)
    }
}

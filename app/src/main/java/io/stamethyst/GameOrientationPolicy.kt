package io.stamethyst

import android.app.Activity
import android.content.pm.ActivityInfo

/** Keeps gameplay landscape, with a portrait Godot boot window and freeform-host ownership. */
internal object GameOrientationPolicy {
    private fun applyRequestedOrientation(activity: Activity, requestedOrientation: Int) {
        if (activity.requestedOrientation != requestedOrientation) {
            activity.requestedOrientation = requestedOrientation
        }
    }

    internal fun resolveRequestedOrientation(
        isInMultiWindowMode: Boolean,
        preparingGodotBoot: Boolean = false
    ): Int {
        return if (isInMultiWindowMode) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else if (preparingGodotBoot) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    fun apply(activity: Activity, isInMultiWindowMode: Boolean, preparingGodotBoot: Boolean = false) {
        applyRequestedOrientation(activity, resolveRequestedOrientation(isInMultiWindowMode, preparingGodotBoot))
    }

    internal fun shouldWaitForLandscape(
        width: Int,
        height: Int,
        isInMultiWindowMode: Boolean,
        preparingGodotBoot: Boolean
    ): Boolean {
        // Godot boots in portrait while the fixed logical game canvas is already landscape.
        return width < height && !isInMultiWindowMode && !preparingGodotBoot
    }

    /**
     * The SlingBreak boot overlay is a portrait minigame, so the game window is deliberately
     * flipped to portrait while the overlay is up. The game canvas itself must never be derived
     * from that transient window (see RenderSurfaceManager.resolveVirtualResolutionForViewport).
     */
    fun applyBootOverlayOrientation(activity: Activity, isInMultiWindowMode: Boolean) {
        apply(activity, isInMultiWindowMode, preparingGodotBoot = true)
    }
}

package io.stamethyst.backend.render

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.Window

/** Coordinates the display-rate request and the live value shown by the launcher. */
internal object DisplayRefreshRatePolicy {
    private const val PREFERENCES_NAME = "display_refresh_rate"
    private const val REQUESTED_REFRESH_RATE_KEY = "requested_refresh_rate_hz"

    data class Snapshot(
        val requestedRefreshRateHz: Float,
        val actualRefreshRateHz: Float,
    ) {
        val shouldShowMismatch: Boolean
            get() = requestedRefreshRateHz > 0f &&
                actualRefreshRateHz > 0f &&
                actualRefreshRateHz < requestedRefreshRateHz - RATE_COMPARISON_EPSILON
    }

    private const val RATE_COMPARISON_EPSILON = 0.01f

    fun readSnapshot(context: Context): Snapshot {
        val appContext = context.applicationContext
        val display = resolveDefaultDisplay(appContext)
        val requested = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getFloat(REQUESTED_REFRESH_RATE_KEY, 0f)
        val actual = display?.refreshRate?.takeIf { it > 0f && !it.isNaN() } ?: 0f
        return Snapshot(
            requestedRefreshRateHz = requested,
            actualRefreshRateHz = actual,
        )
    }

    /** Requests the highest refresh rate exposed by the active display for this render surface. */
    fun requestHighestRefreshRate(context: Context, surface: Surface?): Float {
        if (surface == null || !surface.isValid || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return 0f
        }
        val display = resolveDefaultDisplay(context.applicationContext) ?: return 0f
        val highestRate = highestSupportedRefreshRateHz(display)
        if (highestRate <= 0f) return 0f
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    highestRate,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            } else {
                @Suppress("DEPRECATION")
                surface.setFrameRate(highestRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        } catch (_: Throwable) {
            return 0f
        }
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(REQUESTED_REFRESH_RATE_KEY, highestRate)
            .apply()
        return highestRate
    }

    /** Requests the highest display mode for the launcher's own window. */
    fun requestHighestRefreshRate(context: Context, window: Window): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return 0f
        val display = resolveDefaultDisplay(context.applicationContext) ?: return 0f
        val mode = display.supportedModes
            ?.asSequence()
            ?.filter { it.refreshRate > 0f && !it.refreshRate.isNaN() }
            ?.maxByOrNull { it.refreshRate }
            ?: return 0f
        try {
            val attributes = window.attributes
            if (attributes.preferredDisplayModeId != mode.modeId) {
                attributes.preferredDisplayModeId = mode.modeId
                window.attributes = attributes
            }
        } catch (_: Throwable) {
            return 0f
        }
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(REQUESTED_REFRESH_RATE_KEY, mode.refreshRate)
            .apply()
        return mode.refreshRate
    }

    internal fun highestSupportedRefreshRateHz(display: Display?): Float {
        return display?.supportedModes
            ?.asSequence()
            ?.map { it.refreshRate }
            ?.filter { it > 0f && !it.isNaN() }
            ?.maxOrNull()
            ?: 0f
    }

    private fun resolveDefaultDisplay(context: Context): Display? {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        @Suppress("DEPRECATION")
        return displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
    }
}

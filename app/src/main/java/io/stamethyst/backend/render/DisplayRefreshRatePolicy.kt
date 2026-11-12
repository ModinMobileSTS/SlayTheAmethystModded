package io.stamethyst.backend.render

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.Window

/** Coordinates the display-rate request and the live value shown by the launcher. */
internal object DisplayRefreshRatePolicy {
    internal data class ModeCandidate(val id: Int, val width: Int, val height: Int, val rateHz: Float)

    /** Never change resolution merely because another resolution lists a faster mode first. */
    internal fun highestModeAtCurrentResolution(
        current: ModeCandidate?,
        supported: List<ModeCandidate>,
    ): ModeCandidate? {
        if (current == null || current.width <= 0 || current.height <= 0) return null
        return supported.asSequence()
            .filter { it.id > 0 && it.width == current.width && it.height == current.height }
            .filter { it.rateHz.isFinite() && it.rateHz > 0f }
            .maxWithOrNull(compareBy<ModeCandidate> { it.rateHz }.thenBy { it.id == current.id })
    }

    internal fun highestModeAtCurrentResolution(display: Display): Display.Mode? {
        val modes = display.supportedModes.orEmpty()
        fun Display.Mode.candidate() = ModeCandidate(modeId, physicalWidth, physicalHeight, refreshRate)
        val selected = highestModeAtCurrentResolution(display.mode?.candidate(), modes.map { it.candidate() })
        return modes.firstOrNull { it.modeId == selected?.id }
    }

    /** Shared by production and diagnostics; callers decide whether to persist request metadata. */
    internal fun applySurfaceFrameRate(surface: Surface, rateHz: Float) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "Surface.setFrameRate requires API 30" }
        check(surface.isValid) { "Display Surface unavailable" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            surface.setFrameRate(rateHz, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ALWAYS)
        } else {
            surface.setFrameRate(rateHz, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
    }

    internal fun applyWindowMode(window: Window, mode: Display.Mode) {
        val attributes = window.attributes
        if (attributes.preferredDisplayModeId != mode.modeId) {
            attributes.preferredDisplayModeId = mode.modeId
            window.attributes = attributes
        }
    }

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
            applySurfaceFrameRate(surface, highestRate)
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

    /** Requests the highest mode at the window display's current resolution. */
    fun requestHighestRefreshRate(context: Context, window: Window): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return 0f
        try {
            val display = window.decorView.display ?: resolveDefaultDisplay(context.applicationContext) ?: return 0f
            val mode = highestModeAtCurrentResolution(display) ?: return 0f
            applyWindowMode(window, mode)
            context.applicationContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(REQUESTED_REFRESH_RATE_KEY, mode.refreshRate)
                .apply()
            return mode.refreshRate
        } catch (_: Throwable) {
            return 0f
        }
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

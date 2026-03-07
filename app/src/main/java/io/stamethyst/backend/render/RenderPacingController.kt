package io.stamethyst.backend.render

import android.app.Activity
import android.os.Build
import android.view.Surface
import android.view.View

internal class RenderPacingController(
    private val activity: Activity,
    private val targetFps: Int
) {
    private var lastAppliedWindowPreference: DisplayPerformanceController.FrameRatePreference? = null
    private var lastAppliedSurfaceGeneration = -1
    private var lastAppliedSurfaceFrameRate = 0f

    var windowApplyCount = 0
        private set
    var windowSkipCount = 0
        private set
    var surfaceApplyCount = 0
        private set
    var surfaceSkipCount = 0
        private set

    fun applyWindowPreference(anchorView: View?): Boolean {
        val preference =
            DisplayPerformanceController.resolveWindowFrameRatePreference(activity, anchorView, targetFps)
                ?: run {
                    windowSkipCount++
                    return false
                }
        if (preference == lastAppliedWindowPreference) {
            windowSkipCount++
            return false
        }
        val changed = DisplayPerformanceController.applyWindowFrameRateHint(activity, preference)
        lastAppliedWindowPreference = preference
        if (changed) {
            windowApplyCount++
        } else {
            windowSkipCount++
        }
        return changed
    }

    fun applySurfacePreference(surface: Surface?, surfaceGeneration: Int): Boolean {
        if (surface == null || !surface.isValid || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            surfaceSkipCount++
            return false
        }
        val preferredFrameRate = targetFps.coerceAtLeast(1).toFloat()
        if (surfaceGeneration == lastAppliedSurfaceGeneration &&
            preferredFrameRate == lastAppliedSurfaceFrameRate
        ) {
            surfaceSkipCount++
            return false
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    preferredFrameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                )
            } else {
                surface.setFrameRate(
                    preferredFrameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                )
            }
        } catch (_: Throwable) {
            surfaceSkipCount++
            return false
        }
        lastAppliedSurfaceGeneration = surfaceGeneration
        lastAppliedSurfaceFrameRate = preferredFrameRate
        surfaceApplyCount++
        return true
    }

    fun resetSurfaceTracking() {
        lastAppliedSurfaceGeneration = -1
        lastAppliedSurfaceFrameRate = 0f
    }
}

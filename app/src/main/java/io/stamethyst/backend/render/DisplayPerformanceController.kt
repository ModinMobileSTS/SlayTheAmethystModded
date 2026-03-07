package io.stamethyst.backend.render

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import android.view.View
import kotlin.math.abs

object DisplayPerformanceController {
    private const val REFRESH_RATE_TOLERANCE = 0.6f

    fun applyWindowFrameRateHint(activity: Activity, anchorView: View?, targetFps: Int) {
        val preference = resolveFrameRatePreference(activity, anchorView, targetFps) ?: return
        val attributes = activity.window.attributes
        val refreshRateChanged = abs(attributes.preferredRefreshRate - preference.refreshRate) > 0.01f
        val modeChanged = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            attributes.preferredDisplayModeId != preference.modeId
        } else {
            false
        }
        if (!refreshRateChanged && !modeChanged) {
            return
        }
        attributes.preferredRefreshRate = preference.refreshRate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            attributes.preferredDisplayModeId = preference.modeId
        }
        activity.window.attributes = attributes
    }

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

    private fun resolveFrameRatePreference(
        activity: Activity,
        anchorView: View?,
        targetFps: Int
    ): FrameRatePreference? {
        if (targetFps <= 0) {
            return null
        }
        val display = resolveDisplay(activity, anchorView) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val currentMode = display.mode
            val candidateModes = display.supportedModes
                .filter { mode ->
                    mode.physicalWidth == currentMode.physicalWidth &&
                        mode.physicalHeight == currentMode.physicalHeight
                }
                .ifEmpty { display.supportedModes.toList() }
            val preferredMode = choosePreferredMode(candidateModes, targetFps) ?: currentMode
            return FrameRatePreference(preferredMode.refreshRate, preferredMode.modeId)
        }
        val refreshRate = display.refreshRate
        if (refreshRate <= 0f) {
            return null
        }
        return FrameRatePreference(refreshRate, 0)
    }

    private fun resolveDisplay(activity: Activity, anchorView: View?): Display? {
        val fromView = anchorView?.display
        if (fromView != null) {
            return fromView
        }
        val decorDisplay = activity.window.decorView.display
        if (decorDisplay != null) {
            return decorDisplay
        }
        val displayManager = activity.getSystemService(DisplayManager::class.java) ?: return null
        return displayManager.getDisplay(Display.DEFAULT_DISPLAY)
    }

    private fun choosePreferredMode(
        modes: List<Display.Mode>,
        targetFps: Int
    ): Display.Mode? {
        if (modes.isEmpty()) {
            return null
        }
        val targetRefreshRate = targetFps.toFloat()
        val exactMatch = modes.firstOrNull { mode ->
            abs(mode.refreshRate - targetRefreshRate) <= REFRESH_RATE_TOLERANCE
        }
        if (exactMatch != null) {
            return exactMatch
        }
        val aboveTarget = modes
            .filter { mode -> mode.refreshRate > targetRefreshRate + REFRESH_RATE_TOLERANCE }
            .minByOrNull { mode -> mode.refreshRate }
        if (aboveTarget != null) {
            return aboveTarget
        }
        return modes
            .filter { mode -> mode.refreshRate > 0f }
            .maxByOrNull { mode -> mode.refreshRate }
    }

    private data class FrameRatePreference(
        val refreshRate: Float,
        val modeId: Int
    )
}

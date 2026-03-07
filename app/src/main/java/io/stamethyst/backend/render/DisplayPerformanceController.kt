package io.stamethyst.backend.render

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import android.view.View
import kotlin.math.abs

object DisplayPerformanceController {
    internal const val REFRESH_RATE_TOLERANCE = 0.6f

    internal data class DisplayModeSpec(
        val refreshRate: Float,
        val modeId: Int,
        val physicalWidth: Int,
        val physicalHeight: Int
    )

    internal data class FrameRatePreference(
        val refreshRate: Float,
        val modeId: Int
    )

    internal fun resolveWindowFrameRatePreference(
        activity: Activity,
        anchorView: View?,
        targetFps: Int
    ): FrameRatePreference? {
        return resolveFrameRatePreference(activity, anchorView, targetFps)
    }

    internal fun applyWindowFrameRateHint(
        activity: Activity,
        preference: FrameRatePreference
    ): Boolean {
        val attributes = activity.window.attributes
        val refreshRateChanged = abs(attributes.preferredRefreshRate - preference.refreshRate) > 0.01f
        val modeChanged = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            attributes.preferredDisplayModeId != preference.modeId
        } else {
            false
        }
        if (!refreshRateChanged && !modeChanged) {
            return false
        }
        attributes.preferredRefreshRate = preference.refreshRate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            attributes.preferredDisplayModeId = preference.modeId
        }
        activity.window.attributes = attributes
        return true
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
                .map { mode ->
                    DisplayModeSpec(
                        refreshRate = mode.refreshRate,
                        modeId = mode.modeId,
                        physicalWidth = mode.physicalWidth,
                        physicalHeight = mode.physicalHeight
                    )
                }
            val preferredMode = choosePreferredMode(
                modes = candidateModes,
                currentMode = DisplayModeSpec(
                    refreshRate = currentMode.refreshRate,
                    modeId = currentMode.modeId,
                    physicalWidth = currentMode.physicalWidth,
                    physicalHeight = currentMode.physicalHeight
                ),
                targetFps = targetFps
            ) ?: return null
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

    internal fun choosePreferredMode(
        modes: List<DisplayModeSpec>,
        currentMode: DisplayModeSpec?,
        targetFps: Int
    ): DisplayModeSpec? {
        if (modes.isEmpty()) {
            return currentMode
        }
        val targetRefreshRate = targetFps.toFloat()
        val exactMatch = modes.firstOrNull { mode ->
            abs(mode.refreshRate - targetRefreshRate) <= REFRESH_RATE_TOLERANCE
        }
        if (exactMatch != null) {
            return exactMatch
        }
        return currentMode
    }
}

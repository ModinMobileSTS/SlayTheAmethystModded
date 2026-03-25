package io.stamethyst.backend.render

import android.app.Activity
import android.os.Build
import android.view.Surface
import kotlin.math.abs

internal data class DisplayModeCandidate(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRateHz: Float
)

internal data class WindowRefreshPreference(
    val preferredRefreshRateHz: Float,
    val preferredDisplayModeId: Int?
)

internal class DisplayRefreshRateController(
    private val activity: Activity,
    private val targetFpsLimit: Int
) {
    private var lastAppliedWindowRefreshRateHz = Float.NaN
    private var lastAppliedWindowModeId = Int.MIN_VALUE
    private var lastAppliedSurfaceRefreshRateHz = Float.NaN
    private var lastAppliedSurfaceIdentity = 0

    fun sync(
        inForeground: Boolean,
        hasWindowFocus: Boolean,
        surface: Surface?,
        reason: String
    ) {
        val preference =
            if (inForeground) {
                resolveWindowRefreshPreference()
            } else {
                null
            }
        applyWindowPreference(preference, inForeground, hasWindowFocus, reason)
        applySurfacePreference(surface, preference, inForeground, hasWindowFocus, reason)
    }

    @Suppress("DEPRECATION")
    private fun resolveWindowRefreshPreference(): WindowRefreshPreference? {
        if (!shouldRequestExplicitRefreshRate(targetFpsLimit)) {
            return null
        }
        val targetRefreshRateHz = targetFpsLimit.toFloat()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return WindowRefreshPreference(
                preferredRefreshRateHz = targetRefreshRateHz,
                preferredDisplayModeId = null
            )
        }
        val display = activity.windowManager.defaultDisplay
            ?: return WindowRefreshPreference(
                preferredRefreshRateHz = targetRefreshRateHz,
                preferredDisplayModeId = null
            )
        val currentMode = display.mode
        val supportedModes = display.supportedModes
            ?.map { mode ->
                DisplayModeCandidate(
                    modeId = mode.modeId,
                    width = mode.physicalWidth,
                    height = mode.physicalHeight,
                    refreshRateHz = mode.refreshRate
                )
            }
            .orEmpty()
        return resolveWindowRefreshPreference(
            targetFpsLimit = targetFpsLimit,
            currentDisplayModeId = currentMode?.modeId,
            supportedModes = supportedModes
        )
    }

    private fun applyWindowPreference(
        preference: WindowRefreshPreference?,
        inForeground: Boolean,
        hasWindowFocus: Boolean,
        reason: String
    ) {
        val desiredRefreshRateHz = preference?.preferredRefreshRateHz ?: 0f
        val desiredModeId = preference?.preferredDisplayModeId ?: 0
        val attributes = activity.window.attributes
        var changed = false
        if (!sameRefreshRate(attributes.preferredRefreshRate, desiredRefreshRateHz)) {
            attributes.preferredRefreshRate = desiredRefreshRateHz
            changed = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            attributes.preferredDisplayModeId != desiredModeId
        ) {
            attributes.preferredDisplayModeId = desiredModeId
            changed = true
        }
        if (!changed) {
            return
        }
        activity.window.attributes = attributes
        lastAppliedWindowRefreshRateHz = desiredRefreshRateHz
        lastAppliedWindowModeId = desiredModeId
        println(
            "DisplayRefreshRate: window " +
                "reason=$reason foreground=$inForeground focus=$hasWindowFocus " +
                "targetFps=$targetFpsLimit requestHz=$desiredRefreshRateHz modeId=$desiredModeId"
        )
    }

    private fun applySurfacePreference(
        surface: Surface?,
        preference: WindowRefreshPreference?,
        inForeground: Boolean,
        hasWindowFocus: Boolean,
        reason: String
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        val desiredRefreshRateHz = preference?.preferredRefreshRateHz ?: 0f
        val surfaceIdentity = if (surface != null) System.identityHashCode(surface) else 0
        if (surface == null) {
            lastAppliedSurfaceIdentity = 0
            lastAppliedSurfaceRefreshRateHz = Float.NaN
            return
        }
        if (surfaceIdentity == lastAppliedSurfaceIdentity &&
            sameRefreshRate(lastAppliedSurfaceRefreshRateHz, desiredRefreshRateHz)
        ) {
            return
        }
        try {
            surface.setFrameRate(
                desiredRefreshRateHz,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
            )
            lastAppliedSurfaceIdentity = surfaceIdentity
            lastAppliedSurfaceRefreshRateHz = desiredRefreshRateHz
            println(
                "DisplayRefreshRate: surface " +
                    "reason=$reason foreground=$inForeground focus=$hasWindowFocus " +
                    "targetFps=$targetFpsLimit requestHz=$desiredRefreshRateHz"
            )
        } catch (t: Throwable) {
            println(
                "DisplayRefreshRate: surface_failed " +
                    "reason=$reason foreground=$inForeground focus=$hasWindowFocus " +
                    "targetFps=$targetFpsLimit requestHz=$desiredRefreshRateHz " +
                    "error=${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    companion object {
        private const val BASE_HIGH_REFRESH_RATE_HZ = 60f
        private const val REFRESH_RATE_EPSILON = 0.01f

        internal fun shouldRequestExplicitRefreshRate(targetFpsLimit: Int): Boolean {
            return targetFpsLimit > BASE_HIGH_REFRESH_RATE_HZ.toInt()
        }

        internal fun resolveWindowRefreshPreference(
            targetFpsLimit: Int,
            currentDisplayModeId: Int?,
            supportedModes: List<DisplayModeCandidate>
        ): WindowRefreshPreference? {
            if (!shouldRequestExplicitRefreshRate(targetFpsLimit)) {
                return null
            }
            val targetRefreshRateHz = targetFpsLimit.toFloat()
            if (supportedModes.isEmpty()) {
                return WindowRefreshPreference(
                    preferredRefreshRateHz = targetRefreshRateHz,
                    preferredDisplayModeId = null
                )
            }
            val currentMode = currentDisplayModeId?.let { modeId ->
                supportedModes.firstOrNull { it.modeId == modeId }
            }
            val sameSizeModes =
                if (currentMode != null) {
                    supportedModes.filter { mode ->
                        mode.width == currentMode.width && mode.height == currentMode.height
                    }
                } else {
                    supportedModes
                }
            val sameSizeBest = chooseBestModeForRefreshRate(targetRefreshRateHz, sameSizeModes)
            val globalBest = chooseBestModeForRefreshRate(targetRefreshRateHz, supportedModes)
            val preferredRefreshRateHz =
                when {
                    sameSizeBest != null && sameSizeBest.refreshRateHz > BASE_HIGH_REFRESH_RATE_HZ ->
                        sameSizeBest.refreshRateHz
                    globalBest != null && globalBest.refreshRateHz > BASE_HIGH_REFRESH_RATE_HZ ->
                        globalBest.refreshRateHz
                    else -> targetRefreshRateHz
                }
            val preferredDisplayModeId =
                if (currentMode != null &&
                    sameSizeBest != null &&
                    sameSizeBest.modeId != currentMode.modeId &&
                    sameSizeBest.refreshRateHz > currentMode.refreshRateHz + REFRESH_RATE_EPSILON
                ) {
                    sameSizeBest.modeId
                } else {
                    null
                }
            return WindowRefreshPreference(
                preferredRefreshRateHz = preferredRefreshRateHz,
                preferredDisplayModeId = preferredDisplayModeId
            )
        }

        private fun chooseBestModeForRefreshRate(
            targetRefreshRateHz: Float,
            modes: List<DisplayModeCandidate>
        ): DisplayModeCandidate? {
            if (modes.isEmpty()) {
                return null
            }
            val atOrAboveTarget = modes
                .filter { mode -> mode.refreshRateHz + REFRESH_RATE_EPSILON >= targetRefreshRateHz }
                .minWithOrNull(
                    compareBy<DisplayModeCandidate> { mode ->
                        abs(mode.refreshRateHz - targetRefreshRateHz)
                    }.thenBy { mode ->
                        mode.refreshRateHz
                    }
                )
            if (atOrAboveTarget != null) {
                return atOrAboveTarget
            }
            return modes.maxByOrNull { mode -> mode.refreshRateHz }
        }

        private fun sameRefreshRate(left: Float, right: Float): Boolean {
            if (left.isNaN() && right.isNaN()) {
                return true
            }
            return abs(left - right) < REFRESH_RATE_EPSILON
        }
    }
}

package io.stamethyst.backend.render

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Surface
import kotlin.math.abs
import kotlin.math.roundToInt

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
    private val targetFpsLimit: Float
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
        val targetRefreshRateHz = resolveRequestedRefreshRateHz(targetFpsLimit)
            ?: return null
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Allow a non-seamless mode transition, while keeping game-content compatibility
                // semantics. Some vendor builds keep a higher active mode despite this vote; the
                // Swappy bridge detects that case and falls back to the software pacer.
                surface.setFrameRate(
                    desiredRefreshRateHz,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ALWAYS
                )
            } else {
                surface.setFrameRate(
                    desiredRefreshRateHz,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                )
            }
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
        private const val MAX_AUTOMATIC_TARGET_FPS = 144f
        private const val MIN_SELECTABLE_TARGET_FPS = 24f
        private const val REFRESH_RATE_EPSILON = 0.01f
        private const val REFRESH_RATE_MATCH_EPSILON_HZ = 0.5f

        /**
         * Best estimate of the refresh rate the panel will actually run at for [targetFpsLimit].
         *
         * The in-JVM LWJGL shim cannot discover this on its own: its `DisplayMode` frequency is 0
         * and `getDesktopDisplayMode()` reports a hardcoded 60Hz. The launcher owns the real value
         * because it is the side that requests the mode, so it resolves it here and hands it to the
         * game through a system property.
         */
        @Suppress("DEPRECATION")
        fun resolveExpectedActiveRefreshRateHz(context: Context, targetFpsLimit: Float): Float {
            val display = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.display
                } else {
                    val displayManager =
                        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                    displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                }
            } catch (t: Throwable) {
                null
            } ?: return 0f

            val supportedModes =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    display.supportedModes
                        ?.map { mode ->
                            DisplayModeCandidate(
                                modeId = mode.modeId,
                                width = mode.physicalWidth,
                                height = mode.physicalHeight,
                                refreshRateHz = mode.refreshRate
                            )
                        }
                        .orEmpty()
                } else {
                    emptyList()
                }
            val currentModeId =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    display.mode?.modeId
                } else {
                    null
                }
            return resolveExpectedRefreshRateHz(
                targetFpsLimit = targetFpsLimit,
                currentDisplayRefreshRateHz = display.refreshRate,
                currentDisplayModeId = currentModeId,
                supportedModes = supportedModes
            )
        }

        /**
         * Pure resolution of the expected refresh rate.
         *
         * Only rates the display actually reported are ever returned. [resolveWindowRefreshPreference]
         * falls back to the raw target when the mode list is unknown, and trusting that would let the
         * game believe a 60Hz-only panel runs at 90Hz, which in turn disables its software frame
         * limiter. Returns 0 when nothing trustworthy is known.
         */
        internal fun resolveExpectedRefreshRateHz(
            targetFpsLimit: Float,
            currentDisplayRefreshRateHz: Float,
            currentDisplayModeId: Int?,
            supportedModes: List<DisplayModeCandidate>
        ): Float {
            val currentRate = currentDisplayRefreshRateHz.takeIf { it > 0f && !it.isNaN() } ?: 0f
            if (supportedModes.isEmpty()) {
                return currentRate
            }
            val preferred = resolveWindowRefreshPreference(
                targetFpsLimit = targetFpsLimit,
                currentDisplayModeId = currentDisplayModeId,
                supportedModes = supportedModes
            )?.preferredRefreshRateHz ?: return currentRate
            // Accept the preference only if the panel really advertises that rate.
            val advertised = supportedModes.any { sameRefreshRate(it.refreshRateHz, preferred) }
            return if (advertised && preferred > 0f) preferred else currentRate
        }

        internal fun shouldRequestExplicitRefreshRate(targetFpsLimit: Float): Boolean {
            return resolveRequestedRefreshRateHz(targetFpsLimit) != null
        }

        /**
         * Chooses the highest stable FPS at or below 144 for the current panel rate.
         * Stable means one rendered frame occupies an integer number of display refresh periods.
         */
        internal fun resolveAutomaticTargetFps(currentDisplayRefreshRateHz: Float): Float {
            val refreshRateHz = currentDisplayRefreshRateHz
                .takeIf { it > 0f && !it.isNaN() }
                ?: BASE_HIGH_REFRESH_RATE_HZ
            val refreshIntervals = kotlin.math.ceil(refreshRateHz / MAX_AUTOMATIC_TARGET_FPS)
                .toInt()
                .coerceAtLeast(1)
            return refreshRateHz / refreshIntervals
        }

        internal fun resolveIdealTargetFpsOptions(currentDisplayRefreshRateHz: Float): List<Float> {
            val refreshRateHz = currentDisplayRefreshRateHz
                .takeIf { it > 0f && !it.isNaN() }
                ?: BASE_HIGH_REFRESH_RATE_HZ
            return buildList {
                var intervals = 1
                while (true) {
                    val fps = refreshRateHz / intervals
                    if (fps + REFRESH_RATE_EPSILON < MIN_SELECTABLE_TARGET_FPS) {
                        break
                    }
                    add((fps * 1000f).roundToInt() / 1000f)
                    intervals++
                }
            }
        }

        @Suppress("DEPRECATION")
        fun resolveAutomaticTargetFps(context: Context): Float {
            val display = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.display
                } else {
                    val displayManager =
                        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                    displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                }
            } catch (_: Throwable) {
                null
            }
            return resolveAutomaticTargetFps(display?.refreshRate ?: 0f)
        }

        @Suppress("DEPRECATION")
        fun resolveIdealTargetFpsOptions(context: Context): List<Float> {
            val display = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.display
                } else {
                    val displayManager =
                        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                    displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                }
            } catch (_: Throwable) {
                null
            }
            return resolveIdealTargetFpsOptions(display?.refreshRate ?: 0f)
        }

        internal fun resolveRequestedRefreshRateHz(targetFpsLimit: Float): Float? {
            if (targetFpsLimit <= 0) {
                return null
            }
            return if (targetFpsLimit < BASE_HIGH_REFRESH_RATE_HZ) {
                BASE_HIGH_REFRESH_RATE_HZ
            } else {
                targetFpsLimit
            }
        }

        internal fun resolveWindowRefreshPreference(
            targetFpsLimit: Float,
            currentDisplayModeId: Int?,
            supportedModes: List<DisplayModeCandidate>
        ): WindowRefreshPreference? {
            val targetRefreshRateHz = resolveRequestedRefreshRateHz(targetFpsLimit)
                ?: return null
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
            if (currentMode != null && isFractionalAutomaticTargetFps(targetFpsLimit)) {
                val refreshIntervals = (currentMode.refreshRateHz / targetFpsLimit)
                    .roundToInt()
                    .coerceAtLeast(1)
                if (abs(currentMode.refreshRateHz - targetFpsLimit * refreshIntervals) <=
                    REFRESH_RATE_MATCH_EPSILON_HZ
                ) {
                    return WindowRefreshPreference(
                        preferredRefreshRateHz = currentMode.refreshRateHz,
                        preferredDisplayModeId = null
                    )
                }
            }
            val sameSizeBest = chooseBestModeForRefreshRate(targetRefreshRateHz, sameSizeModes)
            val globalBest = chooseBestModeForRefreshRate(targetRefreshRateHz, supportedModes)
            val preferredRefreshRateHz =
                when {
                    targetRefreshRateHz <= BASE_HIGH_REFRESH_RATE_HZ &&
                        sameSizeBest != null -> sameSizeBest.refreshRateHz
                    targetRefreshRateHz <= BASE_HIGH_REFRESH_RATE_HZ &&
                        globalBest != null -> globalBest.refreshRateHz
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
                    shouldSwitchDisplayMode(targetRefreshRateHz, sameSizeBest)
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

        private fun shouldSwitchDisplayMode(
            targetRefreshRateHz: Float,
            mode: DisplayModeCandidate
        ): Boolean {
            return targetRefreshRateHz <= BASE_HIGH_REFRESH_RATE_HZ ||
                mode.refreshRateHz + REFRESH_RATE_EPSILON >= targetRefreshRateHz
        }

        private fun isFractionalAutomaticTargetFps(targetFpsLimit: Float): Boolean {
            return abs(targetFpsLimit - targetFpsLimit.roundToInt()) > REFRESH_RATE_EPSILON
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

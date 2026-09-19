package io.stamethyst.backend.render

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Surface
import io.stamethyst.config.LauncherConfig
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
    private val targetFpsLimit: Float,
    private val log: (String) -> Unit = { println(it) }
) {
    private val surfaceVoteState = SurfaceFrameRateVoteState()

    fun sync(
        inForeground: Boolean,
        hasWindowFocus: Boolean,
        surface: Surface?,
        surfaceGeneration: Int,
        reason: String
    ) {
        val preference =
            if (inForeground) {
                resolveWindowRefreshPreference()
            } else {
                null
            }
        applyWindowPreference(preference, inForeground, hasWindowFocus, reason)
        applySurfacePreference(
            surface, surfaceGeneration, preference, inForeground, hasWindowFocus, reason
        )
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
        // Explicit display-mode switching is only reliable below Android 12. On
        // Android 12+ the platform performs a seamless mode change from the
        // content-rate vote, and a pinned mode ID would fight that scheduler and
        // defeat its idle fallback. Below Android 12 a content-rate vote alone
        // leaves the panel on its native rate (e.g. 120Hz while rendering 60fps),
        // so pin the resolved mode to actually reach the requested rate.
        val desiredModeId = preference?.preferredDisplayModeId
            ?.takeIf { Build.VERSION.SDK_INT < Build.VERSION_CODES.S }
            ?: 0
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
        log(
            "DisplayRefreshRate: window " +
                "reason=$reason foreground=$inForeground focus=$hasWindowFocus " +
                "targetFps=$targetFpsLimit requestHz=$desiredRefreshRateHz modeId=$desiredModeId"
        )
    }

    private fun applySurfacePreference(
        surface: Surface?,
        surfaceGeneration: Int,
        preference: WindowRefreshPreference?,
        inForeground: Boolean,
        hasWindowFocus: Boolean,
        reason: String
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        val desiredRefreshRateHz = preference?.preferredRefreshRateHz ?: 0f
        if (surface == null || !surface.isValid) {
            surfaceVoteState.clear()
            return
        }
        if (!surfaceVoteState.shouldApply(surface, surfaceGeneration, desiredRefreshRateHz)
        ) {
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Do not opt into a visible mode switch for a continuously running game. The
                // platform can still apply a lightweight seamless transition when available.
                surface.setFrameRate(
                    desiredRefreshRateHz,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                )
            } else {
                surface.setFrameRate(
                    desiredRefreshRateHz,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                )
            }
            surfaceVoteState.recordApplied(surface, surfaceGeneration, desiredRefreshRateHz)
            log(
                "DisplayRefreshRate: surface " +
                    "reason=$reason foreground=$inForeground focus=$hasWindowFocus " +
                    "targetFps=$targetFpsLimit requestHz=$desiredRefreshRateHz " +
                    "surfaceGeneration=$surfaceGeneration surfaceIdentity=${System.identityHashCode(surface)}"
            )
        } catch (t: Throwable) {
            log(
                "DisplayRefreshRate: surface_failed " +
                    "reason=$reason foreground=$inForeground focus=$hasWindowFocus " +
                    "targetFps=$targetFpsLimit requestHz=$desiredRefreshRateHz " +
                    "surfaceGeneration=$surfaceGeneration " +
                    "error=${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    companion object {
        private const val BASE_HIGH_REFRESH_RATE_HZ = 60f
        private const val MIN_SELECTABLE_TARGET_FPS = 24f
        private const val REFRESH_RATE_EPSILON = 0.01f

        /**
         * Best estimate of the refresh rate the display is currently running at.
         *
         * The requested content rate is not evidence that the platform has already switched modes.
         * The launcher publishes the current Android display value through the native bridge and uses
         * this value for startup properties.
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
         * Pure resolution of the currently active refresh rate.
         *
         * The requested content rate is not evidence that the platform has already switched modes.
         * Return only the current display/mode rate so the JVM fallback pacer never runs ahead of the
         * mode transition. Returns 0 when nothing trustworthy is known.
         */
        internal fun resolveExpectedRefreshRateHz(
            targetFpsLimit: Float,
            currentDisplayRefreshRateHz: Float,
            currentDisplayModeId: Int?,
            supportedModes: List<DisplayModeCandidate>
        ): Float {
            val currentRate = currentDisplayRefreshRateHz.takeIf { it > 0f && !it.isNaN() } ?: 0f
            if (currentRate > 0f) return currentRate
            return currentDisplayModeId
                ?.let { modeId -> supportedModes.firstOrNull { it.modeId == modeId }?.refreshRateHz }
                ?.takeIf { it > 0f && !it.isNaN() }
                ?: 0f
        }

        internal fun shouldRequestExplicitRefreshRate(targetFpsLimit: Float): Boolean {
            return resolveRequestedRefreshRateHz(targetFpsLimit) != null
        }

        /**
         * Chooses the highest stable FPS for the current panel rate.
         * Stable means one rendered frame occupies an integer number of display refresh periods.
         */
        internal fun resolveAutomaticTargetFps(currentDisplayRefreshRateHz: Float): Float {
            val refreshRateHz = currentDisplayRefreshRateHz
                .takeIf { it > 0f && !it.isNaN() }
                ?: LauncherConfig.DEFAULT_TARGET_FPS.toFloat()
            return refreshRateHz
        }

        internal fun resolveIdealTargetFpsOptions(currentDisplayRefreshRateHz: Float): List<Float> {
            val refreshRateHz = currentDisplayRefreshRateHz
                .takeIf { it > 0f && !it.isNaN() }
                ?: return emptyList()
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

        internal fun includeSelectedTargetFpsOption(
            options: List<Float>,
            selectedTargetFps: Float
        ): List<Float> {
            if (selectedTargetFps <= 0f || selectedTargetFps.isNaN() || selectedTargetFps in options) {
                return options
            }
            return (options + selectedTargetFps).distinct().sortedDescending()
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
            val supportedModes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                display?.supportedModes
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
            val currentModeId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                display?.mode?.modeId
            } else {
                null
            }
            return resolveAutomaticTargetFps(
                currentDisplayRefreshRateHz = display?.refreshRate ?: 0f,
                currentDisplayModeId = currentModeId,
                supportedModes = supportedModes
            )
        }

        internal fun resolveAutomaticTargetFps(
            currentDisplayRefreshRateHz: Float,
            currentDisplayModeId: Int?,
            supportedModes: List<DisplayModeCandidate>
        ): Float {
            if (supportedModes.isEmpty()) {
                return resolveAutomaticTargetFps(currentDisplayRefreshRateHz)
            }
            val currentMode = currentDisplayModeId?.let { modeId ->
                supportedModes.firstOrNull { it.modeId == modeId }
            }
            val sameSizeModes = currentMode?.let { mode ->
                supportedModes.filter {
                    it.width == mode.width && it.height == mode.height
                }
            }.orEmpty()
            val refreshRateHz = (sameSizeModes.ifEmpty { supportedModes })
                .map { it.refreshRateHz }
                .filter { it > 0f && !it.isNaN() }
                .maxOrNull()
                ?: currentDisplayRefreshRateHz
            return resolveAutomaticTargetFps(refreshRateHz)
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
            val supportedModes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                display?.supportedModes
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
            val currentModeId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                display?.mode?.modeId
            } else {
                null
            }
            return resolveIdealTargetFpsOptions(
                currentDisplayRefreshRateHz = display?.refreshRate ?: 0f,
                currentDisplayModeId = currentModeId,
                supportedModes = supportedModes
            )
        }

        internal fun resolveIdealTargetFpsOptions(
            currentDisplayRefreshRateHz: Float,
            currentDisplayModeId: Int?,
            supportedModes: List<DisplayModeCandidate>
        ): List<Float> {
            val refreshRateHz = currentDisplayRefreshRateHz
                .takeIf { it > 0f && !it.isNaN() }
                ?: currentDisplayModeId
                    ?.let { modeId -> supportedModes.firstOrNull { it.modeId == modeId }?.refreshRateHz }
                    ?.takeIf { it > 0f && !it.isNaN() }
                ?: supportedModes
                    .map { it.refreshRateHz }
                    .filter { it > 0f && !it.isNaN() }
                    .maxOrNull()
                ?: return emptyList()
            return resolveIdealTargetFpsOptions(refreshRateHz)
        }

        internal fun resolveRequestedRefreshRateHz(targetFpsLimit: Float): Float? {
            if (targetFpsLimit <= 0) {
                return null
            }
            return targetFpsLimit
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
            val sameSizeModes = if (currentMode != null) {
                supportedModes.filter { mode ->
                    mode.width == currentMode.width && mode.height == currentMode.height
                }
            } else {
                supportedModes
            }
            val bestMode = chooseBestModeForRefreshRate(targetRefreshRateHz, sameSizeModes)
            val preferredDisplayModeId = bestMode
                ?.takeIf { shouldSwitchDisplayMode(targetRefreshRateHz, it) }
                ?.modeId
            return WindowRefreshPreference(
                preferredRefreshRateHz = targetRefreshRateHz,
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

        private fun chooseBestModeForRefreshRate(
            targetRefreshRateHz: Float,
            modes: List<DisplayModeCandidate>
        ): DisplayModeCandidate? {
            if (modes.isEmpty()) {
                return null
            }
            val atOrAboveTarget = modes
                .filter { mode ->
                    mode.refreshRateHz + REFRESH_RATE_EPSILON >= targetRefreshRateHz
                }
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

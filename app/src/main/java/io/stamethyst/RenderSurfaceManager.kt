package io.stamethyst

import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.stamethyst.backend.render.DisplayConfigSync
import io.stamethyst.backend.render.DisplayRefreshRateController
import io.stamethyst.backend.render.FullscreenCanvasSize
import io.stamethyst.backend.render.FullscreenCanvasResolution
import io.stamethyst.backend.render.ForegroundResyncScheduler
import io.stamethyst.backend.render.RenderSurfaceState
import io.stamethyst.backend.render.VirtualResolutionPolicy
import io.stamethyst.backend.render.VirtualResolutionMode
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge

internal data class RenderViewportInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    fun maxInset(): Int = maxOf(left, top, right, bottom)
}

internal data class RenderViewportLayout(
    val width: Int,
    val height: Int,
    val leftMargin: Int,
    val topMargin: Int,
    val rightMargin: Int,
    val bottomMargin: Int
)

internal enum class HorizontalCropSide {
    LEFT,
    RIGHT
}

/**
 * Coordinates render surface hosting and size synchronization.
 */
class RenderSurfaceManager(
    private val activity: StsGameActivity,
    private val renderScale: Float,
    private val targetFpsLimit: Int,
    useTextureViewSurface: Boolean,
    private val virtualResolutionMode: VirtualResolutionMode,
    private val avoidDisplayCutout: Boolean,
    private val cropScreenBottom: Boolean,
    private val isSoftKeyboardSessionActive: () -> Boolean,
    private val onSurfaceReady: () -> Unit,
    private val onTextureFrameUpdate: (Long) -> Unit
) {
    private val state = RenderSurfaceState()
    private val resyncScheduler = ForegroundResyncScheduler()
    private val renderHost: RenderSurfaceHost = if (useTextureViewSurface) {
        TextureViewHost(activity)
    } else {
        SurfaceViewHost(activity)
    }
    private val refreshRateController = DisplayRefreshRateController(activity, targetFpsLimit)
    private var pendingSurfaceReadyCallback = false
    private var lastResyncReasonSummary = "init"
    private var resyncApplyCount = 0
    private var resyncSkipCount = 0
    private var renderRoot: FrameLayout? = null
    private var lastWindowInsets: WindowInsetsCompat? = null
    private var lastWindowInsetsRotation: Int? = null
    private var postBootSurfaceSoftRefreshScheduled = false
    private var postBootSurfaceSoftRefreshCompleted = false
    private var postBootSurfaceSoftRefreshAttempts = 0
    private var postBootSurfaceSoftRefreshDeferrals = 0
    private var postBootSurfaceSoftRefreshInFlight = false
    private var lastMultiWindowMode: Boolean? = null
    private var windowModeSurfaceRefreshInFlight = false
    private var windowModeSurfaceRefreshTargetMultiWindow: Boolean? = null
    private var forceNextBufferApply = false
    private var forceNextWindowSizeDispatch = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayManager: DisplayManager? = null
    private var lastDisplayRotation: Int? = null
    private var bootOverlayActive = true
    private var fullscreenVirtualResolution: io.stamethyst.backend.render.VirtualResolution? = null
    private var startupVirtualResolution: io.stamethyst.backend.render.VirtualResolution? = null

    private val foregroundResyncRunnable = Runnable {
        applyQueuedResync()
    }
    private val windowConfigurationResyncRetryRunnable = Runnable {
        if (::renderView.isInitialized) {
            applyViewportLayout()
            state.rememberPhysicalSize(renderView.width, renderView.height)
        }
        forceNextBufferApply = true
        requestForegroundResync("window_configuration_retry")
    }
    private val postBootSurfaceSoftRefreshRunnable = Runnable {
        performPostBootSurfaceSoftRefresh()
    }
    private val windowModeSurfaceRefreshRunnable = Runnable {
        performWindowModeSurfaceRefresh()
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            handleDisplayChanged(displayId)
        }
    }

    lateinit var renderView: View
        private set

    val surfaceBufferWidth: Int
        get() = state.surfaceBufferWidth

    val surfaceBufferHeight: Int
        get() = state.surfaceBufferHeight

    val bridgeSurfaceReady: Boolean
        get() = state.bridgeSurfaceReady

    fun getLastTextureFrameTimestampNs(): Long = state.lastTextureFrameTimestampNs

    fun requestRenderViewFocus() {
        if (::renderView.isInitialized) {
            renderView.requestFocus()
        }
    }

    fun getRenderViewWidth(): Int {
        return if (::renderView.isInitialized) renderView.width else 0
    }

    fun getRenderViewHeight(): Int {
        return if (::renderView.isInitialized) renderView.height else 0
    }

    fun init(root: FrameLayout) {
        renderRoot = root
        lastMultiWindowMode = activity.isInMultiWindowMode
        renderHost.attach(root, object : RenderSurfaceHost.Callbacks {
            override fun onSurfaceAvailable(surfaceGeneration: Int, width: Int, height: Int) {
                state.markSurfaceAvailable(surfaceGeneration, width, height)
                syncPreferredRefreshRate("surface_available")
                connectBridgeSurfaceIfNeeded()
                pendingSurfaceReadyCallback = true
                requestForegroundResync("surface_available")
            }

            override fun onSurfaceSizeChanged(surfaceGeneration: Int, width: Int, height: Int) {
                if (renderHost.usesTextureView) {
                    state.rememberPhysicalSize(width, height)
                } else if (::renderView.isInitialized) {
                    state.rememberPhysicalSize(renderView.width, renderView.height)
                }
                syncPreferredRefreshRate("surface_size_changed")
                pendingSurfaceReadyCallback = true
                requestForegroundResync("surface_size_changed")
            }

            override fun onSurfaceDestroyed(surfaceGeneration: Int) {
                pendingSurfaceReadyCallback = false
                disconnectBridgeSurfaceIfNeeded()
                state.markSurfaceDestroyed()
                syncPreferredRefreshRate("surface_destroyed")
            }

            override fun onTextureFrameUpdated(timestampNs: Long) {
                state.updateTextureFrameTimestamp(timestampNs)
                onTextureFrameUpdate(timestampNs)
            }
        })
        renderView = renderHost.renderView
        renderView.isFocusable = true
        renderView.isFocusableInTouchMode = true
        state.rememberPhysicalSize(renderView.width, renderView.height)
        registerDisplayRotationListener()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            lastWindowInsets = insets
            lastWindowInsetsRotation = resolveDisplayRotation()
            applyViewportLayout(insets)
            insets
        }
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left == oldLeft &&
                top == oldTop &&
                right == oldRight &&
                bottom == oldBottom
            ) {
                return@addOnLayoutChangeListener
            }
            applyViewportLayout()
        }
        ViewCompat.requestApplyInsets(root)
        renderView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = (right - left).coerceAtLeast(0)
            val height = (bottom - top).coerceAtLeast(0)
            val oldWidth = (oldRight - oldLeft).coerceAtLeast(0)
            val oldHeight = (oldBottom - oldTop).coerceAtLeast(0)
            if (width == oldWidth && height == oldHeight) {
                return@addOnLayoutChangeListener
            }
            val changed = state.rememberPhysicalSize(width, height)
            if (changed) {
                requestForegroundResync("layout")
            }
        }
        if (renderHost.currentSurface != null || pendingSurfaceReadyCallback) {
            requestForegroundResync("attach_complete")
        }
    }

    fun onDestroy() {
        if (::renderView.isInitialized) {
            renderView.removeCallbacks(foregroundResyncRunnable)
            renderView.removeCallbacks(windowConfigurationResyncRetryRunnable)
            renderView.removeCallbacks(postBootSurfaceSoftRefreshRunnable)
            renderView.removeCallbacks(windowModeSurfaceRefreshRunnable)
        }
        unregisterDisplayRotationListener()
        refreshRateController.sync(
            inForeground = false,
            hasWindowFocus = false,
            surface = renderHost.currentSurface,
            reason = "destroy"
        )
        renderRoot?.let { ViewCompat.setOnApplyWindowInsetsListener(it, null) }
        renderRoot = null
        lastWindowInsets = null
        lastWindowInsetsRotation = null
        disconnectBridgeSurfaceIfNeeded()
        renderHost.release()
    }

    fun onForegroundChanged(foreground: Boolean) {
        state.markForeground(foreground)
        syncPreferredRefreshRate(if (foreground) "resume" else "pause")
        if (foreground) {
            requestForegroundResync("resume")
        }
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        state.markWindowFocus(hasFocus)
        syncPreferredRefreshRate(if (hasFocus) "focus_gain" else "focus_loss")
        if (hasFocus) {
            requestForegroundResync("focus")
        }
    }

    fun resyncAfterForeground() {
        requestForegroundResync("legacy_foreground")
    }

    fun onWindowConfigurationChanged(reason: String) {
        if (!::renderView.isInitialized) {
            return
        }
        val wasInMultiWindow = lastMultiWindowMode
        val isInMultiWindow = activity.isInMultiWindowMode
        lastMultiWindowMode = isInMultiWindow
        applyImmersiveMode()
        renderRoot?.let { root ->
            ViewCompat.requestApplyInsets(root)
            applyViewportLayout()
        }
        state.rememberPhysicalSize(renderView.width, renderView.height)
        forceNextBufferApply = true
        requestForegroundResync(reason)
        if (wasInMultiWindow != null && wasInMultiWindow != isInMultiWindow) {
            scheduleWindowModeSurfaceRefresh(isInMultiWindow)
        }
        renderView.removeCallbacks(windowConfigurationResyncRetryRunnable)
        renderView.postDelayed(
            windowConfigurationResyncRetryRunnable,
            SURFACE_VIEW_CONFIGURATION_RETRY_MS
        )
    }

    fun requestForegroundResync(reason: String) {
        if (!::renderView.isInitialized) {
            return
        }
        if (
            shouldSkipSurfaceViewSteadyStateResync(
                useTextureViewSurface = renderHost.usesTextureView,
                pendingSurfaceReadyCallback = pendingSurfaceReadyCallback,
                bridgeSurfaceReady = state.bridgeSurfaceReady,
                hasCurrentSurface = renderHost.currentSurface != null,
                reason = reason
            )
        ) {
            println(
                "RenderSurfaceResync: backend=SurfaceView reason=$reason skipped=steady_state"
            )
            return
        }
        val scheduled = resyncScheduler.request(reason)
        val delayMs = resolveForegroundResyncDelayMs(renderHost.usesTextureView, reason)
        if (delayMs > 0L) {
            renderView.removeCallbacks(foregroundResyncRunnable)
            renderView.postDelayed(foregroundResyncRunnable, delayMs)
            println(
                "RenderSurfaceResync: backend=SurfaceView reason=$reason " +
                    "scheduled=$scheduled delayMs=$delayMs mode=debounced"
            )
            return
        }
        if (scheduled) {
            renderView.post(foregroundResyncRunnable)
        }
    }

    fun updateWindowSize() {
        if (!::renderView.isInitialized) {
            return
        }
        dispatchWindowSize(buildApplyPlan(renderView.width, renderView.height))
    }

    fun schedulePostBootSurfaceSoftRefresh(triggerReason: String) {
        if (renderHost.usesTextureView ||
            postBootSurfaceSoftRefreshCompleted ||
            postBootSurfaceSoftRefreshInFlight ||
            !::renderView.isInitialized
        ) {
            return
        }
        if (postBootSurfaceSoftRefreshScheduled) {
            println(
                "RenderSurfaceRefresh: backend=SurfaceView trigger=$triggerReason " +
                    "scheduled=false reason=already_scheduled"
            )
            return
        }
        postBootSurfaceSoftRefreshScheduled = true
        renderView.removeCallbacks(postBootSurfaceSoftRefreshRunnable)
        renderView.postDelayed(
            postBootSurfaceSoftRefreshRunnable,
            POST_BOOT_SURFACE_SOFT_REFRESH_DELAY_MS
        )
        println(
            "RenderSurfaceRefresh: backend=SurfaceView trigger=$triggerReason " +
                "scheduled=true delayMs=$POST_BOOT_SURFACE_SOFT_REFRESH_DELAY_MS"
        )
    }

    fun syncDisplayConfigToSurfaceSize() {
        val windowWidth = resolveVirtualWidth()
        val windowHeight = resolveVirtualHeight()
        try {
            DisplayConfigSync.syncToCurrentResolution(
                activity,
                windowWidth,
                windowHeight,
                targetFpsLimit
            )
            println(
                "RenderSurfaceDisplayConfig: synced " +
                    "virtual=${windowWidth}x${windowHeight}, " +
                    "physical=${resolvePhysicalWidth()}x${resolvePhysicalHeight()}, " +
                    "targetFps=$targetFpsLimit"
            )
        } catch (t: Throwable) {
            println(
                "RenderSurfaceDisplayConfig: sync_failed " +
                    "virtual=${windowWidth}x${windowHeight}, " +
                    "physical=${resolvePhysicalWidth()}x${resolvePhysicalHeight()}, " +
                    "targetFps=$targetFpsLimit, " +
                    "error=${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    fun logRenderInfo() {
        println(buildDiagnostics(prefix = "snapshot"))
    }

    fun resolvePhysicalWidth(): Int {
        return state.resolvePhysicalWidth(if (::renderView.isInitialized) renderView.width else 0)
    }

    fun resolvePhysicalHeight(): Int {
        return state.resolvePhysicalHeight(if (::renderView.isInitialized) renderView.height else 0)
    }

    fun resolveVirtualWidth(): Int {
        return resolveVirtualResolution().width
    }

    fun resolveVirtualHeight(): Int {
        return resolveVirtualResolution().height
    }

    private fun applyQueuedResync() {
        if (!::renderView.isInitialized || activity.isFinishing || activity.isDestroyed) {
            return
        }
        val reasons = resyncScheduler.drain()
        val reasonSummary = reasons.joinToString("+").ifBlank { "unspecified" }
        lastResyncReasonSummary = reasonSummary
        val forceBufferApply = forceNextBufferApply
        forceNextBufferApply = false
        val forceWindowSizeDispatch = forceNextWindowSizeDispatch
        forceNextWindowSizeDispatch = false
        val plan = buildApplyPlan(
            viewWidth = renderView.width,
            viewHeight = renderView.height,
            forceBufferApply = forceBufferApply,
            forceWindowSizeDispatch = forceWindowSizeDispatch
        )
        var anyApplied = false

        if (plan.shouldApplyBufferSize && renderHost.currentSurface != null) {
            val result = renderHost.applyBufferSize(
                width = plan.bufferWidth,
                height = plan.bufferHeight,
                surfaceGeneration = renderHost.surfaceGeneration
            )
            state.recordBufferApply(
                plan = plan,
                applied = result.handled,
                incrementsHolderResize = result.changedSurfaceGeometry && !renderHost.usesTextureView
            )
            logBufferApply(plan, result)
            anyApplied = anyApplied || result.handled
        } else {
            state.recordBufferApply(plan = plan, applied = false, incrementsHolderResize = false)
            if (plan.shouldApplyBufferSize) {
                println(
                    "RenderSurfaceBuffer: " +
                        "backend=${if (renderHost.usesTextureView) "TextureView" else "SurfaceView"}, " +
                        "request=${plan.bufferWidth}x${plan.bufferHeight}, " +
                        "handled=false, geometryChanged=false, " +
                        "detail=surface_unavailable bridgeReady=${state.bridgeSurfaceReady}"
                )
            }
        }

        connectBridgeSurfaceIfNeeded()
        if (dispatchWindowSize(plan)) {
            anyApplied = true
        }

        if (pendingSurfaceReadyCallback && state.bridgeSurfaceReady) {
            pendingSurfaceReadyCallback = false
            onSurfaceReady()
        }

        if (anyApplied) {
            resyncApplyCount++
        } else {
            resyncSkipCount++
        }
        println(buildDiagnostics(prefix = "resync"))
    }

    private fun connectBridgeSurfaceIfNeeded() {
        if (state.bridgeSurfaceReady) {
            return
        }
        val surface = renderHost.currentSurface ?: return
        JREUtils.setupBridgeWindow(surface)
        state.markBridgeSurfaceReady(true)
    }

    private fun disconnectBridgeSurfaceIfNeeded() {
        if (!state.bridgeSurfaceReady) {
            return
        }
        try {
            JREUtils.releaseBridgeWindow()
        } catch (_: Throwable) {
        }
        state.markBridgeSurfaceReady(false)
    }

    private fun dispatchWindowSize(plan: RenderSurfaceState.ApplyPlan): Boolean {
        CallbackBridge.physicalWidth = plan.physicalWidth
        CallbackBridge.physicalHeight = plan.physicalHeight
        if (!plan.shouldDispatchWindowSize) {
            state.recordWindowSizeDispatch(plan, dispatched = false)
            return false
        }
        println(
            "RenderSurfaceDispatch: " +
                "view=${if (::renderView.isInitialized) renderView.width else 0}x" +
                "${if (::renderView.isInitialized) renderView.height else 0}, " +
                "physical=${plan.physicalWidth}x${plan.physicalHeight}, " +
                "buffer=${plan.bufferWidth}x${plan.bufferHeight}, " +
                "window=${plan.windowWidth}x${plan.windowHeight}"
        )
        CallbackBridge.windowWidth = plan.windowWidth
        CallbackBridge.windowHeight = plan.windowHeight
        state.recordWindowSizeDispatch(plan, dispatched = true)
        return true
    }

    private fun syncPreferredRefreshRate(reason: String) {
        refreshRateController.sync(
            inForeground = state.isForeground,
            hasWindowFocus = state.hasWindowFocus,
            surface = renderHost.currentSurface,
            reason = reason
        )
    }

    private fun performPostBootSurfaceSoftRefresh() {
        postBootSurfaceSoftRefreshScheduled = false
        if (renderHost.usesTextureView ||
            postBootSurfaceSoftRefreshCompleted ||
            postBootSurfaceSoftRefreshInFlight ||
            !::renderView.isInitialized ||
            activity.isFinishing ||
            activity.isDestroyed
        ) {
            return
        }
        val blocker = resolvePostBootSurfaceSoftRefreshBlocker(
            inForeground = state.isForeground,
            hasWindowFocus = state.hasWindowFocus,
            hasCurrentSurface = renderHost.currentSurface != null,
            softKeyboardSessionActive = isSoftKeyboardSessionActive.invoke()
        )
        if (blocker != null) {
            retryPostBootSurfaceSoftRefresh(blocker)
            return
        }
        postBootSurfaceSoftRefreshDeferrals = 0
        postBootSurfaceSoftRefreshAttempts++
        postBootSurfaceSoftRefreshInFlight = true
        println(
            "RenderSurfaceRefresh: backend=SurfaceView action=soft_visibility_start " +
                "attempt=$postBootSurfaceSoftRefreshAttempts generation=${renderHost.surfaceGeneration}"
        )
        renderView.visibility = View.INVISIBLE
        renderView.invalidate()
        renderView.postOnAnimationDelayed({
            completePostBootSurfaceSoftRefresh()
        }, POST_BOOT_SURFACE_SOFT_REFRESH_HIDDEN_MS)
    }

    private fun scheduleWindowModeSurfaceRefresh(targetMultiWindow: Boolean) {
        if (renderHost.usesTextureView ||
            windowModeSurfaceRefreshInFlight
        ) {
            return
        }
        windowModeSurfaceRefreshTargetMultiWindow = targetMultiWindow
        renderView.removeCallbacks(windowModeSurfaceRefreshRunnable)
        renderView.postDelayed(
            windowModeSurfaceRefreshRunnable,
            WINDOW_MODE_SURFACE_REFRESH_DELAY_MS
        )
    }

    private fun performWindowModeSurfaceRefresh() {
        val targetMultiWindow = windowModeSurfaceRefreshTargetMultiWindow
        windowModeSurfaceRefreshTargetMultiWindow = null
        if (renderHost.usesTextureView ||
            windowModeSurfaceRefreshInFlight ||
            targetMultiWindow == null ||
            activity.isInMultiWindowMode != targetMultiWindow ||
            !::renderView.isInitialized ||
            activity.isFinishing ||
            activity.isDestroyed
        ) {
            return
        }
        windowModeSurfaceRefreshInFlight = true
        renderView.visibility = View.INVISIBLE
        renderView.invalidate()
        renderView.postOnAnimationDelayed({
            windowModeSurfaceRefreshInFlight = false
            if (!::renderView.isInitialized || activity.isFinishing || activity.isDestroyed) {
                return@postOnAnimationDelayed
            }
            renderView.visibility = View.VISIBLE
            renderView.requestLayout()
            renderView.invalidate()
            forceNextBufferApply = true
            forceNextWindowSizeDispatch = true
            requestForegroundResync("window_mode_surface_refresh")
        }, WINDOW_MODE_SURFACE_REFRESH_HIDDEN_MS)
    }

    private fun completePostBootSurfaceSoftRefresh() {
        postBootSurfaceSoftRefreshInFlight = false
        if (!::renderView.isInitialized || activity.isFinishing || activity.isDestroyed) {
            return
        }
        renderView.visibility = View.VISIBLE
        renderView.requestLayout()
        renderView.invalidate()
        postBootSurfaceSoftRefreshCompleted = true
        println(
            "RenderSurfaceRefresh: backend=SurfaceView action=soft_visibility_complete " +
                "attempt=$postBootSurfaceSoftRefreshAttempts generation=${renderHost.surfaceGeneration}"
        )
        requestForegroundResync("post_boot_surface_soft_refresh")
    }

    private fun retryPostBootSurfaceSoftRefresh(reason: String) {
        if (postBootSurfaceSoftRefreshCompleted ||
            postBootSurfaceSoftRefreshDeferrals >= MAX_POST_BOOT_SURFACE_SOFT_REFRESH_ATTEMPTS
        ) {
            println(
                "RenderSurfaceRefresh: backend=SurfaceView action=aborted " +
                    "attempts=$postBootSurfaceSoftRefreshAttempts deferrals=$postBootSurfaceSoftRefreshDeferrals reason=$reason"
            )
            return
        }
        postBootSurfaceSoftRefreshDeferrals++
        postBootSurfaceSoftRefreshScheduled = true
        renderView.removeCallbacks(postBootSurfaceSoftRefreshRunnable)
        renderView.postDelayed(
            postBootSurfaceSoftRefreshRunnable,
            POST_BOOT_SURFACE_SOFT_REFRESH_RETRY_DELAY_MS
        )
        println(
            "RenderSurfaceRefresh: backend=SurfaceView action=retry " +
                "attempt=$postBootSurfaceSoftRefreshAttempts deferral=$postBootSurfaceSoftRefreshDeferrals reason=$reason " +
                "delayMs=$POST_BOOT_SURFACE_SOFT_REFRESH_RETRY_DELAY_MS"
        )
    }

    private fun buildDiagnostics(prefix: String): String {
        return buildString(256) {
            append("RenderSurfaceDiag: ")
            append(prefix)
            append(", backend=")
            append(if (renderHost.usesTextureView) "TextureView" else "SurfaceView")
            append(", reasons=")
            append(lastResyncReasonSummary)
            append(", surfaceGeneration=")
            append(renderHost.surfaceGeneration)
            append(", bridgeReady=")
            append(state.bridgeSurfaceReady)
            append(", foreground=")
            append(state.isForeground)
            append(", focus=")
            append(state.hasWindowFocus)
            append(", physical=")
            append(resolvePhysicalWidth())
            append("x")
            append(resolvePhysicalHeight())
            append(", virtual=")
            append(resolveVirtualWidth())
            append("x")
            append(resolveVirtualHeight())
            append(", effectiveScale=")
            append(resolveVirtualResolution().effectiveScale)
            append(", buffer=")
            append(state.surfaceBufferWidth)
            append("x")
            append(state.surfaceBufferHeight)
            append(", resyncApplied=")
            append(resyncApplyCount)
            append(", resyncSkipped=")
            append(resyncSkipCount)
            append(", holderResizeCount=")
            append(state.holderResizeCount)
            append(", bufferApply=")
            append(state.bufferApplyCount)
            append("/")
            append(state.bufferSkipCount)
            append(", windowDispatch=")
            append(state.windowSizeDispatchCount)
            append("/")
            append(state.windowSizeSkipCount)
        }
    }

    private fun buildApplyPlan(
        viewWidth: Int,
        viewHeight: Int,
        forceBufferApply: Boolean = false,
        forceWindowSizeDispatch: Boolean = false
    ): RenderSurfaceState.ApplyPlan {
        val virtualResolution = resolveCurrentViewportVirtualResolution()
        val plan = if (forceBufferApply) {
            state.buildForcedApplyPlan(
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                virtualWidth = virtualResolution.width,
                virtualHeight = virtualResolution.height
            )
        } else {
            state.buildApplyPlan(
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                virtualWidth = virtualResolution.width,
                virtualHeight = virtualResolution.height
            )
        }
        return if (forceWindowSizeDispatch) {
            plan.copy(shouldDispatchWindowSize = true)
        } else {
            plan
        }
    }

    private fun resolveFullscreenVirtualResolution(): io.stamethyst.backend.render.VirtualResolution {
        fullscreenVirtualResolution?.let { return it }
        val fullscreenCanvas = FullscreenCanvasResolution.resolve(activity)
        return VirtualResolutionPolicy.resolve(
            physicalWidth = fullscreenCanvas.width,
            physicalHeight = fullscreenCanvas.height,
            renderScale = renderScale,
            mode = virtualResolutionMode
        ).also { fullscreenVirtualResolution = it }
    }

    private fun resolveVirtualResolution(): io.stamethyst.backend.render.VirtualResolution {
        return resolveCurrentViewportVirtualResolution()
    }

    private fun resolveVirtualResolutionForViewport(
        rootWidth: Int,
        rootHeight: Int,
        cropInsets: RenderViewportInsets,
        lockResolution: Boolean = true
    ): io.stamethyst.backend.render.VirtualResolution {
        startupVirtualResolution?.let { return it }
        if (!avoidDisplayCutout && !cropScreenBottom) {
            return resolveFullscreenVirtualResolution().also { startupVirtualResolution = it }
        }
        val canvasSize = resolveViewportCanvasSize(rootWidth, rootHeight, cropInsets)
        return VirtualResolutionPolicy.resolve(
            physicalWidth = canvasSize.width,
            physicalHeight = canvasSize.height,
            renderScale = renderScale,
            mode = virtualResolutionMode
        ).also {
            if (lockResolution) {
                startupVirtualResolution = it
            }
        }
    }

    private fun resolveCurrentViewportVirtualResolution(): io.stamethyst.backend.render.VirtualResolution {
        val root = renderRoot
        if (root == null || root.width <= 0 || root.height <= 0) {
            return startupVirtualResolution ?: resolveFullscreenVirtualResolution()
        }
        val insets = currentWindowInsets()
        val cropInsets = resolveViewportCropInsets(insets)
        return resolveVirtualResolutionForViewport(
            rootWidth = root.width,
            rootHeight = root.height,
            cropInsets = cropInsets,
            lockResolution = !avoidDisplayCutout || insets != null
        )
    }

    private fun resolveViewportLayoutMode(): VirtualResolutionMode = VirtualResolutionMode.FULLSCREEN_FILL

    private fun logBufferApply(
        plan: RenderSurfaceState.ApplyPlan,
        result: RenderSurfaceHost.BufferSizeApplyResult
    ) {
        println(
            "RenderSurfaceBuffer: " +
                "backend=${if (renderHost.usesTextureView) "TextureView" else "SurfaceView"}, " +
                "request=${plan.bufferWidth}x${plan.bufferHeight}, " +
                "handled=${result.handled}, " +
                "geometryChanged=${result.changedSurfaceGeometry}, " +
                "detail=${result.detail}"
        )
    }

    fun applyImmersiveMode() {
        applyDisplayCutoutMode()
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        applyLegacyImmersiveMode()
        renderRoot?.let { ViewCompat.requestApplyInsets(it) }
    }

    fun setBootOverlayActive(active: Boolean) {
        if (bootOverlayActive == active) {
            return
        }
        bootOverlayActive = active
        applyImmersiveMode()
        applyViewportLayout()
    }

    @Suppress("DEPRECATION")
    private fun applyLegacyImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return
        }
        activity.window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun applyViewportLayout(insets: WindowInsetsCompat? = null) {
        if (!::renderView.isInitialized) {
            return
        }
        val root = renderRoot ?: return
        val rootWidth = root.width
        val rootHeight = root.height
        if (rootWidth <= 0 || rootHeight <= 0) {
            return
        }
        if (shouldDeferPortraitLandscapeTransition(
                rootWidth = rootWidth,
                rootHeight = rootHeight,
                requestedOrientation = activity.requestedOrientation,
                multiWindow = activity.isInMultiWindowMode
            )
        ) {
            return
        }
        val resolvedInsets = insets ?: currentWindowInsets()
        val cropInsets = resolveViewportCropInsets(resolvedInsets)
        val virtualResolution = resolveVirtualResolutionForViewport(
            rootWidth = rootWidth,
            rootHeight = rootHeight,
            cropInsets = cropInsets,
            lockResolution = !avoidDisplayCutout || resolvedInsets != null
        )
        val layout = resolveFixedVirtualViewportLayout(
            rootWidth = rootWidth,
            rootHeight = rootHeight,
            cropInsets = cropInsets,
            virtualWidth = virtualResolution.width,
            virtualHeight = virtualResolution.height
        ) ?: return
        val params = (renderView.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                layout.width,
                layout.height
            )
        if (params.width == layout.width &&
            params.height == layout.height &&
            params.leftMargin == layout.leftMargin &&
            params.topMargin == layout.topMargin &&
            params.rightMargin == layout.rightMargin &&
            params.bottomMargin == layout.bottomMargin
        ) {
            return
        }
        params.width = layout.width
        params.height = layout.height
        params.gravity = Gravity.TOP or Gravity.START
        params.leftMargin = layout.leftMargin
        params.topMargin = layout.topMargin
        params.rightMargin = layout.rightMargin
        params.bottomMargin = layout.bottomMargin
        renderView.layoutParams = params
        println(
            "RenderSurfaceLayout: " +
                "root=${rootWidth}x${rootHeight}, " +
                "crop=${cropInsets.left},${cropInsets.top},${cropInsets.right},${cropInsets.bottom}, " +
                "viewport=${layout.width}x${layout.height}, " +
                "margins=${layout.leftMargin},${layout.topMargin},${layout.rightMargin},${layout.bottomMargin}"
        )
        requestForegroundResync("viewport_layout")
    }

    private fun currentWindowInsets(): WindowInsetsCompat? {
        return if (
            shouldUseCachedWindowInsets(
                cachedRotation = lastWindowInsetsRotation,
                currentRotation = resolveDisplayRotation()
            )
        ) {
            lastWindowInsets
        } else {
            null
        }
    }

    private fun registerDisplayRotationListener() {
        lastDisplayRotation = resolveDisplayRotation()
        val manager = activity.getSystemService(DisplayManager::class.java)
        displayManager = manager
        manager?.registerDisplayListener(displayListener, mainHandler)
    }

    private fun unregisterDisplayRotationListener() {
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        lastDisplayRotation = null
    }

    private fun handleDisplayChanged(displayId: Int) {
        val rotation = resolveDisplayRotation()
        if (rotation == lastDisplayRotation) {
            return
        }
        lastDisplayRotation = rotation
        // Insets are expressed in window coordinates, so they must not survive a rotation.
        lastWindowInsets = null
        lastWindowInsetsRotation = null
        if (!::renderView.isInitialized) {
            return
        }
        println("RenderSurfaceDisplay: displayChanged id=$displayId rotation=$rotation")
        renderView.post {
            if (!::renderView.isInitialized || activity.isFinishing || activity.isDestroyed) {
                return@post
            }
            renderRoot?.let { ViewCompat.requestApplyInsets(it) }
            state.rememberPhysicalSize(renderView.width, renderView.height)
            forceNextBufferApply = true
            requestForegroundResync("display_rotation")
        }
    }

    private fun resolveScreenBottomCropInsets(insets: WindowInsetsCompat?): RenderViewportInsets {
        if (!cropScreenBottom) {
            return RenderViewportInsets()
        }
        val gestureInsets = if (insets != null) {
            resolveGestureInsets(insets)
        } else {
            RenderViewportInsets()
        }
        val cameraInsets = if (insets != null) {
            resolveCameraAvoidanceInsets(insets)
        } else {
            RenderViewportInsets()
        }
        return resolveScreenBottomCropInsets(
            cropScreenBottom = true,
            gestureInsets = gestureInsets,
            cameraInsets = cameraInsets,
            fallbackInset = resolveStatusBarHeightPx()
        )
    }

    private fun resolveViewportCropInsets(insets: WindowInsetsCompat?): RenderViewportInsets {
        val screenBottomCropInsets = resolveScreenBottomCropInsets(insets)
        val displayCutoutAvoidanceInsets = if (
            insets != null &&
            shouldApplyManualDisplayCutoutAvoidance(avoidDisplayCutout)
        ) {
            resolveDisplayCutoutInsets(insets)
        } else {
            RenderViewportInsets()
        }
        return mergeViewportInsets(screenBottomCropInsets, displayCutoutAvoidanceInsets)
    }

    @Suppress("DEPRECATION")
    private fun resolveDisplayRotation(): Int {
        return try {
            activity.windowManager.defaultDisplay.rotation
        } catch (_: Throwable) {
            0
        }
    }

    private fun resolveGestureInsets(insets: WindowInsetsCompat): RenderViewportInsets {
        val navigationInsets = insets.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.navigationBars()
        )
        val systemGestureInsets = insets.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.systemGestures()
        )
        val mandatoryGestureInsets = insets.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.mandatorySystemGestures()
        )
        val tappableInsets = insets.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.tappableElement()
        )
        return RenderViewportInsets(
            left = maxOf(
                navigationInsets.left,
                systemGestureInsets.left,
                mandatoryGestureInsets.left,
                tappableInsets.left
            ),
            top = maxOf(
                navigationInsets.top,
                systemGestureInsets.top,
                mandatoryGestureInsets.top,
                tappableInsets.top
            ),
            right = maxOf(
                navigationInsets.right,
                systemGestureInsets.right,
                mandatoryGestureInsets.right,
                tappableInsets.right
            ),
            bottom = maxOf(
                navigationInsets.bottom,
                systemGestureInsets.bottom,
                mandatoryGestureInsets.bottom,
                tappableInsets.bottom
            )
        )
    }

    private fun resolveCameraAvoidanceInsets(insets: WindowInsetsCompat): RenderViewportInsets {
        val statusAndCutoutInsets = insets.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
        )
        var left = statusAndCutoutInsets.left
        var top = statusAndCutoutInsets.top
        var right = statusAndCutoutInsets.right
        var bottom = statusAndCutoutInsets.bottom

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val cutout = activity.window.decorView.rootWindowInsets?.displayCutout
            if (cutout != null) {
                left = maxOf(left, cutout.safeInsetLeft)
                top = maxOf(top, cutout.safeInsetTop)
                right = maxOf(right, cutout.safeInsetRight)
                bottom = maxOf(bottom, cutout.safeInsetBottom)
            }
        }

        return RenderViewportInsets(left = left, top = top, right = right, bottom = bottom)
    }

    private fun resolveDisplayCutoutInsets(insets: WindowInsetsCompat): RenderViewportInsets {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            return RenderViewportInsets()
        }
        val cutoutInsets = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout())
        var left = cutoutInsets.left
        var top = cutoutInsets.top
        var right = cutoutInsets.right
        var bottom = cutoutInsets.bottom
        val cutout = activity.window.decorView.rootWindowInsets?.displayCutout
        if (cutout != null) {
            left = maxOf(left, cutout.safeInsetLeft)
            top = maxOf(top, cutout.safeInsetTop)
            right = maxOf(right, cutout.safeInsetRight)
            bottom = maxOf(bottom, cutout.safeInsetBottom)
        }
        return RenderViewportInsets(left = left, top = top, right = right, bottom = bottom)
    }

    private fun resolveStatusBarHeightPx(): Int {
        val resourceId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId == 0) {
            return 0
        }
        return activity.resources.getDimensionPixelSize(resourceId)
    }

    private fun applyDisplayCutoutMode() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            return
        }
        val attributes = activity.window.attributes
        val targetMode = resolveDisplayCutoutMode(
            avoidDisplayCutout = avoidDisplayCutout,
            bootOverlayActive = bootOverlayActive
        )
        if (attributes.layoutInDisplayCutoutMode == targetMode) {
            return
        }
        attributes.layoutInDisplayCutoutMode = targetMode
        activity.window.attributes = attributes
    }

    companion object {
        private const val SURFACE_VIEW_STARTUP_RESYNC_DEBOUNCE_MS = 16L
        private const val SURFACE_VIEW_STABLE_RESYNC_DEBOUNCE_MS = 48L
        private const val SURFACE_VIEW_CONFIGURATION_RETRY_MS = 160L
        private const val POST_BOOT_SURFACE_SOFT_REFRESH_DELAY_MS = 220L
        private const val POST_BOOT_SURFACE_SOFT_REFRESH_HIDDEN_MS = 32L
        private const val POST_BOOT_SURFACE_SOFT_REFRESH_RETRY_DELAY_MS = 160L
        private const val MAX_POST_BOOT_SURFACE_SOFT_REFRESH_ATTEMPTS = 3
        private const val WINDOW_MODE_SURFACE_REFRESH_DELAY_MS = 420L
        private const val WINDOW_MODE_SURFACE_REFRESH_HIDDEN_MS = 32L

        internal fun shouldDeferPortraitLandscapeTransition(
            rootWidth: Int,
            rootHeight: Int,
            requestedOrientation: Int,
            multiWindow: Boolean
        ): Boolean {
            if (multiWindow || rootWidth >= rootHeight) {
                return false
            }
            return requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ||
                requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        internal fun resolveViewportLayout(
            rootWidth: Int,
            rootHeight: Int,
            cropInsets: RenderViewportInsets,
            virtualResolutionMode: VirtualResolutionMode
        ): RenderViewportLayout? {
            if (rootWidth <= 0 || rootHeight <= 0) {
                return null
            }
            val leftCrop = cropInsets.left.coerceAtLeast(0)
            val topCrop = cropInsets.top.coerceAtLeast(0)
            val rightCrop = cropInsets.right.coerceAtLeast(0)
            val bottomCrop = cropInsets.bottom.coerceAtLeast(0)
            val availableWidth = (rootWidth - leftCrop - rightCrop).coerceAtLeast(1)
            val availableHeight = (rootHeight - topCrop - bottomCrop).coerceAtLeast(1)
            val viewportSize = VirtualResolutionPolicy.resolveViewportSize(
                availableWidth = availableWidth,
                availableHeight = availableHeight,
                mode = virtualResolutionMode
            )
            val remainingHorizontal = (availableWidth - viewportSize.width).coerceAtLeast(0)
            val remainingVertical = (availableHeight - viewportSize.height).coerceAtLeast(0)
            val centeredLeftMargin = remainingHorizontal / 2
            val centeredTopMargin = remainingVertical / 2
            return RenderViewportLayout(
                width = viewportSize.width,
                height = viewportSize.height,
                leftMargin = leftCrop + centeredLeftMargin,
                topMargin = topCrop + centeredTopMargin,
                rightMargin = rightCrop + remainingHorizontal - centeredLeftMargin,
                bottomMargin = bottomCrop + remainingVertical - centeredTopMargin
            )
        }

        internal fun resolveFixedVirtualViewportLayout(
            rootWidth: Int,
            rootHeight: Int,
            cropInsets: RenderViewportInsets,
            virtualWidth: Int,
            virtualHeight: Int
        ): RenderViewportLayout? {
            if (rootWidth <= 0 || rootHeight <= 0) {
                return null
            }
            val leftCrop = cropInsets.left.coerceAtLeast(0)
            val topCrop = cropInsets.top.coerceAtLeast(0)
            val rightCrop = cropInsets.right.coerceAtLeast(0)
            val bottomCrop = cropInsets.bottom.coerceAtLeast(0)
            val availableWidth = (rootWidth - leftCrop - rightCrop).coerceAtLeast(1)
            val availableHeight = (rootHeight - topCrop - bottomCrop).coerceAtLeast(1)
            val safeVirtualWidth = virtualWidth.coerceAtLeast(1)
            val safeVirtualHeight = virtualHeight.coerceAtLeast(1)
            val scale = minOf(
                availableWidth.toFloat() / safeVirtualWidth,
                availableHeight.toFloat() / safeVirtualHeight
            )
            val width = (safeVirtualWidth * scale).toInt().coerceAtLeast(1)
            val height = (safeVirtualHeight * scale).toInt().coerceAtLeast(1)
            val remainingHorizontal = (availableWidth - width).coerceAtLeast(0)
            val remainingVertical = (availableHeight - height).coerceAtLeast(0)
            val centeredLeftMargin = remainingHorizontal / 2
            val centeredTopMargin = remainingVertical / 2
            return RenderViewportLayout(
                width = width,
                height = height,
                leftMargin = leftCrop + centeredLeftMargin,
                topMargin = topCrop + centeredTopMargin,
                rightMargin = rightCrop + remainingHorizontal - centeredLeftMargin,
                bottomMargin = bottomCrop + remainingVertical - centeredTopMargin
            )
        }

        internal fun resolveViewportCanvasSize(
            rootWidth: Int,
            rootHeight: Int,
            cropInsets: RenderViewportInsets
        ): FullscreenCanvasSize {
            return FullscreenCanvasSize(
                width = (
                    rootWidth - cropInsets.left.coerceAtLeast(0) -
                        cropInsets.right.coerceAtLeast(0)
                    ).coerceAtLeast(1),
                height = (
                    rootHeight - cropInsets.top.coerceAtLeast(0) -
                        cropInsets.bottom.coerceAtLeast(0)
                    ).coerceAtLeast(1)
            )
        }

        internal fun resolveScreenBottomCropInsets(
            cropScreenBottom: Boolean,
            gestureInsets: RenderViewportInsets,
            cameraInsets: RenderViewportInsets,
            fallbackInset: Int
        ): RenderViewportInsets {
            if (!cropScreenBottom) {
                return RenderViewportInsets()
            }
            val cropSide = resolveScreenBottomCropSide(gestureInsets, cameraInsets)
            val selectedGestureInset = when (cropSide) {
                HorizontalCropSide.LEFT -> gestureInsets.left
                HorizontalCropSide.RIGHT -> gestureInsets.right
            }
            val fallbackCrop = if (
                selectedGestureInset == 0 &&
                cameraInsets.maxInset() == 0
            ) {
                fallbackInset.coerceAtLeast(0)
            } else {
                0
            }
            val cropPx = maxOf(
                selectedGestureInset.coerceAtLeast(0),
                cameraInsets.maxInset().coerceAtLeast(0),
                fallbackCrop
            )
            return when (cropSide) {
                HorizontalCropSide.LEFT -> RenderViewportInsets(left = cropPx)
                HorizontalCropSide.RIGHT -> RenderViewportInsets(right = cropPx)
            }
        }

        private fun resolveScreenBottomCropSide(
            gestureInsets: RenderViewportInsets,
            cameraInsets: RenderViewportInsets
        ): HorizontalCropSide {
            return when {
                cameraInsets.left > cameraInsets.right -> HorizontalCropSide.RIGHT
                cameraInsets.right > cameraInsets.left -> HorizontalCropSide.LEFT
                gestureInsets.left > gestureInsets.right -> HorizontalCropSide.LEFT
                gestureInsets.right > gestureInsets.left -> HorizontalCropSide.RIGHT
                else -> HorizontalCropSide.RIGHT
            }
        }

        internal fun resolveDisplayCutoutMode(
            avoidDisplayCutout: Boolean,
            bootOverlayActive: Boolean
        ): Int {
            return if (bootOverlayActive || !avoidDisplayCutout) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }

        internal fun shouldApplyManualDisplayCutoutAvoidance(avoidDisplayCutout: Boolean): Boolean {
            return avoidDisplayCutout
        }

        internal fun shouldUseCachedWindowInsets(
            cachedRotation: Int?,
            currentRotation: Int
        ): Boolean {
            return cachedRotation == currentRotation
        }

        internal fun mergeViewportInsets(
            first: RenderViewportInsets,
            second: RenderViewportInsets
        ): RenderViewportInsets {
            return RenderViewportInsets(
                left = maxOf(first.left, second.left),
                top = maxOf(first.top, second.top),
                right = maxOf(first.right, second.right),
                bottom = maxOf(first.bottom, second.bottom)
            )
        }

        internal fun resolveForegroundResyncDelayMs(
            useTextureViewSurface: Boolean,
            reason: String
        ): Long {
            if (useTextureViewSurface) {
                return 0L
            }
            return when (reason) {
                "surface_available",
                "surface_size_changed",
                "attach_complete" -> SURFACE_VIEW_STARTUP_RESYNC_DEBOUNCE_MS

                "layout",
                "right_crop",
                "viewport_layout",
                "resume",
                "focus",
                "legacy_foreground",
                "window_configuration",
                "multi_window_mode",
                "display_rotation",
                "window_configuration_retry" -> SURFACE_VIEW_STABLE_RESYNC_DEBOUNCE_MS

                else -> 0L
            }
        }

        internal fun shouldSkipSurfaceViewSteadyStateResync(
            useTextureViewSurface: Boolean,
            pendingSurfaceReadyCallback: Boolean,
            bridgeSurfaceReady: Boolean,
            hasCurrentSurface: Boolean,
            reason: String
        ): Boolean {
            if (useTextureViewSurface ||
                pendingSurfaceReadyCallback ||
                !bridgeSurfaceReady ||
                !hasCurrentSurface
            ) {
                return false
            }
            return reason == "resume" || reason == "focus" || reason == "legacy_foreground"
        }

        internal fun resolvePostBootSurfaceSoftRefreshBlocker(
            inForeground: Boolean,
            hasWindowFocus: Boolean,
            hasCurrentSurface: Boolean,
            softKeyboardSessionActive: Boolean
        ): String? {
            if (!inForeground || !hasWindowFocus) {
                return "not_ready_foreground"
            }
            if (!hasCurrentSurface) {
                return "surface_unavailable"
            }
            if (softKeyboardSessionActive) {
                return "ime_active"
            }
            return null
        }
    }
}

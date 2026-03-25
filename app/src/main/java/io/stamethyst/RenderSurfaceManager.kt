package io.stamethyst

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
import io.stamethyst.backend.render.ForegroundResyncScheduler
import io.stamethyst.backend.render.RenderSurfaceState
import io.stamethyst.backend.render.VirtualResolutionPolicy
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge

/**
 * Coordinates render surface hosting and size synchronization.
 */
class RenderSurfaceManager(
    private val activity: StsGameActivity,
    private val renderScale: Float,
    private val targetFpsLimit: Int,
    useTextureViewSurface: Boolean,
    private val avoidDisplayCutout: Boolean,
    private val cropScreenBottom: Boolean,
    private val onSurfaceReady: () -> Unit,
    private val onSurfaceDestroyed: () -> Unit,
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

    private val foregroundResyncRunnable = Runnable {
        applyQueuedResync()
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
                onSurfaceDestroyed()
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
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            applyRightSideCropInsets(insets)
            insets
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
        }
        refreshRateController.sync(
            inForeground = false,
            hasWindowFocus = false,
            surface = renderHost.currentSurface,
            reason = "destroy"
        )
        renderRoot?.let { ViewCompat.setOnApplyWindowInsetsListener(it, null) }
        renderRoot = null
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

    fun requestForegroundResync(reason: String) {
        if (!::renderView.isInitialized) {
            return
        }
        if (resyncScheduler.request(reason)) {
            renderView.post(foregroundResyncRunnable)
        }
    }

    fun updateWindowSize() {
        if (!::renderView.isInitialized) {
            return
        }
        dispatchWindowSize(buildApplyPlan(renderView.width, renderView.height))
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
        val plan = buildApplyPlan(renderView.width, renderView.height)
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
        CallbackBridge.physicalWidth = plan.physicalWidth
        CallbackBridge.physicalHeight = plan.physicalHeight
        CallbackBridge.windowWidth = plan.windowWidth
        CallbackBridge.windowHeight = plan.windowHeight
        CallbackBridge.sendUpdateWindowSize(plan.windowWidth, plan.windowHeight)
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

    private fun buildApplyPlan(viewWidth: Int, viewHeight: Int): RenderSurfaceState.ApplyPlan {
        return state.buildApplyPlan(
            viewWidth = viewWidth,
            viewHeight = viewHeight
        )
    }

    private fun resolveVirtualResolution() = VirtualResolutionPolicy.resolve(
        physicalWidth = resolvePhysicalWidth(),
        physicalHeight = resolvePhysicalHeight(),
        renderScale = renderScale
    )

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
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        renderRoot?.let { ViewCompat.requestApplyInsets(it) }
    }

    private fun applyRightSideCropInsets(insets: WindowInsetsCompat) {
        if (!::renderView.isInitialized) {
            return
        }
        val targetRightCropPx = resolveRightSideCropPx(insets)
        val params = (renderView.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        if (params.leftMargin == 0 &&
            params.topMargin == 0 &&
            params.rightMargin == targetRightCropPx &&
            params.bottomMargin == 0
        ) {
            return
        }
        params.width = FrameLayout.LayoutParams.MATCH_PARENT
        params.height = FrameLayout.LayoutParams.MATCH_PARENT
        params.gravity = Gravity.TOP or Gravity.START
        params.leftMargin = 0
        params.topMargin = 0
        params.rightMargin = targetRightCropPx
        params.bottomMargin = 0
        renderView.layoutParams = params
        requestForegroundResync("right_crop")
    }

    private data class EdgeInsets(
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0
    )

    private fun resolveRightSideCropPx(insets: WindowInsetsCompat): Int {
        if (!cropScreenBottom) {
            return 0
        }
        val gestureInsets = resolveGestureInsets(insets)
        val cameraInsets = resolveCameraAvoidanceInsets(insets)
        val cameraInset = maxOf(
            cameraInsets.left,
            cameraInsets.top,
            cameraInsets.right,
            cameraInsets.bottom
        )
        return maxOf(gestureInsets.right, cameraInset)
    }

    private fun resolveGestureInsets(insets: WindowInsetsCompat): EdgeInsets {
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
        return EdgeInsets(
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

    private fun resolveCameraAvoidanceInsets(insets: WindowInsetsCompat): EdgeInsets {
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

        if (left == 0 && top == 0 && right == 0 && bottom == 0) {
            val fallbackInset = resolveStatusBarHeightPx()
            right = fallbackInset
        }

        return EdgeInsets(left = left, top = top, right = right, bottom = bottom)
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
        val targetMode = if (avoidDisplayCutout) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (attributes.layoutInDisplayCutoutMode == targetMode) {
            return
        }
        attributes.layoutInDisplayCutoutMode = targetMode
        activity.window.attributes = attributes
    }
}

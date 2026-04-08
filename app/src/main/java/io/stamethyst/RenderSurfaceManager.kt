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
import io.stamethyst.backend.render.VirtualResolutionMode
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
    private val virtualResolutionMode: VirtualResolutionMode,
    private val avoidDisplayCutout: Boolean,
    private val cropScreenBottom: Boolean,
    private val isSoftKeyboardSessionActive: () -> Boolean,
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
    private var lastWindowInsets: WindowInsetsCompat? = null
    private var postBootSurfaceSoftRefreshScheduled = false
    private var postBootSurfaceSoftRefreshCompleted = false
    private var postBootSurfaceSoftRefreshAttempts = 0
    private var postBootSurfaceSoftRefreshDeferrals = 0
    private var postBootSurfaceSoftRefreshInFlight = false

    private val foregroundResyncRunnable = Runnable {
        applyQueuedResync()
    }
    private val postBootSurfaceSoftRefreshRunnable = Runnable {
        performPostBootSurfaceSoftRefresh()
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
            lastWindowInsets = insets
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
            renderView.removeCallbacks(postBootSurfaceSoftRefreshRunnable)
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

    private fun buildApplyPlan(viewWidth: Int, viewHeight: Int): RenderSurfaceState.ApplyPlan {
        return state.buildApplyPlan(
            viewWidth = viewWidth,
            viewHeight = viewHeight
        )
    }

    private fun resolveVirtualResolution() = VirtualResolutionPolicy.resolve(
        physicalWidth = resolvePhysicalWidth(),
        physicalHeight = resolvePhysicalHeight(),
        renderScale = renderScale,
        mode = virtualResolutionMode
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

    private fun applyViewportLayout(insets: WindowInsetsCompat? = lastWindowInsets) {
        if (!::renderView.isInitialized) {
            return
        }
        val root = renderRoot ?: return
        val rootWidth = root.width
        val rootHeight = root.height
        if (rootWidth <= 0 || rootHeight <= 0) {
            return
        }
        val targetRightCropPx = resolveRightSideCropPx(insets)
        val availableWidth = (rootWidth - targetRightCropPx).coerceAtLeast(1)
        val availableHeight = rootHeight.coerceAtLeast(1)
        val viewportSize = VirtualResolutionPolicy.resolveViewportSize(
            availableWidth = availableWidth,
            availableHeight = availableHeight,
            mode = virtualResolutionMode
        )
        val remainingHorizontal = (availableWidth - viewportSize.width).coerceAtLeast(0)
        val remainingVertical = (availableHeight - viewportSize.height).coerceAtLeast(0)
        val leftMargin = remainingHorizontal / 2
        val topMargin = remainingVertical / 2
        val extraRightMargin = remainingHorizontal - leftMargin
        val bottomMargin = remainingVertical - topMargin
        val params = (renderView.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                viewportSize.width,
                viewportSize.height
            )
        if (params.width == viewportSize.width &&
            params.height == viewportSize.height &&
            params.leftMargin == leftMargin &&
            params.topMargin == topMargin &&
            params.rightMargin == targetRightCropPx + extraRightMargin &&
            params.bottomMargin == bottomMargin
        ) {
            return
        }
        params.width = viewportSize.width
        params.height = viewportSize.height
        params.gravity = Gravity.TOP or Gravity.START
        params.leftMargin = leftMargin
        params.topMargin = topMargin
        params.rightMargin = targetRightCropPx + extraRightMargin
        params.bottomMargin = bottomMargin
        renderView.layoutParams = params
        requestForegroundResync("viewport_layout")
    }

    private data class EdgeInsets(
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0
    )

    private fun resolveRightSideCropPx(insets: WindowInsetsCompat?): Int {
        if (!cropScreenBottom) {
            return 0
        }
        val safeInsets = insets ?: return 0
        val gestureInsets = resolveGestureInsets(safeInsets)
        val cameraInsets = resolveCameraAvoidanceInsets(safeInsets)
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

    companion object {
        private const val SURFACE_VIEW_STARTUP_RESYNC_DEBOUNCE_MS = 16L
        private const val SURFACE_VIEW_STABLE_RESYNC_DEBOUNCE_MS = 48L
        private const val POST_BOOT_SURFACE_SOFT_REFRESH_DELAY_MS = 220L
        private const val POST_BOOT_SURFACE_SOFT_REFRESH_HIDDEN_MS = 32L
        private const val POST_BOOT_SURFACE_SOFT_REFRESH_RETRY_DELAY_MS = 160L
        private const val MAX_POST_BOOT_SURFACE_SOFT_REFRESH_ATTEMPTS = 3

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
                "legacy_foreground" -> SURFACE_VIEW_STABLE_RESYNC_DEBOUNCE_MS

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

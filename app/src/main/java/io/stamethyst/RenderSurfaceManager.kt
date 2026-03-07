package io.stamethyst

import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.stamethyst.backend.render.DisplayConfigSync
import io.stamethyst.backend.render.ForegroundResyncScheduler
import io.stamethyst.backend.render.RenderPacingController
import io.stamethyst.backend.render.RenderSurfaceState
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge

/**
 * Coordinates render surface hosting, size synchronization, and frame pacing.
 */
class RenderSurfaceManager(
    private val activity: StsGameActivity,
    renderScale: Float,
    private val targetFps: Int,
    useTextureViewSurface: Boolean,
    private val onSurfaceReady: () -> Unit,
    private val onSurfaceDestroyed: () -> Unit,
    private val onTextureFrameUpdate: (Long) -> Unit
) {
    private val state = RenderSurfaceState(renderScale)
    private val resyncScheduler = ForegroundResyncScheduler()
    private val pacingController = RenderPacingController(activity, targetFps)
    private val renderHost: RenderSurfaceHost = if (useTextureViewSurface) {
        TextureViewHost(activity)
    } else {
        SurfaceViewHost(activity)
    }
    private var pendingSurfaceReadyCallback = false
    private var lastResyncReasonSummary = "init"
    private var resyncApplyCount = 0
    private var resyncSkipCount = 0

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
        renderHost.attach(root, object : RenderSurfaceHost.Callbacks {
            override fun onSurfaceAvailable(surfaceGeneration: Int, width: Int, height: Int) {
                state.markSurfaceAvailable(surfaceGeneration, width, height)
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
                pendingSurfaceReadyCallback = true
                requestForegroundResync("surface_size_changed")
            }

            override fun onSurfaceDestroyed(surfaceGeneration: Int) {
                pendingSurfaceReadyCallback = false
                disconnectBridgeSurfaceIfNeeded()
                pacingController.resetSurfaceTracking()
                state.markSurfaceDestroyed()
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
        disconnectBridgeSurfaceIfNeeded()
        renderHost.release()
    }

    fun onForegroundChanged(foreground: Boolean) {
        state.markForeground(foreground)
        if (foreground) {
            requestForegroundResync("resume")
        }
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        state.markWindowFocus(hasFocus)
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
        dispatchWindowSize(state.buildApplyPlan(renderView.width, renderView.height))
    }

    fun syncDisplayConfigToSurfaceSize() {
        val windowWidth = CallbackBridge.windowWidth.coerceAtLeast(1)
        val windowHeight = CallbackBridge.windowHeight.coerceAtLeast(1)
        try {
            DisplayConfigSync.syncToCurrentResolution(activity, windowWidth, windowHeight, targetFps)
        } catch (_: Throwable) {
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

    private fun applyQueuedResync() {
        if (!::renderView.isInitialized || activity.isFinishing || activity.isDestroyed) {
            return
        }
        val reasons = resyncScheduler.drain()
        val reasonSummary = reasons.joinToString("+").ifBlank { "unspecified" }
        lastResyncReasonSummary = reasonSummary
        val plan = state.buildApplyPlan(renderView.width, renderView.height)
        var anyApplied = false

        val surface = renderHost.currentSurface
        if (plan.shouldApplyBufferSize && surface != null) {
            val applied = renderHost.applyBufferSize(
                width = plan.bufferWidth,
                height = plan.bufferHeight,
                surfaceGeneration = renderHost.surfaceGeneration
            )
            state.recordBufferApply(
                plan = plan,
                applied = applied,
                incrementsHolderResize = !renderHost.usesTextureView
            )
            anyApplied = anyApplied || applied
        } else {
            state.recordBufferApply(plan = plan, applied = false, incrementsHolderResize = false)
        }

        if (pacingController.applyWindowPreference(renderView)) {
            anyApplied = true
        }
        if (surface != null && pacingController.applySurfacePreference(surface, renderHost.surfaceGeneration)) {
            anyApplied = true
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
        CallbackBridge.physicalWidth = plan.physicalWidth
        CallbackBridge.physicalHeight = plan.physicalHeight
        CallbackBridge.windowWidth = plan.windowWidth
        CallbackBridge.windowHeight = plan.windowHeight
        CallbackBridge.sendUpdateWindowSize(plan.windowWidth, plan.windowHeight)
        state.recordWindowSizeDispatch(plan, dispatched = true)
        return true
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
            append(", windowHint=")
            append(pacingController.windowApplyCount)
            append("/")
            append(pacingController.windowSkipCount)
            append(", surfaceHint=")
            append(pacingController.surfaceApplyCount)
            append("/")
            append(pacingController.surfaceSkipCount)
        }
    }

    fun applyImmersiveMode() {
        applyDisplayCutoutMode()
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun applyDisplayCutoutMode() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            return
        }
        val attributes = activity.window.attributes
        if (attributes.layoutInDisplayCutoutMode ==
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        ) {
            return
        }
        attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        activity.window.attributes = attributes
    }
}

package io.stamethyst

import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.stamethyst.backend.render.DisplayConfigSync
import io.stamethyst.backend.render.DisplayPerformanceController
import io.stamethyst.config.LauncherConfig
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge
import kotlin.math.roundToInt

/**
 * Manages render surface (SurfaceView or TextureView), window sizing, and display configuration.
 */
class RenderSurfaceManager(
    private val activity: StsGameActivity,
    private val renderScale: Float,
    private val targetFps: Int,
    private val useTextureViewSurface: Boolean,
    private val onSurfaceReady: () -> Unit,
    private val onSurfaceDestroyed: () -> Unit,
    private val onTextureFrameUpdate: (Long) -> Unit
) {
    private var surfaceView: SurfaceView? = null
    private var textureView: TextureView? = null
    private var textureSurface: Surface? = null

    lateinit var renderView: View
        private set

    var surfaceBufferWidth = 0
        private set
    var surfaceBufferHeight = 0
        private set
    private var lastPhysicalWidth = 0
    private var lastPhysicalHeight = 0

    @Volatile
    var bridgeSurfaceReady = false
        private set

    @Volatile
    private var lastTextureFrameTimestampNs = 0L

    fun getLastTextureFrameTimestampNs(): Long = lastTextureFrameTimestampNs

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
        val renderLayoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        if (useTextureViewSurface) {
            val view = TextureView(activity)
            view.isOpaque = true
            textureView = view
            renderView = view
            root.addView(view, renderLayoutParams)
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    rememberPhysicalSize(width, height)
                    applyTextureBufferSize(surface)
                    releaseTextureSurfaceIfNeeded()
                    textureSurface = Surface(surface)
                    reapplyWindowFrameRateHint()
                    applyRenderSurfaceFrameRateHint(textureSurface)
                    JREUtils.setupBridgeWindow(textureSurface)
                    bridgeSurfaceReady = true
                    updateWindowSize()
                    onSurfaceReady()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    rememberPhysicalSize(width, height)
                    applyTextureBufferSize(surface)
                    reapplyWindowFrameRateHint()
                    applyRenderSurfaceFrameRateHint(textureSurface)
                    updateWindowSize()
                    onSurfaceReady()
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    bridgeSurfaceReady = false
                    JREUtils.releaseBridgeWindow()
                    releaseTextureSurfaceIfNeeded()
                    surfaceBufferWidth = 0
                    surfaceBufferHeight = 0
                    lastPhysicalWidth = 0
                    lastPhysicalHeight = 0
                    onSurfaceDestroyed()
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    lastTextureFrameTimestampNs = surface.timestamp
                    onTextureFrameUpdate(lastTextureFrameTimestampNs)
                }
            }
        } else {
            val view = SurfaceView(activity)
            surfaceView = view
            renderView = view
            root.addView(view, renderLayoutParams)
            view.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    val frame = holder.surfaceFrame
                    if (frame != null) {
                        rememberPhysicalSize(frame.width(), frame.height())
                    }
                    reapplySurfaceViewConfiguration(holder)
                    JREUtils.setupBridgeWindow(holder.surface)
                    bridgeSurfaceReady = true
                    updateWindowSize()
                    onSurfaceReady()
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    surfaceBufferWidth = width.coerceAtLeast(1)
                    surfaceBufferHeight = height.coerceAtLeast(1)
                    rememberPhysicalSizeFromView()
                    reapplyWindowFrameRateHint()
                    applyRenderSurfaceFrameRateHint(holder.surface)
                    updateWindowSize()
                    onSurfaceReady()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    bridgeSurfaceReady = false
                    JREUtils.releaseBridgeWindow()
                    surfaceBufferWidth = 0
                    surfaceBufferHeight = 0
                    lastPhysicalWidth = 0
                    lastPhysicalHeight = 0
                    onSurfaceDestroyed()
                }
            })
        }

        renderView.isFocusable = true
        renderView.isFocusableInTouchMode = true
        renderView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = (right - left).coerceAtLeast(0)
            val height = (bottom - top).coerceAtLeast(0)
            val oldWidth = (oldRight - oldLeft).coerceAtLeast(0)
            val oldHeight = (oldBottom - oldTop).coerceAtLeast(0)
            if (width == oldWidth && height == oldHeight) {
                return@addOnLayoutChangeListener
            }
            rememberPhysicalSize(width, height)
            if (!useTextureViewSurface) {
                reapplySurfaceViewConfiguration()
            } else {
                reapplyWindowFrameRateHint()
                applyRenderSurfaceFrameRateHint(textureSurface)
            }
            updateWindowSize()
        }
    }

    fun onDestroy() {
        if (useTextureViewSurface) {
            try {
                JREUtils.releaseBridgeWindow()
            } catch (_: Throwable) {}
        }
        releaseTextureSurfaceIfNeeded()
        bridgeSurfaceReady = false
    }

    fun updateWindowSize() {
        val physicalWidth = resolvePhysicalWidth()
        val physicalHeight = resolvePhysicalHeight()
        val windowWidth = (physicalWidth * renderScale).roundToInt().coerceAtLeast(1)
        val windowHeight = (physicalHeight * renderScale).roundToInt().coerceAtLeast(1)

        CallbackBridge.physicalWidth = physicalWidth
        CallbackBridge.physicalHeight = physicalHeight
        CallbackBridge.windowWidth = windowWidth
        CallbackBridge.windowHeight = windowHeight
        CallbackBridge.sendUpdateWindowSize(windowWidth, windowHeight)
    }

    fun resyncAfterForeground() {
        if (useTextureViewSurface) {
            reapplyTextureBufferSizeFromView()
            reapplyWindowFrameRateHint()
            applyRenderSurfaceFrameRateHint(textureSurface)
        } else {
            reapplySurfaceViewConfiguration()
        }
        updateWindowSize()

        if (!::renderView.isInitialized) return

        renderView.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            if (useTextureViewSurface) {
                reapplyTextureBufferSizeFromView()
                reapplyWindowFrameRateHint()
                applyRenderSurfaceFrameRateHint(textureSurface)
            } else {
                reapplySurfaceViewConfiguration()
            }
            updateWindowSize()
        }
    }

    fun syncDisplayConfigToSurfaceSize() {
        val windowWidth = CallbackBridge.windowWidth.coerceAtLeast(1)
        val windowHeight = CallbackBridge.windowHeight.coerceAtLeast(1)
        try {
            DisplayConfigSync.syncToCurrentResolution(activity, windowWidth, windowHeight, targetFps)
        } catch (_: Throwable) {}
    }

    fun logRenderInfo() {}

    fun resolvePhysicalWidth(): Int = resolveRawPhysicalWidth()

    fun resolvePhysicalHeight(): Int = resolveRawPhysicalHeight()

    private fun resolveRawPhysicalWidth(): Int {
        val viewWidth = if (::renderView.isInitialized) renderView.width else 0
        return when {
            viewWidth > 0 -> viewWidth
            lastPhysicalWidth > 0 -> lastPhysicalWidth
            surfaceBufferWidth > 0 -> surfaceBufferWidth
            else -> 1
        }
    }

    private fun resolveRawPhysicalHeight(): Int {
        val viewHeight = if (::renderView.isInitialized) renderView.height else 0
        return when {
            viewHeight > 0 -> viewHeight
            lastPhysicalHeight > 0 -> lastPhysicalHeight
            surfaceBufferHeight > 0 -> surfaceBufferHeight
            else -> 1
        }
    }

    private fun applyTextureBufferSize(surface: SurfaceTexture) {
        val rawWidth = resolveRawPhysicalWidth()
        val rawHeight = resolveRawPhysicalHeight()
        val scaledWidth = (rawWidth.coerceAtLeast(1) * renderScale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (rawHeight.coerceAtLeast(1) * renderScale).roundToInt().coerceAtLeast(1)
        surfaceBufferWidth = scaledWidth
        surfaceBufferHeight = scaledHeight
        surface.setDefaultBufferSize(scaledWidth, scaledHeight)
    }

    private fun reapplyTextureBufferSizeFromView() {
        val view = textureView ?: return
        val surfaceTexture = view.surfaceTexture ?: return
        rememberPhysicalSize(view.width, view.height)
        applyTextureBufferSize(surfaceTexture)
    }

    private fun reapplySurfaceViewConfiguration(holder: SurfaceHolder? = surfaceView?.holder) {
        val targetHolder = holder ?: return
        applySurfaceViewBufferSize(targetHolder)
        reapplyWindowFrameRateHint()
        applyRenderSurfaceFrameRateHint(targetHolder.surface)
    }

    private fun applySurfaceViewBufferSize(holder: SurfaceHolder) {
        val rawWidth = resolveRawPhysicalWidth()
        val rawHeight = resolveRawPhysicalHeight()
        val scaledWidth = (rawWidth * renderScale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (rawHeight * renderScale).roundToInt().coerceAtLeast(1)
        surfaceBufferWidth = scaledWidth
        surfaceBufferHeight = scaledHeight
        try {
            holder.setFixedSize(scaledWidth, scaledHeight)
        } catch (_: Throwable) {}
    }

    private fun reapplyWindowFrameRateHint() {
        if (!::renderView.isInitialized) {
            return
        }
        DisplayPerformanceController.applyWindowFrameRateHint(activity, renderView, targetFps)
    }

    private fun applyRenderSurfaceFrameRateHint(surface: Surface?) {
        if (surface == null || !surface.isValid || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        val preferredFrameRate = targetFps.coerceAtLeast(1).toFloat()
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
        } catch (_: Throwable) {}
    }

    private fun rememberPhysicalSize(width: Int, height: Int) {
        if (width > 0) {
            lastPhysicalWidth = width
        }
        if (height > 0) {
            lastPhysicalHeight = height
        }
    }

    private fun rememberPhysicalSizeFromView() {
        val width = if (::renderView.isInitialized) renderView.width else 0
        val height = if (::renderView.isInitialized) renderView.height else 0
        rememberPhysicalSize(width, height)
    }

    private fun releaseTextureSurfaceIfNeeded() {
        textureSurface?.let {
            try {
                it.release()
            } catch (_: Throwable) {}
        }
        textureSurface = null
    }

    fun applyImmersiveMode() {
        applyDisplayCutoutMode()
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun applyDisplayCutoutMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val attributes = activity.window.attributes
        if (attributes.layoutInDisplayCutoutMode == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) return
        attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        activity.window.attributes = attributes
    }
}

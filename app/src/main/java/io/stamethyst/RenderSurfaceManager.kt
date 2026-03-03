package io.stamethyst

import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Log
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
import io.stamethyst.config.LauncherConfig
import net.kdt.pojavlaunch.Logger
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
    companion object {
        private const val TAG = "RenderSurfaceManager"
    }

    private var surfaceView: SurfaceView? = null
    private var textureView: TextureView? = null
    private var textureSurface: Surface? = null

    lateinit var renderView: View
        private set

    var surfaceBufferWidth = 0
        private set
    var surfaceBufferHeight = 0
        private set

    @Volatile
    var bridgeSurfaceReady = false
        private set

    @Volatile
    private var lastTextureFrameTimestampNs = 0L

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
            Log.i(TAG, "Using TextureView surface path for OpenGL ES2: renderScale=$renderScale")
            val view = TextureView(activity)
            view.isOpaque = true
            textureView = view
            renderView = view
            root.addView(view, renderLayoutParams)
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    surfaceBufferWidth = width.coerceAtLeast(1)
                    surfaceBufferHeight = height.coerceAtLeast(1)
                    applyTextureBufferSize(surface)
                    releaseTextureSurfaceIfNeeded()
                    textureSurface = Surface(surface)
                    JREUtils.setupBridgeWindow(textureSurface)
                    bridgeSurfaceReady = true
                    updateWindowSize()
                    onSurfaceReady()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    surfaceBufferWidth = width.coerceAtLeast(1)
                    surfaceBufferHeight = height.coerceAtLeast(1)
                    applyTextureBufferSize(surface)
                    updateWindowSize()
                    onSurfaceReady()
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    bridgeSurfaceReady = false
                    JREUtils.releaseBridgeWindow()
                    releaseTextureSurfaceIfNeeded()
                    surfaceBufferWidth = 0
                    surfaceBufferHeight = 0
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
                        surfaceBufferWidth = frame.width()
                        surfaceBufferHeight = frame.height()
                    }
                    JREUtils.setupBridgeWindow(holder.surface)
                    bridgeSurfaceReady = true
                    updateWindowSize()
                    onSurfaceReady()
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    surfaceBufferWidth = width
                    surfaceBufferHeight = height
                    updateWindowSize()
                    onSurfaceReady()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    bridgeSurfaceReady = false
                    JREUtils.releaseBridgeWindow()
                    onSurfaceDestroyed()
                }
            })
        }

        renderView.isFocusable = true
        renderView.isFocusableInTouchMode = true
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
        }
        updateWindowSize()

        if (!::renderView.isInitialized) return

        renderView.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            if (useTextureViewSurface) {
                reapplyTextureBufferSizeFromView()
            }
            updateWindowSize()
        }
    }

    fun syncDisplayConfigToSurfaceSize() {
        val windowWidth = CallbackBridge.windowWidth.coerceAtLeast(1)
        val windowHeight = CallbackBridge.windowHeight.coerceAtLeast(1)
        try {
            DisplayConfigSync.syncToCurrentResolution(activity, windowWidth, windowHeight, targetFps)
            Logger.appendToLog(
                "Display config synced to ${windowWidth.coerceAtLeast(800)}x${windowHeight.coerceAtLeast(450)} @$targetFps fps"
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to sync info.displayconfig", error)
            try {
                Logger.appendToLog(
                    "Display config sync failed: ${error.javaClass.simpleName}: ${error.message}"
                )
            } catch (_: Throwable) {}
        }
    }

    fun logRenderInfo() {
        Logger.appendToLog(
            "Render scale: $renderScale, surface(raw)=${resolveRawPhysicalWidth()}x${resolveRawPhysicalHeight()}, " +
                    "surface(effective)=${resolvePhysicalWidth()}x${resolvePhysicalHeight()}, " +
                    "window=${CallbackBridge.windowWidth}x${CallbackBridge.windowHeight}"
        )
    }

    fun resolvePhysicalWidth(): Int = resolveRawPhysicalWidth()

    fun resolvePhysicalHeight(): Int = resolveRawPhysicalHeight()

    private fun resolveRawPhysicalWidth(): Int {
        val viewWidth = if (::renderView.isInitialized) renderView.width else 0
        return (if (surfaceBufferWidth > 0) surfaceBufferWidth else viewWidth).coerceAtLeast(1)
    }

    private fun resolveRawPhysicalHeight(): Int {
        val viewHeight = if (::renderView.isInitialized) renderView.height else 0
        return (if (surfaceBufferHeight > 0) surfaceBufferHeight else viewHeight).coerceAtLeast(1)
    }

    private fun applyTextureBufferSize(surface: SurfaceTexture) {
        val rawWidth = if (surfaceBufferWidth > 0) {
            surfaceBufferWidth
        } else if (::renderView.isInitialized) {
            renderView.width
        } else {
            0
        }
        val rawHeight = if (surfaceBufferHeight > 0) {
            surfaceBufferHeight
        } else if (::renderView.isInitialized) {
            renderView.height
        } else {
            0
        }
        val scaledWidth = (rawWidth.coerceAtLeast(1) * renderScale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (rawHeight.coerceAtLeast(1) * renderScale).roundToInt().coerceAtLeast(1)
        surface.setDefaultBufferSize(scaledWidth, scaledHeight)
    }

    private fun reapplyTextureBufferSizeFromView() {
        val view = textureView ?: return
        val surfaceTexture = view.surfaceTexture ?: return
        if (view.width > 0) {
            surfaceBufferWidth = view.width
        }
        if (view.height > 0) {
            surfaceBufferHeight = view.height
        }
        applyTextureBufferSize(surfaceTexture)
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

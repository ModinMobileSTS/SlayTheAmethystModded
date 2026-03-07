package io.stamethyst

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

internal interface RenderSurfaceHost {
    val renderView: View
    val currentSurface: Surface?
    val surfaceGeneration: Int
    val usesTextureView: Boolean

    fun attach(root: FrameLayout, callbacks: Callbacks)
    fun release()
    fun applyBufferSize(width: Int, height: Int, surfaceGeneration: Int): Boolean

    interface Callbacks {
        fun onSurfaceAvailable(surfaceGeneration: Int, width: Int, height: Int)
        fun onSurfaceSizeChanged(surfaceGeneration: Int, width: Int, height: Int)
        fun onSurfaceDestroyed(surfaceGeneration: Int)
        fun onTextureFrameUpdated(timestampNs: Long)
    }
}

internal class TextureViewHost(
    private val activity: StsGameActivity
) : RenderSurfaceHost {
    private var textureView: TextureView? = null
    private var textureSurface: Surface? = null
    private var listener: TextureView.SurfaceTextureListener? = null
    private var lastAppliedBufferWidth = 0
    private var lastAppliedBufferHeight = 0
    private var lastAppliedGeneration = -1

    override lateinit var renderView: View
        private set

    override val currentSurface: Surface?
        get() = textureSurface

    override var surfaceGeneration: Int = 0
        private set

    override val usesTextureView: Boolean = true

    override fun attach(root: FrameLayout, callbacks: RenderSurfaceHost.Callbacks) {
        val view = TextureView(activity).apply {
            isOpaque = true
        }
        textureView = view
        renderView = view
        root.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val hostListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                surfaceGeneration++
                lastAppliedGeneration = -1
                releaseTextureSurfaceIfNeeded()
                textureSurface = Surface(surface)
                callbacks.onSurfaceAvailable(surfaceGeneration, width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                callbacks.onSurfaceSizeChanged(surfaceGeneration, width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                lastAppliedGeneration = -1
                lastAppliedBufferWidth = 0
                lastAppliedBufferHeight = 0
                callbacks.onSurfaceDestroyed(surfaceGeneration)
                releaseTextureSurfaceIfNeeded()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                callbacks.onTextureFrameUpdated(surface.timestamp)
            }
        }
        listener = hostListener
        view.surfaceTextureListener = hostListener
    }

    override fun release() {
        textureView?.surfaceTextureListener = null
        listener = null
        releaseTextureSurfaceIfNeeded()
        textureView = null
    }

    override fun applyBufferSize(width: Int, height: Int, surfaceGeneration: Int): Boolean {
        if (surfaceGeneration != this.surfaceGeneration) {
            return false
        }
        val surfaceTexture = textureView?.surfaceTexture ?: return false
        if (lastAppliedGeneration == this.surfaceGeneration &&
            lastAppliedBufferWidth == width &&
            lastAppliedBufferHeight == height
        ) {
            return false
        }
        surfaceTexture.setDefaultBufferSize(width, height)
        lastAppliedGeneration = this.surfaceGeneration
        lastAppliedBufferWidth = width
        lastAppliedBufferHeight = height
        return true
    }

    private fun releaseTextureSurfaceIfNeeded() {
        textureSurface?.let {
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        textureSurface = null
    }
}

internal class SurfaceViewHost(
    private val activity: StsGameActivity
) : RenderSurfaceHost {
    private var surfaceView: SurfaceView? = null
    private var callback: SurfaceHolder.Callback? = null
    private var lastAppliedBufferWidth = 0
    private var lastAppliedBufferHeight = 0
    private var lastAppliedGeneration = -1

    override lateinit var renderView: View
        private set

    override val currentSurface: Surface?
        get() = surfaceView?.holder?.surface?.takeIf { it.isValid }

    override var surfaceGeneration: Int = 0
        private set

    override val usesTextureView: Boolean = false

    override fun attach(root: FrameLayout, callbacks: RenderSurfaceHost.Callbacks) {
        val view = SurfaceView(activity)
        surfaceView = view
        renderView = view
        root.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val holderCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceGeneration++
                lastAppliedGeneration = -1
                val frame = holder.surfaceFrame
                val physicalWidth = view.width.takeIf { it > 0 } ?: frame?.width() ?: 0
                val physicalHeight = view.height.takeIf { it > 0 } ?: frame?.height() ?: 0
                callbacks.onSurfaceAvailable(
                    surfaceGeneration,
                    physicalWidth,
                    physicalHeight
                )
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                callbacks.onSurfaceSizeChanged(surfaceGeneration, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                lastAppliedGeneration = -1
                lastAppliedBufferWidth = 0
                lastAppliedBufferHeight = 0
                callbacks.onSurfaceDestroyed(surfaceGeneration)
            }
        }
        callback = holderCallback
        view.holder.addCallback(holderCallback)
    }

    override fun release() {
        val callbackToRemove = callback
        val view = surfaceView
        if (callbackToRemove != null && view != null) {
            try {
                view.holder.removeCallback(callbackToRemove)
            } catch (_: Throwable) {
            }
        }
        callback = null
        surfaceView = null
    }

    override fun applyBufferSize(width: Int, height: Int, surfaceGeneration: Int): Boolean {
        if (surfaceGeneration != this.surfaceGeneration) {
            return false
        }
        val holder = surfaceView?.holder ?: return false
        if (lastAppliedGeneration == this.surfaceGeneration &&
            lastAppliedBufferWidth == width &&
            lastAppliedBufferHeight == height
        ) {
            return false
        }
        try {
            holder.setFixedSize(width, height)
        } catch (_: Throwable) {
            return false
        }
        lastAppliedGeneration = this.surfaceGeneration
        lastAppliedBufferWidth = width
        lastAppliedBufferHeight = height
        return true
    }
}

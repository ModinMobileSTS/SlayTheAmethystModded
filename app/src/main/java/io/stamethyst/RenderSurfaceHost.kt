package io.stamethyst

import android.os.SystemClock
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

internal interface RenderSurfaceHost {
    data class BufferSizeApplyResult(
        val handled: Boolean,
        val changedSurfaceGeometry: Boolean,
        val detail: String
    )

    val renderView: View
    val currentSurface: Surface?
    val surfaceGeneration: Int
    val usesTextureView: Boolean

    fun attach(root: FrameLayout, callbacks: Callbacks)
    fun release()
    fun applyBufferSize(
        width: Int,
        height: Int,
        surfaceGeneration: Int
    ): BufferSizeApplyResult

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
    private var surfaceAvailableCount = 0
    private var surfaceSizeChangedCount = 0
    private var surfaceDestroyedCount = 0
    private var lastSurfaceAvailableAtMs = 0L

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
                surfaceAvailableCount++
                lastSurfaceAvailableAtMs = SystemClock.elapsedRealtime()
                println(
                    "RenderSurfaceLifecycle: host=texture_view event=available " +
                        "generation=$surfaceGeneration count=$surfaceAvailableCount size=${width}x$height"
                )
                callbacks.onSurfaceAvailable(surfaceGeneration, width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                surfaceSizeChangedCount++
                println(
                    "RenderSurfaceLifecycle: host=texture_view event=size_changed " +
                        "generation=$surfaceGeneration count=$surfaceSizeChangedCount size=${width}x$height"
                )
                callbacks.onSurfaceSizeChanged(surfaceGeneration, width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                lastAppliedGeneration = -1
                lastAppliedBufferWidth = 0
                lastAppliedBufferHeight = 0
                surfaceDestroyedCount++
                val livedMs = if (lastSurfaceAvailableAtMs > 0L) {
                    SystemClock.elapsedRealtime() - lastSurfaceAvailableAtMs
                } else {
                    -1L
                }
                println(
                    "RenderSurfaceLifecycle: host=texture_view event=destroyed " +
                        "generation=$surfaceGeneration count=$surfaceDestroyedCount livedMs=$livedMs"
                )
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
        println(
            "RenderSurfaceLifecycle: host=texture_view event=release " +
                "available=$surfaceAvailableCount sizeChanged=$surfaceSizeChangedCount " +
                "destroyed=$surfaceDestroyedCount generation=$surfaceGeneration"
        )
        textureView = null
    }

    override fun applyBufferSize(
        width: Int,
        height: Int,
        surfaceGeneration: Int
    ): RenderSurfaceHost.BufferSizeApplyResult {
        if (surfaceGeneration != this.surfaceGeneration) {
            return RenderSurfaceHost.BufferSizeApplyResult(
                handled = false,
                changedSurfaceGeometry = false,
                detail = "texture_generation_mismatch requested=$surfaceGeneration actual=${this.surfaceGeneration}"
            )
        }
        val view = textureView
        val surfaceTexture = view?.surfaceTexture
            ?: return RenderSurfaceHost.BufferSizeApplyResult(
                handled = false,
                changedSurfaceGeometry = false,
                detail = "texture_missing_surface_texture view=${view != null}"
            )
        val detailPrefix =
            "texture request=${width}x$height generation=$surfaceGeneration view=${view.width}x${view.height}"
        if (lastAppliedGeneration == this.surfaceGeneration &&
            lastAppliedBufferWidth == width &&
            lastAppliedBufferHeight == height
        ) {
            return RenderSurfaceHost.BufferSizeApplyResult(
                handled = false,
                changedSurfaceGeometry = false,
                detail = "$detailPrefix duplicate"
            )
        }
        surfaceTexture.setDefaultBufferSize(width, height)
        lastAppliedGeneration = this.surfaceGeneration
        lastAppliedBufferWidth = width
        lastAppliedBufferHeight = height
        return RenderSurfaceHost.BufferSizeApplyResult(
            handled = true,
            changedSurfaceGeometry = true,
            detail = "$detailPrefix default_buffer_size_applied"
        )
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
    private var surfaceCreatedCount = 0
    private var surfaceChangedCount = 0
    private var surfaceDestroyedCount = 0
    private var lastSurfaceCreatedAtMs = 0L

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
                surfaceCreatedCount++
                lastSurfaceCreatedAtMs = SystemClock.elapsedRealtime()
                println(
                    "RenderSurfaceLifecycle: host=surface_view event=created " +
                        "generation=$surfaceGeneration count=$surfaceCreatedCount " +
                        "size=${physicalWidth}x$physicalHeight"
                )
                callbacks.onSurfaceAvailable(
                    surfaceGeneration,
                    physicalWidth,
                    physicalHeight
                )
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surfaceChangedCount++
                println(
                    "RenderSurfaceLifecycle: host=surface_view event=changed " +
                        "generation=$surfaceGeneration count=$surfaceChangedCount " +
                        "size=${width}x$height format=$format"
                )
                callbacks.onSurfaceSizeChanged(surfaceGeneration, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                lastAppliedGeneration = -1
                lastAppliedBufferWidth = 0
                lastAppliedBufferHeight = 0
                surfaceDestroyedCount++
                val livedMs = if (lastSurfaceCreatedAtMs > 0L) {
                    SystemClock.elapsedRealtime() - lastSurfaceCreatedAtMs
                } else {
                    -1L
                }
                println(
                    "RenderSurfaceLifecycle: host=surface_view event=destroyed " +
                        "generation=$surfaceGeneration count=$surfaceDestroyedCount livedMs=$livedMs"
                )
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
        println(
            "RenderSurfaceLifecycle: host=surface_view event=release " +
                "created=$surfaceCreatedCount changed=$surfaceChangedCount " +
                "destroyed=$surfaceDestroyedCount generation=$surfaceGeneration"
        )
        surfaceView = null
    }

    override fun applyBufferSize(
        width: Int,
        height: Int,
        surfaceGeneration: Int
    ): RenderSurfaceHost.BufferSizeApplyResult {
        if (surfaceGeneration != this.surfaceGeneration) {
            return RenderSurfaceHost.BufferSizeApplyResult(
                handled = false,
                changedSurfaceGeometry = false,
                detail = "surface_generation_mismatch requested=$surfaceGeneration actual=${this.surfaceGeneration}"
            )
        }
        val holder = surfaceView?.holder
            ?: return RenderSurfaceHost.BufferSizeApplyResult(
                handled = false,
                changedSurfaceGeometry = false,
                detail = "surface_missing_holder"
            )
        val detailPrefix = buildSurfaceViewDetail(
            holder = holder,
            requestedWidth = width,
            requestedHeight = height,
            requestedGeneration = surfaceGeneration
        )
        if (lastAppliedGeneration == this.surfaceGeneration &&
            lastAppliedBufferWidth == width &&
            lastAppliedBufferHeight == height
        ) {
            return RenderSurfaceHost.BufferSizeApplyResult(
                handled = false,
                changedSurfaceGeometry = false,
                detail = "$detailPrefix duplicate"
            )
        }
        val view = surfaceView
        val frame = holder.surfaceFrame
        val viewWidth = view?.width ?: 0
        val viewHeight = view?.height ?: 0
        val frameWidth = frame?.width() ?: 0
        val frameHeight = frame?.height() ?: 0
        val matchesViewBounds = requestedMatchesSize(width, height, viewWidth, viewHeight)
        val matchesSurfaceFrame = requestedMatchesSize(width, height, frameWidth, frameHeight)
        if (matchesViewBounds || matchesSurfaceFrame) {
            lastAppliedGeneration = this.surfaceGeneration
            lastAppliedBufferWidth = width
            lastAppliedBufferHeight = height
            val suppressionReason = when {
                matchesViewBounds && matchesSurfaceFrame -> "fixed_size_suppressed_view_and_frame_match"
                matchesViewBounds -> "fixed_size_suppressed_view_match"
                else -> "fixed_size_suppressed_frame_match"
            }
            return RenderSurfaceHost.BufferSizeApplyResult(
                handled = true,
                changedSurfaceGeometry = false,
                detail = "$detailPrefix $suppressionReason"
            )
        }
        try {
            holder.setFixedSize(width, height)
        } catch (_: Throwable) {
            return RenderSurfaceHost.BufferSizeApplyResult(
                handled = false,
                changedSurfaceGeometry = false,
                detail = "$detailPrefix fixed_size_failed"
            )
        }
        lastAppliedGeneration = this.surfaceGeneration
        lastAppliedBufferWidth = width
        lastAppliedBufferHeight = height
        return RenderSurfaceHost.BufferSizeApplyResult(
            handled = true,
            changedSurfaceGeometry = true,
            detail = "$detailPrefix fixed_size_applied"
        )
    }

    private fun buildSurfaceViewDetail(
        holder: SurfaceHolder,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedGeneration: Int
    ): String {
        val frame = holder.surfaceFrame
        val frameWidth = frame?.width() ?: 0
        val frameHeight = frame?.height() ?: 0
        val view = surfaceView
        return buildString(128) {
            append("surface request=")
            append(requestedWidth)
            append("x")
            append(requestedHeight)
            append(" generation=")
            append(requestedGeneration)
            append(" view=")
            append(view?.width ?: 0)
            append("x")
            append(view?.height ?: 0)
            append(" frame=")
            append(frameWidth)
            append("x")
            append(frameHeight)
            append(" surfaceValid=")
            append(holder.surface.isValid)
        }
    }

    private fun requestedMatchesSize(
        requestedWidth: Int,
        requestedHeight: Int,
        actualWidth: Int,
        actualHeight: Int
    ): Boolean {
        return requestedWidth > 0 &&
            requestedHeight > 0 &&
            actualWidth > 0 &&
            actualHeight > 0 &&
            requestedWidth == actualWidth &&
            requestedHeight == actualHeight
    }
}

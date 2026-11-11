package io.stamethyst.backend.diag.refresh

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout

/** A deliberately small drawing workload. Counts submissions/draws, not displayed frames. */
internal class RefreshRateProbeView(context: Context) : FrameLayout(context) {
    var draws = 0; private set
    var drawErrors = 0; private set
    var lastDrawError: String? = null; private set
    private var tick = 0L
    private var content: View = AnimationView(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val surfaceEvents = mutableListOf<String>()

    fun lifecycleEvents(): List<String> = surfaceEvents.toList()
    private fun record(message: String) {
        if (surfaceEvents.size < 50) surfaceEvents += "${android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000} $message"
    }

    init { addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

    fun select(phase: RefreshRatePhase) {
        removeAllViews()
        surfaceEvents.clear()
        content = when (phase) {
            RefreshRatePhase.SURFACE -> SurfaceView(context).apply {
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: android.view.SurfaceHolder) { record("surface_created") }
                    override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                        record("surface_changed ${width}x$height format=$format")
                    }
                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) { record("surface_destroyed") }
                })
            }
            RefreshRatePhase.TEXTURE -> TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        record("texture_available ${width}x$height")
                    }
                    override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        record("texture_size ${width}x$height")
                    }
                    override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                        record("texture_destroyed")
                        return true
                    }
                    override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
                }
            }
            else -> AnimationView(context)
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        resetCounts()
    }

    fun ready(): Boolean = width > 0 && height > 0 && when (val view = content) {
        is SurfaceView -> view.holder.surface?.isValid == true
        is TextureView -> view.isAvailable
        else -> isAttachedToWindow
    }

    fun surface(): Surface? = (content as? SurfaceView)?.holder?.surface

    fun resetCounts() { draws = 0; drawErrors = 0; lastDrawError = null }

    fun render(nowNs: Long) {
        tick = nowNs
        try {
            when (val view = content) {
                is SurfaceView -> {
                    if (!view.holder.surface.isValid) return
                    val canvas = view.holder.lockHardwareCanvas()
                    try { drawPattern(canvas); draws++ } finally { view.holder.unlockCanvasAndPost(canvas) }
                }
                is TextureView -> {
                    if (!view.isAvailable) return
                    val canvas = view.lockCanvas() ?: return
                    try { drawPattern(canvas); draws++ } finally { view.unlockCanvasAndPost(canvas) }
                }
                else -> view.invalidate()
            }
        } catch (error: Exception) {
            drawErrors++
            lastDrawError = "${error.javaClass.simpleName}: ${error.message}"
        }
    }

    private fun drawPattern(canvas: Canvas) {
        canvas.drawColor(Color.rgb(23, 30, 43))
        paint.color = Color.rgb(115, 185, 215)
        val x = ((tick % 2_000_000_000L) / 2_000_000_000.0 * canvas.width).toFloat()
        canvas.drawRect(x, 0f, x + canvas.width * .08f, canvas.height.toFloat(), paint)
        paint.color = Color.rgb(71, 89, 110)
        for (index in 1..7) {
            val y = canvas.height * index / 8f
            canvas.drawRect(0f, y, canvas.width.toFloat(), y + 2f, paint)
        }
    }

    private inner class AnimationView(context: Context) : View(context) {
        override fun onDraw(canvas: Canvas) { drawPattern(canvas); draws++ }
    }

    fun release() {
        // SurfaceView/TextureView own their surfaces; detaching releases them. Never release a
        // borrowed holder Surface or the TextureView's SurfaceTexture ourselves.
        removeAllViews()
    }
}

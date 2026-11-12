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
import android.os.Build
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.stamethyst.backend.render.ViewFrameRateVote
import io.stamethyst.ui.rememberHighRefreshRateModifier

/** A deliberately small drawing workload. Counts submissions/draws, not displayed frames. */
internal class RefreshRateProbeView(context: Context) : FrameLayout(context) {
    var draws = 0; private set
    var drawErrors = 0; private set
    var lastDrawError: String? = null; private set
    private var tick = 0L
    private var content: View = AnimationView(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val surfaceEvents = mutableListOf<String>()
    private var kind = RefreshRateProbeKind.VIEW
    private var viewVote: ViewFrameRateVote? = null
    private var textureProducerSurface: Surface? = null
    private var composeTick by mutableLongStateOf(0L)
    private var composeHigh by mutableStateOf(false)
    private var composeDrawn = false
    private var composeVoteInstalled = false

    fun lifecycleEvents(): List<String> = surfaceEvents.toList()
    private fun record(message: String) {
        if (surfaceEvents.size < 50) surfaceEvents += "${android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000} $message"
    }

    init { addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

    fun select(phase: RefreshRatePhase) {
        release()
        surfaceEvents.clear()
        kind = phase.probeKind
        composeHigh = false
        composeDrawn = false
        composeVoteInstalled = false
        content = when (kind) {
            RefreshRateProbeKind.SURFACE -> SurfaceView(context).apply {
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: android.view.SurfaceHolder) { record("surface_created") }
                    override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                        record("surface_changed ${width}x$height format=$format")
                    }
                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) { record("surface_destroyed") }
                })
            }
            RefreshRateProbeKind.TEXTURE -> TextureView(context).apply {
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
            RefreshRateProbeKind.COMPOSE -> ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    val highModifier = rememberHighRefreshRateModifier(enabled = composeHigh)
                    SideEffect { composeVoteInstalled = highModifier != Modifier }
                    ComposeCanvas(Modifier.fillMaxSize().then(highModifier)) {
                        // Snapshot reads invalidate the real animated Canvas, not an empty keepalive.
                        tick = composeTick
                        drawIntoCanvas { drawPattern(it.nativeCanvas) }
                        draws++
                        composeDrawn = true
                    }
                }
            }
            RefreshRateProbeKind.VIEW -> AnimationView(context)
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        resetCounts()
    }

    fun ready(): Boolean = width > 0 && height > 0 && when (val view = content) {
        is SurfaceView -> view.holder.surface?.isValid == true
        is TextureView -> view.isAvailable
        is ComposeView -> isAttachedToWindow && composeDrawn
        else -> isAttachedToWindow
    }

    fun surface(): Surface? = (content as? SurfaceView)?.holder?.surface

    /** Mirrors the production TextureView wrapper, not a separately composed display layer. */
    fun producerSurface(): Surface? = when (val view = content) {
        is SurfaceView -> view.holder.surface
        is TextureView -> textureProducerSurface ?: view.surfaceTexture?.let { texture ->
            Surface(texture).also { textureProducerSurface = it }
        }
        else -> null
    }

    fun requestHighViewVote() {
        check(Build.VERSION.SDK_INT >= 35) { "View high frame-rate vote requires API 35" }
        val vote = viewVote ?: ViewFrameRateVote(content).also { viewVote = it }
        vote.setActive(true)?.let { throw IllegalStateException("View frame-rate request failed", it) }
    }

    fun requestHighComposeVote() {
        check(Build.VERSION.SDK_INT >= 35) { "Compose high frame-rate vote requires API 35" }
        check(kind == RefreshRateProbeKind.COMPOSE)
        composeHigh = true
    }

    fun requestedViewRate(): String? = if (Build.VERSION.SDK_INT >= 35) {
        runCatching { content.requestedFrameRate.toString() }
            .getOrElse { "unavailable: ${it.javaClass.simpleName}" }
    } else null

    fun composeHighVoteInstalled(): Boolean? = if (kind == RefreshRateProbeKind.COMPOSE) composeVoteInstalled else null

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
                is ComposeView -> composeTick = nowNs
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
        val restoreFailure = viewVote?.setActive(false)
        viewVote = null
        composeHigh = false
        composeVoteInstalled = false
        composeDrawn = false
        var failure = restoreFailure
        fun cleanup(block: () -> Unit) {
            runCatching(block).exceptionOrNull()?.let { if (failure == null) failure = it }
        }
        val producer = textureProducerSurface
        textureProducerSurface = null
        cleanup { producer?.release() }
        cleanup { (content as? ComposeView)?.disposeComposition() }
        cleanup { removeAllViews() }
        failure?.let { throw IllegalStateException("Probe cleanup failed", it) }
    }
}

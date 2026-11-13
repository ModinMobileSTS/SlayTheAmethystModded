package io.stamethyst.backend.render

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.stamethyst.LauncherActivity
import io.stamethyst.StsGameActivity
import io.stamethyst.config.LauncherConfig

/**
 * Experimental LTPO keep-active workaround, inspired by svtxvt/android-force-120hz.
 * Independently implemented for app-owned windows: no accessibility service, overlay permission,
 * system settings changes or background animation. The separate 1px surface avoids invalidating
 * the launcher's Compose tree or the game's render surface every frame.
 */
object ForceHighRefreshRateController {
    private val hosts = mutableMapOf<Activity, Host>()
    private val listeners = mutableSetOf<(Boolean) -> Unit>()
    private var installed = false

    @JvmStatic
    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityResumed(activity: Activity) {
                // Leave diagnostic probes and external/utility activities unmodified.
                if (activity !is LauncherActivity && activity !is StsGameActivity) return
                val host = hosts.getOrPut(activity) { Host(activity) }
                host.resumed = true
                // onResume can precede decor attachment; cancel this work again on pause/destroy.
                activity.window.decorView.post(host.refresh)
            }
            override fun onActivityPaused(activity: Activity) { hosts[activity]?.pause() }
            override fun onActivityStopped(activity: Activity) { hosts[activity]?.pause() }
            override fun onActivityDestroyed(activity: Activity) { hosts.remove(activity)?.pause() }
        })
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setForceHighRefreshRateEnabled(context, enabled)
        hosts.values.toList().forEach { it.refresh.run() }
        listeners.toList().forEach { it(enabled) }
    }

    fun addListener(listener: (Boolean) -> Unit) { listeners += listener }
    fun removeListener(listener: (Boolean) -> Unit) { listeners -= listener }

    private class Host(private val activity: Activity) {
        var resumed = false
        private var line: KeepActiveLine? = null
        private val manager get() = activity.windowManager
        private val session = HighRefreshRateSession(::attach, ::detach)
        val refresh = Runnable {
            session.update(LauncherConfig.isForceHighRefreshRateEnabled(activity), resumed)
        }

        fun pause() {
            resumed = false
            activity.window.decorView.removeCallbacks(refresh)
            session.update(enabled = false, resumed = false)
        }

        private fun attach(): Boolean {
            val decor = activity.window.decorView
            val token = decor.windowToken ?: return false
            if (activity.isFinishing || activity.isDestroyed) return false
            val view = KeepActiveLine(activity)
            return try {
                val mode = decor.display?.let { DisplayRefreshRatePolicy.highestModeAtCurrentResolution(it) }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    1, // physical pixel, not dp
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    this.token = token
                    gravity = Gravity.BOTTOM or Gravity.START
                    preferredRefreshRate = mode?.refreshRate ?: 0f
                    preferredDisplayModeId = mode?.modeId ?: 0
                    setTitle("LTPO keep-active")
                }
                manager.addView(view, params)
                line = view
                true
            } catch (error: RuntimeException) {
                view.stop()
                Log.w("ForceHighRefreshRate", "Keep-active window unavailable", error)
                false
            }
        }

        private fun detach() {
            val view = line ?: return
            line = null
            view.stop()
            try {
                manager.removeViewImmediate(view)
            } catch (error: RuntimeException) {
                Log.w("ForceHighRefreshRate", "Keep-active window already removed", error)
            }
        }
    }

    private class KeepActiveLine(context: Context) : View(context), Choreographer.FrameCallback {
        private val choreographer = Choreographer.getInstance()
        private var running = false
        private var frame = 0

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
            isClickable = false
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            updateAnimation()
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            updateAnimation()
        }

        private fun updateAnimation() {
            if (isAttachedToWindow && windowVisibility == VISIBLE) {
                if (!running) {
                    running = true
                    choreographer.postFrameCallback(this)
                }
            } else stop()
        }

        fun stop() {
            running = false
            choreographer.removeFrameCallback(this)
        }

        override fun onDetachedFromWindow() {
            stop()
            super.onDetachedFromWindow()
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            frame = (frame + 1) % 5
            invalidate()
            choreographer.postFrameCallback(this)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.argb(frame + 1, 0, 0, 0))
        }
    }
}

package io.stamethyst

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.lwjgl.glfw.CallbackBridge
import java.util.Locale

internal class GamePerformanceOverlayController(
    private val activity: AppCompatActivity,
    private val overlayView: TextView
) {
    companion object {
        private const val REFRESH_INTERVAL_MS = 1000L
        private const val OVERLAY_SIDE_MARGIN_DP = 12
        private const val OVERLAY_TOP_MARGIN_DP = 40
        private const val BYTES_PER_MB = 1024.0 * 1024.0
    }

    private val activityManager =
        activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val processId = Process.myPid()

    private var running = false
    private var visible = false
    private var lastSampleElapsedMs = 0L
    private var lastSwapCount = 0

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!running || !visible) {
                return
            }
            renderSnapshot()
            overlayView.removeCallbacks(this)
            overlayView.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    fun init() {
        overlayView.visibility = View.GONE
        overlayView.text = ""
        ViewCompat.setOnApplyWindowInsetsListener(overlayView) { view, insets ->
            applyInsets(view, insets)
            insets
        }
        ViewCompat.requestApplyInsets(overlayView)
    }

    fun onResume() {
        running = true
        if (visible) {
            primeSamplingState()
            renderSnapshot()
            scheduleNextUpdate()
        }
    }

    fun onPause() {
        running = false
        stopUpdates()
    }

    fun onDestroy() {
        stopUpdates()
    }

    fun setVisible(visible: Boolean) {
        if (this.visible == visible) {
            return
        }
        this.visible = visible
        overlayView.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            stopUpdates()
            return
        }
        primeSamplingState()
        renderSnapshot()
        if (running) {
            scheduleNextUpdate()
        }
    }

    private fun primeSamplingState() {
        lastSampleElapsedMs = SystemClock.elapsedRealtime()
        lastSwapCount = readSwapCount()
    }

    private fun scheduleNextUpdate() {
        overlayView.removeCallbacks(updateRunnable)
        if (running && visible) {
            overlayView.postDelayed(updateRunnable, REFRESH_INTERVAL_MS)
        }
    }

    private fun stopUpdates() {
        overlayView.removeCallbacks(updateRunnable)
    }

    private fun renderSnapshot() {
        val nowMs = SystemClock.elapsedRealtime()
        val swapCount = readSwapCount()
        val elapsedMs = (nowMs - lastSampleElapsedMs).coerceAtLeast(1L)
        val renderedFrames = if (swapCount >= lastSwapCount) {
            swapCount - lastSwapCount
        } else {
            swapCount
        }
        val fps = renderedFrames.toFloat() * 1000f / elapsedMs.toFloat()
        lastSampleElapsedMs = nowMs
        lastSwapCount = swapCount

        val runtime = Runtime.getRuntime()
        val heapUsedBytes = runtime.totalMemory() - runtime.freeMemory()
        val heapMaxBytes = runtime.maxMemory()
        val nativeHeapBytes = Debug.getNativeHeapAllocatedSize()
        val processPssBytes = readProcessPssBytes()

        overlayView.text = buildString(96) {
            append("FPS ")
            append(String.format(Locale.US, "%.1f", fps))
            append("  PSS ")
            append(processPssBytes?.let(::formatMb) ?: "--")
            append('\n')
            append("Heap ")
            append(formatMb(heapUsedBytes))
            append(" / ")
            append(formatMb(heapMaxBytes))
            append("  Native ")
            append(formatMb(nativeHeapBytes))
        }
    }

    private fun readSwapCount(): Int {
        return try {
            CallbackBridge.nativeGetGlSwapCount().coerceAtLeast(0)
        } catch (_: Throwable) {
            0
        }
    }

    private fun readProcessPssBytes(): Long? {
        val manager = activityManager ?: return null
        return try {
            val info = manager.getProcessMemoryInfo(intArrayOf(processId))
                ?.firstOrNull()
                ?: return null
            info.totalPss.toLong().coerceAtLeast(0L) * 1024L
        } catch (_: Throwable) {
            null
        }
    }

    private fun formatMb(bytes: Long): String {
        val valueMb = bytes.coerceAtLeast(0L) / BYTES_PER_MB
        return String.format(Locale.US, "%.1fMB", valueMb)
    }

    private fun applyInsets(view: View, insets: WindowInsetsCompat) {
        val layoutParams = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val sideMargin = dpToPx(OVERLAY_SIDE_MARGIN_DP)
        val topMargin = dpToPx(OVERLAY_TOP_MARGIN_DP)
        layoutParams.leftMargin = bars.left
        layoutParams.rightMargin = bars.right + sideMargin
        layoutParams.topMargin = bars.top + topMargin
        view.layoutParams = layoutParams
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * activity.resources.displayMetrics.density).toInt()
    }
}

package io.stamethyst

import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Manages the boot overlay UI: progress bar, status text, and dismiss button.
 */
class BootOverlayController(
    private val activity: StsGameActivity,
    private val waitForMainMenu: Boolean,
    private val manualDismissBootOverlay: Boolean,
    private val useTextureViewSurface: Boolean,
    private val onDismissed: () -> Unit,
    private val onRequestEarlyDismiss: () -> Unit,
    private val onSignalMainMenuReady: (String) -> Unit,
    private val onSignalLaunchFailure: (String) -> Unit
) {
    companion object {
        private const val BOOT_OVERLAY_MIN_VISIBLE_MS = 1200L
        private const val BOOT_OVERLAY_READY_DELAY_MS = 700L
    }

    private var bootOverlay: View? = null
    private var bootOverlayProgressBar: ProgressBar? = null
    private var bootOverlayStatusText: TextView? = null
    private var bootOverlayDismissButton: Button? = null
    private var bootOverlayProgress = 0
    private var bootOverlayMessage = ""
    private var bootOverlayShownAtMs = -1L
    private var bootOverlayDismissed = false
    private var mainMenuReadySignaled = false
    private var launchFailureSignaled = false

    @Volatile
    var earlyOverlayDismissOnNextFrame = false
        private set

    @Volatile
    var earlyOverlayDismissRequestFrameTimestampNs = 0L
        private set

    val isDismissed: Boolean get() = bootOverlayDismissed

    fun init() {
        bootOverlay = activity.findViewById(R.id.bootOverlay)
        bootOverlayProgressBar = activity.findViewById(R.id.bootOverlayProgressBar)
        bootOverlayStatusText = activity.findViewById(R.id.bootOverlayStatusText)
        bootOverlayDismissButton = activity.findViewById(R.id.bootOverlayDismissButton)

        if (bootOverlay == null || bootOverlayProgressBar == null || bootOverlayStatusText == null) {
            return
        }

        if (!waitForMainMenu) {
            bootOverlay?.visibility = View.GONE
            bootOverlayDismissed = true
            bootOverlayDismissButton?.let {
                it.visibility = View.GONE
                it.setOnClickListener(null)
            }
            onDismissed()
            return
        }

        bootOverlayDismissButton?.let { button ->
            if (manualDismissBootOverlay) {
                button.visibility = View.VISIBLE
                button.isEnabled = true
                button.text = "关闭遮幕"
                button.setOnClickListener {
                    updateProgress(bootOverlayProgress.coerceAtLeast(99), "Manual dismiss requested")
                    dismiss()
                }
            } else {
                button.visibility = View.GONE
                button.setOnClickListener(null)
            }
        }

        bootOverlay?.visibility = View.VISIBLE

        if (manualDismissBootOverlay) {
            bootOverlay?.setOnTouchListener(null)
            bootOverlay?.isClickable = true
            bootOverlay?.isFocusable = true
            bootOverlay?.setOnClickListener {
                // Consume background taps but keep dismiss button clickable.
            }
        } else {
            bootOverlay?.setOnClickListener(null)
            bootOverlay?.setOnTouchListener { _, _ -> true }
        }

        bootOverlayShownAtMs = SystemClock.uptimeMillis()

        if (manualDismissBootOverlay) {
            updateProgress(1, "Starting launch pipeline... (manual overlay dismiss)")
        } else {
            updateProgress(1, "Starting launch pipeline...")
        }
    }

    fun onDestroy() {
        bootOverlay = null
        bootOverlayProgressBar = null
        bootOverlayStatusText = null
        bootOverlayDismissButton = null
        earlyOverlayDismissOnNextFrame = false
    }

    fun updateProgress(percent: Int, message: String?) {
        if (!waitForMainMenu) return

        val bounded = percent.coerceIn(0, 100)
        val normalizedMessage = message?.trim() ?: ""

        if (bounded < bootOverlayProgress) return
        if (bounded == bootOverlayProgress && normalizedMessage == bootOverlayMessage) return

        bootOverlayProgress = bounded
        bootOverlayMessage = normalizedMessage

        activity.runOnUiThread {
            if (bootOverlayDismissed || bootOverlayProgressBar == null || bootOverlayStatusText == null) return@runOnUiThread

            bootOverlayProgressBar?.progress = bootOverlayProgress
            if (normalizedMessage.isNotEmpty()) {
                bootOverlayStatusText?.text = "$normalizedMessage ($bootOverlayProgress%)"
            }
        }
    }

    fun signalMainMenuReady(message: String) {
        if (!waitForMainMenu || mainMenuReadySignaled) return

        mainMenuReadySignaled = true

        if (manualDismissBootOverlay) {
            updateProgress(100, "$message (tap Close Overlay)")
            activity.runOnUiThread {
                bootOverlayDismissButton?.let {
                    it.text = "进入游戏"
                    it.isEnabled = true
                }
            }
            return
        }

        updateProgress(100, message)

        val now = SystemClock.uptimeMillis()
        val elapsed = if (bootOverlayShownAtMs <= 0L) BOOT_OVERLAY_MIN_VISIBLE_MS else (now - bootOverlayShownAtMs)
        val minDelay = (BOOT_OVERLAY_MIN_VISIBLE_MS - elapsed).coerceAtLeast(0)
        val delay = minDelay.coerceAtLeast(BOOT_OVERLAY_READY_DELAY_MS)

        activity.runOnUiThread {
            bootOverlay?.postDelayed({ dismiss() }, delay)
        }
    }

    fun signalLaunchFailure(detail: String) {
        if (launchFailureSignaled) return
        launchFailureSignaled = true

        val crashDetail = if (isOutOfMemoryFailure(detail)) {
            "OutOfMemoryError detected. JVM heap exhausted during game runtime. $detail"
        } else {
            detail
        }
        onSignalLaunchFailure(crashDetail)
    }

    fun requestEarlyDismiss() {
        if (bootOverlayDismissed || bootOverlay == null) return

        if (manualDismissBootOverlay) {
            updateProgress(bootOverlayProgress.coerceAtLeast(98), "Game ready, tap Close Overlay")
            activity.runOnUiThread {
                bootOverlayDismissButton?.let {
                    it.text = "进入游戏"
                    it.isEnabled = true
                }
            }
            return
        }

        if (useTextureViewSurface) {
            onRequestEarlyDismiss()
            return
        }
        dismiss()
    }

    fun dismiss() {
        if (bootOverlayDismissed || bootOverlay == null) return

        bootOverlayDismissed = true
        earlyOverlayDismissOnNextFrame = false
        earlyOverlayDismissRequestFrameTimestampNs = 0L

        bootOverlayDismissButton?.let {
            it.visibility = View.GONE
            it.setOnClickListener(null)
        }
        bootOverlay?.visibility = View.GONE

        onDismissed()
    }

    fun setEarlyDismissRequestTimestamp(timestampNs: Long) {
        earlyOverlayDismissRequestFrameTimestampNs = timestampNs
        earlyOverlayDismissOnNextFrame = true
    }

    fun onTextureFrameUpdate(currentTimestampNs: Long) {
        if (earlyOverlayDismissOnNextFrame &&
            currentTimestampNs > earlyOverlayDismissRequestFrameTimestampNs
        ) {
            earlyOverlayDismissOnNextFrame = false
            activity.runOnUiThread {
                updateProgress(bootOverlayProgress.coerceAtLeast(99), "Game frame ready")
                dismiss()
            }
        }
    }

    private fun isOutOfMemoryFailure(detail: String?): Boolean {
        if (detail == null || detail.isEmpty()) return false
        val lower = detail.lowercase()
        return lower.contains("outofmemoryerror") ||
            lower.contains("java heap space") ||
            lower.contains("gc overhead limit exceeded")
    }

    fun mapBootOverlayPreparationProgress(percent: Int): Int {
        val bounded = percent.coerceIn(0, 100)
        val ratio = bounded / 100f
        return 12 + ((24 - 12) * ratio).roundToInt()
    }
}

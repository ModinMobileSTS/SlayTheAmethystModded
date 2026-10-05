package io.stamethyst.backend.audio

/** Schedules game-thread probes even when an interruption doesn't pause the Activity. */
internal class ForegroundAudioHealthMonitor(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val canCheckHealth: () -> Boolean,
    private val readAudioMode: () -> Int?,
    // Only foreground events renew the bounded native retry budget, not periodic probes.
    private val requestHealthCheck: (String?) -> Unit
) {
    companion object {
        const val CHECK_INTERVAL_MS = 1000L
    }

    private var running = false
    private var lastAudioMode: Int? = null
    private val probe = object : Runnable {
        override fun run() {
            if (!running) return
            if (!canCheckHealth()) {
                stop()
                return
            }
            val mode = readAudioMode()
            val modeChanged = mode != null && lastAudioMode != null && mode != lastAudioMode
            if (mode != null) lastAudioMode = mode
            requestHealthCheck(if (modeChanged) "audio_mode_changed" else null)
            if (running) postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun start() {
        if (!canCheckHealth()) {
            stop()
            return
        }
        if (running) return
        running = true
        lastAudioMode = readAudioMode()
        requestHealthCheck("foreground")
        postDelayed(probe, CHECK_INTERVAL_MS)
    }

    fun checkNow(reason: String) {
        if (!canCheckHealth()) {
            stop()
            return
        }
        if (!running) {
            running = true
            lastAudioMode = readAudioMode()
            postDelayed(probe, CHECK_INTERVAL_MS)
        }
        requestHealthCheck(reason)
    }

    fun stop() {
        if (!running) return
        running = false
        removeCallbacks(probe)
        lastAudioMode = null
    }
}

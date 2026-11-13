package io.stamethyst.backend.audio

/** Only the game thread touches OpenAL; this scheduler gates probes during communication. */
internal class ForegroundAudioHealthMonitor(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val canCheckHealth: () -> Boolean,
    private val readAudioMode: () -> Int?,
    private val requestHealthCheck: (String?) -> Unit,
    private val setRecoveryAllowed: (Boolean) -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    companion object {
        const val CHECK_INTERVAL_MS = 1000L
        const val NORMAL_MODE_SETTLE_MS = 1000L
    }

    private var probe: Runnable? = null
    private var generation = 0L
    private var communicationSeen = false
    private var normalSinceMs: Long? = null
    private var pendingRouteChange = false

    fun start() {
        if (!canCheckHealth()) {
            stop()
            return
        }
        if (probe != null) return
        val currentGeneration = ++generation
        val task = object : Runnable {
            override fun run() {
                if (currentGeneration != generation || probe !== this) return
                if (!canCheckHealth()) {
                    stop()
                    return
                }
                check()
                if (currentGeneration == generation && probe === this) postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
        probe = task
        check()
        postDelayed(task, CHECK_INTERVAL_MS)
    }

    fun checkNow(reason: String) {
        if (!canCheckHealth()) {
            stop()
            return
        }
        if (reason == "output_route_changed") pendingRouteChange = true
        if (probe == null) start() else check()
    }

    private fun check() {
        val mode = readAudioMode()
        // MODE_RINGTONE / IN_CALL / IN_COMMUNICATION and future non-normal modes.
        if (mode != null && mode != 0) {
            communicationSeen = true
            normalSinceMs = null
            setRecoveryAllowed(false)
            return
        }
        if (communicationSeen) {
            // An unreadable mode after communication is not evidence that the call ended.
            if (mode == null) {
                setRecoveryAllowed(false)
                return
            }
            val since = normalSinceMs ?: nowMs().also { normalSinceMs = it }
            if (nowMs() - since < NORMAL_MODE_SETTLE_MS) {
                setRecoveryAllowed(false)
                return
            }
        }
        setRecoveryAllowed(true)
        val reason = when {
            pendingRouteChange -> "output_route_changed"
            communicationSeen -> "communication_ended"
            else -> null
        }
        pendingRouteChange = false
        communicationSeen = false
        normalSinceMs = null
        requestHealthCheck(reason)
    }

    fun stop() {
        ++generation
        probe?.let(removeCallbacks)
        probe = null
        normalSinceMs = null
        // Preserve an observed call and queued route event across Activity pauses.
        setRecoveryAllowed(false)
    }
}

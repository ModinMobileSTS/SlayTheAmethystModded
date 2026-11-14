package io.stamethyst.backend.audio

/** Only the game thread touches OpenAL; this scheduler gates probes during communication. */
internal class ForegroundAudioHealthMonitor(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val canCheckHealth: () -> Boolean,
    private val readAudioMode: () -> Int?,
    // False means the bridge is not ready; retain the interruption until it accepts it.
    private val requestHealthCheck: (String?) -> Boolean,
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
    private var resumePending = false

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
        if (reason == "output_route_changed") pendingRouteChange = true
        if (!canCheckHealth()) {
            stop()
            return
        }
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
        if (communicationSeen || resumePending) {
            // A stopped scheduler cannot observe a call that starts AND ends in the background.
            // Every actual observation gap therefore needs a fresh stable-normal interval.
            if (mode == null) {
                normalSinceMs = null
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
            resumePending -> "foreground_resumed"
            else -> null
        }
        if (requestHealthCheck(reason)) {
            pendingRouteChange = false
            communicationSeen = false
            resumePending = false
            normalSinceMs = null
        }
    }

    fun stop() {
        // Repeated stop/focus/start calls do not manufacture extra retry budgets.
        if (probe != null) resumePending = true
        ++generation
        probe?.let(removeCallbacks)
        probe = null
        normalSinceMs = null
        // Preserve an observed call and queued route event across Activity pauses.
        setRecoveryAllowed(false)
    }
}

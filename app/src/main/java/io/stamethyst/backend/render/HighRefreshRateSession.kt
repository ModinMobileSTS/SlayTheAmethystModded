package io.stamethyst.backend.render

/** Owns at most one animation window and releases it as soon as the host leaves the foreground. */
internal class HighRefreshRateSession(
    private val start: () -> Boolean,
    private val stop: () -> Unit,
) {
    private var running = false

    fun update(enabled: Boolean, resumed: Boolean) {
        if (enabled && resumed) {
            if (!running) running = start()
        } else if (running) {
            running = false
            stop()
        }
    }
}

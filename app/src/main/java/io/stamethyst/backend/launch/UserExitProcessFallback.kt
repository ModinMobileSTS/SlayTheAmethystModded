package io.stamethyst.backend.launch

/** A promise made only after explicit user exit. View/Activity teardown must not cancel it. */
internal class UserExitProcessFallback(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val delayMs: Long,
    private val terminateProcess: () -> Unit
) {
    private var scheduled = false
    private var executed = false

    fun request() {
        if (scheduled) return
        scheduled = true
        postDelayed(Runnable {
            if (!executed) {
                executed = true
                terminateProcess()
            }
        }, delayMs)
    }
}

package top.apricityx.workshop.workshop

import java.io.IOException
import okhttp3.Call

/** Carries cancellation and a probe deadline through HTTP acceleration's nested calls. */
class SteamCdnRequestControl internal constructor(timeoutMillis: Long? = null) {
    private val deadlineNanos = timeoutMillis?.let { System.nanoTime() + it * 1_000_000L }
    private val calls = mutableListOf<Call>()
    private var cancelled = false

    @Synchronized
    fun checkActive() {
        if (cancelled) throw IOException("Steam CDN request cancelled")
        if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) {
            throw IOException("Steam CDN request deadline exceeded")
        }
    }

    @Synchronized
    fun register(call: Call) {
        checkActive()
        deadlineNanos?.let { deadline ->
            val timeout = call.timeout()
            timeout.deadlineNanoTime(if (timeout.hasDeadline()) minOf(deadline, timeout.deadlineNanoTime()) else deadline)
        }
        calls += call
    }

    @Synchronized
    internal fun cancel() {
        cancelled = true
        calls.forEach(Call::cancel)
        calls.clear()
    }
}

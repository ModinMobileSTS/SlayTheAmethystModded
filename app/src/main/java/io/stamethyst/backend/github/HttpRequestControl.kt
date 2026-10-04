package io.stamethyst.backend.github

import java.io.IOException
import okhttp3.Call

/** Shares a deadline and cancellation with accelerated HTTP's nested forward calls. */
internal class HttpRequestControl(timeoutMillis: Long) {
    private val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
    private val calls = mutableSetOf<Call>()
    private var cancelled = false

    @Synchronized
    fun checkActive() {
        if (cancelled || Thread.currentThread().isInterrupted) {
            throw IOException("HTTP request cancelled")
        }
        if (System.nanoTime() >= deadlineNanos) {
            throw IOException("HTTP request deadline exceeded")
        }
    }

    @Synchronized
    fun register(call: Call) {
        try {
            checkActive()
        } catch (error: IOException) {
            call.cancel()
            throw error
        }
        val timeout = call.timeout()
        timeout.deadlineNanoTime(
            if (timeout.hasDeadline()) minOf(deadlineNanos, timeout.deadlineNanoTime()) else deadlineNanos
        )
        calls += call
    }

    @Synchronized
    fun cancel() {
        cancelled = true
        calls.forEach(Call::cancel)
        calls.clear()
    }
}

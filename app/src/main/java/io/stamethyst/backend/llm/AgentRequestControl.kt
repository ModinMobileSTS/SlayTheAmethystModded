package io.stamethyst.backend.llm

import okhttp3.Call
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Monotonic time, including network waits and retry backoff. Wall-clock changes are irrelevant. */
fun interface AgentRequestClock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = AgentRequestClock { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }
    }
}

enum class AgentRequestTimeoutKind { FIRST_EVENT, STREAM_IDLE, TOTAL, ROUND_BUDGET }

class AgentRequestTimeoutException(val kind: AgentRequestTimeoutKind) :
    SocketTimeoutException("Agent request timeout: ${kind.name.lowercase()}")

data class AgentRequestTimeoutState(val remainingMillis: Long, val expired: AgentRequestTimeoutKind?)

/** All limits are finite. Progress resets only the idle timer, never the total request budget. */
data class AgentRequestTimeoutPolicy(
    val totalTimeoutMillis: Long,
    val firstEventTimeoutMillis: Long = totalTimeoutMillis,
    val idleTimeoutMillis: Long = totalTimeoutMillis,
) {
    init {
        require(totalTimeoutMillis > 0 && firstEventTimeoutMillis > 0 && idleTimeoutMillis > 0)
    }

    /** Pure calculation: null lastEvent means no meaningful model event has arrived yet. */
    fun evaluate(elapsedMillis: Long, lastEventElapsedMillis: Long?): AgentRequestTimeoutState {
        require(elapsedMillis >= 0)
        require(lastEventElapsedMillis == null || lastEventElapsedMillis in 0..elapsedMillis)
        val totalRemaining = (totalTimeoutMillis - elapsedMillis).coerceAtLeast(0)
        val phase = if (lastEventElapsedMillis == null) AgentRequestTimeoutKind.FIRST_EVENT
            else AgentRequestTimeoutKind.STREAM_IDLE
        val phaseRemaining = if (lastEventElapsedMillis == null) {
            (firstEventTimeoutMillis - elapsedMillis).coerceAtLeast(0)
        } else {
            (idleTimeoutMillis - (elapsedMillis - lastEventElapsedMillis)).coerceAtLeast(0)
        }
        return when {
            totalRemaining == 0L -> AgentRequestTimeoutState(0, AgentRequestTimeoutKind.TOTAL)
            phaseRemaining == 0L -> AgentRequestTimeoutState(0, phase)
            else -> AgentRequestTimeoutState(minOf(totalRemaining, phaseRemaining), null)
        }
    }
}

/** Construct once per model/tool round, outside its retry/fallback loop. */
class AgentRoundBudget(
    val totalBudgetMillis: Long,
    private val clock: AgentRequestClock = AgentRequestClock.SYSTEM,
) {
    private val startedAt = clock.nowMillis()

    init { require(totalBudgetMillis > 0) }

    fun remainingMillis(): Long =
        (totalBudgetMillis - (clock.nowMillis() - startedAt).coerceAtLeast(0)).coerceAtLeast(0)

    fun requestTimeoutMillis(configuredTimeoutMillis: Long): Long {
        require(configuredTimeoutMillis > 0)
        val remaining = remainingMillis()
        if (remaining == 0L) throw AgentRequestTimeoutException(AgentRequestTimeoutKind.ROUND_BUDGET)
        return minOf(configuredTimeoutMillis, remaining)
    }

    /** Recheck after waiting: radio recovery and scheduler delays also spend the same budget. */
    fun canWait(delayMillis: Long, minimumAttemptMillis: Long = 1): Boolean {
        require(delayMillis >= 0 && minimumAttemptMillis > 0)
        val remaining = remainingMillis()
        return delayMillis < remaining && minimumAttemptMillis <= remaining - delayMillis
    }
}

/** Injectable scheduler lets tests advance a fake monotonic clock without sleeping. */
fun interface AgentRequestScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable
}

private object AgentRequestTimer : AgentRequestScheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "agent-request-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    override fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable {
        val future = executor.schedule(Runnable { action() }, delayMillis, TimeUnit.MILLISECONDS)
        return AutoCloseable { future.cancel(false) }
    }
}

/**
 * One owner per agent execution. Share it with main/summary models, never between sessions.
 * Cancelling one attempt leaves this owner usable for the next attempt; cancelling the owner is final.
 */
class AgentRequestControl(
    private val clock: AgentRequestClock = AgentRequestClock.SYSTEM,
    private val scheduler: AgentRequestScheduler = AgentRequestTimer,
    val executionPolicy: AgentExecutionPolicy = AgentExecutionPolicy(),
) : AutoCloseable {
    internal val executionBudget = AgentExecutionBudget(executionPolicy, clock)
    private val lock = Any()
    private val requests = LinkedHashSet<AgentRequestScope>()
    private var cancellation: CancellationException? = null

    fun newRequest(policy: AgentRequestTimeoutPolicy): AgentRequestScope {
        checkActive()
        val request = synchronized(lock) {
            cancellation?.let { throw it }
            AgentRequestScope(policy, clock, scheduler, ::finished).also { requests += it }
        }
        request.startTimer()
        return request
    }

    fun checkActive() {
        synchronized(lock) { cancellation?.let { throw it } }
        executionBudget.check()
    }

    fun cancel(message: String = "Agent execution cancelled") {
        val error = CancellationException(message)
        val active = synchronized(lock) {
            if (cancellation != null) return
            cancellation = error
            requests.toList()
        }
        active.forEach { it.cancel(error) }
    }

    private fun finished(request: AgentRequestScope) {
        synchronized(lock) { requests.remove(request) }
    }

    override fun close() = cancel()
}

/**
 * A single attempt, bound only while entering the SDK. The transport captures it before dispatch.
 * For asynchronous streaming, keep this scope open until the terminal callback/wait finishes.
 * Bind again inside any executor/coroutine thread that actually calls model.chat().
 */
class AgentRequestScope internal constructor(
    private val policy: AgentRequestTimeoutPolicy,
    private val clock: AgentRequestClock,
    private val scheduler: AgentRequestScheduler,
    private val onFinished: (AgentRequestScope) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val startedAt = clock.nowMillis()
    private var lastEventElapsed: Long? = null
    private var failure: Throwable? = null
    private var timer: AutoCloseable? = null
    private val calls = LinkedHashSet<Call>()

    val activeCallCount: Int get() = synchronized(lock) { calls.size }
    val stopReason: Throwable? get() = synchronized(lock) { failure }

    fun <T> bind(block: () -> T): T {
        throwIfStopped()
        val previous = current.get()
        current.set(this)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    /** Call for nonempty text/thinking/tool-argument deltas, not headers, SSE comments or pings. */
    fun onMeaningfulEvent() = updateDeadline(markEvent = true)

    fun checkTimeout() = updateDeadline(markEvent = false)

    fun throwIfStopped() {
        checkTimeout()
        stopReason?.let { throw it }
    }

    fun cancel(message: String = "Agent request cancelled") = cancel(CancellationException(message))

    internal fun cancel(error: Throwable) {
        val active = synchronized(lock) { stopLocked(error) } ?: return
        finishCancellation(active)
    }

    internal fun startTimer() = updateDeadline(markEvent = false)

    private fun updateDeadline(markEvent: Boolean) {
        val active = synchronized(lock) {
            if (failure != null) return
            val elapsed = (clock.nowMillis() - startedAt).coerceAtLeast(0)
            val state = policy.evaluate(elapsed, lastEventElapsed)
            if (state.expired != null) {
                stopLocked(AgentRequestTimeoutException(state.expired))
            } else {
                if (markEvent) lastEventElapsed = elapsed
                timer?.close()
                timer = scheduler.schedule(policy.evaluate(elapsed, lastEventElapsed).remainingMillis) {
                    checkTimeout()
                }
                null
            }
        }
        if (active != null) finishCancellation(active)
    }

    private fun stopLocked(error: Throwable): List<Call>? {
        if (failure != null) return null
        failure = error
        timer?.close()
        timer = null
        return calls.toList().also { calls.clear() }
    }

    private fun finishCancellation(active: List<Call>) {
        // Do not call user/transport code while holding the scope lock.
        active.forEach { it.cancel() }
        onFinished(this)
    }

    internal fun register(call: Call) {
        val stopped = synchronized(lock) {
            if (failure == null) { calls += call; false } else true
        }
        if (stopped) call.cancel()
    }

    internal fun unregister(call: Call) {
        synchronized(lock) { calls.remove(call) }
    }

    override fun close() = cancel("Agent request scope closed")

    companion object {
        private val current = ThreadLocal<AgentRequestScope>()
        internal fun current(): AgentRequestScope? = current.get()
    }
}

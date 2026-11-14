package io.stamethyst.backend.llm

import dev.langchain4j.exception.HttpException
import dev.langchain4j.exception.NonRetriableException
import dev.langchain4j.exception.RetriableException
import java.io.IOException
import java.util.concurrent.CancellationException

/** Retry transient transport/provider failures, never invalid requests or cancelled work. */
internal fun isRetryableAgentRequestFailure(error: Throwable): Boolean {
    val causes = ArrayList<Throwable>()
    var current: Throwable? = error
    while (current != null && current !in causes) {
        causes += current
        current = current.cause
    }
    if (causes.any { it is CancellationException || it is InterruptedException || it is NonRetriableException ||
            it is AgentRequestTimeoutException && it.kind == AgentRequestTimeoutKind.ROUND_BUDGET }) {
        return false
    }
    causes.filterIsInstance<HttpException>().firstOrNull()?.let {
        return it.statusCode() in setOf(408, 429) || it.statusCode() in 500..599
    }
    return causes.any { it is IOException || it is RetriableException }
}

/**
 * How long to wait before retrying.
 *
 * A normal transient failure backs off quickly. A dropped socket gets a longer window because the
 * radio can take tens of seconds to come back after the screen was locked, and retrying before that
 * only spends the budget.
 */
internal fun retryDelaySeconds(attempt: Int, error: Throwable): Long =
    if (isConnectionAbort(error)) {
        ABORT_RETRY_DELAY_SECONDS.getOrElse(attempt) { ABORT_RETRY_DELAY_SECONDS.last() }
    } else {
        INITIAL_RETRY_DELAY_SECONDS shl attempt
    }

private const val INITIAL_RETRY_DELAY_SECONDS = 1L
private val ABORT_RETRY_DELAY_SECONDS = longArrayOf(5L, 15L, 30L)

/**
 * True when the failure is a socket torn down under the app rather than a protocol or auth error.
 *
 * Locking the screen drops established connections while the network still reports as connected
 * and validated, so this case needs a fresh connection and a longer wait than a normal retry.
 * A read/first-event/idle/total timeout alone is not evidence of a broken connection: it keeps
 * the ordinary short retry policy, subject to the caller's cumulative round budget.
 */
internal fun isConnectionAbort(error: Throwable): Boolean {
    val causes = ArrayList<Throwable>()
    var current: Throwable? = error
    while (current != null && current !in causes) {
        causes += current
        current = current.cause
    }
    return causes.any { cause ->
        val message = cause.message.orEmpty().lowercase()
        cause is java.net.SocketException && (
            message.contains("connection abort") ||
                message.contains("connection reset") ||
                message.contains("broken pipe") ||
                message.contains("software caused connection abort")
            ) ||
            cause is java.io.EOFException ||
            cause is java.nio.channels.ClosedChannelException
    }
}

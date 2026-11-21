package io.stamethyst.backend.github

import java.io.IOException

/** Opt-in policy for a replayable HTTP request that consumes the full response body. */
internal data class RouteRetryQueue(
    val maxRounds: Int,
    val candidateTimeoutMs: Long,
    val gatewayFailureCodes: Set<Int>,
) {
    init {
        require(maxRounds > 0)
        require(candidateTimeoutMs > 0)
    }

    /**
     * Try each preferred candidate once before asking for more. Only after the first round
     * is exhausted, repeat the same deduplicated order without running discovery again.
     */
    fun <T, K> attempts(
        preferred: Iterable<T>,
        additional: () -> Sequence<T>,
        key: (T) -> K,
    ): Sequence<T> = sequence {
        val round = mutableListOf<T>()
        val seen = mutableSetOf<K>()
        for (candidate in preferred.asSequence() + sequence { yieldAll(additional()) }) {
            if (seen.add(key(candidate))) {
                round += candidate
                yield(candidate)
            }
        }
        repeat(maxRounds - 1) { yieldAll(round) }
    }
}

internal class RouteQueueExhaustedException(cause: IOException) : IOException(cause.message, cause)

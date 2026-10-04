package top.apricityx.workshop.workshop

import top.apricityx.workshop.steam.protocol.CdnServer

/** Per-download feedback, shared by all chunk workers (not a persistent geographic preference). */
internal class SteamCdnServerSelector(
    servers: List<CdnServer>,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private class State(val server: CdnServer) {
        var inFlight = 0
        var bytesPerMillis: Double? = null
        var manifestMillis = DEFAULT_MANIFEST_MILLIS
        var failures = 0
        var retryAfterNanos = 0L
    }

    // Steam client-list weights contain duplicate hosts. They must not multiply retries.
    val servers = servers.distinctBy(CdnServer::host)
    private val states = this.servers.map(::State)
    private val lock = Any()

    fun acquire(excludedHosts: Set<String>, expectedBytes: Int, offset: Int): Lease? = synchronized(lock) {
        val candidates = states.filter { it.server.host !in excludedHosts }
        if (candidates.isEmpty()) return@synchronized null
        val now = nanoTime()
        val healthy = candidates.filter { it.retryAfterNanos <= now }
        // Keep a last-resort path when every edge is cooling down; never deadlock a download.
        val available = healthy.ifEmpty { candidates.filter { it.retryAfterNanos == candidates.minOf(State::retryAfterNanos) } }
        val unknownRate = states.mapNotNull(State::bytesPerMillis).maxOrNull()
            ?.times(0.5) ?: DEFAULT_BYTES_PER_MILLI
        val selected = available.minWith(
            compareBy<State> {
                val transferMillis = expectedBytes.coerceAtLeast(1) / (it.bytesPerMillis ?: unknownRate)
                (it.manifestMillis + transferMillis) * (it.inFlight + 1)
            }.thenBy { Math.floorMod(states.indexOf(it) - offset, states.size) },
        )
        selected.inFlight++
        Lease(selected.server, nanoTime())
    }

    fun manifestSucceeded(server: CdnServer, elapsedMillis: Long): Unit = synchronized(lock) {
        states.first { it.server.host == server.host }.apply {
            manifestMillis = elapsedMillis.coerceAtLeast(1L).toDouble()
            failures = 0
            retryAfterNanos = 0L
        }
        Unit
    }

    fun manifestFailed(server: CdnServer) = synchronized(lock) {
        failed(states.first { it.server.host == server.host })
    }

    private fun failed(state: State) {
        state.failures = (state.failures + 1).coerceAtMost(3)
        val cooldownMillis = FAILURE_COOLDOWN_MILLIS * (1L shl (state.failures - 1))
        state.retryAfterNanos = nanoTime() + cooldownMillis * 1_000_000L
    }

    inner class Lease internal constructor(val server: CdnServer, private val startedNanos: Long) {
        private var released = false

        fun succeeded(bytes: Int) = release { state ->
            val elapsedMillis = ((nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(1L)
            val rate = bytes.coerceAtLeast(1).toDouble() / elapsedMillis
            state.bytesPerMillis = state.bytesPerMillis?.let { it * 0.7 + rate * 0.3 } ?: rate
            state.failures = 0
            state.retryAfterNanos = 0L
        }

        fun failed() = release { failed(it) }

        // Cancelling a race or pausing a worker says nothing about the edge's health.
        fun cancelled() = release {}

        private fun release(update: (State) -> Unit) = synchronized(lock) {
            if (!released) {
                released = true
                val state = states.first { it.server.host == server.host }
                state.inFlight--
                update(state)
            }
        }
    }

    private companion object {
        const val DEFAULT_MANIFEST_MILLIS = 250.0
        const val DEFAULT_BYTES_PER_MILLI = 1_024.0
        const val FAILURE_COOLDOWN_MILLIS = 30_000L
    }
}

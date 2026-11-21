package io.stamethyst.backend.steamcloud

import top.apricityx.workshop.steam.protocol.SteamServiceMethodException

internal class SteamCloudRateLimitCooldownException : Exception("Steam Cloud rate limit; try again later")

/** Shared by cloud clients in this process; never throttle another account or CM authentication. */
internal class SteamCloudRpcCooldown(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val until = mutableMapOf<Long, Long>()

    fun <T> call(steamId: Long, block: () -> T): T {
        synchronized(until) {
            val now = nowMs()
            until.entries.removeAll { it.value <= now }
            if (steamId in until) throw SteamCloudRateLimitCooldownException()
        }
        try {
            return block()
        } catch (error: SteamServiceMethodException) {
            if (error.resultCode == 84) synchronized(until) { until[steamId] = nowMs() + 60_000 }
            throw error
        }
    }
}

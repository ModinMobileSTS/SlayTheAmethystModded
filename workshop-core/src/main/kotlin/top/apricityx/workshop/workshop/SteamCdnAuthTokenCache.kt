package top.apricityx.workshop.workshop

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coalesces simultaneous 403s from one host without serializing different hosts. */
internal class SteamCdnAuthTokenCache {
    private val tokens = ConcurrentHashMap<String, String>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    operator fun get(host: String): String? = tokens[host]

    suspend fun getOrLoad(host: String, load: suspend () -> String): String =
        locks.getOrPut(host) { Mutex() }.withLock {
            tokens[host] ?: load().also { tokens[host] = it }
        }
}

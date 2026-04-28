package io.stamethyst.backend.steamcloud

import java.util.concurrent.locks.ReentrantLock

internal object SteamCloudOperationMutex {
    private val lock = ReentrantLock(true)

    fun <T> runExclusive(block: () -> T): T {
        lock.lockInterruptibly()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

package io.stamethyst.backend.steamcloud

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Serializes presence writes across service instances sharing the process's CM connection. */
internal class SteamGamePresenceOwnership {
    private val current = AtomicReference<Owner?>()
    private val workerLock = ReentrantLock()

    /** Called before starting a genuinely new worker, never by the worker itself. No IO/lock wait. */
    fun claim(): Owner = Owner().also { current.set(it) }

    inner class Owner internal constructor() {
        val isCurrent: Boolean get() = current.get() === this

        /** Worker-only. Check and send share the same lock as every other worker's cleanup. */
        fun <T> withCurrent(action: () -> T): T {
            workerLock.lockInterruptibly()
            try {
                if (!isCurrent) throw CancellationException("Steam presence owner was superseded.")
                return action()
            } finally {
                workerLock.unlock()
            }
        }

        /**
         * Worker-only, including stale owners which still need to release their own client.
         * A claim may occur during an in-flight send/clear, but its new worker cannot send until
         * that operation finishes. Thus an old clear can never follow the new owner's upload.
         */
        fun closeClient(action: (mayClear: Boolean) -> Unit) = workerLock.withLock {
            action(isCurrent)
        }

        fun release() {
            current.compareAndSet(this, null)
        }
    }
}

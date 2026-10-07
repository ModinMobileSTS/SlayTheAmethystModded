package io.stamethyst.backend.steamcloud

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Short lock for local transactions/recovery. Never held during an upload or download. */
internal object SteamCloudLocalStateMutex {
    private val lock = ReentrantLock(true)
    fun <T> runExclusive(context: Context, block: () -> T): T = lock.withLock {
        if (lock.holdCount > 1) return@withLock block()
        RandomAccessFile(File(context.filesDir, "steam-cloud-local-state.lock"), "rw").use { handle ->
            handle.channel.lock().use { block() }
        }
    }
}

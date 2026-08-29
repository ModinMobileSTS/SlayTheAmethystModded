package io.stamethyst.backend.steamcloud

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

internal class SteamCloudLiveSaveInUseException : IOException(
    "The game is currently using the live save files. Exit the game before changing them."
)

/**
 * Coordinates destructive live-save access with the game JVM across app processes.
 *
 * The game waits for an in-flight mutation before launch. Mutators fail fast when the game owns
 * the lease, which avoids lock inversion with game-side Steam services.
 */
internal object SteamCloudLiveSaveLease {
    private const val LOCK_FILE_NAME = "sts-live-save.lock"

    private val processLock = ReentrantLock(true)
    private var lockChannel: FileChannel? = null
    private var fileLock: FileLock? = null

    class Lease internal constructor(
        private val ownsFileLock: Boolean,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                release(ownsFileLock)
            }
        }
    }

    fun acquireForGame(context: Context): Lease = acquire(context, waitForFileLock = true)

    fun acquireForMutation(context: Context): Lease = acquire(context, waitForFileLock = false)

    inline fun <T> runMutation(context: Context, block: () -> T): T =
        acquireForMutation(context).use { block() }

    private fun acquire(context: Context, waitForFileLock: Boolean): Lease {
        if (waitForFileLock) {
            processLock.lockInterruptibly()
        } else if (!processLock.tryLock()) {
            throw SteamCloudLiveSaveInUseException()
        }

        val outermost = processLock.holdCount == 1
        try {
            if (outermost) {
                acquireFileLock(context, waitForFileLock)
            }
            return Lease(outermost)
        } catch (error: Throwable) {
            processLock.unlock()
            throw error
        }
    }

    private fun acquireFileLock(context: Context, waitForFileLock: Boolean) {
        val channel = RandomAccessFile(lockFile(context), "rw").channel
        try {
            val acquiredLock = try {
                if (waitForFileLock) channel.lock() else channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (acquiredLock == null) {
                throw SteamCloudLiveSaveInUseException()
            }
            lockChannel = channel
            fileLock = acquiredLock
        } catch (error: Throwable) {
            channel.close()
            throw error
        }
    }

    private fun release(ownsFileLock: Boolean) {
        try {
            if (ownsFileLock) {
                val acquiredLock = fileLock
                val channel = lockChannel
                fileLock = null
                lockChannel = null
                try {
                    acquiredLock?.release()
                } finally {
                    channel?.close()
                }
            }
        } finally {
            processLock.unlock()
        }
    }

    private fun lockFile(context: Context): File =
        File((context.applicationContext ?: context).filesDir, LOCK_FILE_NAME)
}

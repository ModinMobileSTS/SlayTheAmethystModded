package io.stamethyst.backend.steamcloud

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.util.concurrent.locks.ReentrantLock

internal object SteamCloudOperationMutex {
    private const val LOCK_FILE_NAME = "steam-cloud-operation.lock"

    private val processLock = ReentrantLock(true)
    private var lockChannel: FileChannel? = null
    private var fileLock: FileLock? = null

    fun <T> runExclusive(context: Context, block: () -> T): T {
        if (processLock.isHeldByCurrentThread) {
            processLock.lock()
        } else {
            processLock.lockInterruptibly()
        }
        val outermost = processLock.holdCount == 1
        try {
            if (outermost) {
                acquireFileLock(context)
            }
            return block()
        } finally {
            try {
                if (outermost) {
                    releaseFileLock()
                }
            } finally {
                processLock.unlock()
            }
        }
    }

    private fun acquireFileLock(context: Context) {
        val channel = RandomAccessFile(lockFile(context), "rw").channel
        try {
            val acquiredLock = channel.lock()
            lockChannel = channel
            fileLock = acquiredLock
        } catch (error: Throwable) {
            channel.close()
            throw error
        }
    }

    private fun releaseFileLock() {
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

    private fun lockFile(context: Context): File =
        File((context.applicationContext ?: context).filesDir, LOCK_FILE_NAME)
}

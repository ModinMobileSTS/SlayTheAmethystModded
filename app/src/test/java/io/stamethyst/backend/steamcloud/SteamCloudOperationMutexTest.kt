package io.stamethyst.backend.steamcloud

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SteamCloudOperationMutexTest {
    @Test
    fun nestedOperationsAreReentrantAndUseApplicationFilesDir() {
        val roots = TestRoots.create()
        try {
            val lockFile = File(roots.filesDir, "steam-cloud-operation.lock")
            var innerOperationRan = false

            SteamCloudOperationMutex.runExclusive(roots.context) {
                assertTrue(lockFile.isFile)
                SteamCloudOperationMutex.runExclusive(roots.context) {
                    innerOperationRan = true
                }
            }

            assertTrue(innerOperationRan)
            assertTrue(lockFile.isFile)
            assertTrue(lockFile.parentFile == roots.filesDir)
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun waitingOperationCanBeInterrupted() {
        val roots = TestRoots.create()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiterEntered = AtomicBoolean(false)
        val waiterInterrupted = AtomicBoolean(false)

        val holder = Thread {
            SteamCloudOperationMutex.runExclusive(roots.context) {
                entered.countDown()
                release.await()
            }
        }
        holder.start()

        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val waiter = Thread {
                try {
                    SteamCloudOperationMutex.runExclusive(roots.context) {
                        waiterEntered.set(true)
                    }
                } catch (_: InterruptedException) {
                    waiterInterrupted.set(true)
                }
            }
            waiter.start()
            waiter.interrupt()
            waiter.join(5_000)

            assertFalse(waiter.isAlive)
            assertFalse(waiterEntered.get())
            assertTrue(waiterInterrupted.get())
        } finally {
            release.countDown()
            holder.join(5_000)
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun interruptedOwnerCanReenterForRollback() {
        val roots = TestRoots.create()
        try {
            var nestedOperationRan = false
            SteamCloudOperationMutex.runExclusive(roots.context) {
                Thread.currentThread().interrupt()
                SteamCloudOperationMutex.runExclusive(roots.context) {
                    nestedOperationRan = true
                    assertTrue(Thread.currentThread().isInterrupted)
                }
            }
            assertTrue(nestedOperationRan)
        } finally {
            Thread.interrupted()
            roots.rootDir.deleteRecursively()
        }
    }

    private class TestRoots private constructor(
        val rootDir: File,
        val filesDir: File,
        val context: Context,
    ) {
        companion object {
            fun create(): TestRoots {
                val rootDir = Files.createTempDirectory("steam-cloud-operation-mutex-").toFile()
                val filesDir = File(rootDir, "files").apply { mkdirs() }
                return TestRoots(
                    rootDir = rootDir,
                    filesDir = filesDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getApplicationContext(): Context = this

                        override fun getFilesDir(): File = filesDir

                        override fun getPackageName(): String = "io.stamethyst.test"
                    },
                )
            }
        }
    }
}

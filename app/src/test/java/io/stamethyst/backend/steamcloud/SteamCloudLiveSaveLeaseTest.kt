package io.stamethyst.backend.steamcloud

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudLiveSaveLeaseTest {
    @Test
    fun mutationFailsFastWhileGameThreadOwnsLease() {
        val roots = TestRoots.create()
        val attempted = CountDownLatch(1)
        val rejected = AtomicBoolean(false)
        try {
            SteamCloudLiveSaveLease.acquireForGame(roots.context).use {
                val mutator = Thread {
                    attempted.countDown()
                    try {
                        SteamCloudLiveSaveLease.acquireForMutation(roots.context).close()
                    } catch (_: SteamCloudLiveSaveInUseException) {
                        rejected.set(true)
                    }
                }
                mutator.start()
                assertTrue(attempted.await(5L, TimeUnit.SECONDS))
                mutator.join(5_000L)
                assertFalse(mutator.isAlive)
                assertTrue(rejected.get())
            }
        } finally {
            roots.root.deleteRecursively()
        }
    }

    @Test
    fun mutationRejectsAnExternalProcessStyleFileLock() {
        val roots = TestRoots.create()
        val lockFile = File(roots.files, "sts-live-save.lock")
        try {
            RandomAccessFile(lockFile, "rw").channel.use { channel ->
                channel.lock().use {
                    var rejected = false
                    try {
                        SteamCloudLiveSaveLease.acquireForMutation(roots.context).close()
                    } catch (_: SteamCloudLiveSaveInUseException) {
                        rejected = true
                    }
                    assertTrue(rejected)
                }
            }
        } finally {
            roots.root.deleteRecursively()
        }
    }

    private class TestRoots(
        val root: File,
        val files: File,
        val context: Context,
    ) {
        companion object {
            fun create(): TestRoots {
                val root = Files.createTempDirectory("steam-cloud-live-save-lease").toFile()
                val files = File(root, "files").apply { mkdirs() }
                val context = object : ContextWrapper(Application()) {
                    override fun getApplicationContext(): Context = this
                    override fun getFilesDir(): File = files
                    override fun getPackageName(): String = "io.stamethyst.test"
                }
                return TestRoots(root, files, context)
            }
        }
    }
}

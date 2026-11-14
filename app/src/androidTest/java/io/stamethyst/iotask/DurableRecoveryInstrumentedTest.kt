package io.stamethyst.iotask

import android.content.*
import android.content.Context
import android.os.*
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.iotask.durable.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableRecoveryInstrumentedTest {
    @Test fun checkpointSurvivesProcessKill() = verify("checkpoint")
    @Test fun committedArtifactIsNotReplayed() = verify("committed")
    @Test fun interruptedCleanupResumes() = verify("cleanup")
    @Test fun planCollectionSurvivesProcessKillAndDeletesRecords() = verify("reclaim")
    @Test fun partialBlockAfterProcessKillReplaysFromStart() = verify("block-write")

    @Test fun blockFailureInvalidationAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "durable-recovery/${UUID.randomUUID()}")
        val data = "verified-block".toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val layout = listOf(WriteBlock(0, data.size.toLong(), hash))
        try {
            ReplayableBlockWrite.open(root, "v1", data.size.toLong(), layout).use {
                try { it.write(0, "short".byteInputStream()); fail("Short block accepted") } catch (_: IllegalStateException) { }
                assertFalse(it.isComplete(0))
                try { it.write(0, ByteArray(data.size).inputStream()); fail("Bad digest accepted") } catch (_: IllegalStateException) { }
                assertFalse(it.isComplete(0))
                it.write(0, data.inputStream())
                assertTrue(it.verified())
                it.revert(0)
            }
            ReplayableBlockWrite.open(root, "v1", data.size.toLong(), layout).use {
                assertFalse(it.isComplete(0))
                it.write(0, data.inputStream())
                assertTrue(it.verified())
                assertArrayEquals(data, it.candidate.readBytes())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun pausedMetadataSurvivesReopenAndCancelCleansFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "durable-recovery/${UUID.randomUUID()}")
        try {
            FileTaskStore(root).use {
                val manager = TaskManager(it, durableProbeFactory("pause", false), 1)
                manager.publish(Definition("probe"), "probe")
                assertEquals(State.PAUSED, manager.run("probe").state)
            }
            FileTaskStore(root).use {
                val manager = TaskManager(it, durableProbeFactory("pause", false), 1)
                assertEquals("15", manager.snapshot("probe").metadata["offset"])
                assertTrue(manager.recover().isEmpty())
                assertEquals(State.CANCELLED, manager.cancel("probe").state)
                assertFalse(File(it.workspace("probe"), "part").exists())
            }
            FileTaskStore(root).use { assertEquals(State.CANCELLED, it.read("probe")!!.state) }
        } finally { root.deleteRecursively() }
    }

    private fun verify(mode: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val case = UUID.randomUUID().toString()
        val root = File(context.filesDir, "durable-recovery/$case")
        val connected = CountDownLatch(1)
        val died = CountDownLatch(1)
        val remote = AtomicReference<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                remote.set(service)
                service.linkToDeath({ died.countDown() }, 0)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val intent = Intent(context, DurableRecoveryTestService::class.java)
        var bound = false
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            assertTrue(bound)
            assertTrue(connected.await(15, TimeUnit.SECONDS))
            Messenger(remote.get()).send(Message.obtain().apply {
                data = Bundle().apply { putString("case", case); putString("mode", mode) }
            })
            assertTrue("Execution process must die", died.await(30, TimeUnit.SECONDS))
            context.unbindService(connection)
            bound = false
            FileTaskStore(root).use {
                if (mode == "block-write") {
                    val data = ByteArray(128 * 1024) { (it % 251).toByte() }
                    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(data)
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    ReplayableBlockWrite.open(File(root, "blocks"), "v1", data.size.toLong(),
                        listOf(WriteBlock(0, data.size.toLong(), hash))).use { writer ->
                        assertFalse(writer.isComplete(0))
                        assertArrayEquals(data.copyOfRange(0, 65536), writer.candidate.readBytes().copyOfRange(0, 65536))
                        writer.write(0, data.inputStream())
                        assertTrue(writer.verified())
                        assertArrayEquals(data, writer.candidate.readBytes())
                    }
                    return
                }
                val before = requireNotNull(it.read("probe"))
                if (mode == "reclaim") {
                    assertEquals(State.SUCCEEDED, before.state)
                    val child = requireNotNull(before.metadata["child"])
                    assertEquals("started", it.read(child)!!.metadata["reclaim"])
                    assertTrue(File(root, "work/$child/part").exists())
                    val manager = TaskManager(it, reclaimProbeFactory(false), 1)
                    assertEquals(State.SUCCEEDED, runBlocking { manager.recover().single() }.state)
                    assertTrue(it.all().isEmpty())
                    assertTrue(File(root, "work").listFiles().orEmpty().isEmpty())
                    return
                }
                assertEquals(if (mode == "cleanup") State.CLEANING else State.RUNNING, before.state)
                if (mode != "cleanup") assertNotEquals(Process.myPid().toString(), before.metadata["writerPid"])
                val manager = TaskManager(it, durableProbeFactory(mode, false), 1)
                val result = runBlocking { manager.recover().single() }
                assertEquals(if (mode == "cleanup") State.CANCELLED else State.SUCCEEDED, result.state)
                if (mode == "cleanup") {
                    assertEquals("part-deleted", result.metadata["cleanup"])
                    assertFalse(File(it.workspace("probe"), "part").exists())
                }
                if (mode == "committed") {
                    assertEquals(before.metadata["writerPid"], result.metadata["writerPid"])
                    assertEquals("artifact", result.output["artifact"])
                }
                assertTrue(runBlocking { manager.recover().isEmpty() })
            }
            FileTaskStore(root).use {
                assertEquals(if (mode == "cleanup") State.CANCELLED else State.SUCCEEDED, it.read("probe")!!.state)
            }
        } finally {
            if (bound) context.unbindService(connection)
            context.stopService(intent)
            root.deleteRecursively()
        }
    }
}

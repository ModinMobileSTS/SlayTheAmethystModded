package io.stamethyst.iotask

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
class TaskProcessRecoveryInstrumentedTest {
    @Test fun exitAfterCheckpointContinues() = verify("exit", committed = false)
    @Test fun crashAfterCheckpointContinues() = verify("crash", committed = false)
    @Test fun exitAfterArtifactDoesNotReplay() = verify("exit", committed = true)
    @Test fun crashAfterArtifactDoesNotReplay() = verify("crash", committed = true)

    private fun verify(mode: String, committed: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val case = UUID.randomUUID().toString()
        val root = File(context.filesDir, "io-task-recovery/$case")
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
        val intent = Intent().setComponent(ComponentName(context, ProcessRecoveryTestService::class.java))
        var bound = false
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            assertTrue("Could not bind test-only process", bound)
            assertTrue("Test service did not connect", connected.await(15, TimeUnit.SECONDS))
            Messenger(remote.get()).send(Message.obtain().apply {
                data = Bundle().apply {
                    putString("case", case)
                    putString("mode", mode)
                    putBoolean("committed", committed)
                }
            })
            assertTrue("Execution process did not die", died.await(30, TimeUnit.SECONDS))
            context.unbindService(connection)
            bound = false

            // Fresh store and group, no in-memory snapshot transferred from the dead process.
            val store = ProcessTestStore(root)
            val id = TaskId("probe")
            val interrupted = requireNotNull(store.read(id))
            assertEquals(TaskState.RUNNING, interrupted.state)
            assertEquals(1, interrupted.runCount)
            assertNotEquals(Process.myPid().toString(), interrupted.checkpoint["writerPid"])
            assertEquals("verified-prefix", interrupted.checkpoint["offset"])
            var executions = 0
            var callbacks = 0
            val task = object : Task(id, "process-probe") {
                override suspend fun recover(context: TaskContext): RecoveryResult {
                    assertEquals("verified-prefix", context.checkpointValue("offset"))
                    return if (File(root, "artifact").exists()) {
                        assertEquals("verified-result", File(root, "artifact").readText())
                        RecoveryResult.Completed
                    } else RecoveryResult.Continue
                }
                override suspend fun execute(context: TaskContext): TaskResult {
                    executions++
                    context.checkpoint("recovered", "yes")
                    return TaskResult.Completed
                }
            }
            runBlocking {
                val group = WorkerGroup(store, 1, TaskCompletionListener { callbacks++ })
                assertEquals(TaskState.COMPLETED, group.recover(listOf(task)).single().state)
                assertEquals(2, ProcessTestStore(root).read(id)?.runCount)
                assertEquals(TaskState.COMPLETED, group.await(id).state)
                assertTrue("Completed task was replayed", group.recover(listOf(task)).isEmpty())
            }
            assertEquals(if (committed) 0 else 1, executions)
            assertEquals(1, callbacks)
            assertEquals(TaskState.COMPLETED, ProcessTestStore(root).read(id)?.state)
        } finally {
            if (bound) context.unbindService(connection)
            context.stopService(intent)
            // Only this test's UUID directory, never launcher/user data.
            root.deleteRecursively()
        }
    }
}

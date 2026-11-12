package io.stamethyst.iotask

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskModelTest {
    @Test
    fun successfulTaskPersistsCheckpointAndCallsCompletion() = runBlocking {
        val store = InMemoryTaskStore()
        val completed = AtomicReference<TaskSnapshot?>()
        val group = WorkerGroup(store, 1, object : TaskCompletionListener {
            override fun onCompleted(snapshot: TaskSnapshot) {
                completed.set(snapshot)
            }
        })

        val result = group.submit(object : Task(TaskId("success"), "test") {
            override suspend fun execute(context: TaskContext): TaskResult {
                context.checkpoint("phase", "finished")
                return TaskResult.Completed
            }
        })

        assertEquals(TaskState.COMPLETED, result.state)
        assertEquals("finished", result.checkpoint["phase"])
        assertEquals(result, completed.get())
    }

    @Test
    fun thrownErrorBecomesStableFailure() = runBlocking {
        val group = WorkerGroup(InMemoryTaskStore(), 1)
        val result = group.submit(object : Task(TaskId("failure"), "test") {
            override suspend fun execute(context: TaskContext): TaskResult {
                error("broken")
            }
        })

        assertEquals(TaskState.FAILED, result.state)
        assertEquals("UNEXPECTED_ERROR", result.failure?.code)
        assertEquals("broken", result.failure?.message)
    }

    @Test
    fun waitingTaskCanResumeFromItsCheckpoint() = runBlocking {
        val store = InMemoryTaskStore()
        val group = WorkerGroup(store, 1)
        val runs = AtomicInteger()
        val task = object : Task(TaskId("waiting"), "test") {
            override suspend fun execute(context: TaskContext): TaskResult {
                runs.incrementAndGet()
                if (context.checkpointValue("ready") != "yes") {
                    context.checkpoint("ready", "no")
                    return TaskResult.Waiting("needs user input")
                }
                return TaskResult.Completed
            }
        }

        val waiting = group.submit(task)
        assertEquals(TaskState.WAITING, waiting.state)
        store.write(waiting.copy(checkpoint = mapOf("ready" to "yes")))
        val done = group.resume(task)

        assertEquals(TaskState.COMPLETED, done.state)
        assertEquals(2, runs.get())
        assertEquals(TaskState.COMPLETED, group.await(task.id).state)
    }

    @Test
    fun cancellationStopsWorkerAndReportsCancelled() = runBlocking {
        val store = InMemoryTaskStore()
        val group = WorkerGroup(store, 1)
        val taskId = TaskId("cancel")
        val running = async {
            group.submit(object : Task(taskId, "test") {
                override suspend fun execute(context: TaskContext): TaskResult {
                    while (true) {
                        context.ensureNotCancelled()
                        yield()
                    }
                }
            })
        }
        while (store.read(taskId)?.state != TaskState.RUNNING) yield()
        assertTrue(group.cancel(taskId))

        assertEquals(TaskState.CANCELLED, running.await().state)
    }

    @Test
    fun workerGroupHonorsParallelLimit() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val group = WorkerGroup(InMemoryTaskStore(), 2)
        val tasks = (1..6).map { number ->
            object : Task(TaskId("parallel-$number"), "test") {
                override suspend fun execute(context: TaskContext): TaskResult {
                    val now = active.incrementAndGet()
                    maximum.updateAndGet { old -> maxOf(old, now) }
                    delay(20)
                    active.decrementAndGet()
                    return TaskResult.Completed
                }
            }
        }

        val results = tasks.map { task -> async { group.submit(task) } }.map { it.await() }

        assertTrue(results.all { it.state == TaskState.COMPLETED })
        assertEquals(2, maximum.get())
    }

    @Test
    fun recoveryRerunsPersistedRunningTask() = runBlocking {
        val store = InMemoryTaskStore()
        val id = TaskId("recovery")
        store.write(TaskSnapshot(id, "test", TaskState.RUNNING, mapOf("offset" to "4")))
        val group = WorkerGroup(store, 1)
        val observed = AtomicReference<String?>()

        val result = group.recover(listOf(object : Task(id, "test") {
            override suspend fun recover(context: TaskContext): RecoveryResult = RecoveryResult.Continue

            override suspend fun execute(context: TaskContext): TaskResult {
                observed.set(context.checkpointValue("offset"))
                return TaskResult.Completed
            }
        })).single()

        assertEquals("4", observed.get())
        assertEquals(TaskState.COMPLETED, result.state)
    }

    @Test
    fun queuedCancellationNeverRunsBodyAndReleasesNoExtraPermit() = runBlocking {
        withTimeout(5_000) {
            val store = InMemoryTaskStore()
            val group = WorkerGroup(store, 1)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = async {
                group.submit(testTask("first") { entered.complete(Unit); release.await(); TaskResult.Completed })
            }
            entered.await()
            val bodyRan = AtomicInteger()
            val second = async {
                group.submit(testTask("second") { bodyRan.incrementAndGet(); TaskResult.Completed })
            }
            while (group.snapshot(TaskId("second")) == null) yield()
            assertEquals(TaskState.QUEUED, group.snapshot(TaskId("second"))?.state)
            assertTrue(group.cancel(TaskId("second")))
            assertEquals(TaskState.CANCELLED, second.await().state)
            assertEquals(0, bodyRan.get())
            release.complete(Unit)
            assertEquals(TaskState.COMPLETED, first.await().state)
        }
    }

    @Test
    fun callbackFailureDoesNotChangePersistedSuccessOrBlockNextWorker() = runBlocking {
        val errors = AtomicInteger()
        val callbacks = AtomicInteger()
        val group = WorkerGroup(InMemoryTaskStore(), 1, TaskCompletionListener {
            callbacks.incrementAndGet()
            error("observer failure")
        }, { errors.incrementAndGet() })
        val task = testTask("callback") { TaskResult.Completed }
        assertEquals(TaskState.COMPLETED, group.submit(task).state)
        assertEquals(TaskState.COMPLETED, group.await(task.id).state)
        assertEquals(TaskState.COMPLETED, group.submit(testTask("next") { TaskResult.Completed }).state)
        assertEquals(2, errors.get())
        assertEquals(2, callbacks.get())
    }

    @Test
    fun waitingReleasesPermitAndCancelWaitingNotifiesOnce() = runBlocking {
        val notifications = AtomicInteger()
        val group = WorkerGroup(InMemoryTaskStore(), 1, TaskCompletionListener { notifications.incrementAndGet() })
        val waiting = testTask("wait-cancel") { TaskResult.Waiting("authentication") }
        assertEquals(TaskState.WAITING, group.submit(waiting).state)
        assertEquals(0, notifications.get())
        assertEquals(TaskState.COMPLETED, group.submit(testTask("other") { TaskResult.Completed }).state)
        assertTrue(group.cancel(waiting.id))
        assertEquals(TaskState.CANCELLED, group.await(waiting.id).state)
        assertEquals(false, group.cancel(waiting.id))
        assertEquals(2, notifications.get())
    }

    @Test
    fun recoveryRequiresReconciliationAndDoesNotWakeWaitingOrReplayCompleted() = runBlocking {
        val store = InMemoryTaskStore()
        val ran = AtomicInteger()
        val running = testTask("interrupted") { ran.incrementAndGet(); TaskResult.Completed }
        val waiting = testTask("saved-wait") { ran.incrementAndGet(); TaskResult.Completed }
        val completed = testTask("saved-done") { ran.incrementAndGet(); TaskResult.Completed }
        store.write(TaskSnapshot(running.id, "test", TaskState.RUNNING))
        store.write(TaskSnapshot(waiting.id, "test", TaskState.WAITING, waitingReason = "user input"))
        store.write(TaskSnapshot(completed.id, "test", TaskState.COMPLETED))
        val group = WorkerGroup(store, 1)
        val results = group.recover(listOf(running, waiting, completed))
        assertEquals(1, results.size)
        assertEquals(TaskState.WAITING, results.single().state)
        assertEquals(0, ran.get())
        assertEquals("user input", group.snapshot(waiting.id)?.waitingReason)
    }

    @Test
    fun recoveryCanRecognizeAlreadyCommittedResultWithoutExecutingAgain() = runBlocking {
        val store = InMemoryTaskStore()
        val task = object : Task(TaskId("committed"), "test") {
            override suspend fun recover(context: TaskContext): RecoveryResult = RecoveryResult.Completed
            override suspend fun execute(context: TaskContext): TaskResult = error("must not replay")
        }
        store.write(TaskSnapshot(task.id, task.type, TaskState.RUNNING, mapOf("commit" to "done")))
        assertEquals(TaskState.COMPLETED, WorkerGroup(store, 1).recover(listOf(task)).single().state)
    }

    @Test
    fun concurrentDuplicateIsRejectedAndOriginalRemainsAwaitable() = runBlocking {
        withTimeout(5_000) {
            val group = WorkerGroup(InMemoryTaskStore(), 1)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val task = testTask("duplicate") { entered.complete(Unit); release.await(); TaskResult.Completed }
            val original = async { group.submit(task) }
            entered.await()
            var rejected = false
            try {
                group.submit(task)
            } catch (_: IllegalStateException) { rejected = true }
            assertTrue("duplicate must be rejected", rejected)
            release.complete(Unit)
            assertEquals(TaskState.COMPLETED, original.await().state)
            assertEquals(TaskState.COMPLETED, group.await(task.id).state)
        }
    }

    @Test
    fun explicitFailureAndRetryUseReconciliation() = runBlocking {
        val group = WorkerGroup(InMemoryTaskStore(), 1)
        val attempts = AtomicInteger()
        val task = object : Task(TaskId("retry"), "test") {
            override suspend fun recover(context: TaskContext): RecoveryResult = RecoveryResult.Continue
            override suspend fun execute(context: TaskContext): TaskResult {
                return if (attempts.incrementAndGet() == 1) {
                    context.checkpoint("download", "verified")
                    TaskResult.Failed(TaskFailure("OFFLINE", "network unavailable", true))
                } else {
                    assertEquals("verified", context.checkpointValue("download"))
                    TaskResult.Completed
                }
            }
        }
        assertEquals("OFFLINE", group.submit(task).failure?.code)
        assertEquals(TaskState.COMPLETED, group.retry(task).state)
        assertEquals(2, group.await(task.id).runCount)
    }

    @Test
    fun callerCancellationUnwindsBodyAndRecordsCancelled() = runBlocking {
        withTimeout(5_000) {
            val group = WorkerGroup(InMemoryTaskStore(), 1)
            val entered = CompletableDeferred<Unit>()
            val unwound = AtomicInteger()
            val id = TaskId("caller-cancel")
            val caller = launch {
                group.submit(testTask(id.value) {
                    try { entered.complete(Unit); CompletableDeferred<Unit>().await(); TaskResult.Completed }
                    finally { unwound.incrementAndGet() }
                })
            }
            entered.await()
            caller.cancelAndJoin()
            assertEquals(1, unwound.get())
            assertEquals(TaskState.CANCELLED, group.snapshot(id)?.state)
            assertEquals(TaskState.COMPLETED, group.submit(testTask("after-cancel") { TaskResult.Completed }).state)
        }
    }

    @Test
    fun storeFailureIsNotReportedAsTaskFailureAndBodyIsNotStarted() = runBlocking {
        val backing = InMemoryTaskStore()
        val ran = AtomicInteger()
        val store = object : TaskStore {
            override fun read(id: TaskId) = backing.read(id)
            override fun all() = backing.all()
            override fun write(snapshot: TaskSnapshot) {
                if (snapshot.state == TaskState.RUNNING) throw java.io.IOException("disk unavailable")
                backing.write(snapshot)
            }
        }
        val group = WorkerGroup(store, 1)
        val task = testTask("store-fail") { ran.incrementAndGet(); TaskResult.Completed }
        try {
            group.submit(task)
            error("store error swallowed")
        } catch (expected: java.io.IOException) {
            assertEquals("disk unavailable", expected.message)
        }
        assertEquals(0, ran.get())
        assertEquals(TaskState.QUEUED, backing.read(task.id)?.state)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidParallelismIsRejected() {
        WorkerGroup(InMemoryTaskStore(), 0)
    }

    @Test
    fun failureReleasesWorkerPermitAndEmitsSingleTerminalNotification() = runBlocking {
        val notifications = mutableListOf<TaskState>()
        val group = WorkerGroup(InMemoryTaskStore(), 1, TaskCompletionListener { notifications += it.state })
        assertEquals(TaskState.FAILED, group.submit(testTask("throws") { error("broken") }).state)
        assertEquals(TaskState.COMPLETED, group.submit(testTask("following") { TaskResult.Completed }).state)
        assertEquals(listOf(TaskState.FAILED, TaskState.COMPLETED), notifications)
    }

    @Test
    fun checkpointWriteFailurePreservesLastConfirmedCheckpointAndDoesNotNotifySuccess() = runBlocking {
        val backing = InMemoryTaskStore()
        val callbacks = AtomicInteger()
        val store = object : TaskStore {
            override fun read(id: TaskId) = backing.read(id)
            override fun all() = backing.all()
            override fun write(snapshot: TaskSnapshot) {
                if (snapshot.checkpoint["phase"] == "unsafe") throw java.io.IOException("checkpoint failed")
                backing.write(snapshot)
            }
        }
        val group = WorkerGroup(store, 1, TaskCompletionListener { callbacks.incrementAndGet() })
        val task = testTask("checkpoint-fail") {
            it.checkpoint("phase", "safe")
            it.checkpoint("phase", "unsafe")
            TaskResult.Completed
        }
        try {
            group.submit(task)
            error("checkpoint error swallowed")
        } catch (_: java.io.IOException) { }
        assertEquals("safe", backing.read(task.id)?.checkpoint?.get("phase"))
        assertEquals(TaskState.RUNNING, backing.read(task.id)?.state)
        assertEquals(0, callbacks.get())
    }

    private fun testTask(name: String, action: suspend (TaskContext) -> TaskResult): Task =
        object : Task(TaskId(name), "test") {
            override suspend fun execute(context: TaskContext): TaskResult = action(context)
        }
}

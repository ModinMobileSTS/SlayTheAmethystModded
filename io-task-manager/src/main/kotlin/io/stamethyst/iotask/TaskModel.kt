package io.stamethyst.iotask

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

@JvmInline
value class TaskId(val value: String) {
    init { require(value.isNotBlank()) { "Task ID must not be blank" } }
}

enum class TaskState {
    QUEUED, RUNNING, WAITING, COMPLETED, FAILED, CANCELLED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

data class TaskFailure(val code: String, val message: String, val retryable: Boolean = false)

data class TaskSnapshot(
    val id: TaskId,
    val type: String,
    val state: TaskState,
    val checkpoint: Map<String, String> = emptyMap(),
    val waitingReason: String? = null,
    val failure: TaskFailure? = null,
    val runCount: Int = 0,
)

sealed interface TaskResult {
    data object Completed : TaskResult
    data class Waiting(val reason: String) : TaskResult
    data object Cancelled : TaskResult
    data class Failed(val failure: TaskFailure) : TaskResult
}

/** Recovery is reconciliation, not an implicit replay of execute(). */
sealed interface RecoveryResult {
    data object Continue : RecoveryResult
    data object Completed : RecoveryResult
    data class Waiting(val reason: String) : RecoveryResult
    data class Failed(val failure: TaskFailure) : RecoveryResult
}

/** Writes must finish before returning. A backend failure is propagated, never a task failure. */
interface TaskStore {
    fun read(id: TaskId): TaskSnapshot?
    fun write(snapshot: TaskSnapshot)
    fun all(): List<TaskSnapshot>
}

/** Test/local model only: this store does not survive process exit. */
class InMemoryTaskStore : TaskStore {
    private val snapshots = ConcurrentHashMap<TaskId, TaskSnapshot>()
    override fun read(id: TaskId): TaskSnapshot? = snapshots[id]?.detached()
    override fun write(snapshot: TaskSnapshot) { snapshots[snapshot.id] = snapshot.detached() }
    override fun all(): List<TaskSnapshot> = snapshots.values.map { it.detached() }
}

private fun TaskSnapshot.detached() = copy(checkpoint = checkpoint.toMap())

fun interface TaskCompletionListener {
    /** One notification per terminal attempt in this process; not a durable exactly-once event. */
    fun onCompleted(snapshot: TaskSnapshot)
}

class TaskContext internal constructor(
    val id: TaskId,
    initialCheckpoint: Map<String, String>,
    private val saveCheckpoint: (Map<String, String>) -> Unit,
) {
    private var values = initialCheckpoint.toMap()

    /** Single-task coroutine use only; checkpoint keys/values must not contain credentials. */
    suspend fun checkpoint(key: String, value: String) {
        ensureNotCancelled()
        val next = values + (key to value)
        saveCheckpoint(next)
        values = next
    }

    fun checkpointValue(key: String): String? = values[key]
    fun checkpointValues(): Map<String, String> = values.toMap()
    suspend fun ensureNotCancelled() = currentCoroutineContext().ensureActive()
}

abstract class Task(val id: TaskId, val type: String) {
    init { require(type.isNotBlank()) { "Task type must not be blank" } }

    protected abstract suspend fun execute(context: TaskContext): TaskResult

    /** Must inspect existing effects before explicitly choosing Continue or Completed. */
    protected open suspend fun recover(context: TaskContext): RecoveryResult =
        RecoveryResult.Waiting("Recovery reconciliation required")

    internal suspend fun run(context: TaskContext): TaskResult = execute(context)
    internal suspend fun reconcile(context: TaskContext): RecoveryResult = recover(context)
}

private class StoreFailure(val original: Exception) : Exception(original)

/** Single-use executor; only WorkerGroup creates and owns workers. */
class Worker internal constructor(
    private val task: Task,
    private val store: TaskStore,
    private val queued: TaskSnapshot,
    parent: Job?,
) {
    // Separate child job: cancelling this worker never cancels its caller or siblings.
    private val control = Job(parent)
    internal fun cancel() { control.cancel(CancellationException("Task cancellation requested")) }

    internal suspend fun run(permits: Semaphore, recovering: Boolean): TaskSnapshot {
        var current = queued
        val context = TaskContext(task.id, queued.checkpoint) { values ->
            try {
                val next = current.copy(checkpoint = values)
                store.write(next)
                current = next
            } catch (error: Exception) {
                throw StoreFailure(error)
            }
        }
        val result = try {
            withContext(control) {
                permits.withPermit {
                    current = current.copy(state = TaskState.RUNNING, runCount = current.runCount + 1)
                    try { store.write(current) } catch (error: Exception) { throw StoreFailure(error) }
                    if (recovering) {
                        when (val decision = task.reconcile(context)) {
                            RecoveryResult.Continue -> task.run(context)
                            RecoveryResult.Completed -> TaskResult.Completed
                            is RecoveryResult.Waiting -> TaskResult.Waiting(decision.reason)
                            is RecoveryResult.Failed -> TaskResult.Failed(decision.failure)
                        }
                    } else task.run(context)
                }
            }
        } catch (error: StoreFailure) {
            throw error.original
        } catch (_: CancellationException) {
            TaskResult.Cancelled
        } catch (error: Exception) {
            TaskResult.Failed(TaskFailure("UNEXPECTED_ERROR", error.message ?: error.javaClass.simpleName))
        } finally {
            control.complete()
        }
        val final = when (result) {
            TaskResult.Completed -> current.copy(state = TaskState.COMPLETED)
            TaskResult.Cancelled -> current.copy(state = TaskState.CANCELLED)
            is TaskResult.Waiting -> current.copy(state = TaskState.WAITING, waitingReason = result.reason)
            is TaskResult.Failed -> current.copy(state = TaskState.FAILED, failure = result.failure)
        }.copy(checkpoint = context.checkpointValues())
        store.write(final)
        return final
    }
}

/**
 * Process-local worker owner. Concurrent submissions are bounded, including recovery attempts.
 * Caller cancellation is recorded after task unwind, then propagated to that caller.
 * This is not a cross-process durable scheduler or a file commit journal.
 */
class WorkerGroup(
    private val store: TaskStore,
    maxParallelism: Int,
    private val listener: TaskCompletionListener = TaskCompletionListener {},
    private val onCallbackError: (Exception) -> Unit = {},
) {
    init { require(maxParallelism > 0) { "maxParallelism must be positive" } }
    private val lock = Any()
    private val semaphore = Semaphore(maxParallelism)
    private val workers = mutableMapOf<TaskId, Worker>()
    private val completions = mutableMapOf<TaskId, CompletableDeferred<TaskSnapshot>>()

    /** Persists QUEUED before waiting for a worker permit; returns at terminal or WAITING. */
    suspend fun submit(task: Task): TaskSnapshot = execute(task, Mode.NEW)

    /** Explicitly wakes a WAITING task. FAILED tasks use retry(), never implicit replay. */
    suspend fun resume(task: Task): TaskSnapshot = execute(task, Mode.RESUME)

    /** Retry reconciles previous effects, just like process recovery. */
    suspend fun retry(task: Task): TaskSnapshot = execute(task, Mode.RETRY)

    /** WAITING remains waiting after restart until resume() is explicitly requested. */
    suspend fun recover(tasksToRestore: Iterable<Task>): List<TaskSnapshot> {
        val results = mutableListOf<TaskSnapshot>()
        for (task in tasksToRestore) {
            val state = store.read(task.id)?.state
            if (state == TaskState.RUNNING || state == TaskState.QUEUED) {
                results += execute(task, Mode.RECOVER)
            }
        }
        return results
    }

    fun cancel(id: TaskId): Boolean {
        var cancelled: TaskSnapshot? = null
        val accepted = synchronized(lock) {
            val worker = workers[id]
            if (worker != null) {
                worker.cancel()
                true
            } else {
                val snapshot = store.read(id)
                if (snapshot?.state != TaskState.WAITING) false else {
                    val result = snapshot.copy(state = TaskState.CANCELLED, waitingReason = null)
                    store.write(result)
                    cancelled = result
                    completions[id] = CompletableDeferred(result)
                    true
                }
            }
        }
        cancelled?.let(::notifyCompletion)
        return accepted
    }

    fun snapshot(id: TaskId): TaskSnapshot? = store.read(id)

    /** Waits for the current attempt to finish or yield WAITING, not for an indefinite future resume. */
    suspend fun await(id: TaskId): TaskSnapshot {
        val pending = synchronized(lock) { completions[id] }
        if (pending != null) return pending.await()
        val saved = store.read(id) ?: error("Unknown task: ${id.value}")
        check(saved.state.isTerminal || saved.state == TaskState.WAITING) { "Task has no active worker" }
        return saved
    }

    private enum class Mode { NEW, RESUME, RETRY, RECOVER }

    private suspend fun execute(task: Task, mode: Mode): TaskSnapshot {
        val parent = currentCoroutineContext()[Job]
        val (worker, completion) = synchronized(lock) {
            check(task.id !in workers) { "Task is already executing: ${task.id.value}" }
            val previous = store.read(task.id)
            require(previous == null || previous.type == task.type) { "Task type mismatch" }
            when (mode) {
                Mode.NEW -> require(previous == null) { "Task already exists: ${task.id.value}" }
                Mode.RESUME -> require(previous?.state == TaskState.WAITING) { "Task is not waiting" }
                Mode.RETRY -> require(previous?.state == TaskState.FAILED) { "Task is not failed" }
                Mode.RECOVER -> require(previous?.state == TaskState.QUEUED || previous?.state == TaskState.RUNNING)
            }
            val queued = (previous ?: TaskSnapshot(task.id, task.type, TaskState.QUEUED)).copy(
                state = TaskState.QUEUED, waitingReason = null, failure = null,
            )
            store.write(queued)
            val created = Worker(task, store, queued, parent)
            val done = CompletableDeferred<TaskSnapshot>()
            workers[task.id] = created
            completions[task.id] = done
            created to done
        }
        try {
            val result = worker.run(semaphore, mode == Mode.RECOVER || mode == Mode.RETRY)
            synchronized(lock) {
                workers.remove(task.id)
                completion.complete(result)
            }
            if (result.state.isTerminal) notifyCompletion(result)
            currentCoroutineContext().ensureActive()
            return result
        } catch (error: Exception) {
            synchronized(lock) {
                workers.remove(task.id, worker)
                completion.completeExceptionally(error)
            }
            throw error
        }
    }

    private fun notifyCompletion(snapshot: TaskSnapshot) {
        try { listener.onCompleted(snapshot) } catch (error: Exception) {
            try { onCallbackError(error) } catch (_: Exception) { /* observer cannot change task outcome */ }
        }
    }
}

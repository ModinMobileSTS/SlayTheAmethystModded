package io.stamethyst.iotask.durable

import java.io.File
import kotlinx.coroutines.flow.StateFlow

enum class State {
    QUEUED, RUNNING, PAUSED, WAITING, CLEANING, REVERTING, SUCCEEDED, FAILED, CANCELLED;
    val terminal get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

enum class Control { NONE, PAUSE, CANCEL }
enum class Kind { TASK, PLAN }

data class Definition(
    val type: String,
    val version: Int = 1,
    val input: Map<String, String> = emptyMap(),
    val kind: Kind = Kind.TASK,
) {
    init { require(type.isNotBlank() && version > 0) }
}

data class Snapshot(
    val id: String,
    val definition: Definition,
    val state: State = State.QUEUED,
    val control: Control = Control.NONE,
    val metadata: Map<String, String> = emptyMap(),
    val output: Map<String, String> = emptyMap(),
    val reason: String? = null,
    val parent: String? = null,
    val childKey: String? = null,
    val attempt: Int = 0,
)

sealed interface Result {
    data class Success(val output: Map<String, String> = emptyMap()) : Result
    data class Fail(val reason: String) : Result
    data object Paused : Result
    data class Waiting(val reason: String) : Result
    /** Acknowledges quiescence, not completion of cleanup. */
    data object Cancel : Result
}

sealed interface Recovery {
    data object Execute : Recovery
    data class Finished(val result: Result) : Recovery
}

/** Reconstructed from Definition; never persist closures or live resources. */
abstract class Task {
    abstract suspend fun execute(context: Context): Result
    open suspend fun recover(context: Context): Recovery =
        Recovery.Finished(Result.Waiting("Recovery reconciliation required"))

    /** Idempotent: may be re-entered after process death. Preserve published/shared artifacts. */
    abstract suspend fun cleanup(context: Context)

    /** Idempotently release owned extra-path temporary files after the whole Plan finishes.
     * Published outputs must already live outside the managed workspace. */
    open suspend fun reclaim(context: Context) = Unit

    /** Idempotently undo this object's business effects; checkpoints are not revert targets. */
    open suspend fun revert(context: Context) {
        error("This task does not support reverting")
    }
}

/** Coordinators do not occupy the leaf-I/O semaphore while awaiting children.
 * Override revert to explicitly undo selected child effects; no automatic reverse traversal. */
abstract class TaskPlan : Task()

fun interface TaskFactory { fun create(definition: Definition): Task }

class Context internal constructor(private val manager: TaskManager, val id: String) {
    val metadata get() = manager.snapshot(id).metadata
    val control get() = manager.snapshot(id).control
    val workspace: File get() = manager.workspace(id)

    /** Merge metadata only after referenced business data has been committed. Does not yield. */
    fun checkpoint(update: Map<String, String>) = manager.checkpoint(id, update)
    fun progress(values: Map<String, String>) = manager.progress(id, values)
    fun requestPause() = manager.requestPause(id)
    fun requestCancel() = manager.requestCancel(id)
    fun child(key: String, definition: Definition): Handle = manager.child(id, key, definition)
    suspend fun await(child: Handle): Snapshot = manager.awaitChild(id, child.id)
    /** A Plan explicitly chooses which child effects to undo and in which order. */
    suspend fun revert(child: Handle): Snapshot {
        val parent = manager.snapshot(id)
        check(parent.definition.kind == Kind.PLAN && parent.state == State.REVERTING)
        require(manager.snapshot(child.id).parent == id)
        return manager.revert(child.id)
    }
}

class Handle internal constructor(private val manager: TaskManager, val id: String) {
    fun snapshot(): Snapshot = manager.snapshot(id)
    fun observe(): StateFlow<Snapshot> = manager.observe(id)
    fun progress(): StateFlow<Map<String, String>> = manager.observeProgress(id)
}

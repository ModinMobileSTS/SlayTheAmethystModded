package io.stamethyst.iotask.durable

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Owns one store exclusively. Close the store only after all manager calls have finished. */
class TaskManager(
    private val store: FileTaskStore,
    private val factory: TaskFactory,
    maxParallelism: Int,
    private val onTerminal: (Snapshot) -> Unit = {},
) {
    private val lock = Any()
    private val permits = Semaphore(maxParallelism)
    private val records = store.all().associateBy { it.id }.toMutableMap()
    private val active = mutableMapOf<String, CompletableDeferred<Snapshot>>()
    private val reclaiming = mutableSetOf<String>()
    private val views = mutableMapOf<String, MutableStateFlow<Snapshot>>()
    private val progressViews = mutableMapOf<String, MutableStateFlow<Map<String, String>>>()

    fun publish(definition: Definition, id: String = UUID.randomUUID().toString()): Handle = synchronized(lock) {
        require(id !in records) { "Task already exists" }
        save(Snapshot(id, definition.copy(input = definition.input.toMap())))
        Handle(this, id)
    }

    fun handle(id: String): Handle { snapshot(id); return Handle(this, id) }
    fun snapshot(id: String): Snapshot = synchronized(lock) { detached(records.getValue(id)) }
    fun snapshots(): List<Snapshot> = synchronized(lock) { records.values.map(::detached) }
    internal fun workspace(id: String) = store.workspace(id)
    internal fun observe(id: String): StateFlow<Snapshot> = synchronized(lock) {
        views.getOrPut(id) { MutableStateFlow(snapshot(id)) }.asStateFlow()
    }
    internal fun observeProgress(id: String): StateFlow<Map<String, String>> = synchronized(lock) {
        snapshot(id)
        progressViews.getOrPut(id) { MutableStateFlow(emptyMap()) }.asStateFlow()
    }
    internal fun progress(id: String, values: Map<String, String>) = synchronized(lock) {
        check(id in active || id in reclaiming)
        progressViews.getOrPut(id) { MutableStateFlow(emptyMap()) }.value = values.toMap()
    }
    internal fun checkpoint(id: String, update: Map<String, String>) = synchronized(lock) {
        check(id in active || id in reclaiming)
        val record = records.getValue(id)
        save(record.copy(metadata = record.metadata + update))
    }

    internal fun child(parent: String, key: String, definition: Definition): Handle = synchronized(lock) {
        require(key.isNotBlank())
        val plan = records.getValue(parent)
        check(parent in active && plan.definition.kind == Kind.PLAN)
        val existing = records.values.singleOrNull { it.parent == parent && it.childKey == key }
        if (existing != null) {
            require(existing.definition == definition) { "Child definition changed for $key" }
            return@synchronized Handle(this, existing.id)
        }
        check(plan.control == Control.NONE) { "Cannot publish children during control request" }
        val id = UUID.randomUUID().toString()
        save(Snapshot(id, definition.copy(input = definition.input.toMap()), parent = parent, childKey = key))
        Handle(this, id)
    }

    internal suspend fun awaitChild(parent: String, child: String): Snapshot {
        require(snapshot(child).parent == parent)
        return run(child)
    }

    fun requestPause(id: String): Boolean = request(id, Control.PAUSE)
    fun requestCancel(id: String): Boolean = request(id, Control.CANCEL)

    private fun request(id: String, control: Control): Boolean = synchronized(lock) {
        val record = records.getValue(id)
        if (record.state.terminal) return@synchronized false
        if (record.control != Control.CANCEL) save(record.copy(control = control))
        records.values.filter { it.parent == id }.map { it.id }.forEach { request(it, control) }
        true
    }

    suspend fun pause(id: String): Snapshot { requestPause(id); return run(id) }
    suspend fun cancel(id: String): Snapshot { requestCancel(id); return run(id) }

    suspend fun resume(id: String): Snapshot {
        synchronized(lock) {
            check(id !in active)
            require(records.getValue(id).state in setOf(State.PAUSED, State.WAITING))
            resetTree(id)
        }
        return run(id)
    }

    private fun resetTree(id: String) {
        val record = records.getValue(id)
        if (record.state == State.PAUSED || record.state == State.WAITING) {
            save(record.copy(state = State.QUEUED, control = Control.NONE, reason = null))
        }
        records.values.filter { it.parent == id }.map { it.id }.forEach(::resetTree)
    }

    suspend fun retry(id: String): Snapshot {
        synchronized(lock) {
            check(id !in active)
            val record = records.getValue(id)
            require(record.state == State.FAILED)
            // RUNNING deliberately forces reconciliation before a retry.
            save(record.copy(state = State.RUNNING, control = Control.NONE, reason = null))
        }
        return run(id)
    }

    suspend fun revertTo(id: String, node: String): Snapshot {
        synchronized(lock) {
            require(node.isNotBlank())
            check(id !in active)
            val record = records.getValue(id)
            require(record.state in setOf(State.PAUSED, State.WAITING, State.FAILED))
            check(descendants(id).none { it in active })
            save(record.copy(state = State.REVERTING, revertTarget = node, control = Control.NONE))
        }
        return run(id)
    }

    /** Call only once prior process ownership has been released (enforced by FileTaskStore). */
    suspend fun recover(): List<Snapshot> = coroutineScope {
        snapshots().filter { it.parent == null && (collectable(it) || !it.state.terminal) &&
            (it.state !in setOf(State.PAUSED, State.WAITING) || it.control != Control.NONE) }
            .map { async { run(it.id) } }.map { it.await() }
    }

    suspend fun run(id: String): Snapshot {
        val (completion, owner) = synchronized(lock) {
            active[id]?.let { return@synchronized it to false }
            val record = records.getValue(id)
            if ((record.state.terminal && !collectable(record)) || (record.state in setOf(State.PAUSED, State.WAITING) && record.control == Control.NONE)) {
                return detached(record)
            }
            CompletableDeferred<Snapshot>().also { active[id] = it } to true
        }
        if (!owner) return completion.await()
        try {
            val saved = snapshot(id)
            if (collectable(saved)) {
                reclaimTree(id)
                synchronized(lock) { completion.complete(saved); active.remove(id) }
                return saved
            }
            val task = factory.create(snapshot(id).definition)
            require((task is TaskPlan) == (snapshot(id).definition.kind == Kind.PLAN))
            val result = if (task is TaskPlan) perform(id, task) else permits.withPermit { perform(id, task) }
            if (collectable(result)) reclaimTree(id)
            synchronized(lock) { completion.complete(result); active.remove(id) }
            if (result.state.terminal) try { onTerminal(result) } catch (_: Exception) { }
            return result
        } catch (error: Exception) {
            // Preserve RUNNING/CLEANING/REVERTING for reconciliation. Never relabel store errors.
            synchronized(lock) { active.remove(id); completion.completeExceptionally(error) }
            throw error
        }
    }

    private suspend fun perform(id: String, task: Task): Snapshot {
        val initial = snapshot(id)
        val context = Context(this, id)
        if (initial.state == State.CLEANING || initial.control == Control.CANCEL) return cleanup(id, task, context)
        if (initial.state == State.REVERTING) {
            task.revert(context, requireNotNull(initial.revertTarget))
            return update(id) { it.copy(state = State.PAUSED, revertTarget = null) }
        }
        if (initial.control == Control.PAUSE && initial.state == State.QUEUED) {
            settleChildren(id, Control.PAUSE)
            return finishQuiescent(id, task, context, State.PAUSED)
        }
        update(id) { it.copy(state = State.RUNNING, attempt = it.attempt + 1) }
        val result = try {
            if (initial.state == State.RUNNING) {
                when (val recovery = task.recover(context)) {
                    Recovery.Execute -> {
                        if (context.control == Control.PAUSE) Result.Paused else task.execute(context)
                    }
                    is Recovery.Finished -> recovery.result
                }
            } else task.execute(context)
        } catch (error: CancellationException) {
            throw error // abrupt coroutine interruption is not safe business cancellation
        }
        if (result is Result.Success) {
            check(children(id).all { it.state.terminal }) { "Plan has unfinished children" }
            return update(id) { it.copy(state = State.SUCCEEDED, output = result.output.toMap(), control = Control.NONE) }
        }
        if (result == Result.Cancel || snapshot(id).control == Control.CANCEL) return cleanup(id, task, context)
        return when (result) {
            Result.Paused -> {
                settleChildren(id, Control.PAUSE)
                finishQuiescent(id, task, context, State.PAUSED)
            }
            is Result.Waiting -> {
                settleChildren(id, Control.PAUSE)
                finishQuiescent(id, task, context, State.WAITING, result.reason)
            }
            is Result.Fail -> {
                settleChildren(id, Control.PAUSE)
                finishQuiescent(id, task, context, State.FAILED, result.reason)
            }
            else -> error("Unexpected result")
        }
    }

    private suspend fun cleanup(id: String, task: Task, context: Context): Snapshot {
        update(id) { it.copy(state = State.CLEANING, control = Control.CANCEL) }
        settleChildren(id, Control.CANCEL)
        task.cleanup(context)
        return update(id) { it.copy(state = State.CANCELLED, control = Control.NONE) }
    }

    private suspend fun finishQuiescent(
        id: String, task: Task, context: Context, state: State, reason: String? = null,
    ): Snapshot {
        val settled = synchronized(lock) {
            if (records.getValue(id).control == Control.CANCEL) null
            else update(id) { it.copy(state = state, reason = reason, control = Control.NONE) }
        }
        return settled ?: cleanup(id, task, context)
    }

    private suspend fun settleChildren(id: String, control: Control) {
        children(id).filterNot { it.state.terminal }.forEach {
            request(it.id, control)
            val child = run(it.id)
            check(child.state.terminal || (control == Control.PAUSE && child.state in setOf(State.PAUSED, State.WAITING)))
        }
    }
    private fun collectable(record: Snapshot) = record.parent == null && record.definition.kind == Kind.PLAN &&
        record.state in setOf(State.SUCCEEDED, State.CANCELLED)

    /** Root terminal record remains the durable collection intent until every descendant is gone. */
    private suspend fun reclaimTree(id: String) {
        children(id).forEach {
            check(it.state.terminal && synchronized(lock) { it.id !in active })
            reclaimTree(it.id)
        }
        val record = snapshot(id)
        synchronized(lock) { reclaiming.add(id) }
        try {
            factory.create(record.definition).reclaim(Context(this, id))
            synchronized(lock) {
                store.remove(id)
                records.remove(id)
                views.remove(id)
                progressViews.remove(id)
            }
        } finally { synchronized(lock) { reclaiming.remove(id) } }
    }
    private fun children(id: String) = snapshots().filter { it.parent == id }
    private fun descendants(id: String): List<String> = children(id).flatMap { listOf(it.id) + descendants(it.id) }
    private fun update(id: String, transform: (Snapshot) -> Snapshot): Snapshot = synchronized(lock) {
        save(transform(records.getValue(id)))
        snapshot(id)
    }
    private fun save(record: Snapshot) {
        val copy = detached(record)
        store.write(copy)
        records[copy.id] = copy
        views[copy.id]?.value = detached(copy)
    }
    private fun detached(record: Snapshot) = record.copy(
        definition = record.definition.copy(input = record.definition.input.toMap()),
        metadata = record.metadata.toMap(), output = record.output.toMap(),
    )
}

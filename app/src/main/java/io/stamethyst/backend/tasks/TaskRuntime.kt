package io.stamethyst.backend.tasks

import android.content.Context
import android.util.Log
import io.stamethyst.BuildConfig
import io.stamethyst.backend.process.AppProcess
import io.stamethyst.config.RuntimePaths
import io.stamethyst.iotask.durable.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Default-process, process-lifetime host. No background wakeup or foreground-service promise. */
class TaskRuntime private constructor(
    private val rootProvider: () -> File,
    private val verificationBoundary: ((String) -> Unit)? = null,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val mutations = Mutex()
    private var store: FileTaskStore? = null
    private val ready = scope.async {
        val root = FileInspection.checked(rootProvider().canonicalFile)
        val opened = FileTaskStore(File(root, "manager"))
        store = opened
        try {
            val io = FileIoTasks(File(root, "history"))
            val factory = TaskFactory { definition ->
                val task = io.create(definition)
                if (verificationBoundary == null) task else object : Task() {
                    override suspend fun execute(context: io.stamethyst.iotask.durable.Context): Result {
                        val result = task.execute(context)
                        verificationBoundary.invoke("executed")
                        return result
                    }
                    override suspend fun recover(context: io.stamethyst.iotask.durable.Context) = task.recover(context)
                    override suspend fun cleanup(context: io.stamethyst.iotask.durable.Context) = task.cleanup(context)
                    override suspend fun revert(context: io.stamethyst.iotask.durable.Context) {
                        task.revert(context)
                        verificationBoundary.invoke("reverted")
                    }
                }
            }
            TaskManager(opened, factory, 1).also { it.recover() }
        } catch (error: Exception) {
            opened.close()
            store = null
            throw error
        }
    }

    /** Initialization/reconciliation errors are observable; never replaced with an empty store. */
    suspend fun awaitReady() { ready.await() }

    /** Execution belongs to the host, not to the Activity/caller coroutine awaiting it. */
    fun execute(definition: Definition, id: String = UUID.randomUUID().toString()): Deferred<Snapshot> = scope.async {
        val manager = ready.await()
        mutations.withLock {
            manager.publish(definition, id)
            manager.run(id)
        }
    }

    suspend fun snapshot(id: String): Snapshot = ready.await().snapshot(id)
    suspend fun snapshots(): List<Snapshot> = ready.await().snapshots()
    suspend fun observe(id: String) = ready.await().handle(id).observe()
    suspend fun requestPause(id: String) = ready.await().requestPause(id)
    suspend fun requestCancel(id: String) = ready.await().requestCancel(id)
    fun resume(id: String) = control { it.resume(id) }
    fun retry(id: String) = control { it.retry(id) }
    fun revert(id: String) = control { it.revert(id) }
    fun cancel(id: String): Deferred<Snapshot> {
        return scope.async {
            val manager = ready.await()
            manager.requestCancel(id)
            mutations.withLock { manager.run(id) }
        }
    }

    private fun control(action: suspend (TaskManager) -> Snapshot) = scope.async {
        val manager = ready.await()
        mutations.withLock { action(manager) }
    }

    /** Tests must quiesce ownership before reopening/deleting their namespace. */
    suspend fun close() {
        job.cancelAndJoin()
        withContext(Dispatchers.IO) { store?.close(); store = null }
    }

    companion object {
        @Volatile private var production: TaskRuntime? = null

        @JvmStatic
        @Synchronized
        fun get(context: Context): TaskRuntime {
            val app = context.applicationContext
            check(AppProcess.isDefaultProcess(app)) { "TaskRuntime belongs to default process" }
            return production ?: TaskRuntime(rootProvider = {
                File(RuntimePaths.taskStorageRoot(app), "production")
            }).also { production = it }
        }

        @JvmStatic
        fun start(context: Context) {
            val runtime = get(context)
            runtime.scope.launch {
                try { runtime.awaitReady() } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.e("TaskRuntime", "Task storage/recovery unavailable; mutations blocked", error)
                }
            }
        }

        /** Same host, layout, factory and startup recovery, isolated from production records. */
        fun verification(context: Context, namespace: String, boundary: ((String) -> Unit)? = null): TaskRuntime {
            check(BuildConfig.DEBUG) { "Verification namespaces are debug-only" }
            val id = UUID.fromString(namespace).toString()
            val app = context.applicationContext
            return TaskRuntime({ File(RuntimePaths.taskStorageRoot(app), "verification/$id") }, boundary)
        }
    }
}

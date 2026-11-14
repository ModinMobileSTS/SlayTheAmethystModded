package io.stamethyst.iotask

import android.app.Service
import android.content.Intent
import android.os.*
import android.os.Process
import io.stamethyst.iotask.durable.*
import io.stamethyst.iotask.durable.Task
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** Dedicated debug process using the actual durable store and reconstruction factory. */
class DurableRecoveryTestService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val case = UUID.fromString(message.data.getString("case")).toString()
        val mode = requireNotNull(message.data.getString("mode"))
        kotlin.concurrent.thread(name = "durable-recovery-probe") {
            runBlocking {
                FileTaskStore(File(filesDir, "durable-recovery/$case")).use { store ->
                    if (mode in setOf("task-revert", "plan-revert", "child-revert")) {
                        val manager = TaskManager(store, revertProbeFactory(store.root, mode), 1)
                        manager.publish(Definition("probe", kind = if (mode == "task-revert") Kind.TASK else Kind.PLAN), "probe")
                        manager.run("probe")
                        manager.revert("probe")
                        return@use
                    }
                    if (mode == "block-write") {
                        val data = ByteArray(128 * 1024) { (it % 251).toByte() }
                        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(data)
                            .joinToString("") { "%02x".format(it.toInt() and 255) }
                        ReplayableBlockWrite.open(File(store.root, "blocks"), "v1", data.size.toLong(),
                            listOf(WriteBlock(0, data.size.toLong(), hash))).use { writer ->
                            writer.write(0, object : java.io.InputStream() {
                                var offset = 0
                                override fun read(): Int = error("bulk only")
                                override fun read(buffer: ByteArray, start: Int, length: Int): Int {
                                    if (offset > 0) Process.killProcess(Process.myPid())
                                    val count = minOf(length, 64 * 1024)
                                    data.copyInto(buffer, start, offset, offset + count)
                                    offset += count
                                    return count
                                }
                            })
                        }
                        return@use
                    }
                    val manager = TaskManager(store, if (mode == "reclaim") reclaimProbeFactory(true) else durableProbeFactory(mode, true), 1)
                    manager.publish(Definition("probe", input = mapOf("mode" to mode),
                        kind = if (mode == "reclaim") Kind.PLAN else Kind.TASK), "probe")
                    if (mode == "cleanup") {
                        durableWrite(File(store.workspace("probe"), "part"), "temporary")
                        manager.cancel("probe")
                    } else manager.run("probe")
                }
            }
        }
        true
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
}

/** Side effects live outside workspaces so assertions distinguish business undo from collection. */
fun revertProbeFactory(root: File, killAt: String? = null): TaskFactory = TaskFactory { definition ->
    if (definition.kind == Kind.PLAN) object : TaskPlan() {
        override suspend fun execute(context: Context): Result {
            context.await(context.child("keep", Definition("keep")))
            val undo = context.child("undo", Definition("undo"))
            context.await(undo)
            context.checkpoint(mapOf("undo" to undo.id))
            return Result.Waiting("revert decision")
        }
        override suspend fun cleanup(context: Context) = Unit
        override suspend fun revert(context: Context) {
            context.revert(context.child("undo", Definition("undo")))
            context.checkpoint(mapOf("revertPid" to Process.myPid().toString()))
            if (killAt == "plan-revert") Process.killProcess(Process.myPid())
        }
    } else object : Task() {
        override suspend fun execute(context: Context): Result {
            val artifact = File(root, "${definition.type}.published")
            check(!artifact.exists()) { "Business execution replayed" }
            durableWrite(artifact, "published")
            return Result.Success(mapOf("artifact" to artifact.path))
        }
        override suspend fun cleanup(context: Context) = Unit
        override suspend fun revert(context: Context) {
            val artifact = File(root, "${definition.type}.published")
            check(!artifact.exists() || artifact.delete())
            context.checkpoint(mapOf("revertPid" to Process.myPid().toString()))
            if (killAt == "task-revert" || killAt == "child-revert") Process.killProcess(Process.myPid())
        }
    }
}

fun durableProbeFactory(mode: String, kill: Boolean): TaskFactory = TaskFactory {
    object : Task() {
        override suspend fun execute(context: Context): Result {
            durableWrite(File(context.workspace, "part"), "verified-prefix")
            context.checkpoint(mapOf("offset" to "15", "writerPid" to Process.myPid().toString()))
            if (mode == "committed") durableWrite(File(context.workspace, "artifact"), "verified-result")
            if (mode == "pause") {
                context.requestPause()
                return Result.Paused
            }
            if (kill) Process.killProcess(Process.myPid())
            return Result.Success(mapOf("artifact" to "part"))
        }
        override suspend fun recover(context: Context): Recovery {
            check(File(context.workspace, "part").readText() == "verified-prefix")
            check(context.metadata["offset"] == "15")
            return if (File(context.workspace, "artifact").exists()) {
                check(File(context.workspace, "artifact").readText() == "verified-result")
                Recovery.Finished(Result.Success(mapOf("artifact" to "artifact")))
            } else Recovery.Execute
        }
        override suspend fun cleanup(context: Context) {
            val part = File(context.workspace, "part")
            check(!part.exists() || part.delete())
            context.checkpoint(mapOf("cleanup" to "part-deleted"))
            if (kill) Process.killProcess(Process.myPid())
        }
    }
}

private fun durableWrite(file: File, text: String) {
    FileOutputStream(file).use { it.write(text.toByteArray()); it.fd.sync() }
}

fun reclaimProbeFactory(kill: Boolean): TaskFactory = TaskFactory { definition ->
    if (definition.kind == Kind.PLAN) object : TaskPlan() {
        override suspend fun execute(context: Context): Result {
            val child = context.child("blocks", Definition("blocks"))
            context.await(child)
            context.checkpoint(mapOf("child" to child.id))
            return Result.Success()
        }
        override suspend fun cleanup(context: Context) = Unit
    } else object : Task() {
        override suspend fun execute(context: Context): Result {
            durableWrite(File(context.workspace, "part"), "temporary")
            return Result.Success()
        }
        override suspend fun cleanup(context: Context) = Unit
        override suspend fun reclaim(context: Context) {
            context.checkpoint(mapOf("reclaim" to "started"))
            if (kill) Process.killProcess(Process.myPid())
        }
    }
}

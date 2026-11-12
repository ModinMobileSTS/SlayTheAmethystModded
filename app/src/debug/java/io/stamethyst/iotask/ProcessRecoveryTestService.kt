package io.stamethyst.iotask

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.Process
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Debug-only fixture. Executes exclusively in its dedicated secondary process. */
class ProcessRecoveryTestService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val case = UUID.fromString(message.data.getString("case") ?: return@Handler true).toString()
        val root = File(filesDir, "io-task-recovery/$case")
        val mode = message.data.getString("mode")!!
        require(mode == "crash" || mode == "exit")
        val committed = message.data.getBoolean("committed")
        kotlin.concurrent.thread(name = "io-task-recovery-probe") { runBlocking {
            WorkerGroup(ProcessTestStore(root), 1).submit(object : Task(TaskId("probe"), "process-probe") {
                override suspend fun execute(context: TaskContext): TaskResult {
                    context.checkpoint("writerPid", Process.myPid().toString())
                    context.checkpoint("offset", "verified-prefix")
                    if (committed) {
                        AtomicFile(File(root, "artifact")).let { file ->
                            val output = file.startWrite()
                            try {
                                output.write("verified-result".toByteArray())
                                file.finishWrite(output)
                            } catch (error: Exception) {
                                file.failWrite(output)
                                throw error
                            }
                        }
                    }
                    // Kill the worker process after the durable checkpoint. Using killProcess
                    // models an abrupt process loss without Android's crash dialog/restart loop.
                    Handler(Looper.getMainLooper()).post {
                        if (mode == "crash") Process.killProcess(Process.myPid())
                        kotlin.system.exitProcess(0)
                    }
                    kotlinx.coroutines.awaitCancellation()
                }
            })
        } }
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
}

/** Test fixture, single writer at a time; not a production TaskStore or durability claim. */
class ProcessTestStore(private val root: File) : TaskStore {
    private val file = AtomicFile(File(root, "snapshot.json"))

    override fun read(id: TaskId): TaskSnapshot? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val json = file.openRead().bufferedReader().use { JSONObject(it.readText()) }
        if (json.getString("id") != id.value) return null
        val checkpoint = json.getJSONObject("checkpoint")
        return TaskSnapshot(
            id, json.getString("type"), TaskState.valueOf(json.getString("state")),
            checkpoint.keys().asSequence().associateWith { checkpoint.getString(it) },
            waitingReason = if (json.isNull("waitingReason")) null else json.getString("waitingReason"),
            runCount = json.getInt("runCount"),
        )
    }

    override fun write(snapshot: TaskSnapshot) {
        check(root.isDirectory || root.mkdirs())
        val json = JSONObject().put("id", snapshot.id.value).put("type", snapshot.type)
            .put("state", snapshot.state.name).put("runCount", snapshot.runCount)
            .put("waitingReason", snapshot.waitingReason ?: JSONObject.NULL)
            .put("checkpoint", JSONObject(snapshot.checkpoint))
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    override fun all(): List<TaskSnapshot> = listOfNotNull(read(TaskId("probe")))
}

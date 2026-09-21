package io.stamethyst.ui.aimod

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.stamethyst.LauncherActivity
import io.stamethyst.R
import io.stamethyst.backend.llm.AgentToolExecutionEvent
import io.stamethyst.backend.llm.LlmSettingsRepository
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun applyToolExecutionEvent(
    message: AiEditorMessage,
    event: AgentToolExecutionEvent,
    json: Json = Json { ignoreUnknownKeys = true },
): AiEditorMessage {
    if (event.result == null) {
        return message.copy(
            text = "",
            tools = message.tools + AiToolCall(
                id = event.id,
                name = event.name,
                arguments = event.arguments,
                precedingText = message.text,
            ),
        )
    }

    val displayResult = runCatching {
        json.parseToJsonElement(event.result).jsonObject["content"]?.jsonPrimitive?.content
    }.getOrNull() ?: event.result
    return message.copy(tools = message.tools.map { tool ->
        if (tool.id == event.id) {
            tool.copy(
                result = displayResult.take(12_000),
                failed = event.failed,
                truncated = displayResult.length > 12_000,
            )
        } else {
            tool
        }
    })
}

class AiAgentExecutionService : Service() {
    companion object {
        const val ACTION_RUN = "io.stamethyst.action.AI_AGENT_RUN"
        const val ACTION_CANCEL = "io.stamethyst.action.AI_AGENT_CANCEL"
        const val EXTRA_JOB_ID = "io.stamethyst.extra.AI_AGENT_JOB_ID"
        const val EXTRA_MOD_ID = "io.stamethyst.extra.AI_AGENT_MOD_ID"

        private const val CHANNEL_ID = "ai_agent_execution"
        private const val NOTIFICATION_ID = 646571

        private val runningJobs = ConcurrentHashMap.newKeySet<String>()

        internal fun isJobRunning(jobId: String): Boolean = runningJobs.contains(jobId)

        internal fun reserve(jobId: String): Boolean = synchronized(runningJobs) {
            if (runningJobs.isNotEmpty()) false else runningJobs.add(jobId)
        }

        internal fun release(jobId: String) { runningJobs.remove(jobId) }

        internal fun start(context: Context, job: AiAgentJobRecord) {
            val intent = Intent(context.applicationContext, AiAgentExecutionService::class.java).apply {
                action = ACTION_RUN
                putExtra(EXTRA_JOB_ID, job.jobId)
                putExtra(EXTRA_MOD_ID, job.modId)
            }
            runningJobs += job.jobId
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (error: Throwable) {
                runningJobs.remove(job.jobId)
                throw error
            }
        }

        internal fun cancel(context: Context, jobId: String, modId: String) {
            val intent = Intent(context.applicationContext, AiAgentExecutionService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_JOB_ID, jobId)
                putExtra(EXTRA_MOD_ID, modId)
            }
            context.applicationContext.startService(intent)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var workerThread: Thread? = null
    private var workerJobId: String? = null
    private var workerModId: String? = null
    private var workerJob: AiAgentJobRecord? = null
    @Volatile
    private var cancellationRequested = false
    @Volatile
    private var serviceDestroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val safeIntent = intent ?: return START_NOT_STICKY
        val jobId = safeIntent.getStringExtra(EXTRA_JOB_ID).orEmpty()
        val modId = safeIntent.getStringExtra(EXTRA_MOD_ID).orEmpty()
        if (jobId.isBlank() || modId.isBlank()) return START_NOT_STICKY

        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在准备 AI 任务"))
        when (safeIntent.action) {
            ACTION_CANCEL -> cancelJob(jobId, modId)
            ACTION_RUN -> startJob(jobId, modId, startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceDestroyed = true
        workerJob = null
        workerJobId?.let(runningJobs::remove)
        workerThread?.interrupt()
        workerThread = null
        workerJobId = null
        super.onDestroy()
    }

    private fun startJob(jobId: String, modId: String, startId: Int) {
        if (workerThread != null) {
            if (workerJobId != jobId) {
                val store = AiAgentJobStore(applicationContext, modId)
                store.find(jobId)?.let { markFailed(it, store, "Another AI task is running.") }
                runningJobs.remove(jobId)
            }
            return
        }
        val jobStore = AiAgentJobStore(applicationContext, modId)
        val job = jobStore.find(jobId) ?: run {
            runningJobs.remove(jobId)
            stopWhenIdle(startId)
            return
        }
        if (job.status != AiAgentJobStatus.RUNNING) {
            runningJobs.remove(jobId)
            stopWhenIdle(startId)
            return
        }

        cancellationRequested = false
        serviceDestroyed = false
        workerJobId = jobId
        workerModId = modId
        workerJob = job
        runningJobs += jobId
        updateNotification("正在执行 AI 任务")
        workerThread = thread(start = false, name = "STS-AiAgent-$jobId") {
            try {
                runJob(job, jobStore, startId)
            } catch (error: Throwable) {
                runCatching { markFailed(job, jobStore, error.message ?: error.javaClass.simpleName) }
                finishWorker(job.jobId, startId)
            }
        }
        workerThread?.start()
    }

    private fun cancelJob(jobId: String, modId: String) {
        if (workerJobId == jobId) {
            cancellationRequested = true
            workerThread?.interrupt()
            updateNotification("正在停止 AI 任务")
            return
        }
        val jobStore = AiAgentJobStore(applicationContext, modId)
        val job = jobStore.find(jobId) ?: return
        if (job.status == AiAgentJobStatus.RUNNING) {
            markCancelled(job, jobStore)
        }
        runningJobs.remove(jobId)
        stopWhenIdle(0)
    }

    private fun runJob(
        job: AiAgentJobRecord,
        jobStore: AiAgentJobStore,
        startId: Int,
    ) {
        val conversationStore = AiConversationStore(conversationFile(job.modId))
        val session = conversationStore.load().firstOrNull { it.id == job.conversationId }
        val assistant = session?.messages?.firstOrNull { it.id == job.assistantMessageId }
        if (session == null || assistant == null) {
            markFailed(job, jobStore, "Conversation state is missing.")
            finishWorker(job.jobId, startId)
            return
        }

        val startedAt = System.currentTimeMillis()
        val accepting = java.util.concurrent.atomic.AtomicBoolean(true)
        val streamLock = Any()
        val textBuffer = StringBuilder()
        val thinkingBuffer = StringBuilder()
        var lastFlush = 0L
        fun flush(force: Boolean = false) = synchronized(streamLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (!force && now - lastFlush < 250L) return@synchronized
            if (textBuffer.isEmpty() && thinkingBuffer.isEmpty()) return@synchronized
            updateAssistant(job, conversationStore) { message ->
                message.copy(text = message.text + textBuffer, thinking = message.thinking + thinkingBuffer)
            }
            textBuffer.setLength(0)
            thinkingBuffer.setLength(0)
            lastFlush = now
        }
        fun checkActive() {
            if (serviceDestroyed || cancellationRequested || Thread.currentThread().isInterrupted) {
                throw java.util.concurrent.CancellationException("AI task stopped")
            }
        }
        val executor = AiModAgentExecutor(
            context = applicationContext,
            storagePath = job.storagePath,
            modName = job.modName,
            modId = job.modId,
            reasoningEffort = LlmSettingsRepository(applicationContext).get().reasoningEffort,
            onToolExecution = { event ->
                checkActive()
                flush(true)
                handleToolEvent(job, conversationStore, event)
            },
            onText = { delta ->
                synchronized(streamLock) {
                    if (accepting.get() && !cancellationRequested && !serviceDestroyed) {
                        textBuffer.append(delta)
                        flush()
                    }
                }
            },
            onThinking = { delta ->
                synchronized(streamLock) {
                    if (accepting.get() && !cancellationRequested && !serviceDestroyed) {
                        thinkingBuffer.append(delta)
                        flush()
                    }
                }
            },
            onContext = { state ->
                checkActive()
                conversationStore.update(job.conversationId) { it.copy(context = state) }
            },
            checkCancelled = ::checkActive,
        )

        try {
            updateNotification("AI 正在处理 ${job.modName}")
            val result = executor.execute(session, job.assistantMessageId)
            accepting.set(false)
            val interrupted = Thread.interrupted()
            flush(true)
            if (cancellationRequested || interrupted) {
                markCancelled(job, jobStore)
            } else {
                updateAssistant(job, conversationStore) { message ->
                    message.copy(
                        streaming = false,
                        failed = false,
                        errorMessage = "",
                        text = result.text.ifBlank { message.text },
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        contextTokens = result.contextTokens,
                    )
                }
                jobStore.update(job.jobId) {
                    it.copy(status = AiAgentJobStatus.SUCCEEDED, errorMessage = "")
                }
                updateNotification("AI 任务已完成")
            }
        } catch (error: Throwable) {
            accepting.set(false)
            // FileChannel.lock refuses an interrupted thread; clear only after stopping execution.
            val interrupted = Thread.interrupted()
            runCatching { flush(true) }
            if (serviceDestroyed) {
                // A destroyed service may be the result of process death. Leave RUNNING on disk;
                // the next editor open converts it to the explicit interrupted error state.
                return
            }
            if (cancellationRequested || interrupted || error is InterruptedException || error is java.util.concurrent.CancellationException) {
                markCancelled(job, jobStore)
            } else {
                val message = error.message ?: error.javaClass.simpleName
                markFailed(job, jobStore, message)
            }
        } finally {
            accepting.set(false)
            finishWorker(job.jobId, startId)
        }
    }

    private fun handleToolEvent(
        job: AiAgentJobRecord,
        conversationStore: AiConversationStore,
        event: AgentToolExecutionEvent,
    ) {
        updateAssistant(job, conversationStore) { message ->
            applyToolExecutionEvent(message, event, json)
        }
    }

    private fun updateAssistant(
        job: AiAgentJobRecord,
        conversationStore: AiConversationStore,
        transform: (AiEditorMessage) -> AiEditorMessage,
    ) {
        conversationStore.update(job.conversationId) { session ->
            session.copy(messages = session.messages.map { message ->
                if (message.id == job.assistantMessageId) transform(message) else message
            })
        }
    }

    private fun markFailed(job: AiAgentJobRecord, jobStore: AiAgentJobStore, message: String) {
        val conversationStore = AiConversationStore(conversationFile(job.modId))
        updateAssistant(job, conversationStore) {
            it.copy(streaming = false, failed = true, errorMessage = message)
        }
        jobStore.update(job.jobId) {
            it.copy(status = AiAgentJobStatus.FAILED, errorMessage = message)
        }
        updateNotification("AI 任务失败")
    }

    private fun markCancelled(job: AiAgentJobRecord, jobStore: AiAgentJobStore) {
        val message = getString(R.string.ai_mod_editor_error_stopped)
        val conversationStore = AiConversationStore(conversationFile(job.modId))
        updateAssistant(job, conversationStore) {
            it.copy(streaming = false, failed = true, errorMessage = message)
        }
        jobStore.update(job.jobId) {
            it.copy(status = AiAgentJobStatus.CANCELLED, errorMessage = message)
        }
        updateNotification("AI 任务已停止")
    }

    private fun finishWorker(jobId: String, startId: Int) {
        runningJobs.remove(jobId)
        workerThread = null
        workerJobId = null
        workerModId = null
        workerJob = null
        stopWhenIdle(startId)
    }

    private fun stopWhenIdle(startId: Int) {
        if (workerThread != null) return
        stopForeground(true)
        if (startId > 0) stopSelfResult(startId) else stopSelf()
    }

    private fun conversationFile(modId: String): File = File(
        RuntimePaths.agentModConversationsRoot(
            applicationContext,
            AgentPatchModManager.parentModSegment(modId),
        ),
        "conversations.json",
    )

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Amethyst Agent", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(message: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, LauncherActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                workerJob?.let { job ->
                    putExtra(LauncherActivity.EXTRA_OPEN_AI_STORAGE_PATH, job.storagePath)
                    putExtra(LauncherActivity.EXTRA_OPEN_AI_MOD_NAME, job.modName)
                    putExtra(LauncherActivity.EXTRA_OPEN_AI_MOD_ID, job.modId)
                    putExtra(LauncherActivity.EXTRA_OPEN_AI_CONVERSATION_ID, job.conversationId)
                }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_amethyst)
            .setContentTitle("Amethyst Agent")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val jobId = workerJobId
        val modId = workerModId
        if (jobId != null && modId != null) {
            val cancelIntent = PendingIntent.getService(
                this,
                jobId.hashCode(),
                Intent(this, AiAgentExecutionService::class.java).apply {
                    action = ACTION_CANCEL
                    putExtra(EXTRA_JOB_ID, jobId)
                    putExtra(EXTRA_MOD_ID, modId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, getString(R.string.ai_mod_editor_stop), cancelIntent)
        }
        return builder.build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(message),
        )
    }
}

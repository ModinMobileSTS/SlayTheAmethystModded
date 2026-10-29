package io.stamethyst.ui.aimod

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import io.stamethyst.ui.LauncherAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.stamethyst.ui.LauncherTopAppBar
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Job
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import io.stamethyst.R
import io.stamethyst.backend.llm.JarPatch
import io.stamethyst.backend.llm.JarPatchApplier
import io.stamethyst.backend.llm.JarPatchCodec
import io.stamethyst.backend.llm.LlmReasoningEffort
import io.stamethyst.backend.llm.LlmSettings
import io.stamethyst.backend.llm.LlmSettingsRepository
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.config.RuntimePaths
import io.stamethyst.navigation.Route
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.LauncherNavigationRequestBus
import io.stamethyst.ui.LauncherTransientNoticeBus
import io.stamethyst.ui.SimpleMarkdownContent
import io.stamethyst.ui.UiText
import io.stamethyst.ui.icon.ArrowBack
import io.stamethyst.ui.icon.AttachFile
import io.stamethyst.ui.icon.Description
import io.stamethyst.ui.icon.KeyboardArrowUp
import io.stamethyst.ui.icon.Pending
import io.stamethyst.ui.icon.Refresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

private const val AI_CONVERSATION_INITIAL_VISIBLE_MESSAGES = 3
private const val AI_CONVERSATION_LAZY_LOAD_STEP = 3
private const val AI_CONVERSATION_POLL_ACTIVE_MS = 500L
private const val AI_CONVERSATION_POLL_IDLE_MS = 2_000L

/** Gap between rows of the same message (tool calls, text chunks, thinking blocks). */
private val AI_TRANSCRIPT_ROW_SPACING = 8.dp

/** Gap between two messages. */
private val AI_TRANSCRIPT_MESSAGE_SPACING = 24.dp

@Serializable
internal data class AiAttachment(
    val name: String,
    /** UTF-8 text for a pasted file; empty for an image. */
    val content: String = "",
    /** MIME type, e.g. text/plain or image/png. */
    val mime: String = "text/plain",
    /** Base64 payload of an image attachment; empty for text. */
    val data: String = "",
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

@Serializable
internal data class AiToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val precedingText: String = "",
    val result: String? = null,
    val failed: Boolean = false,
    val truncated: Boolean = false,
    val imageAttachments: List<AiAttachment> = emptyList(),
)

/**
 * The order in which the model produced visible content.  The legacy fields on
 * [AiEditorMessage] are retained for context building and backwards-compatible
 * database reads, but the UI uses these parts when they are available.
 */
@Serializable
internal data class AiMessagePart(
    val kind: String,
    val content: String = "",
    val toolId: String? = null,
)

internal object AiMessagePartKind {
    const val TEXT = "text"
    const val THINKING = "thinking"
    const val TOOL = "tool"
    const val COMPACTION = "compaction"
    const val COMPACTION_REQUEST = "compaction-request"
}

@Serializable
internal data class AiEditorMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val attachments: List<AiAttachment> = emptyList(),
    val thinking: String = "",
    val streaming: Boolean = false,
    val failed: Boolean = false,
    val errorMessage: String = "",
    val retryMessage: String = "",
    val tools: List<AiToolCall> = emptyList(),
    val modelName: String = "",
    val elapsedMs: Long? = null,
    val contextTokens: Int? = null,
    val notice: Boolean = false,
    val parts: List<AiMessagePart> = emptyList(),
)

@Serializable
internal data class AiEditorSession(
    val id: String,
    val messages: List<AiEditorMessage> = emptyList(),
    val title: String = "",
    val revision: Long = 0,
    val context: io.stamethyst.backend.llm.AgentContextState? = null,
    val inbox: List<AiInboxItem> = emptyList(),
)

internal fun AiEditorSession.hasUserPrompt(): Boolean =
    messages.any { it.fromUser && (it.text.isNotBlank() || it.attachments.isNotEmpty()) }

internal fun AiEditorSession.displayTitle(): String = title.ifBlank {
    messages.firstOrNull { it.fromUser && it.text.isNotBlank() }?.text
        ?.lineSequence()?.firstOrNull()?.trim()
        .orEmpty()
        .ifBlank { messages.firstOrNull { it.fromUser }?.attachments?.firstOrNull()?.name.orEmpty() }
}

internal class AiModEditorViewModel(
    private val context: Context,
    private val storagePath: String,
    private val modName: String,
    private val modId: String,
    private val requestedSessionId: String? = null,
) : ViewModel() {
    val messages = mutableStateListOf<AiEditorMessage>()
    val sessions = mutableStateListOf<AiEditorSession>()

    /**
     * Bumped whenever the message list changed. The transcript keys its row preparation on this
     * instead of on the message list, so a poll never deep-compares tens of thousands of parts on
     * the composition thread just to discover that nothing changed.
     */
    var transcriptRevision by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set
    var storageReady by mutableStateOf(false)
        private set
    var switchingConversation by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var pendingPatch by mutableStateOf<JarPatch?>(null)
        private set
    var appliedBackupPath by mutableStateOf<String?>(null)
        private set
    var reasoningEffort by mutableStateOf(LlmReasoningEffort.HIGH)
        private set
    val inbox: List<AiInboxItem> get() = sessions.firstOrNull { it.id == currentSessionId }?.inbox.orEmpty()

    fun updateInbox(id: String, text: String? = null, delivery: AiInboxDelivery? = null, remove: Boolean = false) {
        val sessionId = currentSessionId
        launchStorage {
            val updated = conversationStore.mutate(sessionId) { session ->
                session.copy(inbox = if (remove) session.inbox.filterNot { it.id == id } else session.inbox.map {
                    if (it.id == id) it.copy(text = text ?: it.text, delivery = delivery ?: it.delivery) else it
                })
            }
            withContext(Dispatchers.Main.immediate) { updated?.let(::installSession) }
            pollWakeUp.tryEmit(Unit)
        }
    }

    fun reorderInbox(id: String, direction: Int) {
        val sessionId = currentSessionId
        launchStorage {
            val updated = conversationStore.mutate(sessionId) { session ->
                val items = session.inbox.toMutableList()
                val from = items.indexOfFirst { it.id == id }
                val to = from + direction
                if (from >= 0 && to in items.indices) java.util.Collections.swap(items, from, to)
                session.copy(inbox = items)
            }
            withContext(Dispatchers.Main.immediate) { updated?.let(::installSession) }
        }
    }

    fun saveInboxEdit(id: String, text: String, attachments: List<AiAttachment>, onSaved: () -> Unit) {
        val sessionId = currentSessionId
        val modelName = llmSettings.modelName
        val effort = reasoningEffort
        launchStorage {
            var found = false
            val updated = conversationStore.mutate(sessionId) { session ->
                found = session.inbox.any { it.id == id }
                session.copy(inbox = session.inbox.map {
                    if (it.id == id) it.copy(text = text.trim(), attachments = attachments,
                        modelName = modelName, reasoningEffort = effort) else it
                })
            }
            withContext(Dispatchers.Main.immediate) {
                updated?.let(::installSession)
                if (found) onSaved() else error = context.getString(R.string.ai_inbox_already_delivered)
            }
        }
    }

    private var nextMessageId = 1L
    /** Wakes the conversation poller when a job starts, so the slower idle cadence is invisible. */
    private val pollWakeUp = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var patchJob: Job? = null
    private var patchText: String? = null
    private var patchSessionId: String? = null
    private val settingsRepository = LlmSettingsRepository(context)
    var llmSettings by mutableStateOf(LlmSettings())
        private set
    private val parentModSegment = AgentPatchModManager.parentModSegment(modId)
    private val workspaceAccessRoot = RuntimePaths.agentModWorkspaceRoot(context, parentModSegment)

    private val conversationsRoot = RuntimePaths.agentModConversationsRoot(context, parentModSegment)
    private val conversationStore = AiConversationStore(conversationsRoot)
    private val storageDispatcher = AiEditorStorage.dispatcher
    private val jobStore = AiAgentJobStore(context, modId)
    var currentSessionId: String by mutableStateOf("")
        private set

    val history: List<AiEditorSession> get() = sessions.filter { it.hasUserPrompt() }

    private var toolInfo by mutableStateOf<List<AiToolInfo>>(emptyList())

    init {
        // Directory creation, encrypted preference initialization, and tool construction can all
        // touch disk or initialize providers. Keep them out of the first composition frame.
        viewModelScope.launch(storageDispatcher) {
            workspaceAccessRoot.mkdirs()
            conversationsRoot.mkdirs()
            val loadedSettings = runCatching { settingsRepository.get() }
                .onFailure(::reportStorageFailure).getOrDefault(LlmSettings())
            withContext(Dispatchers.Main.immediate) {
                llmSettings = loadedSettings
                reasoningEffort = loadedSettings.reasoningEffort
            }
            loadConversation()
        }
        viewModelScope.launch(Dispatchers.Default) {
            val loadedTools = AiModAgentExecutor(
                context,
                storagePath,
                modName,
                modId,
                LlmReasoningEffort.OFF,
            ).availableTools()
            withContext(Dispatchers.Main.immediate) { toolInfo = loadedTools }
        }
        startConversationPolling()
    }

    private fun loadConversation() {
        runCatching {
            runCatching { recoverInterruptedJobs() }.onFailure(::reportStorageFailure)
            var historyReadFailed = false
            val stored = conversationStore.loadSummaries { _, failure ->
                historyReadFailed = true
                reportStorageFailure(failure)
            }
            val jobs = runCatching { jobStore.list() }.onFailure(::reportStorageFailure).getOrDefault(emptyList())
            val latestSummary = requestedSessionId?.let { requestedId ->
                stored.firstOrNull { it.id == requestedId }
            } ?: stored.lastOrNull()
            val candidates = listOfNotNull(latestSummary) + stored.asReversed().filter { it.id != latestSummary?.id }
            val latest = candidates.firstNotNullOfOrNull { summary ->
                runCatching { conversationStore.load(summary.id) }.onFailure {
                    historyReadFailed = true
                    reportStorageFailure(it)
                }.getOrNull()
            }
            if (latest == null) {
                // An unreadable history is not an empty history. Do not enable sending into a
                // replacement conversation when none of the existing sessions could be opened.
                if (historyReadFailed) return@runCatching
                val session = AiEditorSession(UUID.randomUUID().toString())
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    sessions += stored
                    sessions += session
                    currentSessionId = session.id
                    persistSession(session)
                    refreshBusyState(jobs)
                    storageReady = true
                }
            } else {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    sessions += stored
                    currentSessionId = latest.id
                    installSession(latest)
                    refreshBusyState(jobs)
                    storageReady = true
                }
            }
        }.onFailure(::reportStorageFailure)
    }

    private fun persistSession(session: AiEditorSession) {
        launchStorage {
            conversationStore.mutate(session.id, create = session) { session }
        }
    }

    /** Serialized storage work; a database failure must never crash the editor process. */
    private fun launchStorage(block: suspend () -> Unit) {
        AiEditorStorage.scope.launch {
            runCatching { block() }
                .onFailure(::reportStorageFailure)
        }
    }

    private fun reportStorageFailure(failure: Throwable) {
        android.util.Log.w("AiModStore", "AI conversation storage failed", failure)
        viewModelScope.launch(Dispatchers.Main.immediate) {
            error = context.getString(R.string.ai_mod_editor_storage_error, failure.message ?: failure.javaClass.simpleName)
        }
    }

    fun dismissError() { error = null }

    private fun installSession(session: AiEditorSession) {
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) sessions[index] = session else sessions += session
        if (session.id != currentSessionId) return
        if (applyIncomingMessages(messages, session.messages)) transcriptRevision++
        nextMessageId = (messages.maxOfOrNull { it.id } ?: 0L) + 1
        val text = messages.lastOrNull { !it.fromUser }?.takeUnless { it.streaming }?.text
        if (text != patchText || patchSessionId != currentSessionId) {
            patchText = text
            patchSessionId = currentSessionId
            patchJob?.cancel()
            pendingPatch = null
            val sessionId = currentSessionId
            patchJob = viewModelScope.launch {
                val patch = withContext(Dispatchers.Default) { text?.let(JarPatchCodec::extract) }
                if (currentSessionId == sessionId && patchText == text) pendingPatch = patch
            }
        }
    }

    private fun mutateCurrent(transform: (AiEditorSession) -> AiEditorSession): AiEditorSession {
        val current = sessions.firstOrNull { it.id == currentSessionId }
            ?: AiEditorSession(currentSessionId)
        val sessionId = currentSessionId
        val next = transform(current).copy(revision = current.revision + 1)
        installSession(next)
        launchStorage {
            conversationStore.mutate(
                sessionId,
                create = current,
                transform = transform,
            )
        }
        return next
    }

    private fun startConversationPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                // While a job streams the store is the only way the UI sees progress. When nothing
                // runs, the same query every 500 ms is pure overhead; a started job wakes the loop
                // immediately instead of waiting out the idle interval.
                val interval = if (busy) AI_CONVERSATION_POLL_ACTIVE_MS else AI_CONVERSATION_POLL_IDLE_MS
                withTimeoutOrNull(interval) { pollWakeUp.first() }
                runCatching {
                    recoverInterruptedJobs()
                    val activeState = withContext(Dispatchers.Main.immediate) {
                        currentSessionId to sessions.firstOrNull { it.id == currentSessionId }
                    }
                    val activeSessionId = activeState.first
                    if (activeSessionId.isBlank()) return@runCatching
                    val stored = conversationStore.revision(activeSessionId)
                        ?.takeIf { it > (activeState.second?.revision ?: -1L) }
                        ?.let { activeState.second?.let(conversationStore::loadChanges) ?: conversationStore.load(activeSessionId) }
                        ?.let(::listOf)
                        .orEmpty()
                    val jobs = jobStore.listForConversation(activeSessionId)
                    withContext(Dispatchers.Main.immediate) {
                        applyExternalState(stored)
                        refreshBusyState(jobs)
                        // Covers an input admitted just after the worker's final inbox check.
                        if (!busy && inbox.isNotEmpty() && jobs.maxByOrNull { it.updatedAtMillis }?.status == AiAgentJobStatus.SUCCEEDED &&
                            !AiAgentExecutionService.isRunning.value) {
                            resumeInbox()
                        }
                    }
                }
            }
        }
    }

    private fun applyExternalState(stored: List<AiEditorSession>) {
        val incoming = stored.firstOrNull { it.id == currentSessionId } ?: return
        val current = sessions.firstOrNull { it.id == currentSessionId }
        if (current != null && incoming.revision <= current.revision) return
        val index = sessions.indexOfFirst { it.id == incoming.id }
        if (index >= 0) sessions[index] = incoming else sessions += incoming
        installSession(incoming)
    }

    private fun recoverInterruptedJobs() {
        val interruptedMessage = context.getString(R.string.ai_mod_editor_error_interrupted)
        val interrupted = jobStore.markInterrupted(
            interruptedMessage = interruptedMessage,
            isRunning = { job -> AiAgentExecutionService.isJobRunning(job.jobId) },
        )
        interrupted.forEach { job ->
            conversationStore.updateMessage(job.conversationId, job.assistantMessageId) { message ->
                message.copy(
                    streaming = false,
                    failed = true,
                    errorMessage = interruptedMessage,
                )
            }
        }
    }

    private fun refreshBusyState(jobs: List<AiAgentJobRecord>? = null) {
        if (jobs != null) {
            busy = jobs.any {
                it.conversationId == currentSessionId && it.status == AiAgentJobStatus.RUNNING
            }
            return
        }
        val sessionId = currentSessionId
        launchStorage {
            val storedJobs = jobStore.listForConversation(sessionId)
            withContext(Dispatchers.Main.immediate) { refreshBusyState(storedJobs) }
        }
    }

    fun updateReasoningEffort(value: LlmReasoningEffort) {
        reasoningEffort = value
        val updated = llmSettings.copy(reasoningEffort = value)
        llmSettings = updated
        launchStorage { settingsRepository.set(updated) }
    }

    fun selectModel(modelName: String) {
        val trimmed = modelName.trim()
        if (trimmed.isEmpty() || trimmed == llmSettings.modelName) return
        val updated = llmSettings.copy(modelName = trimmed)
        llmSettings = updated
        launchStorage { settingsRepository.set(updated) }
    }

    fun send(prompt: String, attachments: List<AiAttachment> = emptyList()) {
        val trimmed = prompt.trim()
        if (!storageReady || currentSessionId.isBlank() || (trimmed.isEmpty() && attachments.isEmpty())) return
        if (busy) {
            val sessionId = currentSessionId
            val item = AiInboxItem(UUID.randomUUID().toString(), trimmed, attachments,
                modelName = llmSettings.modelName, reasoningEffort = reasoningEffort)
            launchStorage {
                val updated = conversationStore.mutate(sessionId) { it.copy(inbox = it.inbox + item) }
                withContext(Dispatchers.Main.immediate) { updated?.let(::installSession) }
                pollWakeUp.tryEmit(Unit)
            }
            return
        }
        if (trimmed == "/compact" && attachments.isEmpty()) {
            compactConversation()
            return
        }
        runCatching {
            mutateCurrent { session ->
                val id = (session.messages.maxOfOrNull { it.id } ?: 0L) + 1
                session.copy(
                    messages = session.messages + AiEditorMessage(id, true, trimmed, attachments),
                    title = session.title.ifBlank {
                        trimmed.lineSequence().firstOrNull().orEmpty().ifBlank { attachments.firstOrNull()?.name.orEmpty() }.take(120)
                    },
                    context = session.context?.copy(estimatedTokens = null),
                )
            }
        }.onFailure { error = it.message; return }
        startRequest()
    }

    fun regenerate(messageId: Long) {
        if (busy) return
        val index = messages.indexOfFirst { it.id == messageId }
        if (index <= 0 || messages[index].fromUser) return
        truncateConversation(messageId)
        startRequest()
    }

    fun resumeInbox() {
        if (busy || inbox.isEmpty()) return
        // Do not remove pending input until a worker actually claims it.
        startRequest()
    }

    fun rollbackTo(messageId: Long) {
        if (busy) return
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        truncateConversation(messageId)
        pendingPatch = null
        error = null
    }

    private fun truncateConversation(messageId: Long) {
        mutateCurrent { session ->
            val index = session.messages.indexOfFirst { it.id == messageId }
            if (index < 0) session else {
                val retained = session.messages.take(index)
                session.copy(messages = retained, context = session.context?.retainSources(retained.mapTo(HashSet()) { it.id }))
            }
        }
    }

    fun newConversation() {
        if (!storageReady || switchingConversation || busy) return
        val current = sessions.firstOrNull { it.id == currentSessionId }
        if (current == null || current.hasUserPrompt()) {
            val session = AiEditorSession(UUID.randomUUID().toString())
            sessions += session
            currentSessionId = session.id
        }
        messages.clear()
        pendingPatch = null
        error = null
        appliedBackupPath = null
    }

    fun switchConversation(sessionId: String) {
        if (!storageReady || switchingConversation || busy || sessionId == currentSessionId) return
        switchingConversation = true
        launchStorage {
            try {
                val session = conversationStore.load(sessionId) ?: return@launchStorage
                withContext(Dispatchers.Main.immediate) {
                    currentSessionId = session.id
                    messages.clear()
                    messages += session.messages
                    nextMessageId = (messages.maxOfOrNull { it.id } ?: 0L) + 1L
                    error = null
                    appliedBackupPath = null
                    installSession(session)
                    refreshBusyState()
                }
            } finally {
                withContext(Dispatchers.Main.immediate) { switchingConversation = false }
            }
        }
    }

    fun cancelGeneration() {
        val sessionId = currentSessionId
        launchStorage {
            val job = jobStore.listForConversation(sessionId)
                .firstOrNull { it.status == AiAgentJobStatus.RUNNING } ?: return@launchStorage
            withContext(Dispatchers.Main.immediate) {
                AiAgentExecutionService.cancel(context, job.jobId, job.modId)
            }
        }
    }

    fun compactConversation() {
        if (messages.isEmpty() || !llmSettings.isConfigured()) return
        startRequest(compact = true)
    }

    private fun startRequest(compact: Boolean = false) {
        if (!storageReady || currentSessionId.isBlank() || busy) return
        val jobId = UUID.randomUUID().toString()
        if (!AiAgentExecutionService.reserve(jobId)) {
            error = "Another AI task is running."
            return
        }
        var assistantId: Long? = null
        runCatching {
            mutateCurrent { session ->
                val id = (session.messages.maxOfOrNull { it.id } ?: 0L) + 1
                assistantId = id
                session.copy(messages = session.messages + AiEditorMessage(
                    id, false, "", streaming = true, modelName = llmSettings.modelName,
                    parts = if (compact) listOf(AiMessagePart(AiMessagePartKind.COMPACTION_REQUEST)) else emptyList(),
                ))
            }
            val job = AiAgentJobRecord(
                jobId = jobId, conversationId = currentSessionId, modId = modId,
                modName = modName, storagePath = storagePath, assistantMessageId = requireNotNull(assistantId),
            )
            error = null
            busy = true
            pollWakeUp.tryEmit(Unit)
            AiEditorStorage.scope.launch {
                runCatching {
                    jobStore.upsert(job)
                    withContext(Dispatchers.Main.immediate) {
                        AiAgentExecutionService.start(context, job)
                    }
                }
                    .onFailure { failure ->
                        AiAgentExecutionService.release(jobId)
                        val message = failure.message ?: failure.javaClass.simpleName
                        runCatching {
                            jobStore.update(jobId) {
                                it.copy(status = AiAgentJobStatus.FAILED, errorMessage = message)
                            }
                        }
                        withContext(Dispatchers.Main.immediate) {
                            runCatching { mutateCurrent { session ->
                                session.copy(messages = session.messages.map {
                                    if (it.id == assistantId) it.copy(streaming = false, failed = true, errorMessage = message) else it
                                })
                            } }
                            busy = false
                            error = message
                        }
                    }
            }
        }
            .onFailure { failure ->
                AiAgentExecutionService.release(jobId)
                val message = failure.message ?: failure.javaClass.simpleName
                runCatching { mutateCurrent { session ->
                    session.copy(messages = session.messages.map {
                        if (it.id == assistantId) it.copy(streaming = false, failed = true, errorMessage = message) else it
                    })
                } }
                busy = false
                error = message
            }
    }

    fun availableTools(): List<AiToolInfo> = toolInfo

    fun applyPendingPatch() {
        val patch = pendingPatch ?: return
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { JarPatchApplier.apply(context, File(storagePath), patch) } }
                .onSuccess { result ->
                    pendingPatch = null
                    appliedBackupPath = result.backupFile.absolutePath
                    LauncherNavigationRequestBus.requestModsRefresh()
                }
                .onFailure { failure -> error = failure.message ?: failure.javaClass.simpleName }
            busy = false
        }
    }

    fun restoreBackup() {
        val backup = appliedBackupPath?.let(::File) ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { JarPatchApplier.restore(context, File(storagePath), backup) } }
                .onSuccess { result ->
                    appliedBackupPath = result.backupFile.absolutePath
                    LauncherNavigationRequestBus.requestModsRefresh()
                }
                .onFailure { failure -> error = failure.message ?: failure.javaClass.simpleName }
            busy = false
        }
    }

    override fun onCleared() {
        super.onCleared()
    }

    companion object {
        fun factory(
            context: Context,
            storagePath: String,
            modName: String,
            modId: String,
            requestedSessionId: String? = null,
        ) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AiModEditorViewModel(
                        context.applicationContext,
                        storagePath,
                        modName,
                        modId,
                        requestedSessionId,
                    ) as T
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherAiModEditorScreen(
    storagePath: String,
    modName: String,
    modId: String,
    conversationId: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var keepScreenOn by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(activity, keepScreenOn) {
        val window = activity?.window
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val originallyEnabled = window != null && (window.attributes.flags and flag) != 0
        if (keepScreenOn) window?.addFlags(flag) else window?.clearFlags(flag)
        onDispose {
            if (originallyEnabled) window?.addFlags(flag) else window?.clearFlags(flag)
        }
    }
    val historyContentDescription = stringResource(R.string.ai_mod_editor_history)
    val navigator = currentNavigator
    val viewModel: AiModEditorViewModel = viewModel(
        key = "${modId}:${conversationId.orEmpty()}",
        factory = AiModEditorViewModel.factory(context, storagePath, modName, modId, conversationId),
    )
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val showJumpToLatest by remember { derivedStateOf { listState.canScrollForward } }
    val composerHazeState = remember { HazeState() }
    var composerHeightPx by remember { mutableStateOf(0) }
    val composerHeight = with(LocalDensity.current) { composerHeightPx.toDp() }
    var draft by rememberSaveable { mutableStateOf("") }
    var draftAttachments = remember { mutableStateListOf<AiAttachment>() }
    var editingInboxId by remember(viewModel.currentSessionId) { mutableStateOf<String?>(null) }
    var stashedDraft by remember { mutableStateOf("") }
    var stashedAttachments by remember { mutableStateOf<List<AiAttachment>>(emptyList()) }
    var stashedModel by remember { mutableStateOf<String?>(null) }
    var stashedEffort by remember { mutableStateOf<LlmReasoningEffort?>(null) }
    fun finishInboxEdit() {
        editingInboxId = null
        draft = stashedDraft
        draftAttachments.clear()
        draftAttachments.addAll(stashedAttachments)
        stashedModel?.let(viewModel::selectModel)
        stashedEffort?.let(viewModel::updateReasoningEffort)
    }
    var showApplyConfirmation by rememberSaveable { mutableStateOf(false) }
    var showRestoreConfirmation by rememberSaveable { mutableStateOf(false) }
    var rollbackTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    val settings = viewModel.llmSettings
    val configured = settings.isConfigured()
    val attachmentScope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // Reading and downscaling a screenshot can take a second; keep the composer responsive.
        attachmentScope.launch {
            readAttachment(context, uri)?.let { draftAttachments += it }
        }
    }

    // The transcript opens on the newest message and materializes only the most recent few.
    // Older messages stay lazy so a long conversation does not inflate the first composition.
    val allMessages = viewModel.messages
    var visibleMessageCount by remember(viewModel.currentSessionId) {
        mutableIntStateOf(AI_CONVERSATION_INITIAL_VISIBLE_MESSAGES)
    }
    val visibleMessages = allMessages.takeLast(visibleMessageCount)
    val transcriptCache = remember(viewModel.currentSessionId) { java.util.concurrent.ConcurrentHashMap<Long, Pair<AiEditorMessage, List<AiTranscriptRow>>>() }
    // Keyed on the view model's revision counter and the window size, not on the message list:
    // comparing that list means deep-comparing every stored part of the streaming message.
    val preparedTranscript by produceState<Pair<String, List<AiTranscriptRow>>>(
        "" to emptyList(),
        viewModel.currentSessionId,
        viewModel.transcriptRevision,
        visibleMessageCount,
    ) {
        val sessionId = viewModel.currentSessionId
        val rows = withContext(Dispatchers.Default) {
            visibleMessages.flatMap { message ->
                val cached = transcriptCache[message.id]
                val rows = if (cached?.first === message) {
                    cached.second
                } else {
                    val fresh = buildAiTranscriptRows(message)
                    val reused = cached?.second?.let { reuseTranscriptRows(it, fresh) }
                    (reused ?: fresh).also { transcriptCache[message.id] = message to it }
                }
                rows
            }.also { transcriptCache.keys.retainAll(visibleMessages.map { it.id }.toSet()) }
        }
        value = sessionId to rows
    }
    val transcriptRows = preparedTranscript.second.takeIf { preparedTranscript.first == viewModel.currentSessionId }.orEmpty()
    val hiddenMessageCount = allMessages.size - visibleMessages.size
    var earlierAnchorMessageId by remember(viewModel.currentSessionId) { mutableStateOf<String?>(null) }
    var earlierAnchorOffset by remember(viewModel.currentSessionId) { mutableIntStateOf(0) }
    var awaitingEarlierAnchorRestore by remember(viewModel.currentSessionId) { mutableStateOf(false) }
    var initialScrollApplied by remember(viewModel.currentSessionId) { mutableStateOf(false) }

    val onLoadEarlierMessages: () -> Unit = {
        val anchor = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { info -> (info.key as? String)?.substringBefore(':')?.toLongOrNull() != null }
        earlierAnchorMessageId = anchor?.key as? String
        earlierAnchorOffset = anchor?.let { -it.offset } ?: 0
        awaitingEarlierAnchorRestore = true
        visibleMessageCount += minOf(AI_CONVERSATION_LAZY_LOAD_STEP, hiddenMessageCount)
    }

    // Entering the screen (or switching conversations) lands on the newest message without
    // animating up from the top. Later appends leave the viewport alone: the user keeps reading
    // wherever they scrolled to and the jump-to-latest action brings them back down.
    LaunchedEffect(viewModel.currentSessionId, transcriptRows, viewModel.storageReady) {
        if (!viewModel.storageReady || initialScrollApplied || transcriptRows.isEmpty()) return@LaunchedEffect
        val lastIndex = snapshotFlow { listState.layoutInfo.totalItemsCount }
            .filter { count -> count >= transcriptRows.size + (if (hiddenMessageCount > 0) 1 else 0) + 1 }
            .first() - 1
        if (lastIndex >= 0) {
            listState.scrollToItem(lastIndex)
            initialScrollApplied = true
        }
    }

    // Reveal one more batch whenever the "load earlier" row scrolls into view.
    LaunchedEffect(listState, hiddenMessageCount, initialScrollApplied, awaitingEarlierAnchorRestore) {
        if (hiddenMessageCount <= 0) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (index == 0 && initialScrollApplied && !awaitingEarlierAnchorRestore) {
                    onLoadEarlierMessages()
                }
            }
    }

    // Prepending older messages shifts every index, so pin the viewport back to the message
    // the user was reading instead of letting the list jump.
    LaunchedEffect(transcriptRows, earlierAnchorMessageId) {
        if (earlierAnchorMessageId == null && !awaitingEarlierAnchorRestore) return@LaunchedEffect
        if (transcriptRows.firstOrNull()?.message?.id != visibleMessages.firstOrNull()?.id) return@LaunchedEffect
        val anchorId = earlierAnchorMessageId
        if (anchorId != null) {
            val headerCount = if (hiddenMessageCount > 0) 1 else 0
            val anchorIndex = transcriptRows.indexOfFirst { row -> row.key == anchorId }
            if (anchorIndex >= 0) listState.scrollToItem(headerCount + anchorIndex, earlierAnchorOffset)
        }
        earlierAnchorMessageId = null
        awaitingEarlierAnchorRestore = false
    }

    Scaffold(
        modifier = modifier,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                LauncherTopAppBar(
                title = {
                    Box {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = viewModel.storageReady && !viewModel.switchingConversation) { showHistory = !showHistory }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .semantics { contentDescription = historyContentDescription },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(stringResource(R.string.ai_mod_editor_title), style = MaterialTheme.typography.titleMedium)
                                Text(modName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(
                                Icons.KeyboardArrowUp,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 4.dp).size(20.dp).rotate(if (showHistory) 0f else 180f),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = showHistory,
                            onDismissRequest = { showHistory = false },
                        ) {
                            Text(
                                stringResource(R.string.ai_mod_editor_history),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val conversationHistory = viewModel.history
                            if (conversationHistory.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ai_mod_editor_history_empty)) },
                                    onClick = { showHistory = false },
                                )
                            } else {
                                conversationHistory.asReversed().forEach { session ->
                                    DropdownMenuItem(
                                        text = { Text(session.displayTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = if (session.id == viewModel.currentSessionId) {
                                            { Icon(Icons.Description, contentDescription = null) }
                                        } else null,
                                        onClick = {
                                            showHistory = false
                                            viewModel.switchConversation(session.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = navigator::goBack) {
                        Icon(Icons.ArrowBack, stringResource(R.string.common_content_desc_back))
                    }
                },
                actions = {
                    AiMessageAction(
                        icon = ImageVector.vectorResource(R.drawable.ic_chat_add),
                        label = stringResource(R.string.ai_mod_editor_new_chat),
                        onClick = viewModel::newConversation,
                        enabled = !viewModel.busy,
                    )
                    AiMessageAction(
                        icon = ImageVector.vectorResource(R.drawable.ic_keep_screen_on),
                        label = stringResource(if (keepScreenOn) R.string.ai_mod_editor_keep_screen_on_enabled else R.string.ai_mod_editor_keep_screen_on),
                        onClick = {
                            keepScreenOn = !keepScreenOn
                            // The tooltip only shows its new label on long press, so confirm the
                            // toggle state right away.
                            LauncherTransientNoticeBus.show(
                                UiText.StringResource(
                                    if (keepScreenOn) R.string.ai_mod_editor_keep_screen_on_notice_on
                                    else R.string.ai_mod_editor_keep_screen_on_notice_off,
                                ),
                            )
                        },
                        highlighted = keepScreenOn,
                    )
                    AiContextCounter(
                        tokens = if (viewModel.messages.isEmpty()) 0 else viewModel.sessions
                            .firstOrNull { it.id == viewModel.currentSessionId }?.context
                            ?.takeIf { it.modelKey == AiContextLimits.key(settings.baseUrl, settings.modelName) }?.estimatedTokens,
                        modelKey = "${settings.baseUrl}/${settings.modelName}",
                        busy = viewModel.busy,
                        tools = viewModel.availableTools(),
                        contextState = viewModel.sessions.firstOrNull { it.id == viewModel.currentSessionId }?.context,
                        endpoint = settings.endpoint.name,
                        onCompact = viewModel::compactConversation,
                    )
                },
                )
            },
    ) { paddingValues ->
        if (viewModel.storageReady && !configured) {
            Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.ai_mod_editor_not_configured))
                TextButton(onClick = { navigator.push(Route.SettingsLlm) }) { Text(stringResource(R.string.settings_llm_title)) }
            }
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(paddingValues).imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = composerHazeState),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = composerHeight + 16.dp),
        ) {
            if (allMessages.isEmpty()) {
                item(key = "prompt-examples") {
                    val examples = listOf(
                        stringResource(R.string.ai_mod_editor_example_translate),
                        stringResource(R.string.ai_mod_editor_example_remove_starter_cards),
                        stringResource(R.string.ai_mod_editor_example_fix_crash),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.ai_mod_editor_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        examples.forEach { example ->
                            Surface(
                                onClick = { draft = example },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Text(
                                    text = example,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
            if (hiddenMessageCount > 0) {
                item(key = "earlier-messages") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onLoadEarlierMessages, enabled = !viewModel.busy) {
                            Text(
                                stringResource(
                                    R.string.ai_mod_editor_load_earlier,
                                    minOf(AI_CONVERSATION_LAZY_LOAD_STEP, hiddenMessageCount),
                                ),
                            )
                        }
                    }
                }
            }
            itemsIndexed(
                transcriptRows,
                key = { _, row -> row.key },
                contentType = { _, row -> if (row.footer) "footer" else row.message.parts.firstOrNull()?.kind ?: "user" },
            ) { index, row ->
                val message = row.message
                val original = row.original
                // One message is now many lazy rows. Keeping the old 24 dp between every row made
                // tool calls and text chunks drift apart; rows of the same message stay 8 dp apart.
                val startsMessage = index == 0 || transcriptRows[index - 1].message.id != message.id
                AiMessageCard(
                    modifier = Modifier.padding(
                        top = if (startsMessage) AI_TRANSCRIPT_MESSAGE_SPACING else AI_TRANSCRIPT_ROW_SPACING,
                    ),
                    message = message,
                    onCopy = { copyToClipboard(context, (original.tools.map { it.precedingText } + original.text).filter { it.isNotBlank() }.joinToString("\n\n")) },
                    onCopyError = { copyToClipboard(context, original.errorMessage) },
                    onRegenerate = { viewModel.regenerate(message.id) },
                    onRollback = { rollbackTargetId = message.id },
                    enabled = !viewModel.busy,
                    showFooter = row.footer,
                    hasCopyContent = original.text.isNotBlank() || original.tools.any { it.precedingText.isNotBlank() },
                    footerOnly = row.footer,
                    showProgress = original.text.isBlank() && original.thinking.isBlank() && original.parts.isEmpty() && original.tools.isEmpty(),
                )
            }
            viewModel.pendingPatch?.let {
                item {
                    Button(
                        onClick = { showApplyConfirmation = true },
                        enabled = !viewModel.busy,
                        modifier = Modifier.fillMaxWidth().padding(top = AI_TRANSCRIPT_MESSAGE_SPACING),
                    ) {
                        Text(stringResource(R.string.ai_mod_editor_apply_patch))
                    }
                }
            }
            viewModel.appliedBackupPath?.let { backupPath ->
                item {
                    Row(
                        modifier = Modifier.padding(top = AI_TRANSCRIPT_MESSAGE_SPACING),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.ai_mod_editor_applied, backupPath), color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showRestoreConfirmation = true }, enabled = !viewModel.busy) {
                            Text(stringResource(R.string.ai_mod_editor_restore))
                        }
                    }
                }
            }
            item(key = "conversation-end") { Spacer(Modifier.height(1.dp)) }
        }
        if (showJumpToLatest) {
            androidx.compose.material3.Surface(
                 modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = composerHeight + 12.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = 2.dp,
            ) {
                val jumpLabel = stringResource(R.string.ai_mod_editor_jump_latest)
                IconButton(onClick = {
                    scrollScope.launch {
                        val lastItem = listState.layoutInfo.totalItemsCount - 1
                        if (lastItem >= 0) listState.animateScrollToItem(lastItem)
                    }
                }) {
                    Icon(Icons.KeyboardArrowUp, jumpLabel, Modifier.rotate(180f))
                }
            }
        }
        val outputSpeeds by AiAgentExecutionService.outputSpeeds.collectAsState()
        AiChatComposer(
            modifier = Modifier.align(Alignment.BottomCenter),
            hazeState = composerHazeState,
            onComposerHeightChanged = { height ->
                if (composerHeightPx != height) composerHeightPx = height
            },
            draft = draft,
            onDraftChange = { draft = it },
            attachments = draftAttachments,
            onRemoveAttachment = { draftAttachments.remove(it) },
            onAttach = { picker.launch(arrayOf("text/*", "application/json", "application/octet-stream", "image/*")) },
            configured = configured && viewModel.storageReady,
            busy = viewModel.busy,
            inbox = viewModel.inbox,
            onInboxChange = viewModel::updateInbox,
            onResumeInbox = viewModel::resumeInbox,
            editingInboxId = editingInboxId,
            onEditInbox = { item ->
                if (editingInboxId == null) {
                    stashedDraft = draft
                    stashedAttachments = draftAttachments.toList()
                    stashedModel = viewModel.llmSettings.modelName
                    stashedEffort = viewModel.reasoningEffort
                }
                editingInboxId = item.id
                draft = item.text
                draftAttachments.clear()
                draftAttachments.addAll(item.attachments)
                item.modelName?.let(viewModel::selectModel)
                item.reasoningEffort?.let(viewModel::updateReasoningEffort)
            },
            onUndoInbox = { item ->
                viewModel.updateInbox(item.id, remove = true)
                draft = listOf(draft, item.text).filter { it.isNotBlank() }.joinToString("\n\n")
                draftAttachments.addAll(item.attachments)
            },
            onSteerInbox = { item ->
                viewModel.updateInbox(item.id, delivery = AiInboxDelivery.STEER)
                if (!viewModel.busy) viewModel.resumeInbox()
            },
            onReorderInbox = viewModel::reorderInbox,
            onCancelInboxEdit = { finishInboxEdit() },
            modelName = settings.modelName,
            models = settings.models,
            effort = viewModel.reasoningEffort,
            onEffortChange = viewModel::updateReasoningEffort,
            onModelSelect = viewModel::selectModel,
            onStop = viewModel::cancelGeneration,
            onSend = {
                val editing = editingInboxId
                if (editing != null) {
                    viewModel.saveInboxEdit(editing, draft, draftAttachments.toList()) { finishInboxEdit() }
                } else {
                    viewModel.send(draft, draftAttachments.toList())
                    draft = ""
                    draftAttachments.clear()
                }
            },
        )
    }
    viewModel.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.common_action_close)) }
            },
        )
    }
    if (showApplyConfirmation) {
        AlertDialog(
            onDismissRequest = { showApplyConfirmation = false },
            title = { Text(stringResource(R.string.ai_mod_editor_apply_title)) },
            text = { Text(stringResource(R.string.ai_mod_editor_apply_message)) },
            dismissButton = { TextButton(onClick = { showApplyConfirmation = false }) { Text(stringResource(R.string.common_action_close)) } },
            confirmButton = { Button(onClick = { showApplyConfirmation = false; viewModel.applyPendingPatch() }) { Text(stringResource(R.string.ai_mod_editor_apply_patch)) } },
        )
    }
    if (showRestoreConfirmation) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmation = false },
            title = { Text(stringResource(R.string.ai_mod_editor_restore)) },
            text = { Text(stringResource(R.string.ai_mod_editor_restore_message)) },
            dismissButton = { TextButton(onClick = { showRestoreConfirmation = false }) { Text(stringResource(R.string.common_action_close)) } },
            confirmButton = { Button(onClick = { showRestoreConfirmation = false; viewModel.restoreBackup() }) { Text(stringResource(R.string.ai_mod_editor_restore)) } },
        )
    }
    rollbackTargetId?.let { targetId ->
        AlertDialog(
            onDismissRequest = { rollbackTargetId = null },
            title = { Text(stringResource(R.string.ai_mod_editor_rollback_confirm_title)) },
            text = { Text(stringResource(R.string.ai_mod_editor_rollback_confirm_message)) },
            dismissButton = { TextButton(onClick = { rollbackTargetId = null }) { Text(stringResource(android.R.string.cancel)) } },
            confirmButton = {
                Button(onClick = {
                    rollbackTargetId = null
                    val target = viewModel.messages.firstOrNull { it.id == targetId }
                    viewModel.rollbackTo(targetId)
                    if (target != null) {
                        draft = target.text
                        draftAttachments.clear()
                        draftAttachments += target.attachments
                    }
                }) { Text(stringResource(R.string.ai_mod_editor_rollback)) }
            },
        )
        }
    }
}

@Composable
internal fun AiMessageCard(
    message: AiEditorMessage,
    onCopy: () -> Unit,
    onCopyError: () -> Unit,
    onRegenerate: () -> Unit,
    onRollback: () -> Unit,
    enabled: Boolean,
    showFooter: Boolean = true,
    hasCopyContent: Boolean = message.text.isNotBlank() || message.tools.any { it.precedingText.isNotBlank() },
    footerOnly: Boolean = false,
    showProgress: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var showActions by remember { mutableStateOf(false) }
    val assistantLabel = stringResource(R.string.ai_mod_editor_assistant_label)
    val actionsLabel = stringResource(R.string.ai_mod_editor_message_actions)
    val colors = MaterialTheme.colorScheme
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 15.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    )
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            horizontalAlignment = if (message.fromUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (message.fromUser && !footerOnly) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
                        .background(colors.surfaceContainerLow)
                        .border(1.dp, colors.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    message.attachments.forEach { attachment ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (attachment.isImage) {
                                AiAttachmentThumbnail(attachment, size = 96.dp)
                            } else {
                                Icon(Icons.AttachFile, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                            }
                            Text(
                                attachment.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (message.text.isNotBlank()) {
                        SelectionContainer {
                            Text(message.text, style = bodyStyle, color = colors.onSurface)
                        }
                    }
                }
            } else if (message.notice && !footerOnly) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.errorContainer.copy(alpha = 0.72f),
                    contentColor = colors.onErrorContainer,
                ) {
                    Text(
                        text = message.text,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                if (message.parts.isNotEmpty()) {
                    remember(message.parts) { coalesceAiParts(message.parts) }.forEachIndexed { partIndex, part ->
                        when (part.kind) {
                            AiMessagePartKind.COMPACTION -> AiCompactionPart(part.content, message.streaming)
                            AiMessagePartKind.COMPACTION_REQUEST -> if (message.parts.none { it.kind == AiMessagePartKind.COMPACTION })
                                Column {
                                    AiCompactionDivider(stringResource(if (message.streaming) R.string.ai_compaction_queued else R.string.ai_compaction_interrupted))
                                    if (!message.streaming && message.errorMessage.isNotBlank()) Text(message.errorMessage, color = colors.error)
                                }
                            AiMessagePartKind.TEXT -> AiAssistantText(part.content, bodyStyle, colors)
                            // Content grows during streaming; keep each block's state tied to its position.
                            AiMessagePartKind.THINKING -> key(message.id, partIndex) {
                                AiThinkingBlock(
                                    text = part.content,
                                    streaming = message.streaming,
                                    colors = colors,
                                )
                            }
                            AiMessagePartKind.TOOL -> message.tools.firstOrNull { it.id == part.toolId }
                                ?.let { AiToolCallRow(it, message.streaming) }
                        }
                    }
                } else {
                    message.tools.forEach { tool ->
                        if (tool.precedingText.isNotBlank()) {
                            MaterialTheme(typography = MaterialTheme.typography.copy(bodySmall = bodyStyle)) {
                                SimpleMarkdownContent(tool.precedingText, textColor = colors.onSurface, textSelectable = true)
                            }
                        }
                        AiToolCallRow(tool, message.streaming)
                    }
                    if (message.thinking.isNotBlank()) {
                        var thinkingExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
                        val thinkingPreview = message.thinking.lineSequence()
                            .firstOrNull { it.isNotBlank() }
                            ?.trim()
                            .orEmpty()
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { thinkingExpanded = !thinkingExpanded }
                                    .padding(vertical = 14.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    Icons.Pending,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (message.streaming && message.text.isBlank()) colors.primary else colors.onSurfaceVariant,
                                )
                                Text(
                                    stringResource(R.string.ai_mod_editor_thinking),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant,
                                )
                                Text(
                                    thinkingPreview,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant.copy(alpha = 0.75f),
                                )
                                Icon(
                                    Icons.KeyboardArrowUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp).rotate(if (thinkingExpanded) 0f else 180f),
                                    tint = colors.onSurfaceVariant,
                                )
                            }
                            if (thinkingExpanded) {
                                AiExpandedMarkdown(message.thinking, colors.onSurfaceVariant)
                            }
                        }
                    }
                    if (message.text.isNotBlank()) {
                        MaterialTheme(
                            typography = MaterialTheme.typography.copy(
                                bodySmall = bodyStyle,
                                bodyMedium = bodyStyle,
                                titleSmall = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = 26.sp,
                                    letterSpacing = 0.sp,
                                ),
                            ),
                        ) {
                            SimpleMarkdownContent(
                                message.text,
                                modifier = Modifier.fillMaxWidth(),
                                textColor = colors.onSurface,
                                codeContainerColor = colors.surfaceContainerLow,
                                textSelectable = true,
                            )
                        }
                    }
                }
                if (message.failed) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = colors.errorContainer,
                        contentColor = colors.onErrorContainer,
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.ai_mod_editor_error, message.errorMessage),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onCopyError) {
                                Text(stringResource(R.string.ai_mod_editor_copy))
                            }
                        }
                    }
                }
                if (showProgress && message.streaming && showFooter && !hasCopyContent && message.text.isBlank() && message.thinking.isBlank() && message.parts.isEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(vertical = 12.dp).size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            if (!message.notice && showFooter) {
                Row(
                    modifier = if (message.fromUser) Modifier else Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!message.fromUser) {
                        Text(
                            text = buildString {
                                append(message.modelName.ifBlank { assistantLabel })
                                message.elapsedMs?.let { append(" · "); append(String.format(java.util.Locale.getDefault(), "%.1fs", it / 1000.0)) }
                            },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!message.failed) {
                        Box {
                            IconButton(onClick = { showActions = true }, modifier = Modifier.semantics { contentDescription = actionsLabel }) {
                                Text("···", color = colors.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
                            }
                            DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                                if (hasCopyContent) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.ai_mod_editor_copy)) },
                                        onClick = { showActions = false; onCopy() },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (message.fromUser) R.string.ai_mod_editor_rollback else R.string.ai_mod_editor_regenerate)) },
                                    enabled = enabled && !message.streaming,
                                    onClick = {
                                        showActions = false
                                        if (message.fromUser) onRollback() else onRegenerate()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiAssistantText(
    text: String,
    bodyStyle: androidx.compose.ui.text.TextStyle,
    colors: androidx.compose.material3.ColorScheme,
) {
    if (text.isBlank()) return
    MaterialTheme(
        typography = MaterialTheme.typography.copy(
            bodySmall = bodyStyle,
            bodyMedium = bodyStyle,
            titleSmall = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                lineHeight = 26.sp,
                letterSpacing = 0.sp,
            ),
        ),
    ) {
        SimpleMarkdownContent(
            text,
            modifier = Modifier.fillMaxWidth(),
            textColor = colors.onSurface,
            codeContainerColor = colors.surfaceContainerLow,
            textSelectable = true,
        )
    }
}

@Composable
private fun AiThinkingBlock(
    text: String,
    streaming: Boolean,
    colors: androidx.compose.material3.ColorScheme,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (text.isBlank()) return
    val preview = remember(text) { text.lineSequence().firstOrNull { it.isNotBlank() }?.take(256)?.trim().orEmpty() }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 14.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Pending,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (streaming) colors.primary else colors.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.ai_mod_editor_thinking),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Text(
                preview,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant.copy(alpha = 0.75f),
            )
            Icon(
                Icons.KeyboardArrowUp,
                contentDescription = null,
                modifier = Modifier.size(16.dp).rotate(if (expanded) 0f else 180f),
                tint = colors.onSurfaceVariant,
            )
        }
        if (expanded) {
            AiExpandedMarkdown(text, colors.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiMessageAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    prominent: Boolean = false,
    highlighted: Boolean = false,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        if (prominent) {
            FilledIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
            }
        } else {
            IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(
                    icon,
                    contentDescription = label,
                    modifier = Modifier.size(18.dp),
                    tint = if (highlighted && enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.8f else 0.38f),
                )
            }
        }
    }
}

private suspend fun readAttachment(context: Context, uri: android.net.Uri): AiAttachment? =
    withContext(Dispatchers.IO) {
        runCatching {
            val name = context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: uri.lastPathSegment
                ?: "attachment"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            val imageMime = io.stamethyst.backend.llm.AgentImages.detectMime(name, bytes)
            if (imageMime != null) {
                require(bytes.size <= 24 * 1024 * 1024) { "Image is larger than 24 MiB." }
                // Screenshots are downscaled here once, so every later request reuses the same compact payload.
                val payload = io.stamethyst.backend.llm.AgentImages.prepare(name, bytes)
                    ?: return@runCatching null
                AiAttachment(name = name, content = "", mime = payload.mime, data = payload.base64)
            } else {
                require(bytes.size <= 512 * 1024) { "Attachment is larger than 512 KiB." }
                AiAttachment(
                    name = name,
                    content = bytes.toString(Charsets.UTF_8),
                    mime = context.contentResolver.getType(uri) ?: "text/plain",
                )
            }
        }.getOrNull()
    }

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("AI message", text))
}

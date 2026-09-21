package io.stamethyst.ui.aimod

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
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
import io.stamethyst.backend.llm.LlmSettingsRepository
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.config.RuntimePaths
import io.stamethyst.navigation.Route
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.LauncherNavigationRequestBus
import io.stamethyst.ui.SimpleMarkdownContent
import io.stamethyst.ui.icon.ArrowBack
import io.stamethyst.ui.icon.AttachFile
import io.stamethyst.ui.icon.Description
import io.stamethyst.ui.icon.KeyboardArrowUp
import io.stamethyst.ui.icon.Pending
import io.stamethyst.ui.icon.Refresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

private const val AI_CONVERSATION_INITIAL_VISIBLE_MESSAGES = 3
private const val AI_CONVERSATION_LAZY_LOAD_STEP = 3

@Serializable
internal data class AiAttachment(val name: String, val content: String)

@Serializable
internal data class AiToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val precedingText: String = "",
    val result: String? = null,
    val failed: Boolean = false,
    val truncated: Boolean = false,
)

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
    val tools: List<AiToolCall> = emptyList(),
    val modelName: String = "",
    val elapsedMs: Long? = null,
    val contextTokens: Int? = null,
)

@Serializable
internal data class AiEditorSession(
    val id: String,
    val messages: List<AiEditorMessage> = emptyList(),
    val title: String = "",
    val revision: Long = 0,
    val context: io.stamethyst.backend.llm.AgentContextState? = null,
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
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var pendingPatch by mutableStateOf<JarPatch?>(null)
        private set
    var appliedBackupPath by mutableStateOf<String?>(null)
        private set
    var reasoningEffort by mutableStateOf(LlmSettingsRepository(context).get().reasoningEffort)
        private set

    private var nextMessageId = 1L
    private val settingsRepository = LlmSettingsRepository(context)
    var llmSettings by mutableStateOf(settingsRepository.get())
        private set
    private val parentModSegment = AgentPatchModManager.parentModSegment(modId)
    private val workspaceAccessRoot = RuntimePaths.agentModWorkspaceRoot(context, parentModSegment)

    private val conversationsRoot = RuntimePaths.agentModConversationsRoot(context, parentModSegment)
    private val conversationsFile get() = conversationsRoot.resolve("conversations.json")
    private val conversationStore = AiConversationStore(conversationsFile)
    private val jobStore = AiAgentJobStore(context, modId)
    var currentSessionId: String by mutableStateOf("")
        private set

    val history: List<AiEditorSession> get() = sessions.filter { it.hasUserPrompt() }

    init {
        workspaceAccessRoot.mkdirs()
        conversationsRoot.mkdirs()
        loadConversation()
        startConversationPolling()
    }

    private fun loadConversation() {
        runCatching {
            recoverInterruptedJobs()
            val stored = conversationStore.load()
            sessions += stored
            val latest = requestedSessionId?.let { requestedId ->
                sessions.firstOrNull { it.id == requestedId }
            } ?: sessions.lastOrNull()
            if (latest == null) {
                val session = AiEditorSession(UUID.randomUUID().toString())
                sessions += session
                currentSessionId = session.id
            } else {
                currentSessionId = latest.id
                messages += latest.messages
                nextMessageId = (messages.maxOfOrNull { it.id } ?: 0L) + 1L
                pendingPatch = messages.lastOrNull { !it.fromUser }?.let { JarPatchCodec.extract(it.text) }
            }
            refreshBusyState()
        }.onFailure {
            sessions.clear()
            messages.clear()
            val session = AiEditorSession(UUID.randomUUID().toString())
            sessions += session
            currentSessionId = session.id
        }
    }

    private fun installSession(session: AiEditorSession) {
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) sessions[index] = session else sessions += session
        if (session.id != currentSessionId) return
        if (session.messages != messages.toList()) {
            messages.clear()
            messages += session.messages
        }
        nextMessageId = (messages.maxOfOrNull { it.id } ?: 0L) + 1
        pendingPatch = messages.lastOrNull { !it.fromUser }?.let { JarPatchCodec.extract(it.text) }
    }

    private fun mutateCurrent(transform: (AiEditorSession) -> AiEditorSession): AiEditorSession {
        val next = requireNotNull(conversationStore.mutate(
            currentSessionId, create = AiEditorSession(currentSessionId), transform = transform,
        ))
        installSession(next)
        return next
    }

    private fun startConversationPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(500L)
                runCatching {
                    recoverInterruptedJobs()
                    val stored = conversationStore.load()
                    withContext(Dispatchers.Main.immediate) {
                        applyExternalState(stored)
                    }
                }
            }
        }
    }

    private fun applyExternalState(stored: List<AiEditorSession>) {
        val merged = mergeAiSessions(sessions.toList(), stored)
        sessions.clear()
        sessions += merged
        merged.firstOrNull { it.id == currentSessionId }?.let(::installSession)
        // The job snapshot may predate a send/cancel on the main thread too.
        refreshBusyState()
    }

    private fun recoverInterruptedJobs() {
        val interruptedMessage = context.getString(R.string.ai_mod_editor_error_interrupted)
        val interrupted = jobStore.markInterrupted(
            interruptedMessage = interruptedMessage,
            isRunning = { job -> AiAgentExecutionService.isJobRunning(job.jobId) },
        )
        interrupted.forEach { job ->
            conversationStore.update(job.conversationId) { session ->
                session.copy(messages = session.messages.map { message ->
                    if (message.id == job.assistantMessageId) {
                        message.copy(
                            streaming = false,
                            failed = true,
                            errorMessage = interruptedMessage,
                        )
                    } else {
                        message
                    }
                })
            }
        }
    }

    private fun refreshBusyState(jobs: List<AiAgentJobRecord> = jobStore.list()) {
        busy = jobs.any {
            it.conversationId == currentSessionId && it.status == AiAgentJobStatus.RUNNING
        }
    }

    fun updateReasoningEffort(value: LlmReasoningEffort) {
        reasoningEffort = value
        val updated = settingsRepository.get().copy(reasoningEffort = value)
        settingsRepository.set(updated)
        llmSettings = settingsRepository.get()
    }

    fun selectModel(modelName: String) {
        val trimmed = modelName.trim()
        if (trimmed.isEmpty() || trimmed == llmSettings.modelName) return
        settingsRepository.set(llmSettings.copy(modelName = trimmed))
        llmSettings = settingsRepository.get()
    }

    fun send(prompt: String, attachments: List<AiAttachment> = emptyList()) {
        val trimmed = prompt.trim()
        if ((trimmed.isEmpty() && attachments.isEmpty()) || busy) return
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
        if (busy) return
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
        if (busy || sessionId == currentSessionId) return
        val session = conversationStore.load().firstOrNull { it.id == sessionId } ?: return
        currentSessionId = session.id
        messages.clear()
        messages += session.messages
        nextMessageId = (messages.maxOfOrNull { it.id } ?: 0L) + 1L
        pendingPatch = messages.lastOrNull { !it.fromUser }?.let { JarPatchCodec.extract(it.text) }
        error = null
        appliedBackupPath = null
        installSession(session)
        refreshBusyState()
    }

    fun cancelGeneration() {
        val job = jobStore.list().firstOrNull {
            it.conversationId == currentSessionId && it.status == AiAgentJobStatus.RUNNING
        } ?: return
        AiAgentExecutionService.cancel(context, job.jobId, job.modId)
    }

    private fun startRequest() {
        if (busy) return
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
                    id, false, "", streaming = true, modelName = settingsRepository.get().modelName,
                ))
            }
            val job = AiAgentJobRecord(
                jobId = jobId, conversationId = currentSessionId, modId = modId,
                modName = modName, storagePath = storagePath, assistantMessageId = requireNotNull(assistantId),
            )
            jobStore.upsert(job)
            error = null
            busy = true
            AiAgentExecutionService.start(context, job)
        }
            .onFailure { failure ->
                AiAgentExecutionService.release(jobId)
                val message = failure.message ?: failure.javaClass.simpleName
                jobStore.update(jobId) {
                    it.copy(status = AiAgentJobStatus.FAILED, errorMessage = message)
                }
                runCatching { mutateCurrent { session ->
                    session.copy(messages = session.messages.map {
                        if (it.id == assistantId) it.copy(streaming = false, failed = true, errorMessage = message) else it
                    })
                } }
                busy = false
                error = message
            }
    }

    private val toolInfo by lazy {
        AiModAgentExecutor(context, storagePath, modName, modId, reasoningEffort).availableTools()
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
    var showApplyConfirmation by rememberSaveable { mutableStateOf(false) }
    var showRestoreConfirmation by rememberSaveable { mutableStateOf(false) }
    var rollbackTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    val settings = viewModel.llmSettings
    val configured = settings.isConfigured()
    val imeBottomForLog = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottomForLog) {
        android.util.Log.i("AiModIme", "imeBottom=$imeBottomForLog")
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        readAttachment(context, uri)?.let { draftAttachments += it }
    }

    // The transcript opens on the newest message and materializes only the most recent few.
    // Older messages stay lazy so a long conversation does not inflate the first composition.
    val allMessages = viewModel.messages
    var visibleMessageCount by remember(viewModel.currentSessionId) {
        mutableIntStateOf(AI_CONVERSATION_INITIAL_VISIBLE_MESSAGES)
    }
    val visibleMessages = allMessages.takeLast(visibleMessageCount)
    val hiddenMessageCount = allMessages.size - visibleMessages.size
    var earlierAnchorMessageId by remember(viewModel.currentSessionId) { mutableStateOf<Long?>(null) }
    var awaitingEarlierAnchorRestore by remember(viewModel.currentSessionId) { mutableStateOf(false) }
    var initialScrollApplied by remember(viewModel.currentSessionId) { mutableStateOf(false) }
    // Whether the user is parked at the newest message. Only scroll gestures update this, so a
    // viewport shrink (keyboard, window resize) is not mistaken for the user leaving the bottom.
    var pinnedToBottom by remember(viewModel.currentSessionId) { mutableStateOf(true) }
    var lastListHeightPx by remember { mutableIntStateOf(0) }

    val onLoadEarlierMessages: () -> Unit = {
        earlierAnchorMessageId = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { info -> info.key is Long }
            ?.key as? Long
        awaitingEarlierAnchorRestore = true
        visibleMessageCount += minOf(AI_CONVERSATION_LAZY_LOAD_STEP, hiddenMessageCount)
    }

    // Entering the screen (or switching conversations) lands on the newest message without
    // animating up from the top; later appends keep the smooth auto-scroll.
    LaunchedEffect(viewModel.currentSessionId, allMessages.size) {
        if (allMessages.isEmpty()) {
            initialScrollApplied = true
            return@LaunchedEffect
        }
        val lastIndex = snapshotFlow { listState.layoutInfo.totalItemsCount }
            .filter { count -> count > 0 }
            .first() - 1
        if (initialScrollApplied) {
            listState.animateScrollToItem(lastIndex)
        } else {
            listState.scrollToItem(lastIndex)
            initialScrollApplied = true
        }
    }

    // Reveal one more batch whenever the "load earlier" row scrolls into view.
    LaunchedEffect(listState, hiddenMessageCount) {
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
    LaunchedEffect(visibleMessageCount, earlierAnchorMessageId) {
        if (earlierAnchorMessageId == null && !awaitingEarlierAnchorRestore) return@LaunchedEffect
        val anchorId = earlierAnchorMessageId
        if (anchorId != null) {
            val headerCount = if (hiddenMessageCount > 0) 1 else 0
            val anchorIndex = visibleMessages.indexOfFirst { message -> message.id == anchorId }
            if (anchorIndex >= 0) listState.scrollToItem(headerCount + anchorIndex)
        }
        earlierAnchorMessageId = null
        awaitingEarlierAnchorRestore = false
    }

    // A user scroll (drag, fling, or an animated jump) is the only thing that may un-pin the
    // transcript from the newest message.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    pinnedToBottom = !listState.canScrollForward
                    android.util.Log.i("AiModIme", "pinned settle scrolling=$scrolling pinned=$pinnedToBottom canFwd=${listState.canScrollForward}")
                }
            }
    }

    AnimatedVisibility(
        visible = true,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(260)) +
            slideInVertically(
                animationSpec = tween(320),
                initialOffsetY = { fullHeight -> fullHeight / 14 },
            ),
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                title = {
                    Box {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showHistory = !showHistory }
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
                    AiContextCounter(
                        tokens = if (viewModel.messages.isEmpty()) 0 else viewModel.sessions
                            .firstOrNull { it.id == viewModel.currentSessionId }?.context
                            ?.takeIf { it.modelKey == AiContextLimits.key(settings.baseUrl, settings.modelName) }?.estimatedTokens,
                        modelKey = "${settings.baseUrl}/${settings.modelName}",
                        busy = viewModel.busy,
                        tools = viewModel.availableTools(),
                        contextState = viewModel.sessions.firstOrNull { it.id == viewModel.currentSessionId }?.context,
                    )
                },
                )
            },
        ) { paddingValues ->
        if (!configured) {
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
                // The keyboard (and window resizes) shrink this list. When that happens while the
                // user is parked at the newest message, follow the bottom instead of leaving the
                // latest reply hidden behind the IME.
                .onSizeChanged { size ->
                    val previousHeight = lastListHeightPx
                    lastListHeightPx = size.height
                    android.util.Log.i(
                        "AiModIme",
                        "resize prev=$previousHeight new=${size.height} pinned=$pinnedToBottom total=${listState.layoutInfo.totalItemsCount} canFwd=${listState.canScrollForward}",
                    )
                    if (previousHeight != 0 && previousHeight != size.height && pinnedToBottom) {
                        scrollScope.launch {
                            val lastItem = listState.layoutInfo.totalItemsCount - 1
                            android.util.Log.i("AiModIme", "pinScroll lastItem=$lastItem")
                            if (lastItem >= 0) listState.scrollToItem(lastItem)
                        }
                    }
                }
                .hazeSource(state = composerHazeState),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = composerHeight + 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (allMessages.isEmpty()) {
                item { Text(stringResource(R.string.ai_mod_editor_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
            items(visibleMessages, key = { it.id }) { message ->
                AiMessageCard(
                    message = message,
                    onCopy = { copyToClipboard(context, (message.tools.map { it.precedingText } + message.text).filter { it.isNotBlank() }.joinToString("\n\n")) },
                    onCopyError = { copyToClipboard(context, message.errorMessage) },
                    onRegenerate = { viewModel.regenerate(message.id) },
                    onRollback = { rollbackTargetId = message.id },
                    enabled = !viewModel.busy,
                )
            }
            viewModel.pendingPatch?.let {
                item {
                    Button(onClick = { showApplyConfirmation = true }, enabled = !viewModel.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ai_mod_editor_apply_patch))
                    }
                }
            }
            viewModel.appliedBackupPath?.let { backupPath ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
            onAttach = { picker.launch(arrayOf("text/*", "application/json", "application/octet-stream")) },
            configured = configured,
            busy = viewModel.busy,
            modelName = settings.modelName,
            models = settings.models,
            effort = viewModel.reasoningEffort,
            onEffortChange = viewModel::updateReasoningEffort,
            onModelSelect = viewModel::selectModel,
            onStop = viewModel::cancelGeneration,
            onSend = {
                viewModel.send(draft, draftAttachments.toList())
                draft = ""
                draftAttachments.clear()
            },
        )
        }
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
private fun AiMessageCard(
    message: AiEditorMessage,
    onCopy: () -> Unit,
    onCopyError: () -> Unit,
    onRegenerate: () -> Unit,
    onRollback: () -> Unit,
    enabled: Boolean,
) {
    var thinkingExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    val assistantLabel = stringResource(R.string.ai_mod_editor_assistant_label)
    val actionsLabel = stringResource(R.string.ai_mod_editor_message_actions)
    val colors = MaterialTheme.colorScheme
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 15.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            horizontalAlignment = if (message.fromUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (message.fromUser) {
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
                            Icon(Icons.AttachFile, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
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
                            SimpleMarkdownContent(
                                message.thinking,
                                modifier = Modifier.padding(start = 4.dp, bottom = 16.dp),
                                textColor = colors.onSurfaceVariant,
                                textSelectable = true,
                            )
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
                if (message.streaming && message.text.isBlank() && message.thinking.isBlank()) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(vertical = 12.dp).size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
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
                            if (message.text.isNotBlank() || message.tools.any { it.precedingText.isNotBlank() }) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiMessageAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    prominent: Boolean = false,
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.8f else 0.38f),
                )
            }
        }
    }
}

private fun readAttachment(context: Context, uri: android.net.Uri): AiAttachment? = runCatching {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "attachment"
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    require(bytes.size <= 512 * 1024) { "Attachment is larger than 512 KiB." }
    AiAttachment(name, bytes.toString(Charsets.UTF_8))
}.getOrNull()

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("AI message", text))
}

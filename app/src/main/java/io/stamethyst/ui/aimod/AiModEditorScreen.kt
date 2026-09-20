package io.stamethyst.ui.aimod

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import android.os.SystemClock
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import io.stamethyst.backend.llm.AgentApiDescribeTool
import io.stamethyst.backend.llm.AgentApiSearchTool
import io.stamethyst.backend.llm.AgentChatModelFactory
import io.stamethyst.backend.llm.AgentPatchModCompileTool
import io.stamethyst.backend.llm.AgentPatchModCreateTool
import io.stamethyst.backend.llm.AgentModSourceDecompileTool
import io.stamethyst.backend.llm.AgentPatchModDeleteTool
import io.stamethyst.backend.llm.AgentPatchModListTool
import io.stamethyst.backend.llm.AgentPatchModPackageTool
import io.stamethyst.backend.llm.AgentPatchModSetEnabledTool
import io.stamethyst.backend.llm.AgentPatchModUpdateTool
import io.stamethyst.backend.llm.AgentPatchPreflightTool
import io.stamethyst.backend.llm.AgentPatchSmokeTestTool
import io.stamethyst.backend.llm.AgentPatchSkeletonTool
import io.stamethyst.backend.llm.AgentPatchTargetInspectTool
import io.stamethyst.backend.llm.AgentSkillTool
import io.stamethyst.backend.llm.AgentTool
import io.stamethyst.backend.llm.AgentToolExecutionEvent
import io.stamethyst.backend.llm.AgentToolPolicyGate
import io.stamethyst.backend.llm.AgentToolRegistry
import io.stamethyst.backend.llm.AgentToolSafety
import io.stamethyst.backend.llm.AgentWorkspaceDeleteTool
import io.stamethyst.backend.llm.AgentWorkspaceFileTool
import io.stamethyst.backend.llm.AgentWorkspaceListTool
import io.stamethyst.backend.llm.AgentWorkspaceWriteTool
import io.stamethyst.backend.llm.JarPatch
import io.stamethyst.backend.llm.JarPatchApplier
import io.stamethyst.backend.llm.JarPatchCodec
import io.stamethyst.backend.llm.LlmReasoningEffort
import io.stamethyst.backend.llm.LlmSettingsRepository
import io.stamethyst.backend.llm.OpenAiCompatibleModelConfig
import io.stamethyst.backend.llm.PolicyGatedAgentGateway
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.backend.mods.AgentPatchWorkspace
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private var generationJob: Job? = null
    private var activeAssistantId: Long? = null
    private val settingsRepository = LlmSettingsRepository(context)
    var llmSettings by mutableStateOf(settingsRepository.get())
        private set
    private val json = Json { ignoreUnknownKeys = true }
    private val parentModSegment = AgentPatchModManager.parentModSegment(modId)
    private val workspaceAccessRoot = RuntimePaths.agentModWorkspaceRoot(context, parentModSegment)

    // The patch-mod workspace is created on demand by the agent's create_agent_patch_mod tool,
    // never automatically when the editor opens.
    @Volatile
    private var activePatchWorkspace: AgentPatchWorkspace? = null
    private val agentToolPolicyGate = AgentToolPolicyGate(
        allowedSafety = setOf(
            AgentToolSafety.READ_ONLY,
            AgentToolSafety.WORKSPACE_WRITE,
            AgentToolSafety.PATCH_CREATE,
            AgentToolSafety.MOD_INSPECTION,
            AgentToolSafety.PATCH_COMPILE,
            AgentToolSafety.PATCH_PACKAGE,
            AgentToolSafety.PATCH_ENABLE,
            AgentToolSafety.PATCH_DELETE,
            AgentToolSafety.PATCH_SMOKE_TEST,
        ),
    )
    private val conversationsRoot = RuntimePaths.agentModConversationsRoot(context, parentModSegment)
    private val conversationsFile get() = conversationsRoot.resolve("conversations.json")
    var currentSessionId: String by mutableStateOf("")
        private set

    val history: List<AiEditorSession> get() = sessions.filter { it.hasUserPrompt() }

    init {
        workspaceAccessRoot.mkdirs()
        conversationsRoot.mkdirs()
        loadConversation()
    }

    private fun loadConversation() {
        runCatching {
            val stored = if (conversationsFile.exists()) {
                json.decodeFromString<List<AiEditorSession>>(conversationsFile.readText())
            } else {
                emptyList()
            }.filter { it.hasUserPrompt() }
            sessions += stored
            val latest = sessions.lastOrNull()
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
            persistConversation()
        }.onFailure {
            sessions.clear()
            messages.clear()
            val session = AiEditorSession(UUID.randomUUID().toString())
            sessions += session
            currentSessionId = session.id
        }
    }

    private fun persistConversation() {
        val index = sessions.indexOfFirst { it.id == currentSessionId }
        if (index >= 0) {
            val hadPrompt = messages.any { it.fromUser }
            sessions[index] = sessions[index].copy(
                messages = messages.toList(),
                title = if (hadPrompt) sessions[index].title else "",
            )
        }
        val stored = sessions.filter { it.hasUserPrompt() }
        runCatching { conversationsFile.writeText(json.encodeToString(stored)) }
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
        val isFirstUserTurn = messages.none { it.fromUser }
        messages += AiEditorMessage(nextMessageId++, true, trimmed, attachments)
        if (isFirstUserTurn) {
            val title = trimmed.lineSequence().firstOrNull()?.trim().orEmpty()
                .ifBlank { attachments.firstOrNull()?.name.orEmpty() }
            if (title.isNotBlank()) {
                val index = sessions.indexOfFirst { it.id == currentSessionId }
                if (index >= 0 && sessions[index].title.isBlank()) {
                    sessions[index] = sessions[index].copy(title = title.take(120))
                }
            }
        }
        persistConversation()
        startRequest()
    }

    fun retry(messageId: Long) {
        if (busy) return
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        messages.subList(index, messages.size).clear()
        persistConversation()
        startRequest()
    }

    fun regenerate(messageId: Long) {
        if (busy) return
        val index = messages.indexOfFirst { it.id == messageId }
        if (index <= 0 || messages[index].fromUser) return
        messages.subList(index, messages.size).clear()
        persistConversation()
        startRequest()
    }

    fun rollbackTo(messageId: Long) {
        if (busy) return
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        messages.subList(index, messages.size).clear()
        pendingPatch = null
        error = null
        persistConversation()
    }

    fun newConversation() {
        if (busy) return
        persistConversation()
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
        persistConversation()
        val session = sessions.firstOrNull { it.id == sessionId } ?: return
        currentSessionId = session.id
        messages.clear()
        messages += session.messages
        nextMessageId = (messages.maxOfOrNull { it.id } ?: 0L) + 1L
        pendingPatch = messages.lastOrNull { !it.fromUser }?.let { JarPatchCodec.extract(it.text) }
        error = null
        appliedBackupPath = null
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        activeAssistantId?.let { id ->
            messages.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { index ->
                messages[index] = messages[index].copy(streaming = false, failed = false)
            }
        }
        activeAssistantId = null
        busy = false
        persistConversation()
    }

    private fun startRequest() {
        if (busy) return
        val assistantId = nextMessageId++
        val startedAt = SystemClock.elapsedRealtime()
        activeAssistantId = assistantId
        messages += AiEditorMessage(assistantId, false, "", streaming = true, modelName = settingsRepository.get().modelName)
        busy = true
        error = null
        persistConversation()
        generationJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { requestAssistant(assistantId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure<AgentResult>(failure)
            }
            result.onSuccess { reply ->
                updateAssistant(assistantId) { message ->
                    // Streaming already accumulated every round into `text`; only replace it with the
                    // gateway's final answer when that answer is non-blank, so a stray empty final
                    // turn can never wipe out content the user already saw.
                    val finalText = reply.text.ifBlank { message.text }
                    message.copy(
                        streaming = false,
                        failed = false,
                        text = finalText,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                        contextTokens = reply.contextTokens,
                    )
                }
                pendingPatch = JarPatchCodec.extract(reply.text)
            }.onFailure { failure ->
                error = failure.message ?: failure.javaClass.simpleName
                updateAssistant(assistantId) { message -> message.copy(streaming = false, failed = true, elapsedMs = SystemClock.elapsedRealtime() - startedAt) }
            }
            busy = false
            activeAssistantId = null
            persistConversation()
        }
    }

    private fun requestAssistant(assistantId: Long): Result<AgentResult> {
        val settings = settingsRepository.get()
        check(settings.isConfigured()) { "LLM is not configured" }
        val config = OpenAiCompatibleModelConfig(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            organizationId = settings.organizationId,
            modelName = settings.modelName,
            endpoint = settings.endpoint,
            requestTimeoutSeconds = settings.requestTimeoutSeconds,
            reasoningEffort = reasoningEffort,
        )
        val gateway = PolicyGatedAgentGateway(
            chatModel = AgentChatModelFactory.create(config),
            agentWorkspaceRoot = workspaceAccessRoot,
            toolRegistry = AgentToolRegistry(
                tools = createAgentTools(),
                policyGate = agentToolPolicyGate,
                onToolExecution = { event ->
                    updateAssistant(assistantId) { message ->
                        if (event.result == null) {
                            message.copy(
                                text = "",
                                tools = message.tools + AiToolCall(
                                    id = event.id,
                                    name = event.name,
                                    arguments = event.arguments,
                                    precedingText = message.text,
                                ),
                            )
                        } else {
                            val displayResult = runCatching {
                                Json.parseToJsonElement(event.result).jsonObject["content"]?.jsonPrimitive?.content
                            }.getOrNull() ?: event.result
                            message.copy(tools = message.tools.map { tool ->
                                if (tool.id == event.id) tool.copy(
                                    result = displayResult.take(12_000),
                                    failed = event.failed,
                                    truncated = displayResult.length > 12_000,
                                ) else tool
                            })
                        }
                    }
                },
            ),
        )
        val transcript = messages.dropLast(1).joinToString("\n\n") { message ->
            "${if (message.fromUser) "User" else "Assistant"}: ${message.text}" +
                message.attachments.joinToString(prefix = "\nAttachments:\n", separator = "\n") { attachment ->
                    "[${attachment.name}]\n${attachment.content}"
                }.takeIf { message.attachments.isNotEmpty() }.orEmpty()
        }
        val systemPrompt = """
            You are a safe mod editing assistant inside a mobile launcher.
            The selected parent mod is '$modName' ($modId).
            Your agent workspace root is '$workspaceAccessRoot'. It contains source/ and patch_source/ for the selected mod.
            Use workspace tools for listing and reading the entire workspace. source/ is read-only context generated by decompile_agent_mod_source; never try to write or delete it. patch_source/ is the agent-owned patch tree and is the only directory writable or deletable through workspace tools.
            No patch workspace exists yet for this session. Do not create one unless the user actually asked for a change. When they do, call create_agent_patch_mod once for each new patch mod with a short descriptive name you choose, an optional semantic version, and a description; it prepares source/ and a dedicated patch_source/<patch_id>/ directory, then returns the exact paths to use.
            If the user asks to inspect or explain the parent mod, call decompile_agent_mod_source; it extracts and decompiles the parent directly into source/ in the selected mod workspace.
            Use write_agent_workspace_file to create or replace files under the current patch_workspace_path returned by create_agent_patch_mod, and delete_agent_workspace_file to remove files or directories there with recursive=true when needed. Do not attempt to modify source/ or another patch mod's directory.
            The launcher creates ModTheSpire.json and agent-patch.json in the current patch_source/<patch_id>/ directory. Keep the patch mod's modid and parent dependency intact.
            ModTheSpire resolves dependencies case-sensitively: copy the exact 'modid' text from source/ModTheSpire.json when listing the parent or any dependency, and never change its casing.
            To implement a requested change, call create_agent_patch_mod first; source/ is shared readable context and the returned patch_workspace_path is the writable tree for that patch mod.
            Before writing any Java against the game, BaseMod, or StSLib, read the bundled reference with read_agent_skill (skill name: basemod-and-stslib). It covers the registration lifecycle and ordering, where each content type is registered, hook names, StSLib keywords, and the mistakes that crash the game when it loads.
            Never write an API call from memory. Find the type with search_agent_api, then confirm the exact overload with describe_agent_api_class before calling or extending it; the declarations come from the installed bytecode.
            Prefer the BaseMod API over ModTheSpire patches: content such as cards, relics, potions, events, keywords, characters, and colors is registered through BaseMod from one @SpireInitializer class. Use @SpirePatch2 only where no API exists, and never use a Replace patch.
            For code changes, write Java 8 sources under <patch_workspace_path>/src/<package>/... using ModTheSpire's @SpirePatch2 and the game/BaseMod APIs, then call compile_agent_patch_source to compile them into that patch workspace. A patch mod does not have to be a @SpirePatch2 hook set: a resource-only patch mod and a mod that registers content through a single @SpireInitializer class with a public static void initialize() are valid too.
            Compilation is fixed to Java 8 and the classpath already contains the game jar, ModTheSpire, the required mods and the parent mod; do not add a build file or a classpath argument.
            If compile_agent_patch_source reports errors, read the diagnostics and fix the sources before packaging.
            When the patch mod is complete, call package_agent_patch_mod with a useful name, semantic version, and description.
            To change a patch mod you already packaged, call list_agent_patch_mods to get its patch_id, edit the files under patch_source/<patch_id>/, call compile_agent_patch_source with that patch_id, then call update_agent_patch_mod with the patch_id and a new version. This replaces the same revision and keeps its enabled state; do not create a duplicate patch mod for an update.
            Packaged patch mods are stored under agent_mods and are loaded by the launcher directly; there is no separate install step.
            After packaging, call smoke_test_agent_patch_mod to launch the game with the patch, the parent mod, the parent mod's prerequisites, and the built-in mods. It succeeds only when the game reaches the main menu; on failure it returns the boot events and log excerpt. If it fails, fix the patch, recompile, update the patch mod, and run it again.
            Ask before enabling a packaged patch mod unless the user explicitly requested activation. Use set_agent_patch_mod_enabled to enable or disable it.
            Use list_agent_patch_mods to check existing revisions and their enabled state before creating a duplicate.
            Use delete_agent_patch_mod with a patch_id to remove a patch revision entirely.
            Never claim that a patch mod was created, packaged, updated, enabled, disabled, or deleted until the corresponding tool confirms it.
            Ask before calling delete_agent_patch_mod unless the user explicitly requested the removal.
        """.trimIndent()
        val streamingModel = AgentChatModelFactory.createStreaming(config)
        return if (streamingModel == null) {
            val reply = gateway.respond(systemPrompt, transcript)
            Result.success(AgentResult(reply.text, reply.contextTokens))
        } else {
            val reply = gateway.respondStreaming(
                systemPrompt = systemPrompt,
                userPrompt = transcript,
                streamingModel = streamingModel,
                onText = { delta -> updateAssistant(assistantId) { it.copy(text = it.text + delta) } },
                onThinking = { delta -> updateAssistant(assistantId) { it.copy(thinking = it.thinking + delta) } },
            )
            Result.success(AgentResult(reply.text, reply.contextTokens))
        }
    }

    private fun updateAssistant(id: Long, transform: (AiEditorMessage) -> AiEditorMessage) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (id != activeAssistantId) return@launch
            val index = messages.indexOfFirst { it.id == id }
            if (index >= 0) messages[index] = transform(messages[index])
        }
    }

    /** Resolves a patch revision that already exists on disk, even from a previous session. */
    private fun resolveExistingPatchWorkspace(patchId: String): AgentPatchWorkspace? =
        AgentPatchModManager.resolvePatchWorkspace(context, modId, patchId, File(storagePath))

    /** Single source of truth for the tools exposed to the agent and shown in the context dialog. */
    private fun createAgentTools(): List<AgentTool> = listOf(
        AgentWorkspaceListTool(workspaceAccessRoot),
        AgentWorkspaceFileTool(workspaceAccessRoot),
        AgentSkillTool(context),
        AgentApiSearchTool(context, File(storagePath)),
        AgentApiDescribeTool(context, File(storagePath)),
        AgentPatchModCreateTool(
            context = context,
            parentModId = modId,
            sourceJar = File(storagePath),
            onCreated = { activePatchWorkspace = it },
        ),
        AgentWorkspaceWriteTool(workspaceAccessRoot),
        AgentWorkspaceDeleteTool(workspaceAccessRoot),
        AgentModSourceDecompileTool(
            context = context,
            parentModId = modId,
            parentJar = File(storagePath),
        ),
        AgentPatchTargetInspectTool(
            context = context,
            parentJar = File(storagePath),
        ),
        AgentPatchSkeletonTool(
            context = context,
            parentJar = File(storagePath),
            workspace = { activePatchWorkspace },
        ),
        AgentPatchModCompileTool(
            context = context,
            parentJar = File(storagePath),
            workspace = { activePatchWorkspace },
            resolveWorkspace = ::resolveExistingPatchWorkspace,
        ),
        AgentPatchPreflightTool(
            context = context,
            parentJar = File(storagePath),
            workspace = { activePatchWorkspace },
            resolveWorkspace = ::resolveExistingPatchWorkspace,
        ),
        AgentPatchModPackageTool(
            context = context,
            parentJar = File(storagePath),
            workspace = { activePatchWorkspace },
        ),
        AgentPatchModUpdateTool(
            context = context,
            parentModId = modId,
            parentJar = File(storagePath),
            onUpdated = { LauncherNavigationRequestBus.requestModsRefresh() },
        ),
        AgentPatchSmokeTestTool(
            context = context,
            parentModId = modId,
            parentJar = File(storagePath),
            workspace = { activePatchWorkspace },
        ),
        AgentPatchModSetEnabledTool(
            context = context,
            parentModId = modId,
            workspace = { activePatchWorkspace },
            onChanged = { LauncherNavigationRequestBus.requestModsRefresh() },
        ),
        AgentPatchModListTool(context, modId),
        AgentPatchModDeleteTool(
            context = context,
            parentModId = modId,
            onDeleted = { LauncherNavigationRequestBus.requestModsRefresh() },
        ),
    )

    /** Metadata for the context dialog, filtered by the same policy gate the agent runs under. */
    fun availableTools(): List<AiToolInfo> = createAgentTools()
        .filter(agentToolPolicyGate::allows)
        .map { tool ->
            val name = tool.specification.name()
            AiToolInfo(name = name, descriptionRes = agentToolDescriptionRes(name))
        }

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
        generationJob?.cancel()
        super.onCleared()
    }

    private data class AgentResult(val text: String, val contextTokens: Int? = null)

    companion object {
        fun factory(context: Context, storagePath: String, modName: String, modId: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AiModEditorViewModel(context.applicationContext, storagePath, modName, modId) as T
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherAiModEditorScreen(
    storagePath: String,
    modName: String,
    modId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val historyContentDescription = stringResource(R.string.ai_mod_editor_history)
    val navigator = currentNavigator
    val viewModel: AiModEditorViewModel = viewModel(factory = AiModEditorViewModel.factory(context, storagePath, modName, modId))
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

    Scaffold(
        modifier = modifier,
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
                        icon = Icons.Description,
                        label = stringResource(R.string.ai_mod_editor_new_chat),
                        onClick = viewModel::newConversation,
                        enabled = !viewModel.busy,
                    )
                    AiContextCounter(
                        tokens = if (viewModel.messages.isEmpty()) 0 else viewModel.messages.lastOrNull { !it.fromUser && !it.streaming }?.contextTokens,
                        modelKey = "${settings.baseUrl}/${settings.modelName}",
                        busy = viewModel.busy,
                        tools = viewModel.availableTools(),
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
                    onRetry = { viewModel.retry(message.id) },
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
            viewModel.error?.let { failure ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ai_mod_editor_error, failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        val retryId = viewModel.messages.lastOrNull { it.failed }?.id
                        if (retryId != null) TextButton(onClick = { viewModel.retry(retryId) }) { Text(stringResource(R.string.ai_mod_editor_retry)) }
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

@Composable
private fun AiMessageCard(
    message: AiEditorMessage,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
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
                if (message.failed) {
                    TextButton(onClick = onRetry, enabled = enabled) {
                        Text(stringResource(R.string.ai_mod_editor_retry), color = colors.error)
                    }
                }
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
                            text = { Text(stringResource(if (message.fromUser) R.string.ai_mod_editor_rollback else if (message.failed) R.string.ai_mod_editor_retry else R.string.ai_mod_editor_regenerate)) },
                            enabled = enabled && !message.streaming,
                            onClick = {
                                showActions = false
                                if (message.fromUser) onRollback() else if (message.failed) onRetry() else onRegenerate()
                            },
                        )
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
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
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

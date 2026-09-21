package io.stamethyst.ui.aimod

import android.content.Context
import io.stamethyst.backend.llm.AgentApiDescribeTool
import io.stamethyst.backend.llm.AgentApiSearchTool
import io.stamethyst.backend.llm.AgentChatModelFactory
import io.stamethyst.backend.llm.AgentContextBudget
import io.stamethyst.backend.llm.AgentContextManager
import io.stamethyst.backend.llm.AgentContextState
import io.stamethyst.backend.llm.AgentListInstalledModsTool
import io.stamethyst.backend.llm.AgentModSourceDecompileTool
import io.stamethyst.backend.llm.AgentPatchModCompileTool
import io.stamethyst.backend.llm.AgentPatchWorkspaceCreateTool
import io.stamethyst.backend.llm.AgentPatchModDeleteTool
import io.stamethyst.backend.llm.AgentPatchModListTool
import io.stamethyst.backend.llm.AgentPatchModPackageTool
import io.stamethyst.backend.llm.AgentPatchModSetEnabledTool
import io.stamethyst.backend.llm.AgentPatchModUpdateTool
import io.stamethyst.backend.llm.AgentPatchPreflightTool
import io.stamethyst.backend.llm.AgentPatchSkeletonTool
import io.stamethyst.backend.llm.AgentPatchSmokeTestTool
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
import io.stamethyst.backend.llm.LlmReasoningEffort
import io.stamethyst.backend.llm.OpenAiCompatibleModelConfig
import io.stamethyst.backend.llm.PolicyGatedAgentGateway
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.backend.mods.AgentPatchWorkspace
import io.stamethyst.config.RuntimePaths
import io.stamethyst.ui.LauncherNavigationRequestBus
import java.io.File

internal data class AiAgentExecutionResult(
    val text: String,
    val contextTokens: Int? = null,
)

internal class AiModAgentExecutor(
    private val context: Context,
    private val storagePath: String,
    private val modName: String,
    private val modId: String,
    private val reasoningEffort: LlmReasoningEffort,
    private val onToolExecution: (AgentToolExecutionEvent) -> Unit = {},
    private val onText: (String) -> Unit = {},
    private val onThinking: (String) -> Unit = {},
    private val onContext: (AgentContextState) -> Unit = {},
    private val checkCancelled: () -> Unit = {},
) {
    private val settingsRepository = io.stamethyst.backend.llm.LlmSettingsRepository(context)
    private val workspaceAccessRoot = RuntimePaths.agentModWorkspaceRoot(
        context,
        AgentPatchModManager.parentModSegment(modId),
    )
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

    @Volatile
    private var activePatchWorkspace: AgentPatchWorkspace? = null
    private var contextManager: AgentContextManager? = null

    fun execute(session: AiEditorSession, assistantMessageId: Long): AiAgentExecutionResult {
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
        val model = AgentChatModelFactory.create(config)
        val saved = session.modelContext(assistantMessageId)
        activePatchWorkspace = saved.activePatchId?.let(::resolveExistingPatchWorkspace)
        val registry = AgentToolRegistry(
                tools = createAgentTools(),
                policyGate = agentToolPolicyGate,
                onToolExecution = onToolExecution,
        )
        val systemPrompt = systemPrompt()
        val modelKey = AiContextLimits.key(settings.baseUrl, settings.modelName)
        val initial = if (saved.modelKey == modelKey) saved else saved.copy(
            modelKey = modelKey, estimateScale = 1.0, lastInputTokens = null, lastOutputTokens = null,
        )
        val manager = AgentContextManager(
            initial = initial,
            systemPrompt = systemPrompt,
            toolSchema = registry.toolSpecifications().joinToString("\n"),
            budget = AgentContextBudget(AiContextLimits.get(context, modelKey)),
            sourceMessageId = assistantMessageId,
            model = model,
            checkpoint = onContext,
            checkCancelled = checkCancelled,
        )
        contextManager = manager
        manager.persist()
        val gateway = PolicyGatedAgentGateway(
            chatModel = model,
            agentWorkspaceRoot = workspaceAccessRoot,
            toolRegistry = registry,
            contextManager = manager,
            checkCancelled = checkCancelled,
        )
        val streamingModel = AgentChatModelFactory.createStreaming(config)
        return if (streamingModel == null) {
            val reply = gateway.respond(systemPrompt, "")
            AiAgentExecutionResult(reply.text, reply.contextTokens)
        } else {
            val reply = gateway.respondStreaming(
                systemPrompt = systemPrompt,
                userPrompt = "",
                streamingModel = streamingModel,
                onText = onText,
                onThinking = onThinking,
            )
            AiAgentExecutionResult(reply.text, reply.contextTokens)
        }
    }

    private fun createAgentTools(): List<AgentTool> = listOf(
        AgentWorkspaceListTool(workspaceAccessRoot),
        AgentWorkspaceFileTool(workspaceAccessRoot),
        AgentSkillTool(context),
        AgentApiSearchTool(context, File(storagePath)),
        AgentApiDescribeTool(context, File(storagePath)),
        AgentPatchWorkspaceCreateTool(
            context = context,
            parentModId = modId,
            sourceJar = File(storagePath),
            onCreated = {
                activePatchWorkspace = it
                contextManager?.setActivePatch(it.patchId)
            },
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
        AgentListInstalledModsTool(context, modId),
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

    fun availableTools(): List<AiToolInfo> = createAgentTools()
        .filter(agentToolPolicyGate::allows)
        .map { AiToolInfo(it.specification.name(), agentToolDescriptionRes(it.specification.name())) }

    private fun resolveExistingPatchWorkspace(patchId: String): AgentPatchWorkspace? =
        AgentPatchModManager.resolvePatchWorkspace(context, modId, patchId, File(storagePath))

    private fun systemPrompt(): String = """
        You are a safe mod editing assistant inside a mobile launcher.
        The selected parent mod is '$modName' ($modId).
        Your agent workspace root is '$workspaceAccessRoot'. It contains source/ and patch_source/ for the selected mod.
        Use workspace tools for listing and reading the entire workspace. source/ is read-only context generated by decompile_agent_mod_source; never try to write or delete it. patch_source/ is the agent-owned patch tree and is the only directory writable or deletable through workspace tools.
        The current active patch ID, when available, is appended to these instructions. Inspect existing files and patch IDs in conversation memory before creating a workspace.
        Do not create a workspace unless the user asked for a new patch and no existing patch workspace should be reused. create_agent_patch_workspace prepares a dedicated patch_source/<patch_id>/ directory and returns the exact paths to use. It does not compile, package, install, or enable a mod.
        If the user asks to inspect or explain the parent mod, call decompile_agent_mod_source; it extracts and decompiles the parent directly into source/ in the selected mod workspace.
        Use write_agent_workspace_file to create or replace files under the current patch_workspace_path returned by create_agent_patch_workspace, and delete_agent_workspace_file to remove files or directories there with recursive=true when needed. Do not attempt to modify source/ or another patch mod's directory.
        The launcher creates ModTheSpire.json and agent-patch.json in the current patch_source/<patch_id>/ directory. Keep the patch mod's modid and parent dependency intact.
        ModTheSpire resolves dependencies case-sensitively: copy the exact 'modid' text from source/ModTheSpire.json when listing the parent or any dependency, and never change its casing.
        For a new patch with no suitable existing workspace, call create_agent_patch_workspace first. For any change to an existing patch, inspect or list the existing revisions and continue directly in that revision's patch_source/<patch_id>/ tree; do not create another workspace. After changing an already packaged revision, compile it with its patch_id and call update_agent_patch_mod so the same revision is replaced.
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
        Call list_installed_mods to see which mods are installed. When the patch is meant to interact with another optional mod, pass its mod_id in smoke_test_agent_patch_mod's mod_ids so the test enables it too; the user's own mod selection is ignored, so this is the only way to verify that combination.
        Ask before enabling a packaged patch mod unless the user explicitly requested activation. Use set_agent_patch_mod_enabled to enable or disable it.
        Use list_agent_patch_mods to check existing revisions and their enabled state before deciding whether a new workspace is necessary. Do not create a duplicate workspace for an ordinary follow-up modification.
        Use delete_agent_patch_mod with a patch_id to remove a patch revision entirely.
        Never claim that a patch mod was created, packaged, updated, enabled, disabled, or deleted until the corresponding tool confirms it.
        Ask before calling delete_agent_patch_mod unless the user explicitly requested the removal.
    """.trimIndent()
}

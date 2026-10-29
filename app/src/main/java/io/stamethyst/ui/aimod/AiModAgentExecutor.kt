package io.stamethyst.ui.aimod

import android.content.Context
import io.stamethyst.backend.llm.AgentApiClassDecompileTool
import io.stamethyst.backend.llm.AgentApiDescribeTool
import io.stamethyst.backend.llm.AgentApiSearchTool
import io.stamethyst.backend.llm.AgentChatModelFactory
import io.stamethyst.backend.llm.AgentCacheStats
import io.stamethyst.backend.llm.AgentContextBudget
import io.stamethyst.backend.llm.AgentContextManager
import io.stamethyst.backend.llm.AgentContextState
import io.stamethyst.backend.llm.AgentListInstalledModsTool
import io.stamethyst.backend.llm.AgentModClassDecompileTool
import io.stamethyst.backend.llm.AgentModClassInspectTool
import io.stamethyst.backend.llm.AgentModReadTargets
import io.stamethyst.backend.llm.AgentModEntriesListTool
import io.stamethyst.backend.llm.AgentModResourceReadTool
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
import io.stamethyst.backend.llm.AgentWorkspaceGlobTool
import io.stamethyst.backend.llm.AgentWorkspaceGrepTool
import io.stamethyst.backend.llm.AgentWorkspaceListTool
import io.stamethyst.backend.llm.AgentWorkspaceWriteTool
import io.stamethyst.backend.llm.LlmReasoningEffort
import io.stamethyst.backend.llm.OpenAiCompatibleModelConfig
import io.stamethyst.backend.llm.PolicyGatedAgentGateway
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.AgentPatchWorkspace
import io.stamethyst.backend.network.NetworkAvailability
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
    private val onRetry: (retryNumber: Int, delaySeconds: Long, error: Throwable) -> Unit = { _, _, _ -> },
    private val onContext: (AgentContextState) -> Unit = {},
    private val checkCancelled: () -> Unit = {},
    private val onCompaction: (io.stamethyst.backend.llm.AgentCompactionProgress) -> Unit = {},
    private val receiveInbox: (Boolean) -> AiDeliveredInput? = { null },
    private val onOutputSpeed: (Double) -> Unit = {},
    private val onExecutionStep: (io.stamethyst.backend.llm.AgentExecutionStep) -> Unit = {},
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
            modelName = settings.modelName,
            endpoint = settings.endpoint,
            requestTimeoutSeconds = settings.requestTimeoutSeconds,
            reasoningEffort = reasoningEffort,
            cacheAffinity = java.util.UUID.nameUUIDFromBytes(session.id.toByteArray(Charsets.UTF_8)).toString(),
        )
        val model = AgentChatModelFactory.create(config)
        var activeConfig = config
        var activeModel = model
        var activeStreamingModel = AgentChatModelFactory.createStreaming(config)
        fun applyInputSettings(input: AiDeliveredInput) {
            val next = activeConfig.copy(
                modelName = input.modelName ?: activeConfig.modelName,
                reasoningEffort = input.reasoningEffort ?: activeConfig.reasoningEffort,
            )
            if (next != activeConfig) {
                activeModel = AgentChatModelFactory.create(next)
                activeStreamingModel = AgentChatModelFactory.createStreaming(next)
                activeConfig = next
                val key = AiContextLimits.key(next.baseUrl, next.modelName)
                contextManager?.switchModel(key, AiContextLimits.get(context, key))
            }
        }
        // Compaction needs visible factual text. Reasoning tokens can consume the summary
        // output budget and leave the provider with an empty or truncated answer.
        val compactionModel = if (reasoningEffort == LlmReasoningEffort.OFF) {
            model
        } else {
            AgentChatModelFactory.create(config.copy(reasoningEffort = LlmReasoningEffort.OFF))
        }
        val saved = session.modelContext(assistantMessageId)
        activePatchWorkspace = saved.activePatchId?.let(::resolveExistingPatchWorkspace)
        val registry = AgentToolRegistry(
                tools = createAgentTools(),
                policyGate = agentToolPolicyGate,
                onToolExecution = onToolExecution,
        )
        val systemPrompt = buildAiEditorSystemPrompt(modName, modId, workspaceAccessRoot)
        val modelKey = AiContextLimits.key(settings.baseUrl, settings.modelName)
        val initial = (if (saved.modelKey == modelKey) saved else saved.copy(
            modelKey = modelKey, estimateScale = 1.0, lastInputTokens = null, lastOutputTokens = null,
            measuredMessageCount = null,
            cacheStats = AgentCacheStats(),
        )).let { state ->
            // A gateway's Chat and Responses routes may use different input accounting conventions.
            if (state.cacheStats.endpoint == settings.endpoint.name) state else state.copy(
                cacheStats = AgentCacheStats(endpoint = settings.endpoint.name),
                measuredMessageCount = null,
                lastInputTokens = null,
                lastOutputTokens = null,
            )
        }
        val manager = AgentContextManager(
            initial = initial,
            systemPrompt = systemPrompt,
            toolSchema = registry.toolSpecifications().joinToString("\n"),
            budget = AgentContextBudget(AiContextLimits.get(context, modelKey)),
            sourceMessageId = assistantMessageId,
            model = compactionModel,
            checkpoint = onContext,
            checkCancelled = checkCancelled,
            toolSpecifications = registry.toolSpecifications(),
            streamingModel = AgentChatModelFactory.createStreaming(config.copy(reasoningEffort = LlmReasoningEffort.OFF)),
            summaryTimeoutMillis = config.requestTimeoutSeconds.toLong() * 1_000,
            onCompaction = onCompaction,
            supportsImageInput = settings.supportsImageInput,
        )
        contextManager = manager
        manager.persist()
        if (session.messages.firstOrNull { it.id == assistantMessageId }?.parts
                ?.any { it.kind == AiMessagePartKind.COMPACTION_REQUEST } == true) {
            manager.prepare(manual = true)
            return AiAgentExecutionResult("", manager.state.estimatedTokens)
        }
        val gateway = PolicyGatedAgentGateway(
            chatModel = model,
            agentWorkspaceRoot = workspaceAccessRoot,
            toolRegistry = registry,
            contextManager = manager,
            checkCancelled = checkCancelled,
            onRetry = onRetry,
            onNonStreamingText = onText,
            onOutputSpeed = onOutputSpeed,
            onExecutionStep = onExecutionStep,
            currentChatModel = { activeModel },
            currentStreamingModel = { activeStreamingModel },
            receiveInbox = { idle ->
                val input = receiveInbox(idle)
                if (input == null) false else {
                    applyInputSettings(input)
                    val (message, assistantId) = input
                    manager.appendInput(io.stamethyst.backend.llm.AgentContextMessage(
                        message.id, "user", message.text,
                        attachments = message.attachments.map {
                            io.stamethyst.backend.llm.AgentContextAttachment(
                                name = it.name,
                                content = it.content,
                                mime = it.mime,
                                data = it.data,
                            )
                        },
                    ), assistantId)
                    true
                }
            },
            streamingTimeoutMillis = config.requestTimeoutSeconds.toLong()
                .coerceIn(
                    io.stamethyst.backend.llm.MIN_LLM_REQUEST_TIMEOUT_SECONDS.toLong(),
                    io.stamethyst.backend.llm.MAX_LLM_REQUEST_TIMEOUT_SECONDS.toLong(),
                ) * 1_000L,
            supportsImageInput = settings.supportsImageInput,
            hasUsableNetwork = { NetworkAvailability.hasValidatedNetwork(context) },
            evictConnectionPool = { AgentChatModelFactory.evictConnectionPool(config) },
        )
        // A resume-only request has no new user prompt; claim pending work before calling the model.
        if (session.messages.lastOrNull { it.id != assistantMessageId }?.fromUser == false) {
            receiveInbox(true)?.let { input ->
                applyInputSettings(input)
                val (message, assistantId) = input
                manager.appendInput(io.stamethyst.backend.llm.AgentContextMessage(
                    message.id, "user", message.text,
                    attachments = message.attachments.map {
                        io.stamethyst.backend.llm.AgentContextAttachment(
                            name = it.name,
                            content = it.content,
                            mime = it.mime,
                            data = it.data,
                        )
                    },
                ), assistantId)
            }
        }
        val streamingModel = activeStreamingModel
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

    private val modReadTargets by lazy {
        val installedMods = ModManager.listInstalledMods(context)
        AgentModReadTargets(modId, File(storagePath)) { installedMods }
    }

    private fun createAgentTools(): List<AgentTool> = listOf(
        AgentWorkspaceListTool(workspaceAccessRoot),
        AgentWorkspaceFileTool(workspaceAccessRoot),
        AgentWorkspaceGlobTool(workspaceAccessRoot),
        AgentWorkspaceGrepTool(workspaceAccessRoot),
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
        AgentModClassInspectTool(
            parentModId = modId,
            parentJar = File(storagePath),
            readTargets = modReadTargets,
        ),
        AgentModClassDecompileTool(
            context = context,
            parentModId = modId,
            parentJar = File(storagePath),
            readTargets = modReadTargets,
        ),
        AgentApiClassDecompileTool(
            context = context,
            parentJar = File(storagePath),
        ),
        AgentModResourceReadTool(File(storagePath), modReadTargets, RuntimePaths.importedStsJar(context)),
        AgentModEntriesListTool(File(storagePath), modReadTargets, RuntimePaths.importedStsJar(context)),
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
}

/** Shared instructions precede Mod/device-specific data so different editors share a long prefix. */
internal fun buildAiEditorSystemPrompt(modName: String, modId: String, workspaceAccessRoot: File): String = """
        You are a safe mod editing assistant inside a mobile launcher.
        Independent read/search/inspect tool calls may be issued together in one response; the launcher runs parallel-safe tools concurrently. Workspace changes, decompilation, compilation, packaging, activation, deletion, and smoke tests run exclusively in call order. If a call needs an earlier tool's output, wait for that output before choosing its arguments.
        Use workspace tools for listing and reading the patch workspace. source/ is a read-only, on-demand cache; it is not a full extraction of the parent JAR and must never be written or deleted through workspace tools. Use list_agent_mod_entries to discover JAR paths, inspect_agent_class for bytecode metadata, decompile_agent_class for one parent or installed-mod class, decompile_agent_api_class for game/BaseMod/StSLib/dependency classes (pass several names at once; large classes return a member index plus a skeleton, and 'member' reads one body), and read_agent_mod_resource for one resource. patch_source/ is the agent-owned patch tree and is the only directory writable or deletable through workspace tools.
        To find something in the workspace, locate it before reading: use grep_agent_workspace to search file contents by regular expression and glob_agent_workspace to find files by pattern like '**/*.java'. Fall back to list_agent_workspace only when you do not know what to look for; it is capped and lists paths only.
        A workspace file that is a PNG, JPEG, WebP or GIF is returned as an image you can see, not as text. Read it with read_agent_workspace_file (omit encoding) and describe what is actually shown before acting on it; never claim to have seen an image you did not read. The same applies to image resources opened with read_agent_mod_resource.
        The current active patch ID, when available, is supplied in launcher state update messages. The latest update takes precedence over older state. Inspect existing files and patch IDs in conversation memory before creating a workspace.
        Do not create a workspace unless the user asked for a new patch and no existing patch workspace should be reused. create_agent_patch_workspace prepares a dedicated patch_source/<patch_id>/ directory and returns the exact paths to use. It does not compile, package, install, or enable a mod.
        If the user asks to inspect or explain the parent mod, call decompile_agent_mod_source to prepare its SHA-256 snapshot, then inspect_agent_class or decompile_agent_class for the needed classes. Do not expect source/ to contain every parent file.
        To read another installed mod (including disabled mods), call list_installed_mods, then pass its mod_id to list_agent_mod_entries, inspect_agent_class, decompile_agent_class, or read_agent_mod_resource. Discover classes with list_agent_mod_entries(entry_type="classes", mod_id=...) rather than search_agent_api, whose classpath only covers the parent and built-in dependencies. Other mods' decompiled code is returned inline, not materialized in the current workspace. These tools are read-only with respect to installed JARs; inspecting another mod does not change the patch target or compile classpath.

        To inspect original game resources (including card art) in desktop-1.0.jar, use list_agent_mod_entries(source="game", prefix=...) to discover exact paths, then read_agent_mod_resource(source="game", entry=...). Omit mod_id for source="game"; the game is not an installed mod. Image resources are returned as viewable attachments when encoding is omitted and offset is 0. Both tools default to source="mod" for backward compatibility. Game resources are read directly from the imported game JAR, not the class-only compile cache, and cannot be modified by these tools.
        Use write_agent_workspace_file to create or replace files under the current patch_workspace_path returned by create_agent_patch_workspace, and delete_agent_workspace_file to remove files or directories there with recursive=true when needed. Do not attempt to modify source/ or another patch mod's directory.
        The launcher creates ModTheSpire.json and agent-patch.json in the current patch_source/<patch_id>/ directory. Keep the patch mod's modid and parent dependency intact.
        ModTheSpire resolves dependencies case-sensitively: copy the exact 'modid' text from source/ModTheSpire.json when listing the parent or any dependency, and never change its casing.
        For a new patch with no suitable existing workspace, call create_agent_patch_workspace first. For any change to an existing patch, inspect or list the existing revisions and continue directly in that revision's patch_source/<patch_id>/ tree; do not create another workspace. After changing an already packaged revision, compile it with its patch_id and call update_agent_patch_mod so the same revision is replaced.
        Before writing any Java against the game, BaseMod, or StSLib, read the bundled reference with read_agent_skill (skill name: basemod-and-stslib). It covers the registration lifecycle and ordering, where each content type is registered, hook names, StSLib keywords, and the mistakes that crash the game when it loads.
        Never write an API call from memory, and never guess what a vanilla method does. Find the type with search_agent_api, confirm the exact overload with describe_agent_api_class, and when behavior matters call decompile_agent_api_class to read the installed game/BaseMod source before designing the patch. Batch independent classes into one call; when a class exceeds the inline cap, read the member index and request the one body you need instead of re-decompiling the class.
        Before writing any patch that targets a method, call inspect_agent_patch_target for that class and member. It returns the exact overloads, descriptors, fields, and by default a javap-style bytecode listing plus a structured local_variables list with LocalVariableTable names; use those names for __instance, __result, __args and localvars instead of guessing. After inspecting a target, call generate_agent_patch_skeleton to write the @SpirePatch2 class with the exact paramtypez, then fill in the TODO.
        Prefer the BaseMod API over ModTheSpire patches: content such as cards, relics, potions, events, keywords, characters, and colors is registered through BaseMod from one @SpireInitializer class. Use @SpirePatch2 only where no API exists, and never use a Replace patch.
        For code changes, write Java 8 sources under <patch_workspace_path>/src/<package>/... using ModTheSpire's @SpirePatch2 and the game/BaseMod APIs, then call compile_agent_patch_source to compile them into that patch workspace. A patch mod does not have to be a @SpirePatch2 hook set: a resource-only patch mod and a mod that registers content through a single @SpireInitializer class with a public static void initialize() are valid too.
        Compilation is fixed to Java 8 and the classpath already contains the game jar, ModTheSpire, the required mods and the parent mod; do not add a build file or a classpath argument.
        If compile_agent_patch_source reports errors, read the diagnostics and fix the sources before packaging.
        Run validate_agent_patch_mod after compiling. If it warns that a target does not record an explicit parameter signature, do not package yet: regenerate the patch class with generate_agent_patch_skeleton using the inspected overload, then compile and validate again.
        When the patch mod is complete, call package_agent_patch_mod with a useful name, semantic version, and description.
        Patch mod names and descriptions must be in Chinese, preserving proper mod names when needed. Keep names short and specific to the main feature or fix; avoid generic names such as 'AI补丁', '测试补丁', or '优化补丁' and do not include the version number. Keep descriptions brief, stating the problem or request addressed and concrete implemented changes without plans or unverified claims. Before packaging or updating, compare the description with the actual patch contents and correct it if they differ. Reuse the existing name unless the patch's purpose changed.
        To change a patch mod you already packaged, call list_agent_patch_mods to get its patch_id, edit the files under patch_source/<patch_id>/, call compile_agent_patch_source with that patch_id, then call update_agent_patch_mod with the patch_id and a new version. This replaces the same revision and keeps its enabled state; do not create a duplicate patch mod for an update.
        Packaged patch mods are stored under agent_mods and are loaded by the launcher directly; there is no separate install step.
        After packaging, call smoke_test_agent_patch_mod to launch the game with the patch, the parent mod, the parent mod's prerequisites, and the built-in mods. It succeeds only when the game reaches the main menu; on failure it returns the boot events and log excerpt. If it fails, fix the patch, recompile, update the patch mod, and run it again.
        Call list_installed_mods to see which mods are installed. When the patch is meant to interact with another optional mod, pass its mod_id in smoke_test_agent_patch_mod's mod_ids so the test enables it too; the user's own mod selection is ignored, so this is the only way to verify that combination.
        Ask before enabling a packaged patch mod unless the user explicitly requested activation. Use set_agent_patch_mod_enabled to enable or disable it.
        Use list_agent_patch_mods to check existing revisions and their enabled state before deciding whether a new workspace is necessary. Do not create a duplicate workspace for an ordinary follow-up modification.
        Use delete_agent_patch_mod with a patch_id to remove a patch revision entirely.
        Never claim that a patch mod was created, packaged, updated, enabled, disabled, or deleted until the corresponding tool confirms it.
        Ask before calling delete_agent_patch_mod unless the user explicitly requested the removal.
        The selected parent mod is '$modName' ($modId).
        Your agent workspace root is '$workspaceAccessRoot'. It contains source/ and patch_source/ for the selected mod.
    """.trimIndent()

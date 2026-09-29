package io.stamethyst.backend.mods

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import io.stamethyst.backend.launch.AgentPatchSmokeTestService
import io.stamethyst.backend.launch.GameLaunchReturnTracker
import io.stamethyst.backend.launch.MainProcessLaunchPreparationCoordinator
import io.stamethyst.backend.launch.MtsStartupCacheCoordinator
import io.stamethyst.backend.launch.StsLaunchSpec
import io.stamethyst.backend.process.AppProcess
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.RandomAccessFile

/** Outcome of running the parent mod together with one AI patch mod and waiting for the main menu. */
data class AgentPatchSmokeTestResult(
    val passed: Boolean,
    val status: String,
    val reason: String,
    val reachedMainMenu: Boolean,
    val durationMs: Long,
    val launchModIds: List<String>,
    val unresolvedDependencies: List<String>,
    val events: String,
    val logExcerpt: String,
    /** Mod ids the agent requested that the launcher does not have installed. */
    val unknownModIds: List<String> = emptyList(),
    /** True when ModTheSpire's own record confirms it loaded exactly the requested mod set. */
    val modSetVerified: Boolean = false,
    /** Jar paths ModTheSpire actually loaded, as reported by its own file-list override. */
    val loadedModJarPaths: List<String> = emptyList(),
)

/**
 * Verifies that a packaged AI patch mod does not break mod loading.
 *
 * The launcher process owns the parts that must run here — resolving the mod set and preparing the
 * MTS classpath with it — while the game itself runs in the `:game` process, driven by
 * [AgentPatchSmokeTestService]. That split is what keeps the run invisible: no Activity is ever
 * started, so the launcher keeps the foreground and the agent page the user is looking at never
 * moves.
 *
 * The mod set is deterministic and independent of the user's optional-mod selection: the parent mod,
 * the AI patch, the parent mod's prerequisites, and the launcher's built-in mods. A failure is
 * therefore attributable to the patch instead of an unrelated enabled mod.
 */
object AgentPatchSmokeTest {
    private const val TAG = "AgentSmokeTest"
    const val DEFAULT_TIMEOUT_MS = 240_000L
    private const val SERVICE_START_TIMEOUT_MS = 30_000L
    private const val GAME_EXIT_GRACE_MS = 25_000L
    private const val GAME_PROCESS_MISSING_POLLS = 12
    private const val POLL_INTERVAL_MS = 250L
    private const val EVENTS_TAIL_CHARS = 4_000
    private const val LOG_TAIL_CHARS = 6_000
    private const val LOG_TAIL_READ_CHARS = 64 * 1024

    private val CRASH_MARKERS = listOf(
        "Fatal signal",
        "signo: 11",
        "SIGSEGV",
        "SIGABRT",
        "Game crashed.",
        "Exception in thread \"LWJGL Application\"",
        "Exception occurred in CardCrawlGame render method!",
    )

    private val BUILT_IN_MOD_IDS = listOf(
        ModManager.MOD_ID_BASEMOD,
        ModManager.MOD_ID_STSLIB,
        ModManager.MOD_ID_AMETHYST_RUNTIME_COMPAT,
        ModManager.MOD_ID_AMETHYST_FLOATING_TOOLS,
        ModManager.MOD_ID_RAM_SAVER,
        ModManager.MOD_ID_AMETHYST_FRAME_PROBE,
    )

    fun run(
        context: Context,
        parentModId: String,
        parentJar: File,
        patchJar: File,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        selectedModIds: Collection<String> = emptyList(),
    ): AgentPatchSmokeTestResult {
        val startedAtMs = SystemClock.elapsedRealtime()
        val appContext = context.applicationContext
        // The MTS classpath must be prepared in the main process: it repairs desktop-1.0.jar, which
        // the `:game` process is not allowed to do.
        if (!AppProcess.isDefaultProcess(appContext)) {
            return failure(startedAtMs, "main_process_required", "The smoke test must run in the launcher process.")
        }
        if (!parentJar.isFile) {
            return failure(startedAtMs, "parent_jar_missing", "The parent mod jar is missing: ${parentJar.absolutePath}")
        }
        if (!patchJar.isFile) {
            return failure(startedAtMs, "patch_jar_missing", "The packaged patch mod jar is missing: ${patchJar.absolutePath}")
        }

        val resolved = try {
            resolveSelection(appContext, parentModId, parentJar, patchJar, selectedModIds)
        } catch (error: Throwable) {
            return failure(startedAtMs, "mod_resolution_failed", error.message ?: error.javaClass.simpleName)
        }
        val selection = resolved.selection
        val resolvedModIds = selection.filesByModId.keys.toList()
        if (selection.unknownSelectedModIds.isNotEmpty()) {
            return failure(
                startedAtMs,
                "unknown_mod_ids",
                "These mod ids are not installed, so they cannot be enabled: " +
                    selection.unknownSelectedModIds.joinToString(", ") +
                    ". Call list_installed_mods for the ids the launcher actually has.",
                unknownModIds = selection.unknownSelectedModIds,
                launchModIds = resolvedModIds,
            )
        }
        if (selection.unresolvedDependencies.isNotEmpty()) {
            return failure(
                startedAtMs,
                "unresolved_dependencies",
                "Missing prerequisites: ${selection.unresolvedDependencies.joinToString(", ")}",
                unresolvedDependencies = selection.unresolvedDependencies,
                launchModIds = resolvedModIds,
            )
        }
        // The run happens in :game, the same process a normal session uses, and one process can only
        // host one JVM. A live session must be closed before a smoke test can start.
        if (GameLaunchReturnTracker.isGameProcessRunning(appContext)) {
            return failure(
                startedAtMs,
                "game_already_running",
                "A game session is already running. Close the game and run the smoke test again.",
                launchModIds = resolvedModIds,
            )
        }

        val eventsFile = RuntimePaths.bootBridgeEventsLog(appContext)
        // A smoke test must exercise the real patch pass, and the mod list must reach the game JVM.
        // Invalidate the MTS caches so the classpath and patch caches rebuild against this mod set.
        runCatching { MtsStartupCacheCoordinator.invalidate(appContext) }
        runCatching { eventsFile.delete() }

        try {
            MainProcessLaunchPreparationCoordinator.prepareBeforeLaunch(
                context = appContext,
                launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
                progressCallback = null,
                launchSnapshotOverride = buildSnapshot(selection),
            )
        } catch (error: Throwable) {
            runCatching { MtsStartupCacheCoordinator.invalidate(appContext) }
            return failure(
                startedAtMs,
                "launch_preparation_failed",
                error.message ?: error.javaClass.simpleName,
                launchModIds = resolvedModIds,
                events = readEvents(eventsFile),
            )
        }

        val modJarPaths = selection.files.map { it.absolutePath }
        // ModTheSpire's --mods list must name each loaded mod by its raw manifest modid, and those
        // ids are validated against the loaded mods, so they come from the same resolved set.
        val launchModIds = selection.filesByModId.keys
            .map { normalized -> resolved.rawModIdByNormalizedId[normalized] ?: normalized }
            .filter { it.isNotBlank() }
        writeRunModFileList(appContext, modJarPaths)
        val auditFile = RuntimePaths.agentSmokeTestModFileListAudit(appContext)
        runCatching { auditFile.delete() }

        val runId = AgentPatchSmokeTestProtocol.newRunId()
        AgentPatchSmokeTestProtocol.writeRequest(
            appContext,
            AgentPatchSmokeTestRequest(
                runId = runId,
                parentModId = resolved.parentModId,
                patchJarPath = patchJar.absolutePath,
                timeoutMs = timeoutMs,
                modJarPaths = modJarPaths,
                launchModIds = launchModIds,
            ),
        )
        AgentPatchSmokeTestProtocol.clearResult(appContext)

        val outcome = try {
            driveService(appContext, runId, timeoutMs)
        } finally {
            AgentPatchSmokeTestProtocol.clearRequest(appContext)
            // The verdict is written before the game JVM shuts down, and the JVM exit trap consults
            // the run marker to decide whether to restart the launcher. The marker must therefore
            // outlive the game process, not just the service binding.
            awaitGameProcessExit(appContext)
            AgentPatchSmokeTestProtocol.clearRunActive(appContext)
            runCatching { MtsStartupCacheCoordinator.invalidate(appContext) }
        }

        if (outcome == null) {
            // A native crash in :game kills the process before the service can write a verdict, so
            // fall back to ModTheSpire's own mod-list audit, the boot events, and the game log
            // instead of reporting nothing useful.
            val events = readEvents(eventsFile)
            val excerpt = logExcerpt(appContext)
            val loaded = readAuditedModJarPaths(appContext)
            val context = describeLastProgress(events, excerpt)
            return failure(
                startedAtMs,
                "game_process_did_not_report",
                "The game process stopped before reporting a result" +
                    context?.let { " (last progress: $it)" }.orEmpty() +
                    ". It most likely crashed natively, which leaves no JVM-level verdict; " +
                    "the loaded_mod_jars, boot events, and log excerpt show how far it got.",
                launchModIds = resolvedModIds,
                events = events,
                logExcerpt = excerpt,
                modSetVerified = matchesRequestedMods(loaded, modJarPaths),
                loadedModJarPaths = loaded,
            )
        }
        return AgentPatchSmokeTestResult(
            passed = outcome.passed,
            status = outcome.status,
            reason = outcome.reason,
            reachedMainMenu = outcome.reachedMainMenu,
            durationMs = outcome.durationMs,
            launchModIds = resolvedModIds,
            unresolvedDependencies = emptyList(),
            events = readEvents(eventsFile),
            logExcerpt = logExcerpt(appContext),
            modSetVerified = outcome.modSetVerified,
            loadedModJarPaths = outcome.loadedModJarPaths,
        )
    }

    /**
     * Binds the `:game` process service and waits for its verdict.
     *
     * The binding is what keeps the game process at a visible importance for the whole run: the
     * client here is a foreground Activity, and the service is bound rather than started, so no
     * foreground-service notification is ever shown.
     */
    private fun driveService(
        context: Context,
        runId: String,
        timeoutMs: Long,
    ): AgentPatchSmokeTestOutcome? {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bound = runCatching {
            context.bindService(
                Intent(context, AgentPatchSmokeTestService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!bound) {
            return null
        }
        try {
            val bindStartedAtMs = SystemClock.elapsedRealtime()
            val hardDeadlineMs = bindStartedAtMs + timeoutMs + SERVICE_START_TIMEOUT_MS * 2
            var runObserved = false
            var processMissingPolls = 0
            while (SystemClock.elapsedRealtime() < hardDeadlineMs) {
                AgentPatchSmokeTestProtocol.readOutcome(context, runId)?.let { return it }
                if (AgentPatchSmokeTestProtocol.isRunActive(context)) {
                    runObserved = true
                } else if (!runObserved &&
                    SystemClock.elapsedRealtime() - bindStartedAtMs > SERVICE_START_TIMEOUT_MS
                ) {
                    // The service never started its run; no verdict is coming.
                    return null
                }
                // The game JVM lives in :game, so a dead process means the service died with it and
                // no verdict will ever be written. Report that instead of waiting out the deadline.
                // includeCached: the smoke session has no Activity, so Android reports its process as
                // cached. Treating that as "gone" would abandon a run that is still working.
                if (runObserved && !GameLaunchReturnTracker.isGameProcessRunning(context, includeCached = true)) {
                    processMissingPolls += 1
                    if (processMissingPolls >= GAME_PROCESS_MISSING_POLLS) {
                        return AgentPatchSmokeTestProtocol.readOutcome(context, runId)
                    }
                } else {
                    processMissingPolls = 0
                }
                sleepQuietly(POLL_INTERVAL_MS)
            }
            return AgentPatchSmokeTestProtocol.readOutcome(context, runId)
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    /**
     * Writes the run-scoped MTS mod list.
     *
     * The game JVM is told to read this file through `amethyst.mts.mod_file_list`, so the shared
     * `.mts_mod_file_list` — which other launcher components rebuild from the user's enabled-mod
     * selection — can no longer change which mods this run loads.
     */
    private fun writeRunModFileList(context: Context, modJarPaths: List<String>) {
        val file = RuntimePaths.agentSmokeTestModFileList(context)
        file.parentFile?.mkdirs()
        file.writeText(modJarPaths.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
    }

    private data class ResolvedSelection(
        val parentModId: String,
        val selection: AgentPatchSmokeModSelection,
        /**
         * Normalized mod id to the raw manifest `modid`.
         *
         * ModTheSpire resolves its `--mods` ids case-sensitively against the manifest text, so the
         * original casing has to be preserved rather than the normalized form used internally.
         */
        val rawModIdByNormalizedId: Map<String, String>,
    )

    /**
     * Waits for the `:game` process to disappear.
     *
     * The service writes its verdict before it shuts the JVM down, so the launcher can observe a
     * finished run while the game is still closing. Unbinding (and hence destroying the service)
     * during that window would clear the run marker early and let the JVM exit trap restart the
     * launcher, destroying the AI editor and cancelling the tool call.
     */
    private fun awaitGameProcessExit(context: Context) {
        val deadlineMs = SystemClock.elapsedRealtime() + GAME_EXIT_GRACE_MS
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            if (!GameLaunchReturnTracker.isGameProcessRunning(context, includeCached = true)) {
                return
            }
            sleepQuietly(POLL_INTERVAL_MS)
        }
        // The service could not stop the game in time; make sure no session outlives the run.
        GameLaunchReturnTracker.terminateTrackedGameProcessAndWait(context)
    }

    private fun resolveSelection(
        context: Context,
        parentModId: String,
        parentJar: File,
        patchJar: File,
        selectedModIds: Collection<String>,
    ): ResolvedSelection {
        val installed = ModManager.listInstalledMods(context)
        val jarByModId = LinkedHashMap<String, File>()
        val dependenciesByModId = LinkedHashMap<String, List<String>>()
        val rawModIdByNormalizedId = LinkedHashMap<String, String>()
        installed.forEach { mod ->
            val normalizedModId = ModManager.normalizeModId(mod.modId)
            val normalizedManifestId = ModManager.normalizeModId(mod.manifestModId)
            val rawModId = mod.manifestModId.trim().ifEmpty { mod.modId.trim() }
            if (mod.installed && mod.jarFile.isFile) {
                if (normalizedModId.isNotEmpty()) jarByModId.putIfAbsent(normalizedModId, mod.jarFile)
                if (normalizedManifestId.isNotEmpty()) jarByModId.putIfAbsent(normalizedManifestId, mod.jarFile)
            }
            if (normalizedModId.isNotEmpty()) {
                dependenciesByModId[normalizedModId] = mod.dependencies
                if (rawModId.isNotEmpty()) rawModIdByNormalizedId[normalizedModId] = rawModId
            }
            if (normalizedManifestId.isNotEmpty()) {
                dependenciesByModId.putIfAbsent(normalizedManifestId, mod.dependencies)
                if (rawModId.isNotEmpty()) rawModIdByNormalizedId.putIfAbsent(normalizedManifestId, rawModId)
            }
        }
        val parentManifest = ModJarManifestParser.readModManifest(parentJar)
        val parentManifestId = ModManager.normalizeModId(parentManifest.modId)
        dependenciesByModId[parentManifestId] = parentManifest.dependencies
        jarByModId[parentManifestId] = parentJar
        parentManifest.modId.trim().takeIf { it.isNotEmpty() }?.let {
            rawModIdByNormalizedId[parentManifestId] = it
        }
        val resolvedParentModId = parentManifestId.ifEmpty { ModManager.normalizeModId(parentModId) }

        val patchManifest = runCatching { ModJarManifestParser.readModManifest(patchJar) }.getOrNull()
        val patchManifestId = ModManager.normalizeModId(patchManifest?.modId)
        patchManifest?.modId?.trim()?.takeIf { it.isNotEmpty() && patchManifestId.isNotEmpty() }?.let {
            rawModIdByNormalizedId[patchManifestId] = it
        }

        // Built-in mods come from the installed-mod list so a missing optional library entry can
        // never break resolution; a required jar that is not installed is simply left out.
        val builtInJars = LinkedHashMap<String, File>()
        val presentRequiredIds = LinkedHashSet<String>()
        BUILT_IN_MOD_IDS.forEach { modId ->
            val jar = jarByModId[modId] ?: return@forEach
            builtInJars[modId] = jar
            presentRequiredIds.add(modId)
        }
        return ResolvedSelection(
            parentModId = resolvedParentModId,
            rawModIdByNormalizedId = rawModIdByNormalizedId,
            selection = AgentPatchSmokeTestModList.resolve(
                builtInJars = builtInJars,
                requiredModIds = presentRequiredIds,
                rootModIds = listOf(resolvedParentModId, patchManifestId),
                rootJars = linkedMapOf(
                    resolvedParentModId to parentJar,
                    patchManifestId.ifEmpty { "amethyst.ai.patch" } to patchJar,
                ),
                dependenciesByModId = dependenciesByModId,
                jarByModId = jarByModId,
                selectedModIds = selectedModIds,
            ),
        )
    }

    private fun buildSnapshot(selection: AgentPatchSmokeModSelection): ModManager.LaunchModSnapshot {
        val requiredIds = LinkedHashSet<String>(BUILT_IN_MOD_IDS)
        val filesByModId = LinkedHashMap(selection.filesByModId)
        val enabledOptionalModIds = filesByModId.keys
            .filterNot { it in requiredIds }
            .toCollection(LinkedHashSet())
        return ModManager.LaunchModSnapshot(
            emptyList(),
            filesByModId.values.toList(),
            filesByModId.keys.toList(),
            enabledOptionalModIds,
            filesByModId,
        )
    }

    /** Reads back what ModTheSpire actually loaded, written by its own file-list override. */
    private fun readAuditedModJarPaths(context: Context): List<String> {
        val auditFile = RuntimePaths.agentSmokeTestModFileListAudit(context)
        return runCatching {
            if (!auditFile.isFile) {
                emptyList()
            } else {
                auditFile.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
            }
        }.getOrDefault(emptyList())
    }

    /** Compares by canonical path and order, since launch order is part of the mod set. */
    private fun matchesRequestedMods(loaded: List<String>, requested: List<String>): Boolean {
        if (loaded.isEmpty() || loaded.size != requested.size) {
            return false
        }
        return loaded.map(::canonicalPathOrSelf) == requested.map(::canonicalPathOrSelf)
    }

    private fun canonicalPathOrSelf(path: String): String =
        runCatching { File(path).canonicalPath }.getOrDefault(path)

    /**
     * Summarises how far the run got, for a crash that left no JVM-level report.
     *
     * The last boot-bridge phase is the most useful clue: a native fatal signal is only visible in
     * logcat, which the launcher cannot read, so the phase and the last log line stand in for it.
     */
    private fun describeLastProgress(events: String, logExcerptText: String): String? {
        val lastPhase = events.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("PHASE", ignoreCase = true) || it.startsWith("FAIL", ignoreCase = true) }
            .map { line ->
                val parts = line.split('\t', limit = 3)
                parts.getOrNull(2)?.trim().orEmpty().removePrefix("@amethyst.startup/")
            }
            .filter { it.isNotEmpty() }
            .lastOrNull()
        val crash = CRASH_MARKERS.firstOrNull { logExcerptText.contains(it, ignoreCase = true) }
        val lastLogLine = logExcerptText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .lastOrNull()
        return listOfNotNull(
            lastPhase?.let { "phase=$it" },
            crash?.let { "marker=$it" },
            lastLogLine?.take(120),
        ).joinToString(", ").ifBlank { null }
    }

    private fun readEvents(eventsFile: File): String =
        runCatching { if (eventsFile.isFile) eventsFile.readText(Charsets.UTF_8) else "" }
            .getOrDefault("")
            .takeLast(EVENTS_TAIL_CHARS)

    private fun logExcerpt(context: Context): String = readTail(context, LOG_TAIL_READ_CHARS)
        .takeLast(LOG_TAIL_CHARS)

    private fun readTail(context: Context, maxChars: Int): String {
        val logFile = RuntimePaths.latestLog(context)
        if (!logFile.isFile) {
            return ""
        }
        return runCatching {
            RandomAccessFile(logFile, "r").use { raf ->
                val length = raf.length()
                val start = (length - maxChars).coerceAtLeast(0L)
                raf.seek(start)
                val buffer = ByteArray((length - start).toInt())
                raf.readFully(buffer)
                String(buffer, Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    private fun failure(
        startedAtMs: Long,
        status: String,
        reason: String,
        unresolvedDependencies: List<String> = emptyList(),
        unknownModIds: List<String> = emptyList(),
        launchModIds: List<String> = emptyList(),
        events: String = "",
        logExcerpt: String = "",
        modSetVerified: Boolean = false,
        loadedModJarPaths: List<String> = emptyList(),
    ): AgentPatchSmokeTestResult {
        Log.i(TAG, "smoke status=$status reason=$reason")
        return AgentPatchSmokeTestResult(
            passed = false,
            status = status,
            reason = reason,
            reachedMainMenu = false,
            durationMs = SystemClock.elapsedRealtime() - startedAtMs,
            launchModIds = launchModIds,
            unresolvedDependencies = unresolvedDependencies,
            unknownModIds = unknownModIds,
            events = events,
            logExcerpt = logExcerpt,
            modSetVerified = modSetVerified,
            loadedModJarPaths = loadedModJarPaths,
        )
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

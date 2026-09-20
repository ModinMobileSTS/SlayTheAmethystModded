package io.stamethyst.backend.mods

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.stamethyst.StsGameActivity
import io.stamethyst.backend.crash.LatestLogCrashDetector
import io.stamethyst.backend.launch.ExpectedGameExitNotice
import io.stamethyst.backend.launch.GameLaunchReturnTracker
import io.stamethyst.backend.launch.MainProcessLaunchPreparationCoordinator
import io.stamethyst.backend.launch.MtsStartupCacheCoordinator
import io.stamethyst.backend.launch.StsLaunchSpec
import io.stamethyst.backend.presence.GamePresenceStateMarker
import io.stamethyst.backend.process.AppProcess
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/** Outcome of launching the parent mod together with one AI patch mod and waiting for the main menu. */
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
)

/**
 * Launches the game with a fixed mod set and reports whether the main menu was reached.
 *
 * The mod set is deterministic and independent of the user's optional-mod selection: the parent mod,
 * the AI patch, the parent mod's prerequisites, and the launcher's built-in mods. The run prepares
 * the MTS classpath with that snapshot, starts [StsGameActivity] with a bounded timeout, watches the
 * boot bridge terminal event (`READY`/`FAIL`), then closes the game again.
 *
 * Android cannot run the GL game headless: this launches the real game Activity. It is "silent" only
 * in the sense that it needs no user interaction and closes itself as soon as the verdict is known.
 */
object AgentPatchSmokeTest {
    private const val TAG = "AgentSmokeTest"
    const val DEFAULT_TIMEOUT_MS = 240_000L
    private const val SETTLE_AFTER_READY_MS = 2_500L
    private const val PROCESS_APPEAR_GRACE_MS = 30_000L
    private const val POLL_INTERVAL_MS = 250L
    private const val MAX_EVENTS_CHARS = 4_000
    private const val MAX_LOG_EXCERPT_CHARS = 6_000
    private const val TAIL_READ_CHARS = 64 * 1024

    private val BUILT_IN_MOD_IDS = listOf(
        ModManager.MOD_ID_BASEMOD,
        ModManager.MOD_ID_STSLIB,
        ModManager.MOD_ID_AMETHYST_RUNTIME_COMPAT,
        ModManager.MOD_ID_AMETHYST_FLOATING_TOOLS,
        ModManager.MOD_ID_RAM_SAVER,
        ModManager.MOD_ID_AMETHYST_FRAME_PROBE,
    )

    private val STRONG_CRASH_MARKERS = listOf(
        "Game crashed.",
        "Exception occurred in CardCrawlGame render method!",
        "Exception in thread \"LWJGL Application\"",
    )

    fun run(
        context: Context,
        parentModId: String,
        parentJar: File,
        patchJar: File,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): AgentPatchSmokeTestResult {
        val startedAtMs = SystemClock.elapsedRealtime()
        val appContext = context.applicationContext
        if (!AppProcess.isDefaultProcess(appContext)) {
            return failure(startedAtMs, "main_process_required", "The smoke test must run in the launcher process.")
        }
        if (!parentJar.isFile) {
            return failure(startedAtMs, "parent_jar_missing", "The parent mod jar is missing: ${parentJar.absolutePath}")
        }
        if (!patchJar.isFile) {
            return failure(startedAtMs, "patch_jar_missing", "The packaged patch mod jar is missing: ${patchJar.absolutePath}")
        }

        val selection = try {
            resolveSelection(appContext, parentModId, parentJar, patchJar)
        } catch (error: Throwable) {
            return failure(startedAtMs, "mod_resolution_failed", error.message ?: error.javaClass.simpleName)
        }
        if (selection.unresolvedDependencies.isNotEmpty()) {
            return failure(
                startedAtMs,
                "unresolved_dependencies",
                "Missing prerequisites for the parent mod: ${selection.unresolvedDependencies.joinToString(", ")}",
                unresolvedDependencies = selection.unresolvedDependencies,
                launchModIds = selection.filesByModId.keys.toList(),
            )
        }

        val snapshot = buildSnapshot(selection)
        val eventsFile = RuntimePaths.bootBridgeEventsLog(appContext)
        val logFile = RuntimePaths.latestLog(appContext)
        val preLaunchLogLength = logFile.takeIf(File::isFile)?.length() ?: 0L

        // A smoke test must exercise the real patch pass, and the mod list must reach the game JVM.
        // Invalidate the MTS caches so the classpath and patch caches rebuild against this mod set.
        runCatching { MtsStartupCacheCoordinator.invalidate(appContext) }
        runCatching { eventsFile.delete() }
        GameLaunchReturnTracker.markGameLaunchStarted(appContext)
        ExpectedGameExitNotice.clearExpectedGameExit(appContext)

        try {
            MainProcessLaunchPreparationCoordinator.prepareBeforeLaunch(
                context = appContext,
                launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
                progressCallback = null,
                launchSnapshotOverride = snapshot,
            )
        } catch (error: Throwable) {
            GameLaunchReturnTracker.clearPendingGameLaunch(appContext)
            runCatching { MtsStartupCacheCoordinator.invalidate(appContext) }
            return failure(
                startedAtMs,
                "launch_preparation_failed",
                error.message ?: error.javaClass.simpleName,
                launchModIds = selection.filesByModId.keys.toList(),
            )
        }

        val launchError = runCatching {
            StsGameActivity.launch(
                context = appContext,
                launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
                backBehavior = BackBehavior.EXIT_TO_LAUNCHER,
                manualDismissBootOverlay = false,
                debugMode = false,
            )
        }.exceptionOrNull()
        if (launchError != null) {
            GameLaunchReturnTracker.clearPendingGameLaunch(appContext)
            runCatching { MtsStartupCacheCoordinator.invalidate(appContext) }
            return failure(
                startedAtMs,
                "game_launch_failed",
                launchError.message ?: launchError.javaClass.simpleName,
                launchModIds = selection.filesByModId.keys.toList(),
            )
        }

        val outcome = awaitOutcome(
            context = appContext,
            eventsFile = eventsFile,
            logFile = logFile,
            preLaunchLogLength = preLaunchLogLength,
            startedAtMs = startedAtMs,
            timeoutMs = timeoutMs.coerceAtLeast(15_000L),
        )
        closeGame(appContext)
        runCatching { MtsStartupCacheCoordinator.invalidate(appContext) }

        return AgentPatchSmokeTestResult(
            passed = outcome.passed,
            status = if (outcome.passed) "passed" else "failed",
            reason = outcome.reason,
            reachedMainMenu = outcome.reachedMainMenu,
            durationMs = SystemClock.elapsedRealtime() - startedAtMs,
            launchModIds = selection.filesByModId.keys.toList(),
            unresolvedDependencies = selection.unresolvedDependencies,
            events = outcome.events,
            logExcerpt = outcome.logExcerpt,
        )
    }

    private fun resolveSelection(
        context: Context,
        parentModId: String,
        parentJar: File,
        patchJar: File,
    ): AgentPatchSmokeModSelection {
        val installed = ModManager.listInstalledMods(context)
        val jarByModId = LinkedHashMap<String, File>()
        val dependenciesByModId = LinkedHashMap<String, List<String>>()
        installed.forEach { mod ->
            val normalizedModId = ModManager.normalizeModId(mod.modId)
            val normalizedManifestId = ModManager.normalizeModId(mod.manifestModId)
            if (mod.installed && mod.jarFile.isFile) {
                if (normalizedModId.isNotEmpty()) jarByModId.putIfAbsent(normalizedModId, mod.jarFile)
                if (normalizedManifestId.isNotEmpty()) jarByModId.putIfAbsent(normalizedManifestId, mod.jarFile)
            }
            if (normalizedModId.isNotEmpty()) {
                dependenciesByModId[normalizedModId] = mod.dependencies
            }
            if (normalizedManifestId.isNotEmpty()) {
                dependenciesByModId.putIfAbsent(normalizedManifestId, mod.dependencies)
            }
        }
        val parentManifest = ModJarManifestParser.readModManifest(parentJar)
        val parentManifestId = ModManager.normalizeModId(parentManifest.modId)
        dependenciesByModId[parentManifestId] = parentManifest.dependencies
        jarByModId[parentManifestId] = parentJar
        val resolvedParentModId = parentManifestId.ifEmpty { ModManager.normalizeModId(parentModId) }

        val patchManifestId = runCatching {
            ModManager.normalizeModId(ModJarManifestParser.readModManifest(patchJar).modId)
        }.getOrDefault("")

        // Built-in mods come from the installed-mod list so a missing optional library entry can
        // never break resolution; a required jar that is not installed is simply left out.
        val builtInJars = LinkedHashMap<String, File>()
        val presentRequiredIds = LinkedHashSet<String>()
        BUILT_IN_MOD_IDS.forEach { modId ->
            val jar = jarByModId[modId] ?: return@forEach
            builtInJars[modId] = jar
            presentRequiredIds.add(modId)
        }
        return AgentPatchSmokeTestModList.resolve(
            builtInJars = builtInJars,
            requiredModIds = presentRequiredIds,
            rootModIds = listOf(resolvedParentModId, patchManifestId),
            explicitJars = linkedMapOf(
                resolvedParentModId to parentJar,
                patchManifestId.ifEmpty { "amethyst.ai.patch" } to patchJar,
            ),
            dependenciesByModId = dependenciesByModId,
            jarByModId = jarByModId,
        )
    }

    private fun buildSnapshot(
        selection: AgentPatchSmokeModSelection,
    ): ModManager.LaunchModSnapshot {
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

    private data class Outcome(
        val passed: Boolean,
        val reason: String,
        val reachedMainMenu: Boolean,
        val events: String,
        val logExcerpt: String,
    )

    private fun awaitOutcome(
        context: Context,
        eventsFile: File,
        logFile: File,
        preLaunchLogLength: Long,
        startedAtMs: Long,
        timeoutMs: Long,
    ): Outcome {
        val deadlineMs = startedAtMs + timeoutMs
        var processSeen = false
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            val events = readEvents(eventsFile)
            val failMessage = terminalMessage(events, "FAIL")
            if (failMessage != null) {
                return Outcome(
                    passed = false,
                    reason = "boot_failure: $failMessage",
                    reachedMainMenu = false,
                    events = events,
                    logExcerpt = logExcerpt(logFile),
                )
            }
            val readyMessage = terminalMessage(events, "READY")
            if (readyMessage != null) {
                sleepQuietly(SETTLE_AFTER_READY_MS)
                val crashAfterReady = crashMarker(logFile, preLaunchLogLength)
                if (crashAfterReady != null) {
                    return Outcome(
                        passed = false,
                        reason = "crash_after_main_menu: $crashAfterReady",
                        reachedMainMenu = true,
                        events = readEvents(eventsFile),
                        logExcerpt = logExcerpt(logFile),
                    )
                }
                if (!GameLaunchReturnTracker.isGameProcessRunning(context)) {
                    return Outcome(
                        passed = false,
                        reason = "game_process_exited_after_main_menu",
                        reachedMainMenu = true,
                        events = readEvents(eventsFile),
                        logExcerpt = logExcerpt(logFile),
                    )
                }
                return Outcome(
                    passed = true,
                    reason = "main_menu_reached",
                    reachedMainMenu = true,
                    events = readEvents(eventsFile),
                    logExcerpt = logExcerpt(logFile),
                )
            }
            if (GameLaunchReturnTracker.isGameProcessRunning(context)) {
                processSeen = true
            } else if (processSeen) {
                val crash = crashMarker(logFile, preLaunchLogLength)
                return Outcome(
                    passed = false,
                    reason = crash?.let { "game_process_exited: $it" } ?: "game_process_exited_before_main_menu",
                    reachedMainMenu = false,
                    events = events,
                    logExcerpt = logExcerpt(logFile),
                )
            } else if (SystemClock.elapsedRealtime() - startedAtMs > PROCESS_APPEAR_GRACE_MS) {
                return Outcome(
                    passed = false,
                    reason = "game_process_never_started",
                    reachedMainMenu = false,
                    events = events,
                    logExcerpt = logExcerpt(logFile),
                )
            }
            sleepQuietly(POLL_INTERVAL_MS)
        }
        val crash = crashMarker(logFile, preLaunchLogLength)
        return Outcome(
            passed = false,
            reason = crash?.let { "timeout: $it" } ?: "timeout_waiting_for_main_menu",
            reachedMainMenu = false,
            events = readEvents(eventsFile),
            logExcerpt = logExcerpt(logFile),
        )
    }

    /**
     * Stops the game again without disturbing the launcher.
     *
     * The `.harness_exit_request` route is deliberately not used: it ends with
     * `LauncherReturnCoordinator.createReturnIntent`, which restarts `LauncherActivity` with
     * `FLAG_ACTIVITY_CLEAR_TASK` and would destroy the AI editor and cancel this very tool call.
     * Instead the pending-launch marker is cleared first, so the launcher's return analysis stays
     * inert and simply resumes the editor when the game process disappears.
     */
    private fun closeGame(context: Context) {
        GameLaunchReturnTracker.clearPendingGameLaunch(context)
        ExpectedGameExitNotice.clearExpectedGameExit(context)
        GameLaunchReturnTracker.terminateTrackedGameProcessAndWait(context)
        GamePresenceStateMarker.markLauncherActive(context)
    }

    private fun terminalMessage(events: String, type: String): String? {
        events.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val parts = line.split('\t', limit = 3)
                if (parts[0].trim().equals(type, ignoreCase = true)) {
                    return parts.getOrNull(2)?.trim().orEmpty().ifBlank { type }
                }
            }
        return null
    }

    private fun readEvents(eventsFile: File): String =
        runCatching {
            if (!eventsFile.isFile) "" else eventsFile.readText(StandardCharsets.UTF_8)
        }.getOrDefault("").takeLast(MAX_EVENTS_CHARS)

    /**
     * Looks for a crash marker in the part of `latest.log` written by this launch.
     *
     * The game JVM truncates `latest.log` when it starts, so a shorter file than the pre-launch
     * length means the whole file is new content and the pre-launch offset must be discarded.
     */
    private fun crashMarker(logFile: File, fromOffset: Long): String? {
        val currentLength = runCatching { logFile.length() }.getOrDefault(0L)
        if (currentLength <= 0L) {
            return null
        }
        val newContentStart = if (currentLength < fromOffset) 0L else fromOffset
        val start = maxOf(newContentStart, currentLength - TAIL_READ_CHARS)
        val appended = readTextFrom(logFile, start, TAIL_READ_CHARS)
        if (appended.isBlank()) {
            return null
        }
        val marker = STRONG_CRASH_MARKERS.firstOrNull { appended.contains(it, ignoreCase = true) }
        if (marker != null) {
            return marker
        }
        // The detector scans a wider tail; only accept its verdict when its own marker also
        // appears in the content this launch wrote, so a pre-launch crash cannot be misreported.
        val summary = runCatching { LatestLogCrashDetector.detect(logFile) }.getOrNull()
        return summary?.takeIf { appended.contains(it.marker, ignoreCase = true) }?.detail
    }

    private fun logExcerpt(logFile: File): String {
        val length = runCatching { logFile.length() }.getOrDefault(0L)
        if (length <= 0L) {
            return ""
        }
        return readTextFrom(logFile, (length - TAIL_READ_CHARS).coerceAtLeast(0L), TAIL_READ_CHARS)
            .takeLast(MAX_LOG_EXCERPT_CHARS)
    }

    private fun readTextFrom(file: File, offset: Long, maxChars: Int): String {
        if (!file.isFile) {
            return ""
        }
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val safeOffset = offset.coerceIn(0L, raf.length())
                raf.seek(safeOffset)
                val available = (raf.length() - safeOffset).coerceAtMost(maxChars.toLong()).toInt()
                if (available <= 0) {
                    return@use ""
                }
                val buffer = ByteArray(available)
                raf.readFully(buffer)
                String(buffer, StandardCharsets.UTF_8)
            }
        }.getOrDefault("")
    }

    private fun failure(
        startedAtMs: Long,
        status: String,
        reason: String,
        unresolvedDependencies: List<String> = emptyList(),
        launchModIds: List<String> = emptyList(),
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
            events = "",
            logExcerpt = "",
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

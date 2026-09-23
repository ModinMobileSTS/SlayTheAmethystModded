package io.stamethyst.backend.launch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import io.stamethyst.backend.crash.LatestLogCrashDetector
import io.stamethyst.backend.mods.AgentPatchSmokeTestOutcome
import io.stamethyst.backend.mods.AgentPatchSmokeTestProtocol
import io.stamethyst.backend.mods.AgentPatchSmokeTestRequest
import io.stamethyst.backend.presence.GamePresenceStateMarker
import io.stamethyst.backend.render.DisplayConfigSync
import io.stamethyst.backend.render.RendererBackendResolver
import io.stamethyst.backend.runtime.RuntimePackInstaller
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import net.kdt.pojavlaunch.LwjglGlfwKeycode
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Runs one AI patch smoke test in the `:game` process, with no UI of any kind.
 *
 * The launcher process cannot do this itself: the game JVM is loaded into the `:game` process, and
 * starting an Activity to get a window would take the foreground away from the agent page. This
 * service instead renders into a [HeadlessGameSurface] — a standalone `ImageReader` surface that
 * belongs to no display — so the game runs a completely normal session while the user's screen never
 * changes.
 *
 * The service is bound (never started) from the launcher: a bound client that is a foreground
 * Activity keeps this process at a visible importance without a foreground-service notification.
 */
class AgentPatchSmokeTestService : Service() {
    private val binder = LocalBinder()

    @Volatile
    private var runStarted = false

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var cancelRequested = false

    private var launchGuardAcquired = false
    private var headlessSurface: HeadlessGameSurface? = null
    private var jvmLaunchController: JvmLaunchController? = null

    /** Set once `launchJVM` has returned, so shutdown knows the game JVM is really gone. */
    private val jvmExited = AtomicBoolean(false)

    /**
     * Keeps the CPU running for the length of the run.
     *
     * The session has no window, so it cannot rely on FLAG_KEEP_SCREEN_ON the way the normal game
     * Activity does. Without this the run can be suspended mid-patch on an idle device.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun isRunning(): Boolean = runStarted && !cancelRequested
    }

    override fun onBind(intent: Intent?): IBinder {
        if (!runStarted) {
            runStarted = true
            startWorker()
        }
        return binder
    }

    /**
     * The launcher unbinds as soon as it has read the verdict, which is before the game JVM has
     * finished exiting. Teardown therefore only asks the run to stop; the worker thread owns the
     * JVM and the run marker, because the JVM exit trap consults the marker while shutting down.
     */
    override fun onDestroy() {
        cancelRequested = true
        super.onDestroy()
    }

    private fun startWorker() {
        val thread = Thread({ runCatching { executeRun() }.onFailure { error ->
            Log.e(TAG, "Smoke test run failed", error)
            AgentPatchSmokeTestProtocol.readRequest(this)?.let { request ->
                writeOutcome(
                    AgentPatchSmokeTestOutcome(
                        runId = request.runId,
                        passed = false,
                        status = "run_failed",
                        reason = error.message ?: error.javaClass.simpleName,
                        reachedMainMenu = false,
                        durationMs = 0L,
                        error = error.javaClass.simpleName,
                    ),
                )
            }
            releaseResources()
        } }, "AgentSmokeTestRun")
        worker = thread
        thread.start()
    }

    private fun executeRun(): Unit {
        val startedAtMs = SystemClock.elapsedRealtime()
        val request = AgentPatchSmokeTestProtocol.readRequest(this)
        if (request == null) {
            writeOutcome(
                AgentPatchSmokeTestOutcome(
                    runId = "",
                    passed = false,
                    status = "request_missing",
                    reason = "No smoke test request was found in the shared hand-off directory.",
                    reachedMainMenu = false,
                    durationMs = SystemClock.elapsedRealtime() - startedAtMs,
                ),
            )
            return
        }
        AgentPatchSmokeTestProtocol.markRunActive(this)
        acquireWakeLock(request.timeoutMs)

        if (!GameProcessLaunchGuard.tryAcquire(LAUNCH_GUARD_TOKEN)) {
            finish(
                request = request,
                startedAtMs = startedAtMs,
                passed = false,
                status = "game_already_running",
                reason = "Another game session is already active. Close it and run the smoke test again.",
                reachedMainMenu = false,
            )
            return
        }
        launchGuardAcquired = true

        val patchJar = File(request.patchJarPath)
        if (!patchJar.isFile) {
            finish(
                request = request,
                startedAtMs = startedAtMs,
                passed = false,
                status = "patch_jar_missing",
                reason = "The packaged patch mod jar is missing: ${request.patchJarPath}",
                reachedMainMenu = false,
            )
            return
        }

        val modFileList = RuntimePaths.agentSmokeTestModFileList(this)
        val modFileListAudit = RuntimePaths.agentSmokeTestModFileListAudit(this)
        if (request.modJarPaths.isEmpty() || request.launchModIds.isEmpty() || !modFileList.isFile) {
            finish(
                request = request,
                startedAtMs = startedAtMs,
                passed = false,
                status = "mod_file_list_missing",
                reason = "The smoke test did not receive a run-scoped mod list to launch with.",
                reachedMainMenu = false,
            )
            return
        }

        val size = resolveSurfaceSize()
        val surface = HeadlessGameSurface.create(size.first, size.second)
        if (surface == null) {
            finish(
                request = request,
                startedAtMs = startedAtMs,
                passed = false,
                status = "headless_surface_unavailable",
                reason = "Could not create the offscreen render target used to run the game invisibly.",
                reachedMainMenu = false,
            )
            return
        }
        headlessSurface = surface

        val eventsFile = RuntimePaths.bootBridgeEventsLog(this)
        val logFile = RuntimePaths.latestLog(this)
        val preLaunchLogLength = logFile.takeIf(File::isFile)?.length() ?: 0L
        runCatching { eventsFile.delete() }
        runCatching { modFileListAudit.delete() }

        val rendererDecision = RendererBackendResolver.resolve(
            context = this,
            requestedSurfaceBackend = LauncherConfig.readRenderSurfaceBackend(this),
            selectionMode = LauncherConfig.readRendererSelectionMode(this),
            manualBackend = LauncherConfig.readManualRendererBackend(this),
        )
        val renderScale = LauncherConfig.readRenderScale(this)
        val targetFps = LauncherConfig.readTargetFpsValue(this)

        val controller = JvmLaunchController(
            context = this,
            launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
            debugMode = false,
            rendererDecision = rendererDecision,
            renderScale = renderScale,
            effectiveTargetFps = targetFps,
            forceJvmCrash = false,
            forceRuntimeCrash = false,
            autoplay = false,
            autoplaySaveMode = AutoplaySaveMode.DEFAULT,
            autoplayMode = AutoplayMode.DEFAULT,
            autoplaySingleRoomSpecPath = "",
            autoplayChoiceDelayMs = 0L,
            performanceDeepDiagnostics = false,
            cardObtainEffectOwnershipCompatEnabled = true,
            mirrorJvmLogsToLogcat = false,
            onProgressUpdate = { _, _ -> },
            onLaunchComplete = { exitCode ->
                Log.i(TAG, "Game JVM exited code=$exitCode")
                jvmExited.set(true)
            },
            onLaunchFailed = { error ->
                Log.w(TAG, "Game JVM launch failed", error)
                jvmExited.set(true)
            },
            onRuntimeCrashDetected = { detail ->
                Log.w(TAG, "Game runtime crash detected: $detail")
            },
            onRuntimeReady = {
                // Runs immediately before the JVM starts, after the native bridge is initialized.
                connectBridgeWindow(surface)
                applyHeadlessWindowState()
            },
            onSurfaceSizeSync = { syncSurfaceSize(surface) },
            getWindowWidth = { surface.width },
            getWindowHeight = { surface.height },
            mtsModFileListOverride = modFileList,
            mtsModFileListAudit = modFileListAudit,
            mtsLaunchModIdsOverride = request.launchModIds,
        )
        jvmLaunchController = controller

        GamePresenceStateMarker.markGameActive(this, StsLaunchSpec.LAUNCH_MODE_MTS)
        ExpectedGameExitNotice.clearExpectedGameExit(this)
        AgentPatchSmokeTestProtocol.clearResult(this)
        val runtimeRoot = RuntimePaths.runtimeRoot(this)
        val javaHome = RuntimePackInstaller.locateJavaHome(runtimeRoot) ?: File(runtimeRoot, "jre")
        controller.start(javaHome = javaHome, bootOverlayController = null)

        val outcome = awaitOutcome(
            eventsFile = eventsFile,
            logFile = logFile,
            preLaunchLogLength = preLaunchLogLength,
            startedAtMs = startedAtMs,
            timeoutMs = request.timeoutMs.coerceAtLeast(MIN_TIMEOUT_MS),
        )
        val loadedModJarPaths = readLoadedModJarPaths(modFileListAudit)
        val modSetVerified = matchesRequestedMods(loadedModJarPaths, request.modJarPaths)
        // The whole point of the smoke test is that a failure is attributable to the patch. If the
        // game did not load the requested mods, any verdict would describe a different session, so
        // an unverifiable run can never be reported as a pass.
        val passed = outcome.passed && modSetVerified
        val reason = when {
            outcome.passed && !modSetVerified && loadedModJarPaths.isEmpty() ->
                "mod_set_unverified: no record of which mods the game loaded was produced"
            outcome.passed && !modSetVerified ->
                "mod_set_mismatch: the game loaded a different mod set than requested"
            else -> outcome.reason
        }
        finish(
            request = request,
            startedAtMs = startedAtMs,
            passed = passed,
            status = if (passed) "passed" else "failed",
            reason = reason,
            reachedMainMenu = outcome.reachedMainMenu,
            loadedModJarPaths = loadedModJarPaths,
            modSetVerified = modSetVerified,
        )
    }

    private fun finish(
        request: AgentPatchSmokeTestRequest,
        startedAtMs: Long,
        passed: Boolean,
        status: String,
        reason: String,
        reachedMainMenu: Boolean,
        loadedModJarPaths: List<String> = emptyList(),
        modSetVerified: Boolean = false,
    ) {
        writeOutcome(
            AgentPatchSmokeTestOutcome(
                runId = request.runId,
                passed = passed,
                status = status,
                reason = reason,
                reachedMainMenu = reachedMainMenu,
                durationMs = SystemClock.elapsedRealtime() - startedAtMs,
                loadedModJarPaths = loadedModJarPaths,
                modSetVerified = modSetVerified,
            ),
        )
        shutdownGame()
        releaseResources()
    }

    private fun writeOutcome(outcome: AgentPatchSmokeTestOutcome) {
        AgentPatchSmokeTestProtocol.writeOutcome(this, outcome)
        Log.i(TAG, "Smoke test status=${outcome.status} reason=${outcome.reason}")
    }

    /**
     * Closes the game JVM.
     *
     * The verdict is already on disk, so this only has to make sure no game session outlives the
     * run. `nativeRequestCloseWindow` is the same graceful path an in-game back exit uses; if the
     * JVM does not exit within the grace period the process is killed, because a live renderer would
     * otherwise keep writing into a surface this run has finished with.
     */
    private fun shutdownGame() {
        if (!jvmExited.get()) {
            runCatching { CallbackBridge.nativeRequestCloseWindow() }
                .onFailure { Log.w(TAG, "Unable to request the game window close", it) }
            val deadlineMs = SystemClock.elapsedRealtime() + JVM_EXIT_GRACE_MS
            while (!jvmExited.get() && SystemClock.elapsedRealtime() < deadlineMs) {
                sleepQuietly(POLL_INTERVAL_MS)
            }
        }
        if (!jvmExited.get()) {
            Log.w(TAG, "Game JVM did not exit in time; killing the game process")
            releaseResources()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /**
     * Releases everything the run owns. Called on every exit path, including the launcher unbinding
     * while the run is still in flight.
     */
    private fun acquireWakeLock(timeoutMs: Long) {
        runCatching {
            val powerManager = getSystemService(POWER_SERVICE) as? PowerManager ?: return
            wakeLock = powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:agent-smoke-test")
                .apply { acquire(timeoutMs + WAKE_LOCK_MARGIN_MS) }
        }.onFailure { Log.w(TAG, "Unable to acquire the smoke test wake lock", it) }
    }

    private fun releaseResources() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        jvmLaunchController?.cleanup()
        jvmLaunchController = null
        headlessSurface?.release()
        headlessSurface = null
        if (launchGuardAcquired) {
            GameProcessLaunchGuard.release(LAUNCH_GUARD_TOKEN)
            launchGuardAcquired = false
        }
        // The run marker is deliberately left in place: the launcher clears it once the game
        // process is really gone.
        GamePresenceStateMarker.markLauncherActive(this)
    }

    private fun connectBridgeWindow(surface: HeadlessGameSurface) {
        runCatching {
            JREUtils.setupBridgeWindow(surface.surface)
        }.onFailure { Log.w(TAG, "Unable to attach the headless window to the bridge", it) }
    }

    /**
     * Presents the session as a normal foreground session to the native bridge.
     *
     * The window is not on a display, but the bridge's GLFW state machine and audio/foreground gates
     * still decide whether the render loop runs and whether it is considered active.
     */
    private fun applyHeadlessWindowState() {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                CallbackBridge.nativeSetRuntimeForeground(true)
                CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_ICONIFIED, 0)
                CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1)
                CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 1)
                CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1)
                // The session is deliberately invisible, so its audio would have no source the user
                // could attribute it to.
                CallbackBridge.nativeSetAudioMuted(true)
            }.onFailure { Log.w(TAG, "Unable to apply the headless window state", it) }
        }
    }

    private fun syncSurfaceSize(surface: HeadlessGameSurface) {
        CallbackBridge.physicalWidth = surface.width
        CallbackBridge.physicalHeight = surface.height
        CallbackBridge.windowWidth = surface.width
        CallbackBridge.windowHeight = surface.height
        runCatching {
            DisplayConfigSync.syncToCurrentResolution(
                context = this,
                width = surface.width,
                height = surface.height,
                targetFpsLimitOverride = LauncherConfig.readTargetFpsValue(this),
            )
        }.onFailure { Log.w(TAG, "Unable to sync the display config", it) }
    }

    /**
     * The render target is landscape, matching how the game actually runs, even when the panel is
     * held in portrait.
     */
    private fun resolveSurfaceSize(): Pair<Int, Int> {
        val displayManager = getSystemService(DisplayManager::class.java)
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val point = Point()
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealSize(point)
        }
        if (point.x <= 0 || point.y <= 0) {
            val metrics = resources.displayMetrics
            point.x = metrics.widthPixels
            point.y = metrics.heightPixels
        }
        val longSide = maxOf(point.x, point.y).coerceAtLeast(1)
        val shortSide = minOf(point.x, point.y).coerceAtLeast(1)
        return longSide to shortSide
    }

    private data class RunOutcome(
        val passed: Boolean,
        val reason: String,
        val reachedMainMenu: Boolean,
    )

    private fun awaitOutcome(
        eventsFile: File,
        logFile: File,
        preLaunchLogLength: Long,
        startedAtMs: Long,
        timeoutMs: Long,
    ): RunOutcome {
        val deadlineMs = startedAtMs + timeoutMs
        var jvmExitGraceDeadlineMs = 0L
        while (!cancelRequested && SystemClock.elapsedRealtime() < deadlineMs) {
            val events = readEvents(eventsFile)
            terminalMessage(events, "FAIL")?.let { detail ->
                return RunOutcome(false, "boot_failure: $detail", false)
            }
            terminalMessage(events, "READY")?.let {
                sleepQuietly(SETTLE_AFTER_READY_MS)
                crashMarker(logFile, preLaunchLogLength)?.let { crash ->
                    return RunOutcome(false, "crash_after_main_menu: $crash", true)
                }
                return RunOutcome(true, "main_menu_reached", true)
            }
            // The game JVM lives in this very process, so process liveness says nothing about the
            // session. Its exit is the real signal: without a terminal boot event the mod set never
            // reached the main menu. A short grace absorbs the exit trap's own writes.
            if (jvmExited.get()) {
                if (jvmExitGraceDeadlineMs == 0L) {
                    jvmExitGraceDeadlineMs = SystemClock.elapsedRealtime() + JVM_EXIT_GRACE_MS
                } else if (SystemClock.elapsedRealtime() >= jvmExitGraceDeadlineMs) {
                    val lateEvents = readEvents(eventsFile)
                    terminalMessage(lateEvents, "READY")?.let {
                        return RunOutcome(true, "main_menu_reached", true)
                    }
                    terminalMessage(lateEvents, "FAIL")?.let { detail ->
                        return RunOutcome(false, "boot_failure: $detail", false)
                    }
                    val crash = crashMarker(logFile, preLaunchLogLength)
                    return RunOutcome(
                        false,
                        crash?.let { "game_jvm_exited: $it" } ?: "game_jvm_exited_before_main_menu",
                        false,
                    )
                }
            } else {
                jvmExitGraceDeadlineMs = 0L
            }
            sleepQuietly(POLL_INTERVAL_MS)
        }
        if (cancelRequested) {
            return RunOutcome(false, "smoke_run_cancelled", false)
        }
        val crash = crashMarker(logFile, preLaunchLogLength)
        return RunOutcome(
            false,
            crash?.let { "timeout: $it" } ?: "timeout_waiting_for_main_menu",
            false,
        )
    }

    private fun readLoadedModJarPaths(auditFile: File): List<String> {
        // The audit is written while the game JVM starts; give it a moment to appear.
        val deadlineMs = SystemClock.elapsedRealtime() + MOD_AUDIT_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            if (auditFile.isFile) break
            sleepQuietly(100L)
        }
        return runCatching {
            if (!auditFile.isFile) {
                emptyList()
            } else {
                auditFile.readLines(StandardCharsets.UTF_8)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
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
        }.getOrDefault("")

    /**
     * Looks for a crash marker in the part of `latest.log` written by this run.
     *
     * The game JVM truncates `latest.log` when it starts, so a file shorter than the pre-run length
     * means the whole file is new content and the pre-run offset must be discarded.
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
        STRONG_CRASH_MARKERS.firstOrNull { appended.contains(it, ignoreCase = true) }?.let { return it }
        // The detector scans a wider tail; only accept its verdict when its own marker also appears
        // in the content this run wrote, so a pre-run crash cannot be misreported.
        val summary = runCatching { LatestLogCrashDetector.detect(logFile) }.getOrNull()
        return summary?.takeIf { appended.contains(it.marker, ignoreCase = true) }?.detail
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

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        private const val TAG = "AgentSmokeTestSvc"
        private const val LAUNCH_GUARD_TOKEN = "agent-smoke-test"
        private const val SETTLE_AFTER_READY_MS = 2_500L
        private const val POLL_INTERVAL_MS = 250L
        private const val MIN_TIMEOUT_MS = 15_000L
        private const val MOD_AUDIT_WAIT_MS = 10_000L
        private const val WAKE_LOCK_MARGIN_MS = 120_000L
        private const val JVM_EXIT_GRACE_MS = 8_000L
        private const val TAIL_READ_CHARS = 64 * 1024
        private val STRONG_CRASH_MARKERS = listOf(
            "Game crashed.",
            "Exception occurred in CardCrawlGame render method!",
            "Exception in thread \"LWJGL Application\"",
        )
    }
}

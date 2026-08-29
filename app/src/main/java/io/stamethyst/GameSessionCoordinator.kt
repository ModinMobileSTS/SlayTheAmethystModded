package io.stamethyst

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import io.stamethyst.backend.audio.ForegroundAudioPolicy
import io.stamethyst.backend.diag.MemoryDiagnosticsLogger
import io.stamethyst.backend.easytier.EasyTierInGameSessionState
import io.stamethyst.backend.easytier.EasyTierInGameStatusReporter
import io.stamethyst.backend.launch.progressText
import io.stamethyst.backend.crash.LatestLogCrashDetector
import io.stamethyst.backend.launch.BackExitNotice
import io.stamethyst.backend.launch.ExpectedGameExitNotice
import io.stamethyst.backend.launch.ExpectedGameExitReturnPolicy
import io.stamethyst.backend.render.AndroidGameModeSupport
import io.stamethyst.backend.render.DisplayConfigSync
import io.stamethyst.backend.render.GameWindowVisibilityPolicy
import io.stamethyst.backend.launch.JvmLaunchController
import io.stamethyst.backend.launch.LaunchPreparationFailureMessageResolver
import io.stamethyst.backend.launch.LauncherReturnCoordinator
import io.stamethyst.backend.launch.StsLaunchSpec
import io.stamethyst.backend.runtime.RuntimePackInstaller
import io.stamethyst.backend.steamcloud.SteamAchievementSyncService
import io.stamethyst.backend.steamcloud.AchievementSyncLogStore
import io.stamethyst.backend.steamcloud.SteamGamePresenceService
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.SpecialKeyInputMode
import io.stamethyst.input.GameInputHandler
import io.stamethyst.input.SystemKeyboardPreviewRequest
import io.stamethyst.ui.LauncherTransientNoticeBus
import io.stamethyst.ui.main.MainScreenViewModel
import net.kdt.pojavlaunch.LwjglGlfwKeycode
import org.lwjgl.glfw.CallbackBridge
import java.io.File

internal class GameSessionCoordinator(
    private val activity: StsGameActivity,
    private val config: GameSessionConfig,
    private val renderSurfaceManager: RenderSurfaceManager,
    private val inputHandler: GameInputHandler,
    private val onJvmLaunchFinished: () -> Unit
) {
    companion object {
        private const val BACK_FORCE_RESTART_DELAY_MS = 120L
        private const val BACK_FORCE_KILL_FALLBACK_MS = 1500L
        private const val BACK_EXIT_CONFIRMATION_WINDOW_MS = 2000L
        private const val CRASH_LAUNCHER_RESTART_DELAY_MS = 320L
        private const val KEYBOARD_REQUEST_POLL_MS = 120L
        private const val LAN_GAME_STATE_REQUEST_POLL_MS = 300L
        private const val FILE_PICKER_REQUEST_POLL_MS = 120L
        private const val RESCUE_TOAST_REQUEST_POLL_MS = 120L
        private const val ACHIEVEMENT_REQUEST_POLL_MS = 120L
        private const val HARNESS_EXIT_REQUEST_POLL_MS = 120L
        private const val EXPECTED_GAME_EXIT_PROCESS_KILL_DELAY_MS = 1500L
        private const val EXPECTED_GAME_EXIT_LAUNCHER_RESTART_DELAY_MS = 180L
        private const val LANDSCAPE_WAIT_TIMEOUT_MS = 4000L
        private val FOREGROUND_AUDIO_RESTORE_DELAYS_MS = longArrayOf(150L, 400L, 1000L, 2200L)
    }

    @Volatile
    private var backExitRequested = false

    @Volatile
    private var backExitHardRestartTriggered = false

    @Volatile
    private var backExitLauncherShown = false

    private var backExitConfirmationDeadlineMs = 0L

    @Volatile
    private var crashReturnTriggered = false

    @Volatile
    private var expectedGameExitReturnTriggered = false

    @Volatile
    private var activityResumed = false

    @Volatile
    private var activityStopped = false

    private var waitingLandscapeSinceMs = -1L
    private var jvmLaunchStartedWallTimeMs = 0L
    private var startCheckPosted = false
    private var lastKeyboardRequestPayload = ""
    private var lastLanGameStateRequestPayload = ""
    private var lastFilePickerRequestPayload = ""
    private var lastRescueToastRequestPayload = ""
    private var keyboardRequestPollStarted = false
    private var lanGameStateRequestPollStarted = false
    private var filePickerRequestPollStarted = false
    private var rescueToastRequestPollStarted = false
    private var achievementRequestPollStarted = false
    private var lastAchievementRequestKey = ""
    private var lastInvalidAchievementPayload = ""
    private var harnessExitRequestPollStarted = false
    private var rescueToastShown = false
    @Volatile
    private var destroyed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val expectedGameExitReturnPolicy = ExpectedGameExitReturnPolicy()
    private var pendingAudioDeviceRecovery = false
    private val foregroundAudioRestoreRunnables = mutableListOf<Runnable>()
    private val foregroundAudioPolicy = ForegroundAudioPolicy()
    private val inGameEasyTierOverlayController by lazy {
        InGameEasyTierOverlayController(
            activity = activity,
            viewModel = ViewModelProvider(activity)[MainScreenViewModel::class.java],
        )
    }
    private val inGameAchievementOverlayController by lazy {
        InGameAchievementOverlayController(activity)
    }
    private val startCheckRunnable = Runnable {
        startCheckPosted = false
        tryStartJvmWhenSurfaceReady()
    }
    private val backExitForceRestartRunnable = Runnable {
        if (backExitRequested) {
            forceRestartLauncherAndTerminateProcess()
        }
    }
    private val expectedGameExitReturnWatchdogRunnable = object : Runnable {
        override fun run() {
            pollExpectedGameExitReturn()
        }
    }
    private val keyboardRequestPollRunnable = object : Runnable {
        override fun run() {
            pollInGameKeyboardRequest()
            if (!destroyed && keyboardRequestPollStarted) {
                mainHandler.postDelayed(this, KEYBOARD_REQUEST_POLL_MS)
            }
        }
    }
    private val filePickerRequestPollRunnable = object : Runnable {
        override fun run() {
            pollInGameFilePickerRequest()
            if (!destroyed && filePickerRequestPollStarted) {
                mainHandler.postDelayed(this, FILE_PICKER_REQUEST_POLL_MS)
            }
        }
    }
    private val lanGameStateRequestPollRunnable = object : Runnable {
        override fun run() {
            pollInGameLanGameStateRequest()
            if (!destroyed && lanGameStateRequestPollStarted) {
                mainHandler.postDelayed(this, LAN_GAME_STATE_REQUEST_POLL_MS)
            }
        }
    }
    private val rescueToastRequestPollRunnable = object : Runnable {
        override fun run() {
            pollRuntimeRescueToastRequest()
            if (!destroyed && rescueToastRequestPollStarted) {
                mainHandler.postDelayed(this, RESCUE_TOAST_REQUEST_POLL_MS)
            }
        }
    }
    private val harnessExitRequestPollRunnable = object : Runnable {
        override fun run() {
            pollHarnessExitRequest()
            if (!destroyed && harnessExitRequestPollStarted) {
                mainHandler.postDelayed(this, HARNESS_EXIT_REQUEST_POLL_MS)
            }
        }
    }
    private val achievementRequestPollRunnable = object : Runnable {
        override fun run() {
            pollAchievementRequest()
            if (!destroyed && achievementRequestPollStarted) {
                mainHandler.postDelayed(this, ACHIEVEMENT_REQUEST_POLL_MS)
            }
        }
    }

    private val bootOverlayController: BootOverlayController = BootOverlayController(
        activity = activity,
        manualDismissBootOverlay = config.manualDismissBootOverlay,
        useTextureViewSurface = config.useTextureViewSurface,
        onDismissed = {
            renderSurfaceManager.setBootOverlayActive(false)
            updateFloatingMouseVisibility()
            updatePerformanceOverlayVisibility()
            updateSystemGameState()
            trySchedulePostBootSurfaceSoftRefresh("overlay_dismissed")
        },
        onRequestEarlyDismiss = {
            bootOverlayController.setEarlyDismissRequestTimestamp(
                renderSurfaceManager.getLastTextureFrameTimestampNs()
            )
        },
        onSignalLaunchFailure = { detail -> signalLaunchFailure(detail) }
    )

    private val jvmLaunchController: JvmLaunchController = JvmLaunchController(
        activity = activity,
        launchMode = config.launchMode,
        debugMode = config.debugMode,
        rendererDecision = config.rendererDecision,
        renderScale = config.renderScale,
        forceJvmCrash = config.forceJvmCrash,
        forceRuntimeCrash = config.forceRuntimeCrash,
        autoplay = config.autoplay,
        autoplaySaveMode = config.autoplaySaveMode,
        autoplayMode = config.autoplayMode,
        autoplaySingleRoomSpecPath = config.autoplaySingleRoomSpecPath,
        autoplayChoiceDelayMs = config.autoplayChoiceDelayMs,
        autoplaySingleRoomBenchMode = config.autoplaySingleRoomBenchMode,
        performanceDeepDiagnostics = config.performanceDeepDiagnostics,
        cardObtainEffectOwnershipCompatEnabled = config.cardObtainEffectOwnershipCompatEnabled,
        mirrorJvmLogsToLogcat = config.mirrorJvmLogsToLogcat,
        onProgressUpdate = { percent, message ->
            bootOverlayController.updateProgress(
                percent,
                bootOverlayController.mapLaunchProgressMessage(percent, message)
            )
        },
        onLaunchComplete = { exitCode -> handleJvmExit(exitCode) },
        onLaunchFailed = { throwable -> handleJvmLaunchFailed(throwable) },
        onRuntimeCrashDetected = { detail -> handleRuntimeCrashDetected(detail) },
        onRuntimeReady = {
            activity.runOnUiThread {
                startExpectedGameExitReturnWatchdog()
                applyForegroundWindowState()
                updateFloatingMouseVisibility()
                startKeyboardRequestPolling()
                startLanGameStateRequestPolling()
                startFilePickerRequestPolling()
                startRescueToastRequestPolling()
                startAchievementRequestPolling()
                SteamGamePresenceService.startIfEnabled(activity)
                updatePerformanceOverlayVisibility()
                updateSystemGameState()
                trySchedulePostBootSurfaceSoftRefresh("runtime_ready")
            }
        },
        onSurfaceSizeSync = {
            renderSurfaceManager.updateWindowSize()
            renderSurfaceManager.logRenderInfo()
            renderSurfaceManager.syncDisplayConfigToSurfaceSize()
        },
        getWindowWidth = { renderSurfaceManager.resolvePhysicalWidth() },
        getWindowHeight = { renderSurfaceManager.resolvePhysicalHeight() }
    )

    val jvmLaunchStarted: Boolean
        get() = jvmLaunchController.vmStarted

    private var performanceOverlayController: GamePerformanceOverlayController? = null

    fun initSessionUi(overlayView: TextView) {
        bootOverlayController.init()
        activity.findViewById<android.widget.FrameLayout>(R.id.gameHost).let { host ->
            inGameEasyTierOverlayController.attachToHost(host)
            inGameAchievementOverlayController.attachToHost(host)
        }
        if (performanceOverlayController == null) {
            performanceOverlayController = GamePerformanceOverlayController(
                activity = activity,
                overlayView = overlayView,
                rendererSummary = config.rendererDecision.overlaySummary(),
                readJvmRuntimeMemorySnapshot = { jvmLaunchController.runtimeMemorySnapshot },
                readJvmLaunchStartedElapsedMs = { jvmLaunchController.jvmLaunchStartedElapsedMs },
                snapshotFile = io.stamethyst.config.RuntimePaths.launcherPerfSnapshot(activity),
                performanceDeepDiagnostics = config.performanceDeepDiagnostics
            )
        }
        performanceOverlayController?.init()
    }

    fun refreshSessionUiVisibility() {
        updateFloatingMouseVisibility()
        updatePerformanceOverlayVisibility()
    }

    fun onDestroy() {
        destroyed = true
        cancelStartCheck()
        cancelBackExitForceRestart()
        cancelExpectedGameExitReturnWatchdog()
        stopKeyboardRequestPolling()
        stopLanGameStateRequestPolling()
        stopFilePickerRequestPolling()
        stopRescueToastRequestPolling()
        stopAchievementRequestPolling()
        stopHarnessExitRequestPolling()
        inGameEasyTierOverlayController.onDestroy()
        inGameAchievementOverlayController.onDestroy()
        RuntimePaths.touchscreenCardHoldStateFile(activity).delete()
        reportEasyTierInGameState(EasyTierInGameSessionState.Online)
        cancelForegroundAudioRestoreRetries()
        activityResumed = false
        pendingAudioDeviceRecovery = false
        foregroundAudioPolicy.markActivityResumed(false)
        updateSystemGameState()
        syncRuntimeForegroundState(false)
        bootOverlayController.onDestroy()
        performanceOverlayController?.onDestroy()
        restoreRequestedTargetFps()
        jvmLaunchController.cleanup()
    }

    fun onResume() {
        if (destroyed) {
            return
        }
        activityResumed = true
        activityStopped = false
        foregroundAudioPolicy.markActivityResumed(true)
        performanceOverlayController?.onResume()
        syncRuntimeForegroundState(true)
        applyForegroundWindowState()
        updateFloatingMouseVisibility()
        updatePerformanceOverlayVisibility()
        updateSystemGameState()
        tryStartJvmWhenSurfaceReady()
    }

    fun onPause() {
        activityResumed = false
        // A paused-but-visible multi-window session must keep rendering and playing audio, so the
        // runtime is only pushed into the background state once the window actually leaves screen.
        val runtimeVisible = resolveRuntimeVisible()
        foregroundAudioPolicy.markActivityResumed(runtimeVisible)
        performanceOverlayController?.onPause()
        if (runtimeVisible) {
            updateSystemGameState()
            return
        }
        cancelForegroundAudioRestoreRetries()
        syncRuntimeForegroundState(false)
        applyBackgroundWindowState()
        updateSystemGameState()
    }

    fun onStop() {
        activityStopped = true
        activityResumed = false
        foregroundAudioPolicy.markActivityResumed(false)
        cancelForegroundAudioRestoreRetries()
        syncRuntimeForegroundState(false)
        applyBackgroundWindowState()
        updateSystemGameState()
    }

    fun onStart() {
        activityStopped = false
    }

    private fun resolveRuntimeVisible(): Boolean {
        return GameWindowVisibilityPolicy.resolveRuntimeVisible(
            activityStopped = activityStopped,
            activityResumed = activityResumed,
            inMultiWindowMode = isActivityInMultiWindowMode()
        )
    }

    private fun isActivityInMultiWindowMode(): Boolean {
        return try {
            activity.isInMultiWindowMode
        } catch (_: Throwable) {
            false
        }
    }

    fun onPlatformAudioFocusChanged(granted: Boolean) {
        if (!granted) {
            cancelForegroundAudioRestoreRetries()
            setRuntimeAudioMuted(true)
            return
        }
        pendingAudioDeviceRecovery = true
        requestForegroundAudioRecovery(forceMuteFirst = true)
    }

    fun onAudioOutputRouteChanged() {
        pendingAudioDeviceRecovery = true
        requestForegroundAudioRecovery(forceMuteFirst = true)
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        updatePerformanceOverlayVisibility()
        syncFocusStateToNative(hasFocus)
        if (hasFocus) {
            scheduleForegroundAudioRestoreRetries()
        }
    }

    fun onSurfaceReady() {
        tryStartJvmWhenSurfaceReady()
    }

    fun onTextureFrameUpdate(timestampNs: Long) {
        bootOverlayController.onTextureFrameUpdate(timestampNs)
    }

    fun isInputDispatchReady(): Boolean {
        if (backExitRequested) return false
        if (!jvmLaunchController.runtimeLifecycleReady) return false
        if (!renderSurfaceManager.bridgeSurfaceReady) return false
        return CallbackBridge.windowWidth > 0 && CallbackBridge.windowHeight > 0
    }

    fun handleAndroidBackPressed() {
        when (config.backBehavior) {
            BackBehavior.EXIT_TO_LAUNCHER -> confirmBackExitToLauncher()
            BackBehavior.SEND_ESCAPE -> sendEscapeKeyToGame()
            BackBehavior.NONE -> Unit
        }
    }

    private fun confirmBackExitToLauncher() {
        val now = SystemClock.uptimeMillis()
        if (backExitConfirmationDeadlineMs != 0L && now <= backExitConfirmationDeadlineMs) {
            backExitConfirmationDeadlineMs = 0L
            requestBackExitToLauncher()
            return
        }
        backExitConfirmationDeadlineMs = now + BACK_EXIT_CONFIRMATION_WINDOW_MS
        Toast.makeText(
            activity,
            R.string.game_back_exit_confirmation,
            Toast.LENGTH_SHORT
        ).show()
    }

    fun handleAndroidBackKeyEvent(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> return true
            KeyEvent.ACTION_UP -> {
                if (!event.isCanceled) {
                    handleAndroidBackPressed()
                }
                return true
            }
            else -> return true
        }
    }

    private fun tryStartJvmWhenSurfaceReady() {
        if (destroyed || activity.isFinishing || activity.isDestroyed || backExitRequested || jvmLaunchController.vmStarted) {
            return
        }

        val rawWidth = renderSurfaceManager.resolvePhysicalWidth()
        val rawHeight = renderSurfaceManager.resolvePhysicalHeight()

        if (rawWidth <= 1 || rawHeight <= 1) {
            scheduleStartCheck()
            return
        }

        // Waiting for the requested landscape orientation only makes sense when the system still
        // honours screenOrientation. In multi-window the requested orientation is ignored, so a
        // portrait window would never turn landscape and this would just stall the launch.
        if (rawWidth < rawHeight && !isActivityInMultiWindowMode()) {
            val now = SystemClock.uptimeMillis()
            if (waitingLandscapeSinceMs < 0L) {
                waitingLandscapeSinceMs = now
            }
            val waitedMs = now - waitingLandscapeSinceMs
            if (waitedMs < LANDSCAPE_WAIT_TIMEOUT_MS) {
                scheduleStartCheck()
                return
            }
        } else {
            waitingLandscapeSinceMs = -1L
        }

        startJvmOnce()
    }

    private fun startJvmOnce() {
        if (destroyed || activity.isFinishing || activity.isDestroyed) {
            return
        }
        if (jvmLaunchController.vmStarted || backExitRequested) {
            if (backExitRequested) {
                activity.finish()
            }
            return
        }

        val runtimeRoot = RuntimePaths.runtimeRoot(activity)
        val javaHome = RuntimePackInstaller.locateJavaHome(runtimeRoot) ?: File(runtimeRoot, "jre")
        MemoryDiagnosticsLogger.logEvent(
            activity,
            "game_session_launch_begin",
            mapOf(
                "launchMode" to config.launchMode,
                "renderScale" to config.renderScale,
                "rendererBackend" to config.rendererDecision.effectiveBackend.rendererId(),
                "rendererSurface" to config.rendererDecision.effectiveSurfaceBackend.persistedValue,
                "useTextureViewSurface" to config.useTextureViewSurface
            )
        )

        clearStaleAchievementRequestBeforeLaunch()
        syncRuntimeForegroundState(true)
        ExpectedGameExitNotice.clearExpectedGameExit(activity)
        jvmLaunchStartedWallTimeMs = System.currentTimeMillis()
        jvmLaunchController.start(
            javaHome = javaHome,
            bootOverlayController = bootOverlayController
        )
    }

    private fun clearStaleAchievementRequestBeforeLaunch() {
        val requestFile = RuntimePaths.achievementRequestFile(activity)
        if (!requestFile.isFile) return
        val deleted = runCatching { requestFile.delete() }.getOrDefault(false)
        AchievementSyncLogStore.append(
            activity,
            if (deleted) "request_cleared_before_launch" else "request_clear_before_launch_failed",
        )
    }

    private fun scheduleStartCheck() {
        if (destroyed || activity.isFinishing || activity.isDestroyed || jvmLaunchController.vmStarted || startCheckPosted) {
            return
        }
        startCheckPosted = true
        renderSurfaceManager.renderView.postDelayed(startCheckRunnable, 120L)
    }

    private fun cancelStartCheck() {
        if (!startCheckPosted) {
            return
        }
        startCheckPosted = false
        renderSurfaceManager.renderView.removeCallbacks(startCheckRunnable)
    }

    private fun handleJvmExit(exitCode: Int) {
        cancelExpectedGameExitReturnWatchdog()
        onJvmLaunchFinished()
        if (crashReturnTriggered || expectedGameExitReturnTriggered) {
            return
        }
        val heapPressureNotice = if (exitCode == 0) {
            jvmLaunchController.buildHeapPressureNotice()
        } else {
            null
        }
        MemoryDiagnosticsLogger.logEvent(
            activity,
            "game_session_jvm_exit",
            mapOf(
                "launchMode" to config.launchMode,
                "exitCode" to exitCode,
                "bootInteractiveSignalSeen" to jvmLaunchController.bootInteractiveSignalSeen,
                "backExitRequested" to backExitRequested,
                "heapPressureWarning" to (heapPressureNotice != null),
                "peakJvmHeapUsedBytes" to heapPressureNotice?.peakHeapUsedBytes,
                "peakJvmHeapMaxBytes" to heapPressureNotice?.peakHeapMaxBytes,
                "suggestedJvmHeapMb" to heapPressureNotice?.suggestedHeapMaxMb
            )
        )
        if (backExitRequested) {
            cancelBackExitForceRestart()
            activity.runOnUiThread {
                if (heapPressureNotice != null) {
                    activity.startActivity(
                        LauncherReturnCoordinator.createHeapPressureIntent(activity, heapPressureNotice)
                    )
                }
                activity.finish()
            }
            return
        }

        val exitedBeforeInteractiveBoot = exitCode == 0 && !jvmLaunchController.bootInteractiveSignalSeen
        val latestCrash = if (exitCode == 0) {
            LatestLogCrashDetector.detect(activity)
        } else {
            null
        }

        activity.runOnUiThread {
            if (latestCrash != null) {
                reportCrashAndReturn(
                    -1,
                    false,
                    latestCrash.detail,
                    terminateProcessAfterReturn = true
                )
                return@runOnUiThread
            }

            if (exitedBeforeInteractiveBoot) {
                reportCrashAndReturn(
                    JvmLaunchController.CRASH_CODE_BOOT_FAILURE,
                    false,
                    jvmLaunchController.buildExitedBeforeInteractiveDetail(),
                    terminateProcessAfterReturn = true
                )
                return@runOnUiThread
            }

            if (exitCode == 0) {
                BackExitNotice.markLauncherReturnHandledInProcess()
                if (heapPressureNotice != null) {
                    activity.startActivity(
                        LauncherReturnCoordinator.createHeapPressureIntent(activity, heapPressureNotice)
                    )
                } else {
                    activity.startActivity(LauncherReturnCoordinator.createReturnIntent(activity))
                }
                activity.finish()
            } else {
                reportCrashAndReturn(
                    exitCode,
                    false,
                    null,
                    terminateProcessAfterReturn = true
                )
            }
        }
    }

    private fun handleJvmLaunchFailed(throwable: Throwable) {
        cancelExpectedGameExitReturnWatchdog()
        onJvmLaunchFinished()
        if (crashReturnTriggered) {
            return
        }
        MemoryDiagnosticsLogger.logEvent(
            activity,
            "game_session_jvm_launch_failed",
            mapOf(
                "launchMode" to config.launchMode,
                "errorClass" to throwable.javaClass.name,
                "errorMessage" to throwable.message
            )
        )
        if (backExitRequested) {
            cancelBackExitForceRestart()
            activity.runOnUiThread { activity.finish() }
            return
        }
        val resolvedMessage = LaunchPreparationFailureMessageResolver.resolve(activity, throwable)
        val message = if (resolvedMessage != null) {
            resolvedMessage
        } else {
            val detail = buildString {
                append(throwable.javaClass.simpleName)
                val throwableMessage = throwable.message?.trim().orEmpty()
                if (throwableMessage.isNotEmpty()) {
                    append(": ")
                    append(throwableMessage)
                }
            }
            activity.progressText(R.string.startup_failure_launch_failed_with_detail, detail)
        }
        activity.runOnUiThread { reportCrashAndReturn(-1, false, message) }
    }

    private fun signalLaunchFailure(detail: String) {
        cancelExpectedGameExitReturnWatchdog()
        if (crashReturnTriggered) {
            return
        }
        MemoryDiagnosticsLogger.logEvent(
            activity,
            "game_session_launch_failure_signaled",
            mapOf(
                "launchMode" to config.launchMode,
                "detail" to detail
            )
        )
        if (backExitRequested) {
            cancelBackExitForceRestart()
            activity.runOnUiThread { activity.finish() }
            return
        }

        val crashCode = if (detail.lowercase().contains("outofmemory")) {
            JvmLaunchController.CRASH_CODE_OUT_OF_MEMORY
        } else {
            JvmLaunchController.CRASH_CODE_BOOT_FAILURE
        }

        activity.runOnUiThread { reportCrashAndReturn(crashCode, false, detail) }
    }

    private fun handleRuntimeCrashDetected(detail: String) {
        cancelExpectedGameExitReturnWatchdog()
        if (backExitRequested || !tryMarkCrashReturnTriggered()) {
            return
        }
        MemoryDiagnosticsLogger.logEvent(
            activity,
            "game_session_runtime_crash_detected",
            mapOf(
                "launchMode" to config.launchMode,
                "detail" to detail
            )
        )
        activity.runOnUiThread {
            launchCrashReturn(
                code = -1,
                isSignal = false,
                detail = detail,
                terminateProcessAfterReturn = true
            )
        }
    }

    private fun requestBackExitToLauncher() {
        if (backExitRequested) {
            return
        }
        cancelExpectedGameExitReturnWatchdog()
        MemoryDiagnosticsLogger.logEvent(
            activity,
            "game_session_back_exit_requested",
            mapOf("launchMode" to config.launchMode)
        )
        backExitRequested = true
        backExitLauncherShown = false
        updateSystemGameState()
        val bootOverlayActive = !bootOverlayController.isDismissed

        inputHandler.hideSoftKeyboard()
        inputHandler.resetGamepadState()
        updateFloatingMouseVisibility()
        updatePerformanceOverlayVisibility()
        BackExitNotice.markExpectedBackExit(activity)

        bootOverlayController.updateProgress(100, activity.progressText(R.string.startup_progress_stopping_game))

        jvmLaunchController.interrupt()

        if (!jvmLaunchController.runtimeLifecycleReady) {
            showLauncherForExpectedBackExit()
            activity.finish()
            return
        }

        try {
            CallbackBridge.nativeSetInputReady(false)
        } catch (_: Throwable) {
        }

        val closeRequested = requestJvmCloseSignal()
        if (bootOverlayActive) {
            showLauncherForExpectedBackExit()
            activity.finish()
            return
        }
        showLauncherForExpectedBackExit()
        if (!closeRequested || !jvmLaunchController.runtimeLifecycleReady) {
            forceRestartLauncherAndTerminateProcess()
            return
        }
        scheduleBackExitForceRestart()
    }

    private fun requestJvmCloseSignal(): Boolean {
        return try {
            CallbackBridge.nativeRequestCloseWindow()
        } catch (_: Throwable) {
            false
        }
    }

    private fun scheduleBackExitForceRestart() {
        cancelBackExitForceRestart()
        renderSurfaceManager.renderView.postDelayed(
            backExitForceRestartRunnable,
            BACK_FORCE_KILL_FALLBACK_MS
        )
    }

    private fun cancelBackExitForceRestart() {
        try {
            renderSurfaceManager.renderView.removeCallbacks(backExitForceRestartRunnable)
        } catch (_: UninitializedPropertyAccessException) {
        }
    }

    private fun sendEscapeKeyToGame() {
        if (backExitRequested) {
            return
        }
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE)
        inputHandler.dispatchKeyboardEventToGame(down)
        inputHandler.dispatchKeyboardEventToGame(up)
    }

    private fun forceRestartLauncherAndTerminateProcess() {
        if (backExitHardRestartTriggered) {
            return
        }
        backExitHardRestartTriggered = true
        if (!backExitLauncherShown) {
            LauncherReturnCoordinator.scheduleLauncherRestart(
                context = activity,
                delayMs = BACK_FORCE_RESTART_DELAY_MS,
                markExpectedBackExitRestart = true
            )
        }
        activity.finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun startExpectedGameExitReturnWatchdog() {
        cancelExpectedGameExitReturnWatchdog()
        expectedGameExitReturnTriggered = false
        expectedGameExitReturnPolicy.reset()
        mainHandler.postDelayed(
            expectedGameExitReturnWatchdogRunnable,
            ExpectedGameExitReturnPolicy.DEFAULT_POLL_INTERVAL_MS
        )
    }

    private fun cancelExpectedGameExitReturnWatchdog() {
        try {
            mainHandler.removeCallbacks(expectedGameExitReturnWatchdogRunnable)
        } catch (_: Throwable) {
        }
        expectedGameExitReturnPolicy.reset()
    }

    private fun pollExpectedGameExitReturn() {
        val launchStartedAtMs = jvmLaunchStartedWallTimeMs
        val markerRecent = launchStartedAtMs > 0L &&
            ExpectedGameExitNotice.isExpectedGameExitRecent(activity, launchStartedAtMs)
        val active = !destroyed &&
            !activity.isFinishing &&
            !activity.isDestroyed &&
            !backExitRequested &&
            !crashReturnTriggered &&
            !expectedGameExitReturnTriggered &&
            jvmLaunchController.runtimeLifecycleReady
        when (
            expectedGameExitReturnPolicy.evaluate(
                nowElapsedMs = SystemClock.uptimeMillis(),
                expectedExitMarkerRecent = markerRecent,
                active = active
            )
        ) {
            ExpectedGameExitReturnPolicy.Decision.ContinuePolling -> {
                mainHandler.postDelayed(
                    expectedGameExitReturnWatchdogRunnable,
                    ExpectedGameExitReturnPolicy.DEFAULT_POLL_INTERVAL_MS
                )
            }

            ExpectedGameExitReturnPolicy.Decision.StopPolling -> Unit
            ExpectedGameExitReturnPolicy.Decision.ReturnToLauncher -> {
                returnToLauncherAfterExpectedGameExitMarker()
            }
        }
    }

    private fun returnToLauncherAfterExpectedGameExitMarker() {
        if (expectedGameExitReturnTriggered || backExitRequested || crashReturnTriggered || destroyed) {
            return
        }
        expectedGameExitReturnTriggered = true
        onJvmLaunchFinished()
        updateSystemGameState()
        BackExitNotice.markLauncherReturnHandledInProcess()
        MemoryDiagnosticsLogger.logEvent(
            activity,
            "game_session_expected_exit_watchdog_return",
            mapOf(
                "launchMode" to config.launchMode,
                "launchStartedAtMs" to jvmLaunchStartedWallTimeMs,
                "runtimeLifecycleReady" to jvmLaunchController.runtimeLifecycleReady
            )
        )
        val launchedImmediately = try {
            activity.startActivity(LauncherReturnCoordinator.createReturnIntent(activity))
            true
        } catch (_: Throwable) {
            false
        }
        if (!launchedImmediately) {
            LauncherReturnCoordinator.scheduleLauncherRestart(
                context = activity,
                delayMs = EXPECTED_GAME_EXIT_LAUNCHER_RESTART_DELAY_MS,
                markExpectedBackExitRestart = false
            )
        }
        activity.finish()
        mainHandler.postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, EXPECTED_GAME_EXIT_PROCESS_KILL_DELAY_MS)
    }

    private fun reportCrashAndReturn(
        code: Int,
        isSignal: Boolean,
        detail: String?,
        terminateProcessAfterReturn: Boolean = false
    ) {
        if (backExitRequested) {
            activity.finish()
            return
        }
        if (!tryMarkCrashReturnTriggered()) {
            activity.finish()
            return
        }
        launchCrashReturn(code, isSignal, detail, terminateProcessAfterReturn)
    }

    private fun launchCrashReturn(
        code: Int,
        isSignal: Boolean,
        detail: String?,
        terminateProcessAfterReturn: Boolean
    ) {
        updateSystemGameState()
        val crashIntent = LauncherReturnCoordinator.createCrashIntent(activity, code, isSignal, detail)
        if (terminateProcessAfterReturn) {
            val launchedImmediately = try {
                activity.startActivity(crashIntent)
                true
            } catch (_: Throwable) {
                false
            }
            if (!launchedImmediately) {
                LauncherReturnCoordinator.scheduleCrashLauncherRestart(
                    context = activity,
                    delayMs = CRASH_LAUNCHER_RESTART_DELAY_MS,
                    code = code,
                    isSignal = isSignal,
                    detail = detail
                )
            }
            activity.finishAffinity()
            renderSurfaceManager.renderView.postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 220L)
            return
        }
        activity.startActivity(crashIntent)
        activity.finish()
    }

    @Synchronized
    private fun tryMarkCrashReturnTriggered(): Boolean {
        if (crashReturnTriggered) {
            return false
        }
        crashReturnTriggered = true
        return true
    }

    private fun showLauncherForExpectedBackExit() {
        if (backExitLauncherShown) {
            return
        }
        backExitLauncherShown = true
        BackExitNotice.markLauncherReturnHandledInProcess()
        activity.startActivity(LauncherReturnCoordinator.createReturnIntent(activity))
    }

    private fun applyForegroundWindowState() {
        syncRuntimeForegroundState(true)
        if (!jvmLaunchController.runtimeLifecycleReady) {
            return
        }
        try {
            CallbackBridge.nativeSetInputReady(true)
            CallbackBridge.nativeSetAudioMuted(!shouldAllowForegroundAudio() || pendingAudioDeviceRecovery)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_ICONIFIED, 0)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 1)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1)
        } catch (_: Throwable) {
        }
        if (shouldAllowForegroundAudio()) {
            scheduleForegroundAudioRestoreRetries()
        } else {
            cancelForegroundAudioRestoreRetries()
        }
    }

    private fun applyBackgroundWindowState() {
        syncRuntimeForegroundState(false)
        if (!jvmLaunchController.runtimeLifecycleReady) {
            return
        }
        try {
            CallbackBridge.nativeSetInputReady(false)
            setRuntimeAudioMuted(true)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_ICONIFIED, 1)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 0)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 0)
        } catch (_: Throwable) {
        }
    }

    private fun scheduleForegroundAudioRestoreRetries() {
        cancelForegroundAudioRestoreRetries()
        if (!shouldAllowForegroundAudio()) {
            return
        }
        for (delayMs in FOREGROUND_AUDIO_RESTORE_DELAYS_MS) {
            val runnable = Runnable {
                restoreForegroundAudioIfNeeded()
            }
            foregroundAudioRestoreRunnables += runnable
            renderSurfaceManager.renderView.postDelayed(runnable, delayMs)
        }
    }

    private fun cancelForegroundAudioRestoreRetries() {
        try {
            for (runnable in foregroundAudioRestoreRunnables) {
                renderSurfaceManager.renderView.removeCallbacks(runnable)
            }
        } catch (_: UninitializedPropertyAccessException) {
        }
        foregroundAudioRestoreRunnables.clear()
    }

    private fun restoreForegroundAudioIfNeeded() {
        if (!shouldAllowForegroundAudio()) {
            return
        }
        if (pendingAudioDeviceRecovery && recoverRuntimeAudioOutput()) {
            pendingAudioDeviceRecovery = false
        }
        setRuntimeAudioMuted(false)
    }

    private fun syncRuntimeForegroundState(foreground: Boolean) {
        try {
            CallbackBridge.nativeSetRuntimeForeground(foreground)
        } catch (_: Throwable) {
        }
    }

    private fun syncFocusStateToNative(hasFocus: Boolean) {
        if (!jvmLaunchController.runtimeLifecycleReady) {
            return
        }
        try {
            CallbackBridge.nativeSetWindowAttrib(
                LwjglGlfwKeycode.GLFW_FOCUSED,
                if (hasFocus) 1 else 0
            )
            CallbackBridge.nativeSetWindowAttrib(
                LwjglGlfwKeycode.GLFW_HOVERED,
                if (hasFocus) 1 else 0
            )
            if (hasFocus) {
                CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1)
                CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_ICONIFIED, 0)
            }
        } catch (_: Throwable) {
        }
    }

    private fun updateFloatingMouseVisibility() {
        val showAndroidFloatingMouse = config.showFloatingMouseWindow
        val showCustomSoftKeys =
            config.showFloatingMouseWindow || config.specialKeyInputMode == SpecialKeyInputMode.BUILT_IN_MOD
        inputHandler.updateFloatingMouseVisibility(
            showAndroidFloatingMouse,
            showCustomSoftKeys,
            jvmLaunchController.runtimeLifecycleReady,
            bootOverlayController.isDismissed,
            backExitRequested
        )
    }

    private fun updatePerformanceOverlayVisibility() {
        val shouldCollect = !backExitRequested &&
            (config.showGamePerformanceOverlay || config.performanceDeepDiagnostics) &&
            jvmLaunchController.runtimeLifecycleReady &&
            bootOverlayController.isDismissed &&
            activity.hasWindowFocus()
        performanceOverlayController?.setVisible(shouldCollect)
    }

    private fun updateSystemGameState() {
        val inForeground = resolveRuntimeVisible() && !backExitRequested
        val isLoading = inForeground &&
            (!jvmLaunchController.runtimeLifecycleReady || !bootOverlayController.isDismissed)
        AndroidGameModeSupport.reportGameState(
            context = activity,
            isLoading = isLoading,
            inForeground = inForeground
        )
    }

    private fun startKeyboardRequestPolling() {
        if (keyboardRequestPollStarted) {
            return
        }
        keyboardRequestPollStarted = true
        lastKeyboardRequestPayload = ""
        RuntimePaths.inGameKeyboardRequestFile(activity).delete()
        RuntimePaths.touchscreenCardHoldStateFile(activity).delete()
        mainHandler.post(keyboardRequestPollRunnable)
    }

    private fun startLanGameStateRequestPolling() {
        if (lanGameStateRequestPollStarted) {
            return
        }
        lanGameStateRequestPollStarted = true
        lastLanGameStateRequestPayload = ""
        RuntimePaths.inGameLanGameStateRequestFile(activity).delete()
        mainHandler.post(lanGameStateRequestPollRunnable)
    }

    private fun startFilePickerRequestPolling() {
        if (filePickerRequestPollStarted) {
            return
        }
        filePickerRequestPollStarted = true
        lastFilePickerRequestPayload = ""
        RuntimePaths.inGameFilePickerRequestFile(activity).delete()
        RuntimePaths.inGameFilePickerResultFile(activity).delete()
        RuntimePaths.inGameFilePickerSelectionFile(activity).delete()
        mainHandler.post(filePickerRequestPollRunnable)
    }

    private fun startRescueToastRequestPolling() {
        if (rescueToastRequestPollStarted) {
            return
        }
        rescueToastRequestPollStarted = true
        rescueToastShown = false
        lastRescueToastRequestPayload = ""
        RuntimePaths.runtimeRescueToastRequestFile(activity).delete()
        mainHandler.post(rescueToastRequestPollRunnable)
        startHarnessExitRequestPolling()
    }

    private fun startHarnessExitRequestPolling() {
        if (harnessExitRequestPollStarted) return
        harnessExitRequestPollStarted = true
        mainHandler.post(harnessExitRequestPollRunnable)
    }

    private fun stopKeyboardRequestPolling() {
        keyboardRequestPollStarted = false
        mainHandler.removeCallbacks(keyboardRequestPollRunnable)
    }

    private fun stopLanGameStateRequestPolling() {
        lanGameStateRequestPollStarted = false
        mainHandler.removeCallbacks(lanGameStateRequestPollRunnable)
    }

    private fun stopFilePickerRequestPolling() {
        filePickerRequestPollStarted = false
        mainHandler.removeCallbacks(filePickerRequestPollRunnable)
    }

    private fun stopRescueToastRequestPolling() {
        rescueToastRequestPollStarted = false
        mainHandler.removeCallbacks(rescueToastRequestPollRunnable)
    }

    private fun startAchievementRequestPolling() {
        if (achievementRequestPollStarted) return
        achievementRequestPollStarted = true
        lastAchievementRequestKey = ""
        lastInvalidAchievementPayload = ""
        AchievementSyncLogStore.append(activity, "polling_started")
        mainHandler.post(achievementRequestPollRunnable)
    }

    private fun stopAchievementRequestPolling() {
        achievementRequestPollStarted = false
        mainHandler.removeCallbacks(achievementRequestPollRunnable)
        AchievementSyncLogStore.append(activity, "polling_stopped")
    }

    private fun pollAchievementRequest() {
        if (!jvmLaunchController.runtimeLifecycleReady || backExitRequested) return
        val requestFile = RuntimePaths.achievementRequestFile(activity)
        val payload = try {
            if (requestFile.isFile) requestFile.readText().trim() else ""
        } catch (error: Throwable) {
            AchievementSyncLogStore.append(
                activity,
                "request_read_failed",
                "error=${AchievementSyncLogStore.errorType(error)}",
            )
            return
        }
        if (payload.isBlank()) return
        val request = SteamAchievementSyncService.parseRequest(payload)
        if (request == null) {
            if (payload != lastInvalidAchievementPayload) {
                lastInvalidAchievementPayload = payload
                AchievementSyncLogStore.append(
                    activity,
                    "request_rejected",
                    "bytes=${payload.toByteArray().size}",
                )
            }
            return
        }
        lastInvalidAchievementPayload = ""
        if (request.dedupeKey == lastAchievementRequestKey) return
        lastAchievementRequestKey = request.dedupeKey
        AchievementSyncLogStore.append(
            activity,
            "request_parsed",
            "request=${request.id} slot=${request.saveSlot ?: "none"} ids=${request.achievementIds.sorted().joinToString(",")}",
        )
        val requestUnchanged = runCatching {
            requestFile.isFile && requestFile.readText().trim() == payload
        }.getOrDefault(false)
        if (requestUnchanged && !requestFile.delete()) {
            AchievementSyncLogStore.append(activity, "request_delete_failed", "request=${request.id}")
        } else if (!requestUnchanged) {
            AchievementSyncLogStore.append(activity, "request_delete_deferred", "request=${request.id}")
        }
        if (LauncherConfig.isAchievementUnlockNotificationEnabled(activity)) {
            inGameAchievementOverlayController.enqueue(request.achievementIds)
        }
        SteamAchievementSyncService.syncRequestAsync(activity.applicationContext, request) { error ->
            if (error != null) {
                activity.runOnUiThread {
                    Toast.makeText(activity, R.string.achievement_sync_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun stopHarnessExitRequestPolling() {
        harnessExitRequestPollStarted = false
        mainHandler.removeCallbacks(harnessExitRequestPollRunnable)
    }

    private fun pollInGameKeyboardRequest() {
        if (!jvmLaunchController.runtimeLifecycleReady || backExitRequested) {
            return
        }
        val requestFile = RuntimePaths.inGameKeyboardRequestFile(activity)
        val payload = try {
            if (requestFile.isFile) requestFile.readText().trim() else ""
        } catch (_: Throwable) {
            ""
        }
        if (payload.isEmpty() || payload == lastKeyboardRequestPayload) {
            return
        }
        lastKeyboardRequestPayload = payload
        val source = payload.lineSequence().firstOrNull()?.trim().orEmpty()
        if (source.startsWith("online_panel:")) {
            inGameEasyTierOverlayController.show()
        } else if (source.startsWith("custom_button:")) {
            inputHandler.requestCustomSoftKeyButtonForGameInput("game_custom_button")
        } else if (source.startsWith("system_keyboard_preview:")) {
            SystemKeyboardPreviewRequest.parse(payload)?.let { request ->
                inputHandler.requestSystemSoftKeyboardPreviewForGameTextInput(
                    reason = "game_text_input_preview",
                    request = request,
                )
            }
        } else if (source.startsWith("system_keyboard:")) {
            inputHandler.requestSystemSoftKeyboardForGameTextInput("game_text_input_system")
        } else {
            inputHandler.requestSoftKeyboardForGameTextInput("game_text_input")
        }
    }

    private fun pollInGameLanGameStateRequest() {
        if (!jvmLaunchController.runtimeLifecycleReady || backExitRequested) {
            return
        }
        val requestFile = RuntimePaths.inGameLanGameStateRequestFile(activity)
        val payload = try {
            if (requestFile.isFile) requestFile.readText().trim() else ""
        } catch (_: Throwable) {
            ""
        }
        if (payload.isEmpty() || payload == lastLanGameStateRequestPayload) {
            return
        }
        lastLanGameStateRequestPayload = payload
        val state = EasyTierInGameSessionState.fromWireValue(
            payload.lineSequence().firstOrNull().orEmpty()
        ) ?: return
        reportEasyTierInGameState(state)
    }

    private fun reportEasyTierInGameState(state: EasyTierInGameSessionState) {
        Thread(
            { runCatching { EasyTierInGameStatusReporter.report(activity, state) } },
            "STS-EasyTierInGameState-${state.wireValue}",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun pollInGameFilePickerRequest() {
        if (!jvmLaunchController.runtimeLifecycleReady || backExitRequested) {
            return
        }
        val requestFile = RuntimePaths.inGameFilePickerRequestFile(activity)
        val payload = try {
            if (requestFile.isFile) requestFile.readText().trim() else ""
        } catch (_: Throwable) {
            ""
        }
        if (payload.isEmpty() || payload == lastFilePickerRequestPayload) {
            return
        }
        lastFilePickerRequestPayload = payload
        val lines = payload.lineSequence().map { it.trim() }.toList()
        val requestId = lines.getOrNull(0).orEmpty()
        val mimeType = lines.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "*/*"
        if (requestId.isNotEmpty()) {
            activity.requestInGameFileSelection(requestId, mimeType)
        }
    }

    private fun pollRuntimeRescueToastRequest() {
        if (!jvmLaunchController.runtimeLifecycleReady || backExitRequested || rescueToastShown) {
            return
        }
        val requestFile = RuntimePaths.runtimeRescueToastRequestFile(activity)
        val payload = try {
            if (requestFile.isFile) requestFile.readText().trim() else ""
        } catch (_: Throwable) {
            ""
        }
        if (payload.isEmpty() || payload == lastRescueToastRequestPayload) {
            return
        }
        lastRescueToastRequestPayload = payload
        rescueToastShown = true
        LauncherTransientNoticeBus.show(
            activity,
            R.string.runtime_save_rescue_toast,
            Toast.LENGTH_LONG
        )
    }

    private fun pollHarnessExitRequest() {
        if (!jvmLaunchController.runtimeLifecycleReady || backExitRequested) return
        val requestFile = RuntimePaths.harnessExitRequestFile(activity)
        val requested = try { requestFile.isFile && requestFile.readText().trim().isNotEmpty() } catch (_: Throwable) { false }
        if (!requested) return
        requestFile.delete()
        requestBackExitToLauncher()
    }

    private fun trySchedulePostBootSurfaceSoftRefresh(triggerReason: String) {
        if (config.useTextureViewSurface ||
            !jvmLaunchController.runtimeLifecycleReady ||
            !bootOverlayController.isDismissed
        ) {
            return
        }
        renderSurfaceManager.schedulePostBootSurfaceSoftRefresh(triggerReason)
    }

    private fun restoreRequestedTargetFps() {
        try {
            DisplayConfigSync.saveTargetFpsLimit(activity, config.requestedTargetFps)
        } catch (_: Throwable) {
        }
    }

    private fun shouldAllowForegroundAudio(): Boolean {
        // markActivityResumed() is fed the resolved visibility, so a paused-but-visible small
        // window still counts as foreground audio here.
        return foregroundAudioPolicy.shouldRestoreForegroundAudio(
            runtimeLifecycleReady = jvmLaunchController.runtimeLifecycleReady,
            backExitRequested = backExitRequested
        )
    }

    private fun requestForegroundAudioRecovery(forceMuteFirst: Boolean) {
        if (!shouldAllowForegroundAudio()) {
            return
        }
        cancelForegroundAudioRestoreRetries()
        if (forceMuteFirst) {
            setRuntimeAudioMuted(true)
        }
        restoreForegroundAudioIfNeeded()
        scheduleForegroundAudioRestoreRetries()
    }

    private fun setRuntimeAudioMuted(muted: Boolean) {
        if (!jvmLaunchController.runtimeLifecycleReady) {
            return
        }
        try {
            CallbackBridge.nativeSetAudioMuted(muted)
        } catch (_: Throwable) {
        }
    }

    private fun recoverRuntimeAudioOutput(): Boolean {
        if (!jvmLaunchController.runtimeLifecycleReady) {
            return false
        }
        return try {
            CallbackBridge.nativeRecoverAudioOutput()
        } catch (_: Throwable) {
            false
        }
    }
}

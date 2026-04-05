package io.stamethyst

import android.os.SystemClock
import android.view.KeyEvent
import android.widget.TextView
import io.stamethyst.backend.audio.ForegroundAudioPolicy
import io.stamethyst.backend.launch.progressText
import io.stamethyst.backend.crash.LatestLogCrashDetector
import io.stamethyst.backend.launch.BackExitNotice
import io.stamethyst.backend.render.AndroidGameModeSupport
import io.stamethyst.backend.render.DisplayConfigSync
import io.stamethyst.backend.launch.JvmLaunchController
import io.stamethyst.backend.launch.LaunchPreparationFailureMessageResolver
import io.stamethyst.backend.launch.LauncherReturnCoordinator
import io.stamethyst.backend.launch.StsLaunchSpec
import io.stamethyst.backend.runtime.RuntimePackInstaller
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.RuntimePaths
import io.stamethyst.input.GameInputHandler
import net.kdt.pojavlaunch.LwjglGlfwKeycode
import org.lwjgl.glfw.CallbackBridge
import java.io.File

internal class GameSessionCoordinator(
    private val activity: StsGameActivity,
    private val config: GameSessionConfig,
    private val renderSurfaceManager: RenderSurfaceManager,
    private val inputHandler: GameInputHandler
) {
    companion object {
        private const val BACK_FORCE_RESTART_DELAY_MS = 120L
        private const val BACK_FORCE_KILL_FALLBACK_MS = 1500L
        private const val CRASH_LAUNCHER_RESTART_DELAY_MS = 320L
        private val FOREGROUND_AUDIO_RESTORE_DELAYS_MS = longArrayOf(150L, 400L, 1000L, 2200L)
    }

    @Volatile
    private var backExitRequested = false

    @Volatile
    private var backExitHardRestartTriggered = false

    @Volatile
    private var backExitLauncherShown = false

    @Volatile
    private var crashReturnTriggered = false

    @Volatile
    private var activityResumed = false

    private var waitingLandscapeSinceMs = -1L
    private var startCheckPosted = false
    private var pendingAudioDeviceRecovery = false
    private val foregroundAudioRestoreRunnables = mutableListOf<Runnable>()
    private val foregroundAudioPolicy = ForegroundAudioPolicy()
    private val backExitForceRestartRunnable = Runnable {
        if (backExitRequested) {
            forceRestartLauncherAndTerminateProcess()
        }
    }

    private val bootOverlayController: BootOverlayController = BootOverlayController(
        activity = activity,
        manualDismissBootOverlay = config.manualDismissBootOverlay,
        useTextureViewSurface = config.useTextureViewSurface,
        onDismissed = {
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
        rendererDecision = config.rendererDecision,
        renderScale = config.renderScale,
        forceJvmCrash = config.forceJvmCrash,
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
                applyForegroundWindowState()
                updateFloatingMouseVisibility()
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

    private var performanceOverlayController: GamePerformanceOverlayController? = null

    fun initSessionUi(overlayView: TextView) {
        bootOverlayController.init()
        if (performanceOverlayController == null) {
            performanceOverlayController = GamePerformanceOverlayController(
                activity = activity,
                overlayView = overlayView,
                rendererSummary = config.rendererDecision.overlaySummary(),
                readJvmRuntimeMemorySnapshot = { jvmLaunchController.runtimeMemorySnapshot },
                readJvmLaunchStartedElapsedMs = { jvmLaunchController.jvmLaunchStartedElapsedMs }
            )
        }
        performanceOverlayController?.init()
    }

    fun refreshSessionUiVisibility() {
        updateFloatingMouseVisibility()
        updatePerformanceOverlayVisibility()
    }

    fun onDestroy() {
        cancelBackExitForceRestart()
        cancelForegroundAudioRestoreRetries()
        activityResumed = false
        pendingAudioDeviceRecovery = false
        foregroundAudioPolicy.markActivityResumed(false)
        foregroundAudioPolicy.markAudioFocusGranted(false)
        updateSystemGameState()
        syncRuntimeForegroundState(false)
        bootOverlayController.onDestroy()
        performanceOverlayController?.onDestroy()
        restoreRequestedTargetFps()
        jvmLaunchController.cleanup()
    }

    fun onResume() {
        activityResumed = true
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
        foregroundAudioPolicy.markActivityResumed(false)
        performanceOverlayController?.onPause()
        cancelForegroundAudioRestoreRetries()
        syncRuntimeForegroundState(false)
        applyBackgroundWindowState()
        updateSystemGameState()
    }

    fun onPlatformAudioFocusChanged(granted: Boolean) {
        foregroundAudioPolicy.markAudioFocusGranted(granted)
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
            BackBehavior.EXIT_TO_LAUNCHER -> requestBackExitToLauncher()
            BackBehavior.SEND_ESCAPE -> sendEscapeKeyToGame()
            BackBehavior.NONE -> Unit
        }
    }

    fun handleAndroidBackKeyEvent(event: KeyEvent): Boolean {
        if (inputHandler.isGamepadKeyEvent(event)) {
            return false
        }
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
        if (backExitRequested || jvmLaunchController.vmStarted) {
            return
        }

        val rawWidth = renderSurfaceManager.resolvePhysicalWidth()
        val rawHeight = renderSurfaceManager.resolvePhysicalHeight()

        if (rawWidth <= 1 || rawHeight <= 1) {
            scheduleStartCheck()
            return
        }

        if (rawWidth < rawHeight) {
            val now = SystemClock.uptimeMillis()
            if (waitingLandscapeSinceMs < 0L) {
                waitingLandscapeSinceMs = now
            }
            val waitedMs = now - waitingLandscapeSinceMs
            if (waitedMs < 4000L) {
                scheduleStartCheck()
                return
            }
        } else {
            waitingLandscapeSinceMs = -1L
        }

        startJvmOnce()
    }

    private fun startJvmOnce() {
        if (jvmLaunchController.vmStarted || backExitRequested) {
            if (backExitRequested) {
                activity.finish()
            }
            return
        }

        val runtimeRoot = RuntimePaths.runtimeRoot(activity)
        val javaHome = RuntimePackInstaller.locateJavaHome(runtimeRoot) ?: File(runtimeRoot, "jre")

        syncRuntimeForegroundState(true)
        jvmLaunchController.start(
            javaHome = javaHome,
            bootOverlayController = bootOverlayController
        )
    }

    private fun scheduleStartCheck() {
        if (jvmLaunchController.vmStarted || startCheckPosted) {
            return
        }
        startCheckPosted = true
        renderSurfaceManager.renderView.postDelayed({
            startCheckPosted = false
            tryStartJvmWhenSurfaceReady()
        }, 120L)
    }

    private fun handleJvmExit(exitCode: Int) {
        if (crashReturnTriggered) {
            return
        }
        val heapPressureNotice = if (exitCode == 0) {
            jvmLaunchController.buildHeapPressureNotice()
        } else {
            null
        }
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
                if (heapPressureNotice != null) {
                    activity.startActivity(
                        LauncherReturnCoordinator.createHeapPressureIntent(activity, heapPressureNotice)
                    )
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
        if (crashReturnTriggered) {
            return
        }
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
        if (crashReturnTriggered) {
            return
        }
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
        if (backExitRequested || !tryMarkCrashReturnTriggered()) {
            return
        }
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
        inputHandler.updateFloatingMouseVisibility(
            config.showFloatingMouseWindow,
            jvmLaunchController.runtimeLifecycleReady,
            bootOverlayController.isDismissed,
            backExitRequested
        )
    }

    private fun updatePerformanceOverlayVisibility() {
        val shouldShow = !backExitRequested &&
            config.showGamePerformanceOverlay &&
            jvmLaunchController.runtimeLifecycleReady &&
            bootOverlayController.isDismissed &&
            activity.hasWindowFocus()
        performanceOverlayController?.setVisible(shouldShow)
    }

    private fun updateSystemGameState() {
        val inForeground = activityResumed && !backExitRequested
        val isLoading = inForeground &&
            (!jvmLaunchController.runtimeLifecycleReady || !bootOverlayController.isDismissed)
        AndroidGameModeSupport.reportGameState(
            context = activity,
            isLoading = isLoading,
            inForeground = inForeground
        )
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

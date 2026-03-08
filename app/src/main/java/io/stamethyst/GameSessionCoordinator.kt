package io.stamethyst

import android.os.SystemClock
import android.view.KeyEvent
import android.widget.TextView
import io.stamethyst.backend.launch.BackExitNotice
import io.stamethyst.backend.launch.JvmLaunchController
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
    }

    @Volatile
    private var backExitRequested = false

    @Volatile
    private var backExitHardRestartTriggered = false

    private var waitingLandscapeSinceMs = -1L
    private var startCheckPosted = false

    private val bootOverlayController: BootOverlayController = BootOverlayController(
        activity = activity,
        manualDismissBootOverlay = config.manualDismissBootOverlay,
        useTextureViewSurface = config.useTextureViewSurface,
        onDismissed = {
            updateFloatingMouseVisibility()
            updatePerformanceOverlayVisibility()
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
        targetFps = config.targetFps,
        forceJvmCrash = config.forceJvmCrash,
        onProgressUpdate = { percent, message ->
            bootOverlayController.updateProgress(
                percent,
                bootOverlayController.mapLaunchProgressMessage(percent, message)
            )
        },
        onLaunchComplete = { exitCode -> handleJvmExit(exitCode) },
        onLaunchFailed = { throwable -> handleJvmLaunchFailed(throwable) },
        onRuntimeReady = {
            activity.runOnUiThread {
                applyForegroundWindowState()
                updateFloatingMouseVisibility()
                updatePerformanceOverlayVisibility()
            }
        },
        onSurfaceSizeSync = {
            renderSurfaceManager.updateWindowSize()
            renderSurfaceManager.logRenderInfo()
            renderSurfaceManager.syncDisplayConfigToSurfaceSize()
        },
        getWindowWidth = { CallbackBridge.windowWidth },
        getWindowHeight = { CallbackBridge.windowHeight }
    )

    private var performanceOverlayController: GamePerformanceOverlayController? = null

    fun initSessionUi(overlayView: TextView) {
        bootOverlayController.init()
        if (performanceOverlayController == null) {
            performanceOverlayController = GamePerformanceOverlayController(
                activity = activity,
                overlayView = overlayView,
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
        bootOverlayController.onDestroy()
        performanceOverlayController?.onDestroy()
        jvmLaunchController.cleanup()
    }

    fun onResume() {
        performanceOverlayController?.onResume()
        applyForegroundWindowState()
        updateFloatingMouseVisibility()
        updatePerformanceOverlayVisibility()
        tryStartJvmWhenSurfaceReady()
    }

    fun onPause() {
        performanceOverlayController?.onPause()
        applyBackgroundWindowState()
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        updatePerformanceOverlayVisibility()
        syncFocusStateToNative(hasFocus)
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
        if (backExitRequested) {
            activity.runOnUiThread { activity.finish() }
            return
        }

        if (exitCode == 0) {
            activity.runOnUiThread { activity.finish() }
        } else {
            activity.runOnUiThread { reportCrashAndReturn(exitCode, false, null) }
        }
    }

    private fun handleJvmLaunchFailed(throwable: Throwable) {
        if (backExitRequested) {
            activity.runOnUiThread { activity.finish() }
            return
        }
        val message = "${throwable.javaClass.simpleName}: ${throwable.message}"
        activity.runOnUiThread { reportCrashAndReturn(-1, false, message) }
    }

    private fun signalLaunchFailure(detail: String) {
        if (backExitRequested) {
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

    private fun requestBackExitToLauncher() {
        if (backExitRequested) {
            return
        }
        backExitRequested = true
        val bootOverlayActive = !bootOverlayController.isDismissed

        inputHandler.hideSoftKeyboard()
        inputHandler.resetGamepadState()
        updateFloatingMouseVisibility()
        updatePerformanceOverlayVisibility()
        BackExitNotice.markExpectedBackExit(activity)

        bootOverlayController.updateProgress(100, "Stopping game...")

        jvmLaunchController.interrupt()

        if (!jvmLaunchController.runtimeLifecycleReady) {
            LauncherReturnCoordinator.returnToLauncher(activity)
            return
        }

        try {
            CallbackBridge.nativeSetInputReady(false)
        } catch (_: Throwable) {
        }

        val closeRequested = requestJvmCloseSignal()
        if (bootOverlayActive) {
            LauncherReturnCoordinator.returnToLauncher(activity)
            return
        }
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
        renderSurfaceManager.renderView.postDelayed({
            if (backExitRequested) {
                forceRestartLauncherAndTerminateProcess()
            }
        }, BACK_FORCE_KILL_FALLBACK_MS)
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
        LauncherReturnCoordinator.scheduleLauncherRestart(
            context = activity,
            delayMs = BACK_FORCE_RESTART_DELAY_MS,
            markExpectedBackExitRestart = true
        )
        activity.finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun reportCrashAndReturn(code: Int, isSignal: Boolean, detail: String?) {
        if (backExitRequested) {
            activity.finish()
            return
        }
        LauncherReturnCoordinator.showCrashAndFinish(activity, code, isSignal, detail)
    }

    private fun applyForegroundWindowState() {
        if (!jvmLaunchController.runtimeLifecycleReady) {
            return
        }
        try {
            CallbackBridge.nativeSetInputReady(true)
            CallbackBridge.nativeSetAudioMuted(false)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_ICONIFIED, 0)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 1)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1)
        } catch (_: Throwable) {
        }
    }

    private fun applyBackgroundWindowState() {
        if (!jvmLaunchController.runtimeLifecycleReady) {
            return
        }
        try {
            CallbackBridge.nativeSetInputReady(false)
            CallbackBridge.nativeSetAudioMuted(true)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_ICONIFIED, 1)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 0)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 0)
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
}

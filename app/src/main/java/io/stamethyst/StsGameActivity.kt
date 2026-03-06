package io.stamethyst

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import io.stamethyst.backend.launch.BackExitNotice
import io.stamethyst.backend.launch.JvmLaunchController
import io.stamethyst.backend.launch.StsLaunchSpec
import io.stamethyst.backend.runtime.RuntimePackInstaller
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.LauncherConfig
import io.stamethyst.input.GameInputHandler
import net.kdt.pojavlaunch.LwjglGlfwKeycode
import org.lwjgl.glfw.CallbackBridge
import java.io.File
import kotlin.system.exitProcess

class StsGameActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_LAUNCH_MODE = "io.stamethyst.launch_mode"
        const val EXTRA_BACK_BEHAVIOR = "io.stamethyst.back_behavior"
        const val EXTRA_BACK_IMMEDIATE_EXIT = "io.stamethyst.back_immediate_exit"
        const val EXTRA_MANUAL_DISMISS_BOOT_OVERLAY = "io.stamethyst.manual_dismiss_boot_overlay"
        const val EXTRA_FORCE_JVM_CRASH = "io.stamethyst.force_jvm_crash"
        const val EXTRA_TARGET_FPS = "io.stamethyst.target_fps"
        private const val BACK_FORCE_RESTART_DELAY_MS = 120L

        @JvmStatic
        fun launch(
            context: Context,
            launchMode: String,
            targetFps: Int,
            backBehavior: BackBehavior,
            manualDismissBootOverlay: Boolean,
            forceJvmCrash: Boolean = false
        ) {
            val intent = Intent(context, StsGameActivity::class.java)
            intent.putExtra(EXTRA_LAUNCH_MODE, launchMode)
            intent.putExtra(EXTRA_TARGET_FPS, targetFps)
            intent.putExtra(EXTRA_BACK_BEHAVIOR, backBehavior.persistedValue)
            intent.putExtra(
                EXTRA_BACK_IMMEDIATE_EXIT,
                backBehavior == BackBehavior.EXIT_TO_LAUNCHER
            )
            intent.putExtra(EXTRA_MANUAL_DISMISS_BOOT_OVERLAY, manualDismissBootOverlay)
            intent.putExtra(EXTRA_FORCE_JVM_CRASH, forceJvmCrash)
            context.startActivity(intent)
        }
    }

    // Configuration
    private var renderScale = LauncherConfig.DEFAULT_RENDER_SCALE
    private var targetFps = LauncherConfig.DEFAULT_TARGET_FPS
    private var launchMode = StsLaunchSpec.LAUNCH_MODE_VANILLA
    private var backBehavior = LauncherConfig.DEFAULT_BACK_BEHAVIOR
    private var manualDismissBootOverlay = false
    private var forceJvmCrash = false
    private var showFloatingMouseWindow = LauncherConfig.DEFAULT_SHOW_FLOATING_MOUSE_WINDOW
    private var longPressMouseShowsKeyboard = LauncherConfig.DEFAULT_LONG_PRESS_MOUSE_SHOWS_KEYBOARD
    private var autoSwitchLeftAfterRightClick = LauncherConfig.DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK
    private var useTextureViewSurface = false

    // State
    @Volatile
    private var backExitRequested = false
    private var waitingLandscapeSinceMs = -1L
    private var startCheckPosted = false

    // Controllers
    private lateinit var renderSurfaceManager: RenderSurfaceManager
    private lateinit var inputHandler: GameInputHandler
    private lateinit var bootOverlayController: BootOverlayController
    private lateinit var jvmLaunchController: JvmLaunchController
    private var onBackInvokedCallback: OnBackInvokedCallback? = null

    private val gameBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            handleAndroidBackPressed()
        }
    }

    // ==================== Activity Lifecycle ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)
        setVolumeControlStream(AudioManager.STREAM_MUSIC)

        parseIntentExtras()
        initControllers()
        initViews()
        registerSystemBackInvokedCallback()
    }

    override fun onDestroy() {
        unregisterSystemBackInvokedCallback()
        inputHandler.onDestroy()
        renderSurfaceManager.onDestroy()
        bootOverlayController.onDestroy()
        jvmLaunchController.cleanup()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        renderSurfaceManager.applyImmersiveMode()
        inputHandler.resetGamepadState()
        renderSurfaceManager.resyncAfterForeground()
        applyForegroundWindowState()
        updateFloatingMouseVisibility()
        tryStartJvmWhenSurfaceReady()
    }

    override fun onPause() {
        inputHandler.resetGamepadState()
        inputHandler.hideSoftKeyboard()
        applyBackgroundWindowState()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            renderSurfaceManager.applyImmersiveMode()
            renderSurfaceManager.resyncAfterForeground()
        }
        syncFocusStateToNative(hasFocus)
    }

    // ==================== Initialization ====================

    private fun parseIntentExtras() {
        renderScale = LauncherConfig.readRenderScale(this)
        val requestedMode = intent.getStringExtra(EXTRA_LAUNCH_MODE)
        if (StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD == requestedMode) {
            launchMode = StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD
        }
        backBehavior = parseBackBehaviorExtra()
        manualDismissBootOverlay = intent.getBooleanExtra(
            EXTRA_MANUAL_DISMISS_BOOT_OVERLAY,
            LauncherConfig.DEFAULT_MANUAL_DISMISS_BOOT_OVERLAY
        )
        forceJvmCrash = intent.getBooleanExtra(EXTRA_FORCE_JVM_CRASH, false)
        showFloatingMouseWindow = LauncherConfig.readShowFloatingMouseWindow(this)
        longPressMouseShowsKeyboard = LauncherConfig.readLongPressMouseShowsKeyboard(this)
        autoSwitchLeftAfterRightClick = LauncherConfig.readAutoSwitchLeftAfterRightClick(this)
        targetFps = LauncherConfig.normalizeTargetFps(
            intent.getIntExtra(EXTRA_TARGET_FPS, LauncherConfig.DEFAULT_TARGET_FPS)
        )
    }

    private fun initControllers() {
        bootOverlayController = BootOverlayController(
            activity = this,
            manualDismissBootOverlay = manualDismissBootOverlay,
            useTextureViewSurface = useTextureViewSurface,
            onDismissed = { updateFloatingMouseVisibility() },
            onRequestEarlyDismiss = {
                bootOverlayController.setEarlyDismissRequestTimestamp(
                    renderSurfaceManager.getLastTextureFrameTimestampNs()
                )
            },
            onSignalLaunchFailure = { detail -> signalLaunchFailure(detail) }
        )

        jvmLaunchController = JvmLaunchController(
            activity = this,
            launchMode = launchMode,
            targetFps = targetFps,
            forceJvmCrash = forceJvmCrash,
            onProgressUpdate = { percent, message ->
                bootOverlayController.updateProgress(
                    percent,
                    bootOverlayController.mapLaunchProgressMessage(percent, message)
                )
            },
            onLaunchComplete = { exitCode -> handleJvmExit(exitCode) },
            onLaunchFailed = { t -> handleJvmLaunchFailed(t) },
            onRuntimeReady = {
                runOnUiThread {
                    applyForegroundWindowState()
                    updateFloatingMouseVisibility()
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

        inputHandler = GameInputHandler(
            activity = this,
            isInputDispatchReady = { isNativeInputDispatchReady() },
            requestRenderViewFocus = {
                if (::renderSurfaceManager.isInitialized) {
                    renderSurfaceManager.requestRenderViewFocus()
                }
            },
            getRenderViewWidth = {
                if (::renderSurfaceManager.isInitialized) {
                    renderSurfaceManager.getRenderViewWidth()
                } else 0
            },
            getRenderViewHeight = {
                if (::renderSurfaceManager.isInitialized) {
                    renderSurfaceManager.getRenderViewHeight()
                } else 0
            }
        )

        renderSurfaceManager = RenderSurfaceManager(
            activity = this,
            renderScale = renderScale,
            targetFps = targetFps,
            useTextureViewSurface = useTextureViewSurface,
            onSurfaceReady = { tryStartJvmWhenSurfaceReady() },
            onSurfaceDestroyed = { },
            onTextureFrameUpdate = { timestampNs ->
                bootOverlayController.onTextureFrameUpdate(timestampNs)
            }
        )
    }

    private fun initViews() {
        onBackPressedDispatcher.addCallback(this, gameBackPressedCallback)

        val root = findViewById<FrameLayout>(R.id.gameRoot)
        renderSurfaceManager.init(root)

        bootOverlayController.init()

        val host = findViewById<FrameLayout>(R.id.gameHost)
        inputHandler.initFloatingMouseControls(
            host = host,
            autoSwitchLeftAfterRightClick = autoSwitchLeftAfterRightClick,
            longPressMouseShowsKeyboard = longPressMouseShowsKeyboard
        )
        updateFloatingMouseVisibility()

        renderSurfaceManager.renderView.setOnTouchListener { _, event -> inputHandler.handleTouchEvent(event) }
        renderSurfaceManager.renderView.requestFocus()
    }

    // ==================== JVM Launch ====================

    private fun tryStartJvmWhenSurfaceReady() {
        if (backExitRequested || jvmLaunchController.vmStarted) return

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
            if (backExitRequested) finish()
            return
        }

        val runtimeRoot = RuntimePaths.runtimeRoot(this)
        val javaHome = RuntimePackInstaller.locateJavaHome(runtimeRoot) ?: File(runtimeRoot, "jre")

        jvmLaunchController.start(
            javaHome = javaHome,
            bootOverlayController = bootOverlayController
        )
    }

    private fun scheduleStartCheck() {
        if (jvmLaunchController.vmStarted || startCheckPosted) return
        startCheckPosted = true
        renderSurfaceManager.renderView.postDelayed({
            startCheckPosted = false
            tryStartJvmWhenSurfaceReady()
        }, 120L)
    }

    private fun handleJvmExit(exitCode: Int) {
        if (backExitRequested) {
            runOnUiThread { finish() }
            return
        }

        if (exitCode == 0) {
            runOnUiThread { finish() }
        } else {
            runOnUiThread { reportCrashAndReturn(exitCode, false, null) }
        }
    }

    private fun handleJvmLaunchFailed(t: Throwable) {
        if (backExitRequested) {
            runOnUiThread { finish() }
            return
        }
        val message = "${t.javaClass.simpleName}: ${t.message}"
        runOnUiThread { reportCrashAndReturn(-1, false, message) }
    }

    // ==================== Launch Failure Handling ====================

    private fun signalLaunchFailure(detail: String) {
        if (backExitRequested) {
            runOnUiThread { finish() }
            return
        }

        val crashCode = if (detail.lowercase().contains("outofmemory")) {
            JvmLaunchController.CRASH_CODE_OUT_OF_MEMORY
        } else {
            JvmLaunchController.CRASH_CODE_BOOT_FAILURE
        }

        runOnUiThread { reportCrashAndReturn(crashCode, false, detail) }
    }

    // ==================== Back Press Handling ====================

    private fun handleAndroidBackPressed() {
        when (backBehavior) {
            BackBehavior.EXIT_TO_LAUNCHER -> requestBackExitToLauncher()
            BackBehavior.SEND_ESCAPE -> sendEscapeKeyToGame()
            BackBehavior.NONE -> Unit
        }
    }

    private fun handleAndroidBackKeyEvent(event: KeyEvent): Boolean {
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

    private fun requestBackExitToLauncher() {
        if (backExitRequested) return
        backExitRequested = true

        inputHandler.hideSoftKeyboard()
        inputHandler.resetGamepadState()
        updateFloatingMouseVisibility()
        BackExitNotice.markExpectedBackExit(this)

        bootOverlayController.updateProgress(100, "Stopping game...")

        jvmLaunchController.interrupt()

        try {
            CallbackBridge.nativeSetInputReady(false)
        } catch (_: Throwable) {}

        requestJvmCloseSignal()
        scheduleLauncherRestartAndKillProcess()
    }

    private fun registerSystemBackInvokedCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (onBackInvokedCallback != null) {
            return
        }
        val callback = OnBackInvokedCallback {
            handleAndroidBackPressed()
        }
        try {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
            onBackInvokedCallback = callback
        } catch (_: Throwable) {}
    }

    private fun unregisterSystemBackInvokedCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val callback = onBackInvokedCallback ?: return
        try {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        } catch (_: Throwable) {}
        onBackInvokedCallback = null
    }

    private fun requestJvmCloseSignal(): Boolean {
        return try {
            CallbackBridge.nativeRequestCloseWindow()
        } catch (_: Throwable) {
            false
        }
    }

    private fun parseBackBehaviorExtra(): BackBehavior {
        val parsedBehavior = BackBehavior.fromPersistedValue(
            intent.getStringExtra(EXTRA_BACK_BEHAVIOR)
        )
        if (parsedBehavior != null) {
            return parsedBehavior
        }
        if (intent.hasExtra(EXTRA_BACK_IMMEDIATE_EXIT)) {
            val immediateExit = intent.getBooleanExtra(
                EXTRA_BACK_IMMEDIATE_EXIT,
                LauncherConfig.DEFAULT_BACK_IMMEDIATE_EXIT
            )
            return if (immediateExit) {
                BackBehavior.EXIT_TO_LAUNCHER
            } else {
                BackBehavior.NONE
            }
        }
        return LauncherConfig.DEFAULT_BACK_BEHAVIOR
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

    private fun scheduleLauncherRestartAndKillProcess() {
        val launcherIntent = Intent(this, LauncherActivity::class.java)
        launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0x71A7, launcherIntent, flags)
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager?
        val triggerAt = SystemClock.elapsedRealtime() + BACK_FORCE_RESTART_DELAY_MS
        val scheduledByAlarm = if (alarmManager != null) {
            scheduleLauncherRestart(alarmManager, triggerAt, pendingIntent)
        } else {
            false
        }
        if (!scheduledByAlarm) {
            try {
                pendingIntent.send()
            } catch (_: PendingIntent.CanceledException) {}
        }
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(0)
    }

    @SuppressLint("MissingPermission")
    private fun scheduleLauncherRestart(
        alarmManager: AlarmManager,
        triggerAt: Long,
        pendingIntent: PendingIntent
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            }
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    // ==================== Crash Reporting ====================

    private fun reportCrashAndReturn(code: Int, isSignal: Boolean, detail: String?) {
        if (backExitRequested) {
            finish()
            return
        }

        val launcherIntent = Intent(this, LauncherActivity::class.java)
        launcherIntent.putExtra(LauncherActivity.EXTRA_CRASH_OCCURRED, true)
        launcherIntent.putExtra(LauncherActivity.EXTRA_CRASH_CODE, code)
        launcherIntent.putExtra(LauncherActivity.EXTRA_CRASH_IS_SIGNAL, isSignal)
        if (!detail.isNullOrBlank()) {
            launcherIntent.putExtra(LauncherActivity.EXTRA_CRASH_DETAIL, detail.trim())
        }
        launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(launcherIntent)
        finish()
    }

    // ==================== Input Event Dispatch ====================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return inputHandler.handleTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return inputHandler.handleGenericMotionEvent(event) || super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_BACK && handleAndroidBackKeyEvent(event)) {
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            return inputHandler.handleVolumeKeyEvent(event)
        }
        if (inputHandler.isGamepadKeyEvent(event)) {
            return inputHandler.handleGamepadKeyEvent(event)
        }
        if (inputHandler.dispatchKeyboardEventToGame(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ==================== Window State ====================

    private fun isNativeInputDispatchReady(): Boolean {
        if (backExitRequested) return false
        if (!jvmLaunchController.runtimeLifecycleReady) return false
        if (!renderSurfaceManager.bridgeSurfaceReady) return false
        return CallbackBridge.windowWidth > 0 && CallbackBridge.windowHeight > 0
    }

    private fun applyForegroundWindowState() {
        if (!jvmLaunchController.runtimeLifecycleReady) return
        try {
            CallbackBridge.nativeSetInputReady(true)
            CallbackBridge.nativeSetAudioMuted(false)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_ICONIFIED, 0)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 1)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1)
        } catch (_: Throwable) {}
    }

    private fun applyBackgroundWindowState() {
        if (!jvmLaunchController.runtimeLifecycleReady) return
        try {
            CallbackBridge.nativeSetInputReady(false)
            CallbackBridge.nativeSetAudioMuted(true)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_ICONIFIED, 1)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 0)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 0)
        } catch (_: Throwable) {}
    }

    private fun syncFocusStateToNative(hasFocus: Boolean) {
        if (!jvmLaunchController.runtimeLifecycleReady) return
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
        } catch (_: Throwable) {}
    }

    private fun updateFloatingMouseVisibility() {
        inputHandler.updateFloatingMouseVisibility(
            showFloatingMouseWindow,
            jvmLaunchController.runtimeLifecycleReady,
            bootOverlayController.isDismissed,
            backExitRequested
        )
    }
}

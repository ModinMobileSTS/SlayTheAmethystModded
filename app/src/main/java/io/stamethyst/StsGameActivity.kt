package io.stamethyst

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import io.stamethyst.backend.audio.GameAudioController
import io.stamethyst.backend.diag.MemoryDiagnosticsLogger
import io.stamethyst.backend.easytier.EasyTierGameProcessPriorityBinding
import io.stamethyst.backend.launch.GameProcessLaunchGuard
import io.stamethyst.backend.launch.StartupTraceEvents
import io.stamethyst.backend.presence.GamePresenceStateMarker
import io.stamethyst.backend.steamcloud.SteamGamePresenceService
import io.stamethyst.backend.render.DisplayPerformanceController
import io.stamethyst.backend.launch.AutoplayMode
import io.stamethyst.backend.launch.AutoplaySaveMode
import io.stamethyst.backend.launch.StsLaunchSpec
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.CloudControlConfig
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import io.stamethyst.input.GameInputHandler
import java.io.FileOutputStream
import java.util.UUID
import org.lwjgl.glfw.CallbackBridge

class StsGameActivity : AppCompatActivity(), SensorEventListener {
    companion object {
        private const val GYROSCOPE_LOG_TAG = "STS-Gyroscope"
        const val EXTRA_LAUNCH_MODE = "io.stamethyst.launch_mode"
        const val EXTRA_BACK_BEHAVIOR = "io.stamethyst.back_behavior"
        const val EXTRA_BACK_IMMEDIATE_EXIT = "io.stamethyst.back_immediate_exit"
        const val EXTRA_MANUAL_DISMISS_BOOT_OVERLAY = "io.stamethyst.manual_dismiss_boot_overlay"
        const val EXTRA_FORCE_JVM_CRASH = "io.stamethyst.force_jvm_crash"
        const val EXTRA_FORCE_RUNTIME_CRASH = "io.stamethyst.force_runtime_crash"
        const val EXTRA_DEBUG_MODE = "io.stamethyst.debug_mode"
        const val EXTRA_AUTOPLAY = "io.stamethyst.autoplay"
        const val EXTRA_AUTOPLAY_SAVE_MODE = "io.stamethyst.autoplay_save_mode"
        const val EXTRA_AUTOPLAY_MODE = "io.stamethyst.autoplay_mode"
        const val EXTRA_AUTOPLAY_SINGLE_ROOM_SPEC = "io.stamethyst.autoplay_single_room_spec"
        const val EXTRA_AUTOPLAY_SINGLE_ROOM_BENCH_MODE = "io.stamethyst.autoplay_single_room_bench_mode"
        const val EXTRA_AUTOPLAY_CHOICE_DELAY_MS = "io.stamethyst.autoplay_choice_delay_ms"
        const val EXTRA_PERFORMANCE_DEEP_DIAGNOSTICS = "io.stamethyst.performance_deep_diagnostics"
        const val EXTRA_CARD_OBTAIN_EFFECT_OWNERSHIP_COMPAT_ENABLED =
            "io.stamethyst.card_obtain_effect_ownership_compat_enabled"

        @JvmStatic
        fun launch(
            context: Context,
            launchMode: String,
            backBehavior: BackBehavior,
            manualDismissBootOverlay: Boolean,
            forceJvmCrash: Boolean = false,
            forceRuntimeCrash: Boolean = false,
            autoplay: Boolean = false,
            autoplaySaveMode: AutoplaySaveMode = AutoplaySaveMode.DEFAULT,
            autoplayMode: AutoplayMode = AutoplayMode.DEFAULT,
            autoplaySingleRoomSpecPath: String = "",
            autoplayChoiceDelayMs: Long = 0L,
            autoplaySingleRoomBenchMode: Boolean = false,
            performanceDeepDiagnostics: Boolean? = null,
            cardObtainEffectOwnershipCompatEnabled: Boolean = true,
            debugMode: Boolean = false
        ) {
            val intent = Intent(context, StsGameActivity::class.java)
            intent.putExtra(EXTRA_LAUNCH_MODE, launchMode)
            intent.putExtra(EXTRA_BACK_BEHAVIOR, backBehavior.persistedValue)
            intent.putExtra(
                EXTRA_BACK_IMMEDIATE_EXIT,
                backBehavior == BackBehavior.EXIT_TO_LAUNCHER
            )
            intent.putExtra(EXTRA_MANUAL_DISMISS_BOOT_OVERLAY, manualDismissBootOverlay)
            intent.putExtra(EXTRA_FORCE_JVM_CRASH, forceJvmCrash)
            intent.putExtra(EXTRA_FORCE_RUNTIME_CRASH, forceRuntimeCrash)
            intent.putExtra(EXTRA_DEBUG_MODE, debugMode)
            intent.putExtra(EXTRA_AUTOPLAY, autoplay)
            intent.putExtra(EXTRA_AUTOPLAY_SAVE_MODE, autoplaySaveMode.persistedValue)
            intent.putExtra(EXTRA_AUTOPLAY_MODE, autoplayMode.persistedValue)
            intent.putExtra(EXTRA_AUTOPLAY_SINGLE_ROOM_SPEC, autoplaySingleRoomSpecPath)
            intent.putExtra(EXTRA_AUTOPLAY_CHOICE_DELAY_MS, autoplayChoiceDelayMs)
            intent.putExtra(EXTRA_AUTOPLAY_SINGLE_ROOM_BENCH_MODE, autoplaySingleRoomBenchMode)
            if (performanceDeepDiagnostics != null) {
                intent.putExtra(EXTRA_PERFORMANCE_DEEP_DIAGNOSTICS, performanceDeepDiagnostics)
            }
            intent.putExtra(
                EXTRA_CARD_OBTAIN_EFFECT_OWNERSHIP_COMPAT_ENABLED,
                cardObtainEffectOwnershipCompatEnabled
            )
            context.startActivity(intent)
        }
    }

    private lateinit var sessionConfig: GameSessionConfig
    private lateinit var renderSurfaceManager: RenderSurfaceManager
    private lateinit var inputHandler: GameInputHandler
    private lateinit var sessionCoordinator: GameSessionCoordinator
    private lateinit var gameAudioController: GameAudioController
    private val keepScreenOnHandler = Handler(Looper.getMainLooper())
    private val keepScreenOnIdleRunnable = Runnable {
        keepScreenOnActive = false
        updateKeepScreenOnFlag()
    }
    private var onBackInvokedCallback: OnBackInvokedCallback? = null
    private var bootOverlayKeepScreenOn = false
    private var keepScreenOnActive = false
    private var activityForeground = false
    private var gameSessionFinished = false
    private val launchGuardToken: String = UUID.randomUUID().toString()
    private val launchGuardLock = Any()
    private var launchGuardAcquired = false
    private var pendingFilePickerRequestId: String? = null
    private var gyroscopeSensorManager: SensorManager? = null
    private var gyroscopeSensor: Sensor? = null
    private var gyroscopeRegistered = false
    private var gyroscopeSensorEventCount = 0L
    private var gyroscopeForwardCount = 0L
    private var gyroscopeRegistrationLogged = false
    private var gyroscopeSensorMissingLogged = false
    private var gyroscopeForwardFailureLogged = false
    private var gyroscopeFirstForwardLogged = false

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        handleFilePickerResult(uri)
    }

    private val gameBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            sessionCoordinator.handleAndroidBackPressed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupBackground = StartupWindowBackground.gameColor(this)
        StartupWindowBackground.applyToWindow(window, startupBackground)
        super.onCreate(savedInstanceState)
        StartupTraceEvents.append(
            this,
            "game_activity_on_create",
            mapOf("launchMode" to intent.getStringExtra(EXTRA_LAUNCH_MODE).orEmpty())
        )
        StartupWindowBackground.applyToDecorView(window, startupBackground)
        launchGuardAcquired = GameProcessLaunchGuard.tryAcquire(launchGuardToken)
        if (!launchGuardAcquired) {
            MemoryDiagnosticsLogger.logEvent(
                this,
                "game_activity_launch_guard_rejected",
                mapOf("sessionToken" to launchGuardToken)
            )
            finish()
            return
        }
        // The game Activity runs in :game, which has its own CloudControlConfig singleton.
        CloudControlConfig.refreshOnAppStart(applicationContext)
        // Holds :easytier at this process's LMK priority so a low-memory device reclaims the game
        // before it silently kills the virtual network underneath a running session.
        EasyTierGameProcessPriorityBinding.attach(this)
        setContentView(R.layout.activity_game)
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
        GameOrientationPolicy.apply(this, isInMultiWindowMode)

        sessionConfig = GameSessionConfig.fromActivityIntent(this, intent)
        GamePresenceStateMarker.markGameActive(this, sessionConfig.launchMode)
        RuntimePaths.richPresenceFile(this).delete()
        MemoryDiagnosticsLogger.logEvent(
            this,
            "game_activity_created",
            buildMemoryEventExtras()
        )
        initControllers()
        renderSurfaceManager.applyImmersiveMode()
        initViews()
        registerSystemBackInvokedCallback()
    }

    override fun onDestroy() {
        val jvmWasStarted = ::sessionCoordinator.isInitialized && sessionCoordinator.jvmLaunchStarted
        unregisterGyroscope()
        MemoryDiagnosticsLogger.logEvent(
            this,
            "game_activity_destroyed",
            buildMemoryEventExtras()
        )
        unregisterSystemBackInvokedCallback()
        DisplayPerformanceController.applySustainedPerformanceMode(this, false)
        if (::gameAudioController.isInitialized) {
            gameAudioController.onDestroy()
        }
        if (::inputHandler.isInitialized) {
            inputHandler.onDestroy()
        }
        if (::renderSurfaceManager.isInitialized) {
            renderSurfaceManager.onDestroy()
        }
        if (::sessionCoordinator.isInitialized) {
            sessionCoordinator.onDestroy()
        }
        EasyTierGameProcessPriorityBinding.detach(this)
        if (!jvmWasStarted || gameSessionFinished) {
            markGameSessionFinished()
        }
        if (launchGuardAcquired && !jvmWasStarted) {
            releaseLaunchGuard()
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        registerGyroscope()
        StartupTraceEvents.append(
            this,
            "game_activity_on_resume",
            mapOf(
                "launchMode" to if (::sessionConfig.isInitialized) {
                    sessionConfig.launchMode
                } else {
                    intent.getStringExtra(EXTRA_LAUNCH_MODE).orEmpty()
                }
            )
        )
        MemoryDiagnosticsLogger.logEvent(
            this,
            "game_activity_resumed",
            buildMemoryEventExtras()
        )
        DisplayPerformanceController.applySustainedPerformanceMode(
            this,
            sessionConfig.sustainedPerformanceModeEnabled
        )
        renderSurfaceManager.applyImmersiveMode()
        inputHandler.resetGamepadState()
        gameAudioController.onResume()
        renderSurfaceManager.onForegroundChanged(true)
        sessionCoordinator.onResume()
        activityForeground = true
        GamePresenceStateMarker.markGameActive(this, sessionConfig.launchMode)
        resetKeepScreenOnIdleTimer()
    }

    override fun onPause() {
        unregisterGyroscope()
        MemoryDiagnosticsLogger.logEvent(
            this,
            "game_activity_paused",
            buildMemoryEventExtras()
        )
        // A multi-window session stays visible after onPause, so audio and the render surface are
        // only torn down in onStop; the coordinator decides whether this pause means "hidden".
        val stillVisible = isInMultiWindowMode
        inputHandler.resetGamepadState()
        inputHandler.hideSoftKeyboard()
        if (!stillVisible) {
            gameAudioController.onPause()
        }
        sessionCoordinator.onPause()
        if (!stillVisible) {
            renderSurfaceManager.onForegroundChanged(false)
        }
        DisplayPerformanceController.applySustainedPerformanceMode(this, false)
        activityForeground = stillVisible
        if (stillVisible) {
            // Re-arm the idle timeout instead of cancelling it, otherwise a visible small window
            // would hold FLAG_KEEP_SCREEN_ON forever while the user works in the other app.
            resetKeepScreenOnIdleTimer()
        } else {
            keepScreenOnHandler.removeCallbacks(keepScreenOnIdleRunnable)
            updateKeepScreenOnFlag()
        }
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE || event.values.size < 3) {
            return
        }
        gyroscopeSensorEventCount += 1L
        if (gyroscopeSensorEventCount == 1L || gyroscopeSensorEventCount % 120L == 0L) {
            Log.i(
                GYROSCOPE_LOG_TAG,
                "sensor_event count=$gyroscopeSensorEventCount x=${event.values[0]} y=${event.values[1]} z=${event.values[2]}"
            )
        }
        forwardGyroscope(event.values[0], event.values[1], event.values[2])
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Gyroscope accuracy does not change how raw angular velocity is forwarded.
    }

    private fun registerGyroscope() {
        if (gyroscopeRegistered) {
            return
        }
        // Only register gyroscope if FirstPersonView mod is enabled
        if (!isFirstPersonViewModEnabled()) {
            if (!gyroscopeSensorMissingLogged) {
                Log.i(GYROSCOPE_LOG_TAG, "gyroscope_disabled_firstperson_mod_not_enabled")
                gyroscopeSensorMissingLogged = true
            }
            return
        }
        val manager = gyroscopeSensorManager ?:
            (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.also {
                gyroscopeSensorManager = it
            } ?: run {
                if (!gyroscopeSensorMissingLogged) {
                    Log.w(GYROSCOPE_LOG_TAG, "sensor_manager_unavailable")
                    gyroscopeSensorMissingLogged = true
                }
                return
            }
        val sensor = gyroscopeSensor ?: manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.also {
            gyroscopeSensor = it
        } ?: run {
            if (!gyroscopeSensorMissingLogged) {
                Log.w(GYROSCOPE_LOG_TAG, "gyroscope_sensor_unavailable")
                gyroscopeSensorMissingLogged = true
            }
            return
        }
        forwardGyroscope(0f, 0f, 0f)
        gyroscopeRegistered = try {
            manager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            ).also { registered ->
                if (!gyroscopeRegistrationLogged) {
                    Log.i(
                        GYROSCOPE_LOG_TAG,
                        "register_listener result=$registered name=${sensor.name} vendor=${sensor.vendor}"
                    )
                    gyroscopeRegistrationLogged = true
                }
            }
        } catch (error: Throwable) {
            if (!gyroscopeRegistrationLogged) {
                Log.w(GYROSCOPE_LOG_TAG, "register_listener_failed", error)
                gyroscopeRegistrationLogged = true
            }
            false
        }
    }

    private fun isFirstPersonViewModEnabled(): Boolean {
        return try {
            val enabledModIds = io.stamethyst.backend.mods.ModManager.listEnabledOptionalModIds(this)
            enabledModIds.contains("firstperson")
        } catch (error: Throwable) {
            Log.w(GYROSCOPE_LOG_TAG, "failed_to_check_firstperson_mod", error)
            false
        }
    }

    private fun unregisterGyroscope() {
        runCatching {
            gyroscopeSensorManager?.unregisterListener(this)
        }
        gyroscopeRegistered = false
        forwardGyroscope(0f, 0f, 0f)
    }

    private fun forwardGyroscope(x: Float, y: Float, z: Float) {
        // Sensor delivery must not take down the Activity when an older native bridge is loaded.
        try {
            CallbackBridge.nativeSetGyroscope(x, y, z)
            gyroscopeForwardCount += 1L
            if (!gyroscopeFirstForwardLogged) {
                Log.i(
                    GYROSCOPE_LOG_TAG,
                    "native_forward_ok count=$gyroscopeForwardCount x=$x y=$y z=$z"
                )
                gyroscopeFirstForwardLogged = true
            }
        } catch (error: Throwable) {
            if (!gyroscopeForwardFailureLogged) {
                Log.w(GYROSCOPE_LOG_TAG, "native_forward_failed count=$gyroscopeForwardCount", error)
                gyroscopeForwardFailureLogged = true
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::sessionCoordinator.isInitialized) {
            sessionCoordinator.onStart()
        }
    }

    override fun onStop() {
        MemoryDiagnosticsLogger.logEvent(
            this,
            "game_activity_stopped",
            buildMemoryEventExtras()
        )
        // Leaving the screen is the point where the runtime may stop rendering; onPause alone can
        // mean "still visible beside another app" in multi-window.
        if (::sessionCoordinator.isInitialized) {
            sessionCoordinator.onStop()
        }
        if (::renderSurfaceManager.isInitialized) {
            renderSurfaceManager.onForegroundChanged(false)
        }
        if (::gameAudioController.isInitialized) {
            gameAudioController.onPause()
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        renderSurfaceManager.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            renderSurfaceManager.applyImmersiveMode()
        }
        sessionCoordinator.onWindowFocusChanged(hasFocus)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        GameOrientationPolicy.apply(this, isInMultiWindowMode)
        if (::renderSurfaceManager.isInitialized) {
            renderSurfaceManager.onWindowConfigurationChanged("window_configuration")
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        GameOrientationPolicy.apply(this, isInMultiWindowMode)
        if (::renderSurfaceManager.isInitialized) {
            renderSurfaceManager.onWindowConfigurationChanged("multi_window_mode")
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        GameOrientationPolicy.apply(this, isInMultiWindowMode)
        if (::renderSurfaceManager.isInitialized) {
            renderSurfaceManager.onWindowConfigurationChanged("multi_window_mode")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        MemoryDiagnosticsLogger.logLowMemory(
            this,
            "game_activity",
            buildMemoryEventExtras()
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MemoryDiagnosticsLogger.logTrimMemory(
            this,
            "game_activity",
            level,
            buildMemoryEventExtras()
        )
    }

    private fun initControllers() {
        inputHandler = GameInputHandler(
            activity = this,
            isInputDispatchReady = { sessionCoordinator.isInputDispatchReady() },
            requestRenderViewFocus = {
                if (::renderSurfaceManager.isInitialized) {
                    renderSurfaceManager.requestRenderViewFocus()
                }
            },
            getRenderViewWidth = {
                if (::renderSurfaceManager.isInitialized) {
                    renderSurfaceManager.getRenderViewWidth()
                } else {
                    0
                }
            },
            getRenderViewHeight = {
                if (::renderSurfaceManager.isInitialized) {
                    renderSurfaceManager.getRenderViewHeight()
                } else {
                    0
                }
            },
            getTargetWindowWidth = {
                if (::renderSurfaceManager.isInitialized) {
                    renderSurfaceManager.resolveVirtualWidth()
                } else {
                    0
                }
            },
            getTargetWindowHeight = {
                if (::renderSurfaceManager.isInitialized) {
                    renderSurfaceManager.resolveVirtualHeight()
                } else {
                    0
                }
            }
        )

        renderSurfaceManager = RenderSurfaceManager(
            activity = this,
            renderScale = sessionConfig.renderScale,
            targetFpsLimit = sessionConfig.effectiveTargetFps,
            useTextureViewSurface = sessionConfig.useTextureViewSurface,
            virtualResolutionMode = sessionConfig.virtualResolutionMode,
            avoidDisplayCutout = sessionConfig.avoidDisplayCutout,
            cropScreenBottom = sessionConfig.cropScreenBottom,
            isSoftKeyboardSessionActive = { inputHandler.isSoftKeyboardSessionActive() },
            onSurfaceReady = { sessionCoordinator.onSurfaceReady() },
            onTextureFrameUpdate = { timestampNs ->
                sessionCoordinator.onTextureFrameUpdate(timestampNs)
            }
        )

        sessionCoordinator = GameSessionCoordinator(
            activity = this,
            config = sessionConfig,
            renderSurfaceManager = renderSurfaceManager,
            inputHandler = inputHandler,
            onJvmLaunchFinished = {
                markGameSessionFinished()
                releaseLaunchGuard()
            }
        )
        gameAudioController = GameAudioController(
            activity = this,
            onAudioFocusGrantedChanged = { granted ->
                sessionCoordinator.onPlatformAudioFocusChanged(granted)
            },
            onAudioOutputRouteChanged = {
                sessionCoordinator.onAudioOutputRouteChanged()
            }
        )
    }

    private fun markGameSessionFinished() {
        if (gameSessionFinished) return
        gameSessionFinished = true
        GamePresenceStateMarker.markLauncherActive(this)
        SteamGamePresenceService.stop(this)
    }

    private fun initViews() {
        onBackPressedDispatcher.addCallback(this, gameBackPressedCallback)

        val root = findViewById<FrameLayout>(R.id.gameRoot)
        renderSurfaceManager.init(root)

        sessionCoordinator.initSessionUi(findViewById(R.id.gamePerformanceOverlay))

        val host = findViewById<FrameLayout>(R.id.gameHost)
        inputHandler.initFloatingMouseControls(
            host = host,
            autoSwitchLeftAfterRightClick = sessionConfig.autoSwitchLeftAfterRightClick,
            touchDoubleClickAsRightClick = sessionConfig.touchDoubleClickAsRightClick,
            ignoreLongPressRightClickWhilePlayingCard =
                sessionConfig.ignoreLongPressRightClickWhilePlayingCard,
            touchMouseInteractionMode = sessionConfig.touchMouseInteractionMode,
            builtInSoftKeyboardEnabled = sessionConfig.builtInSoftKeyboardEnabled
        )
        sessionCoordinator.refreshSessionUiVisibility()

        renderSurfaceManager.renderView.setOnTouchListener { _, event ->
            inputHandler.handleTouchEvent(event)
        }
        renderSurfaceManager.renderView.requestFocus()
    }

    fun setBootOverlayKeepScreenOn(enabled: Boolean) {
        if (bootOverlayKeepScreenOn == enabled) {
            return
        }
        bootOverlayKeepScreenOn = enabled
        if (enabled) {
            keepScreenOnActive = true
        } else {
            resetKeepScreenOnIdleTimer()
        }
        updateKeepScreenOnFlag()
    }

    private fun resetKeepScreenOnIdleTimer() {
        if (!::sessionConfig.isInitialized) {
            return
        }
        keepScreenOnHandler.removeCallbacks(keepScreenOnIdleRunnable)
        if (!activityForeground) {
            keepScreenOnActive = false
            updateKeepScreenOnFlag()
            return
        }
        keepScreenOnActive = true
        val timeoutMs = sessionConfig.keepScreenOnTimeoutMs
        if (timeoutMs != null && !bootOverlayKeepScreenOn) {
            keepScreenOnHandler.postDelayed(keepScreenOnIdleRunnable, timeoutMs)
        }
        updateKeepScreenOnFlag()
    }

    private fun updateKeepScreenOnFlag() {
        if (activityForeground && (bootOverlayKeepScreenOn || keepScreenOnActive)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun requestInGameFileSelection(requestId: String, mimeType: String) {
        if (requestId.isBlank()) {
            return
        }
        if (pendingFilePickerRequestId != null) {
            writeFilePickerResult(requestId, "ERROR", "picker_busy")
            return
        }
        pendingFilePickerRequestId = requestId
        try {
            filePickerLauncher.launch(arrayOf(mimeType.ifBlank { "*/*" }))
        } catch (throwable: Throwable) {
            pendingFilePickerRequestId = null
            writeFilePickerResult(requestId, "ERROR", throwable.javaClass.simpleName)
        }
    }

    private fun handleFilePickerResult(uri: Uri?) {
        val requestId = pendingFilePickerRequestId ?: return
        pendingFilePickerRequestId = null
        if (uri == null) {
            writeFilePickerResult(requestId, "CANCEL", "")
            return
        }
        try {
            val selectedFile = RuntimePaths.inGameFilePickerSelectionFile(this)
            selectedFile.parentFile?.mkdirs()
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) {
                    throw IllegalStateException("openInputStream returned null")
                }
                FileOutputStream(selectedFile, false).use { output ->
                    input.copyTo(output)
                }
            }
            writeFilePickerResult(requestId, "OK", selectedFile.absolutePath)
        } catch (throwable: Throwable) {
            writeFilePickerResult(requestId, "ERROR", throwable.javaClass.simpleName)
        }
    }

    private fun writeFilePickerResult(requestId: String, status: String, payload: String) {
        try {
            val resultFile = RuntimePaths.inGameFilePickerResultFile(this)
            resultFile.parentFile?.mkdirs()
            resultFile.writeText(
                requestId + "\n" +
                    status + "\n" +
                    payload + "\n"
            )
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        resetKeepScreenOnIdleTimer()
        return inputHandler.handleTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        resetKeepScreenOnIdleTimer()
        return super.dispatchTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        resetKeepScreenOnIdleTimer()
        return inputHandler.handleGenericMotionEvent(event) || super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        resetKeepScreenOnIdleTimer()
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_BACK && sessionCoordinator.handleAndroidBackKeyEvent(event)) {
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            return inputHandler.handleVolumeKeyEvent(event)
        }
        if (inputHandler.isGamepadKeyEvent(event) && inputHandler.handleGamepadKeyEvent(event)) {
            return true
        }
        if (inputHandler.dispatchKeyboardEventToGame(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun registerSystemBackInvokedCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (onBackInvokedCallback != null) {
            return
        }
        val callback = OnBackInvokedCallback {
            sessionCoordinator.handleAndroidBackPressed()
        }
        try {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
            onBackInvokedCallback = callback
        } catch (_: Throwable) {
        }
    }

    private fun unregisterSystemBackInvokedCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val callback = onBackInvokedCallback ?: return
        try {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        } catch (_: Throwable) {
        }
        onBackInvokedCallback = null
    }

    private fun releaseLaunchGuard() {
        synchronized(launchGuardLock) {
            if (!launchGuardAcquired) {
                return
            }
            GameProcessLaunchGuard.release(launchGuardToken)
            launchGuardAcquired = false
        }
    }

    private fun buildMemoryEventExtras(): Map<String, Any?> {
        val launchMode = if (::sessionConfig.isInitialized) {
            sessionConfig.launchMode
        } else {
            intent?.getStringExtra(EXTRA_LAUNCH_MODE)
        }
        return linkedMapOf(
            "sessionToken" to launchGuardToken,
            "launchMode" to launchMode,
            "manualDismissBootOverlay" to intent?.getBooleanExtra(EXTRA_MANUAL_DISMISS_BOOT_OVERLAY, false),
            "forceJvmCrash" to intent?.getBooleanExtra(EXTRA_FORCE_JVM_CRASH, false),
            "forceRuntimeCrash" to intent?.getBooleanExtra(EXTRA_FORCE_RUNTIME_CRASH, false)
        )
    }
}

package io.stamethyst

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import io.stamethyst.backend.render.DisplayPerformanceController
import io.stamethyst.backend.launch.StsLaunchSpec
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.LauncherConfig
import io.stamethyst.input.GameInputHandler

class StsGameActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_LAUNCH_MODE = "io.stamethyst.launch_mode"
        const val EXTRA_BACK_BEHAVIOR = "io.stamethyst.back_behavior"
        const val EXTRA_BACK_IMMEDIATE_EXIT = "io.stamethyst.back_immediate_exit"
        const val EXTRA_MANUAL_DISMISS_BOOT_OVERLAY = "io.stamethyst.manual_dismiss_boot_overlay"
        const val EXTRA_FORCE_JVM_CRASH = "io.stamethyst.force_jvm_crash"

        @JvmStatic
        fun launch(
            context: Context,
            launchMode: String,
            backBehavior: BackBehavior,
            manualDismissBootOverlay: Boolean,
            forceJvmCrash: Boolean = false
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
            context.startActivity(intent)
        }
    }

    private lateinit var sessionConfig: GameSessionConfig
    private lateinit var renderSurfaceManager: RenderSurfaceManager
    private lateinit var inputHandler: GameInputHandler
    private lateinit var sessionCoordinator: GameSessionCoordinator
    private var onBackInvokedCallback: OnBackInvokedCallback? = null
    private var bootOverlayKeepScreenOn = false

    private val gameBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            sessionCoordinator.handleAndroidBackPressed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)
        setVolumeControlStream(AudioManager.STREAM_MUSIC)

        sessionConfig = GameSessionConfig.fromActivityIntent(this, intent)
        initControllers()
        renderSurfaceManager.applyImmersiveMode()
        initViews()
        registerSystemBackInvokedCallback()
    }

    override fun onDestroy() {
        unregisterSystemBackInvokedCallback()
        DisplayPerformanceController.applySustainedPerformanceMode(this, false)
        inputHandler.onDestroy()
        renderSurfaceManager.onDestroy()
        sessionCoordinator.onDestroy()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        DisplayPerformanceController.applySustainedPerformanceMode(
            this,
            sessionConfig.sustainedPerformanceModeEnabled
        )
        renderSurfaceManager.applyImmersiveMode()
        inputHandler.resetGamepadState()
        renderSurfaceManager.onForegroundChanged(true)
        sessionCoordinator.onResume()
    }

    override fun onPause() {
        inputHandler.resetGamepadState()
        inputHandler.hideSoftKeyboard()
        sessionCoordinator.onPause()
        renderSurfaceManager.onForegroundChanged(false)
        DisplayPerformanceController.applySustainedPerformanceMode(this, false)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        renderSurfaceManager.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            renderSurfaceManager.applyImmersiveMode()
        }
        sessionCoordinator.onWindowFocusChanged(hasFocus)
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
            avoidDisplayCutout = sessionConfig.avoidDisplayCutout,
            cropScreenBottom = sessionConfig.cropScreenBottom,
            onSurfaceReady = { sessionCoordinator.onSurfaceReady() },
            onSurfaceDestroyed = { },
            onTextureFrameUpdate = { timestampNs ->
                sessionCoordinator.onTextureFrameUpdate(timestampNs)
            }
        )

        sessionCoordinator = GameSessionCoordinator(
            activity = this,
            config = sessionConfig,
            renderSurfaceManager = renderSurfaceManager,
            inputHandler = inputHandler
        )
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
            longPressMouseShowsKeyboard = sessionConfig.longPressMouseShowsKeyboard
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
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return inputHandler.handleTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return inputHandler.handleGenericMotionEvent(event) || super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
        if (inputHandler.isGamepadKeyEvent(event)) {
            return inputHandler.handleGamepadKeyEvent(event)
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
}

package io.stamethyst

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.oracle.dalvik.VMLauncher
import io.stamethyst.backend.launch.BackExitNotice
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.launch.CrashReportStore
import io.stamethyst.backend.render.DisplayConfigSync
import io.stamethyst.backend.launch.LaunchPreparationService
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.backend.render.RendererConfig
import io.stamethyst.backend.runtime.RuntimePackInstaller
import io.stamethyst.backend.core.RuntimePaths
import io.stamethyst.backend.launch.StsLaunchSpec
import io.stamethyst.backend.input.mapViewToWindowCoords
import io.stamethyst.backend.bridge.parseBootBridgeEventLine
import io.stamethyst.input.AndroidGamepadGlfwMapper
import io.stamethyst.input.AndroidGlfwKeycode
import net.kdt.pojavlaunch.LwjglGlfwKeycode
import net.kdt.pojavlaunch.Logger
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Locale

class StsGameActivity : AppCompatActivity(), SurfaceHolder.Callback {
    companion object {
        private const val TAG = "StsGameActivity"
        const val EXTRA_LAUNCH_MODE = "io.stamethyst.launch_mode"
        const val EXTRA_RENDERER_BACKEND = "io.stamethyst.renderer_backend"
        const val EXTRA_WAIT_FOR_MAIN_MENU = "io.stamethyst.wait_for_main_menu"
        const val EXTRA_BACK_IMMEDIATE_EXIT = "io.stamethyst.back_immediate_exit"
        const val EXTRA_MANUAL_DISMISS_BOOT_OVERLAY = "io.stamethyst.manual_dismiss_boot_overlay"
        const val EXTRA_TARGET_FPS = "io.stamethyst.target_fps"
        private const val DEFAULT_RENDER_SCALE = 1.0f
        private const val MIN_RENDER_SCALE = 0.50f
        private const val MAX_RENDER_SCALE = 1.00f
        private const val DEFAULT_TARGET_FPS = 120
        private const val BOOT_OVERLAY_MIN_VISIBLE_MS = 1200L
        private const val BOOT_OVERLAY_READY_DELAY_MS = 700L
        private const val BACK_FORCE_RESTART_DELAY_MS = 120L
        private const val BOOT_LOG_MAX_LINES = 220

        @JvmStatic
        fun launch(
            @NonNull context: Context,
            @NonNull launchMode: String,
            @NonNull rendererBackend: RendererBackend,
            targetFps: Int,
            backImmediateExit: Boolean,
            manualDismissBootOverlay: Boolean
        ) {
            val intent = Intent(context, StsGameActivity::class.java)
            intent.putExtra(EXTRA_LAUNCH_MODE, launchMode)
            intent.putExtra(EXTRA_RENDERER_BACKEND, rendererBackend.rendererId())
            intent.putExtra(EXTRA_TARGET_FPS, targetFps)
            intent.putExtra(
                EXTRA_WAIT_FOR_MAIN_MENU,
                StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD == launchMode
            )
            intent.putExtra(EXTRA_BACK_IMMEDIATE_EXIT, backImmediateExit)
            intent.putExtra(EXTRA_MANUAL_DISMISS_BOOT_OVERLAY, manualDismissBootOverlay)
            context.startActivity(intent)
        }
    }

    @Nullable
    private var surfaceView: SurfaceView? = null

    @Nullable
    private var textureView: TextureView? = null

    @Nullable
    private var textureSurface: Surface? = null

    private lateinit var renderView: View
    private var useTextureViewSurface = false

    @Volatile
    private var vmStarted = false

    @Volatile
    private var runtimeLifecycleReady = false

    @Volatile
    private var bridgeSurfaceReady = false

    @Volatile
    private var backExitRequested = false

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var leftPressed = false
    private var gamepadDirectInputEnableAttempted = false
    private var surfaceBufferWidth = 0
    private var surfaceBufferHeight = 0
    private var waitingLandscapeSinceMs = -1L
    private var startCheckPosted = false
    private var renderScale = DEFAULT_RENDER_SCALE
    private var targetFps = DEFAULT_TARGET_FPS
    private var launchMode = StsLaunchSpec.LAUNCH_MODE_VANILLA
    private var launcherRequestedRenderer = RendererBackend.OPENGL_ES2
    private var waitForMainMenu = false
    private var backImmediateExit = true
    private var manualDismissBootOverlay = false
    private var jvmLogListenerRegistered = false
    private var bootOverlay: View? = null
    private var bootOverlayProgressBar: ProgressBar? = null
    private var bootOverlayStatusText: TextView? = null
    private var bootOverlayLogScroll: ScrollView? = null
    private var bootOverlayLogText: TextView? = null
    private var bootOverlayDismissButton: Button? = null
    private var bootOverlayProgress = 0
    private var bootOverlayMessage = ""
    private var bootOverlayShownAtMs = -1L
    private var bootOverlayDismissed = false
    private var mainMenuReadySignaled = false
    private var launchFailureSignaled = false

    @Volatile
    private var earlyOverlayDismissOnNextFrame = false

    @Volatile
    private var lastTextureFrameTimestampNs = 0L

    @Volatile
    private var earlyOverlayDismissRequestFrameTimestampNs = 0L

    @Volatile
    @Nullable
    private var jvmLaunchThread: Thread? = null

    private var bootBridgeReaderThread: Thread? = null

    @Volatile
    private var bootBridgeReaderStop = false

    private val bootLogLines = ArrayDeque<String>()
    private val gameBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            handleAndroidBackPressed()
        }
    }

    private val jvmLogcatListener = Logger.eventLogListener { text ->
        onJvmLogMessage(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
        applyImmersiveMode()
        renderScale = resolveRenderScale()
        val requestedMode = intent.getStringExtra(EXTRA_LAUNCH_MODE)
        if (StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD == requestedMode) {
            launchMode = StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD
        }
        val requestedRenderer = RendererBackend.fromRendererId(intent.getStringExtra(EXTRA_RENDERER_BACKEND))
        val rendererDecision = RendererConfig.resolveEffectiveBackend(this, requestedRenderer)
        launcherRequestedRenderer = rendererDecision.effective
        appendRendererDecisionLog("game_entry", rendererDecision)
        if (rendererDecision.isFallback) {
            Toast.makeText(
                this,
                getString(R.string.renderer_fallback_toast, rendererDecision.reason),
                Toast.LENGTH_LONG
            ).show()
        }
        waitForMainMenu = intent.getBooleanExtra(
            EXTRA_WAIT_FOR_MAIN_MENU,
            StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD == launchMode
        )
        backImmediateExit = intent.getBooleanExtra(EXTRA_BACK_IMMEDIATE_EXIT, true)
        manualDismissBootOverlay = intent.getBooleanExtra(EXTRA_MANUAL_DISMISS_BOOT_OVERLAY, false)
        targetFps = sanitizeTargetFps(intent.getIntExtra(EXTRA_TARGET_FPS, DEFAULT_TARGET_FPS))
        useTextureViewSurface = shouldUseTextureViewSurface(launcherRequestedRenderer, renderScale)
        initBootOverlay()
        onBackPressedDispatcher.addCallback(this, gameBackPressedCallback)

        val root = findViewById<FrameLayout>(R.id.gameRoot)
        val renderLayoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        if (useTextureViewSurface) {
            if (launcherRequestedRenderer == RendererBackend.KOPPER_ZINK) {
                Log.i(TAG, "Using TextureView surface path for Kopper renderer")
            } else {
                Log.i(
                    TAG,
                    "Using TextureView surface path for scaled rendering: renderScale=$renderScale"
                )
            }
            val view = TextureView(this)
            view.isOpaque = true
            textureView = view
            renderView = view
            root.addView(view, renderLayoutParams)
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    @NonNull surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    surfaceBufferWidth = Math.max(1, width)
                    surfaceBufferHeight = Math.max(1, height)
                    applyTextureBufferSize(surface)
                    releaseTextureSurfaceIfNeeded()
                    textureSurface = Surface(surface)
                    JREUtils.setupBridgeWindow(textureSurface)
                    bridgeSurfaceReady = true
                    updateWindowSize()
                    tryStartJvmWhenSurfaceReady()
                }

                override fun onSurfaceTextureSizeChanged(
                    @NonNull surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    surfaceBufferWidth = Math.max(1, width)
                    surfaceBufferHeight = Math.max(1, height)
                    applyTextureBufferSize(surface)
                    updateWindowSize()
                    tryStartJvmWhenSurfaceReady()
                }

                override fun onSurfaceTextureDestroyed(@NonNull surface: SurfaceTexture): Boolean {
                    bridgeSurfaceReady = false
                    JREUtils.releaseBridgeWindow()
                    releaseTextureSurfaceIfNeeded()
                    surfaceBufferWidth = 0
                    surfaceBufferHeight = 0
                    return true
                }

                override fun onSurfaceTextureUpdated(@NonNull surface: SurfaceTexture) {
                    lastTextureFrameTimestampNs = surface.timestamp
                    if (earlyOverlayDismissOnNextFrame &&
                        lastTextureFrameTimestampNs > earlyOverlayDismissRequestFrameTimestampNs
                    ) {
                        earlyOverlayDismissOnNextFrame = false
                        runOnUiThread {
                            updateBootOverlayProgress(Math.max(bootOverlayProgress, 99), "Game frame ready")
                            dismissBootOverlay()
                        }
                    }
                }
            }
        } else {
            val view = SurfaceView(this)
            surfaceView = view
            renderView = view
            root.addView(view, renderLayoutParams)
            view.holder.addCallback(this)
        }

        renderView.isFocusable = true
        renderView.isFocusableInTouchMode = true
        renderView.setOnTouchListener { _, event -> handleTouchEvent(event) }
        renderView.requestFocus()
    }

    override fun onDestroy() {
        resetGamepadState()
        runtimeLifecycleReady = false
        bridgeSurfaceReady = false
        earlyOverlayDismissOnNextFrame = false
        stopBootBridgeReaderIfRunning()
        val launchThread = jvmLaunchThread
        jvmLaunchThread = null
        launchThread?.interrupt()
        if (useTextureViewSurface) {
            try {
                JREUtils.releaseBridgeWindow()
            } catch (_: Throwable) {
            }
        }
        releaseTextureSurfaceIfNeeded()
        if (jvmLogListenerRegistered) {
            try {
                Logger.removeLogListener(jvmLogcatListener)
            } catch (_: Throwable) {
            }
            jvmLogListenerRegistered = false
        }
        super.onDestroy()
    }

    override fun surfaceCreated(@NonNull holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        if (frame != null) {
            surfaceBufferWidth = frame.width()
            surfaceBufferHeight = frame.height()
        }
        JREUtils.setupBridgeWindow(holder.surface)
        bridgeSurfaceReady = true
        updateWindowSize()
        tryStartJvmWhenSurfaceReady()
    }

    override fun surfaceChanged(@NonNull holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceBufferWidth = width
        surfaceBufferHeight = height
        updateWindowSize()
        tryStartJvmWhenSurfaceReady()
    }

    override fun surfaceDestroyed(@NonNull holder: SurfaceHolder) {
        bridgeSurfaceReady = false
        JREUtils.releaseBridgeWindow()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        resetGamepadState()
        if (runtimeLifecycleReady) {
            CallbackBridge.nativeSetAudioMuted(false)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 1)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_ICONIFIED, 0)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1)
        }
        tryStartJvmWhenSurfaceReady()
    }

    override fun onPause() {
        resetGamepadState()
        if (runtimeLifecycleReady) {
            CallbackBridge.nativeSetAudioMuted(true)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_ICONIFIED, 1)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 0)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0)
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 0)
        }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleAndroidBackPressed()
    }

    private fun handleAndroidBackPressed() {
        if (!backImmediateExit) {
            Log.i(TAG, "Android back pressed: disabled by launcher setting")
            return
        }
        requestBackExitToLauncher()
    }

    private fun handleAndroidBackKeyEvent(@NonNull event: KeyEvent): Boolean {
        val action = event.action
        if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            onBackPressedDispatcher.onBackPressed()
        }
        return true
    }

    private fun requestBackExitToLauncher() {
        if (backExitRequested) {
            return
        }
        backExitRequested = true
        BackExitNotice.markExpectedBackExit(this)
        stopBootBridgeReaderIfRunning()
        updateBootOverlayProgress(100, "Stopping game...")
        Log.i(TAG, "Android back pressed: force restart to launcher")

        jvmLaunchThread?.interrupt()
        try {
            CallbackBridge.nativeSetInputReady(false)
        } catch (_: Throwable) {
        }
        requestJvmCloseSignal()
        scheduleLauncherRestartAndKillProcess()
    }

    private fun requestJvmCloseSignal(): Boolean {
        return try {
            val requested = CallbackBridge.nativeRequestCloseWindow()
            if (requested) {
                Log.i(TAG, "Sent glfwSetWindowShouldClose=true")
            }
            requested
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to request JVM window close", error)
            false
        }
    }

    private fun scheduleLauncherRestartAndKillProcess() {
        val launcherIntent = Intent(this, LauncherActivity::class.java)
        launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0x71A7, launcherIntent, flags)
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager?
        val triggerAt = SystemClock.elapsedRealtime() + BACK_FORCE_RESTART_DELAY_MS
        if (alarmManager != null) {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        } else {
            try {
                pendingIntent.send()
            } catch (_: PendingIntent.CanceledException) {
            }
        }
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
        System.exit(0)
    }

    private fun startJvmOnce() {
        if (vmStarted) {
            return
        }
        if (backExitRequested) {
            finish()
            return
        }
        vmStarted = true
        runtimeLifecycleReady = false
        updateBootOverlayProgress(8, "Starting JVM...")
        CrashReportStore.clear(this)

        val launchThread = Thread({
            try {
                if (backExitRequested) {
                    runOnUiThread { finish() }
                    return@Thread
                }
                LaunchPreparationService.prepare(this, launchMode) { percent, message ->
                    updateBootOverlayProgress(mapBootOverlayPreparationProgress(percent), message)
                }

                val runtimeRoot = RuntimePaths.runtimeRoot(this)
                val javaHome = RuntimePackInstaller.locateJavaHome(runtimeRoot)
                    ?: throw IllegalStateException("No Java home found in ${runtimeRoot.absolutePath}")

                RuntimePaths.ensureBaseDirs(this)
                val logFile = RuntimePaths.latestLog(this)
                val logParent = logFile.parentFile
                if (logParent != null && !logParent.exists() && !logParent.mkdirs()) {
                    throw IllegalStateException("Failed to create log directory: ${logParent.absolutePath}")
                }
                if (!logFile.exists() && !logFile.createNewFile()) {
                    throw IllegalStateException("Failed to create log file: ${logFile.absolutePath}")
                }
                Logger.begin(logFile.absolutePath)
                try {
                    Logger.addLogListener(jvmLogcatListener)
                    jvmLogListenerRegistered = true
                } catch (_: Throwable) {
                    jvmLogListenerRegistered = false
                }
                if (waitForMainMenu && StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD == launchMode) {
                    startBootBridgeReader()
                    updateBootOverlayProgress(26, "Waiting for structured boot events...")
                }
                Logger.appendToLog("Launching STS with java home: ${javaHome.absolutePath}")
                Logger.appendToLog("Launch mode: $launchMode")
                Logger.appendToLog("Surface backend: ${if (useTextureViewSurface) "TextureView" else "SurfaceView"}")
                val originalFboPatchEnabled = CompatibilitySettings.isOriginalFboPatchEnabled(this)
                val downfallFboPatchEnabled = CompatibilitySettings.isDownfallFboPatchEnabled(this)
                val virtualFboPocEnabled = CompatibilitySettings.isVirtualFboPocEnabled(this)
                Logger.appendToLog(
                    "Compat settings: originalFboPatch=$originalFboPatchEnabled, downfallFboPatch=$downfallFboPatchEnabled, virtualFboPoc=$virtualFboPocEnabled"
                )
                val preferredRenderer = RendererConfig.readPreferredBackend(this)
                val rendererDecision = RendererConfig.resolveEffectiveBackend(this, preferredRenderer)
                val effectiveRenderer = rendererDecision.effective
                Logger.appendToLog("Renderer from launcher intent: ${launcherRequestedRenderer.rendererId()}")
                Logger.appendToLog("Renderer decision in game: ${rendererDecision.toLogText()}")
                Logger.appendToLog("Renderer GL library expected: ${effectiveRenderer.lwjglOpenGlLibName()}")
                if (launcherRequestedRenderer != effectiveRenderer) {
                    Logger.appendToLog(
                        "Renderer changed after re-check: launcher_effective=${launcherRequestedRenderer.rendererId()}, game_effective=${effectiveRenderer.rendererId()}"
                    )
                }
                if (StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD == launchMode) {
                    ModJarSupport.appendCompatDiagnosticSnapshot(this, "game_pre_jvm")
                }
                updateWindowSize()
                Logger.appendToLog(
                    "Render scale: $renderScale, surface(raw)=${resolveRawPhysicalWidth()}x${resolveRawPhysicalHeight()}, " +
                        "surface(effective)=${resolvePhysicalWidth()}x${resolvePhysicalHeight()}, " +
                        "window=${CallbackBridge.windowWidth}x${CallbackBridge.windowHeight}"
                )
                Logger.appendToLog("Target FPS limit: $targetFps")
                syncDisplayConfigToSurfaceSize()
                JREUtils.relocateLibPath(applicationInfo.nativeLibraryDir, javaHome.absolutePath)
                JREUtils.setJavaEnvironment(
                    this,
                    javaHome.absolutePath,
                    Math.max(1, CallbackBridge.windowWidth),
                    Math.max(1, CallbackBridge.windowHeight),
                    effectiveRenderer
                )
                JREUtils.initJavaRuntime(javaHome.absolutePath)
                JREUtils.setupExitMethod(applicationContext)
                JREUtils.initializeHooks()
                JREUtils.chdir(RuntimePaths.stsRoot(this).absolutePath)
                CallbackBridge.nativeSetUseInputStackQueue(true)
                CallbackBridge.nativeSetInputReady(true)
                runtimeLifecycleReady = true

                val launchArgs = ArrayList<String>()
                launchArgs.add("java")
                launchArgs.addAll(StsLaunchSpec.buildArgs(this, javaHome, launchMode, effectiveRenderer))
                if (backExitRequested) {
                    runOnUiThread { finish() }
                    return@Thread
                }
                if (StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD == launchMode) {
                    updateBootOverlayProgress(28, "Launching ModTheSpire...")
                } else {
                    updateBootOverlayProgress(85, "Launching game...")
                }
                Logger.appendToLog(
                    "Launch arg check: " +
                        findLaunchArgValue(launchArgs, "-Dorg.lwjgl.opengl.libname=") + ", " +
                        findLaunchArgValue(launchArgs, "-Damethyst.gdx.fbo_fallback=") + ", " +
                        findLaunchArgValue(launchArgs, "-Damethyst.gdx.virtual_fbo_poc=") + ", " +
                        findLaunchArgValue(launchArgs, "-Dorg.lwjgl.librarypath=")
                )
                Logger.appendToLog("Launch args: $launchArgs")

                val exitCode = VMLauncher.launchJVM(launchArgs.toTypedArray())
                Logger.appendToLog("Java Exit code: $exitCode")
                if (backExitRequested) {
                    runOnUiThread { finish() }
                    return@Thread
                }
                if (exitCode == 0) {
                    if (waitForMainMenu &&
                        StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD == launchMode &&
                        !mainMenuReadySignaled &&
                        !bootOverlayDismissed
                    ) {
                        runOnUiThread {
                            reportCrashAndReturn(
                                -3,
                                false,
                                "JVM exited before reaching main menu"
                            )
                        }
                    } else {
                        runOnUiThread { finish() }
                    }
                } else {
                    runOnUiThread { reportCrashAndReturn(exitCode, false, null) }
                }
            } catch (t: Throwable) {
                runtimeLifecycleReady = false
                Log.e(TAG, "Launch failed", t)
                CrashReportStore.recordThrowable(this, "game_launch_thread", t)
                try {
                    Logger.appendToLog("Launch failed: $t")
                } catch (_: Throwable) {
                }
                if (backExitRequested) {
                    runOnUiThread { finish() }
                    return@Thread
                }
                val message = "${t.javaClass.simpleName}: ${t.message}"
                runOnUiThread { reportCrashAndReturn(-1, false, message) }
            } finally {
                runtimeLifecycleReady = false
                jvmLaunchThread = null
            }
        }, "STS-JVM-Thread")
        jvmLaunchThread = launchThread
        launchThread.start()
    }

    private fun tryStartJvmWhenSurfaceReady() {
        if (backExitRequested) {
            return
        }
        if (vmStarted) {
            return
        }
        val rawWidth = resolveRawPhysicalWidth()
        val rawHeight = resolveRawPhysicalHeight()
        if (rawWidth <= 1 || rawHeight <= 1) {
            Log.i(TAG, "Waiting for valid surface size before JVM start: ${rawWidth}x$rawHeight")
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
                Log.i(
                    TAG,
                    "Waiting for landscape surface before JVM start: ${rawWidth}x$rawHeight, waited=${waitedMs}ms"
                )
                scheduleStartCheck()
                return
            }
            Log.w(TAG, "Surface is still portrait after wait, starting JVM anyway: ${rawWidth}x$rawHeight")
        } else {
            waitingLandscapeSinceMs = -1L
        }
        startJvmOnce()
    }

    private fun scheduleStartCheck() {
        if (vmStarted || startCheckPosted) {
            return
        }
        startCheckPosted = true
        renderView.postDelayed({
            startCheckPosted = false
            tryStartJvmWhenSurfaceReady()
        }, 120L)
    }

    private fun initBootOverlay() {
        bootOverlay = findViewById(R.id.bootOverlay)
        bootOverlayProgressBar = findViewById(R.id.bootOverlayProgressBar)
        bootOverlayStatusText = findViewById(R.id.bootOverlayStatusText)
        bootOverlayLogScroll = findViewById(R.id.bootOverlayLogScroll)
        bootOverlayLogText = findViewById(R.id.bootOverlayLogText)
        bootOverlayDismissButton = findViewById(R.id.bootOverlayDismissButton)
        if (bootOverlay == null || bootOverlayProgressBar == null || bootOverlayStatusText == null) {
            waitForMainMenu = false
            return
        }
        if (!waitForMainMenu) {
            bootOverlay?.visibility = View.GONE
            bootOverlayDismissed = true
            bootOverlayDismissButton?.let {
                it.visibility = View.GONE
                it.setOnClickListener(null)
            }
            return
        }
        synchronized(bootLogLines) {
            bootLogLines.clear()
        }
        bootOverlayLogText?.text = ""
        bootOverlayDismissButton?.let { button ->
            if (manualDismissBootOverlay) {
                button.visibility = View.VISIBLE
                button.isEnabled = true
                button.text = "关闭遮幕"
                button.setOnClickListener {
                    updateBootOverlayProgress(Math.max(bootOverlayProgress, 99), "Manual dismiss requested")
                    dismissBootOverlay()
                }
            } else {
                button.visibility = View.GONE
                button.setOnClickListener(null)
            }
        }
        bootOverlay?.visibility = View.VISIBLE
        if (manualDismissBootOverlay) {
            bootOverlay?.setOnTouchListener(null)
            bootOverlay?.isClickable = true
            bootOverlay?.isFocusable = true
            bootOverlay?.setOnClickListener {
                // Consume background taps but keep dismiss button clickable.
            }
        } else {
            bootOverlay?.setOnClickListener(null)
            bootOverlay?.setOnTouchListener { _, _ -> true }
        }
        bootOverlayShownAtMs = SystemClock.uptimeMillis()
        if (manualDismissBootOverlay) {
            updateBootOverlayProgress(1, "Starting launch pipeline... (manual overlay dismiss)")
        } else {
            updateBootOverlayProgress(1, "Starting launch pipeline...")
        }
    }

    private fun appendRendererDecisionLog(stage: String, decision: RendererConfig.ResolutionResult) {
        val line = "[StsGameActivity/$stage] ${decision.toLogText()}\n"
        try {
            RuntimePaths.ensureBaseDirs(this)
            val logFile = RuntimePaths.latestLog(this)
            val parent = logFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return
            }
            FileOutputStream(logFile, true).use { output ->
                output.write(line.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to append renderer decision log", error)
        }
    }

    private fun onJvmLogMessage(text: String?) {
        Log.i(TAG, "[JVM] $text")
        if (!waitForMainMenu || bootOverlayDismissed || text == null) {
            return
        }
        val lines = text.split(Regex("\\r?\\n"))
        for (raw in lines) {
            val line = raw.trim()
            appendBootOverlayLog(raw)
            if (!launchFailureSignaled) {
                val fatal = detectFatalStartupLog(line)
                if (fatal != null) {
                    signalLaunchFailure(fatal)
                    continue
                }
            }
            if (!bootOverlayDismissed && shouldDismissOverlayEarlyLog(line)) {
                runOnUiThread {
                    updateBootOverlayProgress(Math.max(bootOverlayProgress, 98), "Starting game...")
                    requestEarlyOverlayDismiss()
                }
            }
        }
    }

    private fun detectFatalStartupLog(line: String?): String? {
        if (line == null || line.isEmpty()) {
            return null
        }
        val lower = line.lowercase(Locale.ROOT)
        return when {
            lower.contains("com.evacipated.cardcrawl.modthespire.patcher.patchingexception") -> "MTS patching failed: $line"
            lower.contains("missingdependencyexception") -> "MTS missing dependency: $line"
            lower.contains("duplicatemodidexception") -> "MTS duplicate mod id: $line"
            lower.contains("missingmodidexception") -> "MTS missing mod id: $line"
            lower.contains("illegal patch parameter") -> "MTS illegal patch parameter: $line"
            else -> null
        }
    }

    private fun shouldDismissOverlayEarlyLog(line: String?): Boolean {
        if (line == null || line.isEmpty()) {
            return false
        }
        val lower = line.lowercase(Locale.ROOT)
        if (lower.contains("core.cardcrawlgame> distributorplatform=")) {
            return true
        }
        if (lower.contains("core.cardcrawlgame> ismodded=")) {
            return true
        }
        if (lower.contains("core.cardcrawlgame> no migration")) {
            return true
        }
        if (lower.contains("publishaddcustommodemods")) {
            return true
        }
        return lower.contains("events.heartevent>")
    }

    private fun requestEarlyOverlayDismiss() {
        if (bootOverlayDismissed || bootOverlay == null) {
            return
        }
        if (manualDismissBootOverlay) {
            updateBootOverlayProgress(Math.max(bootOverlayProgress, 98), "Game ready, tap Close Overlay")
            runOnUiThread {
                bootOverlayDismissButton?.let {
                    it.text = "进入游戏"
                    it.isEnabled = true
                }
            }
            return
        }
        if (useTextureViewSurface && textureView != null) {
            earlyOverlayDismissRequestFrameTimestampNs = lastTextureFrameTimestampNs
            earlyOverlayDismissOnNextFrame = true
            return
        }
        dismissBootOverlay()
    }

    private fun startBootBridgeReader() {
        stopBootBridgeReaderIfRunning()
        val eventsFile = RuntimePaths.bootBridgeEventsFile(this)
        val parent = eventsFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        try {
            FileOutputStream(eventsFile, false).use {
                // Truncate old events.
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to reset boot bridge events file", error)
        }

        bootBridgeReaderStop = false
        bootBridgeReaderThread = Thread({ runBootBridgeReader(eventsFile) }, "BootBridge-Reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopBootBridgeReaderIfRunning() {
        bootBridgeReaderStop = true
        val thread = bootBridgeReaderThread
        bootBridgeReaderThread = null
        thread?.interrupt()
    }

    private fun runBootBridgeReader(eventsFile: File) {
        var offset = 0L
        while (!bootBridgeReaderStop) {
            try {
                if (!eventsFile.exists()) {
                    sleepQuietly(80L)
                    continue
                }
                RandomAccessFile(eventsFile, "r").use { raf ->
                    val length = raf.length()
                    if (length < offset) {
                        offset = 0L
                    }
                    if (length > offset) {
                        raf.seek(offset)
                        var raw: String? = raf.readLine()
                        while (!bootBridgeReaderStop && raw != null) {
                            val line = String(raw.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)
                            onBootBridgeEventLine(line)
                            raw = raf.readLine()
                        }
                        offset = raf.filePointer
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Boot bridge reader error", error)
            }
            sleepQuietly(80L)
        }
    }

    private fun onBootBridgeEventLine(line: String?) {
        if (line == null) {
            return
        }
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            return
        }
        appendBootOverlayLog("[bridge] $trimmed")

        val parsed = parseBootBridgeEventLine(trimmed) ?: return

        when (parsed.type) {
            "PHASE" -> {
                if (parsed.percent >= 0) {
                    updateBootOverlayProgress(
                        parsed.percent,
                        if (parsed.message.isEmpty()) "Loading..." else parsed.message
                    )
                }
            }

            "READY" -> signalMainMenuReady(
                if (parsed.message.isEmpty()) "Main menu is ready" else parsed.message
            )

            "FAIL" -> signalLaunchFailure(
                if (parsed.message.isEmpty()) "Bridge reported startup failure" else parsed.message
            )
        }
    }

    private fun parseSafeInt(value: String?, fallback: Int): Int {
        if (value == null) {
            return fallback
        }
        return try {
            value.trim().toInt()
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun signalLaunchFailure(detail: String) {
        if (backExitRequested) {
            runOnUiThread { finish() }
            return
        }
        if (launchFailureSignaled) {
            return
        }
        launchFailureSignaled = true
        stopBootBridgeReaderIfRunning()
        Log.e(TAG, "Detected startup failure from boot bridge: $detail")
        runOnUiThread { reportCrashAndReturn(-2, false, detail) }
    }

    private fun signalMainMenuReady(message: String) {
        if (!waitForMainMenu || mainMenuReadySignaled) {
            return
        }
        mainMenuReadySignaled = true
        stopBootBridgeReaderIfRunning()
        if (manualDismissBootOverlay) {
            updateBootOverlayProgress(100, "$message (tap Close Overlay)")
            runOnUiThread {
                bootOverlayDismissButton?.let {
                    it.text = "进入游戏"
                    it.isEnabled = true
                }
            }
            return
        }
        updateBootOverlayProgress(100, message)
        val now = SystemClock.uptimeMillis()
        val elapsed = if (bootOverlayShownAtMs <= 0L) BOOT_OVERLAY_MIN_VISIBLE_MS else (now - bootOverlayShownAtMs)
        val minDelay = Math.max(0L, BOOT_OVERLAY_MIN_VISIBLE_MS - elapsed)
        val delay = Math.max(minDelay, BOOT_OVERLAY_READY_DELAY_MS)
        runOnUiThread {
            bootOverlay?.postDelayed({ dismissBootOverlay() }, delay)
        }
    }

    private fun dismissBootOverlay() {
        if (bootOverlayDismissed || bootOverlay == null) {
            return
        }
        bootOverlayDismissed = true
        earlyOverlayDismissOnNextFrame = false
        earlyOverlayDismissRequestFrameTimestampNs = 0L
        bootOverlayDismissButton?.let {
            it.visibility = View.GONE
            it.setOnClickListener(null)
        }
        bootOverlay?.visibility = View.GONE
    }

    private fun updateBootOverlayProgress(percent: Int, message: String?) {
        if (!waitForMainMenu) {
            return
        }
        val bounded = Math.max(0, Math.min(100, percent))
        val normalizedMessage = message?.trim() ?: ""
        if (bounded < bootOverlayProgress) {
            return
        }
        if (bounded == bootOverlayProgress && normalizedMessage == bootOverlayMessage) {
            return
        }
        bootOverlayProgress = bounded
        bootOverlayMessage = normalizedMessage
        runOnUiThread {
            if (bootOverlayDismissed || bootOverlayProgressBar == null || bootOverlayStatusText == null) {
                return@runOnUiThread
            }
            bootOverlayProgressBar?.progress = bootOverlayProgress
            if (normalizedMessage.isNotEmpty()) {
                bootOverlayStatusText?.text = "$normalizedMessage ($bootOverlayProgress%)"
            }
        }
    }

    private fun appendBootOverlayLog(rawLine: String?) {
        if (!waitForMainMenu || rawLine == null) {
            return
        }
        val line = rawLine.replace('\r', ' ').trim()
        if (line.isEmpty()) {
            return
        }
        synchronized(bootLogLines) {
            bootLogLines.addLast(line)
            while (bootLogLines.size > BOOT_LOG_MAX_LINES) {
                bootLogLines.removeFirst()
            }
        }
        runOnUiThread { renderBootOverlayLogs() }
    }

    private fun renderBootOverlayLogs() {
        if (bootOverlayDismissed || bootOverlayLogText == null) {
            return
        }
        val builder = StringBuilder(4096)
        synchronized(bootLogLines) {
            for (line in bootLogLines) {
                builder.append(line).append('\n')
            }
        }
        bootOverlayLogText?.text = builder.toString()
        bootOverlayLogScroll?.post { bootOverlayLogScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun reportCrashAndReturn(code: Int, isSignal: Boolean, @Nullable detail: String?) {
        if (backExitRequested) {
            finish()
            return
        }
        runtimeLifecycleReady = false
        CrashReportStore.recordLaunchResult(this, "game_report_crash_and_return", code, isSignal, detail)
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

    private fun updateWindowSize() {
        val physicalWidth = resolvePhysicalWidth()
        val physicalHeight = resolvePhysicalHeight()
        val windowWidth = Math.max(1, Math.round(physicalWidth * renderScale))
        val windowHeight = Math.max(1, Math.round(physicalHeight * renderScale))

        CallbackBridge.physicalWidth = physicalWidth
        CallbackBridge.physicalHeight = physicalHeight
        CallbackBridge.windowWidth = windowWidth
        CallbackBridge.windowHeight = windowHeight
        CallbackBridge.sendUpdateWindowSize(windowWidth, windowHeight)
    }

    private fun applyTextureBufferSize(@NonNull surface: SurfaceTexture) {
        val scaledWidth = Math.max(1, Math.round(surfaceBufferWidth * renderScale))
        val scaledHeight = Math.max(1, Math.round(surfaceBufferHeight * renderScale))
        surface.setDefaultBufferSize(scaledWidth, scaledHeight)
    }

    private fun shouldUseTextureViewSurface(requestedRenderer: RendererBackend, scale: Float): Boolean {
        if (requestedRenderer == RendererBackend.KOPPER_ZINK) {
            return true
        }
        return scale < MAX_RENDER_SCALE
    }

    private fun resolvePhysicalWidth(): Int = resolveRawPhysicalWidth()

    private fun resolvePhysicalHeight(): Int = resolveRawPhysicalHeight()

    private fun resolveRawPhysicalWidth(): Int {
        val viewWidth = if (::renderView.isInitialized) renderView.width else 0
        return Math.max(1, if (surfaceBufferWidth > 0) surfaceBufferWidth else viewWidth)
    }

    private fun resolveRawPhysicalHeight(): Int {
        val viewHeight = if (::renderView.isInitialized) renderView.height else 0
        return Math.max(1, if (surfaceBufferHeight > 0) surfaceBufferHeight else viewHeight)
    }

    private fun syncDisplayConfigToSurfaceSize() {
        val windowWidth = Math.max(1, CallbackBridge.windowWidth)
        val windowHeight = Math.max(1, CallbackBridge.windowHeight)
        try {
            DisplayConfigSync.syncToCurrentResolution(this, windowWidth, windowHeight, targetFps)
            Logger.appendToLog(
                "Display config synced to ${Math.max(800, windowWidth)}x${Math.max(450, windowHeight)} @$targetFps fps"
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to sync info.displayconfig", error)
            try {
                Logger.appendToLog(
                    "Display config sync failed: ${error.javaClass.simpleName}: ${error.message}"
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun resolveRenderScale(): Float {
        val configFile = File(RuntimePaths.stsRoot(this), "render_scale.txt")
        if (!configFile.exists()) {
            return DEFAULT_RENDER_SCALE
        }
        return try {
            val value = String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8).trim()
            if (value.isNotEmpty()) {
                val parsed = value.toFloat()
                when {
                    parsed < MIN_RENDER_SCALE -> MIN_RENDER_SCALE
                    parsed > MAX_RENDER_SCALE -> MAX_RENDER_SCALE
                    else -> parsed
                }
            } else {
                DEFAULT_RENDER_SCALE
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Invalid render_scale.txt, fallback to default", error)
            DEFAULT_RENDER_SCALE
        }
    }

    private fun sanitizeTargetFps(requestedFps: Int): Int {
        return if (
            requestedFps == 60 ||
            requestedFps == 90 ||
            requestedFps == 120 ||
            requestedFps == 240
        ) requestedFps else DEFAULT_TARGET_FPS
    }

    private fun mapBootOverlayPreparationProgress(percent: Int): Int {
        val bounded = Math.max(0, Math.min(100, percent))
        val ratio = bounded / 100f
        return 12 + Math.round((24 - 12) * ratio)
    }

    private fun findLaunchArgValue(args: List<String>?, keyPrefix: String?): String {
        if (args == null || keyPrefix.isNullOrEmpty()) {
            return "${keyPrefix}<invalid>"
        }
        for (arg in args) {
            if (arg.startsWith(keyPrefix)) {
                return arg
            }
        }
        return "${keyPrefix}<missing>"
    }

    private fun applyImmersiveMode() {
        applyDisplayCutoutMode()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun applyDisplayCutoutMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }
        val attributes = window.attributes
        if (attributes.layoutInDisplayCutoutMode == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
            return
        }
        attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = attributes
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return handleTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (isGamepadMotionEvent(event)) {
            return handleGamepadMotionEvent(event)
        }
        val source = event.source
        if ((source and InputDevice.SOURCE_MOUSE) != 0 || (source and InputDevice.SOURCE_TOUCHPAD) != 0) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                    moveCursor(event.x, event.y)
                    return true
                }

                MotionEvent.ACTION_SCROLL -> {
                    CallbackBridge.sendScroll(
                        event.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble(),
                        event.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble()
                    )
                    return true
                }

                MotionEvent.ACTION_BUTTON_PRESS -> return sendMouseButton(event.actionButton, true)
                MotionEvent.ACTION_BUTTON_RELEASE -> return sendMouseButton(event.actionButton, false)
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun isGamepadKeyEvent(@Nullable event: KeyEvent?): Boolean {
        if (event == null) {
            return false
        }
        val source = event.source
        if ((source and InputDevice.SOURCE_GAMEPAD) != 0 || (source and InputDevice.SOURCE_JOYSTICK) != 0) {
            return true
        }
        val device = event.device ?: return false
        val deviceSources = device.sources
        return (deviceSources and InputDevice.SOURCE_GAMEPAD) != 0 ||
            (deviceSources and InputDevice.SOURCE_JOYSTICK) != 0
    }

    private fun isGamepadMotionEvent(@Nullable event: MotionEvent?): Boolean {
        if (event == null || event.actionMasked != MotionEvent.ACTION_MOVE) {
            return false
        }
        val source = event.source
        return (source and InputDevice.SOURCE_JOYSTICK) != 0 ||
            (source and InputDevice.SOURCE_GAMEPAD) != 0
    }

    private fun handleGamepadKeyEvent(@NonNull event: KeyEvent): Boolean {
        if (!isNativeInputDispatchReady()) {
            return true
        }
        ensureGamepadDirectInputEnabled()
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return true
        }
        AndroidGamepadGlfwMapper.writeKeyEvent(event.keyCode, action == KeyEvent.ACTION_DOWN)
        return true
    }

    private fun handleGamepadMotionEvent(@NonNull event: MotionEvent): Boolean {
        if (!isNativeInputDispatchReady()) {
            return true
        }
        ensureGamepadDirectInputEnabled()
        AndroidGamepadGlfwMapper.writeMotionEvent(event)
        return true
    }

    private fun ensureGamepadDirectInputEnabled() {
        if (gamepadDirectInputEnableAttempted) {
            return
        }
        gamepadDirectInputEnableAttempted = true
        try {
            val enabled = CallbackBridge.nativeEnableGamepadDirectInput()
            Log.i(TAG, "Requested gamepad direct input: $enabled")
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to enable gamepad direct input", error)
        }
    }

    private fun resetGamepadState() {
        try {
            AndroidGamepadGlfwMapper.resetState()
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to reset gamepad state", error)
        }
    }

    private fun sendMouseButton(androidButton: Int, down: Boolean): Boolean {
        if (!isNativeInputDispatchReady()) {
            return true
        }
        val glfwButton = when (androidButton) {
            MotionEvent.BUTTON_PRIMARY -> LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
            MotionEvent.BUTTON_SECONDARY -> LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()
            MotionEvent.BUTTON_TERTIARY -> LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE.toInt()
            else -> return false
        }
        CallbackBridge.sendMouseButton(glfwButton, down)
        return true
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        if (!isNativeInputDispatchReady()) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                moveCursor(event.getX(0), event.getY(0))
                pressLeftIfNeeded()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val downIndex = event.actionIndex
                activePointerId = event.getPointerId(downIndex)
                moveCursor(event.getX(downIndex), event.getY(downIndex))
                pressLeftIfNeeded()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                var pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0 && event.pointerCount > 0) {
                    activePointerId = event.getPointerId(0)
                    pointerIndex = 0
                }
                if (pointerIndex >= 0) {
                    moveCursor(event.getX(pointerIndex), event.getY(pointerIndex))
                    return true
                }
                return false
            }

            MotionEvent.ACTION_POINTER_UP -> {
                handlePointerUp(event)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                releaseLeftIfNeeded()
                resetTouchState()
                return true
            }

            else -> return false
        }
    }

    private fun handlePointerUp(event: MotionEvent) {
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)
        val remaining = event.pointerCount - 1

        if (pointerId == activePointerId) {
            activePointerId = MotionEvent.INVALID_POINTER_ID
            for (i in 0 until event.pointerCount) {
                if (i == actionIndex) {
                    continue
                }
                activePointerId = event.getPointerId(i)
                moveCursor(event.getX(i), event.getY(i))
                break
            }
        }
        if (remaining <= 0) {
            releaseLeftIfNeeded()
            resetTouchState()
        }
    }

    private fun moveCursor(x: Float, y: Float) {
        if (!isNativeInputDispatchReady()) {
            return
        }
        val mapped = mapToWindowCoords(x, y)
        CallbackBridge.sendCursorPos(mapped[0], mapped[1])
    }

    private fun mapToWindowCoords(viewX: Float, viewY: Float): FloatArray {
        val rawViewWidth = if (::renderView.isInitialized) renderView.width else 0
        val rawViewHeight = if (::renderView.isInitialized) renderView.height else 0
        return mapViewToWindowCoords(
            viewX = viewX,
            viewY = viewY,
            rawViewWidth = rawViewWidth,
            rawViewHeight = rawViewHeight,
            windowWidthRaw = CallbackBridge.windowWidth,
            windowHeightRaw = CallbackBridge.windowHeight
        )
    }

    private fun releaseTextureSurfaceIfNeeded() {
        textureSurface?.let {
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        textureSurface = null
    }

    private fun pressLeftIfNeeded() {
        if (!isNativeInputDispatchReady()) {
            return
        }
        if (leftPressed) {
            return
        }
        CallbackBridge.sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt(), true)
        leftPressed = true
    }

    private fun releaseLeftIfNeeded() {
        if (!isNativeInputDispatchReady()) {
            leftPressed = false
            return
        }
        if (!leftPressed) {
            return
        }
        CallbackBridge.sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt(), false)
        leftPressed = false
    }

    private fun resetTouchState() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return handleAndroidBackKeyEvent(event)
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            return handleVolumeKeyEvent(event)
        }
        if (isGamepadKeyEvent(event)) {
            return handleGamepadKeyEvent(event)
        }
        if (!isNativeInputDispatchReady()) {
            return super.dispatchKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_MULTIPLE) {
            return true
        }

        val glfwKey = AndroidGlfwKeycode.toGlfw(keyCode)
        if (glfwKey == AndroidGlfwKeycode.GLFW_KEY_UNKNOWN) {
            return super.dispatchKeyEvent(event)
        }

        val isDown = event.action == KeyEvent.ACTION_DOWN
        CallbackBridge.setModifiers(glfwKey, isDown)
        CallbackBridge.sendKeyPress(glfwKey, 0, CallbackBridge.getCurrentMods(), isDown)

        val unicode = event.unicodeChar
        if (isDown && unicode > 0 && !Character.isISOControl(unicode)) {
            CallbackBridge.sendChar(unicode.toChar(), CallbackBridge.getCurrentMods())
        }
        return true
    }

    private fun isNativeInputDispatchReady(): Boolean {
        if (backExitRequested) {
            return false
        }
        if (!runtimeLifecycleReady) {
            return false
        }
        if (!bridgeSurfaceReady) {
            return false
        }
        return CallbackBridge.windowWidth > 0 && CallbackBridge.windowHeight > 0
    }

    private fun handleVolumeKeyEvent(@NonNull event: KeyEvent): Boolean {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager?
        if (audioManager == null) {
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val direction = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
                KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
                KeyEvent.KEYCODE_VOLUME_MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
                else -> return false
            }
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
        }
        return true
    }
}

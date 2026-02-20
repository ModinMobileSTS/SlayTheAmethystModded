package io.stamethyst;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.oracle.dalvik.VMLauncher;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.utils.JREUtils;

import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.stamethyst.input.AndroidGamepadGlfwMapper;
import io.stamethyst.input.AndroidGlfwKeycode;

public class StsGameActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String TAG = "StsGameActivity";
    public static final String EXTRA_LAUNCH_MODE = "io.stamethyst.launch_mode";
    public static final String EXTRA_RENDERER_BACKEND = "io.stamethyst.renderer_backend";
    public static final String EXTRA_PRELAUNCH_PREPARED = "io.stamethyst.prelaunch_prepared";
    public static final String EXTRA_WAIT_FOR_MAIN_MENU = "io.stamethyst.wait_for_main_menu";
    public static final String EXTRA_BACK_IMMEDIATE_EXIT = "io.stamethyst.back_immediate_exit";
    public static final String EXTRA_TARGET_FPS = "io.stamethyst.target_fps";
    private static final float DEFAULT_RENDER_SCALE = 0.75f;
    private static final float MIN_RENDER_SCALE = 0.50f;
    private static final float MAX_RENDER_SCALE = 1.00f;
    private static final int DEFAULT_TARGET_FPS = 120;
    private static final long BOOT_OVERLAY_MIN_VISIBLE_MS = 1200L;
    private static final long BOOT_OVERLAY_READY_DELAY_MS = 700L;
    private static final long BACK_FORCE_RESTART_DELAY_MS = 120L;
    private static final int BOOT_LOG_MAX_LINES = 220;

    @Nullable
    private SurfaceView surfaceView;
    @Nullable
    private TextureView textureView;
    @Nullable
    private Surface textureSurface;
    private View renderView;
    private boolean useTextureViewSurface = false;
    private volatile boolean vmStarted = false;
    private volatile boolean runtimeLifecycleReady = false;
    private volatile boolean backExitRequested = false;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private boolean leftPressed = false;
    private boolean gamepadDirectInputEnableAttempted = false;
    private int surfaceBufferWidth = 0;
    private int surfaceBufferHeight = 0;
    private long waitingLandscapeSinceMs = -1L;
    private boolean startCheckPosted = false;
    private float renderScale = DEFAULT_RENDER_SCALE;
    private int targetFps = DEFAULT_TARGET_FPS;
    private String launchMode = StsLaunchSpec.LAUNCH_MODE_VANILLA;
    private RendererBackend launcherRequestedRenderer = RendererBackend.OPENGL_ES2;
    private boolean prelaunchPrepared = false;
    private boolean waitForMainMenu = false;
    private boolean backImmediateExit = true;
    private boolean jvmLogListenerRegistered = false;
    private View bootOverlay;
    private ProgressBar bootOverlayProgressBar;
    private TextView bootOverlayStatusText;
    private ScrollView bootOverlayLogScroll;
    private TextView bootOverlayLogText;
    private int bootOverlayProgress = 0;
    private String bootOverlayMessage = "";
    private long bootOverlayShownAtMs = -1L;
    private boolean bootOverlayDismissed = false;
    private boolean mainMenuReadySignaled = false;
    private boolean launchFailureSignaled = false;
    private volatile boolean earlyOverlayDismissOnNextFrame = false;
    private volatile long lastTextureFrameTimestampNs = 0L;
    private volatile long earlyOverlayDismissRequestFrameTimestampNs = 0L;
    @Nullable
    private volatile Thread jvmLaunchThread;
    private Thread bootBridgeReaderThread;
    private volatile boolean bootBridgeReaderStop = false;
    private final ArrayDeque<String> bootLogLines = new ArrayDeque<>();
    private final Logger.eventLogListener jvmLogcatListener =
            this::onJvmLogMessage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game);
        applyImmersiveMode();
        renderScale = resolveRenderScale();
        String requestedMode = getIntent().getStringExtra(EXTRA_LAUNCH_MODE);
        if (StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD.equals(requestedMode)) {
            launchMode = StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD;
        }
        launcherRequestedRenderer = RendererBackend.fromRendererId(
                getIntent().getStringExtra(EXTRA_RENDERER_BACKEND)
        );
        prelaunchPrepared = getIntent().getBooleanExtra(EXTRA_PRELAUNCH_PREPARED, false);
        waitForMainMenu = getIntent().getBooleanExtra(
                EXTRA_WAIT_FOR_MAIN_MENU,
                StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD.equals(launchMode)
        );
        backImmediateExit = getIntent().getBooleanExtra(EXTRA_BACK_IMMEDIATE_EXIT, true);
        targetFps = sanitizeTargetFps(getIntent().getIntExtra(EXTRA_TARGET_FPS, DEFAULT_TARGET_FPS));
        useTextureViewSurface = shouldUseTextureViewSurface(launcherRequestedRenderer, renderScale);
        initBootOverlay();

        FrameLayout root = findViewById(R.id.gameRoot);
        ViewGroup.LayoutParams renderLayoutParams = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        if (useTextureViewSurface) {
            if (launcherRequestedRenderer == RendererBackend.KOPPER_ZINK) {
                Log.i(TAG, "Using TextureView surface path for Kopper renderer");
            } else {
                Log.i(TAG, "Using TextureView surface path for scaled rendering: renderScale=" + renderScale);
            }
            TextureView view = new TextureView(this);
            view.setOpaque(true);
            textureView = view;
            renderView = view;
            root.addView(view, renderLayoutParams);
            view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    surfaceBufferWidth = Math.max(1, width);
                    surfaceBufferHeight = Math.max(1, height);
                    applyTextureBufferSize(surface);
                    releaseTextureSurfaceIfNeeded();
                    textureSurface = new Surface(surface);
                    JREUtils.setupBridgeWindow(textureSurface);
                    updateWindowSize();
                    tryStartJvmWhenSurfaceReady();
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                    surfaceBufferWidth = Math.max(1, width);
                    surfaceBufferHeight = Math.max(1, height);
                    applyTextureBufferSize(surface);
                    updateWindowSize();
                    tryStartJvmWhenSurfaceReady();
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    JREUtils.releaseBridgeWindow();
                    releaseTextureSurfaceIfNeeded();
                    surfaceBufferWidth = 0;
                    surfaceBufferHeight = 0;
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                    lastTextureFrameTimestampNs = surface.getTimestamp();
                    if (earlyOverlayDismissOnNextFrame
                            && lastTextureFrameTimestampNs > earlyOverlayDismissRequestFrameTimestampNs) {
                        earlyOverlayDismissOnNextFrame = false;
                        runOnUiThread(() -> {
                            updateBootOverlayProgress(Math.max(bootOverlayProgress, 99), "Game frame ready");
                            dismissBootOverlay();
                        });
                    }
                }
            });
        } else {
            SurfaceView view = new SurfaceView(this);
            surfaceView = view;
            renderView = view;
            root.addView(view, renderLayoutParams);
            view.getHolder().addCallback(this);
        }

        renderView.setFocusable(true);
        renderView.setFocusableInTouchMode(true);
        renderView.setOnTouchListener((v, event) -> handleTouchEvent(event));
        renderView.requestFocus();
    }

    @Override
    protected void onDestroy() {
        resetGamepadState();
        runtimeLifecycleReady = false;
        earlyOverlayDismissOnNextFrame = false;
        stopBootBridgeReaderIfRunning();
        Thread launchThread = jvmLaunchThread;
        jvmLaunchThread = null;
        if (launchThread != null) {
            launchThread.interrupt();
        }
        if (useTextureViewSurface) {
            try {
                JREUtils.releaseBridgeWindow();
            } catch (Throwable ignored) {
            }
        }
        releaseTextureSurfaceIfNeeded();
        if (jvmLogListenerRegistered) {
            try {
                Logger.removeLogListener(jvmLogcatListener);
            } catch (Throwable ignored) {
            }
            jvmLogListenerRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (holder.getSurfaceFrame() != null) {
            surfaceBufferWidth = holder.getSurfaceFrame().width();
            surfaceBufferHeight = holder.getSurfaceFrame().height();
        }
        JREUtils.setupBridgeWindow(holder.getSurface());
        updateWindowSize();
        tryStartJvmWhenSurfaceReady();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        surfaceBufferWidth = width;
        surfaceBufferHeight = height;
        updateWindowSize();
        tryStartJvmWhenSurfaceReady();
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        JREUtils.releaseBridgeWindow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        resetGamepadState();
        if (runtimeLifecycleReady) {
            CallbackBridge.nativeSetAudioMuted(false);
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 1);
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_ICONIFIED, 0);
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1);
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1);
        }
        tryStartJvmWhenSurfaceReady();
    }

    @Override
    protected void onPause() {
        resetGamepadState();
        if (runtimeLifecycleReady) {
            CallbackBridge.nativeSetAudioMuted(true);
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_ICONIFIED, 1);
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 0);
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0);
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 0);
        }
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    @Override
    public void onBackPressed() {
        if (!backImmediateExit) {
            Log.i(TAG, "Android back pressed: disabled by launcher setting");
            return;
        }
        requestBackExitToLauncher();
    }

    private void requestBackExitToLauncher() {
        if (backExitRequested) {
            return;
        }
        backExitRequested = true;
        stopBootBridgeReaderIfRunning();
        updateBootOverlayProgress(100, "Stopping game...");
        Log.i(TAG, "Android back pressed: force restart to launcher");

        Thread launchThread = jvmLaunchThread;
        if (launchThread != null) {
            launchThread.interrupt();
        }
        try {
            CallbackBridge.nativeSetInputReady(false);
        } catch (Throwable ignored) {
        }
        requestJvmCloseSignal();
        scheduleLauncherRestartAndKillProcess();
    }

    private boolean requestJvmCloseSignal() {
        try {
            boolean requested = CallbackBridge.nativeRequestCloseWindow();
            if (requested) {
                Log.i(TAG, "Sent glfwSetWindowShouldClose=true");
            }
            return requested;
        } catch (Throwable error) {
            Log.w(TAG, "Failed to request JVM window close", error);
            return false;
        }
    }

    private void scheduleLauncherRestartAndKillProcess() {
        Intent launcherIntent = new Intent(this, LauncherActivity.class);
        launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        int flags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0x71A7, launcherIntent, flags);
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        long triggerAt = SystemClock.elapsedRealtime() + BACK_FORCE_RESTART_DELAY_MS;
        if (alarmManager != null) {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
        } else {
            try {
                pendingIntent.send();
            } catch (PendingIntent.CanceledException ignored) {
            }
        }
        finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    private void startJvmOnce() {
        if (vmStarted) {
            return;
        }
        if (backExitRequested) {
            finish();
            return;
        }
        vmStarted = true;
        runtimeLifecycleReady = false;
        updateBootOverlayProgress(8, "Starting JVM...");

        Thread launchThread = new Thread(() -> {
            try {
                if (backExitRequested) {
                    runOnUiThread(this::finish);
                    return;
                }
                if (prelaunchPrepared) {
                    Log.i(TAG, "Skip prelaunch prepare steps in game activity (already prepared)");
                    updateBootOverlayProgress(12, "Prelaunch checks already done");
                } else {
                    updateBootOverlayProgress(12, "Installing runtime components...");
                    ComponentInstaller.ensureInstalled(this);
                    updateBootOverlayProgress(16, "Preparing Java runtime...");
                    RuntimePackInstaller.ensureInstalled(this);
                    updateBootOverlayProgress(20, "Validating game files...");
                    StsJarValidator.validate(RuntimePaths.importedStsJar(this));
                    if (StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD.equals(launchMode)) {
                        updateBootOverlayProgress(22, "Validating MTS dependencies...");
                        ModJarSupport.validateMtsJar(RuntimePaths.importedMtsJar(this));
                        ModJarSupport.validateBaseModJar(RuntimePaths.importedBaseModJar(this));
                        ModJarSupport.validateStsLibJar(RuntimePaths.importedStsLibJar(this));
                        updateBootOverlayProgress(24, "Preparing MTS classpath...");
                        ModJarSupport.prepareMtsClasspath(this);
                    }
                }

                File runtimeRoot = RuntimePaths.runtimeRoot(this);
                File javaHome = RuntimePackInstaller.locateJavaHome(runtimeRoot);
                if (javaHome == null) {
                    throw new IllegalStateException("No Java home found in " + runtimeRoot.getAbsolutePath());
                }

                RuntimePaths.ensureBaseDirs(this);
                File logFile = RuntimePaths.latestLog(this);
                File logParent = logFile.getParentFile();
                if (logParent != null && !logParent.exists() && !logParent.mkdirs()) {
                    throw new IllegalStateException("Failed to create log directory: " + logParent.getAbsolutePath());
                }
                if (!logFile.exists() && !logFile.createNewFile()) {
                    throw new IllegalStateException("Failed to create log file: " + logFile.getAbsolutePath());
                }
                Logger.begin(logFile.getAbsolutePath());
                try {
                    Logger.addLogListener(jvmLogcatListener);
                    jvmLogListenerRegistered = true;
                } catch (Throwable ignored) {
                    jvmLogListenerRegistered = false;
                }
                if (waitForMainMenu && StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD.equals(launchMode)) {
                    startBootBridgeReader();
                    updateBootOverlayProgress(26, "Waiting for structured boot events...");
                }
                Logger.appendToLog("Launching STS with java home: " + javaHome.getAbsolutePath());
                Logger.appendToLog("Launch mode: " + launchMode);
                Logger.appendToLog("Surface backend: " + (useTextureViewSurface ? "TextureView" : "SurfaceView"));
                boolean originalFboPatchEnabled = CompatibilitySettings.isOriginalFboPatchEnabled(this);
                boolean downfallFboPatchEnabled = CompatibilitySettings.isDownfallFboPatchEnabled(this);
                Logger.appendToLog("Compat settings: originalFboPatch=" + originalFboPatchEnabled
                        + ", downfallFboPatch=" + downfallFboPatchEnabled);
                RendererBackend preferredRenderer = RendererConfig.readPreferredBackend(this);
                RendererConfig.ResolutionResult rendererDecision =
                        RendererConfig.resolveEffectiveBackend(this, preferredRenderer);
                RendererBackend effectiveRenderer = rendererDecision.effective;
                Logger.appendToLog("Renderer from launcher intent: " + launcherRequestedRenderer.rendererId());
                Logger.appendToLog("Renderer decision in game: " + rendererDecision.toLogText());
                Logger.appendToLog("Renderer GL library expected: " + effectiveRenderer.lwjglOpenGlLibName());
                if (launcherRequestedRenderer != effectiveRenderer) {
                    Logger.appendToLog(
                            "Renderer changed after re-check: launcher_effective="
                                    + launcherRequestedRenderer.rendererId()
                                    + ", game_effective="
                                    + effectiveRenderer.rendererId()
                    );
                }
                if (StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD.equals(launchMode)) {
                    ModJarSupport.appendCompatDiagnosticSnapshot(this, "game_pre_jvm");
                }
                updateWindowSize();
                Logger.appendToLog("Render scale: " + renderScale
                        + ", surface(raw)=" + resolveRawPhysicalWidth() + "x" + resolveRawPhysicalHeight()
                        + ", surface(effective)=" + resolvePhysicalWidth() + "x" + resolvePhysicalHeight()
                        + ", window=" + CallbackBridge.windowWidth + "x" + CallbackBridge.windowHeight);
                Logger.appendToLog("Target FPS limit: " + targetFps);
                syncDisplayConfigToSurfaceSize();
                JREUtils.relocateLibPath(getApplicationInfo().nativeLibraryDir, javaHome.getAbsolutePath());
                JREUtils.setJavaEnvironment(this, javaHome.getAbsolutePath(),
                        Math.max(1, CallbackBridge.windowWidth),
                        Math.max(1, CallbackBridge.windowHeight),
                        effectiveRenderer);
                JREUtils.initJavaRuntime(javaHome.getAbsolutePath());
                JREUtils.setupExitMethod(getApplicationContext());
                JREUtils.initializeHooks();
                JREUtils.chdir(RuntimePaths.stsRoot(this).getAbsolutePath());
                runtimeLifecycleReady = true;

                CallbackBridge.nativeSetUseInputStackQueue(true);
                CallbackBridge.nativeSetInputReady(true);

                List<String> launchArgs = new ArrayList<>();
                launchArgs.add("java");
                launchArgs.addAll(StsLaunchSpec.buildArgs(this, javaHome, launchMode, effectiveRenderer));
                if (backExitRequested) {
                    runOnUiThread(this::finish);
                    return;
                }
                if (StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD.equals(launchMode)) {
                    updateBootOverlayProgress(28, "Launching ModTheSpire...");
                } else {
                    updateBootOverlayProgress(85, "Launching game...");
                }
                Logger.appendToLog("Launch arg check: "
                        + findLaunchArgValue(launchArgs, "-Dorg.lwjgl.opengl.libname=")
                        + ", "
                        + findLaunchArgValue(launchArgs, "-Damethyst.gdx.fbo_fallback=")
                        + ", "
                        + findLaunchArgValue(launchArgs, "-Dorg.lwjgl.librarypath="));
                Logger.appendToLog("Launch args: " + launchArgs);

                int exitCode = VMLauncher.launchJVM(launchArgs.toArray(new String[0]));
                Logger.appendToLog("Java Exit code: " + exitCode);
                if (backExitRequested) {
                    runOnUiThread(this::finish);
                    return;
                }
                if (exitCode == 0) {
                    if (waitForMainMenu
                            && StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD.equals(launchMode)
                            && !mainMenuReadySignaled
                            && !bootOverlayDismissed) {
                        runOnUiThread(() -> reportCrashAndReturn(
                                -3,
                                false,
                                "JVM exited before reaching main menu"
                        ));
                    } else {
                        runOnUiThread(this::finish);
                    }
                } else {
                    runOnUiThread(() -> reportCrashAndReturn(exitCode, false, null));
                }
            } catch (Throwable t) {
                runtimeLifecycleReady = false;
                Log.e(TAG, "Launch failed", t);
                try {
                    Logger.appendToLog("Launch failed: " + t);
                } catch (Throwable ignored) {
                }
                if (backExitRequested) {
                    runOnUiThread(this::finish);
                    return;
                }
                String message = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
                runOnUiThread(() -> reportCrashAndReturn(-1, false, message));
            } finally {
                runtimeLifecycleReady = false;
                jvmLaunchThread = null;
            }
        }, "STS-JVM-Thread");
        jvmLaunchThread = launchThread;
        launchThread.start();
    }

    private void tryStartJvmWhenSurfaceReady() {
        if (backExitRequested) {
            return;
        }
        if (vmStarted) {
            return;
        }
        int rawWidth = resolveRawPhysicalWidth();
        int rawHeight = resolveRawPhysicalHeight();
        if (rawWidth <= 1 || rawHeight <= 1) {
            Log.i(TAG, "Waiting for valid surface size before JVM start: " + rawWidth + "x" + rawHeight);
            scheduleStartCheck();
            return;
        }
        if (rawWidth < rawHeight) {
            long now = SystemClock.uptimeMillis();
            if (waitingLandscapeSinceMs < 0L) {
                waitingLandscapeSinceMs = now;
            }
            long waitedMs = now - waitingLandscapeSinceMs;
            if (waitedMs < 4000L) {
                Log.i(TAG, "Waiting for landscape surface before JVM start: "
                        + rawWidth + "x" + rawHeight + ", waited=" + waitedMs + "ms");
                scheduleStartCheck();
                return;
            }
            Log.w(TAG, "Surface is still portrait after wait, starting JVM anyway: "
                    + rawWidth + "x" + rawHeight);
        } else {
            waitingLandscapeSinceMs = -1L;
        }
        startJvmOnce();
    }

    private void scheduleStartCheck() {
        if (vmStarted || startCheckPosted) {
            return;
        }
        startCheckPosted = true;
        renderView.postDelayed(() -> {
            startCheckPosted = false;
            tryStartJvmWhenSurfaceReady();
        }, 120L);
    }

    private void initBootOverlay() {
        bootOverlay = findViewById(R.id.bootOverlay);
        bootOverlayProgressBar = findViewById(R.id.bootOverlayProgressBar);
        bootOverlayStatusText = findViewById(R.id.bootOverlayStatusText);
        bootOverlayLogScroll = findViewById(R.id.bootOverlayLogScroll);
        bootOverlayLogText = findViewById(R.id.bootOverlayLogText);
        if (bootOverlay == null || bootOverlayProgressBar == null || bootOverlayStatusText == null) {
            waitForMainMenu = false;
            return;
        }
        if (!waitForMainMenu) {
            bootOverlay.setVisibility(View.GONE);
            bootOverlayDismissed = true;
            return;
        }
        synchronized (bootLogLines) {
            bootLogLines.clear();
        }
        if (bootOverlayLogText != null) {
            bootOverlayLogText.setText("");
        }
        bootOverlay.setVisibility(View.VISIBLE);
        bootOverlay.setOnTouchListener((v, event) -> true);
        bootOverlayShownAtMs = SystemClock.uptimeMillis();
        updateBootOverlayProgress(1, "Starting launch pipeline...");
    }

    private void onJvmLogMessage(String text) {
        Log.i(TAG, "[JVM] " + text);
        if (!waitForMainMenu || bootOverlayDismissed || text == null) {
            return;
        }
        String[] lines = text.split("\\r?\\n");
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            appendBootOverlayLog(raw);
            if (!launchFailureSignaled) {
                String fatal = detectFatalStartupLog(line);
                if (fatal != null) {
                    signalLaunchFailure(fatal);
                    continue;
                }
            }
            if (!bootOverlayDismissed && shouldDismissOverlayEarlyLog(line)) {
                runOnUiThread(() -> {
                    updateBootOverlayProgress(Math.max(bootOverlayProgress, 98), "Starting game...");
                    requestEarlyOverlayDismiss();
                });
            }
        }
    }

    @Nullable
    private String detectFatalStartupLog(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("com.evacipated.cardcrawl.modthespire.patcher.patchingexception")) {
            return "MTS patching failed: " + line;
        }
        if (lower.contains("missingdependencyexception")) {
            return "MTS missing dependency: " + line;
        }
        if (lower.contains("duplicatemodidexception")) {
            return "MTS duplicate mod id: " + line;
        }
        if (lower.contains("missingmodidexception")) {
            return "MTS missing mod id: " + line;
        }
        if (lower.contains("illegal patch parameter")) {
            return "MTS illegal patch parameter: " + line;
        }
        return null;
    }

    private boolean shouldDismissOverlayEarlyLog(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        // Delay until CardCrawlGame init starts to avoid hiding slightly before logo appears.
        if (lower.contains("core.cardcrawlgame> distributorplatform=")) {
            return true;
        }
        if (lower.contains("core.cardcrawlgame> ismodded=")) {
            return true;
        }
        if (lower.contains("core.cardcrawlgame> no migration")) {
            return true;
        }
        // BaseMod lifecycle reaches this stage only after the game is effectively at main-menu flow.
        if (lower.contains("publishaddcustommodemods")) {
            return true;
        }
        // If the player already entered an event, startup is definitely complete.
        return lower.contains("events.heartevent>");
    }

    private void requestEarlyOverlayDismiss() {
        if (bootOverlayDismissed || bootOverlay == null) {
            return;
        }
        if (useTextureViewSurface && textureView != null) {
            earlyOverlayDismissRequestFrameTimestampNs = lastTextureFrameTimestampNs;
            earlyOverlayDismissOnNextFrame = true;
            return;
        }
        dismissBootOverlay();
    }

    private void startBootBridgeReader() {
        stopBootBridgeReaderIfRunning();
        File eventsFile = RuntimePaths.bootBridgeEventsFile(this);
        File parent = eventsFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (java.io.FileOutputStream ignored = new java.io.FileOutputStream(eventsFile, false)) {
            // Truncate old events.
        } catch (Throwable error) {
            Log.w(TAG, "Failed to reset boot bridge events file", error);
        }

        bootBridgeReaderStop = false;
        bootBridgeReaderThread = new Thread(() -> runBootBridgeReader(eventsFile), "BootBridge-Reader");
        bootBridgeReaderThread.setDaemon(true);
        bootBridgeReaderThread.start();
    }

    private void stopBootBridgeReaderIfRunning() {
        bootBridgeReaderStop = true;
        Thread thread = bootBridgeReaderThread;
        bootBridgeReaderThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void runBootBridgeReader(File eventsFile) {
        long offset = 0L;
        while (!bootBridgeReaderStop) {
            try {
                if (!eventsFile.exists()) {
                    sleepQuietly(80L);
                    continue;
                }
                try (RandomAccessFile raf = new RandomAccessFile(eventsFile, "r")) {
                    long length = raf.length();
                    if (length < offset) {
                        offset = 0L;
                    }
                    if (length > offset) {
                        raf.seek(offset);
                        String raw;
                        while (!bootBridgeReaderStop && (raw = raf.readLine()) != null) {
                            String line = new String(raw.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                            onBootBridgeEventLine(line);
                        }
                        offset = raf.getFilePointer();
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "Boot bridge reader error", error);
            }
            sleepQuietly(80L);
        }
    }

    private void onBootBridgeEventLine(String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        appendBootOverlayLog("[bridge] " + trimmed);

        String[] parts = trimmed.split("\\t", 3);
        String type = parts.length > 0 ? parts[0].trim().toUpperCase(Locale.ROOT) : "";
        int percent = parts.length > 1 ? parseSafeInt(parts[1], -1) : -1;
        String message = parts.length > 2 ? parts[2].trim() : "";

        switch (type) {
            case "PHASE":
                if (percent >= 0) {
                    updateBootOverlayProgress(percent, message.isEmpty() ? "Loading..." : message);
                }
                break;
            case "READY":
                signalMainMenuReady(message.isEmpty() ? "Main menu is ready" : message);
                break;
            case "FAIL":
                signalLaunchFailure(message.isEmpty() ? "Bridge reported startup failure" : message);
                break;
            default:
                break;
        }
    }

    private int parseSafeInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void signalLaunchFailure(String detail) {
        if (backExitRequested) {
            runOnUiThread(this::finish);
            return;
        }
        if (launchFailureSignaled) {
            return;
        }
        launchFailureSignaled = true;
        stopBootBridgeReaderIfRunning();
        Log.e(TAG, "Detected startup failure from boot bridge: " + detail);
        runOnUiThread(() -> reportCrashAndReturn(-2, false, detail));
    }


    private void signalMainMenuReady(String message) {
        if (!waitForMainMenu || mainMenuReadySignaled) {
            return;
        }
        mainMenuReadySignaled = true;
        stopBootBridgeReaderIfRunning();
        updateBootOverlayProgress(100, message);
        long now = SystemClock.uptimeMillis();
        long elapsed = bootOverlayShownAtMs <= 0L ? BOOT_OVERLAY_MIN_VISIBLE_MS : (now - bootOverlayShownAtMs);
        long minDelay = Math.max(0L, BOOT_OVERLAY_MIN_VISIBLE_MS - elapsed);
        long delay = Math.max(minDelay, BOOT_OVERLAY_READY_DELAY_MS);
        runOnUiThread(() -> {
            if (bootOverlay == null) {
                return;
            }
            bootOverlay.postDelayed(this::dismissBootOverlay, delay);
        });
    }

    private void dismissBootOverlay() {
        if (bootOverlayDismissed || bootOverlay == null) {
            return;
        }
        bootOverlayDismissed = true;
        earlyOverlayDismissOnNextFrame = false;
        earlyOverlayDismissRequestFrameTimestampNs = 0L;
        bootOverlay.setVisibility(View.GONE);
    }

    private void updateBootOverlayProgress(int percent, String message) {
        if (!waitForMainMenu) {
            return;
        }
        int bounded = Math.max(0, Math.min(100, percent));
        String normalizedMessage = message == null ? "" : message.trim();
        if (bounded < bootOverlayProgress) {
            return;
        }
        if (bounded == bootOverlayProgress && normalizedMessage.equals(bootOverlayMessage)) {
            return;
        }
        bootOverlayProgress = bounded;
        bootOverlayMessage = normalizedMessage;
        runOnUiThread(() -> {
            if (bootOverlayDismissed || bootOverlayProgressBar == null || bootOverlayStatusText == null) {
                return;
            }
            bootOverlayProgressBar.setProgress(bootOverlayProgress);
            if (!normalizedMessage.isEmpty()) {
                bootOverlayStatusText.setText(normalizedMessage + " (" + bootOverlayProgress + "%)");
            }
        });
    }

    private void appendBootOverlayLog(String rawLine) {
        if (!waitForMainMenu || rawLine == null) {
            return;
        }
        String line = rawLine.replace('\r', ' ').trim();
        if (line.isEmpty()) {
            return;
        }
        synchronized (bootLogLines) {
            bootLogLines.addLast(line);
            while (bootLogLines.size() > BOOT_LOG_MAX_LINES) {
                bootLogLines.removeFirst();
            }
        }
        runOnUiThread(this::renderBootOverlayLogs);
    }

    private void renderBootOverlayLogs() {
        if (bootOverlayDismissed || bootOverlayLogText == null) {
            return;
        }
        StringBuilder builder = new StringBuilder(4096);
        synchronized (bootLogLines) {
            for (String line : bootLogLines) {
                builder.append(line).append('\n');
            }
        }
        bootOverlayLogText.setText(builder.toString());
        if (bootOverlayLogScroll != null) {
            bootOverlayLogScroll.post(() -> bootOverlayLogScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void reportCrashAndReturn(int code, boolean isSignal, @Nullable String detail) {
        if (backExitRequested) {
            finish();
            return;
        }
        runtimeLifecycleReady = false;
        Intent launcherIntent = new Intent(this, LauncherActivity.class);
        launcherIntent.putExtra(LauncherActivity.EXTRA_CRASH_OCCURRED, true);
        launcherIntent.putExtra(LauncherActivity.EXTRA_CRASH_CODE, code);
        launcherIntent.putExtra(LauncherActivity.EXTRA_CRASH_IS_SIGNAL, isSignal);
        if (detail != null && !detail.trim().isEmpty()) {
            launcherIntent.putExtra(LauncherActivity.EXTRA_CRASH_DETAIL, detail.trim());
        }
        launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(launcherIntent);
        finish();
    }

    private void updateWindowSize() {
        int physicalWidth = resolvePhysicalWidth();
        int physicalHeight = resolvePhysicalHeight();
        int windowWidth = Math.max(1, Math.round(physicalWidth * renderScale));
        int windowHeight = Math.max(1, Math.round(physicalHeight * renderScale));

        CallbackBridge.physicalWidth = physicalWidth;
        CallbackBridge.physicalHeight = physicalHeight;
        CallbackBridge.windowWidth = windowWidth;
        CallbackBridge.windowHeight = windowHeight;
        CallbackBridge.sendUpdateWindowSize(windowWidth, windowHeight);
    }

    private void applyTextureBufferSize(@NonNull SurfaceTexture surface) {
        int scaledWidth = Math.max(1, Math.round(surfaceBufferWidth * renderScale));
        int scaledHeight = Math.max(1, Math.round(surfaceBufferHeight * renderScale));
        surface.setDefaultBufferSize(scaledWidth, scaledHeight);
    }

    private boolean shouldUseTextureViewSurface(RendererBackend requestedRenderer, float scale) {
        if (requestedRenderer == RendererBackend.KOPPER_ZINK) {
            return true;
        }
        return scale < MAX_RENDER_SCALE;
    }

    private int resolvePhysicalWidth() {
        return resolveRawPhysicalWidth();
    }

    private int resolvePhysicalHeight() {
        return resolveRawPhysicalHeight();
    }

    private int resolveRawPhysicalWidth() {
        int viewWidth = renderView == null ? 0 : renderView.getWidth();
        return Math.max(1, surfaceBufferWidth > 0 ? surfaceBufferWidth : viewWidth);
    }

    private int resolveRawPhysicalHeight() {
        int viewHeight = renderView == null ? 0 : renderView.getHeight();
        return Math.max(1, surfaceBufferHeight > 0 ? surfaceBufferHeight : viewHeight);
    }

    private void syncDisplayConfigToSurfaceSize() {
        int windowWidth = Math.max(1, CallbackBridge.windowWidth);
        int windowHeight = Math.max(1, CallbackBridge.windowHeight);
        try {
            DisplayConfigSync.syncToCurrentResolution(this, windowWidth, windowHeight, targetFps);
            Logger.appendToLog("Display config synced to "
                    + Math.max(800, windowWidth)
                    + "x"
                    + Math.max(450, windowHeight)
                    + " @"
                    + targetFps
                    + "fps");
        } catch (Throwable error) {
            Log.w(TAG, "Failed to sync info.displayconfig", error);
            try {
                Logger.appendToLog("Display config sync failed: "
                        + error.getClass().getSimpleName()
                        + ": "
                        + String.valueOf(error.getMessage()));
            } catch (Throwable ignored) {
            }
        }
    }

    private float resolveRenderScale() {
        File configFile = new File(RuntimePaths.stsRoot(this), "render_scale.txt");
        if (!configFile.exists()) {
            return DEFAULT_RENDER_SCALE;
        }
        try {
            String value = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8).trim();
            if (!value.isEmpty()) {
                float parsed = Float.parseFloat(value);
                if (parsed < MIN_RENDER_SCALE) {
                    return MIN_RENDER_SCALE;
                }
                if (parsed > MAX_RENDER_SCALE) {
                    return MAX_RENDER_SCALE;
                }
                return parsed;
            }
        } catch (Throwable error) {
            Log.w(TAG, "Invalid render_scale.txt, fallback to default", error);
        }
        return DEFAULT_RENDER_SCALE;
    }

    private int sanitizeTargetFps(int requestedFps) {
        if (requestedFps == 60
                || requestedFps == 90
                || requestedFps == 120
                || requestedFps == 240) {
            return requestedFps;
        }
        return DEFAULT_TARGET_FPS;
    }

    private String findLaunchArgValue(List<String> args, String keyPrefix) {
        if (args == null || keyPrefix == null || keyPrefix.isEmpty()) {
            return keyPrefix + "<invalid>";
        }
        for (String arg : args) {
            if (arg != null && arg.startsWith(keyPrefix)) {
                return arg;
            }
        }
        return keyPrefix + "<missing>";
    }

    private void applyImmersiveMode() {
        applyDisplayCutoutMode();
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller == null) {
            return;
        }
        controller.hide(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void applyDisplayCutoutMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        if (attributes.layoutInDisplayCutoutMode == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
            return;
        }
        attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(attributes);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return handleTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (isGamepadMotionEvent(event)) {
            return handleGamepadMotionEvent(event);
        }
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_MOUSE) != 0 || (source & InputDevice.SOURCE_TOUCHPAD) != 0) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_MOVE:
                case MotionEvent.ACTION_MOVE:
                    moveCursor(event.getX(), event.getY());
                    return true;
                case MotionEvent.ACTION_SCROLL:
                    CallbackBridge.sendScroll(event.getAxisValue(MotionEvent.AXIS_HSCROLL), event.getAxisValue(MotionEvent.AXIS_VSCROLL));
                    return true;
                case MotionEvent.ACTION_BUTTON_PRESS:
                    return sendMouseButton(event.getActionButton(), true);
                case MotionEvent.ACTION_BUTTON_RELEASE:
                    return sendMouseButton(event.getActionButton(), false);
                default:
                    break;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    private boolean isGamepadKeyEvent(@Nullable KeyEvent event) {
        if (event == null) {
            return false;
        }
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) != 0 || (source & InputDevice.SOURCE_JOYSTICK) != 0) {
            return true;
        }
        InputDevice device = event.getDevice();
        if (device != null) {
            int deviceSources = device.getSources();
            return (deviceSources & InputDevice.SOURCE_GAMEPAD) != 0
                    || (deviceSources & InputDevice.SOURCE_JOYSTICK) != 0;
        }
        return false;
    }

    private boolean isGamepadMotionEvent(@Nullable MotionEvent event) {
        if (event == null || event.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return false;
        }
        int source = event.getSource();
        return (source & InputDevice.SOURCE_JOYSTICK) != 0
                || (source & InputDevice.SOURCE_GAMEPAD) != 0;
    }

    private boolean handleGamepadKeyEvent(@NonNull KeyEvent event) {
        ensureGamepadDirectInputEnabled();
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return true;
        }
        AndroidGamepadGlfwMapper.writeKeyEvent(event.getKeyCode(), action == KeyEvent.ACTION_DOWN);
        return true;
    }

    private boolean handleGamepadMotionEvent(@NonNull MotionEvent event) {
        ensureGamepadDirectInputEnabled();
        AndroidGamepadGlfwMapper.writeMotionEvent(event);
        return true;
    }

    private void ensureGamepadDirectInputEnabled() {
        if (gamepadDirectInputEnableAttempted) {
            return;
        }
        gamepadDirectInputEnableAttempted = true;
        try {
            boolean enabled = CallbackBridge.nativeEnableGamepadDirectInput();
            Log.i(TAG, "Requested gamepad direct input: " + enabled);
        } catch (Throwable error) {
            Log.w(TAG, "Failed to enable gamepad direct input", error);
        }
    }

    private void resetGamepadState() {
        try {
            AndroidGamepadGlfwMapper.resetState();
        } catch (Throwable error) {
            Log.w(TAG, "Failed to reset gamepad state", error);
        }
    }

    private boolean sendMouseButton(int androidButton, boolean down) {
        int glfwButton;
        switch (androidButton) {
            case MotionEvent.BUTTON_PRIMARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
                break;
            case MotionEvent.BUTTON_SECONDARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT;
                break;
            case MotionEvent.BUTTON_TERTIARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE;
                break;
            default:
                return false;
        }
        CallbackBridge.sendMouseButton(glfwButton, down);
        return true;
    }

    private boolean handleTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                activePointerId = event.getPointerId(0);
                moveCursor(event.getX(0), event.getY(0));
                pressLeftIfNeeded();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                int downIndex = event.getActionIndex();
                activePointerId = event.getPointerId(downIndex);
                moveCursor(event.getX(downIndex), event.getY(downIndex));
                pressLeftIfNeeded();
                return true;
            case MotionEvent.ACTION_MOVE:
                int pointerIndex = event.findPointerIndex(activePointerId);
                if (pointerIndex < 0 && event.getPointerCount() > 0) {
                    activePointerId = event.getPointerId(0);
                    pointerIndex = 0;
                }
                if (pointerIndex >= 0) {
                    moveCursor(event.getX(pointerIndex), event.getY(pointerIndex));
                    return true;
                }
                return false;
            case MotionEvent.ACTION_POINTER_UP:
                handlePointerUp(event);
                return true;
            case MotionEvent.ACTION_UP:
                releaseLeftIfNeeded();
                resetTouchState();
                return true;
            case MotionEvent.ACTION_CANCEL:
                releaseLeftIfNeeded();
                resetTouchState();
                return true;
            default:
                return false;
        }
    }

    private void handlePointerUp(MotionEvent event) {
        int actionIndex = event.getActionIndex();
        int pointerId = event.getPointerId(actionIndex);
        int remaining = event.getPointerCount() - 1;

        if (pointerId == activePointerId) {
            activePointerId = MotionEvent.INVALID_POINTER_ID;
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (i == actionIndex) {
                    continue;
                }
                activePointerId = event.getPointerId(i);
                moveCursor(event.getX(i), event.getY(i));
                break;
            }
        }
        if (remaining <= 0) {
            releaseLeftIfNeeded();
            resetTouchState();
        }
    }

    private void moveCursor(float x, float y) {
        float[] mapped = mapToWindowCoords(x, y);
        CallbackBridge.sendCursorPos(mapped[0], mapped[1]);
    }

    private float[] mapToWindowCoords(float viewX, float viewY) {
        int rawViewWidth = renderView == null ? 0 : renderView.getWidth();
        int rawViewHeight = renderView == null ? 0 : renderView.getHeight();
        int viewWidth = Math.max(1, rawViewWidth);
        int viewHeight = Math.max(1, rawViewHeight);
        int windowWidth = Math.max(1, CallbackBridge.windowWidth);
        int windowHeight = Math.max(1, CallbackBridge.windowHeight);

        float mappedX = (viewX * windowWidth) / (float) viewWidth;
        float mappedY = (viewY * windowHeight) / (float) viewHeight;

        if (mappedX < 0f) {
            mappedX = 0f;
        } else if (mappedX > windowWidth - 1f) {
            mappedX = windowWidth - 1f;
        }
        if (mappedY < 0f) {
            mappedY = 0f;
        } else if (mappedY > windowHeight - 1f) {
            mappedY = windowHeight - 1f;
        }

        return new float[]{mappedX, mappedY};
    }

    private void releaseTextureSurfaceIfNeeded() {
        if (textureSurface != null) {
            try {
                textureSurface.release();
            } catch (Throwable ignored) {
            }
            textureSurface = null;
        }
    }

    private void pressLeftIfNeeded() {
        if (leftPressed) {
            return;
        }
        CallbackBridge.sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, true);
        leftPressed = true;
    }

    private void releaseLeftIfNeeded() {
        if (!leftPressed) {
            return;
        }
        CallbackBridge.sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
        leftPressed = false;
    }

    private void resetTouchState() {
        activePointerId = MotionEvent.INVALID_POINTER_ID;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isGamepadKeyEvent(event)) {
            return handleGamepadKeyEvent(event);
        }

        if (event.getAction() == KeyEvent.ACTION_MULTIPLE) {
            return true;
        }

        int glfwKey = AndroidGlfwKeycode.toGlfw(event.getKeyCode());
        if (glfwKey == AndroidGlfwKeycode.GLFW_KEY_UNKNOWN) {
            return super.dispatchKeyEvent(event);
        }

        boolean isDown = event.getAction() == KeyEvent.ACTION_DOWN;
        CallbackBridge.setModifiers(glfwKey, isDown);
        CallbackBridge.sendKeyPress(glfwKey, 0, CallbackBridge.getCurrentMods(), isDown);

        int unicode = event.getUnicodeChar();
        if (isDown && unicode > 0 && !Character.isISOControl(unicode)) {
            CallbackBridge.sendChar((char) unicode, CallbackBridge.getCurrentMods());
        }
        return true;
    }
}

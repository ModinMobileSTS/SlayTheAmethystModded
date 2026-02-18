package io.stamethyst;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.SurfaceTexture;
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
import android.widget.FrameLayout;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import io.stamethyst.input.AndroidGlfwKeycode;

public class StsGameActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String TAG = "StsGameActivity";
    public static final String EXTRA_LAUNCH_MODE = "io.stamethyst.launch_mode";
    public static final String EXTRA_RENDERER_BACKEND = "io.stamethyst.renderer_backend";
    private static final float DEFAULT_RENDER_SCALE = 0.75f;
    private static final float MIN_RENDER_SCALE = 0.50f;
    private static final float MAX_RENDER_SCALE = 1.00f;

    @Nullable
    private SurfaceView surfaceView;
    @Nullable
    private TextureView textureView;
    @Nullable
    private Surface textureSurface;
    private View renderView;
    private boolean useTextureViewSurface = false;
    private volatile boolean vmStarted = false;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private boolean leftPressed = false;
    private int surfaceBufferWidth = 0;
    private int surfaceBufferHeight = 0;
    private long waitingLandscapeSinceMs = -1L;
    private boolean startCheckPosted = false;
    private float renderScale = DEFAULT_RENDER_SCALE;
    private String launchMode = StsLaunchSpec.LAUNCH_MODE_VANILLA;
    private RendererBackend launcherRequestedRenderer = RendererBackend.OPENGL_ES2;
    private boolean jvmLogListenerRegistered = false;
    private final Logger.eventLogListener jvmLogcatListener =
            text -> Log.i(TAG, "[JVM] " + text);

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
        useTextureViewSurface = launcherRequestedRenderer == RendererBackend.KOPPER_ZINK;

        FrameLayout root = findViewById(R.id.gameRoot);
        ViewGroup.LayoutParams renderLayoutParams = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        if (useTextureViewSurface) {
            Log.i(TAG, "Using TextureView surface path for Kopper renderer");
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
                    surface.setDefaultBufferSize(surfaceBufferWidth, surfaceBufferHeight);
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
                    surface.setDefaultBufferSize(surfaceBufferWidth, surfaceBufferHeight);
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
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1);
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1);
        tryStartJvmWhenSurfaceReady();
    }

    @Override
    protected void onPause() {
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0);
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 0);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    private void startJvmOnce() {
        if (vmStarted) {
            return;
        }
        vmStarted = true;

        Thread launchThread = new Thread(() -> {
            try {
                ComponentInstaller.ensureInstalled(this);
                RuntimePackInstaller.ensureInstalled(this);
                StsJarValidator.validate(RuntimePaths.importedStsJar(this));
                if (StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD.equals(launchMode)) {
                    ModJarSupport.validateMtsJar(RuntimePaths.importedMtsJar(this));
                    ModJarSupport.validateBaseModJar(RuntimePaths.importedBaseModJar(this));
                    ModJarSupport.validateStsLibJar(RuntimePaths.importedStsLibJar(this));
                    ModJarSupport.prepareMtsClasspath(this);
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
                Logger.appendToLog("Launching STS with java home: " + javaHome.getAbsolutePath());
                Logger.appendToLog("Launch mode: " + launchMode);
                Logger.appendToLog("Surface backend: " + (useTextureViewSurface ? "TextureView" : "SurfaceView"));
                RendererBackend preferredRenderer = RendererConfig.readPreferredBackend(this);
                RendererConfig.ResolutionResult rendererDecision =
                        RendererConfig.resolveEffectiveBackend(this, preferredRenderer);
                RendererBackend effectiveRenderer = rendererDecision.effective;
                Logger.appendToLog("Renderer from launcher intent: " + launcherRequestedRenderer.rendererId());
                Logger.appendToLog("Renderer decision in game: " + rendererDecision.toLogText());
                if (launcherRequestedRenderer != effectiveRenderer) {
                    Logger.appendToLog(
                            "Renderer changed after re-check: launcher_effective="
                                    + launcherRequestedRenderer.rendererId()
                                    + ", game_effective="
                                    + effectiveRenderer.rendererId()
                    );
                }
                updateWindowSize();
                Logger.appendToLog("Render scale: " + renderScale
                        + ", surface(raw)=" + resolveRawPhysicalWidth() + "x" + resolveRawPhysicalHeight()
                        + ", surface(effective)=" + resolvePhysicalWidth() + "x" + resolvePhysicalHeight()
                        + ", window=" + CallbackBridge.windowWidth + "x" + CallbackBridge.windowHeight);
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

                CallbackBridge.nativeSetUseInputStackQueue(true);
                CallbackBridge.nativeSetInputReady(true);

                List<String> launchArgs = new ArrayList<>();
                launchArgs.add("java");
                launchArgs.addAll(StsLaunchSpec.buildArgs(this, javaHome, launchMode, effectiveRenderer));
                Logger.appendToLog("Launch args: " + launchArgs);

                int exitCode = VMLauncher.launchJVM(launchArgs.toArray(new String[0]));
                Logger.appendToLog("Java Exit code: " + exitCode);
                if (exitCode == 0) {
                    runOnUiThread(this::finish);
                } else {
                    runOnUiThread(() -> reportCrashAndReturn(exitCode, false, null));
                }
            } catch (Throwable t) {
                Log.e(TAG, "Launch failed", t);
                try {
                    Logger.appendToLog("Launch failed: " + t);
                } catch (Throwable ignored) {
                }
                String message = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
                runOnUiThread(() -> reportCrashAndReturn(-1, false, message));
            }
        }, "STS-JVM-Thread");
        launchThread.start();
    }

    private void tryStartJvmWhenSurfaceReady() {
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

    private void reportCrashAndReturn(int code, boolean isSignal, @Nullable String detail) {
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
        int physicalWidth = resolvePhysicalWidth();
        int physicalHeight = resolvePhysicalHeight();
        try {
            DisplayConfigSync.syncToCurrentResolution(this, physicalWidth, physicalHeight);
            Logger.appendToLog("Display config synced to " + Math.max(800, physicalWidth) + "x" + Math.max(450, physicalHeight));
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

    private void applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller == null) {
            return;
        }
        controller.hide(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return handleTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
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

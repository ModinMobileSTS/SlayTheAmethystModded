package io.stamethyst;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
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
    private static final float DEFAULT_RENDER_SCALE = 0.75f;
    private static final float MIN_RENDER_SCALE = 0.50f;
    private static final float MAX_RENDER_SCALE = 1.00f;

    private SurfaceView surfaceView;
    private volatile boolean vmStarted = false;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private boolean leftPressed = false;
    private int surfaceBufferWidth = 0;
    private int surfaceBufferHeight = 0;
    private float renderScale = DEFAULT_RENDER_SCALE;
    private String launchMode = StsLaunchSpec.LAUNCH_MODE_VANILLA;

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

        FrameLayout root = findViewById(R.id.gameRoot);
        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        surfaceView.getHolder().addCallback(this);
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        surfaceView.setOnTouchListener((v, event) -> handleTouchEvent(event));
        surfaceView.requestFocus();
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (holder.getSurfaceFrame() != null) {
            surfaceBufferWidth = holder.getSurfaceFrame().width();
            surfaceBufferHeight = holder.getSurfaceFrame().height();
        }
        JREUtils.setupBridgeWindow(holder.getSurface());
        updateWindowSize();
        startJvmOnce();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        surfaceBufferWidth = width;
        surfaceBufferHeight = height;
        updateWindowSize();
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
                Logger.appendToLog("Launching STS with java home: " + javaHome.getAbsolutePath());
                Logger.appendToLog("Launch mode: " + launchMode);
                Logger.appendToLog("Render scale: " + renderScale
                        + ", surface=" + surfaceBufferWidth + "x" + surfaceBufferHeight
                        + ", window=" + CallbackBridge.windowWidth + "x" + CallbackBridge.windowHeight);

                updateWindowSize();
                JREUtils.relocateLibPath(getApplicationInfo().nativeLibraryDir, javaHome.getAbsolutePath());
                JREUtils.setJavaEnvironment(this, javaHome.getAbsolutePath(),
                        Math.max(1, CallbackBridge.windowWidth),
                        Math.max(1, CallbackBridge.windowHeight));
                JREUtils.initJavaRuntime(javaHome.getAbsolutePath());
                JREUtils.setupExitMethod(getApplicationContext());
                JREUtils.initializeHooks();
                JREUtils.chdir(RuntimePaths.stsRoot(this).getAbsolutePath());

                CallbackBridge.nativeSetUseInputStackQueue(true);
                CallbackBridge.nativeSetInputReady(true);

                List<String> launchArgs = new ArrayList<>();
                launchArgs.add("java");
                launchArgs.addAll(StsLaunchSpec.buildArgs(this, javaHome, launchMode));
                Logger.appendToLog("Launch args: " + launchArgs);

                int exitCode = VMLauncher.launchJVM(launchArgs.toArray(new String[0]));
                Logger.appendToLog("Java Exit code: " + exitCode);
                runOnUiThread(() -> Toast.makeText(this, "Game exited with code " + exitCode, Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                Log.e(TAG, "Launch failed", t);
                try {
                    Logger.appendToLog("Launch failed: " + t);
                } catch (Throwable ignored) {
                }
                runOnUiThread(() -> Toast.makeText(this, "Launch failed: " + t.getClass().getSimpleName() + ": " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "STS-JVM-Thread");
        launchThread.start();
    }

    private void updateWindowSize() {
        int physicalWidth = Math.max(1, surfaceBufferWidth > 0 ? surfaceBufferWidth : surfaceView.getWidth());
        int physicalHeight = Math.max(1, surfaceBufferHeight > 0 ? surfaceBufferHeight : surfaceView.getHeight());
        int windowWidth = Math.max(1, Math.round(physicalWidth * renderScale));
        int windowHeight = Math.max(1, Math.round(physicalHeight * renderScale));

        CallbackBridge.physicalWidth = physicalWidth;
        CallbackBridge.physicalHeight = physicalHeight;
        CallbackBridge.windowWidth = windowWidth;
        CallbackBridge.windowHeight = windowHeight;
        CallbackBridge.sendUpdateWindowSize(windowWidth, windowHeight);
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
        int viewWidth = Math.max(1, surfaceView.getWidth());
        int viewHeight = Math.max(1, surfaceView.getHeight());
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

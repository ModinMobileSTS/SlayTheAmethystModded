package com.badlogic.gdx.controllers.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.ControllerListener;
import com.badlogic.gdx.controllers.ControllerManager;
import com.badlogic.gdx.controllers.PovDirection;
import com.badlogic.gdx.math.Vector3;
import com.megacrit.cardcrawl.core.GameCursor;
import com.badlogic.gdx.utils.Array;

import org.lwjgl.glfw.CallbackBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Android-compatible DesktopControllerManager replacement.
 *
 * It avoids the native OIS backend and reads controller state from the
 * CallbackBridge direct gamepad buffers used by SlayTheAmethyst.
 */
public class DesktopControllerManager implements ControllerManager {
    private static final int BUTTON_COUNT = 15;
    private static final int AXIS_COUNT = 6;
    private static final float AXIS_EPSILON = 0.0001f;
    private static final byte BUTTON_RELEASE = 0;
    private static final byte BUTTON_PRESS = 1;

    // Raw axis order in CallbackBridge gamepad buffers (GLFW standard)
    private static final int RAW_AXIS_LEFT_X = 0;
    private static final int RAW_AXIS_LEFT_Y = 1;
    private static final int RAW_AXIS_RIGHT_X = 2;
    private static final int RAW_AXIS_RIGHT_Y = 3;
    private static final int RAW_AXIS_LEFT_TRIGGER = 4;
    private static final int RAW_AXIS_RIGHT_TRIGGER = 5;

    private static final int BUTTON_DPAD_UP = 11;
    private static final int BUTTON_DPAD_RIGHT = 12;
    private static final int BUTTON_DPAD_DOWN = 13;
    private static final int BUTTON_DPAD_LEFT = 14;

    final Array<Controller> controllers = new Array<>();
    final Array<ControllerListener> listeners = new Array<>();

    private final DirectController controller = new DirectController("Pojav XBOX 360 compatible gamepad");
    private final ByteBuffer buttonBuffer;
    private final FloatBuffer axisBuffer;
    private final byte[] lastButtons = new byte[BUTTON_COUNT];
    private final float[] lastAxes = new float[AXIS_COUNT];
    private PovDirection lastPovDirection = PovDirection.center;
    private boolean connectedNotified = false;
    private boolean hasSeenInput = false;

    public DesktopControllerManager() {
        buttonBuffer = createButtonBuffer();
        axisBuffer = createAxisBuffer();
        Arrays.fill(lastButtons, BUTTON_RELEASE);
        Arrays.fill(lastAxes, 0f);
        controllers.add(controller);
        if (Gdx.app != null) {
            Gdx.app.postRunnable(new PollRunnable());
        }
    }

    @Override
    public Array<Controller> getControllers() {
        return controllers;
    }

    @Override
    public void addListener(ControllerListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(ControllerListener listener) {
        listeners.removeValue(listener, true);
    }

    @Override
    public Array<ControllerListener> getListeners() {
        return listeners;
    }

    @Override
    public void clearListeners() {
        listeners.clear();
    }

    private final class PollRunnable implements Runnable {
        @Override
        public void run() {
            try {
                pollAndDispatch();
            } catch (Throwable ignored) {
            }
            if (Gdx.app != null) {
                Gdx.app.postRunnable(this);
            }
        }
    }

    private void pollAndDispatch() {
        if (!hasSeenInput) {
            if (!hasLiveInput()) {
                return;
            }
            hasSeenInput = true;
        }

        // Diagnostic: keep the in-game cursor visible while controller input is active
        // so we can verify whether controller navigation is drifting the mouse position.
        GameCursor.hidden = false;

        if (!connectedNotified) {
            handleConnectedIfNeeded();
        }
        dispatchButtonEvents();
        dispatchAxisEvents();
        dispatchPovEvent();
    }

    private ByteBuffer createButtonBuffer() {
        try {
            ByteBuffer buffer = CallbackBridge.nativeCreateGamepadButtonBuffer();
            if (buffer != null && buffer.capacity() >= BUTTON_COUNT) {
                return buffer;
            }
        } catch (Throwable ignored) {
        }
        return ByteBuffer.allocateDirect(BUTTON_COUNT).order(ByteOrder.LITTLE_ENDIAN);
    }

    private FloatBuffer createAxisBuffer() {
        try {
            ByteBuffer axisBytes = CallbackBridge.nativeCreateGamepadAxisBuffer();
            if (axisBytes != null) {
                FloatBuffer buffer = axisBytes.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                if (buffer.capacity() >= AXIS_COUNT) {
                    return buffer;
                }
            }
        } catch (Throwable ignored) {
        }
        return ByteBuffer.allocateDirect(AXIS_COUNT * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer();
    }

    private boolean hasLiveInput() {
        for (int button = 0; button < BUTTON_COUNT; button++) {
            if (readButtonState(button) == BUTTON_PRESS) {
                return true;
            }
        }
        for (int axis = 0; axis < AXIS_COUNT; axis++) {
            if (Math.abs(readAxisState(axis)) > AXIS_EPSILON) {
                return true;
            }
        }
        return false;
    }

    private byte readButtonState(int button) {
        try {
            return buttonBuffer.get(button) != BUTTON_RELEASE ? BUTTON_PRESS : BUTTON_RELEASE;
        } catch (Throwable ignored) {
            return BUTTON_RELEASE;
        }
    }

    private float readAxisState(int axis) {
        try {
            float value = axisBuffer.get(axis);
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                return 0f;
            }
            if (Math.abs(value) < AXIS_EPSILON) {
                return 0f;
            }
            if (value > 1f) {
                return 1f;
            }
            if (value < -1f) {
                return -1f;
            }
            return value;
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private void handleConnectedIfNeeded() {
        if (connectedNotified) {
            return;
        }
        connectedNotified = true;
        forEachListenerConnected();
    }

    private void dispatchButtonEvents() {
        for (int button = 0; button < BUTTON_COUNT; button++) {
            byte raw = readButtonState(button);
            if (raw == lastButtons[button]) {
                continue;
            }
            lastButtons[button] = raw;
            boolean down = raw == BUTTON_PRESS;
            controller.buttonStates[button] = down;
            if (down) {
                forEachListenerButtonDown(button);
            } else {
                forEachListenerButtonUp(button);
            }
        }
    }

    private void dispatchAxisEvents() {
        float[] mappedAxes = mapAxesForSts();
        for (int axis = 0; axis < AXIS_COUNT; axis++) {
            float value = mappedAxes[axis];
            if (Math.abs(value - lastAxes[axis]) <= AXIS_EPSILON) {
                continue;
            }
            lastAxes[axis] = value;
            controller.axisStates[axis] = value;
            forEachListenerAxisMoved(axis, value);
        }
    }

    /**
     * STS controller defaults are tuned for legacy OIS ordering:
     * axis0/1 = LS Y/X, axis2/3 = RS Y/X, axis4 = LT(+)/RT(-).
     */
    private float[] mapAxesForSts() {
        float leftX = readAxisState(RAW_AXIS_LEFT_X);
        float leftY = readAxisState(RAW_AXIS_LEFT_Y);
        float rightX = readAxisState(RAW_AXIS_RIGHT_X);
        float rightY = readAxisState(RAW_AXIS_RIGHT_Y);
        float leftTrigger = readAxisState(RAW_AXIS_LEFT_TRIGGER);
        float rightTrigger = readAxisState(RAW_AXIS_RIGHT_TRIGGER);

        float dpadX = 0f;
        if (readButtonState(BUTTON_DPAD_LEFT) == BUTTON_PRESS
                && readButtonState(BUTTON_DPAD_RIGHT) != BUTTON_PRESS) {
            dpadX = -1f;
        } else if (readButtonState(BUTTON_DPAD_RIGHT) == BUTTON_PRESS
                && readButtonState(BUTTON_DPAD_LEFT) != BUTTON_PRESS) {
            dpadX = 1f;
        }

        float dpadY = 0f;
        if (readButtonState(BUTTON_DPAD_UP) == BUTTON_PRESS
                && readButtonState(BUTTON_DPAD_DOWN) != BUTTON_PRESS) {
            dpadY = -1f;
        } else if (readButtonState(BUTTON_DPAD_DOWN) == BUTTON_PRESS
                && readButtonState(BUTTON_DPAD_UP) != BUTTON_PRESS) {
            dpadY = 1f;
        }

        // If the stick is idle, make DPad act like the primary movement stick.
        float lsY = Math.abs(leftY) > AXIS_EPSILON ? leftY : dpadY;
        float lsX = Math.abs(leftX) > AXIS_EPSILON ? leftX : dpadX;

        float[] mapped = new float[AXIS_COUNT];
        mapped[0] = lsY;
        mapped[1] = lsX;
        mapped[2] = rightY;
        mapped[3] = rightX;
        mapped[4] = clampAxis(leftTrigger - rightTrigger);
        mapped[5] = rightTrigger;
        return mapped;
    }

    private float clampAxis(float value) {
        if (value > 1f) {
            return 1f;
        }
        if (value < -1f) {
            return -1f;
        }
        if (Math.abs(value) < AXIS_EPSILON) {
            return 0f;
        }
        return value;
    }

    private void dispatchPovEvent() {
        PovDirection current = resolvePovDirection(lastButtons);
        if (current == lastPovDirection) {
            return;
        }
        lastPovDirection = current;
        controller.povDirection = current;
        forEachListenerPovMoved(current);
    }

    private PovDirection resolvePovDirection(byte[] buttons) {
        boolean up = buttons[BUTTON_DPAD_UP] == BUTTON_PRESS;
        boolean right = buttons[BUTTON_DPAD_RIGHT] == BUTTON_PRESS;
        boolean down = buttons[BUTTON_DPAD_DOWN] == BUTTON_PRESS;
        boolean left = buttons[BUTTON_DPAD_LEFT] == BUTTON_PRESS;

        if (up && right) return PovDirection.northEast;
        if (up && left) return PovDirection.northWest;
        if (down && right) return PovDirection.southEast;
        if (down && left) return PovDirection.southWest;
        if (up) return PovDirection.north;
        if (right) return PovDirection.east;
        if (down) return PovDirection.south;
        if (left) return PovDirection.west;
        return PovDirection.center;
    }

    private void forEachListenerConnected() {
        for (int i = 0; i < listeners.size; i++) {
            listeners.get(i).connected(controller);
        }
        for (int i = 0; i < controller.listeners.size; i++) {
            ControllerListener listener = controller.listeners.get(i);
            if (!listeners.contains(listener, true)) {
                listener.connected(controller);
            }
        }
    }

    private void forEachListenerButtonDown(int button) {
        for (int i = 0; i < listeners.size; i++) {
            listeners.get(i).buttonDown(controller, button);
        }
        for (int i = 0; i < controller.listeners.size; i++) {
            ControllerListener listener = controller.listeners.get(i);
            if (!listeners.contains(listener, true)) {
                listener.buttonDown(controller, button);
            }
        }
    }

    private void forEachListenerButtonUp(int button) {
        for (int i = 0; i < listeners.size; i++) {
            listeners.get(i).buttonUp(controller, button);
        }
        for (int i = 0; i < controller.listeners.size; i++) {
            ControllerListener listener = controller.listeners.get(i);
            if (!listeners.contains(listener, true)) {
                listener.buttonUp(controller, button);
            }
        }
    }

    private void forEachListenerAxisMoved(int axis, float value) {
        for (int i = 0; i < listeners.size; i++) {
            listeners.get(i).axisMoved(controller, axis, value);
        }
        for (int i = 0; i < controller.listeners.size; i++) {
            ControllerListener listener = controller.listeners.get(i);
            if (!listeners.contains(listener, true)) {
                listener.axisMoved(controller, axis, value);
            }
        }
    }

    private void forEachListenerPovMoved(PovDirection direction) {
        for (int i = 0; i < listeners.size; i++) {
            listeners.get(i).povMoved(controller, 0, direction);
        }
        for (int i = 0; i < controller.listeners.size; i++) {
            ControllerListener listener = controller.listeners.get(i);
            if (!listeners.contains(listener, true)) {
                listener.povMoved(controller, 0, direction);
            }
        }
    }

    private static final class DirectController implements Controller {
        private final String name;
        private final Vector3 accelerometer = new Vector3();
        private final Array<ControllerListener> listeners = new Array<>();
        private final boolean[] buttonStates = new boolean[BUTTON_COUNT];
        private final float[] axisStates = new float[AXIS_COUNT];
        private PovDirection povDirection = PovDirection.center;

        private DirectController(String name) {
            this.name = name;
        }

        @Override
        public boolean getButton(int buttonCode) {
            if (buttonCode < 0 || buttonCode >= buttonStates.length) {
                return false;
            }
            return buttonStates[buttonCode];
        }

        @Override
        public float getAxis(int axisCode) {
            if (axisCode < 0 || axisCode >= axisStates.length) {
                return 0f;
            }
            return axisStates[axisCode];
        }

        @Override
        public PovDirection getPov(int povCode) {
            return povDirection;
        }

        @Override
        public boolean getSliderX(int sliderCode) {
            return false;
        }

        @Override
        public boolean getSliderY(int sliderCode) {
            return false;
        }

        @Override
        public Vector3 getAccelerometer(int accelerometerCode) {
            return accelerometer;
        }

        @Override
        public void setAccelerometerSensitivity(float sensitivity) {
            // No-op.
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void addListener(ControllerListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeListener(ControllerListener listener) {
            listeners.removeValue(listener, true);
        }
    }
}

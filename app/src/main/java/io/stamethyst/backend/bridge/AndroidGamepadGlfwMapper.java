package io.stamethyst.backend.bridge;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.lwjgl.glfw.CallbackBridge;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public final class AndroidGamepadGlfwMapper {
    public static final int BUTTON_COUNT = 15;
    public static final int AXIS_COUNT = 6;

    private static final float STICK_DEADZONE = 0.20f;
    private static final float TRIGGER_DEADZONE = 0.05f;
    private static final float DPAD_HAT_THRESHOLD = 0.85f;

    private static final byte GLFW_RELEASE = 0;
    private static final byte GLFW_PRESS = 1;

    private static final int GLFW_GAMEPAD_BUTTON_A = 0;
    private static final int GLFW_GAMEPAD_BUTTON_B = 1;
    private static final int GLFW_GAMEPAD_BUTTON_X = 2;
    private static final int GLFW_GAMEPAD_BUTTON_Y = 3;
    private static final int GLFW_GAMEPAD_BUTTON_LEFT_BUMPER = 4;
    private static final int GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER = 5;
    private static final int GLFW_GAMEPAD_BUTTON_BACK = 6;
    private static final int GLFW_GAMEPAD_BUTTON_START = 7;
    private static final int GLFW_GAMEPAD_BUTTON_GUIDE = 8;
    private static final int GLFW_GAMEPAD_BUTTON_LEFT_THUMB = 9;
    private static final int GLFW_GAMEPAD_BUTTON_RIGHT_THUMB = 10;
    private static final int GLFW_GAMEPAD_BUTTON_DPAD_UP = 11;
    private static final int GLFW_GAMEPAD_BUTTON_DPAD_RIGHT = 12;
    private static final int GLFW_GAMEPAD_BUTTON_DPAD_DOWN = 13;
    private static final int GLFW_GAMEPAD_BUTTON_DPAD_LEFT = 14;

    private static final int GLFW_GAMEPAD_AXIS_LEFT_X = 0;
    private static final int GLFW_GAMEPAD_AXIS_LEFT_Y = 1;
    private static final int GLFW_GAMEPAD_AXIS_RIGHT_X = 2;
    private static final int GLFW_GAMEPAD_AXIS_RIGHT_Y = 3;
    private static final int GLFW_GAMEPAD_AXIS_LEFT_TRIGGER = 4;
    private static final int GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER = 5;

    private AndroidGamepadGlfwMapper() {
    }

    public static void resetState() {
        ByteBuffer buttonBuffer = CallbackBridge.sGamepadButtonBuffer;
        FloatBuffer axisBuffer = CallbackBridge.sGamepadAxisBuffer;
        for (int i = 0; i < BUTTON_COUNT; i++) {
            buttonBuffer.put(i, GLFW_RELEASE);
        }
        for (int i = 0; i < AXIS_COUNT; i++) {
            axisBuffer.put(i, 0f);
        }
    }

    public static boolean writeKeyEvent(int keyCode, boolean isDown) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_1:
                setButton(GLFW_GAMEPAD_BUTTON_A, isDown);
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_2:
                setButton(GLFW_GAMEPAD_BUTTON_B, isDown);
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_3:
                setButton(GLFW_GAMEPAD_BUTTON_X, isDown);
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_4:
                setButton(GLFW_GAMEPAD_BUTTON_Y, isDown);
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_5:
                setButton(GLFW_GAMEPAD_BUTTON_LEFT_BUMPER, isDown);
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_6:
                setButton(GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER, isDown);
                return true;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BUTTON_7:
            case KeyEvent.KEYCODE_BACK:
                setButton(GLFW_GAMEPAD_BUTTON_BACK, isDown);
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_8:
            case KeyEvent.KEYCODE_MENU:
                setButton(GLFW_GAMEPAD_BUTTON_START, isDown);
                return true;
            case KeyEvent.KEYCODE_BUTTON_MODE:
                setButton(GLFW_GAMEPAD_BUTTON_GUIDE, isDown);
                return true;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
            case KeyEvent.KEYCODE_BUTTON_9:
                setButton(GLFW_GAMEPAD_BUTTON_LEFT_THUMB, isDown);
                return true;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
            case KeyEvent.KEYCODE_BUTTON_10:
                setButton(GLFW_GAMEPAD_BUTTON_RIGHT_THUMB, isDown);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                setButton(GLFW_GAMEPAD_BUTTON_DPAD_UP, isDown);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                setButton(GLFW_GAMEPAD_BUTTON_DPAD_RIGHT, isDown);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                setButton(GLFW_GAMEPAD_BUTTON_DPAD_DOWN, isDown);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                setButton(GLFW_GAMEPAD_BUTTON_DPAD_LEFT, isDown);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                releaseDpadButtons();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L2:
                setAxis(GLFW_GAMEPAD_AXIS_LEFT_TRIGGER, isDown ? 1f : 0f);
                return true;
            case KeyEvent.KEYCODE_BUTTON_R2:
                setAxis(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER, isDown ? 1f : 0f);
                return true;
            default:
                return false;
        }
    }

    public static void writeMotionEvent(MotionEvent event) {
        float leftX = applyStickDeadzone(event.getAxisValue(MotionEvent.AXIS_X));
        float leftY = applyStickDeadzone(event.getAxisValue(MotionEvent.AXIS_Y));
        float rightX = applyStickDeadzone(readAxisWithFallback(event, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX));
        float rightY = applyStickDeadzone(readAxisWithFallback(event, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY));
        float leftTrigger = normalizeTrigger(readAxisWithFallback(event, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE));
        float rightTrigger = normalizeTrigger(readAxisWithFallback(event, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS));

        setAxis(GLFW_GAMEPAD_AXIS_LEFT_X, leftX);
        setAxis(GLFW_GAMEPAD_AXIS_LEFT_Y, leftY);
        setAxis(GLFW_GAMEPAD_AXIS_RIGHT_X, rightX);
        setAxis(GLFW_GAMEPAD_AXIS_RIGHT_Y, rightY);
        setAxis(GLFW_GAMEPAD_AXIS_LEFT_TRIGGER, leftTrigger);
        setAxis(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER, rightTrigger);

        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        setButton(GLFW_GAMEPAD_BUTTON_DPAD_LEFT, hatX < -DPAD_HAT_THRESHOLD);
        setButton(GLFW_GAMEPAD_BUTTON_DPAD_RIGHT, hatX > DPAD_HAT_THRESHOLD);
        setButton(GLFW_GAMEPAD_BUTTON_DPAD_UP, hatY < -DPAD_HAT_THRESHOLD);
        setButton(GLFW_GAMEPAD_BUTTON_DPAD_DOWN, hatY > DPAD_HAT_THRESHOLD);
    }

    private static void releaseDpadButtons() {
        setButton(GLFW_GAMEPAD_BUTTON_DPAD_UP, false);
        setButton(GLFW_GAMEPAD_BUTTON_DPAD_RIGHT, false);
        setButton(GLFW_GAMEPAD_BUTTON_DPAD_DOWN, false);
        setButton(GLFW_GAMEPAD_BUTTON_DPAD_LEFT, false);
    }

    private static void setButton(int glfwButton, boolean pressed) {
        CallbackBridge.sGamepadButtonBuffer.put(glfwButton, pressed ? GLFW_PRESS : GLFW_RELEASE);
    }

    private static void setAxis(int glfwAxis, float value) {
        CallbackBridge.sGamepadAxisBuffer.put(glfwAxis, value);
    }

    private static float applyStickDeadzone(float value) {
        return Math.abs(value) < STICK_DEADZONE ? 0f : clamp(value, -1f, 1f);
    }

    private static float normalizeTrigger(float rawValue) {
        float normalized = rawValue;
        if (normalized < 0f) {
            normalized = (normalized + 1f) * 0.5f;
        }
        if (normalized < TRIGGER_DEADZONE) {
            return 0f;
        }
        return clamp(normalized, 0f, 1f);
    }

    private static float readAxisWithFallback(MotionEvent event, int primary, int fallback) {
        InputDevice device = event.getDevice();
        if (hasAxis(device, primary)) {
            return event.getAxisValue(primary);
        }
        if (hasAxis(device, fallback)) {
            return event.getAxisValue(fallback);
        }
        float primaryValue = event.getAxisValue(primary);
        if (primaryValue != 0f) {
            return primaryValue;
        }
        return event.getAxisValue(fallback);
    }

    private static boolean hasAxis(InputDevice device, int axis) {
        return device != null && device.getMotionRange(axis) != null;
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}

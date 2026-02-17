package org.lwjgl.glfw;

import android.content.ClipData;
import android.content.ClipDescription;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.MainActivity;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class CallbackBridge {
    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;

    public static volatile int windowWidth;
    public static volatile int windowHeight;
    public static volatile int physicalWidth;
    public static volatile int physicalHeight;

    public static float mouseX;
    public static float mouseY;

    public static volatile boolean holdingAlt;
    public static volatile boolean holdingCapslock;
    public static volatile boolean holdingCtrl;
    public static volatile boolean holdingNumlock;
    public static volatile boolean holdingShift;

    private static volatile boolean isGrabbing;
    public static boolean sGamepadDirectInput;

    public static final ByteBuffer sGamepadButtonBuffer;
    public static final FloatBuffer sGamepadAxisBuffer;

    public static void putMouseEventWithCoords(int button, boolean isDown, float x, float y) {
        sendCursorPos(x, y);
        sendMouseKeycode(button, getCurrentMods(), isDown);
    }

    public static void sendCursorPos(float x, float y) {
        mouseX = x;
        mouseY = y;
        nativeSendCursorPos(mouseX, mouseY);
    }

    public static void sendKeycode(int keycode, char keychar, int scancode, int modifiers, boolean isDown) {
        nativeSendKey(keycode, scancode, isDown ? 1 : 0, modifiers);
        if (isDown && keychar != 0 && !Character.isISOControl(keychar)) {
            nativeSendCharMods(keychar, modifiers);
            nativeSendChar(keychar);
        }
    }

    public static void sendChar(char keychar, int modifiers) {
        nativeSendCharMods(keychar, modifiers);
        nativeSendChar(keychar);
    }

    public static void sendKeyPress(int keyCode, int scancode, int modifiers, boolean status) {
        sendKeycode(keyCode, '\u0000', scancode, modifiers, status);
    }

    public static void sendMouseButton(int button, boolean status) {
        sendMouseKeycode(button, getCurrentMods(), status);
    }

    public static void sendMouseKeycode(int button, int modifiers, boolean isDown) {
        nativeSendMouseButton(button, isDown ? 1 : 0, modifiers);
    }

    public static void sendScroll(double xOffset, double yOffset) {
        nativeSendScroll(xOffset, yOffset);
    }

    public static void sendUpdateWindowSize(int width, int height) {
        nativeSendScreenSize(width, height);
    }

    public static boolean isGrabbing() {
        return isGrabbing;
    }

    public static int getCurrentMods() {
        int mods = 0;
        if (holdingAlt) mods |= LwjglGlfwKeycode.GLFW_MOD_ALT;
        if (holdingCapslock) mods |= LwjglGlfwKeycode.GLFW_MOD_CAPS_LOCK;
        if (holdingCtrl) mods |= LwjglGlfwKeycode.GLFW_MOD_CONTROL;
        if (holdingNumlock) mods |= LwjglGlfwKeycode.GLFW_MOD_NUM_LOCK;
        if (holdingShift) mods |= LwjglGlfwKeycode.GLFW_MOD_SHIFT;
        return mods;
    }

    public static void setModifiers(int keyCode, boolean isDown) {
        switch (keyCode) {
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT:
            case LwjglGlfwKeycode.GLFW_KEY_RIGHT_SHIFT:
                holdingShift = isDown;
                break;
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL:
            case LwjglGlfwKeycode.GLFW_KEY_RIGHT_CONTROL:
                holdingCtrl = isDown;
                break;
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT:
            case LwjglGlfwKeycode.GLFW_KEY_RIGHT_ALT:
                holdingAlt = isDown;
                break;
            default:
                break;
        }
    }

    public static String accessAndroidClipboard(int type, String copy) {
        switch (type) {
            case CLIPBOARD_COPY:
                if (MainActivity.GLOBAL_CLIPBOARD != null) {
                    MainActivity.GLOBAL_CLIPBOARD.setPrimaryClip(ClipData.newPlainText("copy", copy));
                }
                return null;
            case CLIPBOARD_PASTE:
                if (MainActivity.GLOBAL_CLIPBOARD != null
                        && MainActivity.GLOBAL_CLIPBOARD.hasPrimaryClip()
                        && MainActivity.GLOBAL_CLIPBOARD.getPrimaryClipDescription() != null
                        && MainActivity.GLOBAL_CLIPBOARD.getPrimaryClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                        && MainActivity.GLOBAL_CLIPBOARD.getPrimaryClip() != null
                        && MainActivity.GLOBAL_CLIPBOARD.getPrimaryClip().getItemCount() > 0) {
                    CharSequence text = MainActivity.GLOBAL_CLIPBOARD.getPrimaryClip().getItemAt(0).getText();
                    return text == null ? "" : text.toString();
                }
                return "";
            case CLIPBOARD_OPEN:
                MainActivity.openLink(copy);
                return null;
            default:
                return null;
        }
    }

    private static void onDirectInputEnable() {
        sGamepadDirectInput = true;
    }

    private static void onGrabStateChanged(boolean grabbing) {
        isGrabbing = grabbing;
    }

    public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);

    private static native boolean nativeSendChar(char codepoint);

    private static native boolean nativeSendCharMods(char codepoint, int mods);

    private static native void nativeSendKey(int key, int scancode, int action, int mods);

    private static native void nativeSendCursorPos(float x, float y);

    private static native void nativeSendMouseButton(int button, int action, int mods);

    private static native void nativeSendScroll(double xOffset, double yOffset);

    private static native void nativeSendScreenSize(int width, int height);

    public static native void nativeSetWindowAttrib(int attrib, int value);

    public static native boolean nativeSetInputReady(boolean inputReady);

    public static native void nativeSetGrabbing(boolean grabbing);

    public static native boolean nativeEnableGamepadDirectInput();

    public static native String nativeClipboard(int type, byte[] copySource);

    private static native ByteBuffer nativeCreateGamepadButtonBuffer();

    private static native ByteBuffer nativeCreateGamepadAxisBuffer();

    static {
        System.loadLibrary("pojavexec");
        sGamepadButtonBuffer = nativeCreateGamepadButtonBuffer();
        ByteBuffer axis = nativeCreateGamepadAxisBuffer();
        sGamepadAxisBuffer = axis.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
    }
}

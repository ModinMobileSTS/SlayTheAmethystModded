package io.stamethyst.input;

import android.util.SparseIntArray;
import android.view.KeyEvent;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;

public final class AndroidGlfwKeycode {
    public static final int GLFW_KEY_UNKNOWN = LwjglGlfwKeycode.GLFW_KEY_UNKNOWN;

    private static final SparseIntArray MAP = new SparseIntArray();

    static {
        MAP.put(KeyEvent.KEYCODE_ESCAPE, LwjglGlfwKeycode.GLFW_KEY_ESCAPE);
        MAP.put(KeyEvent.KEYCODE_ENTER, LwjglGlfwKeycode.GLFW_KEY_ENTER);
        MAP.put(KeyEvent.KEYCODE_TAB, LwjglGlfwKeycode.GLFW_KEY_TAB);
        MAP.put(KeyEvent.KEYCODE_DEL, LwjglGlfwKeycode.GLFW_KEY_BACKSPACE);
        MAP.put(KeyEvent.KEYCODE_FORWARD_DEL, LwjglGlfwKeycode.GLFW_KEY_DELETE);
        MAP.put(KeyEvent.KEYCODE_DPAD_UP, LwjglGlfwKeycode.GLFW_KEY_UP);
        MAP.put(KeyEvent.KEYCODE_DPAD_DOWN, LwjglGlfwKeycode.GLFW_KEY_DOWN);
        MAP.put(KeyEvent.KEYCODE_DPAD_LEFT, LwjglGlfwKeycode.GLFW_KEY_LEFT);
        MAP.put(KeyEvent.KEYCODE_DPAD_RIGHT, LwjglGlfwKeycode.GLFW_KEY_RIGHT);
        MAP.put(KeyEvent.KEYCODE_SPACE, LwjglGlfwKeycode.GLFW_KEY_SPACE);
        MAP.put(KeyEvent.KEYCODE_SHIFT_LEFT, LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT);
        MAP.put(KeyEvent.KEYCODE_SHIFT_RIGHT, LwjglGlfwKeycode.GLFW_KEY_RIGHT_SHIFT);
        MAP.put(KeyEvent.KEYCODE_CTRL_LEFT, LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL);
        MAP.put(KeyEvent.KEYCODE_CTRL_RIGHT, LwjglGlfwKeycode.GLFW_KEY_RIGHT_CONTROL);
        MAP.put(KeyEvent.KEYCODE_ALT_LEFT, LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT);
        MAP.put(KeyEvent.KEYCODE_ALT_RIGHT, LwjglGlfwKeycode.GLFW_KEY_RIGHT_ALT);

        MAP.put(KeyEvent.KEYCODE_A, LwjglGlfwKeycode.GLFW_KEY_A);
        MAP.put(KeyEvent.KEYCODE_B, LwjglGlfwKeycode.GLFW_KEY_B);
        MAP.put(KeyEvent.KEYCODE_C, LwjglGlfwKeycode.GLFW_KEY_C);
        MAP.put(KeyEvent.KEYCODE_D, LwjglGlfwKeycode.GLFW_KEY_D);
        MAP.put(KeyEvent.KEYCODE_E, LwjglGlfwKeycode.GLFW_KEY_E);
        MAP.put(KeyEvent.KEYCODE_F, LwjglGlfwKeycode.GLFW_KEY_F);
        MAP.put(KeyEvent.KEYCODE_G, LwjglGlfwKeycode.GLFW_KEY_G);
        MAP.put(KeyEvent.KEYCODE_H, LwjglGlfwKeycode.GLFW_KEY_H);
        MAP.put(KeyEvent.KEYCODE_I, LwjglGlfwKeycode.GLFW_KEY_I);
        MAP.put(KeyEvent.KEYCODE_J, LwjglGlfwKeycode.GLFW_KEY_J);
        MAP.put(KeyEvent.KEYCODE_K, LwjglGlfwKeycode.GLFW_KEY_K);
        MAP.put(KeyEvent.KEYCODE_L, LwjglGlfwKeycode.GLFW_KEY_L);
        MAP.put(KeyEvent.KEYCODE_M, LwjglGlfwKeycode.GLFW_KEY_M);
        MAP.put(KeyEvent.KEYCODE_N, LwjglGlfwKeycode.GLFW_KEY_N);
        MAP.put(KeyEvent.KEYCODE_O, LwjglGlfwKeycode.GLFW_KEY_O);
        MAP.put(KeyEvent.KEYCODE_P, LwjglGlfwKeycode.GLFW_KEY_P);
        MAP.put(KeyEvent.KEYCODE_Q, LwjglGlfwKeycode.GLFW_KEY_Q);
        MAP.put(KeyEvent.KEYCODE_R, LwjglGlfwKeycode.GLFW_KEY_R);
        MAP.put(KeyEvent.KEYCODE_S, LwjglGlfwKeycode.GLFW_KEY_S);
        MAP.put(KeyEvent.KEYCODE_T, LwjglGlfwKeycode.GLFW_KEY_T);
        MAP.put(KeyEvent.KEYCODE_U, LwjglGlfwKeycode.GLFW_KEY_U);
        MAP.put(KeyEvent.KEYCODE_V, LwjglGlfwKeycode.GLFW_KEY_V);
        MAP.put(KeyEvent.KEYCODE_W, LwjglGlfwKeycode.GLFW_KEY_W);
        MAP.put(KeyEvent.KEYCODE_X, LwjglGlfwKeycode.GLFW_KEY_X);
        MAP.put(KeyEvent.KEYCODE_Y, LwjglGlfwKeycode.GLFW_KEY_Y);
        MAP.put(KeyEvent.KEYCODE_Z, LwjglGlfwKeycode.GLFW_KEY_Z);

        MAP.put(KeyEvent.KEYCODE_0, LwjglGlfwKeycode.GLFW_KEY_0);
        MAP.put(KeyEvent.KEYCODE_1, LwjglGlfwKeycode.GLFW_KEY_1);
        MAP.put(KeyEvent.KEYCODE_2, LwjglGlfwKeycode.GLFW_KEY_2);
        MAP.put(KeyEvent.KEYCODE_3, LwjglGlfwKeycode.GLFW_KEY_3);
        MAP.put(KeyEvent.KEYCODE_4, LwjglGlfwKeycode.GLFW_KEY_4);
        MAP.put(KeyEvent.KEYCODE_5, LwjglGlfwKeycode.GLFW_KEY_5);
        MAP.put(KeyEvent.KEYCODE_6, LwjglGlfwKeycode.GLFW_KEY_6);
        MAP.put(KeyEvent.KEYCODE_7, LwjglGlfwKeycode.GLFW_KEY_7);
        MAP.put(KeyEvent.KEYCODE_8, LwjglGlfwKeycode.GLFW_KEY_8);
        MAP.put(KeyEvent.KEYCODE_9, LwjglGlfwKeycode.GLFW_KEY_9);

        MAP.put(KeyEvent.KEYCODE_F1, LwjglGlfwKeycode.GLFW_KEY_F1);
        MAP.put(KeyEvent.KEYCODE_F2, LwjglGlfwKeycode.GLFW_KEY_F2);
        MAP.put(KeyEvent.KEYCODE_F3, LwjglGlfwKeycode.GLFW_KEY_F3);
        MAP.put(KeyEvent.KEYCODE_F4, LwjglGlfwKeycode.GLFW_KEY_F4);
        MAP.put(KeyEvent.KEYCODE_F5, LwjglGlfwKeycode.GLFW_KEY_F5);
        MAP.put(KeyEvent.KEYCODE_F6, LwjglGlfwKeycode.GLFW_KEY_F6);
        MAP.put(KeyEvent.KEYCODE_F7, LwjglGlfwKeycode.GLFW_KEY_F7);
        MAP.put(KeyEvent.KEYCODE_F8, LwjglGlfwKeycode.GLFW_KEY_F8);
        MAP.put(KeyEvent.KEYCODE_F9, LwjglGlfwKeycode.GLFW_KEY_F9);
        MAP.put(KeyEvent.KEYCODE_F10, LwjglGlfwKeycode.GLFW_KEY_F10);
        MAP.put(KeyEvent.KEYCODE_F11, LwjglGlfwKeycode.GLFW_KEY_F11);
        MAP.put(KeyEvent.KEYCODE_F12, LwjglGlfwKeycode.GLFW_KEY_F12);
    }

    private AndroidGlfwKeycode() {
    }

    public static int toGlfw(int androidKeyCode) {
        return MAP.get(androidKeyCode, GLFW_KEY_UNKNOWN);
    }
}

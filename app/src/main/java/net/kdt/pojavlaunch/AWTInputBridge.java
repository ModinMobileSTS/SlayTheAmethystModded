/*
 * Derived from PojavLauncher project sources.
 * Source: https://github.com/AngelAuraMC/Amethyst-Android (branch: v3_openjdk)
 * License: LGPL-3.0
 * Modifications: adapted for the SlayTheAmethystModded Android integration.
 */

package net.kdt.pojavlaunch;

public class AWTInputBridge {
    public static final int EVENT_TYPE_CHAR = 1000;
    public static final int EVENT_TYPE_CURSOR_POS = 1003;
    public static final int EVENT_TYPE_KEY = 1005;
    public static final int EVENT_TYPE_MOUSE_BUTTON = 1006;

    public static void sendKey(char keychar, int keycode) {
        nativeSendData(EVENT_TYPE_KEY, keychar, keycode, 1, 0);
        nativeSendData(EVENT_TYPE_KEY, keychar, keycode, 0, 0);
    }

    public static void sendKey(char keychar, int keycode, int state) {
        nativeSendData(EVENT_TYPE_KEY, keychar, keycode, state, 0);
    }

    public static void sendChar(char keychar) {
        nativeSendData(EVENT_TYPE_CHAR, keychar, 0, 0, 0);
    }

    public static void sendMousePress(int awtButtons, boolean isDown) {
        nativeSendData(EVENT_TYPE_MOUSE_BUTTON, awtButtons, isDown ? 1 : 0, 0, 0);
    }

    public static void sendMousePos(int x, int y) {
        nativeSendData(EVENT_TYPE_CURSOR_POS, x, y, 0, 0);
    }

    static {
        System.loadLibrary("pojavexec_awt");
    }

    public static native void nativeSendData(int type, int i1, int i2, int i3, int i4);

    public static native void nativeClipboardReceived(String data, String mimeTypeSub);

    public static native void nativeMoveWindow(int xoff, int yoff);
}

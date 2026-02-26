package com.codedisaster.steamworks;

/**
 * Minimal SteamUtils stub for Android bring-up.
 * Keeps STS startup path alive when native Steam API is unavailable.
 */
public class SteamUtils {

    public SteamUtils(SteamUtilsCallback callback) {
        // No-op on Android minimal runtime.
    }

    public void dispose() {
        // No-op.
    }

    public boolean isSteamRunningOnSteamDeck() {
        return false;
    }

    public boolean showFloatingGamepadTextInput(
            FloatingGamepadTextInputMode mode,
            int xPosition,
            int yPosition,
            int width,
            int height
    ) {
        return false;
    }

    public enum FloatingGamepadTextInputMode {
        ModeSingleLine,
        ModeMultipleLines,
        ModeEmail,
        ModeNumeric
    }
}

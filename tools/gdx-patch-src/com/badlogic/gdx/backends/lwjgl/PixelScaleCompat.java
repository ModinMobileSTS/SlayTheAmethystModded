package com.badlogic.gdx.backends.lwjgl;

import org.lwjgl.opengl.Display;

final class PixelScaleCompat {
    private PixelScaleCompat() {
    }

    static float factor() {
        try {
            return ((Float) Display.class.getMethod("getPixelScaleFactor").invoke(null)).floatValue();
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }
}
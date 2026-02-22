package com.badlogic.gdx.backends.lwjgl;

import com.badlogic.gdx.utils.GdxNativesLoader;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.io.File;

/**
 * Patched for SlayTheAmethyst:
 * - Skip extracting desktop natives from JAR.
 * - Use POJAV_NATIVEDIR and prepackaged arm64 libs.
 */
public final class LwjglNativesLoader {
    public static boolean load = true;

    private LwjglNativesLoader() {
    }

    public static void load() {
        GdxNativesLoader.load();
        if (GdxNativesLoader.disableNativesLoading) return;
        if (!load) return;

        String nativeDir = System.getenv("POJAV_NATIVEDIR");
        if (nativeDir == null || nativeDir.length() == 0) {
            nativeDir = System.getProperty("java.library.path");
        }
        if (nativeDir == null || nativeDir.length() == 0) {
            throw new GdxRuntimeException("Unable to resolve native directory for LWJGL");
        }

        // LWJGL uses this value when resolving liblwjgl/libopenal.
        System.setProperty("org.lwjgl.librarypath", nativeDir);
        System.out.println("[gdx-patch] LwjglNativesLoader.load nativeDir=" + nativeDir
                + " org.lwjgl.librarypath=" + System.getProperty("org.lwjgl.librarypath"));

        load = false;
    }

    static {
        System.setProperty("org.lwjgl.input.Mouse.allowNegativeMouseCoords", "true");
        try {
            Class.forName("javax.jnlp.ServiceManager")
                    .getDeclaredMethod("lookup", String.class)
                    .invoke(null, "javax.jnlp.PersistenceService");
            load = false;
        } catch (Throwable ignored) {
            load = true;
        }
    }
}

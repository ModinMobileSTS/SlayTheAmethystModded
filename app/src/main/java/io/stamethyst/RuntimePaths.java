package io.stamethyst;

import android.content.Context;

import java.io.File;

public final class RuntimePaths {
    private RuntimePaths() {
    }

    public static File stsRoot(Context context) {
        return new File(context.getFilesDir(), "sts");
    }

    public static File importedStsJar(Context context) {
        return new File(stsRoot(context), "desktop-1.0.jar");
    }

    public static File latestLog(Context context) {
        return new File(stsRoot(context), "latestlog.txt");
    }

    public static File componentRoot(Context context) {
        return context.getFilesDir();
    }

    public static File lwjglDir(Context context) {
        return new File(componentRoot(context), "lwjgl3");
    }

    public static File lwjglJar(Context context) {
        return new File(lwjglDir(context), "lwjgl-glfw-classes.jar");
    }

    public static File lwjgl2InjectorDir(Context context) {
        return new File(componentRoot(context), "lwjgl2_methods_injector");
    }

    public static File lwjgl2InjectorJar(Context context) {
        return new File(lwjgl2InjectorDir(context), "lwjgl2_methods_injector.jar");
    }

    public static File gdxPatchDir(Context context) {
        return new File(componentRoot(context), "gdx_patch");
    }

    public static File gdxPatchJar(Context context) {
        return new File(gdxPatchDir(context), "gdx-patch.jar");
    }

    public static File cacioDir(Context context) {
        return new File(componentRoot(context), "caciocavallo");
    }

    public static File runtimeRoot(Context context) {
        return new File(new File(context.getFilesDir(), "runtimes"), "Internal");
    }

    public static void ensureBaseDirs(Context context) {
        stsRoot(context).mkdirs();
        lwjglDir(context).mkdirs();
        lwjgl2InjectorDir(context).mkdirs();
        gdxPatchDir(context).mkdirs();
        cacioDir(context).mkdirs();
        runtimeRoot(context).mkdirs();
    }
}

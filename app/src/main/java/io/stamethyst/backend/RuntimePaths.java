package io.stamethyst.backend;

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

    public static File importedMtsJar(Context context) {
        return new File(stsRoot(context), "ModTheSpire.jar");
    }

    public static File modsDir(Context context) {
        return new File(stsRoot(context), "mods");
    }

    public static File importedBaseModJar(Context context) {
        return new File(modsDir(context), "BaseMod.jar");
    }

    public static File importedStsLibJar(Context context) {
        return new File(modsDir(context), "StSLib.jar");
    }

    public static File enabledModsConfig(Context context) {
        return new File(stsRoot(context), "enabled_mods.txt");
    }

    public static File preferencesDir(Context context) {
        return new File(stsRoot(context), "preferences");
    }

    public static File betaPreferencesDir(Context context) {
        return new File(stsRoot(context), "betaPreferences");
    }

    public static File mtsGdxApiJar(Context context) {
        return new File(stsRoot(context), "mts-gdx-api.jar");
    }

    public static File mtsStsResourcesJar(Context context) {
        return new File(stsRoot(context), "mts-sts-resources.jar");
    }

    public static File mtsBaseModResourcesJar(Context context) {
        return new File(stsRoot(context), "mts-basemod-resources.jar");
    }

    public static File mtsGdxBridgeJar(Context context) {
        return new File(stsRoot(context), "mts-gdx-bridge.jar");
    }

    public static File mtsLocalJreDir(Context context) {
        return new File(stsRoot(context), "jre");
    }

    public static File mtsLocalJreBinDir(Context context) {
        return new File(mtsLocalJreDir(context), "bin");
    }

    public static File mtsLocalJavaShim(Context context) {
        return new File(mtsLocalJreBinDir(context), "java");
    }

    public static File latestLog(Context context) {
        return new File(stsRoot(context), "latestlog.txt");
    }

    public static File lastCrashReport(Context context) {
        return new File(stsRoot(context), "last_crash_report.txt");
    }

    public static File displayConfigFile(Context context) {
        return new File(stsRoot(context), "info.displayconfig");
    }

    public static File rendererConfigFile(Context context) {
        return new File(stsRoot(context), "renderer_backend.txt");
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

    public static File bootBridgeDir(Context context) {
        return new File(componentRoot(context), "boot_bridge");
    }

    public static File bootBridgeJar(Context context) {
        return new File(bootBridgeDir(context), "boot-bridge.jar");
    }

    public static File bootBridgeEventsFile(Context context) {
        return new File(stsRoot(context), "boot_bridge_events.log");
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
        modsDir(context).mkdirs();
        mtsLocalJreBinDir(context).mkdirs();
        lwjglDir(context).mkdirs();
        lwjgl2InjectorDir(context).mkdirs();
        bootBridgeDir(context).mkdirs();
        gdxPatchDir(context).mkdirs();
        cacioDir(context).mkdirs();
        runtimeRoot(context).mkdirs();
    }
}

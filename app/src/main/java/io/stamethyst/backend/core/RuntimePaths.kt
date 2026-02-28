package io.stamethyst.backend.core

import android.content.Context
import java.io.File

object RuntimePaths {
    @JvmStatic
    fun stsRoot(context: Context): File = File(context.filesDir, "sts")

    @JvmStatic
    fun importedStsJar(context: Context): File = File(stsRoot(context), "desktop-1.0.jar")

    @JvmStatic
    fun importedMtsJar(context: Context): File = File(stsRoot(context), "ModTheSpire.jar")

    @JvmStatic
    fun modsDir(context: Context): File = File(stsRoot(context), "mods")

    @JvmStatic
    fun importedBaseModJar(context: Context): File = File(modsDir(context), "BaseMod.jar")

    @JvmStatic
    fun importedStsLibJar(context: Context): File = File(modsDir(context), "StSLib.jar")

    @JvmStatic
    fun enabledModsConfig(context: Context): File = File(stsRoot(context), "enabled_mods.txt")

    @JvmStatic
    fun preferencesDir(context: Context): File = File(stsRoot(context), "preferences")

    @JvmStatic
    fun betaPreferencesDir(context: Context): File = File(stsRoot(context), "betaPreferences")

    @JvmStatic
    fun mtsGdxApiJar(context: Context): File = File(stsRoot(context), "mts-gdx-api.jar")

    @JvmStatic
    fun mtsStsResourcesJar(context: Context): File = File(stsRoot(context), "mts-sts-resources.jar")

    @JvmStatic
    fun mtsBaseModResourcesJar(context: Context): File = File(stsRoot(context), "mts-basemod-resources.jar")

    @JvmStatic
    fun mtsGdxBridgeJar(context: Context): File = File(stsRoot(context), "mts-gdx-bridge.jar")

    @JvmStatic
    fun mtsLocalJreDir(context: Context): File = File(stsRoot(context), "jre")

    @JvmStatic
    fun mtsLocalJreBinDir(context: Context): File = File(mtsLocalJreDir(context), "bin")

    @JvmStatic
    fun mtsLocalJavaShim(context: Context): File = File(mtsLocalJreBinDir(context), "java")

    @JvmStatic
    fun latestLog(context: Context): File = File(stsRoot(context), "latestlog.txt")

    @JvmStatic
    fun lastCrashReport(context: Context): File = File(stsRoot(context), "last_crash_report.txt")

    @JvmStatic
    fun displayConfigFile(context: Context): File = File(stsRoot(context), "info.displayconfig")

    @JvmStatic
    fun rendererConfigFile(context: Context): File = File(stsRoot(context), "renderer_backend.txt")

    @JvmStatic
    fun componentRoot(context: Context): File = context.filesDir

    @JvmStatic
    fun lwjglDir(context: Context): File = File(componentRoot(context), "lwjgl3")

    @JvmStatic
    fun lwjglJar(context: Context): File = File(lwjglDir(context), "lwjgl-glfw-classes.jar")

    @JvmStatic
    fun lwjgl2InjectorDir(context: Context): File = File(componentRoot(context), "lwjgl2_methods_injector")

    @JvmStatic
    fun lwjgl2InjectorJar(context: Context): File =
        File(lwjgl2InjectorDir(context), "lwjgl2_methods_injector.jar")

    @JvmStatic
    fun bootBridgeDir(context: Context): File = File(componentRoot(context), "boot_bridge")

    @JvmStatic
    fun bootBridgeJar(context: Context): File = File(bootBridgeDir(context), "boot-bridge.jar")

    @JvmStatic
    fun bootBridgeEventsFile(context: Context): File = File(stsRoot(context), "boot_bridge_events.log")

    @JvmStatic
    fun gdxPatchDir(context: Context): File = File(componentRoot(context), "gdx_patch")

    @JvmStatic
    fun gdxPatchJar(context: Context): File = File(gdxPatchDir(context), "gdx-patch.jar")

    @JvmStatic
    fun gdxPatchNativesDir(context: Context): File = File(gdxPatchDir(context), "natives")

    @JvmStatic
    fun cacioDir(context: Context): File = File(componentRoot(context), "caciocavallo")

    @JvmStatic
    fun runtimeRoot(context: Context): File = File(File(context.filesDir, "runtimes"), "Internal")

    @JvmStatic
    fun ensureBaseDirs(context: Context) {
        stsRoot(context).mkdirs()
        modsDir(context).mkdirs()
        mtsLocalJreBinDir(context).mkdirs()
        lwjglDir(context).mkdirs()
        lwjgl2InjectorDir(context).mkdirs()
        bootBridgeDir(context).mkdirs()
        gdxPatchDir(context).mkdirs()
        gdxPatchNativesDir(context).mkdirs()
        cacioDir(context).mkdirs()
        runtimeRoot(context).mkdirs()
    }
}

package io.stamethyst.config

import android.content.Context
import java.io.File

object RuntimePaths {
    private const val STS_DIR_NAME = "sts"
    private const val LATEST_LOG_FILE_NAME = "latest.log"
    private const val BOOT_BRIDGE_EVENTS_FILE_NAME = "boot_bridge_events.log"
    private const val JVM_LOG_DIR_NAME = "jvm_logs"

    @JvmStatic
    fun appExternalFilesRoot(context: Context): File? = context.getExternalFilesDir(null)

    @JvmStatic
    fun usesExternalStsStorage(context: Context): Boolean = appExternalFilesRoot(context) != null

    @JvmStatic
    fun legacyInternalStsRoot(context: Context): File = File(context.filesDir, STS_DIR_NAME)

    @JvmStatic
    fun storageRoot(context: Context): File = appExternalFilesRoot(context) ?: context.filesDir

    @JvmStatic
    fun stsRoot(context: Context): File = File(storageRoot(context), STS_DIR_NAME)

    @JvmStatic
    fun stsHome(context: Context): File = File(stsRoot(context), "home")

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
    fun lastExitMarker(context: Context): File = File(stsRoot(context), ".last_exit_marker")

    @JvmStatic
    fun latestLog(context: Context): File = File(stsRoot(context), LATEST_LOG_FILE_NAME)

    @JvmStatic
    fun bootBridgeEventsLog(context: Context): File = File(stsRoot(context), BOOT_BRIDGE_EVENTS_FILE_NAME)

    @JvmStatic
    fun jvmLogsDir(context: Context): File = File(stsRoot(context), JVM_LOG_DIR_NAME)

    @JvmStatic
    fun displayConfigFile(context: Context): File = File(stsRoot(context), "info.displayconfig")

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
    fun normalizeLegacyInternalStsPath(context: Context, rawPath: String?): String? {
        val raw = rawPath?.trim() ?: return null
        if (raw.isEmpty()) {
            return null
        }

        val absolutePath = File(raw).absolutePath
        val currentRootPath = stsRoot(context).absolutePath
        legacyInternalStsRootCandidates(context).forEach { legacyRootPath ->
            if (legacyRootPath == currentRootPath) {
                return absolutePath
            }
            when {
                absolutePath == legacyRootPath -> return currentRootPath
                absolutePath.startsWith("$legacyRootPath${File.separator}") ->
                    return currentRootPath + absolutePath.substring(legacyRootPath.length)
            }
        }
        return absolutePath
    }

    @JvmStatic
    fun legacyInternalPathForCurrent(context: Context, currentPath: String?): String? {
        val raw = currentPath?.trim() ?: return null
        if (raw.isEmpty()) {
            return null
        }

        val absolutePath = File(raw).absolutePath
        val legacyRootPath = legacyInternalStsRoot(context).absolutePath
        val currentRootPath = stsRoot(context).absolutePath
        if (legacyRootPath == currentRootPath) {
            return null
        }

        return when {
            absolutePath == currentRootPath -> legacyRootPath
            absolutePath.startsWith("$currentRootPath${File.separator}") ->
                legacyRootPath + absolutePath.substring(currentRootPath.length)
            else -> null
        }
    }

    private fun legacyInternalStsRootCandidates(context: Context): List<String> {
        val packageName = context.packageName
        return linkedSetOf(
            legacyInternalStsRoot(context).absolutePath,
            "/data/user/0/$packageName/files/$STS_DIR_NAME",
            "/data/data/$packageName/files/$STS_DIR_NAME"
        ).toList()
    }

    @JvmStatic
    fun ensureBaseDirs(context: Context) {
        stsRoot(context).mkdirs()
        stsHome(context).mkdirs()
        modsDir(context).mkdirs()
        jvmLogsDir(context).mkdirs()
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

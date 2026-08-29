package io.stamethyst.config

import android.content.Context
import java.io.File

object RuntimePaths {
    const val MAX_LAUNCHER_CRASH_REPORT_SLOTS = 5
    private const val ANDROID_DATA_SEGMENT = "data"
    private const val ANDROID_FILES_SEGMENT = "files"
    private const val ANDROID_PATH_SEPARATOR = "/"
    private const val ANDROID_USER_SEGMENT = "user"
    private const val ANDROID_USER_ZERO_SEGMENT = "0"
    private const val STS_DIR_NAME = "sts"
    private const val LATEST_LOG_FILE_NAME = "latest.log"
    private const val BOOT_BRIDGE_EVENTS_FILE_NAME = "boot_bridge_events.log"
    private const val MTS_MOD_FILE_LIST_FILE_NAME = ".mts_mod_file_list"
    private const val JVM_LOG_DIR_NAME = "jvm_logs"
    private const val WORKSHOP_AUTO_IMPORT_PATCH_LOG_DIR_NAME = "workshop_auto_import_patch_logs"
    private const val WORKSHOP_BROWSE_FAILURE_LOG_DIR_NAME = "workshop_browse_failure_logs"
    private const val MEMORY_DIAGNOSTICS_LOG_FILE_NAME = "memory_diagnostics.log"
    private const val ACHIEVEMENT_SYNC_LOG_FILE_NAME = "achievement_sync.log"
    private const val PERFORMANCE_LAUNCH_AUDIT_LOG_FILE_NAME = "performance_launch_audit.log"
    private const val JVM_GC_LOG_FILE_NAME = "jvm_gc.log"
    private const val JVM_HEAP_SNAPSHOT_FILE_NAME = "jvm_heap_snapshot.txt"
    private const val LAUNCHER_PERF_SNAPSHOT_FILE_NAME = "launcher_perf_snapshot.txt"
    private const val FRAME_PROBE_INCIDENTS_FILE_NAME = "frame-probe-incidents.jsonl"
    private const val FRAME_PROBE_PREVIOUS_INCIDENTS_FILE_NAME = "frame-probe-incidents.prev.jsonl"
    private const val JVM_SIGNAL_DUMP_FILE_NAME = "last_signal_dump.txt"
    private const val EXPECTED_GAME_EXIT_MARKER_FILE_NAME = ".expected_game_exit_marker"
    private const val EXTERNAL_RESOURCES_DIR_NAME = "external_resources"
    private const val EXTERNAL_RESOURCES_CURRENT_DIR_NAME = "current"
    private const val EXTERNAL_RESOURCES_ASSETS_DIR_NAME = "assets"
    private const val EXTERNAL_RESOURCES_LIB_DIR_NAME = "lib"
    private const val EXTERNAL_RESOURCES_ABI_DIR_NAME = "arm64-v8a"
    private const val EXTERNAL_RESOURCES_MARKER_FILE_NAME = ".resource-pack-installed"
    private const val IN_GAME_KEYBOARD_REQUEST_FILE_NAME = ".in_game_keyboard_request"
    private const val IN_GAME_LAN_GAME_STATE_REQUEST_FILE_NAME = ".in_game_lan_game_state_request"
    private const val IN_GAME_FILE_PICKER_REQUEST_FILE_NAME = ".in_game_file_picker_request"
    private const val IN_GAME_FILE_PICKER_RESULT_FILE_NAME = ".in_game_file_picker_result"
    private const val IN_GAME_FILE_PICKER_SELECTION_FILE_NAME = ".in_game_file_picker_selection"
    private const val TOUCHSCREEN_CARD_HOLD_STATE_FILE_NAME = ".touchscreen_card_hold_state"
    private const val GAME_PRESENCE_STATE_FILE_NAME = ".game_presence_state"
    private const val RUNTIME_RESCUE_TOAST_REQUEST_FILE_NAME = ".runtime_rescue_toast_request"
    private const val ACHIEVEMENT_REQUEST_FILE_NAME = ".achievement_sync_request"
    private const val ACHIEVEMENT_LOCK_COMMAND_FILE_NAME = ".achievement_lock_command"
    private const val HARNESS_EXIT_REQUEST_FILE_NAME = ".harness_exit_request"
    private const val RICH_PRESENCE_FILE_NAME = ".rich_presence_state"
    private const val JVM_HISTOGRAM_DIR_NAME = "jvm_histograms"
    private const val LOGCAT_DIR_NAME = "logcat"
    private const val WINDOW_DIAGNOSTICS_DIR_NAME = "window"
    private const val WINDOW_DIAGNOSTICS_LOG_FILE_NAME = "window_diagnostics.log"
    private const val LEGACY_LOGCAT_CAPTURE_FILE_NAME = "logcat_capture.log"
    private const val LOGCAT_APP_CAPTURE_FILE_NAME = "logcat_app_capture.log"
    private const val LOGCAT_SYSTEM_CAPTURE_FILE_NAME = "logcat_system_capture.log"
    private const val LAUNCHER_LOGCAT_APP_CAPTURE_FILE_NAME = "launcher_logcat_app_capture.log"
    private const val LAUNCHER_LOGCAT_SYSTEM_CAPTURE_FILE_NAME = "launcher_logcat_system_capture.log"
    private const val LAUNCHER_CRASH_REPORT_DIR_NAME = "launcher_crash_reports"
    private const val LAUNCHER_CRASH_REPORT_PREFIX = "sts-launcher-crash-"
    private const val BOOT_OVERLAY_IMAGE_DIR_NAME = "boot_overlay_images"
    private const val MTS_CLASSPATH_CACHE_MARKER_FILE_NAME = ".mts_classpath_cache"
    private const val MTS_PATCH_CACHE_DIR_NAME = "mts_patch_cache"
    private const val MTS_PATCH_CACHE_MARKER_FILE_NAME = ".mts_patch_cache"
    private const val MTS_PATCH_CACHE_JAR_FILE_NAME = "desktop-1.0-modded.jar"
    private const val MTS_PATCH_CACHE_PACKAGE_DIR_NAME = "package"
    private const val MTS_PATCH_CACHE_GDX_PATCH_DIGEST_CACHE_FILE_NAME = "gdx-patch-digest.txt"
    private const val OPTIONAL_MOD_LIBRARY_MIGRATION_MARKER_FILE_NAME = ".optional_mod_library_migrated"
    private const val ANDROID_EXTERNAL_STORAGE_ROOT = "storage"
    private const val ANDROID_EMULATED_SEGMENT = "emulated"
    private const val ANDROID_SDCARD_SEGMENT = "sdcard"
    private val SESSION_LOGCAT_CAPTURE_FILE_NAMES = listOf(
        LOGCAT_APP_CAPTURE_FILE_NAME,
        LOGCAT_SYSTEM_CAPTURE_FILE_NAME,
        LEGACY_LOGCAT_CAPTURE_FILE_NAME
    )
    private val LAUNCHER_LOGCAT_CAPTURE_FILE_NAMES = listOf(
        LAUNCHER_LOGCAT_APP_CAPTURE_FILE_NAME,
        LAUNCHER_LOGCAT_SYSTEM_CAPTURE_FILE_NAME
    )
    private val ALL_LOGCAT_CAPTURE_FILE_NAMES =
        SESSION_LOGCAT_CAPTURE_FILE_NAMES + LAUNCHER_LOGCAT_CAPTURE_FILE_NAMES

    @JvmStatic
    fun appExternalFilesRoot(context: Context): File? = context.getExternalFilesDir(null)

    @JvmStatic
    fun externalAppStsRoot(context: Context): File? = appExternalFilesRoot(context)?.let {
        File(it, STS_DIR_NAME)
    }

    @JvmStatic
    fun usesExternalStsStorage(context: Context): Boolean = externalAppStsRoot(context) != null

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
    fun requiredModsDir(context: Context): File = File(stsRoot(context), "required_mods")

    @JvmStatic
    fun texturePacksDir(context: Context): File = File(stsRoot(context), "texPacks")

    @JvmStatic
    fun texturePackDir(context: Context, publishedFileId: ULong): File =
        File(texturePacksDir(context), publishedFileId.toString())

    @JvmStatic
    fun modTheSpireConfigDir(context: Context): File = File(stsHome(context), ".config/ModTheSpire")

    @JvmStatic
    fun textureReplacerPackOrderFile(context: Context): File =
        File(modTheSpireConfigDir(context), "texturereplacer/pack_order.json")

    @JvmStatic
    fun optionalModsLibraryDir(context: Context): File = File(stsRoot(context), "mods_library")

    @JvmStatic
    fun importedBaseModJar(context: Context): File = File(requiredModsDir(context), "BaseMod.jar")

    @JvmStatic
    fun importedStsLibJar(context: Context): File = File(requiredModsDir(context), "StSLib.jar")

    @JvmStatic
    fun importedAmethystRuntimeCompatJar(context: Context): File =
        File(requiredModsDir(context), "AmethystRuntimeCompat.jar")

    @JvmStatic
    fun importedAmethystFloatingToolsJar(context: Context): File =
        File(requiredModsDir(context), "AmethystFloatingTools.jar")

    @JvmStatic
    fun importedRamSaverJar(context: Context): File = File(requiredModsDir(context), "RamSaver.jar")

    @JvmStatic
    fun importedAmethystFrameProbeJar(context: Context): File =
        File(requiredModsDir(context), "AmethystFrameProbe.jar")

    @JvmStatic
    fun mtsModFileList(context: Context): File = File(stsRoot(context), MTS_MOD_FILE_LIST_FILE_NAME)

    @JvmStatic
    fun enabledModsConfig(context: Context): File = File(stsRoot(context), "enabled_mods.txt")

    @JvmStatic
    fun priorityModsConfig(context: Context): File = File(stsRoot(context), "priority_mod_roots.txt")

    @JvmStatic
    fun importedModPatchMetadataFile(context: Context): File =
        File(stsRoot(context), "imported_mod_patch_metadata.json")

    @JvmStatic
    fun optionalModIndexFile(context: Context): File =
        File(stsRoot(context), "optional_mod_index.json")

    @JvmStatic
    fun optionalModsLibraryMigrationMarker(context: Context): File =
        File(stsRoot(context), OPTIONAL_MOD_LIBRARY_MIGRATION_MARKER_FILE_NAME)

    @JvmStatic
    fun preferencesDir(context: Context): File = File(stsRoot(context), "preferences")

    @JvmStatic
    fun mtsGdxApiJar(context: Context): File = File(stsRoot(context), "mts-gdx-api.jar")

    @JvmStatic
    fun mtsStsResourcesJar(context: Context): File = File(stsRoot(context), "mts-sts-resources.jar")

    @JvmStatic
    fun mtsBaseModResourcesJar(context: Context): File = File(stsRoot(context), "mts-basemod-resources.jar")

    @JvmStatic
    fun mtsGdxBridgeJar(context: Context): File = File(stsRoot(context), "mts-gdx-bridge.jar")

    @JvmStatic
    fun bundledLog4jRuntimeDir(context: Context): File = File(componentRoot(context), "log4j_runtime")

    @JvmStatic
    fun bundledLog4jApiJar(context: Context): File = File(bundledLog4jRuntimeDir(context), "log4j-api.jar")

    @JvmStatic
    fun bundledLog4jCoreJar(context: Context): File = File(bundledLog4jRuntimeDir(context), "log4j-core.jar")

    @JvmStatic
    fun mtsLocalJreDir(context: Context): File = File(stsRoot(context), "jre")

    @JvmStatic
    fun mtsLocalJreBinDir(context: Context): File = File(mtsLocalJreDir(context), "bin")

    @JvmStatic
    fun mtsLocalJavaShim(context: Context): File = File(mtsLocalJreBinDir(context), "java")

    @JvmStatic
    fun lastExitMarker(context: Context): File = File(stsRoot(context), ".last_exit_marker")

    @JvmStatic
    fun expectedGameExitMarker(context: Context): File = File(stsRoot(context), EXPECTED_GAME_EXIT_MARKER_FILE_NAME)

    @JvmStatic
    fun inGameKeyboardRequestFile(context: Context): File = File(stsRoot(context), IN_GAME_KEYBOARD_REQUEST_FILE_NAME)

    @JvmStatic
    fun inGameLanGameStateRequestFile(context: Context): File =
        File(stsRoot(context), IN_GAME_LAN_GAME_STATE_REQUEST_FILE_NAME)

    @JvmStatic
    fun inGameFilePickerRequestFile(context: Context): File =
        File(stsRoot(context), IN_GAME_FILE_PICKER_REQUEST_FILE_NAME)

    @JvmStatic
    fun inGameFilePickerResultFile(context: Context): File =
        File(stsRoot(context), IN_GAME_FILE_PICKER_RESULT_FILE_NAME)

    @JvmStatic
    fun inGameFilePickerSelectionFile(context: Context): File =
        File(stsRoot(context), IN_GAME_FILE_PICKER_SELECTION_FILE_NAME)

    @JvmStatic
    fun touchscreenCardHoldStateFile(context: Context): File =
        File(stsRoot(context), TOUCHSCREEN_CARD_HOLD_STATE_FILE_NAME)


    @JvmStatic
    fun gamePresenceStateFile(context: Context): File =
        File(stsRoot(context), GAME_PRESENCE_STATE_FILE_NAME)

    @JvmStatic
    fun runtimeRescueToastRequestFile(context: Context): File =
        File(stsRoot(context), RUNTIME_RESCUE_TOAST_REQUEST_FILE_NAME)

    @JvmStatic
    fun achievementRequestFile(context: Context): File =
        File(stsRoot(context), ACHIEVEMENT_REQUEST_FILE_NAME)

    @JvmStatic
    fun achievementLockCommandFile(context: Context): File =
        File(stsRoot(context), ACHIEVEMENT_LOCK_COMMAND_FILE_NAME)

    @JvmStatic
    fun harnessExitRequestFile(context: Context): File =
        File(stsRoot(context), HARNESS_EXIT_REQUEST_FILE_NAME)

    @JvmStatic
    fun richPresenceFile(context: Context): File =
        File(stsRoot(context), RICH_PRESENCE_FILE_NAME)

    @JvmStatic
    fun latestLog(context: Context): File = File(stsRoot(context), LATEST_LOG_FILE_NAME)

    @JvmStatic
    fun bootBridgeEventsLog(context: Context): File = File(stsRoot(context), BOOT_BRIDGE_EVENTS_FILE_NAME)

    @JvmStatic
    fun jvmLogsDir(context: Context): File = File(stsRoot(context), JVM_LOG_DIR_NAME)

    @JvmStatic
    fun workshopAutoImportPatchLogsDir(context: Context): File =
        File(stsRoot(context), WORKSHOP_AUTO_IMPORT_PATCH_LOG_DIR_NAME)

    @JvmStatic
    fun memoryDiagnosticsLog(context: Context): File =
        File(jvmLogsDir(context), MEMORY_DIAGNOSTICS_LOG_FILE_NAME)

    @JvmStatic
    fun achievementSyncLog(context: Context): File =
        File(jvmLogsDir(context), ACHIEVEMENT_SYNC_LOG_FILE_NAME)

    @JvmStatic
    fun performanceLaunchAuditLog(context: Context): File =
        File(jvmLogsDir(context), PERFORMANCE_LAUNCH_AUDIT_LOG_FILE_NAME)

    @JvmStatic
    fun workshopBrowseFailureLogsDir(context: Context): File =
        File(stsRoot(context), WORKSHOP_BROWSE_FAILURE_LOG_DIR_NAME)

    fun startupTraceLog(context: Context): File = File(jvmLogsDir(context), "startup_trace.log")

    @JvmStatic
    fun jvmGcLog(context: Context): File = File(stsRoot(context), JVM_GC_LOG_FILE_NAME)

    @JvmStatic
    fun jvmHeapSnapshot(context: Context): File = File(stsRoot(context), JVM_HEAP_SNAPSHOT_FILE_NAME)

    @JvmStatic
    fun launcherPerfSnapshot(context: Context): File =
        File(stsRoot(context), LAUNCHER_PERF_SNAPSHOT_FILE_NAME)

    @JvmStatic
    fun frameProbeIncidents(context: Context): File =
        File(stsRoot(context), FRAME_PROBE_INCIDENTS_FILE_NAME)

    @JvmStatic
    fun frameProbePreviousIncidents(context: Context): File =
        File(stsRoot(context), FRAME_PROBE_PREVIOUS_INCIDENTS_FILE_NAME)

    @JvmStatic
    fun jvmSignalDump(context: Context): File = File(stsRoot(context), JVM_SIGNAL_DUMP_FILE_NAME)

    @JvmStatic
    fun jvmHistogramsDir(context: Context): File = File(stsRoot(context), JVM_HISTOGRAM_DIR_NAME)

    @JvmStatic
    fun logcatDir(context: Context): File = File(stsRoot(context), LOGCAT_DIR_NAME)

    @JvmStatic
    fun windowDiagnosticsDir(context: Context): File = File(stsRoot(context), WINDOW_DIAGNOSTICS_DIR_NAME)

    @JvmStatic
    fun windowDiagnosticsLog(context: Context): File =
        File(windowDiagnosticsDir(context), WINDOW_DIAGNOSTICS_LOG_FILE_NAME)

    @JvmStatic
    fun listWindowDiagnosticsFiles(context: Context): List<File> {
        val directory = windowDiagnosticsDir(context)
        if (!directory.isDirectory) {
            return listOf(windowDiagnosticsLog(context))
        }
        return directory.listFiles()
            ?.asSequence()
            ?.filter { file -> file.isFile && isWindowDiagnosticsFileName(file.name) }
            ?.sortedWith { left, right ->
                compareWindowDiagnosticsFileNames(left.name, right.name)
            }
            ?.toList()
            .orEmpty()
            .ifEmpty { listOf(windowDiagnosticsLog(context)) }
    }

    @JvmStatic
    fun logcatCaptureLog(context: Context): File = logcatAppCaptureLog(context)

    @JvmStatic
    fun logcatAppCaptureLog(context: Context): File = File(logcatDir(context), LOGCAT_APP_CAPTURE_FILE_NAME)

    @JvmStatic
    fun logcatSystemCaptureLog(context: Context): File = File(logcatDir(context), LOGCAT_SYSTEM_CAPTURE_FILE_NAME)

    @JvmStatic
    fun listLogcatCaptureFiles(context: Context): List<File> {
        return listLogcatCaptureFiles(
            context = context,
            recognizedBaseNames = SESSION_LOGCAT_CAPTURE_FILE_NAMES,
            fallbackFiles = listOf(
                logcatAppCaptureLog(context),
                logcatSystemCaptureLog(context),
                legacyLogcatCaptureLog(context)
            )
        )
    }

    @JvmStatic
    fun launcherLogcatAppCaptureLog(context: Context): File =
        File(logcatDir(context), LAUNCHER_LOGCAT_APP_CAPTURE_FILE_NAME)

    @JvmStatic
    fun launcherLogcatSystemCaptureLog(context: Context): File =
        File(logcatDir(context), LAUNCHER_LOGCAT_SYSTEM_CAPTURE_FILE_NAME)

    @JvmStatic
    fun listLauncherLogcatCaptureFiles(context: Context): List<File> {
        return listLogcatCaptureFiles(
            context = context,
            recognizedBaseNames = LAUNCHER_LOGCAT_CAPTURE_FILE_NAMES,
            fallbackFiles = listOf(
                launcherLogcatAppCaptureLog(context),
                launcherLogcatSystemCaptureLog(context)
            )
        )
    }

    @JvmStatic
    fun launcherCrashReportsDir(context: Context): File =
        File(stsRoot(context), LAUNCHER_CRASH_REPORT_DIR_NAME)

    @JvmStatic
    fun bootOverlayImagesDir(context: Context): File =
        File(componentRoot(context), BOOT_OVERLAY_IMAGE_DIR_NAME)

    @JvmStatic
    fun listLauncherCrashReportFiles(context: Context): List<File> {
        val directory = launcherCrashReportsDir(context)
        if (!directory.isDirectory) {
            return emptyList()
        }
        return directory.listFiles()
            ?.asSequence()
            ?.filter { file -> file.isFile && isLauncherCrashReportFileName(file.name) }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            ?.take(MAX_LAUNCHER_CRASH_REPORT_SLOTS)
            ?.toList()
            .orEmpty()
    }

    @JvmStatic
    fun listAllLogcatCaptureFiles(context: Context): List<File> {
        val files = LinkedHashMap<String, File>()
        listLogcatCaptureFiles(context).forEach { files.putIfAbsent(it.name, it) }
        listLauncherLogcatCaptureFiles(context).forEach { files.putIfAbsent(it.name, it) }
        return files.values.toList()
    }

    @JvmStatic
    fun listMemoryDiagnosticsFiles(context: Context): List<File> {
        val directory = jvmLogsDir(context)
        if (!directory.isDirectory) {
            return listOf(memoryDiagnosticsLog(context))
        }
        return directory.listFiles()
            ?.asSequence()
            ?.filter { file -> file.isFile && isMemoryDiagnosticsFileName(file.name) }
            ?.sortedWith { left, right ->
                compareMemoryDiagnosticsFileNames(left.name, right.name)
            }
            ?.toList()
            .orEmpty()
            .ifEmpty { listOf(memoryDiagnosticsLog(context)) }
    }

    @JvmStatic
    fun listAchievementSyncLogFiles(context: Context): List<File> {
        val directory = jvmLogsDir(context)
        if (!directory.isDirectory) {
            return listOf(achievementSyncLog(context))
        }
        return directory.listFiles()
            ?.asSequence()
            ?.filter { file -> file.isFile && isAchievementSyncLogFileName(file.name) }
            ?.sortedWith { left, right ->
                compareAchievementSyncLogFileNames(left.name, right.name)
            }
            ?.toList()
            .orEmpty()
            .ifEmpty { listOf(achievementSyncLog(context)) }
    }

    @JvmStatic
    fun mtsClasspathCacheMarker(context: Context): File =
        File(stsRoot(context), MTS_CLASSPATH_CACHE_MARKER_FILE_NAME)

    @JvmStatic
    fun mtsPatchCacheDir(context: Context): File =
        File(componentRoot(context), MTS_PATCH_CACHE_DIR_NAME)

    @JvmStatic
    fun mtsPatchCacheMarker(context: Context): File =
        File(mtsPatchCacheDir(context), MTS_PATCH_CACHE_MARKER_FILE_NAME)

    @JvmStatic
    fun mtsPatchCacheJar(context: Context): File =
        File(mtsPatchCacheDir(context), MTS_PATCH_CACHE_JAR_FILE_NAME)

    @JvmStatic
    fun mtsPatchCachePackageDir(context: Context): File =
        File(mtsPatchCacheDir(context), MTS_PATCH_CACHE_PACKAGE_DIR_NAME)

    @JvmStatic
    fun mtsPatchCacheGdxPatchDigestCache(context: Context): File =
        File(mtsPatchCacheDir(context), MTS_PATCH_CACHE_GDX_PATCH_DIGEST_CACHE_FILE_NAME)

    @JvmStatic
    fun legacyExternalMtsPatchCacheFiles(context: Context): List<File> =
        legacyExternalStsRootCandidates(context.packageName, appExternalFilesRoot(context))
            .asSequence()
            .map(::File)
            .flatMap { legacyMtsPatchCacheArtifacts(it).asSequence() }
            .distinctBy { it.absolutePath }
            .toList()

    @JvmStatic
    fun legacyInternalMtsPatchCacheFiles(context: Context): List<File> =
        legacyInternalStsRootCandidates(context)
            .asSequence()
            .map(::File)
            .flatMap { legacyMtsPatchCacheArtifacts(it).asSequence() }
            .distinctBy { it.absolutePath }
            .toList()

    @JvmStatic
    fun knownMtsPatchCacheArtifacts(context: Context): List<File> =
        sequenceOf(mtsPatchCacheDir(context))
            .plus(legacyInternalMtsPatchCacheFiles(context).asSequence())
            .plus(legacyExternalMtsPatchCacheFiles(context).asSequence())
            .distinctBy { it.absolutePath }
            .toList()

    private fun legacyMtsPatchCacheArtifacts(stsRoot: File): List<File> = listOf(
        File(stsRoot, MTS_PATCH_CACHE_MARKER_FILE_NAME),
        File(stsRoot, MTS_PATCH_CACHE_JAR_FILE_NAME),
        File(stsRoot, MTS_PATCH_CACHE_PACKAGE_DIR_NAME),
        File(stsRoot, MTS_PATCH_CACHE_DIR_NAME),
        File(stsRoot, "mts_patch_cache_debug.log")
    )

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
    fun agentConnectorDir(context: Context): File = File(componentRoot(context), "game_probe")

    @JvmStatic
    fun agentConnectorJar(context: Context): File =
        File(agentConnectorDir(context), "game-probe.jar")

    @JvmStatic
    fun arthasResourceRoot(context: Context): File = File(context.filesDir, "arthas_resources")

    @JvmStatic
    fun arthasResourceCurrentDir(context: Context): File = File(arthasResourceRoot(context), "current")

    @JvmStatic
    fun arthasResourcePreviousDir(context: Context): File = File(arthasResourceRoot(context), "previous")

    @JvmStatic
    fun arthasResourceStagingDir(context: Context): File = File(arthasResourceRoot(context), "staging")

    @JvmStatic
    fun offlineArthasCoreJar(context: Context): File =
        File(arthasResourceCurrentDir(context), "arthas-core.jar")

    @JvmStatic
    fun offlineArthasSpyJar(context: Context): File =
        File(arthasResourceCurrentDir(context), "arthas-spy.jar")

    @JvmStatic
    fun offlineArthasBridgeJar(context: Context): File =
        File(arthasResourceCurrentDir(context), "arthas-bridge.jar")

    @JvmStatic
    fun offlineArthasOutputDir(context: Context): File =
        File(stsRoot(context), "performance/arthas")

    @JvmStatic
    fun arthasBridgeLog(context: Context): File = File(context.filesDir, "arthas-bridge.log")

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
    fun nativeMarketDir(context: Context): File = File(componentRoot(context), "native_market")

    @JvmStatic
    fun nativeMarketPackagesDir(context: Context): File = File(nativeMarketDir(context), "packages")

    @JvmStatic
    fun nativeMarketPackageDir(context: Context, packageId: String): File =
        File(nativeMarketPackagesDir(context), packageId)

    @JvmStatic
    fun nativeMarketActiveDir(context: Context): File = File(nativeMarketDir(context), "active")

    @JvmStatic
    fun modSuggestionDir(context: Context): File = File(componentRoot(context), "mod_suggestions")

    @JvmStatic
    fun modSuggestionCacheFile(context: Context, localeKey: String): File =
        File(modSuggestionDir(context), "suggestion-$localeKey.json")

    @JvmStatic
    fun cacioDir(context: Context): File = File(componentRoot(context), "caciocavallo")

    @JvmStatic
    fun runtimeRoot(context: Context): File = File(File(context.filesDir, "runtimes"), "Internal")

    @JvmStatic
    fun externalResourcesRoot(context: Context): File =
        File(componentRoot(context), EXTERNAL_RESOURCES_DIR_NAME)

    @JvmStatic
    fun externalResourcesCurrentDir(context: Context): File =
        File(externalResourcesRoot(context), EXTERNAL_RESOURCES_CURRENT_DIR_NAME)

    @JvmStatic
    fun externalResourcesAssetsDir(context: Context): File =
        File(externalResourcesCurrentDir(context), EXTERNAL_RESOURCES_ASSETS_DIR_NAME)

    @JvmStatic
    fun externalNativeLibDir(context: Context): File =
        File(
            File(externalResourcesCurrentDir(context), EXTERNAL_RESOURCES_LIB_DIR_NAME),
            EXTERNAL_RESOURCES_ABI_DIR_NAME
        )

    @JvmStatic
    fun externalResourcesMarkerFile(context: Context): File =
        File(externalResourcesCurrentDir(context), EXTERNAL_RESOURCES_MARKER_FILE_NAME)

    internal fun legacyInternalStsRootCandidates(packageName: String): List<String> =
        legacyInternalStsRootCandidates(packageName, null)

    internal fun legacyExternalStsRootCandidates(packageName: String): List<String> =
        legacyExternalStsRootCandidates(packageName, null)

    @JvmStatic
    fun normalizeLegacyStsPath(context: Context, rawPath: String?): String? {
        val raw = rawPath?.trim() ?: return null
        if (raw.isEmpty()) {
            return null
        }

        val absolutePath = File(raw).absolutePath
        val currentRootPath = stsRoot(context).absolutePath
        if (absolutePath == currentRootPath ||
            absolutePath.startsWith("$currentRootPath${File.separator}")
        ) {
            return absolutePath
        }

        knownLegacyStsRootCandidates(context).forEach { legacyRootPath ->
            if (legacyRootPath == currentRootPath) {
                return@forEach
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
    fun normalizeLegacyInternalStsPath(context: Context, rawPath: String?): String? {
        return normalizeLegacyStsPath(context, rawPath)
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

    private fun legacyInternalStsRootCandidates(context: Context): List<String> =
        legacyInternalStsRootCandidates(context.packageName, context.filesDir)

    private fun knownLegacyStsRootCandidates(context: Context): List<String> {
        val roots = LinkedHashSet<String>()
        legacyInternalStsRootCandidates(context).forEach(roots::add)
        legacyExternalStsRootCandidates(context.packageName, appExternalFilesRoot(context)).forEach(roots::add)
        return roots.toList()
    }

    private fun legacyInternalStsRootCandidates(
        packageName: String,
        filesDir: File?
    ): List<String> {
        val roots = LinkedHashSet<String>()
        filesDir?.let { actualFilesDir ->
            buildLegacyStsRoots(actualFilesDir).forEach(roots::add)
        }
        buildFallbackLegacyStsRoots(packageName).forEach(roots::add)
        return roots.toList()
    }

    private fun buildLegacyStsRoots(filesDir: File): List<String> {
        val roots = LinkedHashSet<String>()
        roots.add(File(filesDir, STS_DIR_NAME).absolutePath)
        runCatching {
            File(filesDir.canonicalFile, STS_DIR_NAME).absolutePath
        }.getOrNull()?.let(roots::add)
        resolveAlternateLegacyFilesDir(filesDir)?.let { alternateFilesDir ->
            roots.add(File(alternateFilesDir, STS_DIR_NAME).path)
        }
        return roots.toList()
    }

    private fun legacyExternalStsRootCandidates(
        packageName: String,
        externalFilesDir: File?
    ): List<String> {
        val roots = LinkedHashSet<String>()
        externalFilesDir?.let { actualExternalFilesDir ->
            roots.add(File(actualExternalFilesDir, STS_DIR_NAME).absolutePath)
            runCatching {
                File(actualExternalFilesDir.canonicalFile, STS_DIR_NAME).absolutePath
            }.getOrNull()?.let(roots::add)
        }
        buildFallbackExternalStsRoots(packageName).forEach(roots::add)
        return roots.toList()
    }

    private fun buildFallbackLegacyStsRoots(packageName: String): List<String> {
        val filesPathSegments = listOf(packageName, ANDROID_FILES_SEGMENT, STS_DIR_NAME)
        return listOf(
            buildAndroidAbsolutePath(
                listOf(
                    ANDROID_DATA_SEGMENT,
                    ANDROID_USER_SEGMENT,
                    ANDROID_USER_ZERO_SEGMENT
                ) + filesPathSegments
            ),
            buildAndroidAbsolutePath(
                listOf(
                    ANDROID_DATA_SEGMENT,
                    ANDROID_DATA_SEGMENT
                ) + filesPathSegments
            )
        )
    }

    private fun buildFallbackExternalStsRoots(packageName: String): List<String> {
        val filesPathSegments = listOf(
            "Android",
            ANDROID_DATA_SEGMENT,
            packageName,
            ANDROID_FILES_SEGMENT,
            STS_DIR_NAME
        )
        return listOf(
            buildAndroidAbsolutePath(
                listOf(
                    ANDROID_EXTERNAL_STORAGE_ROOT,
                    ANDROID_EMULATED_SEGMENT,
                    ANDROID_USER_ZERO_SEGMENT
                ) + filesPathSegments
            ),
            buildAndroidAbsolutePath(listOf(ANDROID_SDCARD_SEGMENT) + filesPathSegments)
        )
    }

    private fun resolveAlternateLegacyFilesDir(filesDir: File): File? {
        val segments = filesDir.path
            .replace('\\', '/')
            .split(ANDROID_PATH_SEPARATOR)
            .filter { it.isNotEmpty() }
        if (segments.lastOrNull() != ANDROID_FILES_SEGMENT) {
            return null
        }
        val alternatePath = when {
            segments.size >= 5 &&
                segments[0] == ANDROID_DATA_SEGMENT &&
                segments[1] == ANDROID_USER_SEGMENT &&
                segments[2] == ANDROID_USER_ZERO_SEGMENT ->
                buildAndroidAbsolutePath(
                    listOf(
                        ANDROID_DATA_SEGMENT,
                        ANDROID_DATA_SEGMENT
                    ) + segments.drop(3)
                )
            segments.size >= 4 &&
                segments[0] == ANDROID_DATA_SEGMENT &&
                segments[1] == ANDROID_DATA_SEGMENT ->
                buildAndroidAbsolutePath(
                    listOf(
                        ANDROID_DATA_SEGMENT,
                        ANDROID_USER_SEGMENT,
                        ANDROID_USER_ZERO_SEGMENT
                    ) + segments.drop(2)
                )
            else -> null
        }
        return alternatePath?.let(::File)
    }

    private fun buildAndroidAbsolutePath(segments: List<String>): String =
        ANDROID_PATH_SEPARATOR + segments.joinToString(ANDROID_PATH_SEPARATOR)

    @JvmStatic
    fun ensureBaseDirs(context: Context) {
        stsRoot(context).mkdirs()
        stsHome(context).mkdirs()
        requiredModsDir(context).mkdirs()
        optionalModsLibraryDir(context).mkdirs()
        jvmLogsDir(context).mkdirs()
        jvmHistogramsDir(context).mkdirs()
        logcatDir(context).mkdirs()
        windowDiagnosticsDir(context).mkdirs()
        launcherCrashReportsDir(context).mkdirs()
        bootOverlayImagesDir(context).mkdirs()
        mtsPatchCacheDir(context).mkdirs()
        mtsLocalJreBinDir(context).mkdirs()
        lwjglDir(context).mkdirs()
        lwjgl2InjectorDir(context).mkdirs()
        agentConnectorDir(context).mkdirs()
        bootBridgeDir(context).mkdirs()
        gdxPatchDir(context).mkdirs()
        gdxPatchNativesDir(context).mkdirs()
        nativeMarketPackagesDir(context).mkdirs()
        nativeMarketActiveDir(context).mkdirs()
        modSuggestionDir(context).mkdirs()
        bundledLog4jRuntimeDir(context).mkdirs()
        cacioDir(context).mkdirs()
        externalResourcesRoot(context).mkdirs()
        runtimeRoot(context).mkdirs()
    }

    internal fun isLogcatCaptureFileName(name: String): Boolean {
        return logcatCaptureBaseName(name, ALL_LOGCAT_CAPTURE_FILE_NAMES) != null
    }

    internal fun isMemoryDiagnosticsFileName(name: String): Boolean {
        return name == MEMORY_DIAGNOSTICS_LOG_FILE_NAME ||
            name.startsWith("$MEMORY_DIAGNOSTICS_LOG_FILE_NAME.")
    }

    internal fun isAchievementSyncLogFileName(name: String): Boolean {
        return name == ACHIEVEMENT_SYNC_LOG_FILE_NAME ||
            name.startsWith("$ACHIEVEMENT_SYNC_LOG_FILE_NAME.")
    }

    internal fun isWindowDiagnosticsFileName(name: String): Boolean {
        return name == WINDOW_DIAGNOSTICS_LOG_FILE_NAME ||
            name.startsWith("$WINDOW_DIAGNOSTICS_LOG_FILE_NAME.")
    }

    internal fun isLauncherCrashReportFileName(name: String): Boolean {
        return name.startsWith(LAUNCHER_CRASH_REPORT_PREFIX) &&
            name.endsWith(".txt", ignoreCase = true)
    }

    internal fun compareLogcatCaptureFileNames(left: String, right: String): Int {
        val leftBaseName = logcatCaptureBaseName(left, ALL_LOGCAT_CAPTURE_FILE_NAMES)
        val rightBaseName = logcatCaptureBaseName(right, ALL_LOGCAT_CAPTURE_FILE_NAMES)
        val byBaseName = logcatCaptureFileOrder(leftBaseName, ALL_LOGCAT_CAPTURE_FILE_NAMES)
            .compareTo(logcatCaptureFileOrder(rightBaseName, ALL_LOGCAT_CAPTURE_FILE_NAMES))
        if (byBaseName != 0) {
            return byBaseName
        }

        val byRotationIndex = rotationIndexForLogcatFile(left, leftBaseName)
            .compareTo(rotationIndexForLogcatFile(right, rightBaseName))
        if (byRotationIndex != 0) {
            return byRotationIndex
        }

        return left.compareTo(right)
    }

    internal fun compareMemoryDiagnosticsFileNames(left: String, right: String): Int {
        val byRotationIndex = rotationIndexForMemoryDiagnosticsFile(left)
            .compareTo(rotationIndexForMemoryDiagnosticsFile(right))
        if (byRotationIndex != 0) {
            return byRotationIndex
        }
        return left.compareTo(right)
    }

    internal fun compareAchievementSyncLogFileNames(left: String, right: String): Int {
        val byRotationIndex = rotationIndexForAchievementSyncLogFile(left)
            .compareTo(rotationIndexForAchievementSyncLogFile(right))
        if (byRotationIndex != 0) {
            return byRotationIndex
        }
        return left.compareTo(right)
    }

    internal fun compareWindowDiagnosticsFileNames(left: String, right: String): Int {
        val byRotationIndex = rotationIndexForWindowDiagnosticsFile(left)
            .compareTo(rotationIndexForWindowDiagnosticsFile(right))
        if (byRotationIndex != 0) {
            return byRotationIndex
        }
        return left.compareTo(right)
    }

    private fun legacyLogcatCaptureLog(context: Context): File {
        return File(logcatDir(context), LEGACY_LOGCAT_CAPTURE_FILE_NAME)
    }

    private fun listLogcatCaptureFiles(
        context: Context,
        recognizedBaseNames: List<String>,
        fallbackFiles: List<File>
    ): List<File> {
        val directory = logcatDir(context)
        if (!directory.isDirectory) {
            return fallbackFiles
        }
        return directory.listFiles()
            ?.asSequence()
            ?.filter { file ->
                file.isFile && logcatCaptureBaseName(file.name, recognizedBaseNames) != null
            }
            ?.sortedWith { left, right ->
                compareLogcatCaptureFileNames(left.name, right.name)
            }
            ?.toList()
            .orEmpty()
            .ifEmpty { fallbackFiles }
    }

    private fun logcatCaptureBaseName(name: String, recognizedBaseNames: List<String>): String? {
        return recognizedBaseNames.firstOrNull { baseName ->
            name == baseName || name.startsWith("$baseName.")
        }
    }

    private fun logcatCaptureFileOrder(baseName: String?, recognizedBaseNames: List<String>): Int {
        return recognizedBaseNames.indexOf(baseName).takeIf { it >= 0 } ?: Int.MAX_VALUE
    }

    private fun rotationIndexForLogcatFile(
        name: String,
        baseName: String? = logcatCaptureBaseName(name, ALL_LOGCAT_CAPTURE_FILE_NAMES)
    ): Int {
        if (baseName == null) {
            return Int.MAX_VALUE
        }
        if (name == baseName) {
            return 0
        }
        return name.substringAfter("$baseName.", "")
            .toIntOrNull()
            ?: Int.MAX_VALUE
    }

    private fun rotationIndexForMemoryDiagnosticsFile(name: String): Int {
        if (!isMemoryDiagnosticsFileName(name)) {
            return Int.MAX_VALUE
        }
        if (name == MEMORY_DIAGNOSTICS_LOG_FILE_NAME) {
            return 0
        }
        return name.substringAfter("$MEMORY_DIAGNOSTICS_LOG_FILE_NAME.", "")
            .toIntOrNull()
            ?: Int.MAX_VALUE
    }

    private fun rotationIndexForAchievementSyncLogFile(name: String): Int {
        if (!isAchievementSyncLogFileName(name)) {
            return Int.MAX_VALUE
        }
        if (name == ACHIEVEMENT_SYNC_LOG_FILE_NAME) {
            return 0
        }
        return name.substringAfter("$ACHIEVEMENT_SYNC_LOG_FILE_NAME.", "")
            .toIntOrNull()
            ?: Int.MAX_VALUE
    }

    private fun rotationIndexForWindowDiagnosticsFile(name: String): Int {
        if (!isWindowDiagnosticsFileName(name)) {
            return Int.MAX_VALUE
        }
        if (name == WINDOW_DIAGNOSTICS_LOG_FILE_NAME) {
            return 0
        }
        return name.substringAfter("$WINDOW_DIAGNOSTICS_LOG_FILE_NAME.", "")
            .toIntOrNull()
            ?: Int.MAX_VALUE
    }

}

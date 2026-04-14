package io.stamethyst.config

import android.content.Context
import java.io.File

object RuntimePaths {
    private const val ANDROID_DATA_SEGMENT = "data"
    private const val ANDROID_FILES_SEGMENT = "files"
    private const val ANDROID_PATH_SEPARATOR = "/"
    private const val ANDROID_USER_SEGMENT = "user"
    private const val ANDROID_USER_ZERO_SEGMENT = "0"
    private const val STS_DIR_NAME = "sts"
    private const val LATEST_LOG_FILE_NAME = "latest.log"
    private const val BOOT_BRIDGE_EVENTS_FILE_NAME = "boot_bridge_events.log"
    private const val JVM_LOG_DIR_NAME = "jvm_logs"
    private const val MEMORY_DIAGNOSTICS_LOG_FILE_NAME = "memory_diagnostics.log"
    private const val JVM_GC_LOG_FILE_NAME = "jvm_gc.log"
    private const val JVM_HEAP_SNAPSHOT_FILE_NAME = "jvm_heap_snapshot.txt"
    private const val JVM_SIGNAL_DUMP_FILE_NAME = "last_signal_dump.txt"
    private const val JVM_HISTOGRAM_DIR_NAME = "jvm_histograms"
    private const val LOGCAT_DIR_NAME = "logcat"
    private const val LEGACY_LOGCAT_CAPTURE_FILE_NAME = "logcat_capture.log"
    private const val LOGCAT_APP_CAPTURE_FILE_NAME = "logcat_app_capture.log"
    private const val LOGCAT_SYSTEM_CAPTURE_FILE_NAME = "logcat_system_capture.log"
    private const val LAUNCHER_LOGCAT_APP_CAPTURE_FILE_NAME = "launcher_logcat_app_capture.log"
    private const val LAUNCHER_LOGCAT_SYSTEM_CAPTURE_FILE_NAME = "launcher_logcat_system_capture.log"
    private const val MTS_CLASSPATH_CACHE_MARKER_FILE_NAME = ".mts_classpath_cache"
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
    fun optionalModsLibraryDir(context: Context): File = File(stsRoot(context), "mods_library")

    @JvmStatic
    fun importedBaseModJar(context: Context): File = File(modsDir(context), "BaseMod.jar")

    @JvmStatic
    fun importedStsLibJar(context: Context): File = File(modsDir(context), "StSLib.jar")

    @JvmStatic
    fun importedAmethystRuntimeCompatJar(context: Context): File =
        File(modsDir(context), "AmethystRuntimeCompat.jar")

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
    fun latestLog(context: Context): File = File(stsRoot(context), LATEST_LOG_FILE_NAME)

    @JvmStatic
    fun bootBridgeEventsLog(context: Context): File = File(stsRoot(context), BOOT_BRIDGE_EVENTS_FILE_NAME)

    @JvmStatic
    fun jvmLogsDir(context: Context): File = File(stsRoot(context), JVM_LOG_DIR_NAME)

    @JvmStatic
    fun memoryDiagnosticsLog(context: Context): File =
        File(jvmLogsDir(context), MEMORY_DIAGNOSTICS_LOG_FILE_NAME)

    @JvmStatic
    fun jvmGcLog(context: Context): File = File(stsRoot(context), JVM_GC_LOG_FILE_NAME)

    @JvmStatic
    fun jvmHeapSnapshot(context: Context): File = File(stsRoot(context), JVM_HEAP_SNAPSHOT_FILE_NAME)

    @JvmStatic
    fun jvmSignalDump(context: Context): File = File(stsRoot(context), JVM_SIGNAL_DUMP_FILE_NAME)

    @JvmStatic
    fun jvmHistogramsDir(context: Context): File = File(stsRoot(context), JVM_HISTOGRAM_DIR_NAME)

    @JvmStatic
    fun logcatDir(context: Context): File = File(stsRoot(context), LOGCAT_DIR_NAME)

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
    fun mtsClasspathCacheMarker(context: Context): File =
        File(stsRoot(context), MTS_CLASSPATH_CACHE_MARKER_FILE_NAME)

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
        modsDir(context).mkdirs()
        optionalModsLibraryDir(context).mkdirs()
        jvmLogsDir(context).mkdirs()
        jvmHistogramsDir(context).mkdirs()
        logcatDir(context).mkdirs()
        mtsLocalJreBinDir(context).mkdirs()
        lwjglDir(context).mkdirs()
        lwjgl2InjectorDir(context).mkdirs()
        bootBridgeDir(context).mkdirs()
        gdxPatchDir(context).mkdirs()
        gdxPatchNativesDir(context).mkdirs()
        nativeMarketPackagesDir(context).mkdirs()
        nativeMarketActiveDir(context).mkdirs()
        modSuggestionDir(context).mkdirs()
        bundledLog4jRuntimeDir(context).mkdirs()
        cacioDir(context).mkdirs()
        runtimeRoot(context).mkdirs()
    }

    internal fun isLogcatCaptureFileName(name: String): Boolean {
        return logcatCaptureBaseName(name, ALL_LOGCAT_CAPTURE_FILE_NAMES) != null
    }

    internal fun isMemoryDiagnosticsFileName(name: String): Boolean {
        return name == MEMORY_DIAGNOSTICS_LOG_FILE_NAME ||
            name.startsWith("$MEMORY_DIAGNOSTICS_LOG_FILE_NAME.")
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
}

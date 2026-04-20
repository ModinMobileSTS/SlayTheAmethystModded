package io.stamethyst.backend.launch

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.content.res.AssetManager
import io.stamethyst.R
import io.stamethyst.backend.diag.MemoryDiagnosticsLogger
import io.stamethyst.backend.fs.FileTreeCleaner
import io.stamethyst.backend.mods.MtsLoaderCrashPatcher
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONObject

object ComponentInstaller {
    private const val COMPONENT_INSTALL_MARKER_FILE_NAME = ".components-installed-marker"
    private const val DEFAULT_PREFS_ASSET_DIR = "components/default_saves/preferences"
    private const val BUNDLED_RUNTIME_NATIVE_ASSET_DIR = "components/bundled_runtime_natives"
    private const val LEGACY_HINA_VIDEO_PATCH_JAR = "hina-video-compat.jar"
    private const val PREF_FILE_PLAYER = "STSPlayer"
    private const val PREF_FILE_PLAYER_BACKUP = "STSPlayer.backUp"
    private const val PREF_FILE_SAVE_SLOTS = "STSSaveSlots"
    private const val PREF_FILE_SAVE_SLOTS_BACKUP = "STSSaveSlots.backUp"
    private const val PLAYER_REQUIRED_TOKEN = "\"name\""
    private const val SAVE_SLOTS_REQUIRED_TOKEN = "\"DEFAULT_SLOT\""

    private fun interface JarValidator {
        @Throws(IOException::class)
        fun validate(jarFile: File)
    }

    private data class PackagedComponentsState(
        val markerFile: File,
        val expectedMarker: String,
        val installedMarker: String?,
        val markerMatches: Boolean,
        val missingComponents: List<String>
    ) {
        val current: Boolean
            get() = markerMatches && missingComponents.isEmpty()
    }

    @Throws(IOException::class)
    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw IOException("Component install cancelled")
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun ensureInstalled(context: Context) {
        ensureInstalled(context, null)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun ensureInstalled(context: Context, progressCallback: StartupProgressCallback?) {
        throwIfInterrupted()
        RuntimePaths.ensureBaseDirs(context)
        val assets = context.assets
        val packagedComponentsState = evaluatePackagedComponentsState(
            context = context,
            assets = assets,
            expectedMarker = resolvePackagedComponentsMarker(context)
        )
        val packagedComponentsCurrent = packagedComponentsState.current
        logDiagnostic(
            context = context,
            event = "component_install_state_resolved",
            extras = linkedMapOf<String, Any?>(
                "packagedComponentsCurrent" to packagedComponentsCurrent,
                "forceReplaceBundledMods" to !packagedComponentsCurrent,
                "expectedMarker" to packagedComponentsState.expectedMarker,
                "installedMarker" to packagedComponentsState.installedMarker,
                "markerMatches" to packagedComponentsState.markerMatches,
                "markerFile" to buildFileState(packagedComponentsState.markerFile),
                "missingComponents" to packagedComponentsState.missingComponents
            )
        )

        throwIfInterrupted()
        if (packagedComponentsCurrent) {
            reportProgress(
                progressCallback,
                72,
                context.progressText(R.string.startup_progress_launcher_components_already_up_to_date)
            )
        } else {
            logDiagnostic(
                context = context,
                event = "component_install_packaged_components_update_started",
                extras = linkedMapOf<String, Any?>(
                    "missingComponents" to packagedComponentsState.missingComponents,
                    "markerMatches" to packagedComponentsState.markerMatches
                )
            )
            installPackagedComponents(context, assets, progressCallback)
            writeInstallMarker(
                componentInstallMarkerFile(context),
                packagedComponentsState.expectedMarker
            )
            logDiagnostic(
                context = context,
                event = "component_install_packaged_components_update_completed",
                extras = linkedMapOf<String, Any?>(
                    "markerFile" to buildFileState(componentInstallMarkerFile(context))
                )
            )
        }

        throwIfInterrupted()
        reportProgress(
            progressCallback,
            72,
            context.progressText(R.string.startup_progress_installing_bundled_mods)
        )
        installBundledMods(assets, context, forceReplaceExisting = !packagedComponentsCurrent)
        throwIfInterrupted()
        reportProgress(
            progressCallback,
            87,
            context.progressText(R.string.startup_progress_preparing_local_java_shim)
        )
        ensureMtsLocalJreShim(context)
        throwIfInterrupted()
        reportProgress(
            progressCallback,
            96,
            context.progressText(R.string.startup_progress_checking_default_preferences)
        )
        ensureDefaultPreferencesIfMissing(assets, context)
        throwIfInterrupted()
        reportProgress(
            progressCallback,
            100,
            context.progressText(R.string.startup_progress_components_ready)
        )
    }

    @Throws(IOException::class)
    private fun installPackagedComponents(
        context: Context,
        assets: AssetManager,
        progressCallback: StartupProgressCallback?
    ) {
        throwIfInterrupted()
        reportProgress(
            progressCallback,
            5,
            context.progressText(R.string.startup_progress_installing_lwjgl_bridge)
        )
        replaceAssetTree(assets, "components/lwjgl3", RuntimePaths.lwjglDir(context))
        throwIfInterrupted()
        reportProgress(
            progressCallback,
            15,
            context.progressText(R.string.startup_progress_installing_startup_bridge)
        )
        replaceAssetTree(assets, "components/boot_bridge", RuntimePaths.bootBridgeDir(context))
        throwIfInterrupted()
        reportProgress(
            progressCallback,
            25,
            context.progressText(R.string.startup_progress_installing_lwjgl2_injector)
        )
        replaceAssetTree(
            assets,
            "components/lwjgl2_methods_injector",
            RuntimePaths.lwjgl2InjectorDir(context)
        )
        throwIfInterrupted()
        reportProgress(
            progressCallback,
            40,
            context.progressText(R.string.startup_progress_installing_gdx_patches)
        )
        replaceAssetTree(assets, "components/gdx_patch", RuntimePaths.gdxPatchDir(context))
        removeLegacyCompatArtifacts(RuntimePaths.gdxPatchDir(context))
        throwIfInterrupted()
        reportProgress(
            progressCallback,
            48,
            context.progressText(R.string.startup_progress_installing_bundled_native_libraries)
        )
        if (hasAssetChildren(assets, BUNDLED_RUNTIME_NATIVE_ASSET_DIR)) {
            copyAssetTree(
                assets,
                BUNDLED_RUNTIME_NATIVE_ASSET_DIR,
                RuntimePaths.gdxPatchNativesDir(context)
            )
        }
        throwIfInterrupted()
        reportProgress(
            progressCallback,
            60,
            context.progressText(R.string.startup_progress_installing_bundled_log4j_runtime)
        )
        replaceAssetTree(
            assets,
            "components/log4j_runtime",
            RuntimePaths.bundledLog4jRuntimeDir(context)
        )
        throwIfInterrupted()
        reportProgress(
            progressCallback,
            70,
            context.progressText(R.string.startup_progress_installing_caciocavallo_runtime)
        )
        replaceAssetTree(assets, "components/caciocavallo", RuntimePaths.cacioDir(context))
    }

    @Throws(IOException::class)
    private fun installBundledMods(
        assets: AssetManager,
        context: Context,
        forceReplaceExisting: Boolean
    ) {
        logDiagnostic(
            context = context,
            event = "component_install_bundled_mods_started",
            extras = linkedMapOf<String, Any?>(
                "forceReplaceExisting" to forceReplaceExisting
            )
        )
        ensureBundledMod(
            context = context,
            modLabel = "ModTheSpire.jar",
            assets = assets,
            assetPath = "components/mods/ModTheSpire.jar",
            targetFile = RuntimePaths.importedMtsJar(context),
            validator = ModJarSupport::validateMtsJar,
            replaceExisting = forceReplaceExisting
        )
        logDiagnostic(
            context = context,
            event = "component_install_mts_loader_patch_started",
            extras = linkedMapOf<String, Any?>(
                "target" to buildFileState(RuntimePaths.importedMtsJar(context))
            )
        )
        MtsLoaderCrashPatcher.ensurePatchedMtsJar(RuntimePaths.importedMtsJar(context))
        logDiagnostic(
            context = context,
            event = "component_install_mts_loader_patch_completed",
            extras = linkedMapOf<String, Any?>(
                "target" to buildFileState(RuntimePaths.importedMtsJar(context))
            )
        )
        ModJarSupport.validateMtsJar(RuntimePaths.importedMtsJar(context))
        ensureBundledMod(
            context = context,
            modLabel = "BaseMod.jar",
            assets = assets,
            assetPath = "components/mods/BaseMod.jar",
            targetFile = RuntimePaths.importedBaseModJar(context),
            validator = ModJarSupport::validateBaseModJar,
            replaceExisting = forceReplaceExisting
        )
        ensureBundledMod(
            context = context,
            modLabel = "StSLib.jar",
            assets = assets,
            assetPath = "components/mods/StSLib.jar",
            targetFile = RuntimePaths.importedStsLibJar(context),
            validator = ModJarSupport::validateStsLibJar,
            replaceExisting = forceReplaceExisting
        )
        ensureBundledMod(
            context = context,
            modLabel = "AmethystRuntimeCompat.jar",
            assets = assets,
            assetPath = "components/mods/AmethystRuntimeCompat.jar",
            targetFile = RuntimePaths.importedAmethystRuntimeCompatJar(context),
            validator = ModJarSupport::validateAmethystRuntimeCompatJar,
            replaceExisting = forceReplaceExisting
        )
        logDiagnostic(
            context = context,
            event = "component_install_bundled_mods_completed",
            extras = linkedMapOf<String, Any?>(
                "runtimeModFiles" to listOf(
                    RuntimePaths.importedMtsJar(context),
                    RuntimePaths.importedBaseModJar(context),
                    RuntimePaths.importedStsLibJar(context),
                    RuntimePaths.importedAmethystRuntimeCompatJar(context)
                ).map(::buildFileState)
            )
        )
    }

    @Throws(IOException::class)
    private fun copyAssetTree(assets: AssetManager, assetPath: String, targetDir: File) {
        throwIfInterrupted()
        val names = assets.list(assetPath) ?: throw IOException("Asset listing failed: $assetPath")
        if (names.isEmpty()) {
            copyFile(assets, assetPath, File(targetDir.parentFile, File(assetPath).name))
            return
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Failed to create: $targetDir")
        }
        for (name in names) {
            throwIfInterrupted()
            val childAssetPath = "$assetPath/$name"
            val childList = assets.list(childAssetPath)
            val childFile = File(targetDir, name)
            if (childList != null && childList.isNotEmpty()) {
                copyAssetTree(assets, childAssetPath, childFile)
            } else {
                copyFile(assets, childAssetPath, childFile)
            }
        }
    }

    @Throws(IOException::class)
    private fun replaceAssetTree(assets: AssetManager, assetPath: String, targetDir: File) {
        prepareCleanDirectory(targetDir, "component directory")
        copyAssetTree(assets, assetPath, targetDir)
    }

    @Throws(IOException::class)
    private fun copyAssetTreeIfMissing(assets: AssetManager, assetPath: String, targetDir: File) {
        throwIfInterrupted()
        val names = assets.list(assetPath) ?: throw IOException("Asset listing failed: $assetPath")
        if (names.isEmpty()) {
            copyFileIfMissing(assets, assetPath, File(targetDir.parentFile, File(assetPath).name))
            return
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Failed to create: $targetDir")
        }
        for (name in names) {
            throwIfInterrupted()
            val childAssetPath = "$assetPath/$name"
            val childList = assets.list(childAssetPath)
            val childFile = File(targetDir, name)
            if (childList != null && childList.isNotEmpty()) {
                copyAssetTreeIfMissing(assets, childAssetPath, childFile)
            } else {
                copyFileIfMissing(assets, childAssetPath, childFile)
            }
        }
    }

    @Throws(IOException::class)
    private fun copyFile(assets: AssetManager, assetPath: String, targetFile: File) {
        throwIfInterrupted()
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create parent: $parent")
        }
        assets.open(assetPath).use { input ->
            FileOutputStream(targetFile, false).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    throwIfInterrupted()
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun copyFileIfMissing(assets: AssetManager, assetPath: String, targetFile: File) {
        if (targetFile.isFile && targetFile.length() > 0) {
            return
        }
        copyFile(assets, assetPath, targetFile)
    }

    @Throws(IOException::class)
    private fun ensureBundledMod(
        context: Context,
        modLabel: String,
        assets: AssetManager,
        assetPath: String,
        targetFile: File,
        validator: JarValidator,
        replaceExisting: Boolean = false
    ) {
        throwIfInterrupted()
        logDiagnostic(
            context = context,
            event = "component_install_bundled_mod_started",
            extras = linkedMapOf<String, Any?>(
                "modLabel" to modLabel,
                "assetPath" to assetPath,
                "replaceExisting" to replaceExisting,
                "target" to buildFileState(targetFile)
            )
        )
        if (!replaceExisting && targetFile.isFile && targetFile.length() > 0) {
            logDiagnostic(
                context = context,
                event = "component_install_bundled_mod_existing_validation_started",
                extras = linkedMapOf<String, Any?>(
                    "modLabel" to modLabel,
                    "target" to buildFileState(targetFile)
                )
            )
            try {
                validator.validate(targetFile)
                logDiagnostic(
                    context = context,
                    event = "component_install_bundled_mod_reused_existing",
                    extras = linkedMapOf<String, Any?>(
                        "modLabel" to modLabel,
                        "target" to buildFileState(targetFile)
                    )
                )
                return
            } catch (error: Throwable) {
                logDiagnostic(
                    context = context,
                    event = "component_install_bundled_mod_existing_validation_failed",
                    extras = linkedMapOf<String, Any?>(
                        "modLabel" to modLabel,
                        "target" to buildFileState(targetFile),
                        "errorClass" to error.javaClass.name,
                        "errorMessage" to error.message
                    )
                )
                logDiagnostic(
                    context = context,
                    event = "component_install_bundled_mod_delete_started",
                    extras = linkedMapOf<String, Any?>(
                        "modLabel" to modLabel,
                        "reason" to "invalid_existing",
                        "target" to buildFileState(targetFile)
                    )
                )
                if (!targetFile.delete()) {
                    throw IOException("Failed to replace invalid mod file: ${targetFile.absolutePath}")
                }
                logDiagnostic(
                    context = context,
                    event = "component_install_bundled_mod_delete_completed",
                    extras = linkedMapOf<String, Any?>(
                        "modLabel" to modLabel,
                        "reason" to "invalid_existing",
                        "target" to buildFileState(targetFile)
                    )
                )
            }
        }
        if (replaceExisting && targetFile.exists()) {
            logDiagnostic(
                context = context,
                event = "component_install_bundled_mod_delete_started",
                extras = linkedMapOf<String, Any?>(
                    "modLabel" to modLabel,
                    "reason" to "replace_existing",
                    "target" to buildFileState(targetFile)
                )
            )
            if (!targetFile.delete()) {
                throw IOException("Failed to replace bundled mod file: ${targetFile.absolutePath}")
            }
            logDiagnostic(
                context = context,
                event = "component_install_bundled_mod_delete_completed",
                extras = linkedMapOf<String, Any?>(
                    "modLabel" to modLabel,
                    "reason" to "replace_existing",
                    "target" to buildFileState(targetFile)
                )
            )
        }
        logDiagnostic(
            context = context,
            event = "component_install_bundled_mod_copy_started",
            extras = linkedMapOf<String, Any?>(
                "modLabel" to modLabel,
                "assetPath" to assetPath,
                "target" to buildFileState(targetFile)
            )
        )
        copyFile(assets, assetPath, targetFile)
        if (!targetFile.isFile || targetFile.length() <= 0) {
            throw IOException("Bundled mod install failed: ${targetFile.absolutePath}")
        }
        logDiagnostic(
            context = context,
            event = "component_install_bundled_mod_copy_completed",
            extras = linkedMapOf<String, Any?>(
                "modLabel" to modLabel,
                "target" to buildFileState(targetFile)
            )
        )
        logDiagnostic(
            context = context,
            event = "component_install_bundled_mod_validation_started",
            extras = linkedMapOf<String, Any?>(
                "modLabel" to modLabel,
                "target" to buildFileState(targetFile)
            )
        )
        try {
            validator.validate(targetFile)
        } catch (error: Throwable) {
            logDiagnostic(
                context = context,
                event = "component_install_bundled_mod_validation_failed",
                extras = linkedMapOf<String, Any?>(
                    "modLabel" to modLabel,
                    "target" to buildFileState(targetFile),
                    "errorClass" to error.javaClass.name,
                    "errorMessage" to error.message
                )
            )
            throw IOException(
                "Bundled mod validation failed: ${targetFile.name}: ${error.message}",
                error
            )
        }
        logDiagnostic(
            context = context,
            event = "component_install_bundled_mod_completed",
            extras = linkedMapOf<String, Any?>(
                "modLabel" to modLabel,
                "target" to buildFileState(targetFile)
            )
        )
    }

    @Throws(IOException::class)
    private fun ensureMtsLocalJreShim(context: Context) {
        throwIfInterrupted()
        val javaShim = RuntimePaths.mtsLocalJavaShim(context)
        val parent = javaShim.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create MTS jre shim directory: $parent")
        }

        val runtimeJava = File(RuntimePaths.runtimeRoot(context), "bin/java")
        val script = "#!/system/bin/sh\n" +
            "RUNTIME_JAVA=\"${runtimeJava.absolutePath}\"\n" +
                $$"if [ -x \"$JAVA_HOME/bin/java\" ]; then\n" +
                $$"  exec \"$JAVA_HOME/bin/java\" \"$@\"\n" +
            "fi\n" +
                $$"exec \"$RUNTIME_JAVA\" \"$@\"\n"

        FileOutputStream(javaShim, false).use { output ->
            output.write(script.toByteArray(StandardCharsets.UTF_8))
        }

        javaShim.setReadable(true, true)
        javaShim.setWritable(true, true)
        if (!javaShim.setExecutable(true, true)) {
            throw IOException("Failed to mark MTS jre shim executable: ${javaShim.absolutePath}")
        }
    }

    @Throws(IOException::class)
    private fun ensureDefaultPreferencesIfMissing(assets: AssetManager, context: Context) {
        throwIfInterrupted()
        if (!hasAssetChildren(assets, DEFAULT_PREFS_ASSET_DIR)) {
            return
        }
        ensureDefaultPreferencesForDir(assets, RuntimePaths.preferencesDir(context))
    }

    @Throws(IOException::class)
    private fun ensureDefaultPreferencesForDir(assets: AssetManager, preferencesDir: File) {
        throwIfInterrupted()
        if (!shouldInstallDefaultPreferences(preferencesDir)) {
            return
        }
        copyAssetTreeIfMissing(assets, DEFAULT_PREFS_ASSET_DIR, preferencesDir)
        repairSentinelFile(assets, preferencesDir, PREF_FILE_PLAYER, PLAYER_REQUIRED_TOKEN)
        repairSentinelFile(assets, preferencesDir, PREF_FILE_SAVE_SLOTS, SAVE_SLOTS_REQUIRED_TOKEN)
        copyFileIfMissing(
            assets,
            "$DEFAULT_PREFS_ASSET_DIR/$PREF_FILE_PLAYER_BACKUP",
            File(preferencesDir, PREF_FILE_PLAYER_BACKUP)
        )
        copyFileIfMissing(
            assets,
            "$DEFAULT_PREFS_ASSET_DIR/$PREF_FILE_SAVE_SLOTS_BACKUP",
            File(preferencesDir, PREF_FILE_SAVE_SLOTS_BACKUP)
        )
    }

    @Throws(IOException::class)
    private fun repairSentinelFile(
        assets: AssetManager,
        preferencesDir: File,
        fileName: String,
        requiredToken: String
    ) {
        throwIfInterrupted()
        val target = File(preferencesDir, fileName)
        if (fileContainsToken(target, requiredToken)) {
            return
        }
        copyFile(assets, "$DEFAULT_PREFS_ASSET_DIR/$fileName", target)
    }

    private fun shouldInstallDefaultPreferences(preferencesDir: File): Boolean {
        return !fileContainsToken(File(preferencesDir, PREF_FILE_PLAYER), PLAYER_REQUIRED_TOKEN) ||
            !fileContainsToken(File(preferencesDir, PREF_FILE_SAVE_SLOTS), SAVE_SLOTS_REQUIRED_TOKEN)
    }

    private fun fileContainsToken(file: File, token: String): Boolean {
        if (token.isEmpty()) {
            return false
        }
        if (!file.isFile || file.length() <= 0) {
            return false
        }
        val bytes = ByteArray(file.length().coerceAtMost(4096L).toInt())
        return try {
            FileInputStream(file).use { input ->
                val read = input.read(bytes)
                if (read <= 0) {
                    return false
                }
                val text = String(bytes, 0, read, StandardCharsets.UTF_8)
                text.contains(token)
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasAssetChildren(assets: AssetManager, assetPath: String): Boolean {
        return try {
            val names = assets.list(assetPath)
            names != null && names.isNotEmpty()
        } catch (_: IOException) {
            false
        }
    }

    private fun componentInstallMarkerFile(context: Context): File {
        return File(RuntimePaths.componentRoot(context), COMPONENT_INSTALL_MARKER_FILE_NAME)
    }

    private fun evaluatePackagedComponentsState(
        context: Context,
        assets: AssetManager,
        expectedMarker: String
    ): PackagedComponentsState {
        val markerFile = componentInstallMarkerFile(context)
        val installedMarker = try {
            if (!markerFile.isFile) {
                null
            } else {
                markerFile.readText(StandardCharsets.UTF_8).trim()
            }
        } catch (_: Throwable) {
            null
        }
        return PackagedComponentsState(
            markerFile = markerFile,
            expectedMarker = expectedMarker,
            installedMarker = installedMarker,
            markerMatches = installedMarker == expectedMarker,
            missingComponents = collectMissingPackagedComponents(context, assets)
        )
    }

    private fun collectMissingPackagedComponents(context: Context, assets: AssetManager): List<String> {
        val missing = ArrayList<String>()
        if (!File(RuntimePaths.lwjglDir(context), "version").isFile ||
            !RuntimePaths.lwjglJar(context).isFile
        ) {
            missing += "lwjgl3"
        }
        if (!RuntimePaths.bootBridgeJar(context).isFile) {
            missing += "boot_bridge"
        }
        if (!File(RuntimePaths.lwjgl2InjectorDir(context), "version").isFile ||
            !RuntimePaths.lwjgl2InjectorJar(context).isFile
        ) {
            missing += "lwjgl2_injector"
        }
        if (!RuntimePaths.gdxPatchJar(context).isFile) {
            missing += "gdx_patch"
        }
        if (!RuntimePaths.bundledLog4jApiJar(context).isFile ||
            !RuntimePaths.bundledLog4jCoreJar(context).isFile
        ) {
            missing += "bundled_log4j_runtime"
        }
        if (!File(RuntimePaths.cacioDir(context), "version").isFile) {
            missing += "caciocavallo"
        }
        if (hasAssetChildren(assets, BUNDLED_RUNTIME_NATIVE_ASSET_DIR)) {
            if (!containsNonEmptyFile(RuntimePaths.gdxPatchNativesDir(context))) {
                missing += "bundled_runtime_natives"
            }
        }
        return missing
    }

    private fun resolvePackagedComponentsMarker(context: Context): String {
        val packageInfo = loadPackageInfo(context)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        return "$versionCode|${packageInfo.lastUpdateTime}"
    }

    private fun loadPackageInfo(context: Context): PackageInfo {
        val packageManager = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, 0)
        }
    }

    @Throws(IOException::class)
    private fun writeInstallMarker(markerFile: File, marker: String) {
        val parent = markerFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create component marker directory: $parent")
        }
        FileOutputStream(markerFile, false).use { output ->
            output.write(marker.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun reportProgress(callback: StartupProgressCallback?, percent: Int, message: String) {
        if (callback == null) {
            return
        }
        val bounded = percent.coerceIn(0, 100)
        callback.onProgress(bounded, message)
    }

    private fun containsNonEmptyFile(root: File): Boolean {
        if (!root.exists()) {
            return false
        }
        if (root.isFile) {
            return root.length() > 0L
        }
        return root.listFiles().orEmpty().any(::containsNonEmptyFile)
    }

    private fun buildFileState(file: File): JSONObject {
        return JSONObject().apply {
            put("path", file.absolutePath)
            put("exists", file.exists())
            put("isFile", file.isFile)
            put("bytes", if (file.isFile) file.length().coerceAtLeast(0L) else 0L)
            put("lastModifiedMs", if (file.exists()) file.lastModified().coerceAtLeast(0L) else 0L)
        }
    }

    private fun logDiagnostic(
        context: Context,
        event: String,
        extras: Map<String, Any?> = emptyMap()
    ) {
        MemoryDiagnosticsLogger.logEvent(
            context = context,
            event = event,
            extras = extras,
            includeMemorySnapshot = false
        )
    }

    private fun removeLegacyCompatArtifacts(gdxPatchDir: File) {
        val legacyHinaPatch = File(gdxPatchDir, LEGACY_HINA_VIDEO_PATCH_JAR)
        if (legacyHinaPatch.isFile) {
            legacyHinaPatch.delete()
        }
    }

    @Throws(IOException::class)
    private fun prepareCleanDirectory(directory: File, label: String) {
        throwIfInterrupted()
        val parent = directory.parentFile
        if (parent != null) {
            if (parent.exists() && !parent.isDirectory) {
                throw IOException("$label parent is not a directory: ${parent.absolutePath}")
            }
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Failed to create $label parent: ${parent.absolutePath}")
            }
        }

        FileTreeCleaner.deleteRecursively(directory)
        if (!directory.exists()) {
            if (!directory.mkdirs() && !directory.isDirectory) {
                throw IOException("Failed to create $label: ${directory.absolutePath}")
            }
            return
        }

        if (!directory.isDirectory) {
            throw IOException("$label path is not a directory: ${directory.absolutePath}")
        }

        val remaining = directory.listFiles()
            ?: throw IOException("Failed to inspect $label: ${directory.absolutePath}")
        if (remaining.isEmpty()) {
            return
        }

        for (child in remaining) {
            throwIfInterrupted()
            FileTreeCleaner.deleteRecursively(child)
        }
        val stillRemaining = directory.listFiles()
        if (stillRemaining == null || stillRemaining.isNotEmpty()) {
            val remainingSummary = FileTreeCleaner.summarizeRemainingEntries(directory)
            val detail = if (remainingSummary.isNullOrBlank()) "" else " (remaining: $remainingSummary)"
            throw IOException("Failed to clean $label: ${directory.absolutePath}$detail")
        }
    }
}

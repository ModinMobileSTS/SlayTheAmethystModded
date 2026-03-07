package io.stamethyst.backend.launch

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.content.res.AssetManager
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

object ComponentInstaller {
    private const val COMPONENT_INSTALL_MARKER_FILE_NAME = ".components-installed-marker"
    private const val DEFAULT_PREFS_ASSET_DIR = "components/default_saves/preferences"
    private const val GDX_VIDEO_NATIVE_ASSET_DIR = "components/gdx_video_natives"
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
        val packagedComponentsMarker = resolvePackagedComponentsMarker(context)

        throwIfInterrupted()
        if (arePackagedComponentsCurrent(context, assets, packagedComponentsMarker)) {
            reportProgress(progressCallback, 72, "Launcher components already up to date")
        } else {
            installPackagedComponents(context, assets, progressCallback)
            writeInstallMarker(componentInstallMarkerFile(context), packagedComponentsMarker)
        }

        throwIfInterrupted()
        reportProgress(progressCallback, 72, "Installing bundled mods...")
        installBundledMods(assets, context)
        throwIfInterrupted()
        reportProgress(progressCallback, 87, "Preparing local java shim...")
        ensureMtsLocalJreShim(context)
        throwIfInterrupted()
        reportProgress(progressCallback, 96, "Checking default preferences...")
        ensureDefaultPreferencesIfMissing(assets, context)
        throwIfInterrupted()
        reportProgress(progressCallback, 100, "Components ready")
    }

    @Throws(IOException::class)
    private fun installPackagedComponents(
        context: Context,
        assets: AssetManager,
        progressCallback: StartupProgressCallback?
    ) {
        throwIfInterrupted()
        reportProgress(progressCallback, 5, "Installing LWJGL bridge...")
        replaceAssetTree(assets, "components/lwjgl3", RuntimePaths.lwjglDir(context))
        throwIfInterrupted()
        reportProgress(progressCallback, 15, "Installing startup bridge...")
        replaceAssetTree(assets, "components/boot_bridge", RuntimePaths.bootBridgeDir(context))
        throwIfInterrupted()
        reportProgress(progressCallback, 25, "Installing LWJGL2 injector...")
        replaceAssetTree(
            assets,
            "components/lwjgl2_methods_injector",
            RuntimePaths.lwjgl2InjectorDir(context)
        )
        throwIfInterrupted()
        reportProgress(progressCallback, 40, "Installing gdx patches...")
        replaceAssetTree(assets, "components/gdx_patch", RuntimePaths.gdxPatchDir(context))
        removeLegacyCompatArtifacts(RuntimePaths.gdxPatchDir(context))
        throwIfInterrupted()
        reportProgress(progressCallback, 48, "Installing gdx video natives...")
        if (hasAssetChildren(assets, GDX_VIDEO_NATIVE_ASSET_DIR)) {
            copyAssetTree(assets, GDX_VIDEO_NATIVE_ASSET_DIR, RuntimePaths.gdxPatchNativesDir(context))
        }
        throwIfInterrupted()
        reportProgress(progressCallback, 55, "Installing Caciocavallo runtime...")
        replaceAssetTree(assets, "components/caciocavallo", RuntimePaths.cacioDir(context))
    }

    @Throws(IOException::class)
    private fun installBundledMods(assets: AssetManager, context: Context) {
        ensureBundledMod(
            assets,
            "components/mods/ModTheSpire.jar",
            RuntimePaths.importedMtsJar(context),
            ModJarSupport::validateMtsJar
        )
        ensureBundledMod(
            assets,
            "components/mods/BaseMod.jar",
            RuntimePaths.importedBaseModJar(context),
            ModJarSupport::validateBaseModJar
        )
        ensureBundledMod(
            assets,
            "components/mods/StSLib.jar",
            RuntimePaths.importedStsLibJar(context),
            ModJarSupport::validateStsLibJar
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
        assets: AssetManager,
        assetPath: String,
        targetFile: File,
        validator: JarValidator
    ) {
        throwIfInterrupted()
        if (targetFile.isFile && targetFile.length() > 0) {
            try {
                validator.validate(targetFile)
                return
            } catch (_: Throwable) {
                if (!targetFile.delete()) {
                    throw IOException("Failed to replace invalid mod file: ${targetFile.absolutePath}")
                }
            }
        }
        copyFileIfMissing(assets, assetPath, targetFile)
        if (!targetFile.isFile || targetFile.length() <= 0) {
            throw IOException("Bundled mod install failed: ${targetFile.absolutePath}")
        }
        try {
            validator.validate(targetFile)
        } catch (error: Throwable) {
            throw IOException(
                "Bundled mod validation failed: ${targetFile.name}: ${error.message}",
                error
            )
        }
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
        ensureDefaultPreferencesForDir(assets, RuntimePaths.betaPreferencesDir(context))
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

    private fun arePackagedComponentsCurrent(
        context: Context,
        assets: AssetManager,
        expectedMarker: String
    ): Boolean {
        val markerFile = componentInstallMarkerFile(context)
        if (!markerFile.isFile) {
            return false
        }
        val installedMarker = try {
            markerFile.readText(StandardCharsets.UTF_8).trim()
        } catch (_: Throwable) {
            return false
        }
        if (installedMarker != expectedMarker) {
            return false
        }
        return hasInstalledPackagedComponents(context, assets)
    }

    private fun hasInstalledPackagedComponents(context: Context, assets: AssetManager): Boolean {
        if (!File(RuntimePaths.lwjglDir(context), "version").isFile ||
            !RuntimePaths.lwjglJar(context).isFile
        ) {
            return false
        }
        if (!RuntimePaths.bootBridgeJar(context).isFile) {
            return false
        }
        if (!File(RuntimePaths.lwjgl2InjectorDir(context), "version").isFile ||
            !RuntimePaths.lwjgl2InjectorJar(context).isFile
        ) {
            return false
        }
        if (!RuntimePaths.gdxPatchJar(context).isFile) {
            return false
        }
        if (!File(RuntimePaths.cacioDir(context), "version").isFile) {
            return false
        }
        if (hasAssetChildren(assets, GDX_VIDEO_NATIVE_ASSET_DIR)) {
            val nativeFiles = RuntimePaths.gdxPatchNativesDir(context).listFiles()
            if (nativeFiles.isNullOrEmpty() || nativeFiles.none { it.isFile && it.length() > 0L }) {
                return false
            }
        }
        return true
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
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create $label parent: $parent")
        }
        deleteRecursively(directory)
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Failed to create $label: ${directory.absolutePath}")
        }
    }

    private fun deleteRecursively(file: File?): Boolean {
        if (Thread.currentThread().isInterrupted) {
            return false
        }
        if (file == null || !file.exists()) {
            return true
        }
        var deleted = true
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    if (Thread.currentThread().isInterrupted) {
                        return false
                    }
                    deleted = deleted && deleteRecursively(child)
                }
            }
        }
        if (!file.delete() && file.exists()) {
            deleted = false
        }
        return deleted
    }
}

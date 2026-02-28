package io.stamethyst.backend.components

import android.content.Context
import android.content.res.AssetManager
import io.stamethyst.backend.core.RuntimePaths
import io.stamethyst.backend.launch.StartupProgressCallback
import io.stamethyst.backend.mods.ModJarSupport
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

object ComponentInstaller {
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

    @JvmStatic
    @Throws(IOException::class)
    fun ensureInstalled(context: Context) {
        ensureInstalled(context, null)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun ensureInstalled(context: Context, progressCallback: StartupProgressCallback?) {
        RuntimePaths.ensureBaseDirs(context)
        val assets = context.assets

        reportProgress(progressCallback, 5, "Installing LWJGL bridge...")
        copyAssetTree(assets, "components/lwjgl3", RuntimePaths.lwjglDir(context))
        reportProgress(progressCallback, 15, "Installing startup bridge...")
        copyAssetTree(assets, "components/boot_bridge", RuntimePaths.bootBridgeDir(context))
        reportProgress(progressCallback, 25, "Installing LWJGL2 injector...")
        copyAssetTree(
            assets,
            "components/lwjgl2_methods_injector",
            RuntimePaths.lwjgl2InjectorDir(context)
        )
        reportProgress(progressCallback, 40, "Installing gdx patches...")
        copyAssetTree(assets, "components/gdx_patch", RuntimePaths.gdxPatchDir(context))
        removeLegacyCompatArtifacts(RuntimePaths.gdxPatchDir(context))
        reportProgress(progressCallback, 48, "Installing gdx video natives...")
        if (hasAssetChildren(assets, GDX_VIDEO_NATIVE_ASSET_DIR)) {
            copyAssetTree(assets, GDX_VIDEO_NATIVE_ASSET_DIR, RuntimePaths.gdxPatchNativesDir(context))
        }
        reportProgress(progressCallback, 55, "Installing Caciocavallo runtime...")
        copyAssetTree(assets, "components/caciocavallo", RuntimePaths.cacioDir(context))
        reportProgress(progressCallback, 72, "Installing bundled mods...")
        installBundledMods(assets, context)
        reportProgress(progressCallback, 87, "Preparing local java shim...")
        ensureMtsLocalJreShim(context)
        reportProgress(progressCallback, 96, "Checking default preferences...")
        ensureDefaultPreferencesIfMissing(assets, context)
        reportProgress(progressCallback, 100, "Components ready")
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
        val names = assets.list(assetPath) ?: throw IOException("Asset listing failed: $assetPath")
        if (names.isEmpty()) {
            copyFile(assets, assetPath, File(targetDir.parentFile, File(assetPath).name))
            return
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Failed to create: $targetDir")
        }
        for (name in names) {
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
    private fun copyAssetTreeIfMissing(assets: AssetManager, assetPath: String, targetDir: File) {
        val names = assets.list(assetPath) ?: throw IOException("Asset listing failed: $assetPath")
        if (names.isEmpty()) {
            copyFileIfMissing(assets, assetPath, File(targetDir.parentFile, File(assetPath).name))
            return
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Failed to create: $targetDir")
        }
        for (name in names) {
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
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create parent: $parent")
        }
        assets.open(assetPath).use { input ->
            FileOutputStream(targetFile, false).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
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
        val javaShim = RuntimePaths.mtsLocalJavaShim(context)
        val parent = javaShim.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create MTS jre shim directory: $parent")
        }

        val runtimeJava = File(RuntimePaths.runtimeRoot(context), "bin/java")
        val script = "#!/system/bin/sh\n" +
            "RUNTIME_JAVA=\"${runtimeJava.absolutePath}\"\n" +
            "if [ -x \"\$JAVA_HOME/bin/java\" ]; then\n" +
            "  exec \"\$JAVA_HOME/bin/java\" \"\$@\"\n" +
            "fi\n" +
            "exec \"\$RUNTIME_JAVA\" \"\$@\"\n"

        FileOutputStream(javaShim, false).use { output ->
            output.write(script.toByteArray(StandardCharsets.UTF_8))
        }

        javaShim.setReadable(true, false)
        javaShim.setWritable(true, true)
        if (!javaShim.setExecutable(true, false)) {
            throw IOException("Failed to mark MTS jre shim executable: ${javaShim.absolutePath}")
        }
    }

    @Throws(IOException::class)
    private fun ensureDefaultPreferencesIfMissing(assets: AssetManager, context: Context) {
        if (!hasAssetChildren(assets, DEFAULT_PREFS_ASSET_DIR)) {
            return
        }
        ensureDefaultPreferencesForDir(assets, RuntimePaths.preferencesDir(context))
        ensureDefaultPreferencesForDir(assets, RuntimePaths.betaPreferencesDir(context))
    }

    @Throws(IOException::class)
    private fun ensureDefaultPreferencesForDir(assets: AssetManager, preferencesDir: File) {
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
        val bytes = ByteArray(Math.min(file.length(), 4096L).toInt())
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

    private fun reportProgress(callback: StartupProgressCallback?, percent: Int, message: String) {
        if (callback == null) {
            return
        }
        val bounded = Math.max(0, Math.min(100, percent))
        callback.onProgress(bounded, message)
    }

    private fun removeLegacyCompatArtifacts(gdxPatchDir: File) {
        val legacyHinaPatch = File(gdxPatchDir, LEGACY_HINA_VIDEO_PATCH_JAR)
        if (legacyHinaPatch.isFile) {
            legacyHinaPatch.delete()
        }
    }
}

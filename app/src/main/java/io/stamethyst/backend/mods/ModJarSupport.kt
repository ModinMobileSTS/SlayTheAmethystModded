package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.R
import io.stamethyst.backend.launch.StartupProgressCallback
import io.stamethyst.backend.launch.progressText
import io.stamethyst.backend.process.AppProcess
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import java.util.ArrayList
import java.util.zip.ZipFile

object ModJarSupport {
    private const val EXPECTED_AMETHYST_RUNTIME_COMPAT_VERSION = "1.0.33"
    private const val EXPECTED_AMETHYST_FLOATING_TOOLS_VERSION = "1.0.0"
    private const val EXPECTED_RAM_SAVER_VERSION = "0.3.1-amethyst.3"

    class ModManifestInfo(
        @JvmField val modId: String,
        @JvmField val normalizedModId: String,
        @JvmField val name: String,
        @JvmField val version: String,
        @JvmField val description: String,
        dependencies: List<String>?
    ) {
        @JvmField
        val dependencies: List<String> = dependencies ?: ArrayList()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun validateMtsJar(jarFile: File?) {
        if (jarFile == null || !jarFile.isFile) {
            throw IOException("ModTheSpire.jar not found")
        }
        ZipFile(jarFile).use { zipFile ->
            val loader = zipFile.getEntry("com/evacipated/cardcrawl/modthespire/Loader.class")
                ?: throw IOException("Invalid ModTheSpire.jar: missing Loader class")
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun validateBaseModJar(jarFile: File?) {
        if (jarFile == null || !jarFile.isFile) {
            throw IOException("BaseMod.jar not found")
        }
        ZipFile(jarFile).use { zipFile ->
            if (zipFile.getEntry("basemod/BaseMod.class") == null) {
                throw IOException("Invalid BaseMod.jar: missing basemod/BaseMod.class")
            }
        }
        val modId = ModJarManifestParser.normalizeModId(resolveModId(jarFile))
        if (ModManager.MOD_ID_BASEMOD != modId) {
            throw IOException("Invalid BaseMod.jar: modid is $modId")
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun validateStsLibJar(jarFile: File?) {
        if (jarFile == null || !jarFile.isFile) {
            throw IOException("StSLib.jar not found")
        }
        ZipFile(jarFile).use { zipFile ->
            if (zipFile.getEntry(STSLIB_MAIN_CLASS) == null) {
                throw IOException("Invalid StSLib.jar: missing $STSLIB_MAIN_CLASS")
            }
        }
        val modId = ModJarManifestParser.normalizeModId(resolveModId(jarFile))
        if (ModManager.MOD_ID_STSLIB != modId) {
            throw IOException("Invalid StSLib.jar: modid is $modId")
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun validateAmethystRuntimeCompatJar(jarFile: File?) {
        if (jarFile == null || !jarFile.isFile) {
            throw IOException("AmethystRuntimeCompat.jar not found")
        }
        ZipFile(jarFile).use { zipFile ->
            if (zipFile.getEntry("io/stamethyst/compatmod/AmethystRuntimeCompat.class") == null) {
                throw IOException(
                    "Invalid AmethystRuntimeCompat.jar: missing runtime compat entry class"
                )
            }
        }
        val modId = ModJarManifestParser.normalizeModId(resolveModId(jarFile))
        if (ModManager.MOD_ID_AMETHYST_RUNTIME_COMPAT != modId) {
            throw IOException("Invalid AmethystRuntimeCompat.jar: modid is $modId")
        }
        val version = readModManifest(jarFile).version.trim()
        if (version != EXPECTED_AMETHYST_RUNTIME_COMPAT_VERSION) {
            throw IOException(
                "Invalid AmethystRuntimeCompat.jar: version is $version, " +
                    "expected $EXPECTED_AMETHYST_RUNTIME_COMPAT_VERSION"
            )
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun validateRamSaverJar(jarFile: File?) {
        if (jarFile == null || !jarFile.isFile) {
            throw IOException("RamSaver.jar not found")
        }
        ZipFile(jarFile).use { zipFile ->
            if (zipFile.getEntry("optispire/RamSaver.class") == null) {
                throw IOException("Invalid RamSaver.jar: missing Ram Saver entry class")
            }
            if (zipFile.getEntry("com/badlogic/gdx/graphics/Texture.class") == null) {
                throw IOException("Invalid RamSaver.jar: missing texture replacement class")
            }
        }
        val modId = ModJarManifestParser.normalizeModId(resolveModId(jarFile))
        if (ModManager.MOD_ID_RAM_SAVER != modId) {
            throw IOException("Invalid RamSaver.jar: modid is $modId")
        }
        val version = readModManifest(jarFile).version.trim()
        if (version != EXPECTED_RAM_SAVER_VERSION) {
            throw IOException(
                "Invalid RamSaver.jar: version is $version, " +
                    "expected $EXPECTED_RAM_SAVER_VERSION"
            )
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun validateAmethystFloatingToolsJar(jarFile: File?) {
        if (jarFile == null || !jarFile.isFile) {
            throw IOException("AmethystFloatingTools.jar not found")
        }
        ZipFile(jarFile).use { zipFile ->
            if (zipFile.getEntry("io/stamethyst/floatingtools/AmethystFloatingTools.class") == null) {
                throw IOException(
                    "Invalid AmethystFloatingTools.jar: missing floating tools entry class"
                )
            }
        }
        val modId = ModJarManifestParser.normalizeModId(resolveModId(jarFile))
        if (ModManager.MOD_ID_AMETHYST_FLOATING_TOOLS != modId) {
            throw IOException("Invalid AmethystFloatingTools.jar: modid is $modId")
        }
        val version = readModManifest(jarFile).version.trim()
        if (version != EXPECTED_AMETHYST_FLOATING_TOOLS_VERSION) {
            throw IOException(
                "Invalid AmethystFloatingTools.jar: version is $version, " +
                    "expected $EXPECTED_AMETHYST_FLOATING_TOOLS_VERSION"
            )
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readModManifest(modJar: File?): ModManifestInfo {
        return ModJarManifestParser.readModManifest(modJar)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun resolveModId(modJar: File?): String {
        return ModJarManifestParser.resolveModId(modJar)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun prepareMtsClasspath(context: Context) {
        prepareMtsClasspath(context, null)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun prepareMtsClasspath(context: Context, progressCallback: StartupProgressCallback?) {
        prepareMtsClasspath(context, progressCallback, null)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun prepareMtsClasspath(
        context: Context,
        progressCallback: StartupProgressCallback?,
        launchSnapshot: ModManager.LaunchModSnapshot?
    ) {
        val stsJar = RuntimePaths.importedStsJar(context)
        val patchJar = RuntimePaths.gdxPatchJar(context)
        val baseModJar = RuntimePaths.importedBaseModJar(context)
        ModCompatibilityDiagnostics.appendCompatLog(context, "prepare classpath start")
        ensurePatchedStsJarReadyForClasspath(
            context = context,
            stsJar = stsJar,
            patchJar = patchJar,
            progressCallback = progressCallback
        )
        reportProgress(
            progressCallback,
            18,
            context.progressText(R.string.startup_progress_applying_compatibility_patches)
        )
        ModCompatibilityPatchCoordinator.applyCompatPatchRules(context, launchSnapshot)
        ModClasspathJarBuilder.ensureGdxApiJar(
            context = context,
            stsJar = stsJar,
            targetJar = RuntimePaths.mtsGdxApiJar(context),
            progressCallback = buildRangeProgressCallback(progressCallback, 24, 36)
        )
        ModClasspathJarBuilder.ensureStsResourceJar(
            context = context,
            stsJar = stsJar,
            targetJar = RuntimePaths.mtsStsResourcesJar(context),
            progressCallback = buildRangeProgressCallback(progressCallback, 37, 84)
        )
        ModClasspathJarBuilder.ensureBaseModResourceJar(
            context = context,
            baseModJar = baseModJar,
            targetJar = RuntimePaths.mtsBaseModResourcesJar(context),
            progressCallback = buildRangeProgressCallback(progressCallback, 85, 96)
        )
        ModCompatibilityDiagnostics.appendCompatLog(context, "prepare classpath done")
        reportProgress(
            progressCallback,
            100,
            context.progressText(R.string.startup_progress_mts_classpath_cache_ready)
        )
    }

    @Throws(IOException::class)
    private fun ensurePatchedStsJarReadyForClasspath(
        context: Context,
        stsJar: File,
        patchJar: File,
        progressCallback: StartupProgressCallback?
    ) {
        val patchProgress = buildRangeProgressCallback(progressCallback, 0, 17)
        if (StsDesktopJarPatcher.isPatchedWithCurrentPatch(stsJar, patchJar)) {
            reportProgress(
                progressCallback,
                17,
                context.progressText(R.string.startup_progress_patched_desktop_jar_ready)
            )
            return
        }
        if (!AppProcess.isDefaultProcess(context)) {
            throw IOException(
                "desktop-1.0.jar requires main-process patching before MTS classpath preparation"
            )
        }
        StsDesktopJarPatcher.ensurePatchedStsJar(
            context = context,
            stsJar = stsJar,
            patchJar = patchJar,
            progressCallback = patchProgress
        )
    }

    @JvmStatic
    fun appendCompatDiagnosticSnapshot(context: Context, stage: String) {
        ModCompatibilityDiagnostics.appendCompatDiagnostics(context, stage)
    }

    private fun buildRangeProgressCallback(
        callback: StartupProgressCallback?,
        startPercent: Int,
        endPercent: Int
    ): ModClasspathJarBuilder.BuildProgressCallback? {
        if (callback == null) {
            return null
        }
        val safeStart = startPercent.coerceIn(0, 100)
        val safeEnd = endPercent.coerceIn(0, 100)
        return ModClasspathJarBuilder.BuildProgressCallback { percent, message ->
            val bounded = percent.coerceIn(0, 100)
            val mapped = safeStart + (((safeEnd - safeStart) * bounded) / 100f).toInt()
            callback.onProgress(mapped.coerceIn(0, 100), message)
        }
    }

    private fun reportProgress(
        callback: StartupProgressCallback?,
        percent: Int,
        message: String
    ) {
        callback?.onProgress(percent.coerceIn(0, 100), message)
    }
}

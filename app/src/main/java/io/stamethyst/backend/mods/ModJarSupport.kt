package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.R
import io.stamethyst.backend.launch.StartupProgressCallback
import io.stamethyst.backend.launch.progressText
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import java.util.ArrayList
import java.util.zip.ZipFile

object ModJarSupport {
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
        val stsJar = RuntimePaths.importedStsJar(context)
        val patchJar = RuntimePaths.gdxPatchJar(context)
        val baseModJar = RuntimePaths.importedBaseModJar(context)
        ModCompatibilityDiagnostics.appendCompatLog(context, "prepare classpath start")
        StsDesktopJarPatcher.ensurePatchedStsJar(
            context = context,
            stsJar = stsJar,
            patchJar = patchJar,
            progressCallback = buildRangeProgressCallback(progressCallback, 0, 17)
        )
        reportProgress(
            progressCallback,
            18,
            context.progressText(R.string.startup_progress_applying_compatibility_patches)
        )
        ModCompatibilityPatchCoordinator.applyCompatPatchRules(context)
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

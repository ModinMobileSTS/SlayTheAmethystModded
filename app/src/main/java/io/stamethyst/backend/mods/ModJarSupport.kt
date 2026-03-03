package io.stamethyst.backend.mods

import android.content.Context
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
        val stsJar = RuntimePaths.importedStsJar(context)
        val patchJar = RuntimePaths.gdxPatchJar(context)
        val baseModJar = RuntimePaths.importedBaseModJar(context)
        ModCompatibilityDiagnosticsLogger.appendCompatLog(context, "prepare classpath start")
        StsDesktopJarPatcher.ensurePatchedStsJar(stsJar, patchJar)
        ModCompatibilityPatchCoordinator.applyCompatPatchRules(context)
        ModClasspathJarBuilder.ensureGdxApiJar(stsJar, RuntimePaths.mtsGdxApiJar(context))
        ModClasspathJarBuilder.ensureStsResourceJar(stsJar, RuntimePaths.mtsStsResourcesJar(context))
        ModClasspathJarBuilder.ensureBaseModResourceJar(baseModJar, RuntimePaths.mtsBaseModResourcesJar(context))
        ModCompatibilityDiagnosticsLogger.appendCompatLog(context, "prepare classpath done")
    }

    @JvmStatic
    fun appendCompatDiagnosticSnapshot(context: Context, stage: String) {
        ModCompatibilityDiagnosticsLogger.appendCompatDiagnostics(context, stage)
    }
}

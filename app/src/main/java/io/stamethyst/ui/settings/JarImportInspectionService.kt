package io.stamethyst.ui.settings

import android.app.Activity
import android.content.Context
import android.net.Uri
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.DuplicateZipEntryNormalizer
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.ModManifestRootCompatPatcher
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile

internal data class ModJarInspection(
    val displayName: String,
    val manifest: ModJarSupport.ModManifestInfo? = null,
    val normalizedModId: String = "",
    val reservedComponent: String? = null,
    val parseError: String? = null,
    val patchedManifestRootEntries: Int = 0,
    val patchedManifestRootPrefix: String = ""
)

internal object JarImportInspectionService {
    internal const val RESERVED_COMPONENT_BASEMOD = "BaseMod"
    internal const val RESERVED_COMPONENT_STSLIB = "StSLib"
    internal const val RESERVED_COMPONENT_MTS = "ModTheSpire"
    internal const val RESERVED_COMPONENT_AMETHYST_RUNTIME_COMPAT = "Amethyst Runtime Compat"
    private const val MTS_LOADER_ENTRY = "com/evacipated/cardcrawl/modthespire/Loader.class"

    @Throws(IOException::class)
    fun prepareImportedJar(host: Activity, uri: Uri, tempFile: File): String {
        val displayName = SettingsFileService.resolveDisplayName(host, uri)
        SettingsFileService.copyUriToFile(host, uri, tempFile)
        DuplicateZipEntryNormalizer.normalizeInPlaceIfNeeded(tempFile)
        return displayName
    }

    fun inspectPreparedModJar(
        context: Context,
        jarFile: File,
        displayName: String
    ): ModJarInspection {
        return inspectPreparedModJar(
            jarFile = jarFile,
            displayName = displayName,
            manifestRootCompatEnabled = CompatibilitySettings.isModManifestRootCompatEnabled(context)
        )
    }

    internal fun inspectPreparedModJar(
        jarFile: File,
        displayName: String,
        manifestRootCompatEnabled: Boolean
    ): ModJarInspection {
        var patchedManifestRootEntries = 0
        var patchedManifestRootPrefix = ""
        return try {
            if (manifestRootCompatEnabled) {
                val patchResult = ModManifestRootCompatPatcher.patchNestedManifestRootInPlace(jarFile)
                patchedManifestRootEntries = patchResult.patchedFileEntries
                patchedManifestRootPrefix = patchResult.sourceRootPrefix
            }
            val manifest = ModJarSupport.readModManifest(jarFile)
            val normalizedModId = ModManager.normalizeModId(manifest.modId)
            ModJarInspection(
                displayName = displayName,
                manifest = manifest,
                normalizedModId = normalizedModId,
                reservedComponent = resolveReservedComponent(normalizedModId),
                parseError = null,
                patchedManifestRootEntries = patchedManifestRootEntries,
                patchedManifestRootPrefix = patchedManifestRootPrefix
            )
        } catch (error: Throwable) {
            ModJarInspection(
                displayName = displayName,
                manifest = null,
                normalizedModId = "",
                reservedComponent = null,
                parseError = error.message ?: error.javaClass.simpleName,
                patchedManifestRootEntries = patchedManifestRootEntries,
                patchedManifestRootPrefix = patchedManifestRootPrefix
            )
        }
    }

    fun resolveReservedComponent(modId: String): String? {
        return when (ModManager.normalizeModId(modId)) {
            ModManager.MOD_ID_BASEMOD -> RESERVED_COMPONENT_BASEMOD
            ModManager.MOD_ID_STSLIB -> RESERVED_COMPONENT_STSLIB
            "modthespire" -> RESERVED_COMPONENT_MTS
            ModManager.MOD_ID_AMETHYST_RUNTIME_COMPAT -> RESERVED_COMPONENT_AMETHYST_RUNTIME_COMPAT
            else -> null
        }
    }

    fun isLikelyModTheSpireJar(jarFile: File, displayName: String): Boolean {
        if (looksLikeModTheSpireName(displayName)) {
            return true
        }
        return try {
            ZipFile(jarFile).use { zipFile ->
                zipFile.getEntry(MTS_LOADER_ENTRY) != null
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun looksLikeModTheSpireName(displayName: String?): Boolean {
        val normalized = displayName?.trim()?.lowercase(Locale.ROOT) ?: return false
        if (normalized.isEmpty()) {
            return false
        }
        return (normalized == "modthespire.jar")
            || (normalized.endsWith(".jar") && normalized.contains("modthespire"))
    }
}

package io.stamethyst.backend.mods.importing.patches

import android.content.Context
import io.stamethyst.backend.mods.ImportedModPatchInfo
import io.stamethyst.backend.mods.importing.ImportPatchCategory
import io.stamethyst.backend.mods.importing.patches.mods.chaofanmod.ChaofanModImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.downfall.DownfallImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.frieren.FrierenImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.firstperson.FirstPersonViewImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.jacketnoanoko.JacketNoAnoKoImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.ori.OriImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.superfastmode.SuperFastModeImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.vupshion.VupShionImportPatchModule
import io.stamethyst.backend.mods.importing.patches.structure.DuplicateZipEntryPatchModule
import io.stamethyst.backend.mods.importing.patches.structure.ManifestRootPatchModule
import io.stamethyst.backend.mods.importing.patches.texture.AtlasFilterPatchModule
import io.stamethyst.backend.mods.importing.patches.texture.AtlasOfflineDownscalePatchModule

/** A UI-ready view of one registered import patch and its state for an imported mod. */
internal data class ImportPatchModuleState(
    val id: String,
    val displayName: String,
    val summary: String,
    val category: ImportPatchCategory,
    val version: Int,
    val defaultEnabled: Boolean,
    val userConfigurable: Boolean,
    val enabled: Boolean,
    val available: Boolean,
    val appliedVersion: Int?,
    val outdated: Boolean,
)

internal object ImportPatchRegistry {
    private val registrations: List<ImportPatchModule> = listOf(
        DuplicateZipEntryPatchModule,
        ManifestRootPatchModule,
        AtlasFilterPatchModule,
        AtlasOfflineDownscalePatchModule,
        JacketNoAnoKoImportPatchModule,
        FrierenImportPatchModule,
        DownfallImportPatchModule,
        FirstPersonViewImportPatchModule,
        SuperFastModeImportPatchModule,
        VupShionImportPatchModule,
        ChaofanModImportPatchModule,
        OriImportPatchModule
    )

    private val modulesById: Map<String, ImportPatchModule> = registrations.associateBy { it.id }

    fun modules(context: Context): List<ImportPatchModule> {
        return registrations
            .filter { it.isAvailable(context) }
            .sortedBy { it.order }
    }

    fun moduleForId(moduleId: String?): ImportPatchModule? {
        return modulesById[moduleId?.trim().orEmpty()]
    }

    fun currentVersion(moduleId: String?): Int? = moduleForId(moduleId)?.version

    fun isEnabled(context: Context, moduleId: String): Boolean {
        return ImportPatchSettings.isEnabled(context, moduleId) ?: false
    }

    fun setEnabled(context: Context, moduleId: String, enabled: Boolean): Boolean {
        return ImportPatchSettings.setEnabled(context, moduleId, enabled)
    }

    fun moduleStates(
        context: Context,
        patchInfo: ImportedModPatchInfo? = null
    ): List<ImportPatchModuleState> {
        return registrations
            .sortedBy { it.order }
            .map { module ->
                val appliedVersion = patchInfo?.appliedPatchVersion(module.id)
                ImportPatchModuleState(
                    id = module.id,
                    displayName = context.getString(module.displayNameResId),
                    summary = context.getString(module.summaryResId),
                    category = module.category,
                    version = module.version,
                    defaultEnabled = module.defaultEnabled,
                    userConfigurable = module.userConfigurable,
                    enabled = ImportPatchSettings.isEnabled(context, module),
                    available = module.isAvailable(context),
                    appliedVersion = appliedVersion,
                    outdated = appliedVersion != null && appliedVersion < module.version,
                )
            }
    }

    fun moduleState(
        context: Context,
        moduleId: String,
        patchInfo: ImportedModPatchInfo? = null
    ): ImportPatchModuleState? {
        return moduleStates(context, patchInfo).firstOrNull { it.id == moduleId }
    }

    fun hasOutdatedAppliedPatches(patchInfo: ImportedModPatchInfo): Boolean {
        return patchInfo.appliedPatches.any { record ->
            val currentVersion = currentVersion(record.moduleId) ?: return@any false
            record.version < currentVersion
        }
    }
}

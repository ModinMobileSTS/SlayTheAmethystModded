package io.stamethyst.backend.mods.importing.patches

import android.content.Context
import io.stamethyst.R
import io.stamethyst.backend.mods.AtlasOfflineDownscaleStrategy
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.DownfallImportCompatPatcher
import io.stamethyst.backend.mods.DuplicateZipEntryNormalizer
import io.stamethyst.backend.mods.FrierenModCompatPatcher
import io.stamethyst.backend.mods.JacketNoAnoKoModCompatPatcher
import io.stamethyst.backend.mods.ModAtlasFilterCompatPatcher
import io.stamethyst.backend.mods.ModAtlasOfflineDownscalePatcher
import io.stamethyst.backend.mods.ModManifestRootCompatPatcher
import io.stamethyst.backend.mods.VupShionModCompatPatcher
import io.stamethyst.backend.mods.importing.ImportPatchCategory
import io.stamethyst.backend.mods.importing.ImportPatchFailurePolicy
import io.stamethyst.backend.mods.importing.ImportPatchPlan
import io.stamethyst.backend.mods.importing.ImportPatchResult
import io.stamethyst.backend.mods.importing.ModImportDecisions
import io.stamethyst.backend.mods.importing.ModImportItemPlan
import java.io.File

internal object DuplicateZipEntryPatchModule : ImportPatchModule {
    override val id = "structure.duplicate_zip_entries"
    override val version = 1
    override val displayNameResId = R.string.mod_import_patch_zip_entry_title
    override val summaryResId = R.string.mod_import_patch_zip_entry_summary
    override val category = ImportPatchCategory.Structural
    override val defaultEnabled = true
    override val userConfigurable = false
    override val order = 100
    override val failurePolicy = ImportPatchFailurePolicy.BlockImport

    override fun plan(context: Context, item: ModImportItemPlan): ImportPatchPlan? {
        return basePlan(applicable = true)
    }

    override fun apply(
        context: Context,
        workingJar: File,
        item: ModImportItemPlan,
        plan: ImportPatchPlan,
        decisions: ModImportDecisions
    ): ImportPatchResult {
        val result = DuplicateZipEntryNormalizer.normalizeInPlaceIfNeeded(workingJar)
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.getString(displayNameResId),
            applied = result.changed,
            summary = if (result.changed) {
                context.getString(R.string.mod_import_patch_zip_entry_applied)
            } else {
                context.getString(R.string.mod_import_patch_zip_entry_noop)
            },
            details = listOf(
                context.getString(R.string.mod_import_patch_zip_entry_detail_total, result.totalEntries),
                context.getString(R.string.mod_import_patch_zip_entry_detail_unique, result.uniqueEntries),
                context.getString(R.string.mod_import_patch_zip_entry_detail_removed, result.duplicateEntriesRemoved)
            ),
            metrics = mapOf(
                "totalEntries" to result.totalEntries,
                "uniqueEntries" to result.uniqueEntries,
                "duplicateEntriesRemoved" to result.duplicateEntriesRemoved
            )
        )
    }
}

internal object ManifestRootPatchModule : ImportPatchModule {
    override val id = "structure.manifest_root"
    override val version = 1
    override val displayNameResId = R.string.mod_import_patch_manifest_root_title
    override val summaryResId = R.string.mod_import_patch_manifest_root_summary
    override val category = ImportPatchCategory.Structural
    override val defaultEnabled = true
    override val userConfigurable = false
    override val order = 200
    override val failurePolicy = ImportPatchFailurePolicy.BlockImport

    override fun isAvailable(context: Context): Boolean {
        return CompatibilitySettings.isModManifestRootCompatEnabled(context)
    }

    override fun plan(context: Context, item: ModImportItemPlan): ImportPatchPlan? {
        val details = item.patchPlans.firstOrNull { it.moduleId == id }?.details.orEmpty()
        return basePlan(applicable = true, details = details)
    }

    override fun apply(
        context: Context,
        workingJar: File,
        item: ModImportItemPlan,
        plan: ImportPatchPlan,
        decisions: ModImportDecisions
    ): ImportPatchResult {
        val result = ModManifestRootCompatPatcher.patchNestedManifestRootInPlace(workingJar)
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.getString(displayNameResId),
            applied = result.hasPatchedChanges,
            summary = if (result.hasPatchedChanges) {
                context.getString(R.string.mod_import_patch_manifest_root_applied)
            } else {
                context.getString(R.string.mod_import_patch_manifest_root_noop)
            },
            details = listOf(
                context.getString(R.string.mod_import_patch_manifest_root_detail_moved, result.patchedFileEntries),
                context.getString(
                    R.string.mod_import_patch_manifest_root_detail_prefix,
                    result.sourceRootPrefix.ifBlank {
                        context.getString(R.string.mod_import_patch_manifest_root_prefix_none)
                    }
                )
            ),
            metrics = mapOf(
                "patchedFileEntries" to result.patchedFileEntries
            ),
            attributes = mapOf(
                "sourceRootPrefix" to result.sourceRootPrefix
            )
        )
    }
}

internal object AtlasFilterPatchModule : ImportPatchModule {
    override val id = "texture.atlas_filter"
    override val version = 1
    override val displayNameResId = R.string.mod_import_patch_atlas_filter_title
    override val summaryResId = R.string.mod_import_patch_atlas_filter_summary
    override val category = ImportPatchCategory.Texture
    override val defaultEnabled = true
    override val userConfigurable = false
    override val order = 300
    override val failurePolicy = ImportPatchFailurePolicy.BlockImport

    override fun isAvailable(context: Context): Boolean {
        return CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(context)
    }

    override fun plan(context: Context, item: ModImportItemPlan): ImportPatchPlan? {
        return basePlan(applicable = true)
    }

    override fun apply(
        context: Context,
        workingJar: File,
        item: ModImportItemPlan,
        plan: ImportPatchPlan,
        decisions: ModImportDecisions
    ): ImportPatchResult {
        val result = ModAtlasFilterCompatPatcher.patchMipMapFiltersInPlace(workingJar)
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.getString(displayNameResId),
            applied = result.hasPatchedChanges,
            summary = if (result.hasPatchedChanges) {
                context.getString(R.string.mod_import_patch_atlas_filter_applied)
            } else {
                context.getString(R.string.mod_import_patch_atlas_filter_noop)
            },
            details = listOf(
                context.getString(R.string.mod_import_patch_atlas_filter_detail_files, result.patchedAtlasEntries),
                context.getString(R.string.mod_import_patch_atlas_filter_detail_lines, result.patchedFilterLines)
            ),
            metrics = mapOf(
                "patchedAtlasEntries" to result.patchedAtlasEntries,
                "patchedFilterLines" to result.patchedFilterLines
            )
        )
    }
}

internal object AtlasOfflineDownscalePatchModule : ImportPatchModule {
    override val id = "texture.atlas_offline_downscale"
    override val version = 1
    override val displayNameResId = R.string.mod_import_patch_atlas_downscale_title
    override val summaryResId = R.string.mod_import_patch_atlas_downscale_summary
    override val category = ImportPatchCategory.Texture
    override val defaultEnabled = true
    override val userConfigurable = true
    override val order = 400
    override val failurePolicy = ImportPatchFailurePolicy.SkipPatchContinueImport

    override fun plan(context: Context, item: ModImportItemPlan): ImportPatchPlan? {
        val result = ModAtlasOfflineDownscalePatcher.inspectOversizedAtlasPages(
            item.source.file,
            AtlasOfflineDownscaleStrategy.previewCandidates()
        )
        if (!result.hasPatchedChanges) {
            return null
        }
        return basePlan(
            applicable = true,
            details = listOf(
                context.getString(R.string.mod_import_patch_atlas_downscale_candidate_files, result.patchedAtlasEntries),
                context.getString(R.string.mod_import_patch_atlas_downscale_candidate_pages, result.downscaledPageEntries),
                context.getString(
                    R.string.mod_import_patch_atlas_downscale_detail_saved_memory,
                    formatRuntimeMemorySaved(result.estimatedRuntimeBytesSaved)
                )
            )
        )
    }

    override fun apply(
        context: Context,
        workingJar: File,
        item: ModImportItemPlan,
        plan: ImportPatchPlan,
        decisions: ModImportDecisions
    ): ImportPatchResult {
        val strategy = decisions.atlasDownscaleStrategy
            ?: AtlasOfflineDownscaleStrategy.maxEdge(AtlasOfflineDownscaleStrategy.DEFAULT_MAX_EDGE_PX)
        val result = ModAtlasOfflineDownscalePatcher.patchOversizedAtlasPagesInPlace(workingJar, strategy)
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.getString(displayNameResId),
            applied = result.hasPatchedChanges,
            summary = if (result.hasPatchedChanges) {
                context.getString(R.string.mod_import_patch_atlas_downscale_applied)
            } else {
                context.getString(R.string.mod_import_patch_atlas_downscale_noop)
            },
            details = listOf(
                context.getString(R.string.mod_import_patch_atlas_downscale_detail_files, result.patchedAtlasEntries),
                context.getString(R.string.mod_import_patch_atlas_downscale_detail_pages, result.downscaledPageEntries),
                context.getString(R.string.mod_import_patch_atlas_downscale_detail_strategy, strategy.mode.name, strategy.value),
                context.getString(
                    R.string.mod_import_patch_atlas_downscale_detail_saved_memory,
                    formatRuntimeMemorySaved(result.estimatedRuntimeBytesSaved)
                )
            ),
            metrics = mapOf(
                "patchedAtlasEntries" to result.patchedAtlasEntries,
                "downscaledPageEntries" to result.downscaledPageEntries,
                "estimatedRuntimeBytesSavedMb" to bytesToWholeMegabytes(result.estimatedRuntimeBytesSaved)
            )
        )
    }

    private fun formatRuntimeMemorySaved(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 10.0) {
            String.format(java.util.Locale.US, "%.0f MB", mb)
        } else {
            String.format(java.util.Locale.US, "%.1f MB", mb)
        }
    }

    private fun bytesToWholeMegabytes(bytes: Long): Int {
        if (bytes <= 0L) return 0
        return (bytes / (1024L * 1024L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

internal object FrierenImportPatchModule : ImportPatchModule {
    private const val TARGET_MOD_ID = "frierenmod"
    override val id = "mod.frieren.anti_pirate"
    override val version = 1
    override val displayNameResId = R.string.mod_import_patch_frieren_title
    override val summaryResId = R.string.mod_import_patch_frieren_summary
    override val category = ImportPatchCategory.ModSpecific
    override val defaultEnabled = true
    override val userConfigurable = false
    override val order = 610
    override val failurePolicy = ImportPatchFailurePolicy.SkipPatchContinueImport

    override fun isAvailable(context: Context): Boolean {
        return CompatibilitySettings.isFrierenModCompatEnabled(context)
    }

    override fun plan(context: Context, item: ModImportItemPlan): ImportPatchPlan? {
        if (item.normalizedModId != TARGET_MOD_ID) return null
        return basePlan(applicable = true)
    }

    override fun apply(
        context: Context,
        workingJar: File,
        item: ModImportItemPlan,
        plan: ImportPatchPlan,
        decisions: ModImportDecisions
    ): ImportPatchResult {
        val result = FrierenModCompatPatcher.patchAntiPirateInPlace(workingJar)
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.getString(displayNameResId),
            applied = result.patchedAntiPirateMethod,
            summary = if (result.patchedAntiPirateMethod) {
                context.getString(R.string.mod_import_patch_frieren_applied)
            } else {
                context.getString(R.string.mod_import_patch_frieren_noop)
            },
            details = listOf(context.getString(R.string.mod_import_patch_frieren_detail, result.patchedAntiPirateMethod.toString()))
        )
    }
}

internal object DownfallImportPatchModule : ImportPatchModule {
    private const val TARGET_MOD_ID = "downfall"
    override val id = "mod.downfall.mobile_layout"
    override val version = 1
    override val displayNameResId = R.string.mod_import_patch_downfall_title
    override val summaryResId = R.string.mod_import_patch_downfall_summary
    override val category = ImportPatchCategory.ModSpecific
    override val defaultEnabled = true
    override val userConfigurable = false
    override val order = 620
    override val failurePolicy = ImportPatchFailurePolicy.SkipPatchContinueImport

    override fun isAvailable(context: Context): Boolean {
        return CompatibilitySettings.isDownfallImportCompatEnabled(context)
    }

    override fun plan(context: Context, item: ModImportItemPlan): ImportPatchPlan? {
        if (item.normalizedModId != TARGET_MOD_ID) return null
        return basePlan(applicable = true)
    }

    override fun apply(
        context: Context,
        workingJar: File,
        item: ModImportItemPlan,
        plan: ImportPatchPlan,
        decisions: ModImportDecisions
    ): ImportPatchResult {
        val result = DownfallImportCompatPatcher.patchInPlace(workingJar)
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.getString(displayNameResId),
            applied = result.patchedClassEntries > 0,
            summary = if (result.patchedClassEntries > 0) {
                context.getString(R.string.mod_import_patch_downfall_applied)
            } else {
                context.getString(R.string.mod_import_patch_downfall_noop)
            },
            details = listOf(
                context.getString(R.string.mod_import_patch_downfall_detail_classes, result.patchedClassEntries),
                context.getString(R.string.mod_import_patch_downfall_detail_merchant, result.patchedMerchantClassEntries),
                context.getString(R.string.mod_import_patch_downfall_detail_hexaghost, result.patchedHexaghostBodyClassEntries),
                context.getString(R.string.mod_import_patch_downfall_detail_boss_panel, result.patchedBossMechanicPanelClassEntries)
            ),
            metrics = mapOf(
                "patchedClassEntries" to result.patchedClassEntries,
                "patchedMerchantClassEntries" to result.patchedMerchantClassEntries,
                "patchedHexaghostBodyClassEntries" to result.patchedHexaghostBodyClassEntries,
                "patchedBossMechanicPanelClassEntries" to result.patchedBossMechanicPanelClassEntries
            )
        )
    }
}

internal object VupShionImportPatchModule : ImportPatchModule {
    private const val TARGET_MOD_ID = "vupshionmod"
    override val id = "mod.vupshion.startup_compat"
    override val version = 1
    override val displayNameResId = R.string.mod_import_patch_vupshion_title
    override val summaryResId = R.string.mod_import_patch_vupshion_summary
    override val category = ImportPatchCategory.ModSpecific
    override val defaultEnabled = true
    override val userConfigurable = false
    override val order = 630
    override val failurePolicy = ImportPatchFailurePolicy.SkipPatchContinueImport

    override fun isAvailable(context: Context): Boolean {
        return CompatibilitySettings.isVupShionModCompatEnabled(context)
    }

    override fun plan(context: Context, item: ModImportItemPlan): ImportPatchPlan? {
        if (item.normalizedModId != TARGET_MOD_ID) return null
        return basePlan(applicable = true)
    }

    override fun apply(
        context: Context,
        workingJar: File,
        item: ModImportItemPlan,
        plan: ImportPatchPlan,
        decisions: ModImportDecisions
    ): ImportPatchResult {
        val result = VupShionModCompatPatcher.patchInPlace(workingJar)
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.getString(displayNameResId),
            applied = result.hasAnyPatch,
            summary = if (result.hasAnyPatch) {
                context.getString(R.string.mod_import_patch_vupshion_applied)
            } else {
                context.getString(R.string.mod_import_patch_vupshion_noop)
            },
            details = listOf(context.getString(R.string.mod_import_patch_vupshion_detail, result.hasAnyPatch.toString()))
        )
    }
}

internal object JacketNoAnoKoImportPatchModule : ImportPatchModule {
    private const val TARGET_MOD_ID = "jacketnoanokomod"
    override val id = "mod.jacketnoanoko.shader"
    override val version = 1
    override val displayNameResId = R.string.mod_import_patch_jacketnoanoko_title
    override val summaryResId = R.string.mod_import_patch_jacketnoanoko_summary
    override val category = ImportPatchCategory.Shader
    override val defaultEnabled = false
    override val userConfigurable = false
    override val order = 520
    override val failurePolicy = ImportPatchFailurePolicy.SkipPatchContinueImport

    override fun isAvailable(context: Context): Boolean {
        return CompatibilitySettings.isJacketNoAnoKoModCompatEnabled(context)
    }

    override fun plan(context: Context, item: ModImportItemPlan): ImportPatchPlan? {
        if (item.normalizedModId != TARGET_MOD_ID) return null
        return basePlan(applicable = true)
    }

    override fun apply(
        context: Context,
        workingJar: File,
        item: ModImportItemPlan,
        plan: ImportPatchPlan,
        decisions: ModImportDecisions
    ): ImportPatchResult {
        val result = JacketNoAnoKoModCompatPatcher.patchInPlace(workingJar)
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.getString(displayNameResId),
            applied = result.hasAnyPatch,
            summary = if (result.hasAnyPatch) {
                context.getString(R.string.mod_import_patch_jacketnoanoko_applied)
            } else {
                context.getString(R.string.mod_import_patch_jacketnoanoko_noop)
            },
            details = listOf(
                context.getString(R.string.mod_import_patch_jacketnoanoko_detail_shader_files, result.patchedShaderEntries),
                context.getString(R.string.mod_import_patch_jacketnoanoko_detail_version_directives, result.removedDesktopVersionDirectives),
                context.getString(R.string.mod_import_patch_jacketnoanoko_detail_precision_blocks, result.insertedFragmentPrecisionBlocks)
            ),
            metrics = mapOf(
                "patchedShaderEntries" to result.patchedShaderEntries,
                "removedDesktopVersionDirectives" to result.removedDesktopVersionDirectives,
                "insertedFragmentPrecisionBlocks" to result.insertedFragmentPrecisionBlocks
            )
        )
    }
}

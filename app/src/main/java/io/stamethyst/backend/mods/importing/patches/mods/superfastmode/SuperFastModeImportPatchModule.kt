package io.stamethyst.backend.mods.importing.patches.mods.superfastmode

import android.content.Context
import io.stamethyst.R
import io.stamethyst.backend.mods.importing.ImportPatchCategory
import io.stamethyst.backend.mods.importing.ImportPatchFailurePolicy
import io.stamethyst.backend.mods.importing.ImportPatchPlan
import io.stamethyst.backend.mods.importing.ImportPatchResult
import io.stamethyst.backend.mods.importing.ModImportDecisions
import io.stamethyst.backend.mods.importing.ModImportItemPlan
import io.stamethyst.backend.mods.importing.importString
import io.stamethyst.backend.mods.importing.patches.ImportPatchModule
import java.io.File

internal object SuperFastModeImportPatchModule : ImportPatchModule {
    override val id = "mod.superfastmode.boss_relic_selection"
    override val version = 2
    override val displayNameResId = R.string.mod_import_patch_superfastmode_title
    override val summaryResId = R.string.mod_import_patch_superfastmode_summary
    override val category = ImportPatchCategory.ModSpecific
    override val defaultEnabled = true
    override val userConfigurable = true
    override val order = 630
    override val failurePolicy = ImportPatchFailurePolicy.SkipPatchContinueImport

    override fun plan(context: Context, item: ModImportItemPlan): ImportPatchPlan? {
        if (item.normalizedModId != "superfastmode") return null
        return basePlan(applicable = true)
    }

    override fun apply(
        context: Context,
        workingJar: File,
        item: ModImportItemPlan,
        plan: ImportPatchPlan,
        decisions: ModImportDecisions
    ): ImportPatchResult {
        val applied = SuperFastModeImportCompatPatcher.patchInPlace(workingJar)
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.importString(displayNameResId),
            applied = applied,
            summary = context.importString(
                if (applied) R.string.mod_import_patch_superfastmode_applied
                else R.string.mod_import_patch_superfastmode_noop
            ),
            details = emptyList(),
            metrics = mapOf("patchedClassEntries" to if (applied) 1 else 0)
        )
    }
}

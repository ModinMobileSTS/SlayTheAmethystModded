package io.stamethyst.backend.mods.importing

import android.content.Context
import io.stamethyst.R
import io.stamethyst.backend.mods.ImportedModPatchInfo
import io.stamethyst.backend.mods.ImportedModPatchRegistry
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.MtsLaunchManifestValidator
import io.stamethyst.backend.mods.OptionalModStorageCoordinator
import io.stamethyst.backend.mods.importing.patches.AtlasFilterPatchModule
import io.stamethyst.backend.mods.importing.patches.AtlasOfflineDownscalePatchModule
import io.stamethyst.backend.mods.importing.patches.DownfallImportPatchModule
import io.stamethyst.backend.mods.importing.patches.DuplicateZipEntryPatchModule
import io.stamethyst.backend.mods.importing.patches.FrierenImportPatchModule
import io.stamethyst.backend.mods.importing.patches.ImportPatchModule
import io.stamethyst.backend.mods.importing.patches.ImportPatchRegistry
import io.stamethyst.backend.mods.importing.patches.JacketNoAnoKoImportPatchModule
import io.stamethyst.backend.mods.importing.patches.ManifestRootPatchModule
import io.stamethyst.backend.mods.importing.patches.VupShionImportPatchModule
import io.stamethyst.ui.main.MainFolderStateStore
import io.stamethyst.ui.main.NewlyImportedModHighlightStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

internal object ModImportExecutor {
    fun execute(
        context: Context,
        plan: ModImportPlan,
        decisions: ModImportDecisions,
        onProgress: (ModImportExecutionProgress) -> Unit = {}
    ): ModImportExecutionReport {
        OptionalModStorageCoordinator.ensureOptionalModLibraryReady(context)
        val modulesById = ImportPatchRegistry.modules(context).associateBy { it.id }
        val executableItems = plan.importableItems.filter { item ->
            val conflictKey = item.duplicateConflictKey
            conflictKey == null || decisions.duplicateDecisionFor(conflictKey) != DuplicateImportDecision.SkipNew
        }
        val results = ArrayList<ModImportExecutionItemResult>()
        plan.skippedItems.forEach { item ->
            results.add(
                ModImportExecutionItemResult(
                    itemId = item.id,
                    displayName = item.source.displayName,
                    modId = item.normalizedModId,
                    modName = item.displayModName,
                    skipped = true,
                    message = item.blockingDetail
                )
            )
        }
        plan.blockedItems.forEach { item ->
            results.add(
                ModImportExecutionItemResult(
                    itemId = item.id,
                    displayName = item.source.displayName,
                    modId = item.normalizedModId,
                    modName = item.displayModName,
                    blocked = true,
                    message = item.blockingDetail
                )
            )
        }
        plan.importableItems
            .filter { item ->
                val conflictKey = item.duplicateConflictKey
                conflictKey != null && decisions.duplicateDecisionFor(conflictKey) == DuplicateImportDecision.SkipNew
            }
            .forEach { item ->
                results.add(
                    ModImportExecutionItemResult(
                        itemId = item.id,
                        displayName = item.source.displayName,
                        modId = item.normalizedModId,
                        modName = item.displayModName,
                        skipped = true,
                        message = context.getString(R.string.mod_import_result_skipped_duplicate_decision)
                    )
                )
            }

        executableItems.forEachIndexed { index, item ->
            onProgress(
                ModImportExecutionProgress(
                    currentIndex = index,
                    total = executableItems.size,
                    currentFileName = item.source.displayName,
                    message = context.getString(R.string.mod_import_progress_importing, item.source.displayName)
                )
            )
            results.add(executeItem(context, item, plan, decisions, modulesById))
        }
        onProgress(
            ModImportExecutionProgress(
                currentIndex = executableItems.size,
                total = executableItems.size,
                currentFileName = "",
                message = context.getString(R.string.mod_import_progress_complete)
            )
        )
        val report = ModImportExecutionReport(results)
        NewlyImportedModHighlightStore.mark(report.importedResults.mapNotNull { it.storagePath })
        return report
    }

    private fun executeItem(
        context: Context,
        item: ModImportItemPlan,
        plan: ModImportPlan,
        decisions: ModImportDecisions,
        modulesById: Map<String, ImportPatchModule>
    ): ModImportExecutionItemResult {
        val workingJar = File(plan.session.sessionDir, "working-${item.source.index}.jar")
        return try {
            copyFile(item.source.file, workingJar)
            val patchResults = ArrayList<ImportPatchResult>()
            for (patchPlan in item.patchPlans) {
                if (!decisions.isPatchEnabled(item.id, patchPlan)) {
                    continue
                }
                val module = modulesById[patchPlan.moduleId] ?: continue
                try {
                    val result = module.apply(
                        context = context,
                        workingJar = workingJar,
                        item = item,
                        plan = patchPlan,
                        decisions = decisions
                    )
                    patchResults.add(result)
                } catch (error: Throwable) {
                    if (patchPlan.failurePolicy == ImportPatchFailurePolicy.BlockImport) {
                        throw error
                    }
                    patchResults.add(
                        ImportPatchResult(
                            moduleId = patchPlan.moduleId,
                            moduleVersion = patchPlan.moduleVersion,
                            displayNameResId = patchPlan.displayNameResId,
                            summaryResId = patchPlan.summaryResId,
                            displayName = patchPlan.displayName,
                            applied = false,
                            summary = context.getString(R.string.mod_import_patch_failed_skipped),
                            details = listOf(error.message ?: error.javaClass.simpleName)
                        )
                    )
                }
            }
            val finalLaunchModId = MtsLaunchManifestValidator.resolveLaunchModId(workingJar).trim()
            val replaceExisting = item.duplicateConflictKey?.let { conflictKey ->
                decisions.duplicateDecisionFor(conflictKey) == DuplicateImportDecision.ReplaceExisting
            } == true
            val reuse = if (replaceExisting) {
                buildReusePlan(plan, item, decisions)
            } else {
                DuplicateReusePlan()
            }
            if (replaceExisting) {
                ModManager.removeExistingOptionalModsForImport(
                    context = context,
                    normalizedModId = item.normalizedModId,
                    launchModId = finalLaunchModId,
                    excludedPath = workingJar.absolutePath
                )
            }
            val targetName = reuse.targetFileName
                ?.takeIf { it.isNotBlank() }
                ?: item.source.displayName.ifBlank { "${item.normalizedModId}.jar" }
            val target = ModManager.resolveStorageFileForImportedMod(context, targetName)
            moveFileReplacing(workingJar, target)
            val targetPath = target.absolutePath
            applyFolderDecision(context, targetPath, decisions.targetFolderId)
            if (!reuse.assignedFolderId.isNullOrBlank()) {
                applyFolderDecision(context, targetPath, reuse.assignedFolderId)
            }
            ImportedModPatchRegistry.put(
                context = context,
                storagePath = targetPath,
                patchInfo = buildLegacyPatchInfo(item, patchResults)
            )
            ModImportExecutionItemResult(
                itemId = item.id,
                displayName = item.source.displayName,
                modId = item.normalizedModId,
                modName = item.displayModName,
                storagePath = targetPath,
                imported = true,
                message = context.getString(R.string.mod_import_execution_imported),
                patchResults = patchResults
            )
        } catch (error: Throwable) {
            if (workingJar.exists()) {
                workingJar.delete()
            }
            ModImportExecutionItemResult(
                itemId = item.id,
                displayName = item.source.displayName,
                modId = item.normalizedModId,
                modName = item.displayModName,
                failed = true,
                message = error.message ?: error.javaClass.simpleName
            )
        }
    }

    private data class DuplicateReusePlan(
        val targetFileName: String? = null,
        val assignedFolderId: String? = null
    )

    private fun buildReusePlan(
        plan: ModImportPlan,
        item: ModImportItemPlan,
        decisions: ModImportDecisions
    ): DuplicateReusePlan {
        val conflict = plan.duplicateConflicts.firstOrNull {
            it.normalizedModId == item.duplicateConflictKey
        } ?: return DuplicateReusePlan()
        val firstExisting = conflict.existingSources
            .sortedWith(compareBy({ it.fileName.lowercase() }, { it.fileName }, { it.storagePath }))
            .firstOrNull()
        return DuplicateReusePlan(
            targetFileName = if (decisions.reusePreviousFileNameOnReplace) firstExisting?.fileName else null,
            assignedFolderId = if (decisions.reusePreviousFolderOnReplace) {
                conflict.existingSources.firstOrNull { !it.assignedFolderId.isNullOrBlank() }?.assignedFolderId
            } else {
                null
            }
        )
    }

    private fun applyFolderDecision(context: Context, storagePath: String, folderId: String?) {
        val activity = context as? android.app.Activity ?: return
        val normalizedFolderId = folderId?.trim().orEmpty()
        if (normalizedFolderId.isEmpty()) {
            return
        }
        val store = MainFolderStateStore().apply { ensureLoaded(activity) }
        if (store.folders.none { it.id == normalizedFolderId }) {
            return
        }
        store.assignments[storagePath] = normalizedFolderId
        store.persist(activity)
    }

    private fun buildLegacyPatchInfo(
        item: ModImportItemPlan,
        patchResults: List<ImportPatchResult>
    ): ImportedModPatchInfo {
        var patchedAtlasEntries = 0
        var patchedFilterLines = 0
        var downscaledAtlasEntries = 0
        var downscaledAtlasPageEntries = 0
        var patchedManifestRootEntries = 0
        var patchedManifestRootPrefix = ""
        var patchedFrieren = false
        var patchedDownfallClassEntries = 0
        var patchedDownfallMerchant = 0
        var patchedDownfallHexaghost = 0
        var patchedDownfallBossPanel = 0
        var patchedVupShion = false
        var patchedJacketShaderEntries = 0
        var patchedJacketVersionDirectives = 0
        var patchedJacketPrecisionBlocks = 0

        patchResults.filter { it.applied }.forEach { result ->
            when (result.moduleId) {
                AtlasFilterPatchModule.id -> {
                    patchedAtlasEntries += result.metrics["patchedAtlasEntries"] ?: 0
                    patchedFilterLines += result.metrics["patchedFilterLines"] ?: 0
                }
                AtlasOfflineDownscalePatchModule.id -> {
                    downscaledAtlasEntries += result.metrics["patchedAtlasEntries"] ?: 0
                    downscaledAtlasPageEntries += result.metrics["downscaledPageEntries"] ?: 0
                }
                ManifestRootPatchModule.id -> {
                    patchedManifestRootEntries += result.metrics["patchedFileEntries"] ?: 0
                    patchedManifestRootPrefix = result.attributes["sourceRootPrefix"].orEmpty()
                }
                FrierenImportPatchModule.id -> patchedFrieren = true
                DownfallImportPatchModule.id -> {
                    patchedDownfallClassEntries += result.metrics["patchedClassEntries"] ?: 0
                    patchedDownfallMerchant += result.metrics["patchedMerchantClassEntries"] ?: 0
                    patchedDownfallHexaghost += result.metrics["patchedHexaghostBodyClassEntries"] ?: 0
                    patchedDownfallBossPanel += result.metrics["patchedBossMechanicPanelClassEntries"] ?: 0
                }
                VupShionImportPatchModule.id -> patchedVupShion = true
                JacketNoAnoKoImportPatchModule.id -> {
                    patchedJacketShaderEntries += result.metrics["patchedShaderEntries"] ?: 0
                    patchedJacketVersionDirectives += result.metrics["removedDesktopVersionDirectives"] ?: 0
                    patchedJacketPrecisionBlocks += result.metrics["insertedFragmentPrecisionBlocks"] ?: 0
                }
            }
        }
        return ImportedModPatchInfo(
            modId = item.normalizedModId,
            modName = item.displayModName,
            patchedAtlasEntries = patchedAtlasEntries,
            patchedFilterLines = patchedFilterLines,
            downscaledAtlasEntries = downscaledAtlasEntries,
            downscaledAtlasPageEntries = downscaledAtlasPageEntries,
            patchedManifestRootEntries = patchedManifestRootEntries,
            patchedManifestRootPrefix = patchedManifestRootPrefix,
            patchedFrierenAntiPirateMethod = patchedFrieren,
            patchedDownfallClassEntries = patchedDownfallClassEntries,
            patchedDownfallMerchantClassEntries = patchedDownfallMerchant,
            patchedDownfallHexaghostBodyClassEntries = patchedDownfallHexaghost,
            patchedDownfallBossMechanicPanelClassEntries = patchedDownfallBossPanel,
            patchedVupShionWebButtonConstructor = patchedVupShion,
            patchedJacketNoAnoKoShaderEntries = patchedJacketShaderEntries,
            patchedJacketNoAnoKoDesktopVersionDirectives = patchedJacketVersionDirectives,
            patchedJacketNoAnoKoFragmentPrecisionBlocks = patchedJacketPrecisionBlocks
        )
    }

    private fun copyFile(source: File, target: File) {
        source.inputStream().use { input ->
            FileOutputStream(target, false).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun moveFileReplacing(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        if (source.renameTo(target)) {
            return
        }
        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        if (!source.delete()) {
            throw IOException("Failed to delete temporary import file: ${source.absolutePath}")
        }
    }
}

package io.stamethyst.backend.mods.importing

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import io.stamethyst.R
import io.stamethyst.backend.mods.ImportedModPatchRecord
import io.stamethyst.backend.mods.ImportedModPatchInfo
import io.stamethyst.backend.mods.ImportedModPatchRegistry
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.ModManifestNameRewriter
import io.stamethyst.backend.mods.MtsLaunchManifestValidator
import io.stamethyst.backend.mods.OptionalModStorageCoordinator
import io.stamethyst.backend.mods.importing.patches.ImportPatchModule
import io.stamethyst.backend.mods.importing.patches.ImportPatchRegistry
import io.stamethyst.backend.mods.importing.patches.mods.chaofanmod.ChaofanModImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.downfall.DownfallImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.frieren.FrierenImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.jacketnoanoko.JacketNoAnoKoImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.ori.OriImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.vupshion.VupShionImportPatchModule
import io.stamethyst.backend.mods.importing.patches.structure.ManifestRootPatchModule
import io.stamethyst.backend.mods.importing.patches.texture.AtlasFilterPatchModule
import io.stamethyst.backend.mods.importing.patches.texture.AtlasOfflineDownscalePatchModule
import io.stamethyst.backend.workshop.WorkshopMetadataStore
import io.stamethyst.ui.main.MainFolderAssignmentHandoffStore
import io.stamethyst.ui.main.MainFolderStateStore
import io.stamethyst.ui.main.ModAliasStore
import io.stamethyst.ui.main.NewlyImportedModHighlightStore
import io.stamethyst.ui.main.resolveModStoragePathCandidates
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.LinkedHashMap

internal object ModImportExecutor {
    private class ProgressReporter(
        val totalSteps: Int,
        private val onProgress: (ModImportExecutionProgress) -> Unit
    ) {
        var completedSteps: Int = 0
            private set

        fun step(item: ModImportItemPlan, message: String) {
            val stepBeforeOperation = completedSteps
            onProgress(
                ModImportExecutionProgress(
                    currentIndex = stepBeforeOperation,
                    total = totalSteps,
                    currentFileName = item.source.displayName,
                    message = message,
                    currentStep = stepBeforeOperation,
                    totalSteps = totalSteps
                )
            )
            completedSteps = (completedSteps + 1).coerceAtMost(totalSteps)
        }
    }

    fun execute(
        context: Context,
        plan: ModImportPlan,
        decisions: ModImportDecisions,
        onPatchEvent: (ModImportPatchExecutionEvent) -> Unit = {},
        onProgress: (ModImportExecutionProgress) -> Unit = {}
    ): ModImportExecutionReport {
        OptionalModStorageCoordinator.ensureOptionalModLibraryReady(context)
        val modulesById = ImportPatchRegistry.modules(context).associateBy { it.id }
        val executableItems = plan.importableItems.filter { item ->
            val conflictKey = item.duplicateConflictKey
            conflictKey == null || decisions.duplicateDecisionFor(conflictKey) != DuplicateImportDecision.SkipNew
        }
        val progress = ProgressReporter(
            totalSteps = executableItems.sumOf { countItemSteps(context, it, decisions, modulesById) },
            onProgress = onProgress
        )
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
                        message = context.importString(R.string.mod_import_result_skipped_duplicate_decision)
                    )
                )
            }

        executableItems.forEach { item ->
            results.add(executeItem(context, item, plan, decisions, modulesById, progress, onPatchEvent))
        }
        onProgress(
            ModImportExecutionProgress(
                currentIndex = executableItems.size,
                total = executableItems.size,
                currentFileName = "",
                message = context.importString(R.string.mod_import_progress_complete),
                currentStep = progress.completedSteps,
                totalSteps = progress.totalSteps
            )
        )
        val report = ModImportExecutionReport(results)
        NewlyImportedModHighlightStore.mark(context, report.importedResults.mapNotNull { it.storagePath })
        return report
    }

    private fun executeItem(
        context: Context,
        item: ModImportItemPlan,
        plan: ModImportPlan,
        decisions: ModImportDecisions,
        modulesById: Map<String, ImportPatchModule>,
        progress: ProgressReporter,
        onPatchEvent: (ModImportPatchExecutionEvent) -> Unit
    ): ModImportExecutionItemResult {
        // A plan may have rewritten a disposable inspection copy. Every real import starts
        // from the untouched source so both manager and per-item switches are authoritative.
        val workingJar = File(plan.session.sessionDir, "working-${item.source.index}.jar")
        var committedTarget: File? = null
        var commitMarker: File? = null
        return try {
            progress.step(
                item = item,
                message = context.importString(R.string.mod_import_progress_prepare_working_copy, item.source.displayName)
            )
            copyFile(item.source.file, workingJar)
            val patchResults = ArrayList<ImportPatchResult>()
            for (patchPlan in item.patchPlans) {
                if (!ImportPatchRegistry.isEnabled(context, patchPlan.moduleId)) {
                    onPatchEvent(
                        ModImportPatchExecutionEvent.Skipped(
                            item = item,
                            patchPlan = patchPlan,
                            reason = ModImportPatchSkipReason.DisabledBySetting
                        )
                    )
                    continue
                }
                if (!decisions.isPatchEnabled(item.id, patchPlan)) {
                    onPatchEvent(
                        ModImportPatchExecutionEvent.Skipped(
                            item = item,
                            patchPlan = patchPlan,
                            reason = ModImportPatchSkipReason.DisabledByDecision
                        )
                    )
                    continue
                }
                val module = modulesById[patchPlan.moduleId]
                if (module == null) {
                    onPatchEvent(
                        ModImportPatchExecutionEvent.Skipped(
                            item = item,
                            patchPlan = patchPlan,
                            reason = ModImportPatchSkipReason.ModuleUnavailable
                        )
                    )
                    continue
                }
                try {
                    progress.step(
                        item = item,
                        message = context.importString(
                            R.string.mod_import_progress_apply_patch,
                            resolvePatchProgressName(context, patchPlan)
                        )
                    )
                    onPatchEvent(
                        ModImportPatchExecutionEvent.Started(
                            item = item,
                            patchPlan = patchPlan
                        )
                    )
                    val result = module.apply(
                        context = context,
                        workingJar = workingJar,
                        item = item,
                        plan = patchPlan,
                        decisions = decisions
                    )
                    patchResults.add(result)
                    onPatchEvent(
                        ModImportPatchExecutionEvent.Succeeded(
                            item = item,
                            patchPlan = patchPlan,
                            result = result
                        )
                    )
                } catch (error: Throwable) {
                    onPatchEvent(
                        ModImportPatchExecutionEvent.Failed(
                            item = item,
                            patchPlan = patchPlan,
                            error = error,
                            importBlocked = patchPlan.failurePolicy == ImportPatchFailurePolicy.BlockImport
                        )
                    )
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
                            summary = context.importString(R.string.mod_import_patch_failed_skipped),
                            details = error.patchFailureDetailsForResult() +
                                fileDiagnostics("patch.working", workingJar) +
                                directoryDiagnostics("patch.session", plan.session.sessionDir)
                        )
                    )
                }
            }
            progress.step(
                item = item,
                message = context.importString(R.string.mod_import_progress_validate_manifest, item.source.displayName)
            )
            val finalLaunchModId = MtsLaunchManifestValidator.resolveLaunchModId(workingJar).trim()
            val replaceExisting = item.duplicateConflictKey?.let { conflictKey ->
                decisions.duplicateDecisionFor(conflictKey) == DuplicateImportDecision.ReplaceExisting
            } == true
            val reuse = if (replaceExisting) {
                buildReusePlan(plan, item, decisions)
            } else {
                DuplicateReusePlan()
            }
            val targetName = resolveImportTargetFileName(item, reuse.targetFileName)
            val target = ModManager.resolveStorageFileForImportedMod(context, targetName)
            progress.step(
                item = item,
                message = context.importString(R.string.mod_import_progress_write_file, target.name)
            )
            OptionalModStorageCoordinator.withImportArtifactLock {
                val marker = importCommitMarker(target)
                commitMarker = marker
                writeImportCommitMarker(marker, target)
                moveFileReplacing(workingJar, target)
            }
            committedTarget = target
            val targetPath = target.absolutePath
            progress.step(
                item = item,
                message = context.importString(R.string.mod_import_progress_update_folder, item.source.displayName)
            )
            val hasExplicitFolderDecision = decisions.hasTargetFolderDecision(item.id)
            val selectedFolderId = decisions.targetFolderIdFor(item.id)
            if (hasExplicitFolderDecision) {
                if (selectedFolderId.isNullOrBlank()) {
                    clearFolderDecision(
                        context = context,
                        storagePath = targetPath,
                        sourceStoragePaths = reuse.sourceStoragePaths,
                        normalizedModId = if (replaceExisting) item.normalizedModId else ""
                    )
                } else {
                    applyFolderDecision(
                        context = context,
                        storagePath = targetPath,
                        folderId = selectedFolderId,
                        sourceStoragePaths = reuse.sourceStoragePaths,
                        normalizedModId = if (replaceExisting) item.normalizedModId else ""
                    )
                }
            } else if (!reuse.assignedFolderId.isNullOrBlank()) {
                applyFolderDecision(
                    context = context,
                    storagePath = targetPath,
                    folderId = reuse.assignedFolderId,
                    sourceStoragePaths = reuse.sourceStoragePaths,
                    normalizedModId = item.normalizedModId
                )
            } else {
                clearFolderDecision(context, targetPath)
            }
            if (!hasExplicitFolderDecision && replaceExisting && decisions.reusePreviousFolderOnReplace) {
                enqueueDuplicateFolderReuseHandoff(
                    context = context,
                    targetPath = targetPath,
                    reuse = reuse,
                    normalizedModId = item.normalizedModId
                )
            }
            progress.step(
                item = item,
                message = context.importString(R.string.mod_import_progress_write_metadata, item.source.displayName)
            )
            ImportedModPatchRegistry.put(
                context = context,
                storagePath = targetPath,
                patchInfo = buildImportedPatchInfo(item, patchResults)
            )
            commitMarker?.delete()
            commitMarker = null
            if (replaceExisting) {
                progress.step(
                    item = item,
                    message = context.importString(R.string.mod_import_progress_replace_existing, item.source.displayName)
                )
                ModManager.removeExistingOptionalModsForImport(
                    context = context,
                    normalizedModId = item.normalizedModId,
                    launchModId = finalLaunchModId,
                    excludedPath = target.absolutePath
                )
                WorkshopMetadataStore(context).removeByLocalJarPaths(reuse.sourceStoragePaths)
                applyDuplicateFileNameAlias(
                    context = context,
                    targetPath = targetPath,
                    reuse = reuse,
                    decisions = decisions
                )
            }
            progress.step(
                item = item,
                message = context.importString(R.string.mod_import_progress_item_complete, item.source.displayName)
            )
            ModImportExecutionItemResult(
                itemId = item.id,
                displayName = item.source.displayName,
                modId = item.normalizedModId,
                modName = item.displayModName,
                storagePath = targetPath,
                imported = true,
                message = context.importString(R.string.mod_import_execution_imported),
                patchResults = patchResults
            )
        } catch (error: Throwable) {
            commitMarker?.let { marker ->
                committedTarget?.delete()
                marker.delete()
            }
            if (workingJar.exists()) {
                workingJar.delete()
            }
            ModImportExecutionItemResult(
                itemId = item.id,
                displayName = item.source.displayName,
                modId = item.normalizedModId,
                modName = item.displayModName,
                failed = true,
                message = error.message ?: error.javaClass.simpleName,
                failureDetails = error.importFailureDetails()
            )
        }
    }

    private fun enqueueDuplicateFolderReuseHandoff(
        context: Context,
        targetPath: String,
        reuse: DuplicateReusePlan,
        normalizedModId: String
    ) {
        if (reuse.sourceStoragePaths.isEmpty() && reuse.assignedFolderId.isNullOrBlank()) {
            return
        }
        MainFolderAssignmentHandoffStore.enqueueDuplicateFolderReuse(
            context = context,
            targetStoragePath = targetPath,
            folderId = reuse.assignedFolderId.orEmpty(),
            sourceStoragePaths = reuse.sourceStoragePaths,
            normalizedModId = normalizedModId
        )
    }

    private fun applyDuplicateFileNameAlias(
        context: Context,
        targetPath: String,
        reuse: DuplicateReusePlan,
        decisions: ModImportDecisions
    ) {
        val existingAliases = ModAliasStore.loadAliases(context)
        val alias = reuse.sourceStoragePaths.firstNotNullOfOrNull { sourcePath ->
            ModAliasStore.resolveAlias(sourcePath, existingAliases).trim().ifEmpty { null }
        }.orEmpty()
        reuse.sourceStoragePaths.forEach { sourcePath ->
            ModAliasStore.setAlias(context, sourcePath, "")
        }
        if (alias.isNotEmpty()) {
            val targetFile = File(targetPath)
            val rewritten = targetFile.isFile &&
                runCatching {
                    ModManifestNameRewriter.rewriteNameInPlace(targetFile, alias)
                }.isSuccess
            if (rewritten) {
                ModAliasStore.setAlias(context, targetPath, "")
            } else {
                ModAliasStore.setAlias(context, targetPath, alias)
            }
        }
    }

    private fun countItemSteps(
        context: Context,
        item: ModImportItemPlan,
        decisions: ModImportDecisions,
        modulesById: Map<String, ImportPatchModule>
    ): Int {
        val enabledPatchSteps = item.patchPlans.count { patchPlan ->
            ImportPatchRegistry.isEnabled(context, patchPlan.moduleId) &&
                decisions.isPatchEnabled(item.id, patchPlan) &&
                modulesById.containsKey(patchPlan.moduleId)
        }
        val replaceExisting = item.duplicateConflictKey?.let { conflictKey ->
            decisions.duplicateDecisionFor(conflictKey) == DuplicateImportDecision.ReplaceExisting
        } == true
        return 1 + enabledPatchSteps + 1 + if (replaceExisting) 1 else 0 + 1 + 1 + 1 + 1
    }

    private fun resolvePatchProgressName(context: Context, patchPlan: ImportPatchPlan): String {
        return if (patchPlan.displayNameResId != 0) {
            context.importString(patchPlan.displayNameResId)
        } else {
            patchPlan.displayName
        }
    }

    private data class DuplicateReusePlan(
        val targetFileName: String? = null,
        val assignedFolderId: String? = null,
        val sourceStoragePaths: List<String> = emptyList()
    )

    internal fun resolveImportTargetFileName(
        item: ModImportItemPlan,
        reusedTargetFileName: String?
    ): String {
        return reusedTargetFileName
            ?.takeIf { it.isNotBlank() }
            ?: item.source.displayName.ifBlank { "${item.normalizedModId}.jar" }
    }

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
            },
            sourceStoragePaths = conflict.existingSources.map { it.storagePath }
        )
    }

    private fun applyFolderDecision(
        context: Context,
        storagePath: String,
        folderId: String?,
        sourceStoragePaths: Collection<String> = emptyList(),
        normalizedModId: String = ""
    ) {
        val normalizedFolderId = folderId?.trim().orEmpty()
        if (normalizedFolderId.isEmpty()) {
            return
        }
        val store = MainFolderStateStore().apply { ensureLoaded(context) }
        if (store.folders.none { it.id == normalizedFolderId }) {
            return
        }
        sourceStoragePaths.forEach { sourcePath ->
            resolveModStoragePathCandidates(sourcePath).forEach { candidate ->
                store.assignments.remove(candidate)
            }
        }
        val normalizedKey = ModManager.normalizeModId(normalizedModId)
        if (normalizedKey.isNotEmpty()) {
            store.assignments.remove(normalizedKey)
        }
        store.assignments[storagePath] = normalizedFolderId
        store.persist(context, synchronous = true)
        if (sourceStoragePaths.isNotEmpty() || normalizedModId.isNotBlank()) {
            MainFolderAssignmentHandoffStore.enqueueDuplicateFolderReuse(
                context = context,
                targetStoragePath = storagePath,
                folderId = normalizedFolderId,
                sourceStoragePaths = sourceStoragePaths,
                normalizedModId = normalizedModId
            )
        }
    }

    private fun clearFolderDecision(
        context: Context,
        storagePath: String,
        sourceStoragePaths: Collection<String> = emptyList(),
        normalizedModId: String = ""
    ) {
        val store = MainFolderStateStore().apply { ensureLoaded(context) }
        var changed = false
        resolveModStoragePathCandidates(storagePath).forEach { candidate ->
            if (store.assignments.remove(candidate) != null) {
                changed = true
            }
        }
        sourceStoragePaths.forEach { sourcePath ->
            resolveModStoragePathCandidates(sourcePath).forEach { candidate ->
                if (store.assignments.remove(candidate) != null) {
                    changed = true
                }
            }
        }
        val normalizedKey = ModManager.normalizeModId(normalizedModId)
        if (normalizedKey.isNotEmpty() && store.assignments.remove(normalizedKey) != null) {
            changed = true
        }
        store.unassignedIsCollapsed = false
        if (changed) {
            store.persist(context, synchronous = true)
        }
    }

    private fun buildImportedPatchInfo(
        item: ModImportItemPlan,
        patchResults: List<ImportPatchResult>
    ): ImportedModPatchInfo {
        var patchedAtlasEntries = 0
        var patchedFilterLines = 0
        var downscaledAtlasEntries = 0
        var downscaledAtlasPageEntries = 0
        var downscaledAtlasRuntimeMemorySavedMb = 0
        var patchedManifestRootEntries = 0
        var patchedManifestRootPrefix = ""
        var patchedFrieren = false
        var patchedDownfallClassEntries = 0
        var patchedDownfallMerchant = 0
        var patchedDownfallHexaghost = 0
        var patchedDownfallBossPanel = 0
        var patchedVupShion = false
        var patchedChaofanMod = false
        var patchedJacketShaderEntries = 0
        var patchedJacketVersionDirectives = 0
        var patchedJacketPrecisionBlocks = 0
        var patchedOriShaderEntries = 0
        var patchedOriGaussianBlurShaderEntries = 0
        var patchedOriBoxBlurShaderEntries = 0
        var patchedOriTextureSamplesBefore = 0
        var patchedOriTextureSamplesAfter = 0
        val appliedPatches = LinkedHashMap<String, ImportedModPatchRecord>()

        patchResults.filter { it.applied }.forEach { result ->
            val existingRecord = appliedPatches[result.moduleId]
            if (existingRecord == null || result.moduleVersion > existingRecord.version) {
                appliedPatches[result.moduleId] = ImportedModPatchRecord(
                    moduleId = result.moduleId,
                    version = result.moduleVersion.coerceAtLeast(0)
                )
            }
            when (result.moduleId) {
                AtlasFilterPatchModule.id -> {
                    patchedAtlasEntries += result.metrics["patchedAtlasEntries"] ?: 0
                    patchedFilterLines += result.metrics["patchedFilterLines"] ?: 0
                }
                AtlasOfflineDownscalePatchModule.id -> {
                    downscaledAtlasEntries += result.metrics["patchedAtlasEntries"] ?: 0
                    downscaledAtlasPageEntries += result.metrics["downscaledPageEntries"] ?: 0
                    downscaledAtlasRuntimeMemorySavedMb +=
                        result.metrics["estimatedRuntimeBytesSavedMb"] ?: 0
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
                ChaofanModImportPatchModule.id -> patchedChaofanMod = true
                JacketNoAnoKoImportPatchModule.id -> {
                    patchedJacketShaderEntries += result.metrics["patchedShaderEntries"] ?: 0
                    patchedJacketVersionDirectives += result.metrics["removedDesktopVersionDirectives"] ?: 0
                    patchedJacketPrecisionBlocks += result.metrics["insertedFragmentPrecisionBlocks"] ?: 0
                }
                OriImportPatchModule.id -> {
                    patchedOriShaderEntries += result.metrics["patchedShaderEntries"] ?: 0
                    patchedOriGaussianBlurShaderEntries +=
                        result.metrics["patchedGaussianBlurShaderEntries"] ?: 0
                    patchedOriBoxBlurShaderEntries +=
                        result.metrics["patchedBoxBlurShaderEntries"] ?: 0
                    patchedOriTextureSamplesBefore =
                        maxOf(
                            patchedOriTextureSamplesBefore,
                            result.metrics["estimatedTextureSamplesBefore"] ?: 0
                        )
                    patchedOriTextureSamplesAfter =
                        maxOf(
                            patchedOriTextureSamplesAfter,
                            result.metrics["estimatedTextureSamplesAfter"] ?: 0
                        )
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
            downscaledAtlasRuntimeMemorySavedMb = downscaledAtlasRuntimeMemorySavedMb,
            patchedManifestRootEntries = patchedManifestRootEntries,
            patchedManifestRootPrefix = patchedManifestRootPrefix,
            patchedFrierenAntiPirateMethod = patchedFrieren,
            patchedDownfallClassEntries = patchedDownfallClassEntries,
            patchedDownfallMerchantClassEntries = patchedDownfallMerchant,
            patchedDownfallHexaghostBodyClassEntries = patchedDownfallHexaghost,
            patchedDownfallBossMechanicPanelClassEntries = patchedDownfallBossPanel,
            patchedVupShionWebButtonConstructor = patchedVupShion,
            patchedChaofanModSteamworksHelperInitialization = patchedChaofanMod,
            patchedJacketNoAnoKoShaderEntries = patchedJacketShaderEntries,
            patchedJacketNoAnoKoDesktopVersionDirectives = patchedJacketVersionDirectives,
            patchedJacketNoAnoKoFragmentPrecisionBlocks = patchedJacketPrecisionBlocks,
            patchedOriShaderEntries = patchedOriShaderEntries,
            patchedOriGaussianBlurShaderEntries = patchedOriGaussianBlurShaderEntries,
            patchedOriBoxBlurShaderEntries = patchedOriBoxBlurShaderEntries,
            patchedOriTextureSamplesBefore = patchedOriTextureSamplesBefore,
            patchedOriTextureSamplesAfter = patchedOriTextureSamplesAfter,
            appliedPatches = appliedPatches.values.toList()
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
            ?: throw ImportFileCommitException(
                message = "Target has no parent: ${target.absolutePath}",
                diagnosticDetails = listOf(fileDiagnostics("commit.source", source), fileDiagnostics("commit.target", target))
            )
        val diagnosticDetails = ArrayList<String>()
        diagnosticDetails.add(fileDiagnostics("commit.before.source", source))
        diagnosticDetails.add(fileDiagnostics("commit.before.target", target))
        diagnosticDetails.add(directoryDiagnostics("commit.before.parent", parent))
        if (!parent.exists() && !parent.mkdirs()) {
            throw ImportFileCommitException(
                message = "Failed to create directory: ${parent.absolutePath}",
                diagnosticDetails = diagnosticDetails + directoryDiagnostics("commit.mkdir.parent", parent)
            )
        }
        diagnosticDetails.add(directoryDiagnostics("commit.ready.parent", parent))
        if (!source.exists()) {
            throw ImportFileCommitException(
                message = "Source file not found: ${source.absolutePath}",
                diagnosticDetails = diagnosticDetails + fileDiagnostics("commit.missing.source", source)
            )
        }
        val directRename = renameFile(source, target)
        diagnosticDetails.add("commit.directRename=${directRename.toLogText()}")
        if (directRename.success) {
            return
        }
        val temp = File(
            parent,
            ".${target.name}.${System.nanoTime()}.importing"
        )
        if (temp.exists() && !temp.delete()) {
            throw ImportFileCommitException(
                message = "Failed to clear stale temporary import file: ${temp.absolutePath}",
                diagnosticDetails = diagnosticDetails + fileDiagnostics("commit.staleTemp", temp)
            )
        }
        var committed = false
        try {
            try {
                FileInputStream(source).use { input ->
                    FileOutputStream(temp, false).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            } catch (error: Throwable) {
                throw ImportFileCommitException(
                    message = "Failed to copy imported mod file to temporary file: ${temp.absolutePath}",
                    cause = error,
                    diagnosticDetails = diagnosticDetails + listOf(
                        fileDiagnostics("commit.copyFailed.source", source),
                        fileDiagnostics("commit.copyFailed.temp", temp),
                        directoryDiagnostics("commit.copyFailed.parent", parent)
                    )
                )
            }
            diagnosticDetails.add(fileDiagnostics("commit.afterCopy.temp", temp))
            diagnosticDetails.add(directoryDiagnostics("commit.afterCopy.parent", parent))
            val tempRename = renameFile(temp, target)
            diagnosticDetails.add("commit.tempRename=${tempRename.toLogText()}")
            if (tempRename.success) {
                committed = true
            } else {
                throw ImportFileCommitException(
                    message = "Failed to commit imported mod file: ${target.absolutePath}",
                    diagnosticDetails = diagnosticDetails + listOf(
                        fileDiagnostics("commit.failed.source", source),
                        fileDiagnostics("commit.failed.temp", temp),
                        fileDiagnostics("commit.failed.target", target),
                        directoryDiagnostics("commit.failed.parent", parent)
                    )
                )
            }
        } finally {
            if (!committed) {
                temp.delete()
            }
        }
        source.delete()
    }

    private fun renameFile(source: File, target: File): RenameFileResult {
        val attempts = ArrayList<RenameFileAttempt>()
        try {
            if (source.renameTo(target)) {
                attempts.add(RenameFileAttempt(method = "File.renameTo", success = true, detail = "ok"))
                return RenameFileResult(success = true, attempts = attempts)
            }
            attempts.add(RenameFileAttempt(method = "File.renameTo", success = false, detail = "returned false"))
        } catch (error: Throwable) {
            attempts.add(RenameFileAttempt(method = "File.renameTo", success = false, detail = error.summaryForDiagnostics()))
        }
        try {
            Os.rename(source.absolutePath, target.absolutePath)
            attempts.add(RenameFileAttempt(method = "Os.rename", success = true, detail = "ok"))
            return RenameFileResult(success = true, attempts = attempts)
        } catch (error: Throwable) {
            attempts.add(RenameFileAttempt(method = "Os.rename", success = false, detail = error.summaryForDiagnostics()))
        }
        return RenameFileResult(success = false, attempts = attempts)
    }

    private class ImportFileCommitException(
        message: String,
        cause: Throwable? = null,
        val diagnosticDetails: List<String> = emptyList()
    ) : IOException(message, cause)

    private data class RenameFileResult(
        val success: Boolean,
        val attempts: List<RenameFileAttempt>
    )

    private data class RenameFileAttempt(
        val method: String,
        val success: Boolean,
        val detail: String
    )

    private fun RenameFileResult.toLogText(): String {
        return attempts.joinToString("; ") { attempt ->
            "${attempt.method}:${if (attempt.success) "success" else "failed"}:${attempt.detail}"
        }.ifBlank { "no attempts" }
    }

    private fun Throwable.patchFailureDetailsForResult(): List<String> {
        val details = ArrayList<String>()
        details.add(summaryForDiagnostics())
        cause?.let { details.add("cause=${it.summaryForDiagnostics()}") }
        return details
    }

    private fun Throwable.importFailureDetails(): List<String> {
        val details = ArrayList<String>()
        details.add("error=${summaryForDiagnostics()}")
        cause?.let { details.add("cause=${it.summaryForDiagnostics()}") }
        if (this is ImportFileCommitException) {
            details.addAll(diagnosticDetails)
        }
        stackTrace.take(8).forEach { frame ->
            details.add("stack=$frame")
        }
        return details
    }

    private fun Throwable.summaryForDiagnostics(): String {
        val base = javaClass.name + (message?.trim()?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "")
        return if (this is ErrnoException) {
            "$base errno=$errno"
        } else {
            base
        }
    }

    private fun fileDiagnostics(label: String, file: File): String {
        return "$label path=${file.absolutePath} exists=${file.exists()} isFile=${file.isFile} isDirectory=${file.isDirectory} length=${safeLength(file)} canRead=${file.canRead()} canWrite=${file.canWrite()} usableSpace=${file.usableSpace} freeSpace=${file.freeSpace} totalSpace=${file.totalSpace}"
    }

    private fun directoryDiagnostics(label: String, directory: File): String {
        val importArtifacts = directory.listFiles()
            ?.count { file -> file.name.endsWith(".importing") || file.name.endsWith(".importing.marker") }
            ?: -1
        return "$label path=${directory.absolutePath} exists=${directory.exists()} isFile=${directory.isFile} isDirectory=${directory.isDirectory} canRead=${directory.canRead()} canWrite=${directory.canWrite()} usableSpace=${directory.usableSpace} freeSpace=${directory.freeSpace} totalSpace=${directory.totalSpace} importArtifacts=$importArtifacts"
    }

    private fun safeLength(file: File): Long {
        return if (file.exists()) file.length() else 0L
    }

    private fun importCommitMarker(target: File): File {
        val parent = target.parentFile ?: throw IOException("Target has no parent: ${target.absolutePath}")
        return File(parent, ".${target.name}.importing.marker")
    }

    private fun writeImportCommitMarker(marker: File, target: File) {
        val parent = marker.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        marker.writeText(target.name, Charsets.UTF_8)
    }
}

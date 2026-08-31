package io.stamethyst.backend.workshop

import android.content.Context
import io.stamethyst.backend.mods.AtlasOfflineDownscaleStrategy
import io.stamethyst.backend.mods.importing.DuplicateImportDecision
import io.stamethyst.backend.mods.importing.ImportPatchResult
import io.stamethyst.backend.mods.importing.ModImportDecisions
import io.stamethyst.backend.mods.importing.ModImportExecutionProgress
import io.stamethyst.backend.mods.importing.ModImportExecutionReport
import io.stamethyst.backend.mods.importing.ModImportExecutor
import io.stamethyst.backend.mods.importing.ModImportPatchExecutionEvent
import io.stamethyst.backend.mods.importing.ModImportPatchSkipReason
import io.stamethyst.backend.mods.importing.ModImportPlanningOptions
import io.stamethyst.backend.mods.importing.ModImportPlanningProgress
import io.stamethyst.backend.mods.importing.ModImportPlanner
import io.stamethyst.backend.mods.importing.ModImportPlan
import io.stamethyst.backend.mods.importing.patches.ImportPatchRegistry
import io.stamethyst.backend.mods.importing.patches.texture.AtlasOfflineDownscalePatchModule
import io.stamethyst.ui.preferences.LauncherPreferences
import java.io.File

internal object WorkshopAutoImporter {
    private val ATLAS_OFFLINE_DOWNSCALE_PATCH_ID = AtlasOfflineDownscalePatchModule.id

    fun importDownloadedJar(
        context: Context,
        details: WorkshopItemDetails,
        jarFile: File,
        onProgress: (WorkshopAutoImportProgress) -> Unit = {},
    ): WorkshopAutoImportResult = importDownloadedJars(
        context = context,
        details = details,
        jarFiles = listOf(jarFile),
        onProgress = onProgress,
    )

    fun importDownloadedJars(
        context: Context,
        details: WorkshopItemDetails,
        jarFiles: List<File>,
        onProgress: (WorkshopAutoImportProgress) -> Unit = {},
    ): WorkshopAutoImportResult {
        val normalizedJarFiles = jarFiles
            .map { it.absoluteFile }
            .distinctBy { it.absolutePath }
        val logFile = runCatching { WorkshopAutoImportPatchLogStore.createLogFile(context) }.getOrNull()
        log(logFile, "自动导入修补开始")
        log(logFile, "workshop.appId=${details.summary.appId}")
        log(logFile, "workshop.publishedFileId=${details.summary.publishedFileId}")
        log(logFile, "workshop.title=${details.summary.title}")
        log(logFile, "source.jar.count=${normalizedJarFiles.size}")
        log(logFile, "context.filesDir=${context.filesDir.absolutePath} state=${describeFile(context.filesDir)}")
        log(logFile, "context.cacheDir=${context.cacheDir.absolutePath} state=${describeFile(context.cacheDir)}")
        log(logFile, "context.externalCacheDir=${context.externalCacheDir?.absolutePath.orEmpty()} state=${context.externalCacheDir?.let(::describeFile).orEmpty()}")
        normalizedJarFiles.forEachIndexed { index, jarFile ->
            log(logFile, "source.jar[$index].path=${jarFile.absolutePath}")
            log(logFile, "source.jar[$index].exists=${jarFile.isFile}")
            log(logFile, "source.jar[$index].length=${if (jarFile.isFile) jarFile.length() else 0L}")
        }
        val plan = try {
            log(logFile, "规划阶段开始")
            ModImportPlanner.planLocalFiles(
                context = context,
                files = normalizedJarFiles,
                options = ModImportPlanningOptions(
                    includeUserConfigurablePatches = true,
                    deferUserConfigurablePatchInspection = true,
                ),
                onProgress = { progress ->
                    log(logFile, progress.toLogLine("规划进度"))
                    onProgress(progress.toWorkshopAutoImportProgress())
                },
            )
        } catch (error: Throwable) {
            log(logFile, "规划阶段失败：${error.summaryForLog()}")
            log(logFile, error.stackTraceToString())
            return WorkshopAutoImportResult.Failed(error.message ?: error.javaClass.simpleName)
        }
        return try {
            log(logFile, "规划阶段完成")
            log(logFile, "import.sessionDir=${plan.session.sessionDir.absolutePath}")
            log(logFile, "import.sessionDirState=${describeFile(plan.session.sessionDir)}")
            logPlan(logFile, plan)
            val decisions = buildAutoImportDecisions(context, plan)
            logDecisions(logFile, plan, decisions)
            val report = ModImportExecutor.execute(
                context = context,
                plan = plan,
                decisions = decisions,
                onPatchEvent = { event -> logPatchEvent(logFile, event) },
                onProgress = { progress ->
                    log(logFile, progress.toLogLine("执行进度"))
                    onProgress(progress.toWorkshopAutoImportProgress(executionProgressStart = PLANNING_PROGRESS_WEIGHT))
                }
            )
            logReport(logFile, report)
            val importedItems = report.importedResults
                .mapNotNull { result ->
                    val storagePath = result.storagePath?.trim().orEmpty()
                    if (storagePath.isEmpty()) {
                        null
                    } else {
                        WorkshopAutoImportedMod(
                            modName = result.modName,
                            storagePath = storagePath,
                        )
                    }
                }
            if (importedItems.isEmpty()) {
                log(logFile, "自动导入失败：自动导入未产生已安装模组")
                return WorkshopAutoImportResult.Failed("自动导入未产生已安装模组")
            }
            val failedCount = report.failedCount + report.blockedCount + report.skippedCount
            if (failedCount > 0 || importedItems.size < normalizedJarFiles.size) {
                val message = "自动导入仅成功 ${importedItems.size}/${normalizedJarFiles.size} 个 jar"
                log(logFile, "自动导入失败：$message")
                return WorkshopAutoImportResult.Failed(message)
            }
            log(logFile, "自动导入成功：items=${importedItems.joinToString { "${it.modName}:${it.storagePath}" }}")
            WorkshopAutoImportResult.Imported(
                mods = importedItems,
            )
        } catch (error: Throwable) {
            log(logFile, "执行阶段失败：${error.summaryForLog()}")
            log(logFile, error.stackTraceToString())
            WorkshopAutoImportResult.Failed(error.message ?: error.javaClass.simpleName)
        } finally {
            log(logFile, "cleanup.before=${describeFile(plan.session.sessionDir)}")
            runCatching { ModImportPlanner.cleanup(plan.session) }
                .onFailure { error -> log(logFile, "清理导入会话失败：${error.summaryForLog()}") }
            log(logFile, "cleanup.after=${describeFile(plan.session.sessionDir)}")
            log(logFile, "自动导入修补结束")
        }
    }

    private fun ModImportPlanningProgress.toWorkshopAutoImportProgress(): WorkshopAutoImportProgress {
        return WorkshopAutoImportProgress(
            message = message,
            percent = scaleProgress(percent, PLANNING_PROGRESS_WEIGHT),
            currentStep = currentStep,
            totalSteps = totalSteps,
            currentFileName = currentFileName,
        )
    }

    private fun ModImportExecutionProgress.toWorkshopAutoImportProgress(
        executionProgressStart: Int
    ): WorkshopAutoImportProgress {
        val remainingProgress = 100 - executionProgressStart
        return WorkshopAutoImportProgress(
            message = message,
            percent = (executionProgressStart + scaleProgress(percent, remainingProgress)).coerceIn(0, 100),
            currentStep = currentStep,
            totalSteps = totalSteps,
            currentFileName = currentFileName,
        )
    }

    private fun scaleProgress(percent: Int, weight: Int): Int {
        val safePercent = percent.coerceIn(0, 100)
        if (safePercent <= 0 || weight <= 0) return 0
        return ((safePercent * weight) + 99) / 100
    }

    private fun buildAutoImportDecisions(
        context: Context,
        plan: io.stamethyst.backend.mods.importing.ModImportPlan
    ): ModImportDecisions {
        val atlasDownscaleEnabled = ImportPatchRegistry.isEnabled(
            context,
            ATLAS_OFFLINE_DOWNSCALE_PATCH_ID
        )
        val patchEnabled = LinkedHashMap<String, Boolean>()
        plan.importableItems.forEach { item ->
            item.patchPlans.forEach { patch ->
                patchEnabled[ModImportDecisions.patchDecisionKey(item.id, patch.moduleId)] =
                    ImportPatchRegistry.isEnabled(context, patch.moduleId)
            }
        }
        return ModImportDecisions(
            duplicateDecisions = plan.duplicateConflicts.associate {
                it.normalizedModId to DuplicateImportDecision.ReplaceExisting
            },
            reusePreviousFileNameOnReplace = true,
            reusePreviousFolderOnReplace = true,
            patchEnabledByKey = patchEnabled,
            atlasDownscaleStrategy = if (atlasDownscaleEnabled) {
                AtlasOfflineDownscaleStrategy.maxEdge(
                    LauncherPreferences.readWorkshopAutoImportAtlasDownscaleMaxEdgePx(context)
                )
            } else {
                null
            },
            targetFolderIdByItemId = emptyMap(),
        )
    }

    private fun log(logFile: File?, message: String) {
        WorkshopAutoImportPatchLogStore.appendLine(logFile, message)
    }

    private fun describeFile(file: File): String {
        return "path=${file.absolutePath} exists=${file.exists()} isFile=${file.isFile} isDirectory=${file.isDirectory} length=${file.length()} canRead=${file.canRead()} canWrite=${file.canWrite()} usableSpace=${file.usableSpace} freeSpace=${file.freeSpace} totalSpace=${file.totalSpace}"
    }

    private fun logPlan(logFile: File?, plan: ModImportPlan) {
        log(logFile, "规划摘要：items=${plan.items.size} importable=${plan.importableItems.size} blocked=${plan.blockedItems.size} skipped=${plan.skippedItems.size} duplicateConflicts=${plan.duplicateConflicts.size}")
        plan.items.forEach { item ->
            log(
                logFile,
                "导入项：id=${item.id} file=${item.source.displayName} status=${item.status.name} modId=${item.normalizedModId.ifBlank { "<empty>" }} modName=${item.displayModName} patchPlans=${item.patchPlans.size} blockingReason=${item.blockingReason?.name.orEmpty()} blockingDetail=${item.blockingDetail.ifBlank { "<empty>" }}"
            )
            item.patchPlans.forEach { patch ->
                log(
                    logFile,
                    "修补计划：item=${item.id} module=${patch.moduleId} version=${patch.moduleVersion} name=${patch.displayName} category=${patch.category.name} defaultEnabled=${patch.defaultEnabled} userConfigurable=${patch.userConfigurable} failurePolicy=${patch.failurePolicy.name} applicable=${patch.applicable} details=${patch.details.joinToString(" | ").ifBlank { "<empty>" }}"
                )
            }
        }
        plan.duplicateConflicts.forEach { conflict ->
            log(
                logFile,
                "重复模组冲突：modId=${conflict.normalizedModId} importing=${conflict.importingDisplayNames.joinToString()} existing=${conflict.existingSources.joinToString { it.storagePath }}"
            )
        }
    }

    private fun logDecisions(logFile: File?, plan: ModImportPlan, decisions: ModImportDecisions) {
        plan.duplicateConflicts.forEach { conflict ->
            log(logFile, "自动决策：duplicate modId=${conflict.normalizedModId} decision=${decisions.duplicateDecisionFor(conflict.normalizedModId).name}")
        }
        log(logFile, "自动决策：reusePreviousFileNameOnReplace=${decisions.reusePreviousFileNameOnReplace}")
        log(logFile, "自动决策：reusePreviousFolderOnReplace=${decisions.reusePreviousFolderOnReplace}")
        log(logFile, "自动决策：atlasDownscaleStrategy=${decisions.atlasDownscaleStrategy}")
        plan.importableItems.forEach { item ->
            item.patchPlans.forEach { patch ->
                log(logFile, "自动决策：patch item=${item.id} module=${patch.moduleId} enabled=${decisions.isPatchEnabled(item.id, patch)}")
            }
        }
    }

    private fun logPatchEvent(logFile: File?, event: ModImportPatchExecutionEvent) {
        val itemText = "item=${event.item.id} modId=${event.item.normalizedModId.ifBlank { "<empty>" }} file=${event.item.source.displayName}"
        val patchText = "module=${event.patchPlan.moduleId} version=${event.patchPlan.moduleVersion} name=${event.patchPlan.displayName} failurePolicy=${event.patchPlan.failurePolicy.name}"
        when (event) {
            is ModImportPatchExecutionEvent.Started -> {
                log(logFile, "修补开始：$itemText $patchText")
            }
            is ModImportPatchExecutionEvent.Succeeded -> {
                log(logFile, "修补完成：$itemText $patchText ${event.result.toLogText()}")
            }
            is ModImportPatchExecutionEvent.Skipped -> {
                log(logFile, "修补跳过：$itemText $patchText reason=${event.reason.logText()}")
            }
            is ModImportPatchExecutionEvent.Failed -> {
                log(logFile, "修补失败：$itemText $patchText importBlocked=${event.importBlocked} error=${event.error.summaryForLog()}")
                log(logFile, event.error.stackTraceToString())
            }
        }
    }

    private fun logReport(logFile: File?, report: ModImportExecutionReport) {
        log(logFile, "执行摘要：imported=${report.importedCount} skipped=${report.skippedCount} blocked=${report.blockedCount} failed=${report.failedCount} appliedPatches=${report.appliedPatchResults.size}")
        report.results.forEach { result ->
            log(
                logFile,
                "执行结果：item=${result.itemId} modId=${result.modId} modName=${result.modName} imported=${result.imported} skipped=${result.skipped} blocked=${result.blocked} failed=${result.failed} storagePath=${result.storagePath.orEmpty()} message=${result.message.ifBlank { "<empty>" }} patchResults=${result.patchResults.size} failureDetails=${result.failureDetails.size}"
            )
            result.failureDetails.forEach { detail ->
                log(logFile, "执行失败详情：item=${result.itemId} $detail")
            }
            result.patchResults.forEach { patchResult ->
                log(logFile, "执行修补结果：item=${result.itemId} ${patchResult.toLogText()}")
            }
        }
    }

    private fun ModImportPlanningProgress.toLogLine(prefix: String): String =
        "$prefix：step=$currentStep/$totalSteps percent=${percent.coerceIn(0, 100)} file=$currentFileName message=$message"

    private fun ModImportExecutionProgress.toLogLine(prefix: String): String =
        "$prefix：step=$currentStep/$totalSteps percent=${percent.coerceIn(0, 100)} file=$currentFileName message=$message"

    private fun ImportPatchResult.toLogText(): String {
        return "module=$moduleId version=$moduleVersion name=$displayName applied=$applied summary=$summary details=${details.joinToString(" | ").ifBlank { "<empty>" }} metrics=${metrics.ifEmpty { emptyMap<String, Int>() }} attributes=${attributes.ifEmpty { emptyMap<String, String>() }}"
    }

    private fun ModImportPatchSkipReason.logText(): String = when (this) {
        ModImportPatchSkipReason.DisabledByDecision -> "disabled_by_decision"
        ModImportPatchSkipReason.DisabledBySetting -> "disabled_by_setting"
        ModImportPatchSkipReason.ModuleUnavailable -> "module_unavailable"
    }

    private fun Throwable.summaryForLog(): String {
        return javaClass.name + (message?.trim()?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "")
    }

    private const val PLANNING_PROGRESS_WEIGHT = 30
}

internal data class WorkshopAutoImportProgress(
    val message: String,
    val percent: Int,
    val currentStep: Int,
    val totalSteps: Int,
    val currentFileName: String,
)

internal data class WorkshopAutoImportedMod(
    val modName: String,
    val storagePath: String,
)

internal sealed interface WorkshopAutoImportResult {
    data class Imported(
        val mods: List<WorkshopAutoImportedMod>,
    ) : WorkshopAutoImportResult {
        val modName: String
            get() = mods.firstOrNull()?.modName.orEmpty()
        val storagePath: String
            get() = mods.firstOrNull()?.storagePath.orEmpty()
        val modNames: List<String>
            get() = mods.map { it.modName }.filter { it.isNotBlank() }
        val storagePaths: List<String>
            get() = mods.map { it.storagePath }.filter { it.isNotBlank() }
    }

    data class Failed(
        val message: String,
    ) : WorkshopAutoImportResult
}

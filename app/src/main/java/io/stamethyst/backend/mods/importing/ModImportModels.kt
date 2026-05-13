package io.stamethyst.backend.mods.importing

import android.net.Uri
import io.stamethyst.backend.mods.AtlasOfflineDownscaleStrategy
import io.stamethyst.backend.mods.ModJarSupport
import java.io.File

internal enum class ModImportItemStatus {
    IMPORTABLE,
    NEEDS_DECISION,
    SKIPPED,
    BLOCKED
}

internal enum class ModImportBlockingReason {
    UnsupportedFile,
    CompressedArchive,
    UnreadableJar,
    MissingManifest,
    MissingModId,
    ReservedCoreComponent,
    InvalidMtsLaunchManifest
}

internal enum class DuplicateImportDecision {
    ReplaceExisting,
    KeepMultiple,
    SkipNew
}

internal data class ModImportSession(
    val id: Long,
    val sessionDir: File
)

internal data class PreparedImportSource(
    val index: Int,
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val file: File
)

internal data class ExistingDuplicateImportSource(
    val storagePath: String,
    val fileName: String,
    val assignedFolderId: String? = null
)

internal data class DuplicateImportConflict(
    val normalizedModId: String,
    val displayModId: String,
    val importingItemIds: List<String>,
    val importingDisplayNames: List<String>,
    val existingSources: List<ExistingDuplicateImportSource>
)

internal data class ModImportItemPlan(
    val id: String,
    val source: PreparedImportSource,
    val status: ModImportItemStatus,
    val blockingReason: ModImportBlockingReason? = null,
    val blockingDetail: String = "",
    val manifest: ModJarSupport.ModManifestInfo? = null,
    val normalizedModId: String = "",
    val launchModId: String = "",
    val reservedComponent: String = "",
    val duplicateConflictKey: String? = null,
    val patchPlans: List<ImportPatchPlan> = emptyList()
) {
    val displayModId: String
        get() = manifest?.modId?.trim()?.ifBlank { normalizedModId } ?: normalizedModId
    val displayModName: String
        get() = manifest?.name?.trim()?.ifBlank { displayModId } ?: source.displayName
    val importable: Boolean
        get() = status == ModImportItemStatus.IMPORTABLE || status == ModImportItemStatus.NEEDS_DECISION
}

internal data class ModImportPlan(
    val session: ModImportSession,
    val items: List<ModImportItemPlan>,
    val duplicateConflicts: List<DuplicateImportConflict>
) {
    val importableItems: List<ModImportItemPlan>
        get() = items.filter { it.importable }
    val blockedItems: List<ModImportItemPlan>
        get() = items.filter { it.status == ModImportItemStatus.BLOCKED }
    val skippedItems: List<ModImportItemPlan>
        get() = items.filter { it.status == ModImportItemStatus.SKIPPED }
    val configurablePatchPlans: List<ImportPatchPlan>
        get() = importableItems.flatMap { it.patchPlans }.filter { it.userConfigurable }
}

internal data class ModImportDecisions(
    val duplicateDecisions: Map<String, DuplicateImportDecision> = emptyMap(),
    val reusePreviousFileNameOnReplace: Boolean = true,
    val reusePreviousFolderOnReplace: Boolean = true,
    val patchEnabledByKey: Map<String, Boolean> = emptyMap(),
    val atlasDownscaleStrategy: AtlasOfflineDownscaleStrategy? = null,
    val targetFolderId: String? = null
) {
    fun duplicateDecisionFor(modId: String): DuplicateImportDecision {
        return duplicateDecisions[modId] ?: DuplicateImportDecision.KeepMultiple
    }

    fun isPatchEnabled(itemId: String, plan: ImportPatchPlan): Boolean {
        return patchEnabledByKey[patchDecisionKey(itemId, plan.moduleId)] ?: plan.defaultEnabled
    }

    companion object {
        fun patchDecisionKey(itemId: String, moduleId: String): String = "$itemId::$moduleId"
    }
}

internal data class ModImportExecutionProgress(
    val currentIndex: Int,
    val total: Int,
    val currentFileName: String,
    val message: String
) {
    val percent: Int
        get() = if (total <= 0) 0 else ((currentIndex.coerceIn(0, total) * 100) / total).coerceIn(0, 100)
}

internal data class ModImportExecutionItemResult(
    val itemId: String,
    val displayName: String,
    val modId: String,
    val modName: String,
    val storagePath: String? = null,
    val imported: Boolean = false,
    val skipped: Boolean = false,
    val blocked: Boolean = false,
    val failed: Boolean = false,
    val message: String = "",
    val patchResults: List<ImportPatchResult> = emptyList()
)

internal data class ModImportExecutionReport(
    val results: List<ModImportExecutionItemResult>
) {
    val importedResults: List<ModImportExecutionItemResult>
        get() = results.filter { it.imported }
    val importedCount: Int
        get() = importedResults.size
    val skippedCount: Int
        get() = results.count { it.skipped }
    val blockedCount: Int
        get() = results.count { it.blocked }
    val failedCount: Int
        get() = results.count { it.failed }
    val appliedPatchResults: List<ImportPatchResult>
        get() = importedResults.flatMap { it.patchResults }.filter { it.applied }
}

internal enum class ImportPatchCategory {
    Structural,
    Texture,
    Shader,
    Bytecode,
    ModSpecific,
    Safety
}

internal enum class ImportPatchFailurePolicy {
    BlockImport,
    SkipPatchContinueImport
}

internal data class ImportPatchPlan(
    val moduleId: String,
    val moduleVersion: Int,
    val displayNameResId: Int = 0,
    val summaryResId: Int = 0,
    val displayName: String,
    val summary: String,
    val category: ImportPatchCategory,
    val defaultEnabled: Boolean,
    val userConfigurable: Boolean,
    val failurePolicy: ImportPatchFailurePolicy,
    val applicable: Boolean,
    val details: List<String> = emptyList()
)

internal data class ImportPatchResult(
    val moduleId: String,
    val moduleVersion: Int,
    val displayNameResId: Int = 0,
    val summaryResId: Int = 0,
    val displayName: String,
    val applied: Boolean,
    val summary: String,
    val details: List<String> = emptyList(),
    val metrics: Map<String, Int> = emptyMap(),
    val attributes: Map<String, String> = emptyMap()
)

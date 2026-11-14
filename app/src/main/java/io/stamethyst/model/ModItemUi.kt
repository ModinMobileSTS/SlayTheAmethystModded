package io.stamethyst.model

import androidx.compose.runtime.Stable

@Stable
data class AgentPatchModUi(
    val patchId: String,
    val patchModId: String,
    val name: String,
    val version: String,
    val description: String,
    val enabled: Boolean,
    val jarPath: String = "",
)

@Stable
data class ModItemUi(
    val modId: String,
    val manifestModId: String,
    val storagePath: String,
    val name: String,
    val version: String,
    val fileSizeBytes: Long = 0L,
    val description: String,
    val dependencies: List<String>,
    val required: Boolean,
    val installed: Boolean,
    val enabled: Boolean,
    val explicitPriority: Int?,
    val effectivePriority: Int?,
    val importPatchDetails: String? = null,
    val importPatches: List<ModImportPatchUi> = emptyList(),
    val hasOutdatedImportPatches: Boolean = false,
    val newlyImported: Boolean = false,
    val favorite: Boolean = false,
    val workshop: WorkshopModUi? = null,
    val alias: String = "",
    val agentPatchMods: List<AgentPatchModUi> = emptyList(),
    val hasAgentPatchCatalogueEntries: Boolean = false,
) {
    val needsImportPatchUpdate: Boolean
        get() = hasOutdatedImportPatches || importPatches.any { it.appliedVersion == null || it.isOutdated }

    val updatableImportPatches: List<ModImportPatchUi>
        get() = importPatches.filter { it.appliedVersion == null || it.isOutdated }
}

@Stable
data class ModImportPatchUi(
    val moduleId: String,
    val name: String,
    val summary: String,
    val appliedVersion: Int?,
    val currentVersion: Int,
    val userConfigurable: Boolean,
    val enabled: Boolean,
    val isOutdated: Boolean = false
)

@Stable
data class WorkshopModUi(
    val appId: UInt,
    val publishedFileId: ULong,
    val state: WorkshopModState,
    val isTexturePack: Boolean = false,
    val statusText: String = "",
    val localJarPath: String = "",
    val localPreviewImagePath: String = "",
    val downloadProgressPercent: Int? = null,
    /**
     * True when the item is on [io.stamethyst.backend.workshop.WorkshopDownloadBlocklist].
     * The launcher manages these itself, so the mod page must not offer download actions.
     */
    val downloadBlocked: Boolean = false,
    val hasPendingInstall: Boolean = false,
)

enum class WorkshopModState {
    NotDownloaded,
    ImportedUnpatched,
    UpdatePendingInstall,
    ImportedPatched,
    Queued,
    Downloading,
    Cancelling,
    DownloadPaused,
    DownloadFailed,
    NonStandardDownloaded,
    TexturePackInstalled,
    UpdateAvailable,
    FileMissing
}

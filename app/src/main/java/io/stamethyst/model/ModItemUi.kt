package io.stamethyst.model

import androidx.compose.runtime.Stable

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
    val alias: String = ""
)

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
    val statusText: String = "",
    val localJarPath: String = "",
    val localPreviewImagePath: String = "",
    val downloadProgressPercent: Int? = null,
    /**
     * True when the item is on [io.stamethyst.backend.workshop.WorkshopDownloadBlocklist].
     * The launcher manages these itself, so the mod page must not offer download actions.
     */
    val downloadBlocked: Boolean = false,
)

enum class WorkshopModState {
    NotDownloaded,
    ImportedUnpatched,
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

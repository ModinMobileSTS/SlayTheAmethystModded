package io.stamethyst.ui.workshop

import io.stamethyst.backend.workshop.WorkshopInstalledModRecord
import io.stamethyst.backend.workshop.WorkshopDownloadBlocklist
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopModStateResolver
import io.stamethyst.backend.workshop.WorkshopResolvedModStateKind
import io.stamethyst.R

internal enum class WorkshopModDownloadState(
    val statusLabelResId: Int,
    val actionLabelResId: Int,
    val canStartDownload: Boolean,
) {
    Downloaded(R.string.workshop_download_state_downloaded, R.string.workshop_download_state_downloaded, false),
    UpdatePendingInstall(R.string.workshop_download_state_pending_install, R.string.workshop_download_state_pending_install, false),
    NotDownloaded(R.string.workshop_download_state_not_downloaded, R.string.workshop_action_download, true),
    UpdateAvailable(R.string.workshop_download_state_update_available, R.string.workshop_action_update, true),
    Queued(R.string.workshop_download_state_queued, R.string.workshop_download_state_queued, false),
    Downloading(R.string.workshop_download_state_downloading, R.string.workshop_download_state_downloading, false),
    Paused(R.string.workshop_download_state_paused, R.string.workshop_action_continue, true),
    Cancelling(R.string.workshop_download_state_cancelling, R.string.workshop_download_state_cancelling, false),
    DownloadFailed(R.string.workshop_download_state_failed, R.string.workshop_action_retry, true),
    Unavailable(R.string.workshop_download_state_unavailable, R.string.workshop_download_state_unavailable, false),
}

internal const val WORKSHOP_DOWNLOAD_PROGRESS_REFRESH_INTERVAL_MS = 500L

internal fun resolveWorkshopModDownloadState(
    item: WorkshopItemSummary,
    installedMods: List<WorkshopInstalledModRecord>,
    downloadTasks: List<WorkshopDownloadTaskUi>,
    preparingDownloadIds: Set<ULong> = emptySet(),
): WorkshopModDownloadState {
    val task = downloadTasks.firstOrNull { it.publishedFileId == item.publishedFileId }
    return resolveWorkshopModDownloadState(
        item = item,
        installedMods = installedMods,
        taskStatus = task?.status,
        taskMessage = task?.message.orEmpty(),
        preparingDownloadIds = preparingDownloadIds,
    )
}

internal fun resolveWorkshopModDownloadState(
    item: WorkshopItemSummary,
    installedMods: List<WorkshopInstalledModRecord>,
    downloadTaskStatuses: Map<ULong, WorkshopDownloadTaskStatus>,
    preparingDownloadIds: Set<ULong> = emptySet(),
): WorkshopModDownloadState = resolveWorkshopModDownloadState(
    item = item,
    installedMods = installedMods,
    taskStatus = downloadTaskStatuses[item.publishedFileId],
    preparingDownloadIds = preparingDownloadIds,
)

private fun resolveWorkshopModDownloadState(
    item: WorkshopItemSummary,
    installedMods: List<WorkshopInstalledModRecord>,
    taskStatus: WorkshopDownloadTaskStatus?,
    taskMessage: String = "",
    preparingDownloadIds: Set<ULong> = emptySet(),
): WorkshopModDownloadState {
    if (WorkshopDownloadBlocklist.isBlocked(item)) return WorkshopModDownloadState.Unavailable
    if (item.publishedFileId in preparingDownloadIds) return WorkshopModDownloadState.Queued
    val installed = installedMods.firstOrNull {
        it.appId == item.appId && it.publishedFileId == item.publishedFileId
    }
    val resolved = WorkshopModStateResolver.resolve(
        record = installed,
        taskStatus = taskStatus,
        taskMessage = taskMessage,
        remoteUpdatedAtMillis = item.updatedAtMillis,
    )
    return resolved.kind.toWorkshopModDownloadState()
}

private fun WorkshopResolvedModStateKind.toWorkshopModDownloadState(): WorkshopModDownloadState = when (this) {
    WorkshopResolvedModStateKind.NotDownloaded -> WorkshopModDownloadState.NotDownloaded
    WorkshopResolvedModStateKind.ImportedUnpatched,
    WorkshopResolvedModStateKind.ImportedPatched,
    WorkshopResolvedModStateKind.NonStandardDownloaded,
    WorkshopResolvedModStateKind.TexturePackInstalled -> WorkshopModDownloadState.Downloaded
    WorkshopResolvedModStateKind.UpdatePendingInstall -> WorkshopModDownloadState.UpdatePendingInstall
    WorkshopResolvedModStateKind.Queued -> WorkshopModDownloadState.Queued
    WorkshopResolvedModStateKind.Downloading -> WorkshopModDownloadState.Downloading
    WorkshopResolvedModStateKind.DownloadPaused -> WorkshopModDownloadState.Paused
    WorkshopResolvedModStateKind.Cancelling -> WorkshopModDownloadState.Cancelling
    WorkshopResolvedModStateKind.DownloadFailed,
    WorkshopResolvedModStateKind.FileMissing -> WorkshopModDownloadState.DownloadFailed
    WorkshopResolvedModStateKind.UpdateAvailable -> WorkshopModDownloadState.UpdateAvailable
}

internal fun WorkshopDownloadTaskStatus.isActiveWorkshopDownload(): Boolean = when (this) {
    WorkshopDownloadTaskStatus.Queued,
    WorkshopDownloadTaskStatus.Resolving,
    WorkshopDownloadTaskStatus.Downloading,
    WorkshopDownloadTaskStatus.Pausing,
    WorkshopDownloadTaskStatus.Cancelling -> true
    WorkshopDownloadTaskStatus.Paused,
    WorkshopDownloadTaskStatus.Completed,
    WorkshopDownloadTaskStatus.Failed,
    WorkshopDownloadTaskStatus.Cancelled -> false
}

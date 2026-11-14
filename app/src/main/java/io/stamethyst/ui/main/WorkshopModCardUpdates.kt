package io.stamethyst.ui.main

import io.stamethyst.backend.workshop.WorkshopDownloadBlocklist
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.WorkshopModStateResolver
import io.stamethyst.backend.workshop.WorkshopResolvedModStateKind
import io.stamethyst.backend.workshop.isActiveDownload
import io.stamethyst.backend.workshop.shouldShowOnLauncherCards
import io.stamethyst.model.ModItemUi
import io.stamethyst.model.WorkshopModState
import io.stamethyst.model.WorkshopModUi
import io.stamethyst.ui.workshop.WorkshopDownloadTaskUi

internal data class WorkshopModCardUpdate(
    val mods: List<ModItemUi>,
    val hasActiveDownloads: Boolean,
    val needsInstalledModsRefresh: Boolean,
)

internal fun updateWorkshopModCards(
    mods: List<ModItemUi>,
    tasks: List<WorkshopDownloadTaskUi>,
): WorkshopModCardUpdate {
    val tasksById = tasks.associateBy { it.details.summary.appId to it.publishedFileId }
    var needsRefresh = false
    val updatedMods = mods.map { mod ->
        val workshop = mod.workshop ?: return@map mod
        val task = tasksById[workshop.appId to workshop.publishedFileId]
        val taskState = task?.status?.toWorkshopModStateOrNull()
        if (task == null || taskState == null) {
            // Completed/cancelled tasks are excluded from the launcher's lightweight disk read.
            // Keep their card until a full scan restores the downloaded jar's install action or
            // replaces it with the installed mod. Do this even if OTHER downloads are still active.
            if (workshop.state.isWorkshopTaskState()) needsRefresh = true
            mod
        } else {
            if (workshop.state in setOf(WorkshopModState.Queued, WorkshopModState.Downloading, WorkshopModState.Cancelling) &&
                !task.status.isActiveDownload()) needsRefresh = true
            mod.copy(
                fileSizeBytes = task.fileSizeBytes.takeIf { it > 0L } ?: mod.fileSizeBytes,
                workshop = workshop.copy(
                    state = if (workshop.hasPendingInstall && !task.status.isActiveDownload())
                        WorkshopModState.UpdatePendingInstall else taskState,
                    statusText = task.message.ifBlank { task.status.defaultWorkshopStatusText() },
                    downloadProgressPercent = task.progressPercent,
                    hasPendingInstall = workshop.hasPendingInstall && !task.status.isActiveDownload(),
                ),
            )
        }
    }.toMutableList()
    val existingIds = updatedMods.mapNotNull { mod ->
        mod.workshop?.let { it.appId to it.publishedFileId }
    }.toMutableSet()
    tasks.asReversed().forEach { task ->
        val key = task.details.summary.appId to task.publishedFileId
        if (task.status.shouldShowOnLauncherCards() && existingIds.add(key)) {
            updatedMods.add(0, task.toStandaloneWorkshopModItem())
        }
    }
    return WorkshopModCardUpdate(
        mods = updatedMods,
        hasActiveDownloads = tasks.any { it.status.isActiveDownload() },
        needsInstalledModsRefresh = needsRefresh,
    )
}

private fun WorkshopModState.isWorkshopTaskState(): Boolean = when (this) {
    WorkshopModState.Queued,
    WorkshopModState.Downloading,
    WorkshopModState.Cancelling,
    WorkshopModState.DownloadPaused,
    WorkshopModState.DownloadFailed -> true
    else -> false
}

private fun WorkshopDownloadTaskStatus.toWorkshopModStateOrNull(): WorkshopModState? = when (
    WorkshopModStateResolver.resolveTaskKind(this)
) {
    WorkshopResolvedModStateKind.Queued -> WorkshopModState.Queued
    WorkshopResolvedModStateKind.Downloading -> WorkshopModState.Downloading
    WorkshopResolvedModStateKind.Cancelling -> WorkshopModState.Cancelling
    WorkshopResolvedModStateKind.DownloadPaused -> WorkshopModState.DownloadPaused
    WorkshopResolvedModStateKind.DownloadFailed -> WorkshopModState.DownloadFailed
    else -> null
}

private fun WorkshopDownloadTaskStatus.defaultWorkshopStatusText(): String = when (this) {
    WorkshopDownloadTaskStatus.Queued -> "等待下载"
    WorkshopDownloadTaskStatus.Resolving -> "正在解析下载内容"
    WorkshopDownloadTaskStatus.Downloading -> "正在下载"
    WorkshopDownloadTaskStatus.Pausing -> "正在暂停"
    WorkshopDownloadTaskStatus.Cancelling -> "正在取消"
    WorkshopDownloadTaskStatus.Paused -> "下载已暂停，可继续"
    WorkshopDownloadTaskStatus.Failed -> "下载失败"
    WorkshopDownloadTaskStatus.Completed -> "下载完成"
    WorkshopDownloadTaskStatus.Cancelled -> "下载已取消"
}

private fun WorkshopDownloadTaskUi.toStandaloneWorkshopModItem(): ModItemUi {
    val summary = details.summary
    return ModItemUi(
        modId = "workshop:${publishedFileId}",
        manifestModId = "workshop:${publishedFileId}",
        storagePath = "workshop:${summary.appId}:${publishedFileId}",
        name = title.ifBlank { summary.title.ifBlank { publishedFileId.toString() } },
        version = summary.updatedAtMillis.toString(),
        fileSizeBytes = fileSizeBytes.takeIf { it > 0L } ?: totalBytes ?: summary.fileSizeBytes,
        description = description.ifBlank { summary.description },
        dependencies = emptyList(),
        required = false,
        installed = false,
        enabled = false,
        explicitPriority = null,
        effectivePriority = null,
        workshop = WorkshopModUi(
            appId = summary.appId,
            publishedFileId = publishedFileId,
            state = checkNotNull(status.toWorkshopModStateOrNull()),
            statusText = message.ifBlank { status.defaultWorkshopStatusText() },
            downloadProgressPercent = progressPercent,
            downloadBlocked = WorkshopDownloadBlocklist.isBlocked(publishedFileId),
        ),
    )
}

package io.stamethyst.backend.workshop

enum class WorkshopResolvedModStateKind {
    NotDownloaded,
    ImportedUnpatched,
    ImportedPatched,
    Queued,
    Downloading,
    DownloadPaused,
    Cancelling,
    DownloadFailed,
    NonStandardDownloaded,
    TexturePackInstalled,
    UpdateAvailable,
    FileMissing,
}

data class WorkshopResolvedModState(
    val kind: WorkshopResolvedModStateKind,
    val statusText: String,
)

object WorkshopModStateResolver {
    fun resolve(
        record: WorkshopInstalledModRecord?,
        taskStatus: WorkshopDownloadTaskStatus? = null,
        taskMessage: String = "",
        remoteUpdatedAtMillis: Long = 0L,
    ): WorkshopResolvedModState {
        val taskState = resolveTaskState(taskStatus, taskMessage)
        if (taskState != null && (record?.cardState != WorkshopModCardState.UpdateAvailable || taskStatus.shouldOverrideUpdateAvailableRecord())) {
            return taskState
        }
        if (record == null) {
            return WorkshopResolvedModState(WorkshopResolvedModStateKind.NotDownloaded, "未下载")
        }
        return resolveRecordState(record, remoteUpdatedAtMillis)
    }

    /**
     * Single source of truth for mapping a persisted task status onto a display state kind.
     *
     * Every surface that renders download progress (mod cards, market list, download center)
     * must go through this so the pages cannot drift apart.
     */
    fun resolveTaskKind(status: WorkshopDownloadTaskStatus?): WorkshopResolvedModStateKind? = when (status) {
        WorkshopDownloadTaskStatus.Queued -> WorkshopResolvedModStateKind.Queued
        // Pausing keeps reporting as Downloading: the transfer is still draining until the
        // service actually settles on Paused.
        WorkshopDownloadTaskStatus.Resolving,
        WorkshopDownloadTaskStatus.Downloading,
        WorkshopDownloadTaskStatus.Pausing -> WorkshopResolvedModStateKind.Downloading
        WorkshopDownloadTaskStatus.Cancelling -> WorkshopResolvedModStateKind.Cancelling
        WorkshopDownloadTaskStatus.Paused -> WorkshopResolvedModStateKind.DownloadPaused
        WorkshopDownloadTaskStatus.Failed -> WorkshopResolvedModStateKind.DownloadFailed
        WorkshopDownloadTaskStatus.Completed,
        WorkshopDownloadTaskStatus.Cancelled,
        null -> null
    }

    private fun resolveTaskState(
        status: WorkshopDownloadTaskStatus?,
        message: String,
    ): WorkshopResolvedModState? {
        val kind = resolveTaskKind(status) ?: return null
        val fallbackText = when (kind) {
            WorkshopResolvedModStateKind.Queued -> "等待下载"
            WorkshopResolvedModStateKind.Downloading -> "正在下载"
            WorkshopResolvedModStateKind.Cancelling -> "正在取消"
            WorkshopResolvedModStateKind.DownloadPaused -> "下载已暂停，可继续"
            WorkshopResolvedModStateKind.DownloadFailed -> "下载失败"
            else -> ""
        }
        return WorkshopResolvedModState(kind = kind, statusText = message.ifBlank { fallbackText })
    }

    private fun WorkshopDownloadTaskStatus?.shouldOverrideUpdateAvailableRecord(): Boolean = when (this) {
        WorkshopDownloadTaskStatus.Queued,
        WorkshopDownloadTaskStatus.Resolving,
        WorkshopDownloadTaskStatus.Downloading,
        WorkshopDownloadTaskStatus.Pausing,
        WorkshopDownloadTaskStatus.Cancelling,
        WorkshopDownloadTaskStatus.Paused,
        WorkshopDownloadTaskStatus.Failed -> true
        WorkshopDownloadTaskStatus.Completed,
        WorkshopDownloadTaskStatus.Cancelled,
        null -> false
    }

    private fun resolveRecordState(
        record: WorkshopInstalledModRecord,
        remoteUpdatedAtMillis: Long,
    ): WorkshopResolvedModState {
        if (remoteUpdatedAtMillis > 0L &&
            record.updatedAtMillis > 0L &&
            remoteUpdatedAtMillis > record.updatedAtMillis &&
            (
                record.cardState == WorkshopModCardState.ImportedPatched ||
                    record.cardState == WorkshopModCardState.ImportedUnpatched ||
                    record.cardState == WorkshopModCardState.TexturePackInstalled
                )
        ) {
            return WorkshopResolvedModState(WorkshopResolvedModStateKind.UpdateAvailable, "发现创意工坊更新")
        }
        return when (record.cardState) {
            WorkshopModCardState.ImportedUnpatched -> WorkshopResolvedModState(
                WorkshopResolvedModStateKind.ImportedUnpatched,
                record.statusText.ifBlank { "等待修补" },
            )
            WorkshopModCardState.ImportedPatched -> WorkshopResolvedModState(
                WorkshopResolvedModStateKind.ImportedPatched,
                record.statusText,
            )
            WorkshopModCardState.Downloading -> WorkshopResolvedModState(
                WorkshopResolvedModStateKind.Downloading,
                record.statusText.ifBlank { "正在下载" },
            )
            WorkshopModCardState.DownloadPaused -> WorkshopResolvedModState(
                WorkshopResolvedModStateKind.DownloadPaused,
                record.statusText.ifBlank { "下载已暂停，可继续" },
            )
            WorkshopModCardState.DownloadFailed -> WorkshopResolvedModState(
                WorkshopResolvedModStateKind.DownloadFailed,
                record.statusText.ifBlank { "下载失败" },
            )
            WorkshopModCardState.NonStandardDownloaded -> WorkshopResolvedModState(
                WorkshopResolvedModStateKind.NonStandardDownloaded,
                record.statusText,
            )
            WorkshopModCardState.TexturePackInstalled -> WorkshopResolvedModState(
                WorkshopResolvedModStateKind.TexturePackInstalled,
                record.statusText.ifBlank { "Texture Replacer 资源包已安装" },
            )
            WorkshopModCardState.UpdateAvailable -> WorkshopResolvedModState(
                WorkshopResolvedModStateKind.UpdateAvailable,
                record.statusText.ifBlank { "发现创意工坊更新" },
            )
            WorkshopModCardState.FileMissing -> WorkshopResolvedModState(
                WorkshopResolvedModStateKind.FileMissing,
                record.statusText.ifBlank { "已下载文件缺失，请重新下载" },
            )
        }
    }
}

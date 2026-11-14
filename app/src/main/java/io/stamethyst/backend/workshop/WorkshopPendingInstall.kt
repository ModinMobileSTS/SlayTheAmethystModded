package io.stamethyst.backend.workshop

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal const val WORKSHOP_UPDATE_PENDING_INSTALL_MESSAGE = "更新已下载，等待安装（原版本已保留）"
internal const val WORKSHOP_UPDATE_PENDING_FILES_MISSING_MESSAGE = "新版下载文件缺失，请重新更新（原版本已保留）"

internal fun WorkshopInstalledModRecord.withPendingInstall(
    details: WorkshopItemDetails,
    artifacts: List<WorkshopDownloadedArtifact>,
    message: String = WORKSHOP_UPDATE_PENDING_INSTALL_MESSAGE,
): WorkshopInstalledModRecord = copy(
    pendingInstall = WorkshopPendingInstall(
        localJarPaths = artifacts.map { it.relativePath }.distinct(),
        updatedAtMillis = details.summary.updatedAtMillis,
        dependencies = details.dependencies,
    ),
    cardState = if (details.summary.updatedAtMillis > updatedAtMillis) {
        WorkshopModCardState.UpdateAvailable
    } else {
        WorkshopModCardState.ImportedPatched
    },
    statusText = message,
)

internal fun WorkshopInstalledModRecord.installationJarPaths(): List<String> =
    pendingInstall?.localJarPaths ?: allLocalJarPaths()

/** Manual retries must use the candidate's version/dependencies, not stamp the old version again. */
internal fun WorkshopInstalledModRecord.installationDetails(): WorkshopItemDetails = WorkshopItemDetails(
    summary = WorkshopItemSummary(
        appId = appId, publishedFileId = publishedFileId, title = title,
        description = description, previewUrl = previewUrl,
        updatedAtMillis = pendingInstall?.updatedAtMillis ?: updatedAtMillis,
    ),
    dependencies = pendingInstall?.dependencies ?: dependencies,
)

internal fun WorkshopInstalledModRecord.pendingInstallFiles(context: Context): List<File> =
    pendingInstall?.localJarPaths.orEmpty().map { path ->
        val file = File(path)
        if (file.isAbsolute) file else RuntimePaths.workshopItemDirs(context, appId, publishedFileId)
            .map { File(it, path) }.firstOrNull { it.isFile }
            ?: File(RuntimePaths.workshopItemDir(context, appId, publishedFileId), path)
    }

/** A restored old installation is not evidence that the new download was successfully installed. */
internal fun WorkshopInstalledModRecord.canCleanDownloadedJars(): Boolean =
    cardState == WorkshopModCardState.ImportedPatched && pendingInstall == null

/** Manual import runs outside the download service but must not be mistaken for a crashed worker. */
internal object WorkshopPendingInstallOperations {
    private val imports = ConcurrentHashMap.newKeySet<ULong>()
    fun started(id: ULong) { imports.add(id) }
    fun finished(id: ULong) { imports.remove(id) }
    fun isActive(id: ULong): Boolean = id in imports
}

internal fun recoverStoppedPendingInstall(
    context: Context,
    metadataStore: WorkshopMetadataStore,
    taskStore: WorkshopDownloadTaskStore,
    task: WorkshopDownloadTaskRecord,
): Boolean {
    if (!task.status.isActiveDownload() || task.status == WorkshopDownloadTaskStatus.Queued ||
        WorkshopDownloadProcessService.isActiveDownload(task.publishedFileId) ||
        WorkshopPendingInstallOperations.isActive(task.publishedFileId)) return false
    val summary = task.details.summary
    if (metadataStore.findByPublishedFileId(summary.appId, task.publishedFileId)?.pendingInstall == null) return false
    return WorkshopInterruptedDownloadRecovery.recoverFinishedTransferIfPossible(context, metadataStore, taskStore, task)
}

package io.stamethyst.ui.workshop

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopDownloadProcessService
import io.stamethyst.backend.workshop.WorkshopDownloadTaskRecord
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStore
import io.stamethyst.backend.workshop.WorkshopInterruptedDownloadRecovery
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopMetadataStore
import io.stamethyst.backend.workshop.WorkshopModCardState
import io.stamethyst.backend.workshop.recoverStoppedPendingInstall
import io.stamethyst.backend.workshop.WorkshopPendingInstallOperations
import io.stamethyst.backend.workshop.isActiveDownload
import io.stamethyst.backend.workshop.isRunningDownload
import io.stamethyst.backend.workshop.shouldRecoverInterruptedDownload
import io.stamethyst.backend.workshop.shouldShowOnLauncherCards

internal object WorkshopDownloadCenterStore {
    private const val ACTIVE_DOWNLOAD_RECOVERY_GRACE_MS = 30_000L

    val tasks = mutableStateListOf<WorkshopDownloadTaskUi>()
    val taskStatuses = mutableStateMapOf<ULong, WorkshopDownloadTaskStatus>()
    private val initLock = Any()
    private var store: WorkshopDownloadTaskStore? = null
    private var appContext: Context? = null
    private var recoveredInterruptedDownloads = false
    private var recoveredLauncherVisibleInterruptedDownloads = false

    fun initialize(context: Context) {
        ensureStore(context)
    }

    fun loadTasksWithRecovery(context: Context): List<WorkshopDownloadTaskUi> {
        ensureStore(context)
        recoverInterruptedDownloadsIfNeeded(context)
        val tasks = loadTasks()
        return if (recoverStoppedPendingInstalls(context, tasks)) loadTasks() else tasks
    }

    fun loadLauncherCardTasksWithRecovery(context: Context): List<WorkshopDownloadTaskUi> {
        ensureStore(context)
        recoverLauncherVisibleInterruptedDownloadsIfNeeded(context)
        val tasks = loadLauncherCardTasks()
        return if (recoverStoppedPendingInstalls(context, tasks)) loadLauncherCardTasks() else tasks
    }

    fun refresh() {
        replaceInMemory(loadTasks())
    }

    fun loadTasks(): List<WorkshopDownloadTaskUi> {
        val records = store?.list().orEmpty()
        val context = appContext
        return records.map { it.toUi(context) }
    }

    fun loadLauncherCardTasks(): List<WorkshopDownloadTaskUi> {
        val records = store?.listLauncherVisible().orEmpty()
        val context = appContext
        return records.map { it.toUi(context) }
    }

    fun replaceInMemory(loadedTasks: List<WorkshopDownloadTaskUi>) {
        replaceInMemory(loadedTasks, preserveExistingFinishedTasks = false)
    }

    fun replaceLauncherCardTasksInMemory(loadedTasks: List<WorkshopDownloadTaskUi>) {
        replaceInMemory(loadedTasks, preserveExistingFinishedTasks = true)
    }

    private fun replaceInMemory(
        loadedTasks: List<WorkshopDownloadTaskUi>,
        preserveExistingFinishedTasks: Boolean,
    ) {
        val nextTasks = (if (preserveExistingFinishedTasks) {
            mergePreservingFinishedTasks(loadedTasks)
        } else {
            loadedTasks
        }).sortedByDescending { it.updatedAtMillis }
        replaceTaskStatuses(nextTasks)
        if (tasks == nextTasks) return
        tasks.clear()
        tasks.addAll(nextTasks)
    }

    private fun mergePreservingFinishedTasks(loadedTasks: List<WorkshopDownloadTaskUi>): List<WorkshopDownloadTaskUi> {
        if (tasks.isEmpty()) return loadedTasks
        val loadedIds = loadedTasks.mapTo(LinkedHashSet()) { task -> task.publishedFileId }
        val preservedFinishedTasks = tasks.filter { task ->
            task.publishedFileId !in loadedIds && !task.status.shouldShowOnLauncherCards()
        }
        if (preservedFinishedTasks.isEmpty()) return loadedTasks
        return loadedTasks + preservedFinishedTasks
    }

    private fun replaceTaskStatuses(loadedTasks: List<WorkshopDownloadTaskUi>) {
        val loadedStatuses = loadedTasks.associate { it.publishedFileId to it.status }
        taskStatuses.keys.toList().forEach { publishedFileId ->
            if (publishedFileId !in loadedStatuses) {
                taskStatuses.remove(publishedFileId)
            }
        }
        loadedStatuses.forEach { (publishedFileId, status) ->
            if (taskStatuses[publishedFileId] != status) {
                taskStatuses[publishedFileId] = status
            }
        }
    }

    fun upsert(task: WorkshopDownloadTaskUi) {
        upsertInMemory(task)
        store?.upsert(task.toRecord())
    }

    fun persistUpsert(task: WorkshopDownloadTaskUi) {
        store?.upsert(task.toRecord())
    }

    fun upsertInMemory(task: WorkshopDownloadTaskUi) {
        taskStatuses[task.publishedFileId] = task.status
        val index = tasks.indexOfFirst { it.publishedFileId == task.publishedFileId }
        if (index >= 0 && tasks[index] == task) return
        if (index >= 0) tasks.removeAt(index)
        val insertionIndex = tasks.indexOfFirst { it.updatedAtMillis <= task.updatedAtMillis }
        tasks.add(if (insertionIndex >= 0) insertionIndex else tasks.size, task)
    }

    fun updateInMemory(
        publishedFileId: ULong,
        transform: (WorkshopDownloadTaskUi) -> WorkshopDownloadTaskUi,
    ): WorkshopDownloadTaskUi? {
        val index = tasks.indexOfFirst { it.publishedFileId == publishedFileId }
        if (index < 0) return null
        val updatedTask = transform(tasks[index])
        upsertInMemory(updatedTask)
        return updatedTask
    }

    fun removeInMemory(publishedFileId: ULong) {
        taskStatuses.remove(publishedFileId)
        tasks.removeAll { it.publishedFileId == publishedFileId }
    }

    fun update(publishedFileId: ULong, transform: (WorkshopDownloadTaskUi) -> WorkshopDownloadTaskUi) {
        val updatedTask = updateInMemory(publishedFileId, transform)
        if (updatedTask != null) {
            store?.upsert(updatedTask.toRecord())
            return
        }
        store?.update(publishedFileId) { record -> transform(record.toUi(appContext)).toRecord() }
    }

    fun persistUpdate(publishedFileId: ULong, transform: (WorkshopDownloadTaskUi) -> WorkshopDownloadTaskUi) {
        store?.update(publishedFileId) { record -> transform(record.toUi(appContext)).toRecord() }
    }

    fun remove(publishedFileId: ULong) {
        removeInMemory(publishedFileId)
        store?.remove(publishedFileId)
    }

    fun persistRemove(publishedFileId: ULong) {
        store?.remove(publishedFileId)
    }

    fun find(publishedFileId: ULong): WorkshopDownloadTaskUi? = tasks.firstOrNull { it.publishedFileId == publishedFileId }

    fun hasRunningTask(): Boolean = tasks.any { it.status.isRunningDownload() }

    fun nextQueuedTask(): WorkshopDownloadTaskUi? = tasks
        .filter { it.status == WorkshopDownloadTaskStatus.Queued }
        .minByOrNull { it.updatedAtMillis }

    private fun ensureStore(context: Context): WorkshopDownloadTaskStore {
        val applicationContext = context.applicationContext
        return synchronized(initLock) {
            appContext = applicationContext
            store ?: WorkshopDownloadTaskStore(applicationContext).also { store = it }
        }
    }

    private fun recoverInterruptedDownloadsIfNeeded(context: Context) {
        val shouldRecover = synchronized(initLock) {
            if (recoveredInterruptedDownloads) {
                false
            } else {
                recoveredInterruptedDownloads = true
                true
            }
        }
        if (!shouldRecover) return
        try {
            recoverInterruptedDownloads(context)
        } catch (error: Throwable) {
            synchronized(initLock) {
                recoveredInterruptedDownloads = false
            }
            throw error
        }
    }

    private fun recoverInterruptedDownloads(context: Context) {
        val taskStore = store ?: return
        val metadataStore = WorkshopMetadataStore(context)
        val now = System.currentTimeMillis()
        taskStore.list().forEach { task ->
            if (task.shouldRecoverInterrupted(context, now)) {
                WorkshopInterruptedDownloadRecovery.recoverFinishedTransferIfPossible(
                    context = context,
                    metadataStore = metadataStore,
                    taskStore = taskStore,
                    task = task,
                )
            }
        }
        val recovered = store?.recoverInterruptedTasksWithResult { task ->
            task.shouldRecoverInterrupted(context, now)
        }.orEmpty()
        if (recovered.isEmpty()) return
        recovered.forEach { task ->
            if (WorkshopInterruptedDownloadRecovery.recoverFinishedTransferIfPossible(
                    context = context,
                    metadataStore = metadataStore,
                    taskStore = taskStore,
                    task = task,
                )
            ) {
                return@forEach
            }
            val summary = task.details.summary
            metadataStore.updateState(
                appId = summary.appId,
                publishedFileId = summary.publishedFileId,
                state = WorkshopModCardState.DownloadPaused,
                statusText = task.message.ifBlank { context.getString(R.string.workshop_download_task_message_paused) },
            )
        }
    }

    private fun recoverLauncherVisibleInterruptedDownloadsIfNeeded(context: Context) {
        val shouldRecover = synchronized(initLock) {
            if (recoveredInterruptedDownloads || recoveredLauncherVisibleInterruptedDownloads) {
                false
            } else {
                recoveredLauncherVisibleInterruptedDownloads = true
                true
            }
        }
        if (!shouldRecover) return
        try {
            recoverLauncherVisibleInterruptedDownloads(context)
        } catch (error: Throwable) {
            synchronized(initLock) {
                recoveredLauncherVisibleInterruptedDownloads = false
            }
            throw error
        }
    }

    private fun recoverLauncherVisibleInterruptedDownloads(context: Context) {
        val taskStore = store ?: return
        val metadataStore = WorkshopMetadataStore(context)
        val now = System.currentTimeMillis()
        val visibleTasks = taskStore.listLauncherVisible()
        visibleTasks.forEach { task ->
            if (task.shouldRecoverInterrupted(context, now)) {
                WorkshopInterruptedDownloadRecovery.recoverFinishedTransferIfPossible(
                    context = context,
                    metadataStore = metadataStore,
                    taskStore = taskStore,
                    task = task,
                )
            }
        }
        val recovered = taskStore.recoverLauncherVisibleInterruptedTasksWithResult { task ->
            task.shouldRecoverInterrupted(context, now)
        }
        if (recovered.isEmpty()) return
        recovered.forEach { task ->
            if (WorkshopInterruptedDownloadRecovery.recoverFinishedTransferIfPossible(
                    context = context,
                    metadataStore = metadataStore,
                    taskStore = taskStore,
                    task = task,
                )
            ) {
                return@forEach
            }
            val summary = task.details.summary
            metadataStore.updateState(
                appId = summary.appId,
                publishedFileId = summary.publishedFileId,
                state = WorkshopModCardState.DownloadPaused,
                statusText = task.message.ifBlank { context.getString(R.string.workshop_download_task_message_paused) },
            )
        }
    }

    private fun WorkshopDownloadTaskRecord.shouldRecoverInterrupted(context: Context, now: Long): Boolean {
        return shouldRecoverInterruptedDownload(
            nowMillis = now,
            isActiveDownload = WorkshopDownloadProcessService.isActiveDownload(context, publishedFileId) ||
                WorkshopPendingInstallOperations.isActive(publishedFileId),
            activeDownloadRecoveryGraceMs = ACTIVE_DOWNLOAD_RECOVERY_GRACE_MS,
        )
    }

    private fun recoverStoppedPendingInstalls(context: Context, tasks: List<WorkshopDownloadTaskUi>): Boolean {
        val taskStore = store ?: return false
        val candidates = tasks.filter { task ->
            task.status.isActiveDownload() && task.status != WorkshopDownloadTaskStatus.Queued
        }
        if (candidates.isEmpty()) return false
        val metadataStore = WorkshopMetadataStore(context)
        var changed = false
        candidates.forEach { task ->
            changed = recoverStoppedPendingInstall(context, metadataStore, taskStore, task.toRecord()) || changed
        }
        return changed
    }
}

internal data class WorkshopDownloadTaskUi(
    val publishedFileId: ULong,
    val title: String,
    val status: WorkshopDownloadTaskStatus,
    val message: String,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val details: WorkshopItemDetails,
    val previewUrl: String = details.summary.previewUrl,
    val description: String = details.summary.description,
    val authorName: String = details.summary.authorName,
    val fileSizeBytes: Long = details.summary.fileSizeBytes,
    val progressPercent: Int? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = details.summary.fileSizeBytes.takeIf { it > 0L },
    val completedFiles: Int? = null,
    val totalFiles: Int? = null,
    val completedChunks: Int? = null,
    val totalChunks: Int? = null,
    val errorClass: String = "",
    val errorMessage: String = "",
    val errorStackTrace: String = "",
    val downloadLog: String = "",
    val preservePartialDownload: Boolean = false,
)

private fun WorkshopDownloadTaskRecord.toUi(context: Context?): WorkshopDownloadTaskUi {
    val normalizedStatus = if (
        status == WorkshopDownloadTaskStatus.Paused &&
        context != null &&
        WorkshopDownloadProcessService.isActiveDownload(context, publishedFileId)
    ) {
        WorkshopDownloadTaskStatus.Downloading
    } else {
        status
    }
    val normalizedMessage = if (normalizedStatus != status) {
        context?.getString(R.string.workshop_download_task_message_downloading) ?: "正在下载"
    } else {
        message
    }
    return WorkshopDownloadTaskUi(
        publishedFileId = publishedFileId,
        title = title,
        status = normalizedStatus,
        message = normalizedMessage,
        updatedAtMillis = updatedAtMillis,
        details = details,
        previewUrl = previewUrl,
        description = description,
        authorName = authorName,
        fileSizeBytes = fileSizeBytes,
        progressPercent = progressPercent,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        completedFiles = completedFiles,
        totalFiles = totalFiles,
        completedChunks = completedChunks,
        totalChunks = totalChunks,
        errorClass = errorClass,
        errorMessage = errorMessage,
        errorStackTrace = errorStackTrace,
        downloadLog = downloadLog,
        preservePartialDownload = preservePartialDownload,
    )
}

internal fun WorkshopDownloadTaskUi.toRecord(): WorkshopDownloadTaskRecord = WorkshopDownloadTaskRecord(
    publishedFileId = publishedFileId,
    title = title,
    status = status,
    message = message,
    updatedAtMillis = updatedAtMillis,
    details = details,
    previewUrl = previewUrl,
    description = description,
    authorName = authorName,
    fileSizeBytes = fileSizeBytes,
    progressPercent = progressPercent,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    completedFiles = completedFiles,
    totalFiles = totalFiles,
    completedChunks = completedChunks,
    totalChunks = totalChunks,
    errorClass = errorClass,
    errorMessage = errorMessage,
    errorStackTrace = errorStackTrace,
    downloadLog = downloadLog,
    preservePartialDownload = preservePartialDownload,
)

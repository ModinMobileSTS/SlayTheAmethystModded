package io.stamethyst.backend.workshop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import androidx.core.app.NotificationCompat
import io.stamethyst.R
import io.stamethyst.LauncherActivity
import io.stamethyst.ui.main.NewlyImportedModHighlightStore
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import top.apricityx.workshop.workshop.WorkshopSteamRateLimitedException

class WorkshopDownloadProcessService : Service() {
    companion object {
        const val ACTION_DOWNLOAD = "io.stamethyst.action.WORKSHOP_DOWNLOAD"
        const val ACTION_PAUSE = "io.stamethyst.action.WORKSHOP_DOWNLOAD_PAUSE"
        const val ACTION_CANCEL = "io.stamethyst.action.WORKSHOP_DOWNLOAD_CANCEL"

        const val EXTRA_RESULT_RECEIVER = "io.stamethyst.extra.RESULT_RECEIVER"
        const val EXTRA_APP_ID = "io.stamethyst.extra.APP_ID"
        const val EXTRA_PUBLISHED_FILE_ID = "io.stamethyst.extra.PUBLISHED_FILE_ID"
        const val EXTRA_TITLE = "io.stamethyst.extra.TITLE"
        const val EXTRA_DESCRIPTION = "io.stamethyst.extra.DESCRIPTION"
        const val EXTRA_PREVIEW_URL = "io.stamethyst.extra.PREVIEW_URL"
        const val EXTRA_AUTHOR_NAME = "io.stamethyst.extra.AUTHOR_NAME"
        const val EXTRA_FILE_SIZE_BYTES = "io.stamethyst.extra.FILE_SIZE_BYTES"
        const val EXTRA_UPDATED_AT_MILLIS = "io.stamethyst.extra.UPDATED_AT_MILLIS"
        const val EXTRA_DOWNLOAD_COUNT = "io.stamethyst.extra.DOWNLOAD_COUNT"
        const val EXTRA_FILE_URL = "io.stamethyst.extra.FILE_URL"
        const val EXTRA_HCONTENT_FILE = "io.stamethyst.extra.HCONTENT_FILE"
        const val EXTRA_DEPOT_ID = "io.stamethyst.extra.DEPOT_ID"
        const val EXTRA_JSON_METADATA = "io.stamethyst.extra.JSON_METADATA"
        const val EXTRA_DEPENDENCIES = "io.stamethyst.extra.DEPENDENCIES"
        const val EXTRA_MESSAGE = "io.stamethyst.extra.MESSAGE"
        const val EXTRA_TASK_STATUS = "io.stamethyst.extra.TASK_STATUS"
        const val EXTRA_WRITTEN_BYTES = "io.stamethyst.extra.WRITTEN_BYTES"
        const val EXTRA_TOTAL_BYTES = "io.stamethyst.extra.TOTAL_BYTES"
        const val EXTRA_PROGRESS_PERCENT = "io.stamethyst.extra.PROGRESS_PERCENT"
        const val EXTRA_COMPLETED_FILES = "io.stamethyst.extra.COMPLETED_FILES"
        const val EXTRA_TOTAL_FILES = "io.stamethyst.extra.TOTAL_FILES"
        const val EXTRA_COMPLETED_CHUNKS = "io.stamethyst.extra.COMPLETED_CHUNKS"
        const val EXTRA_TOTAL_CHUNKS = "io.stamethyst.extra.TOTAL_CHUNKS"
        const val EXTRA_ERROR_CLASS = "io.stamethyst.extra.ERROR_CLASS"
        const val EXTRA_ERROR_MESSAGE = "io.stamethyst.extra.ERROR_MESSAGE"
        const val EXTRA_ERROR_STACKTRACE = "io.stamethyst.extra.ERROR_STACKTRACE"

        const val RESULT_PROGRESS = 1
        const val RESULT_COMPLETED = 2
        const val RESULT_FAILURE = 3
        const val RESULT_PAUSED = 4
        const val RESULT_CANCELLED = 5
        private const val CHANNEL_ID = "workshop_download"
        private const val NOTIFICATION_ID = 646570
        private const val ACTIVE_MARKER_STALE_MS = 30_000L
        private const val ACTIVE_MARKER_HEARTBEAT_MS = 5_000L
        private const val ACTIVE_MARKER_DIR = "workshop/active_downloads"

        @Volatile
        private var activeDownloadKeySnapshot: Set<String> = emptySet()

        fun isActiveDownload(publishedFileId: ULong): Boolean {
            return activeDownloadKeySnapshot.any { it.substringAfter(':') == publishedFileId.toString() }
        }

        fun isActiveDownload(context: Context, publishedFileId: ULong): Boolean {
            if (isActiveDownload(publishedFileId)) return true
            val markerFile = activeDownloadMarkerFile(context, publishedFileId)
            if (!markerFile.isFile) return false
            return System.currentTimeMillis() - markerFile.lastModified() <= ACTIVE_MARKER_STALE_MS
        }

        private fun activeDownloadMarkerFile(context: Context, publishedFileId: ULong): File =
            File(context.applicationContext.filesDir, "$ACTIVE_MARKER_DIR/$publishedFileId.active")

        fun start(context: Context, details: WorkshopItemDetails, receiver: ResultReceiver? = null) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, WorkshopDownloadProcessService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_RESULT_RECEIVER, receiver)
                putExtra(EXTRA_APP_ID, details.summary.appId.toString())
                putExtra(EXTRA_PUBLISHED_FILE_ID, details.summary.publishedFileId.toString())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }

        fun start(context: Context, summary: WorkshopItemSummary, receiver: ResultReceiver? = null) {
            start(context, WorkshopItemDetails(summary = summary), receiver)
        }

        fun startNextQueued(context: Context) {
            val taskStore = WorkshopDownloadTaskStore(context)
            taskStore.claimNextQueuedTasks(availableDownloadSlots(context)).forEach { task ->
                runCatching { start(context, task.details, null) }.onFailure { error ->
                    taskStore.update(task.publishedFileId) {
                        it.copy(
                            status = WorkshopDownloadTaskStatus.Failed,
                            message = "启动下载服务失败：${error.message ?: error.javaClass.simpleName}",
                            errorClass = error.javaClass.name,
                            errorMessage = error.message ?: error.javaClass.simpleName,
                            errorStackTrace = error.stackTraceToString(),
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                    }
                }
            }
        }

        private fun availableDownloadSlots(context: Context): Int {
            val limit = LauncherPreferences.readWorkshopMaxConcurrentDownloads(context)
            val persistedRunningCount = WorkshopDownloadTaskStore(context).list().count { it.status.isRunningDownload() }
            val runningCount = maxOf(activeDownloadKeySnapshot.size, activeDownloadMarkerCount(context), persistedRunningCount)
            return (limit - runningCount).coerceAtLeast(0)
        }

        private fun activeDownloadMarkerCount(context: Context): Int {
            val now = System.currentTimeMillis()
            val markerDir = File(context.applicationContext.filesDir, ACTIVE_MARKER_DIR)
            return markerDir.listFiles()
                .orEmpty()
                .count { file -> file.isFile && file.name.endsWith(".active") && now - file.lastModified() <= ACTIVE_MARKER_STALE_MS }
        }

        fun pause(context: Context, appId: UInt, publishedFileId: ULong, receiver: ResultReceiver? = null) {
            control(context, ACTION_PAUSE, appId, publishedFileId, receiver)
        }

        fun cancel(context: Context, appId: UInt, publishedFileId: ULong, receiver: ResultReceiver? = null) {
            control(context, ACTION_CANCEL, appId, publishedFileId, receiver)
        }

        private fun control(context: Context, action: String, appId: UInt, publishedFileId: ULong, receiver: ResultReceiver?) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, WorkshopDownloadProcessService::class.java).apply {
                this.action = action
                putExtra(EXTRA_APP_ID, appId.toString())
                putExtra(EXTRA_PUBLISHED_FILE_ID, publishedFileId.toString())
                putExtra(EXTRA_RESULT_RECEIVER, receiver)
            }
            appContext.startService(intent)
        }
    }

    @Volatile
    private var workerThreads: MutableMap<String, Thread> = ConcurrentHashMap()

    private val requestedStops: MutableMap<String, StopReason> = ConcurrentHashMap()
    private val activeServices: MutableMap<String, WorkshopService> = ConcurrentHashMap()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val safeIntent = intent ?: return START_NOT_STICKY
        if (safeIntent.action == ACTION_PAUSE || safeIntent.action == ACTION_CANCEL) {
            handleControlIntent(safeIntent)
            return START_NOT_STICKY
        }
        if (safeIntent.action != ACTION_DOWNLOAD) return START_NOT_STICKY

        val receiver = extractResultReceiver(safeIntent)
        startForeground(NOTIFICATION_ID, buildNotification("准备下载"))
        val requestedDownloadKey = safeIntent.downloadKeyOrNull()
        if (requestedDownloadKey != null && workerThreads.containsKey(requestedDownloadKey)) {
            startForeground(NOTIFICATION_ID, buildNotification("下载正在进行"))
            receiver?.send(RESULT_PROGRESS, Bundle().apply {
                putString(EXTRA_MESSAGE, "下载已在后台进行中")
                putString(EXTRA_TASK_STATUS, "Downloading")
            })
            return START_NOT_STICKY
        }
        if (workerThreads.size >= LauncherPreferences.readWorkshopMaxConcurrentDownloads(applicationContext)) {
            safeIntent.publishedFileIdOrNull()?.let { publishedFileId ->
                val deferredTaskStore = WorkshopDownloadTaskStore(applicationContext)
                val deferredTask = deferredTaskStore.find(publishedFileId)
                if (deferredTask != null && WorkshopDownloadBlocklist.isBlocked(deferredTask.details.summary)) {
                    cancelBlockedTask(deferredTaskStore, deferredTask, receiver)
                    if (workerThreads.isEmpty()) {
                        stopForegroundCompat()
                        stopSelf()
                    }
                    return START_NOT_STICKY
                }
                deferredTaskStore.update(publishedFileId) { task ->
                    if (task.status == WorkshopDownloadTaskStatus.Resolving) {
                        task.copy(
                            status = WorkshopDownloadTaskStatus.Queued,
                            message = "等待下载",
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                    } else {
                        task
                    }
                }
            }
            receiver?.send(RESULT_PROGRESS, Bundle().apply {
                putString(EXTRA_MESSAGE, "等待下载")
                putString(EXTRA_TASK_STATUS, "Queued")
            })
            if (workerThreads.isEmpty()) {
                stopForegroundCompat()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val startingTaskStore = WorkshopDownloadTaskStore(applicationContext)
        val requestedPublishedFileId = safeIntent.publishedFileIdOrNull()
        if (requestedPublishedFileId == null) {
            if (workerThreads.isEmpty()) {
                stopForegroundCompat()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val startingTask = startingTaskStore.find(requestedPublishedFileId)
        if (startingTask == null) {
            if (workerThreads.isEmpty()) {
                stopForegroundCompat()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (startingTaskStore.isMarkedDeleted(startingTask.publishedFileId) || !startingTask.status.canStartDownload()) {
            if (workerThreads.isEmpty()) {
                stopForegroundCompat()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (WorkshopDownloadBlocklist.isBlocked(startingTask.details.summary)) {
            cancelBlockedTask(startingTaskStore, startingTask, receiver)
            if (workerThreads.isEmpty()) {
                stopForegroundCompat()
                stopSelf()
            }
            startNextQueued(applicationContext)
            return START_NOT_STICKY
        }
        val rateLimitStore = WorkshopSteamRateLimitStore(applicationContext)
        if (rateLimitStore.remainingMillis() > 0L) {
            pauseForSteamRateLimit(
                taskStore = startingTaskStore,
                metadataStore = WorkshopMetadataStore(applicationContext),
                details = startingTask.details,
                message = rateLimitStore.cooldownMessage(),
                receiver = receiver,
            )
            if (workerThreads.isEmpty()) {
                stopForegroundCompat()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val startingDetails = startingTask.details
        startingTaskStore.update(startingDetails.summary.publishedFileId) {
            it.copy(
                status = WorkshopDownloadTaskStatus.Resolving,
                message = "正在解析下载内容",
                updatedAtMillis = System.currentTimeMillis(),
                downloadLog = buildInitialDownloadLog(startingDetails),
            )
        }
        startingTaskStore.appendLog(startingDetails.summary.publishedFileId, "服务已启动，准备解析下载内容")
        val activeDownloadKey = requireNotNull(requestedDownloadKey)
        requestedStops.remove(activeDownloadKey)
        touchActiveDownloadMarker(startingDetails.summary.publishedFileId)
        val thread = Thread({ runDownload(startId, safeIntent, receiver) }, "STS-WorkshopDownload-$activeDownloadKey")
        workerThreads[activeDownloadKey] = thread
        activeDownloadKeySnapshot = workerThreads.keys.toSet()
        thread.start()
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        val threads = workerThreads.values.toList()
        activeServices.values.forEach { it.cancelActiveCalls() }
        activeServices.clear()
        workerThreads.clear()
        activeDownloadKeySnapshot = emptySet()
        threads.forEach { thread ->
            thread.interrupt()
        }
        super.onDestroy()
    }

    private fun handleControlIntent(intent: Intent) {
        val requestedDownloadKey = intent.downloadKeyOrNull() ?: return
        val stopReason = if (intent.action == ACTION_PAUSE) StopReason.Pause else StopReason.Cancel
        val thread = workerThreads[requestedDownloadKey]
        if (thread == null) {
            handleControlWithoutWorker(intent, stopReason)
            return
        }
        requestedStops[requestedDownloadKey] = stopReason
        activeServices[requestedDownloadKey]?.cancelActiveCalls()
        thread.interrupt()
    }

    private fun handleControlWithoutWorker(intent: Intent, stopReason: StopReason) {
        val appId = intent.getStringExtra(EXTRA_APP_ID)?.toUIntOrNull() ?: return
        val publishedFileId = intent.publishedFileIdOrNull() ?: return
        val taskStore = WorkshopDownloadTaskStore(applicationContext)
        when (stopReason) {
            StopReason.Pause -> {
                taskStore.update(publishedFileId) {
                    it.copy(
                        status = WorkshopDownloadTaskStatus.Paused,
                        message = "下载已暂停",
                        updatedAtMillis = System.currentTimeMillis(),
                        preservePartialDownload = true,
                    )
                }
                WorkshopMetadataStore(applicationContext).updateState(
                    appId = appId,
                    publishedFileId = publishedFileId,
                    state = WorkshopModCardState.DownloadPaused,
                    statusText = "下载已暂停",
                )
            }
            StopReason.Cancel -> {
                taskStore.removeAndMarkDeleted(publishedFileId)
                WorkshopMetadataStore(applicationContext).remove(appId, publishedFileId)
                RuntimePaths.workshopItemDirs(applicationContext, appId, publishedFileId)
                    .forEach { it.deleteRecursively() }
            }
        }
    }

    private fun runDownload(startId: Int, intent: Intent, receiver: ResultReceiver?) {
        val metadataStore = WorkshopMetadataStore(applicationContext)
        val taskStore = WorkshopDownloadTaskStore(applicationContext)
        val service = WorkshopService(applicationContext)
        val downloadKey = requireNotNull(intent.downloadKeyOrNull())
        activeServices[downloadKey] = service
        val publishedFileId = intent.publishedFileIdOrNull()
        val loadedDetails = publishedFileId?.takeUnless(taskStore::isMarkedDeleted)?.let { taskStore.find(it)?.details }
        if (loadedDetails == null) {
            workerThreads.remove(downloadKey)
            activeServices.remove(downloadKey)
            requestedStops.remove(downloadKey)
            activeDownloadKeySnapshot = workerThreads.keys.toSet()
            if (workerThreads.isEmpty()) {
                stopForegroundCompat()
                stopSelf()
            }
            startNextQueued(applicationContext)
            return
        }
        var details = loadedDetails
        val activeDownloadHeartbeat = startActiveDownloadHeartbeat(details.summary.publishedFileId, downloadKey)
        val initialTask = taskStore.find(details.summary.publishedFileId)
        val preservePartialDownload = initialTask?.shouldPreservePartialDownload() == true
        var existingRecord: WorkshopInstalledModRecord? = null
        try {
            val previousRecord = metadataStore.findByPublishedFileId(
                appId = details.summary.appId,
                publishedFileId = details.summary.publishedFileId,
            )
            existingRecord = previousRecord?.restoreCandidateForInterruptedUpdate()
            val queuedMessage = if (existingRecord != null) "等待更新" else "等待下载"
            metadataStore.upsert(
                (previousRecord ?: WorkshopInstalledModRecord(
                    appId = details.summary.appId,
                    publishedFileId = details.summary.publishedFileId,
                    title = details.summary.title,
                    description = details.summary.description,
                    previewUrl = details.summary.previewUrl,
                    versionText = details.summary.updatedAtMillis.toString(),
                    updatedAtMillis = details.summary.updatedAtMillis,
                    installedAtMillis = System.currentTimeMillis(),
                    localJarPath = "",
                    dependencies = details.dependencies,
                )).copy(
                    title = details.summary.title,
                    description = details.summary.description,
                    previewUrl = details.summary.previewUrl,
                    cardState = WorkshopModCardState.Downloading,
                    statusText = queuedMessage,
                    dependencies = details.dependencies,
                )
            )
            taskStore.update(details.summary.publishedFileId) {
                it.copy(
                    status = WorkshopDownloadTaskStatus.Resolving,
                    message = queuedMessage,
                    updatedAtMillis = System.currentTimeMillis(),
                    progressPercent = if (preservePartialDownload) it.progressPercent else null,
                    downloadedBytes = if (preservePartialDownload) it.downloadedBytes else 0L,
                    completedFiles = if (preservePartialDownload) it.completedFiles else null,
                    completedChunks = if (preservePartialDownload) it.completedChunks else null,
                    errorClass = "",
                    errorMessage = "",
                    errorStackTrace = "",
                    preservePartialDownload = preservePartialDownload,
                )
            }
            taskStore.appendLog(details.summary.publishedFileId, queuedMessage)
            sendProgress(receiver, queuedMessage, "Resolving")
            runBlocking {
                if (!details.hasDownloadSource()) {
                    taskStore.update(details.summary.publishedFileId) {
                        it.copy(status = WorkshopDownloadTaskStatus.Resolving, message = "正在解析下载内容", updatedAtMillis = System.currentTimeMillis())
                    }
                    taskStore.appendLog(details.summary.publishedFileId, "正在通过 Steam 创意工坊解析下载内容")
                    sendProgress(receiver, "正在解析下载内容", "Resolving")
                    details = service.getDetails(
                        appId = details.summary.appId,
                        publishedFileId = details.summary.publishedFileId,
                        fallbackSummary = details.summary,
                        includeCommunityData = false,
                        includeDependencyData = false,
                    )
                    taskStore.update(details.summary.publishedFileId) { it.copy(details = details) }
                    taskStore.appendLog(
                        details.summary.publishedFileId,
                        "解析完成：fileUrl=${details.fileUrl?.takeIf(String::isNotBlank) ?: "<empty>"}, hcontentFile=${details.hcontentFile ?: "<empty>"}, depotId=${details.depotId ?: "<empty>"}",
                    )
                }
                downloadPreviewImageInBackground(service, metadataStore, details)
                val outputDir = RuntimePaths.workshopItemDir(
                    applicationContext,
                    details.summary.appId,
                    details.summary.publishedFileId,
                )
                if (preservePartialDownload) {
                    taskStore.appendLog(details.summary.publishedFileId, "保留部分下载文件，尝试断点续传")
                } else {
                    cleanDownloadedContent(outputDir)
                }
                taskStore.appendLog(details.summary.publishedFileId, "输出目录：${outputDir.absolutePath}")
                var lastProgressPersistAtMillis = 0L
                var lastProgressPercent: Int? = null
                var lastProgressCompletedFiles: Int? = null
                var lastProgressCompletedChunks: Int? = null
                var lastProgressLogAtMillis = 0L
                var lastProgressLogPercent: Int? = null
                service.download(WorkshopDownloadRequest(details, outputDir)).collect { event ->
                    when (event) {
                        WorkshopDownloadEvent.Ignored -> Unit
                        is WorkshopDownloadEvent.Log -> {
                            taskStore.appendLog(details.summary.publishedFileId, event.message)
                            sendProgress(receiver, event.message, null)
                        }
                        is WorkshopDownloadEvent.Progress -> {
                            if (taskStore.isMarkedDeleted(details.summary.publishedFileId)) return@collect
                            val now = System.currentTimeMillis()
                            val progressPercent = event.progress.progressPercent()
                            if (!event.progress.shouldPersist(
                                    nowMillis = now,
                                    lastPersistAtMillis = lastProgressPersistAtMillis,
                                    lastPercent = lastProgressPercent,
                                    lastCompletedFiles = lastProgressCompletedFiles,
                                    lastCompletedChunks = lastProgressCompletedChunks,
                                )
                            ) {
                                return@collect
                            }
                            taskStore.update(details.summary.publishedFileId) {
                                it.copy(
                                    status = WorkshopDownloadTaskStatus.Downloading,
                                    message = "正在下载",
                                    progressPercent = progressPercent,
                                    downloadedBytes = event.progress.writtenBytes,
                                    totalBytes = event.progress.totalBytes ?: it.totalBytes,
                                    completedFiles = event.progress.completedFiles ?: it.completedFiles,
                                    totalFiles = event.progress.totalFiles ?: it.totalFiles,
                                    completedChunks = event.progress.completedChunks ?: it.completedChunks,
                                    totalChunks = event.progress.totalChunks ?: it.totalChunks,
                                    updatedAtMillis = now,
                                )
                            }
                            if (event.progress.shouldLog(
                                    percent = progressPercent,
                                    nowMillis = now,
                                    lastLogAtMillis = lastProgressLogAtMillis,
                                    lastLoggedPercent = lastProgressLogPercent,
                                )
                            ) {
                                taskStore.appendLog(details.summary.publishedFileId, event.progress.downloadLogLine())
                                lastProgressLogAtMillis = now
                                lastProgressLogPercent = progressPercent
                            }
                            lastProgressPersistAtMillis = now
                            lastProgressPercent = progressPercent
                            lastProgressCompletedFiles = event.progress.completedFiles
                            lastProgressCompletedChunks = event.progress.completedChunks
                            sendProgress(receiver, "正在下载", "Downloading", event.progress)
                        }
                        is WorkshopDownloadEvent.StateChanged -> {
                            if (taskStore.isMarkedDeleted(details.summary.publishedFileId)) return@collect
                            val message = event.state.displayText()
                            updateNotification(message)
                            if (event.state == WorkshopDownloadState.Success) {
                                rememberCompletedJarBeforeFinalization(
                                    metadataStore = metadataStore,
                                    taskStore = taskStore,
                                    service = service,
                                    details = details,
                                )
                                val currentTask = taskStore.find(details.summary.publishedFileId)
                                if (currentTask?.status != WorkshopDownloadTaskStatus.Completed) {
                                    val importStageMessage = currentTask?.message
                                        ?.takeIf { it.isNotBlank() }
                                        ?: "下载完成，准备导入修补"
                                    taskStore.update(details.summary.publishedFileId) {
                                        it.copy(
                                            status = WorkshopDownloadTaskStatus.Downloading,
                                            message = importStageMessage,
                                            progressPercent = it.progressPercent ?: 100,
                                            updatedAtMillis = System.currentTimeMillis(),
                                            preservePartialDownload = false,
                                        )
                                    }
                                    metadataStore.updateState(
                                        appId = details.summary.appId,
                                        publishedFileId = details.summary.publishedFileId,
                                        state = WorkshopModCardState.Downloading,
                                        statusText = importStageMessage,
                                    )
                                    sendProgress(
                                        receiver = receiver,
                                        message = importStageMessage,
                                        taskStatus = "Downloading",
                                        progressPercent = currentTask?.progressPercent ?: 100,
                                    )
                                }
                                taskStore.appendLog(details.summary.publishedFileId, "状态变更：$message")
                                return@collect
                            }
                            if (event.state != WorkshopDownloadState.Success) {
                                metadataStore.updateState(
                                    appId = details.summary.appId,
                                    publishedFileId = details.summary.publishedFileId,
                                    state = WorkshopModCardState.Downloading,
                                    statusText = message,
                                )
                            }
                            taskStore.update(details.summary.publishedFileId) {
                                it.copy(
                                    status = event.state.toDownloadTaskStatus(),
                                    message = message,
                                    updatedAtMillis = System.currentTimeMillis(),
                                )
                            }
                            taskStore.appendLog(details.summary.publishedFileId, "状态变更：$message")
                            sendProgress(receiver, message, event.state.toTaskStatusName())
                        }
                        is WorkshopDownloadEvent.Completed -> {
                            if (requestedStops[downloadKey] == StopReason.Cancel || taskStore.isMarkedDeleted(details.summary.publishedFileId) || taskStore.find(details.summary.publishedFileId) == null) {
                                cleanOutput(details)
                                return@collect
                            }
                            val outputDir = RuntimePaths.workshopItemDir(
                                applicationContext,
                                details.summary.appId,
                                details.summary.publishedFileId,
                            )
                            val jarArtifacts = findDownloadedJars(outputDir)
                            val message: String
                            val record = if (jarArtifacts.isNotEmpty()) {
                                val downloadedMessage = if (jarArtifacts.size > 1) {
                                    "下载完成，检测到 ${jarArtifacts.size} 个 jar"
                                } else {
                                    "下载完成"
                                }
                                val autoImportEnabled = initialTask?.forceAutoImport == true ||
                                    LauncherPreferences.isWorkshopAutoImportEnabled(applicationContext)
                                val hasMultipleJarCandidates = jarArtifacts.size > 1
                                val canAutoImportDownloadedJars = autoImportEnabled && !hasMultipleJarCandidates
                                val preserveExistingUntilAutoImportFinishes =
                                    canAutoImportDownloadedJars && existingRecord.hasInstalledJar()
                                val downloadedJarPaths = jarArtifacts.map { it.relativePath }
                                var downloadedRecord = service.createInstalledRecord(details, jarArtifacts.first())
                                    .copy(localJarPaths = downloadedJarPaths)
                                if (hasMultipleJarCandidates) {
                                    downloadedRecord = downloadedRecord.copy(
                                        statusText = "检测到 ${jarArtifacts.size} 个 jar，请选择要导入的文件",
                                    )
                                }
                                if (!preserveExistingUntilAutoImportFinishes) {
                                    metadataStore.upsert(downloadedRecord)
                                }
                                val importStageMessage = when {
                                    canAutoImportDownloadedJars -> "下载完成，准备导入修补"
                                    hasMultipleJarCandidates -> "下载完成，检测到 ${jarArtifacts.size} 个 jar，等待选择导入文件"
                                    else -> "下载完成，等待手动导入修补"
                                }
                                taskStore.update(details.summary.publishedFileId) {
                                    it.copy(
                                        status = WorkshopDownloadTaskStatus.Downloading,
                                        message = importStageMessage,
                                        progressPercent = if (canAutoImportDownloadedJars) 0 else 100,
                                        downloadedBytes = (it.totalBytes ?: it.downloadedBytes).coerceAtLeast(it.downloadedBytes),
                                        completedFiles = it.totalFiles ?: it.completedFiles,
                                        updatedAtMillis = System.currentTimeMillis(),
                                        preservePartialDownload = false,
                                    )
                                }
                                taskStore.appendLog(details.summary.publishedFileId, importStageMessage)
                                val previewImagePath = downloadPreviewImageIfNeeded(service, metadataStore, details)
                                downloadedRecord = downloadedRecord.copy(localPreviewImagePath = previewImagePath)
                                if (!preserveExistingUntilAutoImportFinishes) {
                                    metadataStore.upsert(downloadedRecord)
                                }
                                if (canAutoImportDownloadedJars) {
                                    val jarFiles = jarArtifacts.map { artifact -> File(outputDir, artifact.relativePath) }
                                    var lastAutoImportProgressMessage = ""
                                    when (val importResult = WorkshopAutoImporter.importDownloadedJars(
                                        context = applicationContext,
                                        details = details,
                                        jarFiles = jarFiles,
                                        onProgress = { progress ->
                                            val progressMessage = progress.toDownloadStatusMessage()
                                            taskStore.update(details.summary.publishedFileId) {
                                                it.copy(
                                                    status = WorkshopDownloadTaskStatus.Downloading,
                                                    message = progressMessage,
                                                    progressPercent = progress.percent.coerceIn(0, 100),
                                                    updatedAtMillis = System.currentTimeMillis(),
                                                    preservePartialDownload = false,
                                                )
                                            }
                                            metadataStore.updateState(
                                                appId = details.summary.appId,
                                                publishedFileId = details.summary.publishedFileId,
                                                state = WorkshopModCardState.Downloading,
                                                statusText = progressMessage,
                                            )
                                            if (progressMessage != lastAutoImportProgressMessage) {
                                                taskStore.appendLog(details.summary.publishedFileId, progressMessage)
                                                lastAutoImportProgressMessage = progressMessage
                                            }
                                            sendProgress(
                                                receiver = receiver,
                                                message = progressMessage,
                                                taskStatus = "Downloading",
                                                progressPercent = progress.percent.coerceIn(0, 100),
                                            )
                                        },
                                    )) {
                                        is WorkshopAutoImportResult.Imported -> {
                                            val importedSummary = importResult.formatImportedSummary()
                                            message = "下载完成，已自动导入 $importedSummary"
                                            downloadedRecord.copy(
                                                localJarPath = importResult.storagePath,
                                                localJarPaths = importResult.storagePaths,
                                                cardState = WorkshopModCardState.ImportedPatched,
                                                statusText = "已安装 $importedSummary",
                                            )
                                        }
                                        is WorkshopAutoImportResult.Failed -> {
                                            message = "$downloadedMessage，自动导入失败：${importResult.message}"
                                            if (preserveExistingUntilAutoImportFinishes) {
                                                existingRecord!!.copy(statusText = message)
                                            } else {
                                                downloadedRecord.copy(statusText = "自动导入失败，请手动导入")
                                            }
                                        }
                                    }
                                } else {
                                    message = if (hasMultipleJarCandidates) {
                                        "下载完成，检测到 ${jarArtifacts.size} 个 jar，请在模组页选择要导入的文件"
                                    } else {
                                        downloadedMessage
                                    }
                                    downloadedRecord
                                }
                            } else {
                                val previewImagePath = downloadPreviewImageIfNeeded(service, metadataStore, details)
                                val texturePackDir = TextureReplacerWorkshopInstaller.install(applicationContext, details, outputDir)
                                message = "下载完成，已作为 Texture Replacer 资源包安装并启用"
                                service.createTexturePackRecord(details, texturePackDir)
                                    .copy(localPreviewImagePath = previewImagePath)
                            }
                            if (existingRecord == null) {
                                NewlyImportedModHighlightStore.mark(applicationContext, record.newlyImportedHighlightKeys())
                            }
                            metadataStore.upsert(record)
                            if (jarArtifacts.isNotEmpty() && record.cardState == WorkshopModCardState.ImportedPatched) {
                                cleanDownloadedContent(outputDir)
                            }
                            taskStore.update(details.summary.publishedFileId) {
                                it.copy(
                                    status = WorkshopDownloadTaskStatus.Completed,
                                    message = message,
                                    progressPercent = 100,
                                    updatedAtMillis = System.currentTimeMillis(),
                                    preservePartialDownload = false,
                                )
                            }
                            taskStore.appendLog(details.summary.publishedFileId, message)
                            receiver?.send(
                                RESULT_COMPLETED,
                                Bundle().apply {
                                    putString(EXTRA_MESSAGE, message)
                                    putString(EXTRA_TASK_STATUS, "Completed")
                                }
                            )
                        }
                        is WorkshopDownloadEvent.Failed -> error(
                            buildString {
                                append(event.failure.message)
                                if (event.failure.detail.isNotBlank()) {
                                    append(": ")
                                    append(event.failure.detail)
                                }
                            }
                        )
                    }
                }
            }
        } catch (throwable: Throwable) {
            when (requestedStops[downloadKey]) {
                StopReason.Pause -> {
                    taskStore.update(details.summary.publishedFileId) {
                        it.copy(
                            status = WorkshopDownloadTaskStatus.Paused,
                            message = "下载已暂停",
                            updatedAtMillis = System.currentTimeMillis(),
                            preservePartialDownload = true,
                        )
                    }
                    taskStore.appendLog(details.summary.publishedFileId, "下载已暂停")
                    restoreExistingRecordOrUpdateState(
                        metadataStore = metadataStore,
                        existingRecord = existingRecord,
                        details = details,
                        fallbackState = WorkshopModCardState.DownloadPaused,
                        fallbackStatusText = "下载已暂停",
                    )
                    receiver?.send(RESULT_PAUSED, Bundle().apply {
                        putString(EXTRA_MESSAGE, "下载已暂停")
                        putString(EXTRA_TASK_STATUS, "Paused")
                    })
                }
                StopReason.Cancel -> {
                    val explicitlyDeleted = taskStore.isMarkedDeleted(details.summary.publishedFileId)
                    cleanOutput(details)
                    taskStore.update(details.summary.publishedFileId) {
                        it.copy(
                            status = WorkshopDownloadTaskStatus.Cancelled,
                            message = "下载已取消",
                            updatedAtMillis = System.currentTimeMillis(),
                            preservePartialDownload = false,
                        )
                    }
                    taskStore.appendLog(details.summary.publishedFileId, "下载已取消，已清理输出目录")
                    if (!explicitlyDeleted && existingRecord != null) {
                        metadataStore.upsert(existingRecord)
                    } else {
                        metadataStore.remove(details.summary.appId, details.summary.publishedFileId)
                    }
                    receiver?.send(RESULT_CANCELLED, Bundle().apply {
                        putString(EXTRA_MESSAGE, "下载已取消")
                        putString(EXTRA_TASK_STATUS, "Cancelled")
                    })
                }
                null -> {
                    if (throwable.hasWorkshopSteamRateLimitCause()) {
                        val rateLimitStore = WorkshopSteamRateLimitStore(applicationContext)
                        rateLimitStore.activate()
                        pauseForSteamRateLimit(
                            taskStore = taskStore,
                            metadataStore = metadataStore,
                            details = details,
                            message = rateLimitStore.cooldownMessage(),
                            receiver = receiver,
                            existingRecord = existingRecord,
                        )
                    } else {
                        val error = throwable.message ?: throwable.javaClass.simpleName
                        taskStore.update(details.summary.publishedFileId) {
                            it.copy(
                                status = WorkshopDownloadTaskStatus.Failed,
                                message = "下载失败：$error",
                                errorClass = throwable.javaClass.name,
                                errorMessage = error,
                                errorStackTrace = throwable.stackTraceToString(),
                                updatedAtMillis = System.currentTimeMillis(),
                                preservePartialDownload = true,
                            )
                        }
                        taskStore.appendLog(details.summary.publishedFileId, "下载失败：$error")
                        taskStore.appendLog(details.summary.publishedFileId, throwable.stackTraceToString())
                        restoreExistingRecordOrUpdateState(
                            metadataStore = metadataStore,
                            existingRecord = existingRecord,
                            details = details,
                            fallbackState = WorkshopModCardState.DownloadFailed,
                            fallbackStatusText = "下载失败：${throwable.message ?: throwable.javaClass.simpleName}",
                        )
                        receiver?.send(RESULT_FAILURE, errorBundle(throwable))
                    }
                }
            }
        } finally {
            activeDownloadHeartbeat.interrupt()
            clearActiveDownloadMarker(details.summary.publishedFileId)
            workerThreads.remove(downloadKey)
            activeServices.remove(downloadKey)
            requestedStops.remove(downloadKey)
            activeDownloadKeySnapshot = workerThreads.keys.toSet()
            if (workerThreads.isEmpty()) {
                stopForegroundCompat()
                stopSelf()
            }
            startNextQueued(applicationContext)
        }
    }

    private fun cancelBlockedTask(
        taskStore: WorkshopDownloadTaskStore,
        task: WorkshopDownloadTaskRecord,
        receiver: ResultReceiver?,
    ) {
        val message = getString(R.string.workshop_download_task_message_blocked)
        val summary = task.details.summary
        taskStore.update(task.publishedFileId) {
            it.copy(
                status = WorkshopDownloadTaskStatus.Cancelled,
                message = message,
                updatedAtMillis = System.currentTimeMillis(),
                errorClass = "WorkshopDownloadBlocked",
                errorMessage = message,
            )
        }
        taskStore.appendLog(task.publishedFileId, message)
        WorkshopMetadataStore(applicationContext).updateState(
            appId = summary.appId,
            publishedFileId = summary.publishedFileId,
            state = WorkshopModCardState.DownloadFailed,
            statusText = message,
        )
        receiver?.send(RESULT_CANCELLED, Bundle().apply {
            putString(EXTRA_MESSAGE, message)
            putString(EXTRA_TASK_STATUS, "Cancelled")
        })
    }

    private fun pauseForSteamRateLimit(
        taskStore: WorkshopDownloadTaskStore,
        metadataStore: WorkshopMetadataStore,
        details: WorkshopItemDetails,
        message: String,
        receiver: ResultReceiver?,
        existingRecord: WorkshopInstalledModRecord? = metadataStore.findByPublishedFileId(
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
        )?.restoreCandidateForInterruptedUpdate(),
    ) {
        taskStore.update(details.summary.publishedFileId) {
            it.copy(
                status = WorkshopDownloadTaskStatus.Paused,
                message = message,
                errorClass = WorkshopSteamRateLimitedException::class.java.name,
                errorMessage = message,
                errorStackTrace = "",
                updatedAtMillis = System.currentTimeMillis(),
                preservePartialDownload = true,
            )
        }
        taskStore.appendLog(details.summary.publishedFileId, message)
        restoreExistingRecordOrUpdateState(
            metadataStore = metadataStore,
            existingRecord = existingRecord,
            details = details,
            fallbackState = WorkshopModCardState.DownloadPaused,
            fallbackStatusText = message,
        )
        receiver?.send(RESULT_PAUSED, Bundle().apply {
            putString(EXTRA_MESSAGE, message)
            putString(EXTRA_TASK_STATUS, "Paused")
        })
    }

    private fun startActiveDownloadHeartbeat(publishedFileId: ULong, downloadKey: String): Thread {
        touchActiveDownloadMarker(publishedFileId)
        return thread(name = "STS-WorkshopDownloadHeartbeat-$downloadKey", isDaemon = true) {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(ACTIVE_MARKER_HEARTBEAT_MS)
                    touchActiveDownloadMarker(publishedFileId)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun touchActiveDownloadMarker(publishedFileId: ULong) {
        runCatching {
            val markerFile = activeDownloadMarkerFile(applicationContext, publishedFileId)
            markerFile.parentFile?.mkdirs()
            markerFile.writeText(System.currentTimeMillis().toString(), StandardCharsets.UTF_8)
        }
    }

    private fun clearActiveDownloadMarker(publishedFileId: ULong) {
        runCatching { activeDownloadMarkerFile(applicationContext, publishedFileId).delete() }
    }

    private fun extractDetails(intent: Intent): WorkshopItemDetails {
        val appId = intent.getStringExtra(EXTRA_APP_ID)?.toUIntOrNull() ?: error("Missing workshop app id")
        val publishedFileId = intent.getStringExtra(EXTRA_PUBLISHED_FILE_ID)?.toULongOrNull()
            ?: error("Missing workshop published file id")
        return WorkshopItemDetails(
            summary = WorkshopItemSummary(
                appId = appId,
                publishedFileId = publishedFileId,
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty(),
                previewUrl = intent.getStringExtra(EXTRA_PREVIEW_URL).orEmpty(),
                authorName = intent.getStringExtra(EXTRA_AUTHOR_NAME).orEmpty(),
                fileSizeBytes = intent.getLongExtra(EXTRA_FILE_SIZE_BYTES, 0L),
                updatedAtMillis = intent.getLongExtra(EXTRA_UPDATED_AT_MILLIS, 0L),
                downloadCount = intent.getLongExtra(EXTRA_DOWNLOAD_COUNT, 0L),
            ),
            fileUrl = intent.getStringExtra(EXTRA_FILE_URL),
            hcontentFile = intent.getStringExtra(EXTRA_HCONTENT_FILE)?.toULongOrNull(),
            depotId = intent.getStringExtra(EXTRA_DEPOT_ID)?.toUIntOrNull(),
            jsonMetadata = intent.getStringExtra(EXTRA_JSON_METADATA).orEmpty(),
            dependencies = intent.getStringExtra(EXTRA_DEPENDENCIES).orEmpty().parseDependenciesExtra(),
        )
    }

    private fun WorkshopItemDetails.hasDownloadSource(): Boolean = !fileUrl.isNullOrBlank() || hcontentFile != null

    private fun Throwable.hasWorkshopSteamRateLimitCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is WorkshopSteamRateLimitedException) return true
            current = current.cause
        }
        return false
    }

    private fun sendProgress(
        receiver: ResultReceiver?,
        message: String,
        taskStatus: String?,
        progress: WorkshopDownloadProgress? = null,
        progressPercent: Int? = null,
    ) {
        updateNotification(message)
        receiver?.send(
            RESULT_PROGRESS,
            Bundle().apply {
                putString(EXTRA_MESSAGE, message)
                if (taskStatus != null) putString(EXTRA_TASK_STATUS, taskStatus)
                progress?.let { current ->
                    putLong(EXTRA_WRITTEN_BYTES, current.writtenBytes)
                    current.totalBytes?.let { putLong(EXTRA_TOTAL_BYTES, it) }
                    current.completedFiles?.let { putInt(EXTRA_COMPLETED_FILES, it) }
                    current.totalFiles?.let { putInt(EXTRA_TOTAL_FILES, it) }
                    current.completedChunks?.let { putInt(EXTRA_COMPLETED_CHUNKS, it) }
                    current.totalChunks?.let { putInt(EXTRA_TOTAL_CHUNKS, it) }
                }
                (progress?.progressPercent() ?: progressPercent)?.let { putInt(EXTRA_PROGRESS_PERCENT, it.coerceIn(0, 100)) }
            }
        )
    }

    private fun extractResultReceiver(intent: Intent): ResultReceiver? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(EXTRA_RESULT_RECEIVER)
    }

    private fun errorBundle(throwable: Throwable): Bundle {
        val stackTraceWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stackTraceWriter))
        return Bundle().apply {
            putString(EXTRA_ERROR_CLASS, throwable.javaClass.name)
            putString(EXTRA_ERROR_MESSAGE, throwable.message)
            putString(EXTRA_ERROR_STACKTRACE, stackTraceWriter.toString())
            putString(EXTRA_MESSAGE, "下载失败：${throwable.message ?: throwable.javaClass.simpleName}")
            putString(EXTRA_TASK_STATUS, "Failed")
        }
    }

    private fun Intent.downloadKeyOrNull(): String? {
        val appId = getStringExtra(EXTRA_APP_ID).orEmpty()
        val publishedFileId = getStringExtra(EXTRA_PUBLISHED_FILE_ID).orEmpty()
        return if (appId.isBlank() || publishedFileId.isBlank()) null else "$appId:$publishedFileId"
    }

    private fun Intent.publishedFileIdOrNull(): ULong? = getStringExtra(EXTRA_PUBLISHED_FILE_ID)?.toULongOrNull()

    private fun downloadPreviewImageIfNeeded(
        service: WorkshopService,
        metadataStore: WorkshopMetadataStore,
        details: WorkshopItemDetails,
    ): String {
        val existing = metadataStore.findByPublishedFileId(
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
        )?.localPreviewImagePath.orEmpty()
        if (existing.isNotBlank()) return existing
        val previewImagePath = service.downloadPreviewImage(
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            previewUrl = details.summary.previewUrl,
        )
        metadataStore.updatePreviewImagePath(
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            localPreviewImagePath = previewImagePath,
        )
        return previewImagePath
    }

    private fun downloadPreviewImageInBackground(
        service: WorkshopService,
        metadataStore: WorkshopMetadataStore,
        details: WorkshopItemDetails,
    ) {
        thread(name = "STS-WorkshopPreview-${details.summary.publishedFileId}", isDaemon = true) {
            runCatching { downloadPreviewImageIfNeeded(service, metadataStore, details) }
        }
    }

    private fun cleanOutput(details: WorkshopItemDetails) {
        RuntimePaths.workshopItemDirs(
            applicationContext,
            details.summary.appId,
            details.summary.publishedFileId,
        ).forEach { it.deleteRecursively() }
    }

    private fun restoreExistingRecordOrUpdateState(
        metadataStore: WorkshopMetadataStore,
        existingRecord: WorkshopInstalledModRecord?,
        details: WorkshopItemDetails,
        fallbackState: WorkshopModCardState,
        fallbackStatusText: String,
    ) {
        if (existingRecord != null) {
            metadataStore.upsert(existingRecord)
            return
        }
        metadataStore.updateState(
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            state = fallbackState,
            statusText = fallbackStatusText,
        )
    }

    private fun rememberCompletedJarBeforeFinalization(
        metadataStore: WorkshopMetadataStore,
        taskStore: WorkshopDownloadTaskStore,
        service: WorkshopService,
        details: WorkshopItemDetails,
    ) {
        val task = taskStore.find(details.summary.publishedFileId)
        if (task?.status == WorkshopDownloadTaskStatus.Completed) return
        val outputDir = RuntimePaths.workshopItemDir(
            applicationContext,
            details.summary.appId,
            details.summary.publishedFileId,
        )
        val jarArtifacts = findDownloadedJars(outputDir)
        if (jarArtifacts.isEmpty()) return
        val existingRecord = metadataStore.findByPublishedFileId(
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
        )
        if (existingRecord?.allLocalJarPaths()?.any { path -> File(path).isAbsolute && File(path).isFile } == true) {
            return
        }
        val statusText = if (jarArtifacts.size > 1) {
            "检测到 ${jarArtifacts.size} 个 jar，请选择要导入的文件"
        } else {
            "等待修补"
        }
        metadataStore.upsert(
            service.createInstalledRecord(details, jarArtifacts.first())
                .copy(
                    localJarPaths = jarArtifacts.map { it.relativePath },
                    statusText = statusText,
                )
        )
        val message = if (jarArtifacts.size > 1) {
            "下载完成，检测到 ${jarArtifacts.size} 个 jar，等待选择导入文件"
        } else {
            "下载完成，准备导入修补"
        }
        taskStore.update(details.summary.publishedFileId) {
            it.copy(
                status = WorkshopDownloadTaskStatus.Downloading,
                message = message,
                progressPercent = if (jarArtifacts.size > 1) 100 else 0,
                downloadedBytes = (it.totalBytes ?: it.downloadedBytes).coerceAtLeast(it.downloadedBytes),
                completedFiles = it.totalFiles ?: it.completedFiles,
                updatedAtMillis = System.currentTimeMillis(),
                preservePartialDownload = false,
            )
        }
        taskStore.appendLog(details.summary.publishedFileId, message)
    }

    private fun WorkshopAutoImportProgress.toDownloadStatusMessage(): String {
        val progressText = if (totalSteps > 0) {
            val step = when {
                percent >= 100 -> totalSteps
                currentStep < totalSteps -> currentStep + 1
                else -> currentStep
            }.coerceIn(1, totalSteps)
            "（$step/$totalSteps，${percent.coerceIn(0, 100)}%）"
        } else {
            "（${percent.coerceIn(0, 100)}%）"
        }
        return "正在导入修补$progressText：$message"
    }

    private fun WorkshopInstalledModRecord?.hasInstalledJar(): Boolean {
        return this?.allLocalJarPaths().orEmpty()
            .any { path -> File(path).isAbsolute && File(path).isFile }
    }

    private fun WorkshopInstalledModRecord.restoreCandidateForInterruptedUpdate(): WorkshopInstalledModRecord? {
        return when (cardState) {
            WorkshopModCardState.UpdateAvailable,
            WorkshopModCardState.ImportedUnpatched,
            WorkshopModCardState.ImportedPatched,
            WorkshopModCardState.NonStandardDownloaded,
            WorkshopModCardState.TexturePackInstalled,
            WorkshopModCardState.FileMissing -> this
            WorkshopModCardState.Downloading -> when (contentKind) {
                WorkshopInstalledContentKind.TexturePack -> copy(
                    cardState = WorkshopModCardState.TexturePackInstalled,
                    statusText = statusText.ifBlank { "已作为 Texture Replacer 资源包安装并启用" },
                )
                WorkshopInstalledContentKind.NonStandard -> copy(
                    cardState = WorkshopModCardState.NonStandardDownloaded,
                    statusText = statusText.ifBlank { "该模组不是标准 jar 格式，请手动处理" },
                )
                WorkshopInstalledContentKind.JarMod -> if (allLocalJarPaths().isNotEmpty()) {
                    copy(
                        cardState = WorkshopModCardState.ImportedPatched,
                        statusText = statusText.ifBlank { "已安装" },
                    )
                } else {
                    null
                }
            }
            WorkshopModCardState.DownloadPaused,
            WorkshopModCardState.DownloadFailed -> null
        }
    }

    private fun WorkshopDownloadTaskRecord.shouldPreservePartialDownload(): Boolean {
        if (preservePartialDownload) return true
        if (status == WorkshopDownloadTaskStatus.Paused) return true
        return status == WorkshopDownloadTaskStatus.Failed && downloadedBytes > 0L
    }

    private fun cleanDownloadedContent(outputDir: File) {
        if (!outputDir.isDirectory) return
        outputDir.listFiles().orEmpty().forEach { file ->
            if (!file.name.startsWith("preview.", ignoreCase = true)) {
                file.deleteRecursively()
            }
        }
    }

    private fun findDownloadedJars(outputDir: File): List<WorkshopDownloadedArtifact> {
        if (!outputDir.isDirectory) return emptyList()
        return outputDir.walkTopDown()
            .filter { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) && file.length() > 0L }
            .sortedWith(compareBy<File>({ it.relativeTo(outputDir).path.lowercase() }, { it.relativeTo(outputDir).path }))
            .map { jar ->
                WorkshopDownloadedArtifact(
                    relativePath = jar.relativeTo(outputDir).path,
                    sizeBytes = jar.length(),
                    modifiedAtMillis = jar.lastModified(),
                )
            }
            .toList()
    }

    private fun buildNotification(message: String): Notification {
        ensureNotificationChannel()
        val intent = Intent(this, LauncherActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentTitle("创意工坊下载")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "创意工坊下载", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private enum class StopReason { Pause, Cancel }
}

private fun WorkshopDownloadProgress.progressPercent(): Int? {
    val safeTotalBytes = totalBytes?.takeIf { it > 0L } ?: return null
    return ((writtenBytes.coerceIn(0L, safeTotalBytes) * 100L) / safeTotalBytes).toInt().coerceIn(0, 100)
}

private fun WorkshopDownloadProgress.shouldPersist(
    nowMillis: Long,
    lastPersistAtMillis: Long,
    lastPercent: Int?,
    lastCompletedFiles: Int?,
    lastCompletedChunks: Int?,
): Boolean {
    val percent = progressPercent()
    if (lastPersistAtMillis == 0L) return true
    if (isCompleteProgress()) return true
    if (nowMillis - lastPersistAtMillis < PROGRESS_PERSIST_INTERVAL_MS) return false
    if (percent != null && percent != lastPercent) return true
    if (completedFiles != null && completedFiles != lastCompletedFiles) return true
    if (completedChunks != null && completedChunks != lastCompletedChunks) return true
    return true
}

private fun WorkshopDownloadProgress.shouldLog(
    percent: Int?,
    nowMillis: Long,
    lastLogAtMillis: Long,
    lastLoggedPercent: Int?,
): Boolean {
    if (isCompleteProgress()) return true
    if (lastLogAtMillis == 0L) return true
    if (percent != null && (lastLoggedPercent == null || percent >= lastLoggedPercent + PROGRESS_LOG_PERCENT_STEP)) return true
    return nowMillis - lastLogAtMillis >= PROGRESS_LOG_INTERVAL_MS
}

private fun WorkshopDownloadProgress.isCompleteProgress(): Boolean {
    val totalFileCount = totalFiles
    val files = completedFiles
    if (files != null && totalFileCount != null && totalFileCount > 0 && files >= totalFileCount) return true
    if (totalFileCount != null && totalFileCount > 0) return false
    if (progressPercent() == 100) return true
    val chunks = completedChunks
    val totalChunkCount = totalChunks
    return chunks != null && totalChunkCount != null && totalChunkCount > 0 && chunks >= totalChunkCount
}

private fun WorkshopDownloadTaskStatus.canStartDownload(): Boolean = when (this) {
    WorkshopDownloadTaskStatus.Queued,
    WorkshopDownloadTaskStatus.Resolving -> true
    WorkshopDownloadTaskStatus.Downloading,
    WorkshopDownloadTaskStatus.Pausing,
    WorkshopDownloadTaskStatus.Cancelling,
    WorkshopDownloadTaskStatus.Paused,
    WorkshopDownloadTaskStatus.Completed,
    WorkshopDownloadTaskStatus.Failed,
    WorkshopDownloadTaskStatus.Cancelled -> false
}

private fun WorkshopDownloadProgress.downloadLogLine(): String = buildString {
    append("下载进度：writtenBytes=").append(writtenBytes)
    totalBytes?.let { append(", totalBytes=").append(it) }
    progressPercent()?.let { append(", percent=").append(it).append('%') }
    if (completedFiles != null || totalFiles != null) {
        append(", files=").append(completedFiles ?: 0).append('/').append(totalFiles ?: "?")
    }
    if (completedChunks != null || totalChunks != null) {
        append(", chunks=").append(completedChunks ?: 0).append('/').append(totalChunks ?: "?")
    }
}

private const val PROGRESS_PERSIST_INTERVAL_MS = 500L
private const val PROGRESS_LOG_INTERVAL_MS = 15_000L
private const val PROGRESS_LOG_PERCENT_STEP = 5

private fun buildInitialDownloadLog(details: WorkshopItemDetails): String = buildString {
    appendLine("创意工坊下载日志")
    appendLine("Title: ${details.summary.title}")
    appendLine("App ID: ${details.summary.appId}")
    appendLine("Workshop ID: ${details.summary.publishedFileId}")
    appendLine("Author: ${details.summary.authorName.ifBlank { "<empty>" }}")
    appendLine("Remote updatedAtMillis: ${details.summary.updatedAtMillis}")
    appendLine("Declared fileSizeBytes: ${details.summary.fileSizeBytes}")
    appendLine("Preview URL: ${details.summary.previewUrl.ifBlank { "<empty>" }}")
    appendLine("Initial fileUrl: ${details.fileUrl?.takeIf(String::isNotBlank) ?: "<empty>"}")
    appendLine("Initial hcontentFile: ${details.hcontentFile ?: "<empty>"}")
    appendLine("Initial depotId: ${details.depotId ?: "<empty>"}")
    appendLine("Dependencies: ${details.dependencies.size}")
}.trimEnd()

private fun WorkshopInstalledModRecord.newlyImportedHighlightKeys(): List<String> {
    return (listOf("workshop:$appId:$publishedFileId") + allLocalJarPaths() + texturePackPath)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

private fun WorkshopAutoImportResult.Imported.formatImportedSummary(): String {
    val names = modNames
    return when {
        names.isEmpty() -> "${storagePaths.size} 个模组"
        names.size == 1 -> names.single()
        else -> "${names.size} 个模组：${names.joinToString("、")}"
    }
}

private fun WorkshopDownloadState.displayText(): String = when (this) {
    WorkshopDownloadState.Resolving -> "正在解析下载内容"
    WorkshopDownloadState.Downloading -> "正在下载"
    WorkshopDownloadState.Success -> "下载完成"
    WorkshopDownloadState.Failed -> "下载失败"
}

private fun WorkshopDownloadState.toTaskStatusName(): String = when (this) {
    WorkshopDownloadState.Resolving -> "Resolving"
    WorkshopDownloadState.Downloading -> "Downloading"
    WorkshopDownloadState.Success -> "Completed"
    WorkshopDownloadState.Failed -> "Failed"
}

private fun WorkshopDownloadState.toDownloadTaskStatus(): WorkshopDownloadTaskStatus = when (this) {
    WorkshopDownloadState.Resolving -> WorkshopDownloadTaskStatus.Resolving
    WorkshopDownloadState.Downloading -> WorkshopDownloadTaskStatus.Downloading
    WorkshopDownloadState.Success -> WorkshopDownloadTaskStatus.Completed
    WorkshopDownloadState.Failed -> WorkshopDownloadTaskStatus.Failed
}

private fun String.parseDependenciesExtra(): List<WorkshopItemSummary> {
    if (isBlank()) return emptyList()
    return lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        val appId = parts.getOrNull(0)?.toUIntOrNull() ?: 646570u
        val publishedFileId = parts.getOrNull(1)?.toULongOrNull() ?: return@mapNotNull null
        WorkshopItemSummary(
            appId = appId,
            publishedFileId = publishedFileId,
            title = parts.getOrNull(2).orEmpty(),
            previewUrl = parts.getOrNull(3).orEmpty(),
            description = parts.getOrNull(4).orEmpty(),
            authorName = parts.getOrNull(5).orEmpty(),
            fileSizeBytes = parts.getOrNull(6)?.toLongOrNull() ?: 0L,
            updatedAtMillis = parts.getOrNull(7)?.toLongOrNull() ?: 0L,
            downloadCount = parts.getOrNull(8)?.toLongOrNull() ?: 0L,
        )
    }.toList()
}

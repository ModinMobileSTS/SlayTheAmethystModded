package io.stamethyst.backend.workshop

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.apricityx.workshop.workshop.DownloadEvent
import top.apricityx.workshop.workshop.WorkshopDownloadEngine

internal fun interface WorkshopContentDownloader {
    fun download(details: WorkshopItemDetails, outputDir: File): Flow<WorkshopDownloadEvent>
}

internal class SteamPipeWorkshopContentDownloader(
    private val engineFactory: () -> WorkshopDownloadEngine,
) : WorkshopContentDownloader {
    override fun download(details: WorkshopItemDetails, outputDir: File): Flow<WorkshopDownloadEvent> {
        val engine = engineFactory()
        return engine.download(
            top.apricityx.workshop.workshop.WorkshopDownloadRequest(
                appId = details.summary.appId,
                publishedFileId = details.summary.publishedFileId,
                outputDir = outputDir,
            )
        ).map { event ->
            when (event) {
                is DownloadEvent.StateChanged -> WorkshopDownloadEvent.StateChanged(event.state.toLauncherState())
                is DownloadEvent.Completed -> WorkshopDownloadEvent.Completed(
                    event.files.map { file ->
                        WorkshopDownloadedArtifact(
                            relativePath = file.relativePath,
                            sizeBytes = file.sizeBytes,
                            modifiedAtMillis = file.modifiedEpochMillis,
                        )
                    }
                )
                is DownloadEvent.Failed -> WorkshopDownloadEvent.Failed(
                    WorkshopDownloadFailure(message = event.message)
                )
                is DownloadEvent.LogAppended -> WorkshopDownloadEvent.Log(event.line)
                is DownloadEvent.Progress -> WorkshopDownloadEvent.Progress(
                    WorkshopDownloadProgress(
                        writtenBytes = event.writtenBytes,
                        receivedBytes = event.receivedBytes,
                        diskWriteBytes = event.diskWriteBytes,
                        writeProgressBytes = event.writeProgressBytes,
                        writeTotalBytes = event.writeTotalBytes,
                        totalBytes = event.totalBytes,
                        completedChunks = event.completedChunks,
                        totalChunks = event.totalChunks,
                        completedFiles = event.completedFiles,
                        totalFiles = event.totalFiles,
                    )
                )
                is DownloadEvent.FileCompleted -> WorkshopDownloadEvent.Ignored
            }
        }
    }
}

internal fun top.apricityx.workshop.workshop.DownloadState.toLauncherState(): WorkshopDownloadState = when (this) {
    top.apricityx.workshop.workshop.DownloadState.Idle,
    top.apricityx.workshop.workshop.DownloadState.Resolving,
    top.apricityx.workshop.workshop.DownloadState.Connecting -> WorkshopDownloadState.Resolving
    top.apricityx.workshop.workshop.DownloadState.Downloading,
    top.apricityx.workshop.workshop.DownloadState.Paused -> WorkshopDownloadState.Downloading
    top.apricityx.workshop.workshop.DownloadState.Success -> WorkshopDownloadState.Success
    top.apricityx.workshop.workshop.DownloadState.Failed -> WorkshopDownloadState.Failed
}

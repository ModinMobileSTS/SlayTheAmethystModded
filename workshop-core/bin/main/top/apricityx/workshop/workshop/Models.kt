package top.apricityx.workshop.workshop

import kotlinx.serialization.Serializable
import java.io.File

data class WorkshopDownloadRequest(
    val appId: UInt,
    val publishedFileId: ULong,
    val outputDir: File,
)

@Serializable
enum class DownloadState {
    Idle,
    Resolving,
    Connecting,
    Downloading,
    Paused,
    Success,
    Failed,
}

data class DownloadedFileInfo(
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedEpochMillis: Long,
)

sealed interface DownloadEvent {
    data class StateChanged(val state: DownloadState) : DownloadEvent
    data class LogAppended(val line: String) : DownloadEvent
    data class Progress(
        val writtenBytes: Long,
        val totalBytes: Long?,
        val completedChunks: Int? = null,
        val totalChunks: Int? = null,
        val completedFiles: Int? = null,
        val totalFiles: Int? = null,
    ) : DownloadEvent
    data class FileCompleted(val file: DownloadedFileInfo) : DownloadEvent
    data class Completed(val files: List<DownloadedFileInfo>) : DownloadEvent
    data class Failed(val message: String) : DownloadEvent
}

sealed interface ResolvedWorkshopItem {
    val title: String
    val metadataJson: String

    data class DirectUrlItem(
        val fileName: String,
        val fileUrl: String,
        val size: Long?,
        override val title: String,
        override val metadataJson: String,
    ) : ResolvedWorkshopItem

    data class UgcManifestItem(
        val manifestId: ULong,
        val depotId: UInt,
        override val title: String,
        override val metadataJson: String,
    ) : ResolvedWorkshopItem
}

class WorkshopDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

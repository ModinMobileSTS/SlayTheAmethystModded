package top.apricityx.workshop.workshop

import top.apricityx.workshop.steam.protocol.CmServer
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
import top.apricityx.workshop.steam.protocol.SessionContext
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import top.apricityx.workshop.steam.protocol.newDefaultOkHttpClient
import java.io.File
import java.time.Instant

class WorkshopDownloadEngine(
    private val resolver: PublishedFileResolver,
    private val directDownloader: DirectWorkshopDownloader,
    private val ugcWorkshopDownloader: UgcWorkshopDownloader,
) {
    fun download(request: WorkshopDownloadRequest): Flow<DownloadEvent> = channelFlow {
        request.outputDir.mkdirs()
        val logFile = File(request.outputDir, "download.log").apply {
            parentFile?.mkdirs()
            writeText("")
        }
        val metadataFile = File(request.outputDir, "metadata.json")
        val completedFiles = linkedMapOf<String, DownloadedFileInfo>()

        suspend fun emitEvent(event: DownloadEvent) {
            if (event is DownloadEvent.FileCompleted) {
                completedFiles[event.file.relativePath] = event.file
            }
            send(event)
        }

        suspend fun emitLog(message: String) {
            logFile.appendText("[${Instant.now()}] $message\n")
            send(DownloadEvent.LogAppended(message))
        }

        try {
            send(DownloadEvent.StateChanged(DownloadState.Resolving))
            emitLog("Resolving workshop metadata for app=${request.appId} publishedFileId=${request.publishedFileId}")

            val resolved = resolver.resolve(request.appId, request.publishedFileId)
            metadataFile.writeText(resolved.metadataJson)
            emitLog("Metadata saved to ${metadataFile.name}")

            when (resolved) {
                is ResolvedWorkshopItem.DirectUrlItem -> {
                    send(DownloadEvent.StateChanged(DownloadState.Downloading))
                    emitLog("Using file_url direct download path")
                    directDownloader.download(request, resolved, ::emitEvent, ::emitLog)
                }

                is ResolvedWorkshopItem.UgcManifestItem -> {
                    send(DownloadEvent.StateChanged(DownloadState.Connecting))
                    emitLog("Using UGC manifest path manifest=${resolved.manifestId} depot=${resolved.depotId}")
                    ugcWorkshopDownloader.download(request, resolved, ::emitEvent, ::emitLog)
                }
            }

            val files = completedFiles
                .values
                .sortedBy(DownloadedFileInfo::relativePath)
                .ifEmpty { discoverFiles(request.outputDir) }
            send(DownloadEvent.StateChanged(DownloadState.Success))
            emitLog("Download finished with ${files.size} discovered files")
            send(DownloadEvent.Completed(files))
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            throw error
        } catch (error: Throwable) {
            val failureMessage = error.userVisibleDownloadFailureMessage()
            send(DownloadEvent.LogAppended("Download failed: $failureMessage"))
            send(DownloadEvent.StateChanged(DownloadState.Failed))
            send(DownloadEvent.Failed(failureMessage))
        }
    }

    companion object {
        fun createDefault(
            client: OkHttpClient = newDefaultOkHttpClient(),
            sessionFactory: () -> SteamCmSession = { OkHttpSteamCmSession(client) },
            sessionConnector: suspend (SteamCmSession, List<CmServer>) -> SessionContext = { session, servers ->
                session.connectAnonymous(servers)
            },
            maxConcurrentChunks: Int = UgcWorkshopDownloader.DEFAULT_MAX_CONCURRENT_CHUNKS,
            allowPublicCdnFallbackOnSessionFailure: Boolean = true,
            publishedFileLanguage: String? = null,
        ): WorkshopDownloadEngine {
            val directoryClient = SteamDirectoryClient(client)
            return WorkshopDownloadEngine(
                resolver = PublishedFileResolver(
                    client = client,
                    json = Json { ignoreUnknownKeys = true },
                    language = publishedFileLanguage,
                ),
                directDownloader = DirectWorkshopDownloader(client),
                ugcWorkshopDownloader = UgcWorkshopDownloader(
                    client = client,
                    directoryClient = directoryClient,
                    maxConcurrentChunks = maxConcurrentChunks,
                    sessionFactory = sessionFactory,
                    sessionConnector = sessionConnector,
                    allowPublicCdnFallbackOnSessionFailure = allowPublicCdnFallbackOnSessionFailure,
                ),
            )
        }
    }
}

private fun discoverFiles(root: File): List<DownloadedFileInfo> {
    if (!root.exists()) {
        return emptyList()
    }

    return root.walkTopDown()
        .filter { it.isFile }
        .filterNot { it.name == "metadata.json" || it.name == "download.log" || it.extension == "tmp" || it.extension == "part" }
        .filterNot { it.toRelativeString(root).startsWith(".chunks") }
        .map {
            DownloadedFileInfo(
                relativePath = it.toRelativeString(root).replace(File.separatorChar, '/'),
                sizeBytes = it.length(),
                modifiedEpochMillis = it.lastModified(),
            )
        }
        .toList()
}

package top.apricityx.workshop.workshop

import top.apricityx.workshop.steam.protocol.CdnServer
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.steam.protocol.SteamContentClient
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

class UgcWorkshopDownloader(
    private val client: OkHttpClient,
    private val directoryClient: SteamDirectoryClient,
    private val maxConcurrentChunks: Int = DEFAULT_MAX_CONCURRENT_CHUNKS,
    private val sessionFactory: () -> SteamCmSession = { OkHttpSteamCmSession(client) },
    private val sessionConnector: suspend (SteamCmSession, List<top.apricityx.workshop.steam.protocol.CmServer>) -> top.apricityx.workshop.steam.protocol.SessionContext =
        { session, servers -> session.connectAnonymous(servers) },
    private val allowPublicCdnFallbackOnSessionFailure: Boolean = true,
) {
    suspend fun download(
        request: WorkshopDownloadRequest,
        item: ResolvedWorkshopItem.UgcManifestItem,
        emit: suspend (DownloadEvent) -> Unit,
        log: suspend (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        log("Loading Steam CM websocket candidates")
        val cmServers = directoryClient.loadServers()
        log("Loaded ${cmServers.size} CM websocket candidates")
        val cdnTransport = SteamCdnTransport(client)

        sessionFactory().use { session ->
            val contentClient = SteamContentClient(session, directoryClient)
            val connectResult = runCatching { sessionConnector(session, cmServers) }
            connectResult
                .onSuccess { log("Connected to Steam CM cell=${it.cellId} steamId=${it.steamId}") }
                .onFailure {
                    if (allowPublicCdnFallbackOnSessionFailure) {
                        log("Steam CM connection failed, continuing with public CDN flow: ${it.message}")
                    } else {
                        throw it
                    }
                }

            val manifestRequestCode = runCatching {
                contentClient.getManifestRequestCode(
                    appId = request.appId,
                    depotId = item.depotId,
                    manifestId = item.manifestId,
                )
            }.getOrElse {
                log("Manifest request code unavailable, retrying without request code: ${it.message}")
                0uL
            }

            val contentServers = runCatching {
                contentClient.getServersForSteamPipe()
            }.getOrElse {
                log("Falling back to public content server directory API")
                directoryClient.loadContentServers()
            }

            require(contentServers.isNotEmpty()) { "No CDN servers available for SteamPipe" }
            log("Loaded ${contentServers.size} SteamPipe content servers")
            val serverPool = cdnTransport.buildServerPool(request.appId, contentServers)
            require(serverPool.downloadServers.isNotEmpty()) { "No CDN download servers available for app=${request.appId}" }
            log(
                "Selected ${serverPool.downloadServers.size} weighted CDN entries " +
                    "from ${serverPool.downloadServers.distinctBy(CdnServer::host).size} servers",
            )
            serverPool.proxyServer?.let { proxy ->
                log("Detected CDN proxy host=${proxy.host} template=${proxy.proxyRequestPathTemplate ?: "(none)"}")
            }
            val cdnAuthTokenCache = ConcurrentHashMap<String, String>()

            val depotKey = runCatching {
                session.requestDepotDecryptionKey(
                    appId = request.appId,
                    depotId = item.depotId,
                )
            }.onSuccess {
                log("Loaded depot key for depot=${item.depotId}")
            }.onFailure {
                log("Depot key request failed for depot=${item.depotId}: ${it.message}")
            }.getOrNull()

            val manifest = downloadManifest(
                appId = request.appId,
                item = item,
                contentServers = serverPool.downloadServers,
                proxyServer = serverPool.proxyServer,
                manifestRequestCode = manifestRequestCode,
                contentClient = contentClient,
                cdnTransport = cdnTransport,
                cdnAuthTokenCache = cdnAuthTokenCache,
                log = log,
            )
            val preparedManifest = when {
                manifest.filenamesEncrypted && depotKey != null -> {
                    log("Decrypting encrypted manifest filenames with depot key")
                    runCatching { manifest.decryptFilenames(depotKey) }
                        .getOrElse {
                            log("Manifest filename decryption failed; continuing with encoded names: ${it.message}")
                            manifest
                        }
                }

                manifest.filenamesEncrypted -> {
                    log("Manifest filenames are encrypted but depot key is unavailable; continuing with encoded names")
                    manifest
                }

                else -> manifest
            }

            emit(DownloadEvent.StateChanged(DownloadState.Downloading))
            val chunks = preparedManifest.uniqueChunks()
            val totalBytes = chunks.sumOf { it.uncompressedLength.toLong() }
            val totalFiles = preparedManifest.files.count {
                it.linkTarget.isNullOrBlank() && !preparedManifest.requiresDirectory(it)
            }
            log("Manifest ${preparedManifest.manifestId} contains ${preparedManifest.files.size} files and ${chunks.size} unique chunks")
            emit(
                DownloadEvent.Progress(
                    writtenBytes = 0L,
                    totalBytes = totalBytes,
                    completedChunks = 0,
                    totalChunks = chunks.size,
                    completedFiles = 0,
                    totalFiles = totalFiles,
                ),
            )

            val stageDir = File(request.outputDir, ".chunks").apply { mkdirs() }
            cacheChunks(
                appId = request.appId,
                depotId = item.depotId,
                contentServers = serverPool.downloadServers,
                proxyServer = serverPool.proxyServer,
                contentClient = contentClient,
                cdnTransport = cdnTransport,
                cdnAuthTokenCache = cdnAuthTokenCache,
                chunks = chunks,
                stageDir = stageDir,
                depotKey = depotKey,
                totalFiles = totalFiles,
                emit = emit,
                log = log,
            )

            assembleFiles(
                manifest = preparedManifest,
                outputDir = request.outputDir,
                stageDir = stageDir,
                totalBytes = totalBytes,
                totalChunks = chunks.size,
                totalFiles = totalFiles,
                emit = emit,
                log = log,
            )
        }
    }

    private suspend fun downloadManifest(
        appId: UInt,
        item: ResolvedWorkshopItem.UgcManifestItem,
        contentServers: List<CdnServer>,
        proxyServer: CdnServer?,
        manifestRequestCode: ULong,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: ConcurrentHashMap<String, String>,
        log: suspend (String) -> Unit,
    ): DepotManifest {
        var lastError: Throwable? = null
        for (server in contentServers) {
            try {
                currentCoroutineContext().ensureActive()
                log("Trying manifest download from ${server.host}")
                val path = buildString {
                    append("depot/${item.depotId}/manifest/${item.manifestId}/5")
                    if (manifestRequestCode > 0uL) {
                        append("/$manifestRequestCode")
                    }
                }
                val bytes = requestBytes(
                    server = server,
                    proxyServer = proxyServer,
                    path = path,
                    query = cdnAuthTokenCache[server.host],
                    appId = appId,
                    depotId = item.depotId,
                    contentClient = contentClient,
                    cdnTransport = cdnTransport,
                    cdnAuthTokenCache = cdnAuthTokenCache,
                )
                return DepotManifestParser.parse(unzipSingleEntry(bytes))
            } catch (error: Throwable) {
                if (error is CancellationException || error is InterruptedException) throw error
                lastError = error
                log("Manifest download failed from ${server.host}: ${error.message}")
            }
        }
        throw WorkshopDownloadException("Unable to download UGC manifest", lastError)
    }

    private suspend fun cacheChunks(
        appId: UInt,
        depotId: UInt,
        contentServers: List<CdnServer>,
        proxyServer: CdnServer?,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: ConcurrentHashMap<String, String>,
        chunks: List<ManifestChunk>,
        stageDir: File,
        depotKey: ByteArray?,
        totalFiles: Int,
        emit: suspend (DownloadEvent) -> Unit,
        log: suspend (String) -> Unit,
    ) = coroutineScope {
        val semaphore = Semaphore(maxConcurrentChunks.coerceAtLeast(1))
        val totalBytes = chunks.sumOf { it.uncompressedLength.toLong() }
        val downloaded = java.util.concurrent.atomic.AtomicLong(0L)
        val completedChunks = AtomicInteger(0)
        val totalChunks = chunks.size

        chunks.map { chunk ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    currentCoroutineContext().ensureActive()
                    val stageFile = File(stageDir, "${chunk.idHex}.chunk")
                    if (tryReuseCachedChunk(stageFile, chunk, downloaded, completedChunks, totalBytes, totalChunks, totalFiles, emit)) {
                        return@withPermit
                    }

                    val processed = downloadChunkWithRetries(
                        appId = appId,
                        depotId = depotId,
                        contentServers = contentServers,
                        proxyServer = proxyServer,
                        contentClient = contentClient,
                        cdnTransport = cdnTransport,
                        cdnAuthTokenCache = cdnAuthTokenCache,
                        chunk = chunk,
                        depotKey = depotKey,
                        log = log,
                    )
                    writeAtomically(stageFile, processed)
                    emit(
                        DownloadEvent.Progress(
                            writtenBytes = downloaded.addAndGet(processed.size.toLong()),
                            totalBytes = totalBytes,
                            completedChunks = completedChunks.incrementAndGet(),
                            totalChunks = totalChunks,
                            completedFiles = 0,
                            totalFiles = totalFiles,
                        ),
                    )
                }
            }
        }.awaitAll()
    }

    private suspend fun tryReuseCachedChunk(
        stageFile: File,
        chunk: ManifestChunk,
        downloaded: java.util.concurrent.atomic.AtomicLong,
        completedChunks: AtomicInteger,
        totalBytes: Long,
        totalChunks: Int,
        totalFiles: Int,
        emit: suspend (DownloadEvent) -> Unit,
    ): Boolean {
        if (!stageFile.exists()) {
            return false
        }

        if (!validateChunkFile(stageFile, chunk)) {
            stageFile.delete()
            return false
        }

        emit(
            DownloadEvent.Progress(
                writtenBytes = downloaded.addAndGet(stageFile.length()),
                totalBytes = totalBytes,
                completedChunks = completedChunks.incrementAndGet(),
                totalChunks = totalChunks,
                completedFiles = 0,
                totalFiles = totalFiles,
            ),
        )
        return true
    }

    private suspend fun downloadChunkWithRetries(
        appId: UInt,
        depotId: UInt,
        contentServers: List<CdnServer>,
        proxyServer: CdnServer?,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: ConcurrentHashMap<String, String>,
        chunk: ManifestChunk,
        depotKey: ByteArray?,
        log: suspend (String) -> Unit,
    ): ByteArray {
        var lastError: Throwable? = null

        for (attempt in 1..MAX_CHUNK_DOWNLOAD_ATTEMPTS) {
            for (server in rotateServers(contentServers, attempt - 1)) {
                try {
                    currentCoroutineContext().ensureActive()
                    val path = "depot/$depotId/chunk/${chunk.idHex}"
                    val raw = requestBytes(
                        server = server,
                        proxyServer = proxyServer,
                        path = path,
                        query = cdnAuthTokenCache[server.host],
                        appId = appId,
                        depotId = depotId,
                        contentClient = contentClient,
                        cdnTransport = cdnTransport,
                        cdnAuthTokenCache = cdnAuthTokenCache,
                    )
                    return ChunkProcessor.process(raw, chunk, depotKey)
                } catch (error: Throwable) {
                    if (error is CancellationException || error is InterruptedException) throw error
                    lastError = error
                    log("Chunk ${chunk.idHex} failed from ${server.host}: ${error.fullMessage()}")
                }
            }

            if (attempt < MAX_CHUNK_DOWNLOAD_ATTEMPTS) {
                log("Retrying chunk ${chunk.idHex} (${attempt + 1}/$MAX_CHUNK_DOWNLOAD_ATTEMPTS)")
                delay(CHUNK_RETRY_DELAY_MILLIS * attempt)
            }
        }

        throw WorkshopDownloadException(
            lastError?.fullMessage()?.let { "Failed to download chunk ${chunk.idHex}: $it" }
                ?: "Failed to download chunk ${chunk.idHex}",
            lastError,
        )
    }

    private suspend fun assembleFiles(
        manifest: DepotManifest,
        outputDir: File,
        stageDir: File,
        totalBytes: Long,
        totalChunks: Int,
        totalFiles: Int,
        emit: suspend (DownloadEvent) -> Unit,
        log: suspend (String) -> Unit,
    ) {
        var completedFiles = 0
        manifest.files.forEach { file ->
            currentCoroutineContext().ensureActive()
            if (!file.linkTarget.isNullOrBlank()) {
                log("Skipping symlink-like manifest entry ${file.path} -> ${file.linkTarget}")
                return@forEach
            }

            val preparedEntry = WorkshopOutputPathManager.prepare(
                outputDir = outputDir,
                manifest = manifest,
                file = file,
            )
            if (preparedEntry is PreparedManifestEntry.DirectoryEntry) {
                log("Created directory entry ${file.path}")
                return@forEach
            }
            val target = (preparedEntry as PreparedManifestEntry.FileEntry).target

            val existingValidation = target
                .takeIf { it.exists() && it.length() == file.size }
                ?.let { WorkshopFileIntegrityVerifier.assess(it, file) }
            val reuse = existingValidation != null && existingValidation !is AssembledFileValidation.Invalid
            if (existingValidation is AssembledFileValidation.ChunkVerifiedHashMismatch) {
                log(
                    "Reusing ${file.path} despite SHA-1 mismatch because all chunk ranges validated " +
                        "expected=${existingValidation.expectedShaHex} actual=${existingValidation.actualShaHex}",
                )
            }
            if (!reuse) {
                RandomAccessFile(target, "rw").use { output ->
                    output.setLength(file.size)
                    file.chunks.forEach { chunk ->
                        val chunkFile = File(stageDir, "${chunk.idHex}.chunk")
                        output.seek(chunk.offset)
                        chunkFile.inputStream().buffered().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                if (Thread.currentThread().isInterrupted) throw InterruptedException("SteamPipe workshop download interrupted")
                                val read = input.read(buffer)
                                if (read == -1) {
                                    break
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }

                when (val validation = WorkshopFileIntegrityVerifier.assess(target, file)) {
                    AssembledFileValidation.Verified -> Unit

                    is AssembledFileValidation.ChunkVerifiedHashMismatch -> {
                        log(
                            "Assembled ${file.path} with valid chunk coverage, but manifest SHA-1 differed; " +
                                "continuing expected=${validation.expectedShaHex} actual=${validation.actualShaHex}",
                        )
                    }

                    is AssembledFileValidation.Invalid -> {
                        throw WorkshopDownloadException(
                            "Assembled file checksum mismatch for ${file.path} " +
                                "(expected=${validation.expectedShaHex} actual=${validation.actualShaHex} " +
                                "exactChunkCoverage=${validation.exactChunkCoverage} " +
                                "chunkChecksumsValid=${validation.chunkChecksumsValid})",
                        )
                    }
                }
            }

            emit(
                DownloadEvent.FileCompleted(
                    DownloadedFileInfo(
                        relativePath = file.path,
                        sizeBytes = target.length(),
                        modifiedEpochMillis = target.lastModified(),
                    ),
                ),
            )
            completedFiles += 1
            emit(
                DownloadEvent.Progress(
                    writtenBytes = totalBytes,
                    totalBytes = totalBytes,
                    completedChunks = totalChunks,
                    totalChunks = totalChunks,
                    completedFiles = completedFiles,
                    totalFiles = totalFiles,
                ),
            )
        }
    }

    private suspend fun requestBytes(
        server: CdnServer,
        proxyServer: CdnServer?,
        path: String,
        query: String?,
        appId: UInt,
        depotId: UInt,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: ConcurrentHashMap<String, String>,
    ): ByteArray {
        return cdnTransport.requestBytes(
            server = server,
            path = path,
            query = query,
            proxyServer = proxyServer,
            resolveAuthToken = { host ->
                cdnAuthTokenCache[host] ?: contentClient.getCdnAuthToken(appId, depotId, host).token.also {
                    cdnAuthTokenCache[host] = it
                }
            },
        )
    }

    private fun unzipSingleEntry(zipBytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            val entry = zip.nextEntry ?: throw WorkshopDownloadException("Zip payload was empty")
            val output = ByteArrayOutputStream()
            zip.copyTo(output)
            zip.closeEntry()
            return output.toByteArray()
        }
    }

    private fun validateChunkFile(file: File, chunk: ManifestChunk): Boolean {
        if (!file.isFile || file.length() != chunk.uncompressedLength.toLong()) {
            return false
        }
        file.inputStream().buffered().use { input ->
            val checksum = steamAdler32(input)
            return checksum == chunk.checksum
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun rotateServers(
        servers: List<CdnServer>,
        offset: Int,
    ): List<CdnServer> {
        if (servers.isEmpty()) {
            return emptyList()
        }
        return List(servers.size) { index ->
            servers[(index + offset) % servers.size]
        }
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT_CHUNKS = 4
        private const val MAX_CHUNK_DOWNLOAD_ATTEMPTS = 3
        private const val CHUNK_RETRY_DELAY_MILLIS = 750L
    }
}

private fun Throwable.fullMessage(): String = buildString {
    append(this@fullMessage::class.simpleName ?: "Error")
    message?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
    cause?.let { cause ->
        append("; caused by ")
        append(cause::class.simpleName ?: "Error")
        cause.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
    }
}

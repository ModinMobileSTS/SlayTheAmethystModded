package top.apricityx.workshop.workshop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import top.apricityx.workshop.steam.protocol.CdnServer
import top.apricityx.workshop.steam.protocol.CmServer
import top.apricityx.workshop.steam.protocol.SessionContext
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.steam.protocol.SteamContentClient
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient

data class SteamDepotFileDownloadRequest(
    val appId: UInt,
    val depotId: UInt,
    val manifestId: ULong,
    val branch: String = "public",
    val fileName: String,
    val outputFile: File,
    val depotKey: ByteArray?,
)

data class SteamDepotFileDownloadProgress(
    val writtenBytes: Long,
    val totalBytes: Long,
    val completedChunks: Int,
    val totalChunks: Int,
) {
    val progressPercent: Int
        get() = if (totalBytes <= 0L) {
            0
        } else {
            ((writtenBytes.coerceIn(0L, totalBytes) * 100L) / totalBytes).toInt()
        }
}

class SteamDepotSingleFileDownloader(
    private val client: OkHttpClient,
    private val directoryClient: SteamDirectoryClient,
    private val sessionFactory: () -> SteamCmSession,
    private val sessionConnector: suspend (SteamCmSession, List<CmServer>) -> SessionContext,
    private val maxConcurrentChunks: Int = DEFAULT_MAX_CONCURRENT_CHUNKS,
    /**
     * Keeps Steam-China-only edges at the head of the CDN pool. Steam returns a
     * globally weighted list, so mainland clients otherwise get overseas edges.
     */
    private val preferSteamChinaCdn: Boolean = false,
) {
    suspend fun download(
        request: SteamDepotFileDownloadRequest,
        emitProgress: suspend (SteamDepotFileDownloadProgress) -> Unit,
        waitIfPaused: suspend () -> Unit = {},
        cmServers: List<CmServer>? = null,
    ): File = withContext(Dispatchers.IO) {
        waitIfPaused()
        val connectionServers = cmServers ?: directoryClient.loadServers()
        waitIfPaused()
        val cdnTransport = SteamCdnTransport(client)

        sessionFactory().use { session ->
            waitIfPaused()
            sessionConnector(session, connectionServers)
            val contentClient = SteamContentClient(session, directoryClient)
            waitIfPaused()
            val (manifestRequestCode, contentServers) = coroutineScope {
                val code = async {
                    contentClient.getManifestRequestCode(
                        appId = request.appId,
                        depotId = request.depotId,
                        manifestId = request.manifestId,
                        branch = request.branch,
                    ).also {
                        if (it == 0uL) throw WorkshopDownloadException(
                            "Steam returned no manifest request code for depot=${request.depotId} manifest=${request.manifestId}",
                        )
                    }
                }
                // SteamContentClient already owns the cell-aware HTTP fallback.
                val servers = async { contentClient.getServersForSteamPipe() }
                code.await() to servers.await()
            }
            waitIfPaused()
            require(contentServers.isNotEmpty()) { "No CDN servers available for SteamPipe" }
            val serverPool = cdnTransport.buildServerPool(
                appId = request.appId,
                contentServers = contentServers,
                preferSteamChinaServers = preferSteamChinaCdn,
            )
            require(serverPool.downloadServers.isNotEmpty()) { "No CDN download servers available for app=${request.appId}" }
            val cdnAuthTokenCache = SteamCdnAuthTokenCache()
            val serverSelector = SteamCdnServerSelector(serverPool.downloadServers)

            val manifest = downloadManifest(
                request = request,
                serverSelector = serverSelector,
                proxyServer = serverPool.proxyServer,
                manifestRequestCode = manifestRequestCode,
                contentClient = contentClient,
                cdnTransport = cdnTransport,
                cdnAuthTokenCache = cdnAuthTokenCache,
                waitIfPaused = waitIfPaused,
            )
            val preparedManifest = when {
                manifest.filenamesEncrypted && request.depotKey != null -> manifest.decryptFilenames(request.depotKey)
                manifest.filenamesEncrypted -> throw WorkshopDownloadException(
                    "Steam depot ${request.depotId} manifest ${request.manifestId} has encrypted filenames, but no depot key is available",
                )
                else -> manifest
            }
            val targetEntry = preparedManifest.files.firstOrNull { it.matchesTargetFileName(request.fileName) }
                ?: throw WorkshopDownloadException(
                    "Steam depot ${request.depotId} manifest ${request.manifestId} did not contain ${request.fileName}",
                )
            if (!targetEntry.linkTarget.isNullOrBlank() || preparedManifest.requiresDirectory(targetEntry)) {
                throw WorkshopDownloadException("Steam depot entry ${targetEntry.path} is not a regular file")
            }

            downloadFileChunks(
                request = request,
                manifestFile = targetEntry,
                serverSelector = serverSelector,
                proxyServer = serverPool.proxyServer,
                contentClient = contentClient,
                cdnTransport = cdnTransport,
                cdnAuthTokenCache = cdnAuthTokenCache,
                emitProgress = emitProgress,
                waitIfPaused = waitIfPaused,
            )
            request.outputFile
        }
    }

    private suspend fun downloadManifest(
        request: SteamDepotFileDownloadRequest,
        serverSelector: SteamCdnServerSelector,
        proxyServer: CdnServer?,
        manifestRequestCode: ULong,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: SteamCdnAuthTokenCache,
        waitIfPaused: suspend () -> Unit,
    ): DepotManifest = coroutineScope {
        val servers = serverSelector.servers
        val nextServer = AtomicInteger(0)
        val remaining = AtomicInteger(servers.size)
        val lastError = AtomicReference<Throwable?>()
        val winner = CompletableDeferred<DepotManifest>()
        val workers = List(minOf(MAX_CONCURRENT_MANIFEST_REQUESTS, servers.size)) {
            launch(Dispatchers.IO) {
                while (true) {
                    val index = nextServer.getAndIncrement()
                    if (index >= servers.size) break
                    val server = servers[index]
                    try {
                        waitIfPaused()
                        val started = System.nanoTime()
                        val bytes = requestBytes(
                            server = server,
                            proxyServer = proxyServer,
                            path = "depot/${request.depotId}/manifest/${request.manifestId}/5/$manifestRequestCode",
                            query = cdnAuthTokenCache[server.host],
                            appId = request.appId,
                            depotId = request.depotId,
                            contentClient = contentClient,
                            cdnTransport = cdnTransport,
                            cdnAuthTokenCache = cdnAuthTokenCache,
                            requestTimeoutMillis = MANIFEST_REQUEST_TIMEOUT_MILLIS,
                        )
                        val manifest = DepotManifestParser.parse(unzipSingleEntry(bytes))
                        if (manifest.depotId != request.depotId || manifest.manifestId != request.manifestId) {
                            throw WorkshopDownloadException("Steam CDN returned an unexpected depot manifest")
                        }
                        serverSelector.manifestSucceeded(server, (System.nanoTime() - started) / 1_000_000L)
                        winner.complete(manifest)
                        return@launch
                    } catch (error: Throwable) {
                        if (error is CancellationException || error is InterruptedException) {
                            winner.completeExceptionally(error)
                            throw error
                        }
                        serverSelector.manifestFailed(server)
                        lastError.set(error)
                    } finally {
                        if (remaining.decrementAndGet() == 0 && !winner.isCompleted) {
                            winner.completeExceptionally(WorkshopDownloadException("Unable to download Steam depot manifest", lastError.get()))
                        }
                    }
                }
            }
        }
        try {
            winner.await()
        } finally {
            workers.forEach { it.cancel() }
        }
    }

    private suspend fun downloadFileChunks(
        request: SteamDepotFileDownloadRequest,
        manifestFile: ManifestFile,
        serverSelector: SteamCdnServerSelector,
        proxyServer: CdnServer?,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: SteamCdnAuthTokenCache,
        emitProgress: suspend (SteamDepotFileDownloadProgress) -> Unit,
        waitIfPaused: suspend () -> Unit,
    ) {
        val parent = request.outputFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw WorkshopDownloadException("Failed to create output directory: ${parent.absolutePath}")
        }

        val chunks = manifestFile.chunks.sortedBy(ManifestChunk::offset)
        val totalBytes = manifestFile.size
        emitProgress(
            SteamDepotFileDownloadProgress(
                writtenBytes = 0L,
                totalBytes = totalBytes,
                completedChunks = 0,
                totalChunks = chunks.size,
            ),
        )

        val stageDir = createChunkStageDir(
            parent = parent ?: request.outputFile.absoluteFile.parentFile ?: File("."),
            outputName = request.outputFile.name,
        )
        try {
            cacheFileChunks(
                request = request,
                manifestFile = manifestFile,
                chunks = chunks,
                stageDir = stageDir,
                serverSelector = serverSelector,
                proxyServer = proxyServer,
                contentClient = contentClient,
                cdnTransport = cdnTransport,
                cdnAuthTokenCache = cdnAuthTokenCache,
                emitProgress = emitProgress,
                waitIfPaused = waitIfPaused,
            )
            assembleFileChunks(
                outputFile = request.outputFile,
                manifestFile = manifestFile,
                chunks = chunks,
                stageDir = stageDir,
                waitIfPaused = waitIfPaused,
            )
        } finally {
            stageDir.deleteRecursively()
        }

        when (val validation = WorkshopFileIntegrityVerifier.assess(request.outputFile, manifestFile)) {
            AssembledFileValidation.Verified,
            is AssembledFileValidation.ChunkVerifiedHashMismatch -> Unit
            is AssembledFileValidation.Invalid -> throw WorkshopDownloadException(
                "Downloaded file checksum mismatch for ${manifestFile.path} " +
                    "(expected=${validation.expectedShaHex} actual=${validation.actualShaHex})",
            )
        }
    }

    private suspend fun cacheFileChunks(
        request: SteamDepotFileDownloadRequest,
        manifestFile: ManifestFile,
        chunks: List<ManifestChunk>,
        stageDir: File,
        serverSelector: SteamCdnServerSelector,
        proxyServer: CdnServer?,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: SteamCdnAuthTokenCache,
        emitProgress: suspend (SteamDepotFileDownloadProgress) -> Unit,
        waitIfPaused: suspend () -> Unit,
    ) = coroutineScope {
        val semaphore = Semaphore(maxConcurrentChunks.coerceAtLeast(1))
        val writtenBytes = AtomicLong(0L)
        val completedChunks = AtomicInteger(0)
        val emitMutex = Mutex()

        chunks.mapIndexed { index, chunk ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    currentCoroutineContext().ensureActive()
                    waitIfPaused()
                    val processed = downloadChunkWithRetries(
                        request = request,
                        serverSelector = serverSelector,
                        proxyServer = proxyServer,
                        contentClient = contentClient,
                        cdnTransport = cdnTransport,
                        cdnAuthTokenCache = cdnAuthTokenCache,
                        chunk = chunk,
                        waitIfPaused = waitIfPaused,
                        serverStartOffset = index,
                    )
                    writeAtomically(chunkStageFile(stageDir, index, chunk), processed)
                    emitMutex.withLock {
                        val downloaded = writtenBytes.addAndGet(processed.size.toLong())
                        val completed = completedChunks.incrementAndGet()
                        emitProgress(
                            SteamDepotFileDownloadProgress(
                                writtenBytes = downloaded,
                                totalBytes = manifestFile.size,
                                completedChunks = completed,
                                totalChunks = chunks.size,
                            ),
                        )
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun assembleFileChunks(
        outputFile: File,
        manifestFile: ManifestFile,
        chunks: List<ManifestChunk>,
        stageDir: File,
        waitIfPaused: suspend () -> Unit,
    ) {
        waitIfPaused()
        RandomAccessFile(outputFile, "rw").use { output ->
            output.setLength(manifestFile.size)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            chunks.forEachIndexed { index, chunk ->
                currentCoroutineContext().ensureActive()
                waitIfPaused()
                output.seek(chunk.offset)
                chunkStageFile(stageDir, index, chunk).inputStream().buffered().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) {
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private suspend fun downloadChunkWithRetries(
        request: SteamDepotFileDownloadRequest,
        serverSelector: SteamCdnServerSelector,
        proxyServer: CdnServer?,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: SteamCdnAuthTokenCache,
        chunk: ManifestChunk,
        waitIfPaused: suspend () -> Unit,
        serverStartOffset: Int = 0,
    ): ByteArray {
        var lastError: Throwable? = null
        for (attempt in 1..MAX_CHUNK_DOWNLOAD_ATTEMPTS) {
            val triedHosts = mutableSetOf<String>()
            while (true) {
                currentCoroutineContext().ensureActive()
                waitIfPaused()
                val lease = serverSelector.acquire(
                    excludedHosts = triedHosts,
                    expectedBytes = chunk.compressedLength,
                    offset = serverStartOffset + attempt - 1,
                ) ?: break
                val server = lease.server
                triedHosts += server.host
                try {
                    val raw = requestBytes(
                        server = server,
                        proxyServer = proxyServer,
                        path = "depot/${request.depotId}/chunk/${chunk.idHex}",
                        query = cdnAuthTokenCache[server.host],
                        appId = request.appId,
                        depotId = request.depotId,
                        contentClient = contentClient,
                        cdnTransport = cdnTransport,
                        cdnAuthTokenCache = cdnAuthTokenCache,
                    )
                    val processed = ChunkProcessor.process(raw, chunk, request.depotKey)
                    lease.succeeded(raw.size)
                    return processed
                } catch (error: Throwable) {
                    if (error is CancellationException || error is InterruptedException) {
                        lease.cancelled()
                        throw error
                    }
                    lease.failed()
                    lastError = error
                }
            }
            if (attempt < MAX_CHUNK_DOWNLOAD_ATTEMPTS) {
                delay(CHUNK_RETRY_DELAY_MILLIS * attempt)
            }
        }
        throw WorkshopDownloadException("Failed to download chunk ${chunk.idHex}", lastError)
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
        cdnAuthTokenCache: SteamCdnAuthTokenCache,
        requestTimeoutMillis: Long? = null,
    ): ByteArray {
        return cdnTransport.requestBytes(
            server = server,
            path = path,
            query = query,
            proxyServer = proxyServer,
            resolveAuthToken = { host ->
                cdnAuthTokenCache.getOrLoad(host) {
                    contentClient.getCdnAuthToken(appId, depotId, host).token
                }
            },
            requestTimeoutMillis = requestTimeoutMillis,
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

    private fun createChunkStageDir(parent: File, outputName: String): File {
        val stageRoot = File.createTempFile("$outputName.chunks-", ".tmp", parent)
        if (!stageRoot.delete() || !stageRoot.mkdirs()) {
            throw WorkshopDownloadException("Failed to create chunk staging directory: ${stageRoot.absolutePath}")
        }
        return stageRoot
    }

    private fun chunkStageFile(
        stageDir: File,
        index: Int,
        chunk: ManifestChunk,
    ): File = File(stageDir, "$index-${chunk.idHex}.chunk")

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun ManifestFile.matchesTargetFileName(fileName: String): Boolean {
        val normalizedPath = path.replace('\\', '/')
        val normalizedFileName = fileName.trim().replace('\\', '/')
        return normalizedPath.equals(normalizedFileName, ignoreCase = true) ||
            normalizedPath.endsWith("/$normalizedFileName", ignoreCase = true)
    }

    private companion object {
        private const val DEFAULT_MAX_CONCURRENT_CHUNKS = 4
        private const val MAX_CONCURRENT_MANIFEST_REQUESTS = 3
        private const val MANIFEST_REQUEST_TIMEOUT_MILLIS = 15_000L
        private const val MAX_CHUNK_DOWNLOAD_ATTEMPTS = 3
        private const val CHUNK_RETRY_DELAY_MILLIS = 750L
    }
}

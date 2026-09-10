package io.stamethyst.backend.resources

import android.content.Context
import io.stamethyst.BuildConfig
import io.stamethyst.R
import io.stamethyst.backend.fs.FileTreeCleaner
import io.stamethyst.backend.github.WattToolkitAcceleratedHttp
import io.stamethyst.backend.diag.MemoryDiagnosticsLogger
import io.stamethyst.backend.github.GithubRequestClients
import io.stamethyst.backend.launch.StartupProgressCallback
import io.stamethyst.backend.launch.StartupTraceEvents
import io.stamethyst.backend.launch.progressText
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import io.stamethyst.backend.update.UpdateMirrorManager
import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.backend.update.toGithubMirrorHttpException
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

data class ResourcePackSlowDownloadMirrorSwitch(
    val currentSourceLabel: String,
    val nextSourceLabel: String,
    val nextPreferredMirrorSource: UpdateSource?
)

class ResourcePackDownloadMirrorSwitchController {
    private val switchRequests = AtomicLong(0L)
    private val currentCalls = CopyOnWriteArraySet<Call>()
    private val listeners =
        CopyOnWriteArraySet<(ResourcePackSlowDownloadMirrorSwitch?) -> Unit>()

    @Volatile
    private var currentPrompt: ResourcePackSlowDownloadMirrorSwitch? = null

    fun addSlowDownloadListener(listener: (ResourcePackSlowDownloadMirrorSwitch?) -> Unit) {
        listeners += listener
        listener(currentPrompt)
    }

    fun removeSlowDownloadListener(listener: (ResourcePackSlowDownloadMirrorSwitch?) -> Unit) {
        listeners -= listener
    }

    fun clearSlowDownloadPrompt() {
        publishSlowDownloadPrompt(null)
    }

    fun requestSwitchToNextMirror(): Boolean {
        switchRequests.incrementAndGet()
        publishSlowDownloadPrompt(null)
        currentCalls.forEach { it.cancel() }
        return true
    }

    internal fun switchRequestVersion(): Long = switchRequests.get()

    internal fun hasSwitchRequestSince(version: Long): Boolean =
        switchRequests.get() != version

    internal fun publishSlowDownloadPrompt(prompt: ResourcePackSlowDownloadMirrorSwitch?) {
        if (currentPrompt == prompt) {
            return
        }
        currentPrompt = prompt
        listeners.forEach { listener ->
            listener(prompt)
        }
    }

    internal fun trackCall(call: Call) {
        currentCalls.add(call)
    }

    internal fun clearCall(call: Call) {
        currentCalls.remove(call)
    }
}

object ExternalResourcePackService {
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val PROBE_CONNECT_TIMEOUT_MS = 4_000
    private const val PROBE_READ_TIMEOUT_MS = 6_000
    private const val MAX_PROBE_PARALLELISM = 8
    private const val USER_AGENT = "SlayTheAmethyst-ResourcePack"
    private const val DOWNLOAD_PROGRESS_REPORT_STEP_BYTES = 256L * 1024L
    private const val SLOW_DOWNLOAD_WINDOW_NANOS = 10_000_000_000L
    private const val SLOW_DOWNLOAD_THRESHOLD_BYTES_PER_SECOND = 512L * 1024L
    private const val DEFAULT_CHUNK_COUNT = 4
    private const val MIN_CHUNKED_DOWNLOAD_THRESHOLD_BYTES = 4L * 1024L * 1024L

    val externalizedNativeLibraries: Set<String> = ResourcePackContract.nativeLibraries

    internal data class ConfiguredResourcePackDownloadCandidate(
        val displayName: String,
        val requestUrl: String,
        val usesGithubAcceleration: Boolean,
        val preferredMirrorSource: UpdateSource?
    )

    internal data class ResourcePackLinkProbeResult(
        val displayName: String,
        val requestUrl: String,
        val usesGithubAcceleration: Boolean,
        val preferredMirrorSource: UpdateSource?,
        val reachable: Boolean,
        val elapsedNanos: Long,
        val candidateIndex: Int,
        val error: Throwable?
    )

    internal data class ResourcePackDownloadCandidate(
        val displayName: String,
        val requestUrl: String,
        val usesGithubAcceleration: Boolean,
        val preferredMirrorSource: UpdateSource?,
        val elapsedNanos: Long,
        val candidateIndex: Int
    )

    private data class ResourcePackDownloadFailure(
        val sourceLabel: String,
        val error: Throwable
    )

    internal fun orderResourcePackDownloadCandidates(
        probeResults: List<ResourcePackLinkProbeResult>
    ): List<ResourcePackDownloadCandidate> {
        return probeResults
            .asSequence()
            .filter(ResourcePackLinkProbeResult::reachable)
            .sortedWith(
                compareBy<ResourcePackLinkProbeResult> { it.elapsedNanos }
                    .thenBy { it.candidateIndex }
            )
            .map { result ->
                ResourcePackDownloadCandidate(
                    displayName = result.displayName,
                    requestUrl = result.requestUrl,
                    usesGithubAcceleration = result.usesGithubAcceleration,
                    preferredMirrorSource = result.preferredMirrorSource,
                    elapsedNanos = result.elapsedNanos,
                    candidateIndex = result.candidateIndex
                )
            }
            .toList()
    }

    internal fun buildResourcePackDownloadCandidates(
        resourcePackUrls: List<String>,
        preferredSource: UpdateSource,
        bypassAcceleratedLinks: Boolean
    ): List<ConfiguredResourcePackDownloadCandidate> {
        val normalizedUrls = resourcePackUrls
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        val githubMirrorCandidates = UpdateSource.downloadCandidates(
            preferredUserSource = preferredSource,
            metadataSource = preferredSource,
            bypassAcceleratedLinks = bypassAcceleratedLinks
        )
        return buildList {
            normalizedUrls.forEach { resourcePackUrl ->
                if (UpdateSource.isMirrorableGithubUrl(resourcePackUrl)) {
                    githubMirrorCandidates.forEach { source ->
                        add(
                            ConfiguredResourcePackDownloadCandidate(
                                displayName = source.displayName,
                                requestUrl = source.buildUrl(resourcePackUrl),
                                usesGithubAcceleration = source.usesGithubAcceleration,
                                preferredMirrorSource = source.takeIf { it.userSelectable }
                            )
                        )
                    }
                } else {
                    add(
                        ConfiguredResourcePackDownloadCandidate(
                            displayName = directResourcePackSourceName(resourcePackUrl),
                            requestUrl = resourcePackUrl,
                            usesGithubAcceleration = false,
                            preferredMirrorSource = null
                        )
                    )
                }
            }
        }.distinctBy { candidate ->
            // Watt direct access and the bare origin resolve to the same URL, so
            // keying only on the URL dropped the unaccelerated origin attempt.
            // They are different transports and both must stay in the chain.
            candidate.requestUrl to candidate.usesGithubAcceleration
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun ensureAvailable(context: Context) {
        ensureAvailable(context, null)
    }

    /** Forces a fresh download even when the currently installed pack passes validation. */
    @JvmStatic
    @Throws(IOException::class)
    fun reinstall(
        context: Context,
        progressCallback: StartupProgressCallback? = null,
        mirrorSwitchController: ResourcePackDownloadMirrorSwitchController? = null
    ) {
        ensureAvailable(
            context = context,
            progressCallback = progressCallback,
            mirrorSwitchController = mirrorSwitchController,
            forceReinstall = true
        )
    }

    @JvmStatic
    fun isAvailable(context: Context): Boolean {
        return runCatching { ResourcePackStore.inspect(context).ready }.getOrDefault(false)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun ensureAvailable(context: Context, progressCallback: StartupProgressCallback?) {
        ensureAvailable(context, progressCallback, null)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun ensureAvailable(
        context: Context,
        progressCallback: StartupProgressCallback?,
        mirrorSwitchController: ResourcePackDownloadMirrorSwitchController?,
        forceReinstall: Boolean = false
    ) {
        ResourcePackStore.withExclusiveLock(context) {
            MemoryDiagnosticsLogger.logEvent(
                context = context,
                event = "resource_pack_prepare_started",
                extras = mapOf("expectedVersion" to BuildConfig.RESOURCE_PACK_VERSION),
                includeMemorySnapshot = false
            )
            StartupTraceEvents.append(
                context = context,
                event = "resource_pack_prepare_started",
                extras = mapOf("expectedVersion" to BuildConfig.RESOURCE_PACK_VERSION)
            )
            try {
                ensureAvailableLocked(
                    context,
                    progressCallback,
                    mirrorSwitchController,
                    forceReinstall
                )
                MemoryDiagnosticsLogger.logEvent(
                    context = context,
                    event = "resource_pack_prepare_completed",
                    extras = mapOf(
                        "packId" to ResourcePackStore.activePackId(context),
                        "generation" to ResourcePackStore.activeGenerationDir(context)?.absolutePath
                    ),
                    includeMemorySnapshot = false
                )
                StartupTraceEvents.append(
                    context = context,
                    event = "resource_pack_prepare_completed",
                    extras = mapOf("packId" to ResourcePackStore.activePackId(context).orEmpty())
                )
            } catch (error: Throwable) {
                MemoryDiagnosticsLogger.logEvent(
                    context = context,
                    event = "resource_pack_prepare_failed",
                    extras = mapOf(
                        "errorClass" to error.javaClass.name,
                        "errorMessage" to error.message,
                        "state" to ResourcePackStore.buildDiagnostics(context)
                    ),
                    includeMemorySnapshot = false
                )
                StartupTraceEvents.append(
                    context = context,
                    event = "resource_pack_prepare_failed",
                    extras = mapOf(
                        "errorClass" to error.javaClass.name,
                        "errorMessage" to error.message.orEmpty()
                    )
                )
                throw error
            }
        }
    }

    private fun ensureAvailableLocked(
        context: Context,
        progressCallback: StartupProgressCallback?,
        mirrorSwitchController: ResourcePackDownloadMirrorSwitchController?,
        forceReinstall: Boolean
    ) {
        throwIfInterrupted()
        ResourcePackStore.recover(context)
        RuntimePaths.ensureBaseDirs(context)
        reportProgress(
            progressCallback,
            4,
            context.progressText(R.string.startup_progress_checking_external_resources)
        )

        val inspection = ResourcePackStore.inspect(context)
        // Reuse the installed generation whenever the hosted pack version still
        // matches. App updates must not bump resourcePack.version unless the
        // zip at RESOURCE_PACK_DOWNLOAD_URL actually changed.
        if (inspection.ready && !forceReinstall) {
            reportProgress(
                progressCallback,
                100,
                context.progressText(R.string.startup_progress_external_resources_available)
            )
            return
        }

        var embeddedFailure: Throwable? = null
        if (embeddedResourcePackExists(context)) {
            try {
                installEmbeddedResourcePack(
                    context = context,
                    progressCallback = progressCallback
                )
                reportProgress(
                    progressCallback,
                    100,
                    context.progressText(R.string.startup_progress_external_resources_ready)
                )
                return
            } catch (error: Throwable) {
                embeddedFailure = error
                MemoryDiagnosticsLogger.logEvent(
                    context = context,
                    event = "resource_pack_embedded_archive_failed",
                    extras = mapOf(
                        "errorClass" to error.javaClass.name,
                        "errorMessage" to error.message
                    ),
                    includeMemorySnapshot = false
                )
            }
        }

        val resourcePackUrls = BuildConfig.RESOURCE_PACK_DOWNLOAD_URLS
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        if (resourcePackUrls.isEmpty()) {
            throw IOException(
                "External resource pack is required but RESOURCE_PACK_DOWNLOAD_URLS is not configured. " +
                    "External pack issues: ${inspection.issues.joinToString(", ")}" +
                    (embeddedFailure?.let { " Embedded archive failed: ${summarizeResourcePackError(it)}" } ?: ""),
                embeddedFailure
            )
        }

        val stagingRoot = File(
            RuntimePaths.externalResourcesStagingRoot(context),
            "download-${System.nanoTime()}"
        )
        val downloadFile = File(stagingRoot, "resources.zip")
        prepareCleanDirectory(stagingRoot)
        try {
            // The candidate is not considered successful until its archive is installed and
            // validated. A reachable mirror can still serve a stale or corrupt archive.
            downloadResourcePack(
                context = context,
                resourcePackUrls = resourcePackUrls,
                targetFile = downloadFile,
                progressCallback = progressCallback,
                mirrorSwitchController = mirrorSwitchController
            )
        } finally {
            deleteTreeForCleanup(stagingRoot)
        }

        reportProgress(
            progressCallback,
            100,
            context.progressText(R.string.startup_progress_external_resources_ready)
        )
    }

    fun isExternalizedNativeLibrary(libraryName: String): Boolean =
        libraryName in externalizedNativeLibraries

    @JvmStatic
    @Throws(IOException::class)
    fun installNativeLibraries(context: Context) {
        ResourcePackStore.withExclusiveLock(context) {
            val generation = ResourcePackStore.activeGenerationDir(context)
                ?: throw IOException("No active external resource generation is installed")
            val packId = generation.name
            val sourceDir = File(File(generation, "lib"), ResourcePackContract.ABI)
            val targetDir = RuntimePaths.externalNativeLibDir(context)
            if (nativeLibrariesAreCurrent(targetDir, packId, sourceDir)) {
                return@withExclusiveLock
            }

            val parent = targetDir.parentFile
                ?: throw IOException("External native library directory has no parent")
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Failed to create external native library parent: ${parent.absolutePath}")
            }
            val stagingDir = File(parent, ".natives-staging-${System.nanoTime()}")
            val backupDir = File(parent, ".natives-backup-${System.nanoTime()}")
            prepareCleanDirectory(stagingDir)
            var installed = false
            try {
                copyNonExternalNativeFiles(targetDir, stagingDir)
                externalizedNativeLibraries.forEach { libraryName ->
                    val source = File(sourceDir, libraryName)
                    if (!source.isFile || source.length() <= 0L) {
                        throw IOException("Missing external resource native library: ${source.absolutePath}")
                    }
                    val target = File(stagingDir, libraryName)
                    copyFile(source, target)
                    if (!target.setExecutable(true, false)) {
                        throw IOException("Failed to mark native library executable: ${target.absolutePath}")
                    }
                }
                File(stagingDir, ".resource-pack-id").writeText(
                    "packId=$packId\n",
                    Charsets.UTF_8
                )
                if (!nativeLibrariesAreCurrent(stagingDir, packId, sourceDir)) {
                    throw IOException("Staged external native libraries failed validation")
                }
                if (targetDir.exists() && !targetDir.renameTo(backupDir)) {
                    throw IOException("Failed to preserve existing external native libraries")
                }
                if (!stagingDir.renameTo(targetDir)) {
                    throw IOException("Failed to activate external native libraries")
                }
                installed = true
                deleteTreeForCleanup(backupDir)
            } catch (error: Throwable) {
                if (!installed && !targetDir.exists() && backupDir.exists() &&
                    !backupDir.renameTo(targetDir)
                ) {
                    throw IOException(
                        "Failed to restore external native libraries after activation error",
                        error
                    )
                }
                throw error
            } finally {
                if (!installed && !targetDir.exists() && backupDir.exists()) {
                    backupDir.renameTo(targetDir)
                }
                deleteTreeForCleanup(stagingDir)
                if (installed || targetDir.exists()) {
                    deleteTreeForCleanup(backupDir)
                }
            }
        }
    }

    private fun embeddedResourcePackExists(context: Context): Boolean {
        return try {
            context.assets.open(ResourcePackContract.EMBEDDED_ARCHIVE_ASSET_PATH).use { }
            true
        } catch (_: IOException) {
            false
        }
    }

    @Throws(IOException::class)
    private fun installEmbeddedResourcePack(
        context: Context,
        progressCallback: StartupProgressCallback?
    ) {
        val stagingRoot = File(
            RuntimePaths.externalResourcesStagingRoot(context),
            "embedded-staging-${System.nanoTime()}"
        )
        val archiveFile = File(stagingRoot, "resources.zip")
        prepareCleanDirectory(stagingRoot)
        try {
            copyAssetToFile(
                context = context,
                assetPath = ResourcePackContract.EMBEDDED_ARCHIVE_ASSET_PATH,
                targetFile = archiveFile
            )
            ResourcePackStore.installArchive(
                context = context,
                archiveFile = archiveFile,
                progressCallback = progressCallback,
                source = "embedded-apk"
            )
        } finally {
            deleteTreeForCleanup(stagingRoot)
        }
    }

    @Throws(IOException::class)
    private fun copyAssetToFile(context: Context, assetPath: String, targetFile: File) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        context.assets.open(assetPath).use { input ->
            FileOutputStream(targetFile, false).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copiedBytes = 0L
                while (true) {
                    throwIfInterrupted()
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (read == 0) {
                        continue
                    }
                    copiedBytes += read
                    if (copiedBytes > ResourcePackContract.MAX_ARCHIVE_BYTES) {
                        throw IOException("Embedded resource pack archive is too large")
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun downloadResourcePack(
        context: Context,
        resourcePackUrls: List<String>,
        targetFile: File,
        progressCallback: StartupProgressCallback?,
        mirrorSwitchController: ResourcePackDownloadMirrorSwitchController?
    ) {
        val downloadClients = WattToolkitAcceleratedHttp.createClientPair(
            context = context,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            followRedirects = true
        )
        val probeClients = WattToolkitAcceleratedHttp.createClientPair(
            context = context,
            connectTimeoutMs = PROBE_CONNECT_TIMEOUT_MS,
            readTimeoutMs = PROBE_READ_TIMEOUT_MS,
            followRedirects = true
        )
        val preferredSource = UpdateMirrorManager.current(context)
        val bypassAcceleratedLinks = NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(context)
        val candidates = buildResourcePackDownloadCandidates(
            resourcePackUrls = resourcePackUrls,
            preferredSource = preferredSource,
            bypassAcceleratedLinks = bypassAcceleratedLinks
        )
        val orderedCandidates = probeResourcePackDownloadCandidates(
            clients = probeClients,
            candidates = candidates,
            progressCallback = progressCallback,
            context = context
        )
        val failures = ArrayList<ResourcePackDownloadFailure>()
        for ((index, candidate) in orderedCandidates.withIndex()) {
            throwIfInterrupted()
            mirrorSwitchController?.publishSlowDownloadPrompt(null)
            reportProgress(
                progressCallback,
                10,
                context.progressText(
                    R.string.startup_progress_selected_external_resource_link,
                    candidate.displayName
                )
            )
            MemoryDiagnosticsLogger.logEvent(
                context = context,
                event = "resource_pack_download_candidate_started",
                extras = mapOf(
                    "source" to candidate.displayName,
                    "url" to candidate.requestUrl
                ),
                includeMemorySnapshot = false
            )
            try {
                downloadFile(
                    client = downloadClients.pick(candidate.usesGithubAcceleration),
                    requestUrl = candidate.requestUrl,
                    targetFile = targetFile,
                    progressCallback = progressCallback,
                    context = context,
                    mirrorSwitchContext = mirrorSwitchController?.let { controller ->
                        ResourcePackDownloadMirrorSwitchContext(
                            controller = controller,
                            switchRequestVersion = controller.switchRequestVersion(),
                            currentSourceLabel = candidate.displayName,
                            nextSourceLabel = orderedCandidates.getOrNull(index + 1)?.displayName,
                            nextPreferredMirrorSource = orderedCandidates.getOrNull(index + 1)?.preferredMirrorSource
                        )
                    }
                )
                ResourcePackStore.installArchive(
                    context = context,
                    archiveFile = targetFile,
                    progressCallback = progressCallback,
                    source = "download:${candidate.displayName}"
                )
                mirrorSwitchController?.publishSlowDownloadPrompt(null)
                return
            } catch (error: Throwable) {
                if (error is ResourcePackMirrorSwitchRequestedException) {
                    continue
                }
                failures += ResourcePackDownloadFailure(candidate.displayName, error)
                MemoryDiagnosticsLogger.logEvent(
                    context = context,
                    event = "resource_pack_download_candidate_failed",
                    extras = mapOf(
                        "source" to candidate.displayName,
                        "errorClass" to error.javaClass.name,
                        "errorMessage" to error.message
                    ),
                    includeMemorySnapshot = false
                )
            } finally {
                mirrorSwitchController?.publishSlowDownloadPrompt(null)
            }
        }
        throw ResourcePackDownloadFallbackException(failures)
    }

    private fun probeResourcePackDownloadCandidates(
        clients: GithubRequestClients,
        candidates: List<ConfiguredResourcePackDownloadCandidate>,
        progressCallback: StartupProgressCallback?,
        context: Context
    ): List<ResourcePackDownloadCandidate> {
        throwIfInterrupted()
        val total = candidates.size
        val completedCount = AtomicInteger(0)
        reportProgress(
            progressCallback,
            6,
            context.progressText(
                R.string.startup_progress_checking_external_resource_links,
                0,
                total,
                ""
            )
        )

        // Launch all probes in parallel so the total wait is bounded by the
        // slowest single candidate, not the sum of all candidates.
        val threadCount = total.coerceAtMost(MAX_PROBE_PARALLELISM).coerceAtLeast(1)
        val executor = Executors.newFixedThreadPool(threadCount)
        val futures: List<Future<ResourcePackLinkProbeResult>> = candidates.mapIndexed { index, candidate ->
            executor.submit<ResourcePackLinkProbeResult> {
                val result = probeResourcePackLink(
                    client = clients.pick(candidate.usesGithubAcceleration),
                    candidate = candidate,
                    candidateIndex = index
                )
                val done = completedCount.incrementAndGet()
                reportProgress(
                    progressCallback,
                    6 + ((done * 4) / total.coerceAtLeast(1)),
                    context.progressText(
                        R.string.startup_progress_checking_external_resource_links,
                        done,
                        total,
                        candidate.displayName
                    )
                )
                result
            }
        }

        val results: List<ResourcePackLinkProbeResult> = try {
            futures.map { future ->
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    // probeResourcePackLink wraps all errors via runCatching, so
                    // ExecutionException here means an unexpected runtime failure.
                    throw e.cause ?: e
                }
            }
        } catch (e: InterruptedException) {
            // Parent thread was cancelled — cancel in-flight probes and propagate.
            futures.forEach { it.cancel(true) }
            Thread.currentThread().interrupt()
            throw IOException("External resource preparation cancelled", e)
        } finally {
            executor.shutdownNow()
        }

        return orderResourcePackDownloadCandidates(results)
            .ifEmpty {
                throw ResourcePackDownloadFallbackException(
                    results.map { result ->
                        ResourcePackDownloadFailure(
                            sourceLabel = result.displayName,
                            error = result.error ?: IOException("Resource pack link is unreachable.")
                        )
                    }
                )
            }
    }

    private fun probeResourcePackLink(
        client: OkHttpClient,
        candidate: ConfiguredResourcePackDownloadCandidate,
        candidateIndex: Int,
    ): ResourcePackLinkProbeResult {
        val startedAtNs = System.nanoTime()
        return runCatching {
            if (!isResourcePackLinkReachable(client, candidate.requestUrl)) {
                throw IOException("Resource pack link is unreachable.")
            }
            ResourcePackLinkProbeResult(
                displayName = candidate.displayName,
                requestUrl = candidate.requestUrl,
                usesGithubAcceleration = candidate.usesGithubAcceleration,
                preferredMirrorSource = candidate.preferredMirrorSource,
                reachable = true,
                elapsedNanos = System.nanoTime() - startedAtNs,
                candidateIndex = candidateIndex,
                error = null
            )
        }.getOrElse { error ->
            ResourcePackLinkProbeResult(
                displayName = candidate.displayName,
                requestUrl = candidate.requestUrl,
                usesGithubAcceleration = candidate.usesGithubAcceleration,
                preferredMirrorSource = candidate.preferredMirrorSource,
                reachable = false,
                elapsedNanos = System.nanoTime() - startedAtNs,
                candidateIndex = candidateIndex,
                error = error
            )
        }
    }

    private fun isResourcePackLinkReachable(client: OkHttpClient, requestUrl: String): Boolean {
        return requestResourcePackProbe(client, requestUrl, "HEAD") ||
            requestResourcePackRangeProbe(client, requestUrl)
    }

    private fun requestResourcePackProbe(
        client: OkHttpClient,
        requestUrl: String,
        method: String,
    ): Boolean {
        val requestBuilder = Request.Builder()
            .url(requestUrl)
            .header("User-Agent", USER_AGENT)
        val request = if (method.equals("HEAD", ignoreCase = true)) {
            requestBuilder.head().build()
        } else {
            requestBuilder.method(method, null).build()
        }
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun requestResourcePackRangeProbe(
        client: OkHttpClient,
        requestUrl: String,
    ): Boolean {
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=0-0")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 206
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Dispatches to chunked parallel download when the server supports Range requests and the
     * file is large enough to benefit from it; otherwise falls back to a single-stream download.
     */
    @Throws(IOException::class)
    private fun downloadFile(
        client: OkHttpClient,
        requestUrl: String,
        targetFile: File,
        progressCallback: StartupProgressCallback?,
        context: Context,
        mirrorSwitchContext: ResourcePackDownloadMirrorSwitchContext?
    ) {
        val contentLength = fetchRangeSupportedContentLength(client, requestUrl)
        if (contentLength != null) {
            ResourcePackContract.requireArchiveBytes(contentLength)
        }
        if (contentLength != null && contentLength >= MIN_CHUNKED_DOWNLOAD_THRESHOLD_BYTES) {
            downloadFileChunked(
                client = client,
                requestUrl = requestUrl,
                targetFile = targetFile,
                contentLength = contentLength,
                progressCallback = progressCallback,
                context = context,
                mirrorSwitchContext = mirrorSwitchContext
            )
            return
        }
        downloadFileSingleStream(
            client = client,
            requestUrl = requestUrl,
            targetFile = targetFile,
            progressCallback = progressCallback,
            context = context,
            mirrorSwitchContext = mirrorSwitchContext
        )
    }

    /**
     * Returns the content length of [requestUrl] if the server advertises Range support,
     * or null if Range is unsupported or the length is unknown.
     */
    private fun fetchRangeSupportedContentLength(client: OkHttpClient, requestUrl: String): Long? {
        val request = Request.Builder()
            .url(requestUrl)
            .head()
            .header("User-Agent", USER_AGENT)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val acceptRanges = response.header("Accept-Ranges")
                    ?.split(',')
                    ?.any { value -> value.trim().equals("bytes", ignoreCase = true) }
                    ?: false
                if (!acceptRanges) return null
                response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseContentRange(value: String?): Triple<Long, Long, Long>? {
        val match = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+)$", RegexOption.IGNORE_CASE)
            .matchEntire(value?.trim().orEmpty())
            ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (start < 0L || end < start || total <= end) return null
        return Triple(start, end, total)
    }

    /**
     * Downloads [requestUrl] into [targetFile] by issuing [DEFAULT_CHUNK_COUNT] concurrent
     * Range requests and writing each chunk directly to its byte offset in a pre-allocated
     * temp file, then atomically renaming it into place.
     */
    @Throws(IOException::class)
    private fun downloadFileChunked(
        client: OkHttpClient,
        requestUrl: String,
        targetFile: File,
        contentLength: Long,
        progressCallback: StartupProgressCallback?,
        context: Context,
        mirrorSwitchContext: ResourcePackDownloadMirrorSwitchContext?
    ) {
        throwIfInterrupted()
        mirrorSwitchContext?.throwIfSwitchRequested()
        mirrorSwitchContext?.markDownloadStarted()
        val slowDownloadTicker = mirrorSwitchContext?.startSlowDownloadTicker()

        val parent = targetFile.parentFile
            ?: throw IOException("Resource pack target has no parent: ${targetFile.absolutePath}")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        ResourcePackContract.requireArchiveBytes(contentLength)
        val tempFile = File(parent, "${targetFile.name}.part")
        // Pre-allocate the full file so random-access writes from each chunk are safe.
        java.io.RandomAccessFile(tempFile, "rw").use { raf -> raf.setLength(contentLength) }

        val chunkCount = DEFAULT_CHUNK_COUNT
        val chunkSize = (contentLength + chunkCount - 1) / chunkCount
        val totalBytesWritten = AtomicLong(0L)
        // Start below -step so the first 256 KB triggers a report.
        val lastReportedBytes = AtomicLong(-DOWNLOAD_PROGRESS_REPORT_STEP_BYTES)
        val downloadStartNanos = System.nanoTime()

        val executor = Executors.newFixedThreadPool(chunkCount)
        val futures: List<Future<Unit>> = (0 until chunkCount).map { chunkIndex ->
            val rangeStart = chunkIndex * chunkSize
            val rangeEnd = minOf(rangeStart + chunkSize - 1, contentLength - 1)
            @Suppress("UNCHECKED_CAST")
            executor.submit<Unit> {
                downloadChunk(
                    client = client,
                    requestUrl = requestUrl,
                    tempFile = tempFile,
                    rangeStart = rangeStart,
                    rangeEnd = rangeEnd,
                    totalBytesWritten = totalBytesWritten,
                    lastReportedBytes = lastReportedBytes,
                    contentLength = contentLength,
                    downloadStartNanos = downloadStartNanos,
                    progressCallback = progressCallback,
                    context = context,
                    mirrorSwitchContext = mirrorSwitchContext
                )
            } as Future<Unit>
        }

        try {
            for (future in futures) {
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    // Cancel all remaining in-flight chunk requests immediately.
                    executor.shutdownNow()
                    futures.forEach { it.cancel(true) }
                    throw e.cause ?: e
                }
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            futures.forEach { it.cancel(true) }
            Thread.currentThread().interrupt()
            throw IOException("External resource preparation cancelled", e)
        } finally {
            slowDownloadTicker?.close()
            executor.shutdownNow()
        }

        if (totalBytesWritten.get() != contentLength) {
            tempFile.delete()
            throw IOException(
                "Resource pack download size mismatch: ${totalBytesWritten.get()}/$contentLength bytes"
            )
        }

        if (targetFile.exists() && !targetFile.delete()) {
            tempFile.delete()
            throw IOException("Failed to replace file: ${targetFile.absolutePath}")
        }
        if (!tempFile.renameTo(targetFile)) {
            copyFile(tempFile, targetFile)
            tempFile.delete()
        }
    }

    /** Downloads a single byte-range chunk of [requestUrl] and writes it to [tempFile]. */
    @Throws(IOException::class)
    private fun downloadChunk(
        client: OkHttpClient,
        requestUrl: String,
        tempFile: File,
        rangeStart: Long,
        rangeEnd: Long,
        totalBytesWritten: AtomicLong,
        lastReportedBytes: AtomicLong,
        contentLength: Long,
        downloadStartNanos: Long,
        progressCallback: StartupProgressCallback?,
        context: Context,
        mirrorSwitchContext: ResourcePackDownloadMirrorSwitchContext?
    ) {
        throwIfInterrupted()
        mirrorSwitchContext?.throwIfSwitchRequested()
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=$rangeStart-$rangeEnd")
            .build()
        val call = client.newCall(request)
        mirrorSwitchContext?.controller?.trackCall(call)
        try {
            call.execute().use { response ->
                mirrorSwitchContext?.throwIfSwitchRequested()
                if (response.code != 206) {
                    throw IOException(
                        "Expected HTTP 206 for Range request, got ${response.code}"
                    )
                }
                val contentRange = parseContentRange(response.header("Content-Range"))
                    ?: throw IOException("Range response is missing a valid Content-Range header")
                if (contentRange.first != rangeStart ||
                    contentRange.second != rangeEnd ||
                    contentRange.third != contentLength
                ) {
                    throw IOException(
                        "Range response mismatch: expected $rangeStart-$rangeEnd/$contentLength, " +
                            "got ${contentRange.first}-${contentRange.second}/${contentRange.third}"
                    )
                }
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var chunkBytes = 0L
                response.body.byteStream().use { input ->
                    java.io.RandomAccessFile(tempFile, "rw").use { raf ->
                        raf.seek(rangeStart)
                        while (true) {
                            throwIfInterrupted()
                            mirrorSwitchContext?.throwIfSwitchRequested()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            chunkBytes += read
                            if (chunkBytes > rangeEnd - rangeStart + 1L) {
                                throw IOException("Range response returned too many bytes")
                            }
                            raf.write(buffer, 0, read)
                            val total = totalBytesWritten.addAndGet(read.toLong())
                            mirrorSwitchContext?.recordDownloadProgress(total)
                            val prev = lastReportedBytes.get()
                            val shouldReport =
                                total - prev >= DOWNLOAD_PROGRESS_REPORT_STEP_BYTES ||
                                    total >= contentLength
                            if (shouldReport && lastReportedBytes.compareAndSet(prev, total)) {
                                val elapsedNanos = System.nanoTime() - downloadStartNanos
                                val speedText = if (elapsedNanos >= 500_000_000L) {
                                    " · " + formatBytes(
                                        total * 1_000_000_000L / elapsedNanos
                                    ) + "/s"
                                } else {
                                    ""
                                }
                                reportProgress(
                                    progressCallback,
                                    mapDownloadPercent(total, contentLength),
                                    context.progressText(
                                        R.string.startup_progress_downloading_external_resources,
                                        formatBytes(total),
                                        formatBytes(contentLength),
                                        speedText
                                    )
                                )
                            }
                        }
                    }
                }
                if (chunkBytes != rangeEnd - rangeStart + 1L) {
                    throw IOException(
                        "Range response returned $chunkBytes bytes, expected ${rangeEnd - rangeStart + 1L}"
                    )
                }
            }
        } catch (error: Throwable) {
            if (mirrorSwitchContext?.isSwitchRequested() == true) {
                throw ResourcePackMirrorSwitchRequestedException()
            }
            throw error
        } finally {
            mirrorSwitchContext?.controller?.clearCall(call)
        }
    }

    @Throws(IOException::class)
    private fun downloadFileSingleStream(
        client: OkHttpClient,
        requestUrl: String,
        targetFile: File,
        progressCallback: StartupProgressCallback?,
        context: Context,
        mirrorSwitchContext: ResourcePackDownloadMirrorSwitchContext?
    ) {
        throwIfInterrupted()
        mirrorSwitchContext?.throwIfSwitchRequested()
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("User-Agent", USER_AGENT)
            .build()
        val call = client.newCall(request)
        mirrorSwitchContext?.controller?.trackCall(call)
        mirrorSwitchContext?.markDownloadStarted()
        val slowDownloadTicker = mirrorSwitchContext?.startSlowDownloadTicker()
        try {
            call.execute().use { response ->
                mirrorSwitchContext?.throwIfSwitchRequested()
                if (!response.isSuccessful) {
                    throw response.toGithubMirrorHttpException()
                }
                val parent = targetFile.parentFile
                    ?: throw IOException("Resource pack target has no parent: ${targetFile.absolutePath}")
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IOException("Failed to create directory: ${parent.absolutePath}")
                }
                val tempFile = File(parent, "${targetFile.name}.part")
                val totalBytes = response.body.contentLength().takeIf { it > 0L }
                if (totalBytes != null) {
                    ResourcePackContract.requireArchiveBytes(totalBytes)
                }
                var downloadedBytes = 0L
                response.body.byteStream().use { input ->
                    FileOutputStream(tempFile, false).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var lastReportBytes = 0L
                        var downloadStartNanos = -1L
                        while (true) {
                            throwIfInterrupted()
                            mirrorSwitchContext?.throwIfSwitchRequested()
                            val read = input.read(buffer)
                            if (read < 0) {
                                break
                            }
                            if (read == 0) {
                                continue
                            }
                            if (downloadStartNanos < 0L) {
                                downloadStartNanos = System.nanoTime()
                            }
                            if (downloadedBytes + read > ResourcePackContract.MAX_ARCHIVE_BYTES) {
                                throw IOException("Resource pack download exceeded the archive size limit")
                            }
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            mirrorSwitchContext?.recordDownloadProgress(downloadedBytes)
                            val shouldReport = downloadedBytes - lastReportBytes >=
                                DOWNLOAD_PROGRESS_REPORT_STEP_BYTES ||
                                totalBytes?.let { downloadedBytes >= it } == true
                            if (shouldReport) {
                                val elapsedNanos = System.nanoTime() - downloadStartNanos
                                val speedText = if (elapsedNanos >= 500_000_000L) {
                                    " · " + formatBytes(downloadedBytes * 1_000_000_000L / elapsedNanos) + "/s"
                                } else {
                                    ""
                                }
                                reportProgress(
                                    progressCallback,
                                    mapDownloadPercent(downloadedBytes, totalBytes),
                                    context.progressText(
                                        R.string.startup_progress_downloading_external_resources,
                                        formatBytes(downloadedBytes),
                                        totalBytes?.let(::formatBytes).orEmpty(),
                                        speedText
                                    )
                                )
                                lastReportBytes = downloadedBytes
                            }
                        }
                    }
                }
                if (totalBytes != null && downloadedBytes != totalBytes) {
                    tempFile.delete()
                    throw IOException(
                        "Resource pack download size mismatch: $downloadedBytes/$totalBytes bytes"
                    )
                }
                if (targetFile.exists() && !targetFile.delete()) {
                    tempFile.delete()
                    throw IOException("Failed to replace file: ${targetFile.absolutePath}")
                }
                if (!tempFile.renameTo(targetFile)) {
                    copyFile(tempFile, targetFile)
                    tempFile.delete()
                }
            }
        } catch (error: Throwable) {
            if (mirrorSwitchContext?.isSwitchRequested() == true) {
                throw ResourcePackMirrorSwitchRequestedException()
            }
            throw error
        } finally {
            slowDownloadTicker?.close()
            mirrorSwitchContext?.controller?.clearCall(call)
        }
    }

    private data class ResourcePackDownloadMirrorSwitchContext(
        val controller: ResourcePackDownloadMirrorSwitchController,
        val switchRequestVersion: Long,
        val currentSourceLabel: String,
        val nextSourceLabel: String?,
        val nextPreferredMirrorSource: UpdateSource?
    ) {
        private val speedMonitor = ResourcePackSlowDownloadSpeedMonitor()
        private val latestDownloadedBytes = AtomicLong(0L)

        fun markDownloadStarted() {
            val now = System.nanoTime()
            latestDownloadedBytes.set(0L)
            speedMonitor.reset(now, 0L)
        }

        fun startSlowDownloadTicker(): AutoCloseable? {
            if (nextSourceLabel == null) {
                return null
            }
            val running = AtomicBoolean(true)
            val thread = Thread({
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(1_000L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    recordDownloadProgress(latestDownloadedBytes.get())
                }
            }, "STS-ResourcePackSlowDownloadTicker").apply {
                isDaemon = true
                start()
            }
            return AutoCloseable {
                running.set(false)
                thread.interrupt()
            }
        }

        @Synchronized
        fun recordDownloadProgress(downloadedBytes: Long) {
            latestDownloadedBytes.set(downloadedBytes)
            if (nextSourceLabel == null) {
                return
            }
            if (speedMonitor.record(System.nanoTime(), downloadedBytes)) {
                controller.publishSlowDownloadPrompt(
                    ResourcePackSlowDownloadMirrorSwitch(
                        currentSourceLabel = currentSourceLabel,
                        nextSourceLabel = nextSourceLabel,
                        nextPreferredMirrorSource = nextPreferredMirrorSource
                    )
                )
            }
        }

        fun throwIfSwitchRequested() {
            if (isSwitchRequested()) {
                throw ResourcePackMirrorSwitchRequestedException()
            }
        }

        fun isSwitchRequested(): Boolean =
            controller.hasSwitchRequestSince(switchRequestVersion)
    }

    private data class ResourcePackDownloadSpeedSample(
        val timeNanos: Long,
        val downloadedBytes: Long
    )

    private class ResourcePackSlowDownloadSpeedMonitor {
        private val samples = ArrayDeque<ResourcePackDownloadSpeedSample>()

        fun reset(nowNanos: Long, downloadedBytes: Long) {
            samples.clear()
            samples.addLast(ResourcePackDownloadSpeedSample(nowNanos, downloadedBytes))
        }

        fun record(nowNanos: Long, downloadedBytes: Long): Boolean {
            if (samples.isEmpty()) {
                reset(nowNanos, downloadedBytes)
                return false
            }
            samples.addLast(ResourcePackDownloadSpeedSample(nowNanos, downloadedBytes))
            prune(nowNanos)
            val first = samples.peekFirst() ?: return false
            val elapsedNanos = nowNanos - first.timeNanos
            if (elapsedNanos < SLOW_DOWNLOAD_WINDOW_NANOS) {
                return false
            }
            val bytesInWindow = (downloadedBytes - first.downloadedBytes).coerceAtLeast(0L)
            val bytesPerSecond = bytesInWindow.toDouble() * 1_000_000_000.0 / elapsedNanos.toDouble()
            return bytesPerSecond < SLOW_DOWNLOAD_THRESHOLD_BYTES_PER_SECOND
        }

        private fun prune(nowNanos: Long) {
            while (samples.size > 1) {
                val first = samples.removeFirst()
                val second = samples.peekFirst()
                if (second != null && nowNanos - second.timeNanos >= SLOW_DOWNLOAD_WINDOW_NANOS) {
                    continue
                }
                samples.addFirst(first)
                break
            }
        }
    }

    private class ResourcePackMirrorSwitchRequestedException : IOException(
        "Resource pack mirror switch requested."
    )

    private class ResourcePackDownloadFallbackException(
        failures: List<ResourcePackDownloadFailure>
    ) : IOException(
        failures.joinToString(separator = " | ") { failure ->
            "${failure.sourceLabel}: ${summarizeResourcePackError(failure.error)}"
        }.ifBlank { "No resource pack download candidates succeeded." },
        failures.lastOrNull()?.error
    )

    private fun directResourcePackSourceName(requestUrl: String): String {
        val host = runCatching { URL(requestUrl).host.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        return when {
            host == "gitee.com" || host.endsWith(".gitee.com") -> "Gitee"
            host.isNotEmpty() -> host
            else -> "Direct"
        }
    }

    private fun summarizeResourcePackError(error: Throwable): String {
        return error.message
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error.javaClass.simpleName
    }

    private fun mapDownloadPercent(downloadedBytes: Long, totalBytes: Long?): Int {
        if (totalBytes == null || totalBytes <= 0L) {
            return 18
        }
        val bounded = downloadedBytes.coerceIn(0L, totalBytes)
        return 10 + ((bounded * 58L) / totalBytes).toInt().coerceIn(0, 58)
    }

    @Throws(IOException::class)
    private fun prepareCleanDirectory(directory: File) {
        val parent = directory.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        if (directory.exists()) {
            deleteTreeForCleanup(directory)
            if (directory.exists()) {
                throw IOException("Failed to clean staging directory: ${directory.absolutePath}")
            }
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Failed to create directory: ${directory.absolutePath}")
        }
    }

    private fun nativeLibrariesAreCurrent(directory: File, packId: String, sourceDir: File): Boolean {
        if (!directory.isDirectory) {
            return false
        }
        val marker = File(directory, ".resource-pack-id")
        if (!marker.isFile || marker.readText(Charsets.UTF_8).trim() != "packId=$packId") {
            return false
        }
        return runCatching {
            externalizedNativeLibraries.all { libraryName ->
                val source = File(sourceDir, libraryName)
                val target = File(directory, libraryName)
                source.isFile &&
                    source.length() > 0L &&
                    target.isFile &&
                    target.length() == source.length() &&
                    sha256(source) == sha256(target)
            }
        }.getOrDefault(false)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    @Throws(IOException::class)
    private fun copyNonExternalNativeFiles(sourceDir: File, targetDir: File) {
        if (!sourceDir.exists()) {
            return
        }
        if (!sourceDir.isDirectory) {
            throw IOException("Existing external native library path is not a directory: ${sourceDir.absolutePath}")
        }
        val sourceRoot = sourceDir.canonicalFile.toPath()
        sourceDir.walkTopDown().forEach { source ->
            if (!source.isFile || source.name == ".resource-pack-id") {
                return@forEach
            }
            if (source.name in externalizedNativeLibraries) {
                return@forEach
            }
            val relative = sourceRoot.relativize(source.canonicalFile.toPath())
                .toString()
                .replace(File.separatorChar, '/')
            val target = File(targetDir, relative)
            copyFile(source, target)
        }
    }

    private fun deleteTreeForCleanup(file: File?) {
        if (file == null || !file.exists()) {
            return
        }
        val wasInterrupted = Thread.interrupted()
        try {
            FileTreeCleaner.deleteRecursively(file)
            if (file.exists()) {
                FileTreeCleaner.deleteRecursively(file)
            }
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    @Throws(IOException::class)
    private fun copyFile(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output ->
                input.copyTo(output)
            }
        }
        target.setLastModified(source.lastModified())
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.coerceAtLeast(0L).toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            bytes.coerceAtLeast(0L).toString() + " " + units[unitIndex]
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

    @Throws(IOException::class)
    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw IOException("External resource preparation cancelled")
        }
    }

    private fun reportProgress(callback: StartupProgressCallback?, percent: Int, message: String) {
        callback?.onProgress(percent.coerceIn(0, 100), message)
    }
}

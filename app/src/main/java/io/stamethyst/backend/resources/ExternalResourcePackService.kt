package io.stamethyst.backend.resources

import android.content.Context
import io.stamethyst.BuildConfig
import io.stamethyst.R
import io.stamethyst.backend.fs.FileTreeCleaner
import io.stamethyst.backend.github.WattToolkitAcceleratedHttp
import io.stamethyst.backend.github.GithubRequestClients
import io.stamethyst.backend.launch.StartupProgressCallback
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
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
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

    private val externalizedAssetRootPaths = listOf(
        "components/jre",
        "components/lwjgl3",
        "components/log4j_runtime",
        "components/mods/ModTheSpire.jar",
        "components/mods/BaseMod.jar",
        "components/mods/StSLib.jar",
        "ui"
    )

    private val requiredCommonAssetFiles = listOf(
        "components/jre/version",
        "components/jre/universal.tar.xz",
        "components/lwjgl3/version",
        "components/lwjgl3/lwjgl-glfw-classes.jar",
        "components/log4j_runtime/log4j-api.jar",
        "components/log4j_runtime/log4j-core.jar",
        "components/mods/ModTheSpire.jar",
        "components/mods/BaseMod.jar",
        "components/mods/StSLib.jar",
        "ui/boot_bright.png",
        "ui/boot_dark.png",
        "ui/update_notice.png"
    )

    private val requiredRuntimeArchiveAlternatives = listOf(
        "components/jre/bin-aarch64.tar.xz",
        "components/jre/bin-arm64.tar.xz"
    )

    val externalizedNativeLibraries: Set<String> = linkedSetOf(
        "libEGL_mesa.so",
        "libOSMesa.so",
        "libVkLayer_khronos_timeline_semaphore.so",
        "libcutils.so",
        "libgdx-freetype.so",
        "libgdx.so",
        "libgl4es_114.so",
        "libglapi.so",
        "libglxshim.so",
        "libjnidispatch.so",
        "liblinkerhook.so",
        "libmobileglues.so",
        "libeasytier_android_jni.so",
        "libeasytier_ffi.so",
        "libspirv-cross-c-shared.so",
        "libvulkan_freedreno.so",
        "libzink_dri.so"
    )

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

    @JvmStatic
    fun isAvailable(context: Context): Boolean {
        return runCatching {
            collectExternalPackIssues(
                context = context,
                packRoot = RuntimePaths.externalResourcesCurrentDir(context)
            ).isEmpty()
        }.getOrDefault(false)
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
        mirrorSwitchController: ResourcePackDownloadMirrorSwitchController?
    ) {
        throwIfInterrupted()
        RuntimePaths.ensureBaseDirs(context)
        reportProgress(
            progressCallback,
            4,
            context.progressText(R.string.startup_progress_checking_external_resources)
        )

        val externalPackIssues = collectExternalPackIssues(
            context = context,
            packRoot = RuntimePaths.externalResourcesCurrentDir(context)
        )
        if (externalPackIssues.isEmpty()) {
            reportProgress(
                progressCallback,
                100,
                context.progressText(R.string.startup_progress_external_resources_available)
            )
            return
        }

        val bundledMissing = collectMissingBundledResources(context)
        if (bundledMissing.isEmpty()) {
            installBundledResources(
                context = context,
                progressCallback = progressCallback
            )
            reportProgress(
                progressCallback,
                100,
                context.progressText(R.string.startup_progress_external_resources_ready)
            )
            return
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
                    "Missing bundled resources: ${bundledMissing.joinToString(", ")}. " +
                    "External pack issues: ${externalPackIssues.joinToString(", ")}"
            )
        }

        val stagingRoot = File(
            RuntimePaths.externalResourcesRoot(context),
            "staging-${System.nanoTime()}"
        )
        val downloadFile = File(stagingRoot, "resources.zip")
        val extractedDir = File(stagingRoot, "current")
        prepareCleanDirectory(stagingRoot)
        try {
            downloadResourcePack(
                context = context,
                resourcePackUrls = resourcePackUrls,
                targetFile = downloadFile,
                progressCallback = progressCallback,
                mirrorSwitchController = mirrorSwitchController
            )
            throwIfInterrupted()
            reportProgress(
                progressCallback,
                72,
                context.progressText(R.string.startup_progress_extracting_external_resources, 0)
            )
            extractResourcePack(
                archiveFile = downloadFile,
                targetDir = extractedDir,
                progressCallback = progressCallback,
                context = context
            )
            val missingAfterExtract = collectMissingResourcePackContent(extractedDir)
            if (missingAfterExtract.isNotEmpty()) {
                throw IOException(
                    "Downloaded resource pack is incomplete. Missing: " +
                        missingAfterExtract.joinToString(", ")
                )
            }
            writeInstallMarker(context, extractedDir)
            installExtractedResources(
                context = context,
                extractedDir = extractedDir
            )
        } finally {
            FileTreeCleaner.deleteRecursively(stagingRoot)
        }

        reportProgress(
            progressCallback,
            100,
            context.progressText(R.string.startup_progress_external_resources_ready)
        )
    }

    fun isExternalizedNativeLibrary(libraryName: String): Boolean =
        libraryName in externalizedNativeLibraries

    private fun collectExternalPackIssues(context: Context, packRoot: File): List<String> {
        val missing = ArrayList<String>()
        missing += collectMissingResourcePackContent(packRoot)
        val markerVersion = readInstalledResourcePackVersion(
            File(packRoot, RuntimePaths.externalResourcesMarkerFile(context).name)
        )
        val expectedVersion = BuildConfig.RESOURCE_PACK_VERSION.trim()
        if (markerVersion != expectedVersion) {
            missing += "resource pack version $expectedVersion"
        }
        return missing
    }

    private fun collectMissingBundledResources(context: Context): List<String> {
        val missing = ArrayList<String>()
        requiredCommonAssetFiles.forEach { assetPath ->
            if (!bundledAssetFileExists(context, assetPath)) {
                missing += "assets/$assetPath"
            }
        }
        if (requiredRuntimeArchiveAlternatives.none { assetPath ->
                bundledAssetFileExists(context, assetPath)
            }
        ) {
            missing += "assets/components/jre/{bin-aarch64.tar.xz,bin-arm64.tar.xz}"
        }

        val appNativeDir = File(context.applicationInfo.nativeLibraryDir)
        externalizedNativeLibraries.forEach { libraryName ->
            if (!File(appNativeDir, libraryName).isFile) {
                missing += "lib/arm64-v8a/$libraryName"
            }
        }
        return missing
    }

    private fun collectMissingResourcePackContent(packRoot: File): List<String> {
        val missing = ArrayList<String>()
        requiredCommonAssetFiles.forEach { assetPath ->
            if (!File(File(packRoot, "assets"), assetPath).isFile) {
                missing += "assets/$assetPath"
            }
        }
        if (requiredRuntimeArchiveAlternatives.none { assetPath ->
                File(File(packRoot, "assets"), assetPath).isFile
            }
        ) {
            missing += "assets/components/jre/{bin-aarch64.tar.xz,bin-arm64.tar.xz}"
        }
        externalizedNativeLibraries.forEach { libraryName ->
            if (!File(externalNativeDir(packRoot), libraryName).isFile) {
                missing += "lib/arm64-v8a/$libraryName"
            }
        }
        return missing
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
                mirrorSwitchController?.publishSlowDownloadPrompt(null)
                return
            } catch (error: Throwable) {
                if (error is ResourcePackMirrorSwitchRequestedException) {
                    continue
                }
                failures += ResourcePackDownloadFailure(candidate.displayName, error)
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
                if (acceptRanges?.lowercase(Locale.ROOT) != "bytes") return null
                response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
            }
        } catch (_: Throwable) {
            null
        }
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
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                response.body.byteStream().use { input ->
                    java.io.RandomAccessFile(tempFile, "rw").use { raf ->
                        raf.seek(rangeStart)
                        while (true) {
                            throwIfInterrupted()
                            mirrorSwitchContext?.throwIfSwitchRequested()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
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
                response.body.byteStream().use { input ->
                    FileOutputStream(tempFile, false).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloadedBytes = 0L
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
    private fun extractResourcePack(
        archiveFile: File,
        targetDir: File,
        progressCallback: StartupProgressCallback?,
        context: Context
    ) {
        prepareCleanDirectory(targetDir)
        ZipFile(archiveFile).use { zipFile ->
            val entries = zipFile.entries().asSequence()
                .filterNot(ZipEntry::isDirectory)
                .toList()
            val totalEntries = entries.size.coerceAtLeast(1)
            entries.forEachIndexed { index, entry ->
                throwIfInterrupted()
                val targetFile = resolveZipTarget(targetDir, entry)
                val parent = targetFile.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw IOException("Failed to create directory: ${parent.absolutePath}")
                }
                zipFile.getInputStream(entry).use { input ->
                    FileOutputStream(targetFile, false).use { output ->
                        input.copyTo(output)
                    }
                }
                if (targetFile.name.endsWith(".so", ignoreCase = true)) {
                    targetFile.setExecutable(true, false)
                }
                val percent = ((index + 1) * 100 / totalEntries).coerceIn(0, 100)
                reportProgress(
                    progressCallback,
                    72 + ((percent * 24) / 100),
                    context.progressText(R.string.startup_progress_extracting_external_resources, percent)
                )
            }
        }
    }

    @Throws(IOException::class)
    private fun installBundledResources(
        context: Context,
        progressCallback: StartupProgressCallback?
    ) {
        val stagingRoot = File(
            RuntimePaths.externalResourcesRoot(context),
            "bundled-staging-${System.nanoTime()}"
        )
        val extractedDir = File(stagingRoot, "current")
        prepareCleanDirectory(stagingRoot)
        try {
            copyBundledResources(
                context = context,
                targetDir = extractedDir,
                progressCallback = progressCallback
            )
            val missingAfterCopy = collectMissingResourcePackContent(extractedDir)
            if (missingAfterCopy.isNotEmpty()) {
                throw IOException(
                    "Bundled resource pack is incomplete. Missing: " +
                        missingAfterCopy.joinToString(", ")
                )
            }
            writeInstallMarker(context, extractedDir)
            installExtractedResources(
                context = context,
                extractedDir = extractedDir
            )
        } finally {
            FileTreeCleaner.deleteRecursively(stagingRoot)
        }
    }

    @Throws(IOException::class)
    private fun copyBundledResources(
        context: Context,
        targetDir: File,
        progressCallback: StartupProgressCallback?
    ) {
        prepareCleanDirectory(targetDir)
        val totalSteps = (externalizedAssetRootPaths.size + externalizedNativeLibraries.size)
            .coerceAtLeast(1)
        var completedSteps = 0

        externalizedAssetRootPaths.forEach { assetRoot ->
            throwIfInterrupted()
            copyBundledAssetTree(
                context = context,
                assetPath = assetRoot,
                targetFile = File(File(targetDir, "assets"), assetRoot)
            )
            completedSteps++
            reportBundledCopyProgress(context, progressCallback, completedSteps, totalSteps)
        }

        val appNativeDir = File(context.applicationInfo.nativeLibraryDir)
        val targetNativeDir = externalNativeDir(targetDir)
        externalizedNativeLibraries.forEach { libraryName ->
            throwIfInterrupted()
            val sourceFile = File(appNativeDir, libraryName)
            if (!sourceFile.isFile) {
                throw IOException("Missing bundled native library: ${sourceFile.absolutePath}")
            }
            val targetFile = File(targetNativeDir, libraryName)
            copyFile(sourceFile, targetFile)
            targetFile.setExecutable(true, false)
            completedSteps++
            reportBundledCopyProgress(context, progressCallback, completedSteps, totalSteps)
        }
    }

    @Throws(IOException::class)
    private fun copyBundledAssetTree(context: Context, assetPath: String, targetFile: File) {
        val children = context.assets.list(assetPath)
            ?.filter(String::isNotEmpty)
            .orEmpty()
        if (children.isEmpty()) {
            copyBundledAssetFile(context, assetPath, targetFile)
            return
        }
        if (!targetFile.exists() && !targetFile.mkdirs()) {
            throw IOException("Failed to create directory: ${targetFile.absolutePath}")
        }
        children.forEach { childName ->
            copyBundledAssetTree(
                context = context,
                assetPath = "$assetPath/$childName",
                targetFile = File(targetFile, childName)
            )
        }
    }

    @Throws(IOException::class)
    private fun copyBundledAssetFile(context: Context, assetPath: String, targetFile: File) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        context.assets.open(assetPath).use { input ->
            FileOutputStream(targetFile, false).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun reportBundledCopyProgress(
        context: Context,
        progressCallback: StartupProgressCallback?,
        completedSteps: Int,
        totalSteps: Int
    ) {
        val percent = ((completedSteps * 100) / totalSteps).coerceIn(0, 100)
        reportProgress(
            progressCallback,
            8 + ((percent * 88) / 100),
            context.progressText(R.string.startup_progress_extracting_external_resources, percent)
        )
    }

    private fun bundledAssetFileExists(context: Context, assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).use { }
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun readInstalledResourcePackVersion(markerFile: File): String? {
        if (!markerFile.isFile) {
            return null
        }
        return runCatching {
            markerFile.readLines(StandardCharsets.UTF_8)
                .firstOrNull { line -> line.startsWith("version=") }
                ?.substringAfter("version=")
                ?.trim()
        }.getOrNull()
    }

    @Throws(IOException::class)
    private fun resolveZipTarget(targetDir: File, entry: ZipEntry): File {
        val normalizedName = entry.name
            .replace('\\', '/')
            .trimStart('/')
        if (normalizedName.isEmpty() ||
            normalizedName.startsWith("../") ||
            normalizedName.contains("/../")
        ) {
            throw IOException("Unsafe resource pack entry: ${entry.name}")
        }
        val targetFile = File(targetDir, normalizedName)
        val targetRootPath = targetDir.canonicalFile.toPath()
        val targetPath = targetFile.canonicalFile.toPath()
        if (!targetPath.startsWith(targetRootPath)) {
            throw IOException("Unsafe resource pack entry: ${entry.name}")
        }
        return targetFile
    }

    @Throws(IOException::class)
    private fun writeInstallMarker(context: Context, extractedDir: File) {
        val marker = File(extractedDir, RuntimePaths.externalResourcesMarkerFile(context).name)
        marker.writeText(
            "version=${BuildConfig.RESOURCE_PACK_VERSION}\n" +
                "appVersion=${BuildConfig.VERSION_NAME}\n",
            StandardCharsets.UTF_8
        )
    }

    @Throws(IOException::class)
    private fun installExtractedResources(context: Context, extractedDir: File) {
        val root = RuntimePaths.externalResourcesRoot(context)
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Failed to create directory: ${root.absolutePath}")
        }
        val currentDir = RuntimePaths.externalResourcesCurrentDir(context)
        val previousDir = File(root, "previous")
        FileTreeCleaner.deleteRecursively(previousDir)
        if (currentDir.exists() && !currentDir.renameTo(previousDir)) {
            FileTreeCleaner.deleteRecursively(currentDir)
        }
        if (!extractedDir.renameTo(currentDir)) {
            copyDirectory(extractedDir, currentDir)
            FileTreeCleaner.deleteRecursively(extractedDir)
        }
        FileTreeCleaner.deleteRecursively(previousDir)
    }

    private fun externalNativeDir(currentDir: File): File =
        File(File(currentDir, "lib"), "arm64-v8a")

    @Throws(IOException::class)
    private fun prepareCleanDirectory(directory: File) {
        val parent = directory.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        FileTreeCleaner.deleteRecursively(directory)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Failed to create directory: ${directory.absolutePath}")
        }
    }

    @Throws(IOException::class)
    private fun copyDirectory(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.exists() && !target.mkdirs()) {
                throw IOException("Failed to create directory: ${target.absolutePath}")
            }
            source.listFiles().orEmpty().forEach { child ->
                copyDirectory(child, File(target, child.name))
            }
            return
        }
        copyFile(source, target)
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

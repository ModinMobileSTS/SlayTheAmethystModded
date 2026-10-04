package io.stamethyst.backend.resources

import io.stamethyst.backend.github.GithubRequestClients
import io.stamethyst.backend.github.HttpRequestControl
import io.stamethyst.backend.resources.ExternalResourcePackService.ConfiguredResourcePackDownloadCandidate
import io.stamethyst.backend.resources.ExternalResourcePackService.ResourcePackLinkProbeResult
import io.stamethyst.backend.update.toGithubMirrorHttpException
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Selects usable links without putting the slowest mirror on the download's critical path. */
internal object ResourcePackLinkSelector {
    const val PROBE_TIMEOUT_MS = 6_000L
    private const val SUCCESS_GRACE_MS = 250L
    private const val MAX_PARALLELISM = 8
    private const val USER_AGENT = "SlayTheAmethyst-ResourcePack"

    fun probe(
        clients: GithubRequestClients,
        candidates: List<ConfiguredResourcePackDownloadCandidate>,
        timeoutMillis: Long = PROBE_TIMEOUT_MS,
        successGraceMillis: Long = SUCCESS_GRACE_MS,
        onCompleted: (ResourcePackLinkProbeResult, Int) -> Unit = { _, _ -> },
    ): List<ResourcePackLinkProbeResult> {
        require(timeoutMillis > 0L)
        require(successGraceMillis >= 0L)
        if (Thread.currentThread().isInterrupted) {
            throw IOException("External resource preparation cancelled")
        }
        if (candidates.isEmpty()) return emptyList()

        var deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val controls = candidates.map { HttpRequestControl(timeoutMillis) }
        val executor = Executors.newFixedThreadPool(candidates.size.coerceAtMost(MAX_PARALLELISM)) { task ->
            Thread(task, "STS-ResourcePackLinkProbe").apply { isDaemon = true }
        }
        val completion = ExecutorCompletionService<ResourcePackLinkProbeResult>(executor)
        val futures = candidates.mapIndexed { index, candidate ->
            completion.submit {
                probeLink(clients.pick(candidate.usesGithubAcceleration), candidate, index, controls[index])
            }
        }
        val results = ArrayList<ResourcePackLinkProbeResult>()
        var foundReachableLink = false
        try {
            while (results.size < candidates.size) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) break
                val future = completion.poll(remainingNanos, TimeUnit.NANOSECONDS) ?: break
                val result = try {
                    future.get()
                } catch (error: ExecutionException) {
                    throw error.cause ?: error
                }
                results += result
                onCompleted(result, results.size)
                if (result.reachable && !foundReachableLink) {
                    foundReachableLink = true
                    deadlineNanos = minOf(
                        deadlineNanos,
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(successGraceMillis)
                    )
                }
            }
            return results
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("External resource preparation cancelled", error)
        } finally {
            // Interrupting a Future alone does not close sockets, especially inside Watt forwarding.
            controls.forEach(HttpRequestControl::cancel)
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
        }
    }

    private fun probeLink(
        client: OkHttpClient,
        candidate: ConfiguredResourcePackDownloadCandidate,
        candidateIndex: Int,
        control: HttpRequestControl,
    ): ResourcePackLinkProbeResult {
        val startedNanos = System.nanoTime()
        var rangeContentLength: Long? = null
        val error = try {
            try {
                rangeContentLength = requestProbe(client, candidate.requestUrl, control, range = false)
            } catch (headError: IOException) {
                // Some mirrors reject HEAD; GET/Range shares the same deadline rather than
                // paying a second full timeout after an unresponsive HEAD request.
                control.checkActive()
                try {
                    rangeContentLength = requestProbe(client, candidate.requestUrl, control, range = true)
                } catch (rangeError: IOException) {
                    rangeError.addSuppressed(headError)
                    throw rangeError
                }
            }
            control.checkActive()
            null
        } catch (error: Exception) {
            error
        }
        return ResourcePackLinkProbeResult(
            displayName = candidate.displayName,
            requestUrl = candidate.requestUrl,
            usesGithubAcceleration = candidate.usesGithubAcceleration,
            preferredMirrorSource = candidate.preferredMirrorSource,
            reachable = error == null,
            elapsedNanos = System.nanoTime() - startedNanos,
            candidateIndex = candidateIndex,
            error = error,
            rangeSupportProbed = error == null,
            rangeSupportedContentLength = rangeContentLength,
        )
    }

    private fun requestProbe(
        client: OkHttpClient,
        requestUrl: String,
        control: HttpRequestControl,
        range: Boolean,
    ): Long? {
        control.checkActive()
        val request = Request.Builder()
            .url(requestUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .tag(HttpRequestControl::class.java, control)
            .apply {
                if (range) get().header("Range", "bytes=0-0") else head()
            }
            .build()
        val call = client.newCall(request)
        control.register(call)
        return call.execute().use { response ->
            if (!response.isSuccessful) throw response.toGithubMirrorHttpException()
            if (range) {
                if (response.code != 206) return@use null
                Regex("^bytes\\s+0-0/(\\d+)$", RegexOption.IGNORE_CASE)
                    .matchEntire(response.header("Content-Range")?.trim().orEmpty())
                    ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0L }
            } else {
                rangeSupportedContentLength(response)
            }
        }
    }

    fun rangeSupportedContentLength(response: Response): Long? {
        val supportsRanges = response.header("Accept-Ranges")
            ?.split(',')
            ?.any { it.trim().equals("bytes", ignoreCase = true) }
            ?: false
        return if (supportsRanges) {
            response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
        } else {
            null
        }
    }
}

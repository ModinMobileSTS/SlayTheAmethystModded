package io.stamethyst.backend.github

import android.content.Context
import io.stamethyst.backend.diag.MemoryDiagnosticsLogger
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import io.stamethyst.backend.update.GithubMirrorFallback
import io.stamethyst.backend.update.UpdateMirrorManager
import io.stamethyst.backend.update.UpdateSource
import okhttp3.OkHttpClient

/** Shared update-source policy for GitHub resources; unrelated URLs always use plain transport. */
internal object GithubResourceRequests {
    fun <T> run(
        context: Context,
        clients: GithubRequestClients,
        url: String,
        onAttempt: (UpdateSource) -> Unit = {},
        onFailure: (UpdateSource, Throwable) -> Unit = { _, _ -> },
        block: (OkHttpClient, String) -> T,
    ): T = run(
        clients = clients,
        url = url,
        preferredSource = UpdateMirrorManager.current(context),
        bypassAcceleratedLinks = NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(context),
        onAttempt = { source ->
            MemoryDiagnosticsLogger.logEvent(
                context = context,
                event = "github_resource_attempt",
                extras = mapOf("source" to source.displayName, "url" to source.buildUrl(url)),
                includeMemorySnapshot = false,
            )
            onAttempt(source)
        },
        onFailure = { source, error ->
            MemoryDiagnosticsLogger.logEvent(
                context = context,
                event = "github_resource_failed",
                extras = mapOf("source" to source.displayName, "url" to source.buildUrl(url),
                    "error" to error.message),
                includeMemorySnapshot = false,
            )
            onFailure(source, error)
        },
        block = block,
    )

    internal fun <T> run(
        clients: GithubRequestClients,
        url: String,
        preferredSource: UpdateSource,
        bypassAcceleratedLinks: Boolean = false,
        onAttempt: (UpdateSource) -> Unit = {},
        onFailure: (UpdateSource, Throwable) -> Unit = { _, _ -> },
        block: (OkHttpClient, String) -> T,
    ): T {
        if (!UpdateSource.isMirrorableGithubUrl(url)) return block(clients.plainClient, url)
        return GithubMirrorFallback.run(preferredSource, bypassAcceleratedLinks) { source ->
            onAttempt(source)
            try {
                block(clients.pick(source.usesGithubAcceleration), source.buildUrl(url))
            } catch (error: Exception) {
                onFailure(source, error)
                throw error
            }
        }.value
    }
}

package io.stamethyst.backend.mods

import io.stamethyst.backend.network.AcceleratedRouteEvent
import io.stamethyst.backend.update.UpdateSource

enum class AgentPatchRoutePhase { SELECTING, CONNECTING, SWITCHING }

/** Request-local narration: global route searches and catalogue refreshes cannot overwrite it. */
internal class AgentPatchDownloadProgressReporter(
    private val onProgress: (AgentPatchInstallProgress) -> Unit,
) {
    fun attemptingSource(source: UpdateSource) {
        if (source.usesGithubAcceleration) selecting(source.displayName)
        else route(AgentPatchRoutePhase.CONNECTING, source.displayName)
    }

    fun selecting(target: String) = route(AgentPatchRoutePhase.SELECTING, target)

    fun switching(target: String) = route(AgentPatchRoutePhase.SWITCHING, target)

    fun onRouteEvent(event: AcceleratedRouteEvent) {
        when (event) {
            is AcceleratedRouteEvent.ForwardTargetAttempt ->
                route(AgentPatchRoutePhase.CONNECTING, event.target)
            is AcceleratedRouteEvent.ForwardTargetFailed -> switching(event.target)
            is AcceleratedRouteEvent.OfficialAttempt ->
                route(AgentPatchRoutePhase.CONNECTING, event.host)
            is AcceleratedRouteEvent.OfficialFailed -> switching(event.host)
            // Receiving headers is not download completion. Byte reads own download progress.
            else -> Unit
        }
    }

    fun downloading(downloadedBytes: Long, totalBytes: Long) {
        val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
        onProgress(
            AgentPatchInstallProgress(
                stage = AgentPatchInstallStage.DOWNLOADING,
                fraction = (0.02f + percent / 100f * 0.68f).coerceIn(0.02f, 0.70f),
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
            )
        )
    }

    private fun route(phase: AgentPatchRoutePhase, target: String) {
        onProgress(
            AgentPatchInstallProgress(
                stage = AgentPatchInstallStage.SELECTING_ROUTE,
                fraction = 0.02f,
                routePhase = phase,
                routeTarget = target,
            )
        )
    }
}

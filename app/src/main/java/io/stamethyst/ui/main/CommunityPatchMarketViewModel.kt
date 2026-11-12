package io.stamethyst.ui.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.stamethyst.backend.mods.CommunityPatchMod
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal data class CommunityPatchMarketState(
    val summary: WorkshopItemSummary? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val authorLoading: Boolean
        get() = loading && summary?.authorName.isNullOrBlank() && summary?.author?.name.isNullOrBlank()
    val ratingLoading: Boolean
        get() = loading && summary?.rating == null
}

internal fun CommunityPatchMod.workshopFallback(): WorkshopItemSummary? = workshopId
    ?.takeIf { it > 0uL }
    ?.let { id ->
        WorkshopItemSummary(
            publishedFileId = id,
            appId = 646570u,
            title = name.ifBlank { modId },
            previewUrl = "",
            description = "",
        )
    }

/** Entry-scoped: loading a community card must not change the market's selected detail or browse list. */
internal class CommunityPatchMarketViewModel(
    private val summaryStore: CommunityPatchSummaryStore = CommunityPatchSummaryStore.shared,
) : ViewModel() {
    var state by mutableStateOf(CommunityPatchMarketState())
        private set
    private var requestKey: String? = null
    private var requestGeneration = 0L
    private var lastRefreshToken = 0
    private var job: Job? = null

    fun stateFor(mod: CommunityPatchMod): CommunityPatchMarketState {
        val current = state
        val fallback = mod.workshopFallback() ?: return CommunityPatchMarketState()
        if (requestKey == "${mod.modId}:${fallback.publishedFileId}") return current
        return CommunityPatchMarketState(
            summary = summaryStore.peek(fallback.appId, fallback.publishedFileId),
            loading = true,
        )
    }

    fun load(mod: CommunityPatchMod, service: WorkshopService, refreshToken: Int = 0, force: Boolean = false) {
        val fallback = mod.workshopFallback() ?: run {
            requestGeneration++
            job?.cancel()
            requestKey = null
            state = CommunityPatchMarketState()
            return
        }
        val key = "${mod.modId}:${fallback.publishedFileId}"
        // Retry a failed community fetch without discarding/re-requesting the API summary.
        // Only the explicit page refresh token revalidates the base metadata.
        val refreshRequested = refreshToken != lastRefreshToken
        if (key == requestKey && !refreshRequested && !force) return
        val generation = ++requestGeneration
        job?.cancel()
        if (key != requestKey) state = stateFor(mod)
        requestKey = key
        lastRefreshToken = refreshToken
        state = state.copy(loading = true, error = null)
        val retainedSummary = state.summary ?: fallback
        job = viewModelScope.launch {
            try {
                val base = state.summary?.takeUnless { refreshRequested } ?: run {
                    summaryStore.load(fallback.appId, fallback.publishedFileId, force = refreshRequested) {
                        service.getDetails(
                            appId = fallback.appId,
                            publishedFileId = fallback.publishedFileId,
                            fallbackSummary = retainedSummary,
                            includeCommunityData = false,
                            includeDependencyData = false,
                        ).summary
                    }
                }
                coroutineContext.ensureActive()
                if (generation != requestGeneration) return@launch
                // Keep known author/rating visible during refresh; only missing fields shimmer.
                val visible = base.copy(
                    authorName = base.authorName.ifBlank { retainedSummary.authorName },
                    author = base.author ?: retainedSummary.author,
                    rating = base.rating ?: retainedSummary.rating,
                )
                state = state.copy(summary = visible)
                val enriched = service.loadCommunitySummary(visible)
                coroutineContext.ensureActive()
                if (generation != requestGeneration) return@launch
                state = CommunityPatchMarketState(summary = enriched)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                coroutineContext.ensureActive()
                if (generation != requestGeneration) return@launch
                // A previous successful snapshot remains visible if a refresh fails.
                state = state.copy(loading = false, error = error.message ?: error.javaClass.simpleName)
            }
        }
    }
}

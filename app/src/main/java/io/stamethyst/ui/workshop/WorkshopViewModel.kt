package io.stamethyst.ui.workshop

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.stamethyst.R
import io.stamethyst.config.RuntimePaths
import io.stamethyst.backend.workshop.BaiduAiTextTranslationClient
import io.stamethyst.backend.workshop.BaiduTranslationApiException
import io.stamethyst.backend.workshop.BaiduTranslationCredentials
import io.stamethyst.backend.workshop.BaiduTranslationCredentialsRepository
import io.stamethyst.backend.workshop.WorkshopBrowseFailureLogStore
import io.stamethyst.backend.workshop.WorkshopBrowseQuery
import io.stamethyst.backend.workshop.WorkshopBrowseSort
import io.stamethyst.backend.workshop.WorkshopBrowseTimeFilter
import io.stamethyst.backend.workshop.WorkshopChangeNotes
import io.stamethyst.backend.workshop.WorkshopComment
import io.stamethyst.backend.workshop.WorkshopCommentThreadContext
import io.stamethyst.backend.workshop.WorkshopDownloadBlocklist
import io.stamethyst.backend.workshop.WorkshopDownloadProcessService
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStore
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.WorkshopDetailUpdate
import io.stamethyst.backend.workshop.WorkshopInstalledModRecord
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopLoadPhase
import io.stamethyst.backend.workshop.WorkshopLoadProgress
import io.stamethyst.backend.workshop.WorkshopLoadProgressReporter
import io.stamethyst.backend.workshop.WorkshopMetadataStore
import io.stamethyst.backend.workshop.WorkshopModCategory
import io.stamethyst.backend.workshop.WorkshopModCardState
import io.stamethyst.backend.workshop.WorkshopService
import io.stamethyst.backend.workshop.WorkshopSteamLoginRequiredException
import io.stamethyst.backend.workshop.WorkshopSubscriptionVerificationException
import io.stamethyst.backend.workshop.WorkshopSubscriptionSyncController
import io.stamethyst.backend.workshop.WorkshopSubscriptionEnqueueResult
import io.stamethyst.backend.workshop.WorkshopUnsubscriptionVerificationException
import io.stamethyst.backend.workshop.WorkshopUpdateCheckResult
import io.stamethyst.backend.workshop.WorkshopUpdateChecker
import io.stamethyst.backend.workshop.buildBaiduModDescriptionReference
import io.stamethyst.backend.workshop.buildBaiduWorkshopCommentReference
import io.stamethyst.backend.workshop.isActiveDownload
import io.stamethyst.backend.workshop.isRunningDownload
import io.stamethyst.backend.workshop.splitTranslationSections
import io.stamethyst.backend.workshop.mapLocaleLanguageToBaiduLanguage
import io.stamethyst.ui.preferences.LauncherPreferences
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Stable
internal class WorkshopViewModel : ViewModel() {
    var uiState by mutableStateOf(WorkshopUiState())
        private set

    private var service: WorkshopService? = null
    private var metadataStore: WorkshopMetadataStore? = null
    private var loaded = false
    private var activeListMode: WorkshopListMode = WorkshopListMode.Browse
    private var activeQueryText: String = ""
    private var activeSort: WorkshopBrowseSort = WorkshopBrowseSort.Default
    private var activeTimeFilter: WorkshopBrowseTimeFilter = WorkshopBrowseTimeFilter.OneWeek
    private var activeCategory: WorkshopModCategory = WorkshopModCategory.All
    private var browseRequestGeneration = 0
    private var progressListenerInstalled = false
    private var activeProgressSessionId: Long = 0L
    private var activeDetailProgressSessionId: Long? = null
    private var refreshDownloadStateJob: Job? = null
    private var updateAllJob: Job? = null
    private val detailsCache = mutableMapOf<String, WorkshopItemDetails>()
    private val detailLoadsInFlight = ConcurrentHashMap<String, Job>()
    private val commentTranslationsInFlight = mutableSetOf<String>()
    private var commentTranslationJob: Job? = null
    private var commentLoadJob: Job? = null
    private var commentDataGeneration = 0L
    private var detailTranslationJob: Job? = null
    private val translatedParts = mutableMapOf<List<String>, String>()
    private val translationClient = BaiduAiTextTranslationClient()
    private val downloadTaskPersistenceMutex = Mutex()

    internal fun socialService(context: Context) =
        (service ?: WorkshopService(context.applicationContext).also { service = it }).social

    override fun onCleared() {
        if (progressListenerInstalled) WorkshopLoadProgressReporter.setListener(null)
        service?.close()
        service = null
        super.onCleared()
    }

    private fun ensureProgressListener() {
        if (progressListenerInstalled) return
        progressListenerInstalled = true
        WorkshopLoadProgressReporter.setListener { progress ->
            // Progress arrives from OkHttp/IO threads; hop to the main dispatcher because uiState is
            // Compose snapshot state owned by the UI thread.
            viewModelScope.launch(Dispatchers.Main.immediate) {
                when (progress.sessionId) {
                    activeProgressSessionId ->
                        uiState = uiState.copy(loadProgress = progress)
                    activeDetailProgressSessionId ->
                        uiState = uiState.copy(detailLoadProgress = progress)
                }
            }
        }
    }

    private fun clearDetailProgressSession(sessionId: Long) {
        // A newer detail load may have superseded this one; only its own session may close the bar.
        if (activeDetailProgressSessionId != sessionId) return
        activeDetailProgressSessionId = null
        uiState = uiState.copy(detailLoadProgress = null)
    }

    fun load(context: Context, initialListMode: WorkshopListMode = WorkshopListMode.Browse) {
        val loadStartedAtMs = SystemClock.elapsedRealtime()
        WorkshopDownloadCenterStore.initialize(context)
        if (loaded) {
            Log.i(
                WORKSHOP_PERF_TAG,
                "load skip alreadyLoaded=true mode=$initialListMode elapsedMs=${SystemClock.elapsedRealtime() - loadStartedAtMs}",
            )
            refreshDownloadState(context)
            if (uiState.listMode != initialListMode) {
                when (initialListMode) {
                    WorkshopListMode.Browse -> showWorkshopBrowse(context)
                    WorkshopListMode.Subscriptions -> showSubscribedWorkshopMods(context)
                }
            }
            return
        }
        loaded = true
        activeListMode = initialListMode
        val serviceStartedAtMs = SystemClock.elapsedRealtime()
        service = service ?: WorkshopService(context)
        val serviceCreateMs = SystemClock.elapsedRealtime() - serviceStartedAtMs
        metadataStore = metadataStore ?: WorkshopMetadataStore(context)
        metadataStore?.markMissingFiles()
        val steamLoggedIn = service?.hasSteamAuth() == true
        uiState = uiState.copy(
            steamLoggedIn = steamLoggedIn,
            listMode = activeListMode,
            installedMods = metadataStore?.list().orEmpty(),
        )
        Log.i(
            WORKSHOP_PERF_TAG,
            "load firstInit serviceCreateMs=$serviceCreateMs steamLoggedIn=$steamLoggedIn mode=$activeListMode setupMs=${SystemClock.elapsedRealtime() - loadStartedAtMs}",
        )
        viewModelScope.launch(Dispatchers.IO) {
            WorkshopDownloadProcessService.startNextQueued(context)
        }
        refreshDownloadState(context)
        when (activeListMode) {
            WorkshopListMode.Browse -> search(
                context,
                "",
                sort = LauncherPreferences.readWorkshopDefaultSort(context),
            )
            WorkshopListMode.Subscriptions -> loadSubscribedPage(context, page = 1, append = false)
        }
    }

    private fun findCachedDetails(appId: UInt, publishedFileId: ULong): WorkshopItemDetails? {
        return uiState.selected?.takeIf { selected ->
            selected.summary.appId == appId && selected.summary.publishedFileId == publishedFileId &&
                uiState.detailUpdate == null
        } ?: detailsCache[detailsCacheKey(appId, publishedFileId)]
    }

    private fun findSummaryFallback(appId: UInt, publishedFileId: ULong): WorkshopItemSummary? {
        uiState.items.firstOrNull { item ->
            item.appId == appId && item.publishedFileId == publishedFileId
        }?.let { return it }
        uiState.selected?.takeIf { selected ->
            selected.summary.appId == appId && selected.summary.publishedFileId == publishedFileId
        }?.summary?.let { return it }
        detailsCache[detailsCacheKey(appId, publishedFileId)]?.summary?.let { return it }
        uiState.selected?.dependencies?.firstOrNull { dependency ->
            dependency.appId == appId && dependency.publishedFileId == publishedFileId
        }?.let { return it }
        return uiState.installedMods.firstOrNull { record ->
            record.appId == appId && record.publishedFileId == publishedFileId
        }?.toWorkshopItemSummary()
    }

    /** Local-only entry point: opening updates must not fetch the market browse page. */
    fun loadInstalledState(context: Context) {
        if (metadataStore == null) metadataStore = WorkshopMetadataStore(context.applicationContext)
        refreshDownloadState(context.applicationContext)
    }

    fun refreshDownloadState(context: Context) {
        WorkshopDownloadCenterStore.initialize(context)
        if (refreshDownloadStateJob?.isActive == true) return
        val currentMetadataStore = metadataStore
        val currentService = service
        refreshDownloadStateJob = viewModelScope.launch {
            val (loadedTasks, installedMods, steamLoggedIn) = withContext(Dispatchers.IO) {
                currentMetadataStore?.markMissingFiles()
                Triple(
                    WorkshopDownloadCenterStore.loadTasksWithRecovery(context),
                    currentMetadataStore?.list().orEmpty(),
                    currentService?.hasSteamAuth() == true,
                )
            }
            uiState = uiState.copy(
                steamLoggedIn = steamLoggedIn,
                listMode = activeListMode,
                installedMods = installedMods,
                downloadInProgress = loadedTasks.any { it.status.isActiveDownload() },
            )
            WorkshopDownloadCenterStore.replaceInMemory(loadedTasks)
        }
    }

    fun refreshDownloadTaskState(context: Context) {
        WorkshopDownloadCenterStore.initialize(context)
        if (refreshDownloadStateJob?.isActive == true) return
        val currentMetadataStore = metadataStore
        val currentService = service
        refreshDownloadStateJob = viewModelScope.launch {
            val loadedTasks = withContext(Dispatchers.IO) {
                WorkshopDownloadCenterStore.loadTasksWithRecovery(context)
            }
            val hasActiveDownload = loadedTasks.any { task -> task.status.isActiveDownload() }
            if (hasActiveDownload) {
                WorkshopDownloadCenterStore.replaceInMemory(loadedTasks)
                if (!uiState.downloadInProgress) {
                    uiState = uiState.copy(downloadInProgress = true)
                }
                return@launch
            }

            val (installedMods, steamLoggedIn) = withContext(Dispatchers.IO) {
                currentMetadataStore?.markMissingFiles()
                Pair(
                    currentMetadataStore?.list().orEmpty(),
                    currentService?.hasSteamAuth() == true,
                )
            }
            uiState = uiState.copy(
                steamLoggedIn = steamLoggedIn,
                listMode = activeListMode,
                installedMods = installedMods,
                downloadInProgress = false,
            )
            WorkshopDownloadCenterStore.replaceInMemory(loadedTasks)
        }
    }

    private fun updateDownloadTaskAsync(
        publishedFileId: ULong,
        transform: (WorkshopDownloadTaskUi) -> WorkshopDownloadTaskUi,
    ): WorkshopDownloadTaskUi? {
        val updatedTask = WorkshopDownloadCenterStore.updateInMemory(publishedFileId, transform)
        persistDownloadTaskAsync {
            if (updatedTask != null) {
                WorkshopDownloadCenterStore.persistUpsert(updatedTask)
            } else {
                WorkshopDownloadCenterStore.persistUpdate(publishedFileId, transform)
            }
        }
        return updatedTask
    }

    private fun persistDownloadTaskAsync(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            downloadTaskPersistenceMutex.withLock {
                block()
            }
        }
    }

    fun refreshSteamAuth(context: Context) {
        val currentService = service ?: return
        val steamLoggedIn = currentService.hasSteamAuth()
        if (!steamLoggedIn && activeListMode == WorkshopListMode.Subscriptions) {
            uiState = uiState.copy(
                browseLoading = false,
                loadingMore = false,
                listMode = WorkshopListMode.Subscriptions,
                steamLoggedIn = false,
                items = emptyList(),
                nextPage = 1,
                hasMorePages = false,
                subscribedWorkshopIds = emptySet(),
                detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Unknown,
                errorMessage = null,
            )
            return
        }

        if (uiState.steamLoggedIn != steamLoggedIn) {
            uiState = if (steamLoggedIn) {
                uiState.copy(steamLoggedIn = true)
            } else {
                uiState.copy(
                    steamLoggedIn = false,
                    subscribedWorkshopIds = emptySet(),
                    detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Unknown,
                )
            }
        }
        if (steamLoggedIn && activeListMode == WorkshopListMode.Subscriptions && !uiState.browseLoading) {
            loadSubscribedPage(context, page = 1, append = false)
        }
    }

    fun search(
        context: Context,
        queryText: String,
        sort: WorkshopBrowseSort = WorkshopBrowseSort.Default,
        timeFilter: WorkshopBrowseTimeFilter = WorkshopBrowseTimeFilter.OneWeek,
        category: WorkshopModCategory = WorkshopModCategory.All,
    ) {
        activeListMode = WorkshopListMode.Browse
        activeQueryText = queryText
        activeSort = sort
        activeTimeFilter = timeFilter
        activeCategory = category
        loadBrowsePage(context, queryText = queryText, page = 1, append = false)
    }

    fun refreshBrowse(context: Context) {
        when (activeListMode) {
            WorkshopListMode.Browse -> loadBrowsePage(
                context = context,
                queryText = activeQueryText,
                page = 1,
                append = false,
                clearItems = false,
            )
            WorkshopListMode.Subscriptions -> loadSubscribedPage(
                context = context,
                page = 1,
                append = false,
                clearItems = false,
            )
        }
    }

    fun loadNextPage(context: Context) {
        val state = uiState
        if (state.browseLoading || state.loadingMore || !state.hasMorePages) return
        when (state.listMode) {
            WorkshopListMode.Browse -> loadBrowsePage(context, queryText = activeQueryText, page = state.nextPage, append = true)
            WorkshopListMode.Subscriptions -> loadSubscribedPage(context, page = state.nextPage, append = true)
        }
    }

    fun showSubscribedWorkshopMods(context: Context) {
        activeListMode = WorkshopListMode.Subscriptions
        loadSubscribedPage(context, page = 1, append = false)
    }

    fun showWorkshopBrowse(context: Context) {
        activeListMode = WorkshopListMode.Browse
        loadBrowsePage(context, queryText = activeQueryText, page = 1, append = false)
    }

    private fun loadBrowsePage(
        context: Context,
        queryText: String,
        page: Int,
        append: Boolean,
        clearItems: Boolean = true,
    ) {
        val currentService = service ?: return
        activeListMode = WorkshopListMode.Browse
        val requestGeneration = ++browseRequestGeneration
        val browseStartedAtMs = SystemClock.elapsedRealtime()
        // Paged appends load below the fold and must not reopen the header progress bar.
        val reportProgress = !append
        val progressSessionId = if (reportProgress) {
            ensureProgressListener()
            WorkshopLoadProgressReporter.beginSession().also { activeProgressSessionId = it }
        } else {
            null
        }
        currentService.beginProgressSession(progressSessionId)
        Log.i(
            WORKSHOP_PERF_TAG,
            "loadBrowsePage start gen=$requestGeneration page=$page append=$append sort=$activeSort time=$activeTimeFilter category=$activeCategory queryLen=${queryText.length}",
        )
        viewModelScope.launch {
            val browseQuery = WorkshopBrowseQuery(
                searchText = queryText,
                sort = activeSort,
                timeFilter = activeTimeFilter,
                category = activeCategory,
                page = page,
                pageSize = WorkshopUiState.PAGE_SIZE,
            )
            uiState = if (append) {
                uiState.copy(loadingMore = true, errorMessage = null)
            } else {
                uiState.copy(
                    browseLoading = true,
                    loadingMore = false,
                    listMode = WorkshopListMode.Browse,
                    errorMessage = null,
                    items = if (clearItems) emptyList() else uiState.items,
                    nextPage = 1,
                    hasMorePages = true,
                    loadProgress = WorkshopLoadProgress(
                        sessionId = progressSessionId ?: 0L,
                        phase = WorkshopLoadPhase.Preparing,
                    ),
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    currentService.browse(browseQuery)
                }
            }.onSuccess { result ->
                progressSessionId?.let { sessionId ->
                    WorkshopLoadProgressReporter.report(sessionId, WorkshopLoadPhase.Completed)
                    WorkshopLoadProgressReporter.endSession(sessionId)
                }
                currentService.beginProgressSession(null)
                if (activeListMode != WorkshopListMode.Browse || requestGeneration != browseRequestGeneration) return@onSuccess
                val existing = if (append) uiState.items else emptyList()
                val merged = (existing + result.items).distinctBy { it.publishedFileId }
                uiState = uiState.copy(
                    browseLoading = false,
                    loadingMore = false,
                    items = merged,
                    nextPage = page + 1,
                    hasMorePages = result.hasNextPage,
                    errorMessage = if (merged.isEmpty()) context.getString(R.string.workshop_error_no_entries_found) else null,
                )
                enrichBrowseItemsInBackground(currentService, requestGeneration, merged)
                Log.i(
                    WORKSHOP_PERF_TAG,
                    "loadBrowsePage success gen=$requestGeneration page=$page items=${merged.size} hasNext=${result.hasNextPage} elapsedMs=${SystemClock.elapsedRealtime() - browseStartedAtMs}",
                )
            }.onFailure { error ->
                progressSessionId?.let { sessionId ->
                    WorkshopLoadProgressReporter.report(
                        sessionId = sessionId,
                        phase = WorkshopLoadPhase.Failed,
                        detail = error.message ?: error.javaClass.simpleName,
                    )
                    WorkshopLoadProgressReporter.endSession(sessionId)
                }
                currentService.beginProgressSession(null)
                WorkshopBrowseFailureLogStore.writeFailure(
                    context = context,
                    query = browseQuery,
                    page = page,
                    append = append,
                    elapsedMs = SystemClock.elapsedRealtime() - browseStartedAtMs,
                    error = error,
                )
                if (activeListMode != WorkshopListMode.Browse || requestGeneration != browseRequestGeneration) return@onFailure
                uiState = uiState.copy(
                    browseLoading = false,
                    loadingMore = false,
                    errorMessage = error.message ?: error.javaClass.simpleName,
                )
                Log.w(
                    WORKSHOP_PERF_TAG,
                    "loadBrowsePage failure gen=$requestGeneration page=$page elapsedMs=${SystemClock.elapsedRealtime() - browseStartedAtMs} error=${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    /**
     * Backfills file size / download counts for cards already on screen. Only replaces ids still
     * present in the current list, so a late patch can never resurrect items a newer query removed;
     * the generation check just avoids redundant API calls racing when filters change quickly.
     */
    private fun enrichBrowseItemsInBackground(
        service: WorkshopService,
        requestGeneration: Int,
        items: List<WorkshopItemSummary>,
    ) {
        if (items.none { item -> item.fileSizeBytes <= 0L || item.downloadCount <= 0L }) return
        viewModelScope.launch {
            val enrichedItems = runCatching {
                withContext(Dispatchers.IO) { service.loadBrowseItemMetadata(items) }
            }.getOrNull() ?: return@launch
            if (requestGeneration != browseRequestGeneration || activeListMode != WorkshopListMode.Browse) {
                return@launch
            }
            val enrichedById = enrichedItems.associateBy { item -> item.publishedFileId }
            uiState = uiState.copy(
                items = uiState.items.map { item ->
                    enrichedById[item.publishedFileId]?.takeIf { enriched -> enriched != item } ?: item
                },
            )
        }
    }

    private fun loadSubscribedPage(
        context: Context,
        page: Int,
        append: Boolean,
        clearItems: Boolean = true,
    ) {
        val currentService = service ?: return
        activeListMode = WorkshopListMode.Subscriptions
        if (!currentService.hasSteamAuth()) {
            uiState = uiState.copy(
                browseLoading = false,
                loadingMore = false,
                listMode = WorkshopListMode.Subscriptions,
                steamLoggedIn = false,
                items = emptyList(),
                nextPage = 1,
                hasMorePages = false,
                subscribedWorkshopIds = emptySet(),
                detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Unknown,
                errorMessage = null,
            )
            return
        }
        viewModelScope.launch {
            uiState = if (append) {
                uiState.copy(loadingMore = true, errorMessage = null)
            } else {
                uiState.copy(
                    browseLoading = true,
                    loadingMore = false,
                    listMode = WorkshopListMode.Subscriptions,
                    errorMessage = null,
                    items = if (clearItems) emptyList() else uiState.items,
                    nextPage = 1,
                    hasMorePages = true,
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    currentService.browseSubscriptions(page = page, pageSize = WorkshopUiState.PAGE_SIZE)
                }
            }.onSuccess { result ->
                if (activeListMode != WorkshopListMode.Subscriptions) return@onSuccess
                val existing = if (append) uiState.items else emptyList()
                val merged = (existing + result.items).distinctBy { it.publishedFileId }
                uiState = uiState.copy(
                    browseLoading = false,
                    loadingMore = false,
                    items = merged,
                    nextPage = page + 1,
                    hasMorePages = result.hasNextPage,
                    steamLoggedIn = currentService.hasSteamAuth(),
                    subscribedWorkshopIds = uiState.subscribedWorkshopIds + result.items.map { it.publishedFileId },
                    errorMessage = null,
                )
            }.onFailure { error ->
                if (activeListMode != WorkshopListMode.Subscriptions) return@onFailure
                uiState = uiState.copy(
                    browseLoading = false,
                    loadingMore = false,
                    steamLoggedIn = currentService.hasSteamAuth(),
                    errorMessage = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    fun openDetails(context: Context, item: WorkshopItemSummary) {
        loadDetails(context, item.appId, item.publishedFileId, item)
    }

    fun loadDetails(
        context: Context,
        appId: UInt,
        publishedFileId: ULong,
        fallbackSummary: WorkshopItemSummary? = null,
        clearSelected: Boolean = false,
        ignoreCache: Boolean = false,
    ) {
        val currentService = service ?: return
        val detailKey = detailsCacheKey(appId, publishedFileId)
        if (uiState.selected?.cacheKey() != detailKey) {
            commentLoadJob?.cancel()
            commentTranslationJob?.cancel()
            detailTranslationJob?.cancel()
            uiState = uiState.copy(detailTranslationLoadingId = null)
        }
        if (detailLoadsInFlight.containsKey(detailKey)) return
        // A cache hit for another mod must supersede the old pipeline too.
        detailLoadsInFlight.entries.filter { it.key != detailKey }.forEach { (key, job) ->
            job.cancel()
            detailLoadsInFlight.remove(key, job)
        }
        if (!clearSelected && !ignoreCache) {
            findCachedDetails(appId, publishedFileId)?.let { cachedDetails ->
                // Stale-while-revalidate: when the only reason the cache fails reuse is a newer
                // card version, render it immediately and validate in the background instead of
                // making the user wait for a full pipeline on every repeat open.
                if (
                    cachedDetails.hasReusableCommunityData() &&
                    isCachedDetailStaleVersusFallback(cachedDetails.summary, fallbackSummary)
                ) {
                    Log.i(
                        WORKSHOP_PERF_TAG,
                        "loadDetails swrShow publishedFileId=$publishedFileId appId=$appId cachedUpdatedAt=${cachedDetails.summary.updatedAtMillis} fallbackUpdatedAt=${fallbackSummary?.updatedAtMillis}",
                    )
                    showLoadedDetails(
                        context = context,
                        currentService = currentService,
                        details = cachedDetails,
                        refreshSubscriptionStatus = shouldRefreshCachedDetailSubscriptionStatus(
                            currentService = currentService,
                            publishedFileId = publishedFileId,
                        ),
                    )
                    revalidateStaleCachedDetail(context, appId, publishedFileId)
                    return
                }
                cachedDetails
                    .takeIf { details -> details.canReuseForDetailOpen(fallbackSummary) }
                    ?.let { reusableDetails ->
                        Log.i(
                            WORKSHOP_PERF_TAG,
                            "loadDetails cacheHit publishedFileId=$publishedFileId appId=$appId",
                        )
                        showLoadedDetails(
                            context = context,
                            currentService = currentService,
                            details = reusableDetails,
                            refreshSubscriptionStatus = shouldRefreshCachedDetailSubscriptionStatus(
                                currentService = currentService,
                                publishedFileId = publishedFileId,
                            ),
                        )
                        return
                    }
            }
        }
        // Opening another mod supersedes any in-flight detail pipeline: cancel it so its remaining
        // requests stop consuming network and its narration cannot interleave with this one.
        detailLoadsInFlight.values.forEach { runningJob -> runningJob.cancel() }
        detailLoadsInFlight.clear()
        commentLoadJob?.cancel()
        // Narrate the detail pipeline the same way the market header does: an open progress session
        // lets the acceleration layer's route events (node picks, failovers, official fallback) flow
        // into the bar while getDetails talks to Steam. The reporter tracks one active session, so a
        // detail load opening here simply supersedes an in-flight browse narration.
        ensureProgressListener()
        val detailProgressSessionId = WorkshopLoadProgressReporter.beginSession()
            .also { activeDetailProgressSessionId = it }
        currentService.beginProgressSession(detailProgressSessionId)
        val loadJob = viewModelScope.launch {
            val loadStartedAtMs = SystemClock.elapsedRealtime()
            Log.i(
                WORKSHOP_PERF_TAG,
                "loadDetails start publishedFileId=$publishedFileId appId=$appId clearSelected=$clearSelected ignoreCache=$ignoreCache hasFallback=${fallbackSummary != null}",
            )
            val cachedDetailsBeforeLoad = findCachedDetails(appId, publishedFileId)
            val commentGenerationBeforeLoad = commentDataGeneration
            try {
                uiState = uiState.copy(
                    selected = if (clearSelected) null else uiState.selected,
                    detailLoadingId = publishedFileId,
                    detailUpdate = null,
                    detailLoadProgress = WorkshopLoadProgress(
                        sessionId = detailProgressSessionId,
                        phase = WorkshopLoadPhase.Preparing,
                    ),
                    errorMessage = null,
                    commentLoadingId = null,
                    commentErrorMessage = null,
                    commentTranslationLoadingKey = null,
                    commentTranslationErrorMessage = null,
                    detailTranslationModeKey = null,
                    detailTranslationLoadingId = null,
                    detailTranslationErrorMessage = null,
                    detailChangeNotesLoadingId = null,
                    detailChangeNotesErrorMessage = null,
                    detailSubscriptionLoadingId = null,
                    detailSubscriptionMessage = null,
                    detailSubscriptionStatusId = publishedFileId,
                    detailSubscriptionStatus = if (currentService.hasSteamAuth()) {
                        WorkshopDetailSubscriptionStatus.Checking
                    } else {
                        WorkshopDetailSubscriptionStatus.Unknown
                    },
                )
                val summaryFallback = cachedDetailsBeforeLoad?.summary
                    ?: fallbackSummary
                    ?: findSummaryFallback(appId, publishedFileId)
                refreshDetailSubscriptionStatus(appId, publishedFileId)
                var commentsStarted = false
                var requestedCommentContext: WorkshopCommentThreadContext? = null
                val loadedDetails = withContext(Dispatchers.IO) {
                    currentService.getDetails(appId, publishedFileId, summaryFallback, onUpdate = { update ->
                        withContext(Dispatchers.Main.immediate) {
                            // Each network branch publishes independently. Keep comments that arrived
                            // between snapshots instead of replacing them with an empty detail payload.
                            val visibleUpdate = update.retainVisibleCache(cachedDetailsBeforeLoad)
                            val details = visibleUpdate.details
                                .mergeCachedCommunityData(cachedDetailsBeforeLoad)
                                .retainLoadedCommentsFrom(uiState.selected, commentDataGeneration != commentGenerationBeforeLoad)
                            uiState = uiState.copy(
                                selected = details,
                                detailUpdate = visibleUpdate.copy(details = details),
                                errorMessage = update.metadataErrorMessage,
                                commentErrorMessage = if (commentsStarted || update.commentContextLoading) {
                                    uiState.commentErrorMessage
                                } else {
                                    details.commentUnavailableMessage(context)
                                },
                            )
                            val improvedCommentContext = uiState.commentErrorMessage != null &&
                                uiState.commentLoadingId == null && details.commentThreadContext != requestedCommentContext
                            if ((!commentsStarted || improvedCommentContext) && details.shouldLoadInitialWorkshopComments()) {
                                commentsStarted = true
                                requestedCommentContext = details.commentThreadContext
                                loadWorkshopCommentsPage(context, appId, publishedFileId, page = 1)
                            }
                        }
                    })
                }
                val details = loadedDetails
                    .mergeCachedCommunityData(cachedDetailsBeforeLoad)
                    .retainLoadedCommentsFrom(uiState.selected, commentDataGeneration != commentGenerationBeforeLoad)
                Log.i(
                    WORKSHOP_PERF_TAG,
                    "loadDetails success publishedFileId=$publishedFileId deps=${details.dependencies.size} commentsPending=${details.shouldLoadInitialWorkshopComments()} elapsedMs=${SystemClock.elapsedRealtime() - loadStartedAtMs}",
                )
                WorkshopLoadProgressReporter.report(detailProgressSessionId, WorkshopLoadPhase.Completed)
                detailsCache[detailKey] = details
                uiState = uiState.copy(selected = details, detailLoadingId = null, detailUpdate = null)
            } catch (error: CancellationException) {
                // Superseded by a newer detail load; never surface this as a user-facing failure.
                throw error
            } catch (error: Throwable) {
                Log.w(
                    WORKSHOP_PERF_TAG,
                    "loadDetails failed publishedFileId=$publishedFileId elapsedMs=${SystemClock.elapsedRealtime() - loadStartedAtMs} error=${error.message ?: error.javaClass.simpleName}",
                )
                WorkshopLoadProgressReporter.report(
                    sessionId = detailProgressSessionId,
                    phase = WorkshopLoadPhase.Failed,
                    detail = error.message ?: error.javaClass.simpleName,
                )
                uiState = uiState.copy(
                    detailLoadingId = null,
                    detailUpdate = uiState.detailUpdate?.copy(
                        metadataLoading = false,
                        communityLoading = false,
                        dependenciesLoading = false,
                    ),
                    detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Unknown,
                    errorMessage = error.message ?: error.javaClass.simpleName,
                )
            } finally {
                // Every teardown call is guarded by session-id equality, so a load that was already
                // superseded by a newer one cannot close the newer load's narration.
                WorkshopLoadProgressReporter.endSession(detailProgressSessionId)
                currentService.endProgressSession(detailProgressSessionId)
                clearDetailProgressSession(detailProgressSessionId)
            }
        }
        detailLoadsInFlight[detailKey] = loadJob
        loadJob.invokeOnCompletion {
            detailLoadsInFlight.remove(detailKey, loadJob)
        }
    }

    /**
     * Validates a just-shown stale cache entry with one lightweight summaries call and upgrades to
     * a full refresh only when Steam reports a newer published version.
     */
    private fun revalidateStaleCachedDetail(context: Context, appId: UInt, publishedFileId: ULong) {
        val currentService = service ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { currentService.getSummaries(appId, listOf(publishedFileId)) }
            }.onSuccess { summaries ->
                val freshSummary = summaries.firstOrNull { summary ->
                    summary.appId == appId && summary.publishedFileId == publishedFileId
                }
                if (freshSummary == null || freshSummary.updatedAtMillis <= 0L) return@onSuccess
                val stillViewingThisMod = uiState.selected?.summary?.let { selected ->
                    selected.appId == appId && selected.publishedFileId == publishedFileId
                } == true
                if (!stillViewingThisMod) return@onSuccess
                val cachedUpdatedAt =
                    findCachedDetails(appId, publishedFileId)?.summary?.updatedAtMillis ?: 0L
                if (freshSummary.updatedAtMillis > cachedUpdatedAt) {
                    Log.i(
                        WORKSHOP_PERF_TAG,
                        "loadDetails swrRefresh publishedFileId=$publishedFileId cachedUpdatedAt=$cachedUpdatedAt freshUpdatedAt=${freshSummary.updatedAtMillis}",
                    )
                    loadDetails(
                        context = context,
                        appId = appId,
                        publishedFileId = publishedFileId,
                        clearSelected = false,
                        ignoreCache = true,
                    )
                }
            }.onFailure { error ->
                Log.w(
                    WORKSHOP_PERF_TAG,
                    "loadDetails swrRevalidateFailed publishedFileId=$publishedFileId error=${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    private fun showLoadedDetails(
        context: Context,
        currentService: WorkshopService,
        details: WorkshopItemDetails,
        refreshSubscriptionStatus: Boolean = true,
    ) {
        if (uiState.selected?.cacheKey() != details.cacheKey()) {
            commentLoadJob?.cancel()
            commentTranslationJob?.cancel()
            detailTranslationJob?.cancel()
            uiState = uiState.copy(detailTranslationLoadingId = null)
        }
        detailsCache[details.cacheKey()] = details
        val summary = details.summary
        val shouldLoadComments = details.shouldLoadInitialWorkshopComments()
        val steamLoggedIn = currentService.hasSteamAuth()
        uiState = uiState.copy(
            selected = details,
            detailLoadingId = null,
            detailUpdate = null,
            errorMessage = null,
            commentLoadingId = if (shouldLoadComments) summary.publishedFileId else null,
            commentErrorMessage = details.commentUnavailableMessage(context),
            commentTranslationLoadingKey = null,
            commentTranslationErrorMessage = null,
            detailSubscriptionStatusId = summary.publishedFileId,
            detailSubscriptionStatus = if (!steamLoggedIn) {
                WorkshopDetailSubscriptionStatus.Unknown
            } else if (refreshSubscriptionStatus) {
                WorkshopDetailSubscriptionStatus.Checking
            } else if (summary.publishedFileId in uiState.subscribedWorkshopIds) {
                WorkshopDetailSubscriptionStatus.Subscribed
            } else {
                uiState.detailSubscriptionStatusFor(summary.publishedFileId)
            },
        )
        if (shouldLoadComments) {
            loadWorkshopCommentsPage(context, summary.appId, summary.publishedFileId, page = 1)
        }
        if (refreshSubscriptionStatus) {
            refreshDetailSubscriptionStatus(summary.appId, summary.publishedFileId)
        }
    }

    private fun shouldRefreshCachedDetailSubscriptionStatus(
        currentService: WorkshopService,
        publishedFileId: ULong,
    ): Boolean =
        currentService.hasSteamAuth() &&
            publishedFileId !in uiState.subscribedWorkshopIds &&
            uiState.detailSubscriptionStatusFor(publishedFileId) == WorkshopDetailSubscriptionStatus.Unknown

    fun retryDetailsLoad(
        context: Context,
        appId: UInt,
        publishedFileId: ULong,
    ) {
        loadDetails(
            context = context,
            appId = appId,
            publishedFileId = publishedFileId,
            clearSelected = true,
        )
    }

    fun loadSelectedChangeNotes(context: Context) {
        val currentService = service ?: return
        val details = uiState.selected ?: return
        val summary = details.summary
        val changeNotesKey = details.cacheKey()
        if (uiState.detailChangeNotes.containsKey(changeNotesKey)) {
            uiState = uiState.copy(detailChangeNotesErrorMessage = null)
            return
        }
        if (uiState.detailChangeNotesLoadingId == summary.publishedFileId) return

        uiState = uiState.copy(
            detailChangeNotesLoadingId = summary.publishedFileId,
            detailChangeNotesErrorMessage = null,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { currentService.getChangeNotes(summary.publishedFileId) }
            }.onSuccess { changeNotes ->
                val current = uiState.selected?.takeIf { selected -> selected.cacheKey() == changeNotesKey }
                val updatedDetails = current?.copy(
                    changeNotes = changeNotes.markdown,
                    changeNotesUrl = changeNotes.url,
                )
                if (updatedDetails != null && uiState.detailUpdate == null) {
                    detailsCache[changeNotesKey] = updatedDetails
                }
                uiState = uiState.copy(
                    selected = updatedDetails ?: uiState.selected,
                    detailChangeNotes = uiState.detailChangeNotes + (changeNotesKey to changeNotes),
                    detailChangeNotesLoadingId = null,
                    detailChangeNotesErrorMessage = null,
                )
            }.onFailure { error ->
                val stillSelected = uiState.selected?.cacheKey() == changeNotesKey
                uiState = uiState.copy(
                    detailChangeNotesLoadingId = null,
                    detailChangeNotesErrorMessage = if (stillSelected) context.getString(
                        R.string.workshop_change_notes_load_failed,
                        error.message ?: error.javaClass.simpleName,
                    ) else uiState.detailChangeNotesErrorMessage,
                )
            }
        }
    }

    fun loadPreviousWorkshopCommentsPage(context: Context) {
        shiftWorkshopCommentsPage(context, delta = -1)
    }

    fun loadNextWorkshopCommentsPage(context: Context) {
        shiftWorkshopCommentsPage(context, delta = 1)
    }

    fun retryWorkshopCommentsPage(context: Context) {
        val details = uiState.selected ?: return
        if (uiState.commentLoadingId != null) return
        if (details.commentCount == 0L) {
            uiState = uiState.copy(commentErrorMessage = null)
            return
        }
        if (details.commentThreadContext == null) {
            retryWorkshopCommentContext(context, details)
            return
        }
        loadWorkshopCommentsPage(
            context = context,
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            page = details.commentPage.coerceAtLeast(1),
        )
    }

    private fun retryWorkshopCommentContext(
        context: Context,
        details: WorkshopItemDetails,
    ) {
        val currentService = service ?: return
        val summary = details.summary
        uiState = uiState.copy(
            commentLoadingId = summary.publishedFileId,
            commentErrorMessage = null,
        )
        commentLoadJob?.cancel()
        commentLoadJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    currentService.getDetails(
                        appId = summary.appId,
                        publishedFileId = summary.publishedFileId,
                        fallbackSummary = summary,
                        includeCommunityData = true,
                        includeDependencyData = false,
                    )
                }
            }.onSuccess { loadedDetails ->
                val currentDetails = uiState.selected?.takeIf { current ->
                    current.summary.appId == summary.appId && current.summary.publishedFileId == summary.publishedFileId
                } ?: return@onSuccess
                val refreshedDetails = loadedDetails.retainDetailStateFrom(currentDetails)
                if (uiState.detailUpdate == null) detailsCache[refreshedDetails.cacheKey()] = refreshedDetails
                val shouldLoadComments = refreshedDetails.shouldLoadWorkshopComments()
                uiState = uiState.copy(
                    selected = refreshedDetails,
                    commentLoadingId = if (shouldLoadComments) summary.publishedFileId else null,
                    commentErrorMessage = refreshedDetails.commentUnavailableMessage(context),
                )
                if (shouldLoadComments) {
                    commentLoadJob = null
                    loadWorkshopCommentsPage(
                        context = context,
                        appId = summary.appId,
                        publishedFileId = summary.publishedFileId,
                        page = 1,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                val stillSelected = uiState.selected?.let { current ->
                    current.summary.appId == summary.appId && current.summary.publishedFileId == summary.publishedFileId
                } == true
                if (!stillSelected) return@onFailure
                uiState = uiState.copy(
                    commentLoadingId = null,
                    commentErrorMessage = error.message ?: context.getString(R.string.workshop_error_load_comments_failed),
                )
            }
        }
    }

    private fun shiftWorkshopCommentsPage(context: Context, delta: Int) {
        val details = uiState.selected ?: return
        if (uiState.commentLoadingId != null) return
        val targetPage = (details.commentPage + delta).coerceAtLeast(1)
        if (targetPage == details.commentPage) return
        if (delta < 0 && !details.hasPreviousCommentPage) return
        if (delta > 0 && !details.hasNextCommentPage) return
        loadWorkshopCommentsPage(
            context = context,
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            page = targetPage,
        )
    }

    private fun loadWorkshopCommentsPage(
        context: Context,
        appId: UInt,
        publishedFileId: ULong,
        page: Int,
    ) {
        val currentService = service ?: return
        val detailSnapshot = uiState.selected?.takeIf { details ->
            details.summary.appId == appId && details.summary.publishedFileId == publishedFileId
        } ?: return
        val commentUnavailableMessage = detailSnapshot.commentUnavailableMessage(context)
        if (!detailSnapshot.shouldLoadWorkshopComments()) {
            uiState = uiState.copy(
                commentLoadingId = null,
                commentErrorMessage = commentUnavailableMessage,
            )
            return
        }

        commentTranslationJob?.cancel()
        uiState = uiState.copy(
            commentLoadingId = publishedFileId,
            commentErrorMessage = null,
            commentTranslationLoadingKey = null,
            commentTranslationErrorMessage = null,
        )
        commentLoadJob?.cancel()
        commentLoadJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { currentService.getCommentsPage(detailSnapshot, page) }
            }.onSuccess { commentPage ->
                commentDataGeneration += 1
                val updatedDetails = uiState.selected?.takeIf { current ->
                    current.summary.appId == appId && current.summary.publishedFileId == publishedFileId
                }?.copy(
                    commentsUrl = commentPage.commentsUrl,
                    commentCount = commentPage.commentCount,
                    commentPage = commentPage.page,
                    commentTotalPages = commentPage.totalPages,
                    hasPreviousCommentPage = commentPage.hasPreviousPage,
                    hasNextCommentPage = commentPage.hasNextPage,
                    comments = commentPage.comments,
                )
                if (updatedDetails != null && uiState.detailUpdate == null) {
                    detailsCache[updatedDetails.cacheKey()] = updatedDetails
                }
                uiState = uiState.copy(
                    selected = updatedDetails ?: uiState.selected,
                    commentLoadingId = null,
                    commentErrorMessage = null,
                    commentTranslationErrorMessage = null,
                )
                updatedDetails
                    ?.takeIf { updated -> uiState.detailTranslationModeKey == updated.cacheKey() }
                    ?.let { updated -> translateWorkshopCommentsIfNeeded(context, updated) }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (uiState.selected?.summary?.let { it.appId == appId && it.publishedFileId == publishedFileId } != true) {
                    return@onFailure
                }
                val currentDetails = uiState.selected
                if (currentDetails?.commentThreadContext != null &&
                    currentDetails.commentThreadContext != detailSnapshot.commentThreadContext
                ) {
                    // The community page may have supplied a richer context while the early
                    // API-derived comment request was running. Retry against that context once.
                    commentLoadJob = null
                    loadWorkshopCommentsPage(context, appId, publishedFileId, page)
                    return@onFailure
                }
                uiState = uiState.copy(
                    commentLoadingId = null,
                    commentErrorMessage = error.message ?: context.getString(R.string.workshop_error_load_comments_failed),
                )
            }
        }
    }

    fun downloadSelected(context: Context) {
        val details = uiState.selected ?: return
        // A partial dependency list must never bypass the missing-dependency confirmation.
        if (uiState.detailLoadingId == details.summary.publishedFileId) return
        markPreparingDownload(details.summary.publishedFileId)
        startDownloadAfterDependencyCheck(context, details.summary, details)
    }

    fun showWorkshopSubscriptionSteamLoginRequired(context: Context) {
        Log.w(WORKSHOP_SUBSCRIPTION_LOG_TAG, "workshop subscription action blocked reason=steamLoginRequired")
        uiState = uiState.copy(
            detailSubscriptionLoadingId = null,
            detailSubscriptionMessage = context.getString(R.string.workshop_subscribe_requires_steam_login),
        )
    }

    fun dismissWorkshopSubscribeMessage() {
        uiState = uiState.copy(detailSubscriptionMessage = null)
    }

    private fun refreshDetailSubscriptionStatus(
        appId: UInt,
        publishedFileId: ULong,
    ) {
        val currentService = service ?: return
        if (!currentService.hasSteamAuth()) {
            uiState = uiState.copy(
                steamLoggedIn = false,
                detailSubscriptionStatusId = publishedFileId,
                detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Unknown,
                subscribedWorkshopIds = uiState.subscribedWorkshopIds - publishedFileId,
            )
            return
        }
        uiState = uiState.copy(
            steamLoggedIn = true,
            detailSubscriptionStatusId = publishedFileId,
            detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Checking,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    currentService.isSubscribedToPublishedFile(appId, publishedFileId)
                }
            }.onSuccess { subscribed ->
                Log.i(
                    WORKSHOP_SUBSCRIPTION_LOG_TAG,
                    "refreshDetailSubscriptionStatus success appId=$appId publishedFileId=$publishedFileId subscribed=$subscribed",
                )
                val nextSubscribedIds = if (subscribed) {
                    uiState.subscribedWorkshopIds + publishedFileId
                } else {
                    uiState.subscribedWorkshopIds - publishedFileId
                }
                uiState = uiState.copy(
                    subscribedWorkshopIds = nextSubscribedIds,
                    detailSubscriptionStatus = if (uiState.detailSubscriptionStatusId == publishedFileId) {
                        if (subscribed) {
                            WorkshopDetailSubscriptionStatus.Subscribed
                        } else {
                            WorkshopDetailSubscriptionStatus.NotSubscribed
                        }
                    } else {
                        uiState.detailSubscriptionStatus
                    },
                )
            }.onFailure { error ->
                if (error is WorkshopSteamLoginRequiredException) {
                    Log.w(
                        WORKSHOP_SUBSCRIPTION_LOG_TAG,
                        "refreshDetailSubscriptionStatus failed appId=$appId publishedFileId=$publishedFileId reason=steamLoginRequired",
                        error,
                    )
                    uiState = uiState.copy(
                        steamLoggedIn = false,
                        detailSubscriptionStatus = if (uiState.detailSubscriptionStatusId == publishedFileId) {
                            WorkshopDetailSubscriptionStatus.Unknown
                        } else {
                            uiState.detailSubscriptionStatus
                        },
                        subscribedWorkshopIds = uiState.subscribedWorkshopIds - publishedFileId,
                    )
                    return@onFailure
                }
                Log.e(
                    WORKSHOP_SUBSCRIPTION_LOG_TAG,
                    "refreshDetailSubscriptionStatus failed appId=$appId publishedFileId=$publishedFileId",
                    error,
                )
                uiState = uiState.copy(
                    detailSubscriptionStatus = if (uiState.detailSubscriptionStatusId == publishedFileId) {
                        WorkshopDetailSubscriptionStatus.Unknown
                    } else {
                        uiState.detailSubscriptionStatus
                    },
                )
            }
        }
    }

    fun subscribeSelected(context: Context) {
        val currentService = service ?: return
        val details = uiState.selected ?: return
        val summary = details.summary
        val subscriptionStatus = uiState.detailSubscriptionStatusFor(summary.publishedFileId)
        if (uiState.detailSubscriptionLoadingId == summary.publishedFileId ||
            subscriptionStatus == WorkshopDetailSubscriptionStatus.Checking ||
            subscriptionStatus == WorkshopDetailSubscriptionStatus.Subscribed ||
            (subscriptionStatus == WorkshopDetailSubscriptionStatus.Unknown && uiState.subscribedWorkshopIds.contains(summary.publishedFileId))
        ) {
            Log.i(
                WORKSHOP_SUBSCRIPTION_LOG_TAG,
                "subscribeSelected skipped appId=${summary.appId} publishedFileId=${summary.publishedFileId} loading=${uiState.detailSubscriptionLoadingId == summary.publishedFileId} status=$subscriptionStatus alreadySubscribed=${uiState.subscribedWorkshopIds.contains(summary.publishedFileId)}",
            )
            return
        }
        if (!currentService.hasSteamAuth()) {
            showWorkshopSubscriptionSteamLoginRequired(context)
            return
        }
        uiState = uiState.copy(
            detailSubscriptionLoadingId = summary.publishedFileId,
            detailSubscriptionMessage = null,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val account = WorkshopSubscriptionSyncController.get(context).captureAccount()
                    currentService.subscribeToPublishedFile(summary.appId, summary.publishedFileId)
                    account
                }
            }.onSuccess { account ->
                Log.i(
                    WORKSHOP_SUBSCRIPTION_LOG_TAG,
                    "subscribeSelected success appId=${summary.appId} publishedFileId=${summary.publishedFileId} title=${summary.title}",
                )
                fun stillSelected() = uiState.selected?.summary?.let {
                    it.appId == summary.appId && it.publishedFileId == summary.publishedFileId
                } == true
                if (stillSelected()) {
                    uiState = uiState.copy(
                        subscribedWorkshopIds = uiState.subscribedWorkshopIds + summary.publishedFileId,
                        detailSubscriptionLoadingId = null,
                        detailSubscriptionStatusId = summary.publishedFileId,
                        detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Subscribed,
                        detailSubscriptionMessage = context.getString(R.string.workshop_subscribe_success,
                            summary.title.ifBlank { summary.publishedFileId.toString() }),
                    )
                }
                // This is separate from the confirmed Steam operation: preparation cannot undo it.
                val result = WorkshopSubscriptionSyncController.get(context).enqueueSubscribed(account, summary)
                if (stillSelected()) {
                    val message = when (result) {
                        WorkshopSubscriptionEnqueueResult.Queued -> R.string.workshop_subscribe_queued
                        WorkshopSubscriptionEnqueueResult.Skipped -> R.string.workshop_subscribe_success
                        WorkshopSubscriptionEnqueueResult.PreparationFailed -> R.string.workshop_subscribe_preparation_failed
                    }
                    uiState = uiState.copy(detailSubscriptionMessage = context.getString(message,
                        summary.title.ifBlank { summary.publishedFileId.toString() }))
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (uiState.selected?.summary?.let {
                    it.appId == summary.appId && it.publishedFileId == summary.publishedFileId
                } != true) return@onFailure
                if (error is WorkshopSteamLoginRequiredException) {
                    Log.w(
                        WORKSHOP_SUBSCRIPTION_LOG_TAG,
                        "subscribeSelected failed appId=${summary.appId} publishedFileId=${summary.publishedFileId} reason=steamLoginRequired",
                        error,
                    )
                    showWorkshopSubscriptionSteamLoginRequired(context)
                    return@onFailure
                }
                Log.e(
                    WORKSHOP_SUBSCRIPTION_LOG_TAG,
                    "subscribeSelected failed appId=${summary.appId} publishedFileId=${summary.publishedFileId}",
                    error,
                )
                val errorMessage = if (error is WorkshopSubscriptionVerificationException) {
                    context.getString(R.string.workshop_subscribe_not_confirmed)
                } else {
                    error.message ?: error.javaClass.simpleName
                }
                uiState = uiState.copy(
                    detailSubscriptionLoadingId = null,
                    subscribedWorkshopIds = uiState.subscribedWorkshopIds - summary.publishedFileId,
                    detailSubscriptionStatusId = summary.publishedFileId,
                    detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.NotSubscribed,
                    detailSubscriptionMessage = context.getString(
                        R.string.workshop_subscribe_failed,
                        errorMessage,
                    ),
                )
            }
        }
    }

    fun unsubscribeSelected(context: Context) {
        val currentService = service ?: return
        val details = uiState.selected ?: return
        val summary = details.summary
        val subscriptionStatus = uiState.detailSubscriptionStatusFor(summary.publishedFileId)
        val subscribed = subscriptionStatus == WorkshopDetailSubscriptionStatus.Subscribed ||
            (subscriptionStatus == WorkshopDetailSubscriptionStatus.Unknown && uiState.subscribedWorkshopIds.contains(summary.publishedFileId))
        if (uiState.detailSubscriptionLoadingId == summary.publishedFileId ||
            subscriptionStatus == WorkshopDetailSubscriptionStatus.Checking ||
            !subscribed
        ) {
            Log.i(
                WORKSHOP_SUBSCRIPTION_LOG_TAG,
                "unsubscribeSelected skipped appId=${summary.appId} publishedFileId=${summary.publishedFileId} loading=${uiState.detailSubscriptionLoadingId == summary.publishedFileId} status=$subscriptionStatus subscribed=$subscribed",
            )
            return
        }
        if (!currentService.hasSteamAuth()) {
            showWorkshopSubscriptionSteamLoginRequired(context)
            return
        }
        uiState = uiState.copy(
            detailSubscriptionLoadingId = summary.publishedFileId,
            detailSubscriptionMessage = null,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    currentService.unsubscribeFromPublishedFile(summary.appId, summary.publishedFileId)
                }
            }.onSuccess {
                Log.i(
                    WORKSHOP_SUBSCRIPTION_LOG_TAG,
                    "unsubscribeSelected success appId=${summary.appId} publishedFileId=${summary.publishedFileId} title=${summary.title}",
                )
                uiState = uiState.copy(
                    detailSubscriptionLoadingId = null,
                    items = if (activeListMode == WorkshopListMode.Subscriptions) {
                        uiState.items.filterNot { item -> item.publishedFileId == summary.publishedFileId }
                    } else {
                        uiState.items
                    },
                    subscribedWorkshopIds = uiState.subscribedWorkshopIds - summary.publishedFileId,
                    detailSubscriptionStatusId = summary.publishedFileId,
                    detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.NotSubscribed,
                    detailSubscriptionMessage = context.getString(
                        R.string.workshop_unsubscribe_success,
                        summary.title.ifBlank { summary.publishedFileId.toString() },
                    ),
                )
            }.onFailure { error ->
                if (error is WorkshopSteamLoginRequiredException) {
                    Log.w(
                        WORKSHOP_SUBSCRIPTION_LOG_TAG,
                        "unsubscribeSelected failed appId=${summary.appId} publishedFileId=${summary.publishedFileId} reason=steamLoginRequired",
                        error,
                    )
                    showWorkshopSubscriptionSteamLoginRequired(context)
                    return@onFailure
                }
                Log.e(
                    WORKSHOP_SUBSCRIPTION_LOG_TAG,
                    "unsubscribeSelected failed appId=${summary.appId} publishedFileId=${summary.publishedFileId}",
                    error,
                )
                val errorMessage = if (error is WorkshopUnsubscriptionVerificationException) {
                    context.getString(R.string.workshop_unsubscribe_not_confirmed)
                } else {
                    error.message ?: error.javaClass.simpleName
                }
                uiState = uiState.copy(
                    detailSubscriptionLoadingId = null,
                    subscribedWorkshopIds = uiState.subscribedWorkshopIds + summary.publishedFileId,
                    detailSubscriptionStatusId = summary.publishedFileId,
                    detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Subscribed,
                    detailSubscriptionMessage = context.getString(
                        R.string.workshop_unsubscribe_failed,
                        errorMessage,
                    ),
                )
            }
        }
    }

    fun toggleSelectedDetailsTranslation(
        context: Context,
        onOpenBaiduTranslationCredentials: (String) -> Unit = {},
    ) {
        val details = uiState.selected ?: return
        val summary = details.summary
        val translationKey = details.cacheKey()
        if (uiState.detailTranslationModeKey == translationKey) {
            commentTranslationJob?.cancel()
            uiState = uiState.copy(
                detailTranslationModeKey = null,
                detailTranslationErrorMessage = null,
                commentTranslationErrorMessage = null,
            )
            return
        }
        if (uiState.detailTranslations[translationKey]?.complete == true) {
            uiState = uiState.copy(
                detailTranslationModeKey = translationKey,
                detailTranslationErrorMessage = null,
                commentTranslationErrorMessage = null,
            )
            translateWorkshopCommentsIfNeeded(
                context = context,
                details = details,
                onOpenBaiduTranslationCredentials = onOpenBaiduTranslationCredentials,
            )
            return
        }
        if (uiState.detailTranslationLoadingId == summary.publishedFileId) return

        val originalTitle = summary.title.trim()
        val originalDescription = summary.description.trim()
        val hasTranslatableComments = details.comments.any { comment -> comment.content.isNotBlank() }
        if (originalTitle.isBlank() && originalDescription.isBlank() && !hasTranslatableComments) {
            uiState = uiState.copy(detailTranslationErrorMessage = context.getString(R.string.workshop_translate_no_text))
            return
        }

        val credentials = BaiduTranslationCredentialsRepository(context).getCredentials()
        validateBaiduTranslationCredentials(context, credentials)?.let { message ->
            uiState = uiState.copy(detailTranslationErrorMessage = message)
            onOpenBaiduTranslationCredentials(message)
            return
        }

        val appId = summary.appId
        val publishedFileId = summary.publishedFileId
        uiState = uiState.copy(
            detailTranslationModeKey = translationKey,
            detailTranslationLoadingId = publishedFileId,
            detailTranslationErrorMessage = null,
            commentTranslationErrorMessage = null,
        )
        translateWorkshopCommentsIfNeeded(
            context = context,
            details = details,
            credentials = credentials,
            onOpenBaiduTranslationCredentials = onOpenBaiduTranslationCredentials,
        )
        detailTranslationJob = viewModelScope.launch {
            runCatching {
                val targetLanguage = mapLocaleLanguageToBaiduLanguage(Locale.getDefault()) ?: BAIDU_DEFAULT_TARGET_LANGUAGE
                val reference = buildBaiduModDescriptionReference(
                    modTitle = originalTitle,
                    gameTitle = BAIDU_STS_GAME_TITLE,
                )
                var translated = WorkshopDetailTranslation(summary.title, summary.description, complete = false)
                suspend fun translatePart(text: String): String = translateWithBaiduCredentials(
                    text = text,
                    targetLanguage = targetLanguage,
                    credentials = credentials,
                    reference = reference,
                )
                fun publish() {
                    uiState = uiState.copy(
                        detailTranslations = uiState.detailTranslations + (translationKey to translated),
                    )
                }
                // Independent requests: one failed part must not discard successful parts.
                var firstError: Throwable? = null
                if (originalTitle.isNotBlank()) {
                    try {
                        translated = translated.copy(title = translatePart(originalTitle))
                        publish()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        firstError = error
                    }
                }
                val sections = splitTranslationSections(originalDescription)
                val results = sections.toMutableList()
                for ((index, section) in sections.withIndex()) {
                    try {
                        val leading = section.takeWhile(Char::isWhitespace)
                        val trailing = section.takeLastWhile(Char::isWhitespace)
                        results[index] = if (section.isBlank()) section else leading + translatePart(section.trim()).trim() + trailing
                        translated = translated.copy(description = results.joinToString(""))
                        publish()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (firstError == null) firstError = error
                    }
                }
                firstError?.let { throw it }
                translated.copy(complete = true)
            }.onSuccess { translated ->
                val current = uiState.selected?.takeIf { selected ->
                    selected.summary.appId == appId && selected.summary.publishedFileId == publishedFileId
                }
                if (current == null) {
                    uiState = uiState.copy(detailTranslationLoadingId = null)
                    return@onSuccess
                }
                val currentTranslationKey = current.cacheKey()
                uiState = uiState.copy(
                    detailTranslations = uiState.detailTranslations + (currentTranslationKey to translated),
                    detailTranslationLoadingId = null,
                    detailTranslationErrorMessage = null,
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (uiState.selected?.cacheKey() != translationKey) return@onFailure
                if (error is BaiduTranslationApiException) {
                    val message = error.message ?: context.getString(R.string.workshop_translate_invalid_api_credentials)
                    uiState = uiState.copy(
                        detailTranslationLoadingId = null,
                        detailTranslationErrorMessage = message,
                    )
                    onOpenBaiduTranslationCredentials(message)
                    return@onFailure
                }
                uiState = uiState.copy(
                    detailTranslationLoadingId = null,
                    detailTranslationErrorMessage = error.message ?: context.getString(R.string.workshop_translate_failed),
                )
            }
        }
    }

    private fun translateWorkshopCommentsIfNeeded(
        context: Context,
        details: WorkshopItemDetails,
        credentials: BaiduTranslationCredentials? = null,
        onOpenBaiduTranslationCredentials: (String) -> Unit = {},
    ) {
        val detailKey = details.cacheKey()
        if (uiState.detailTranslationModeKey != detailKey) return
        if (uiState.commentLoadingId == details.summary.publishedFileId) return
        val translationKey = details.commentTranslationCacheKey() ?: return
        val commentsToTranslate = details.commentsNeedingTranslation()
        if (commentsToTranslate.isEmpty() || translationKey in commentTranslationsInFlight) return

        val resolvedCredentials = credentials ?: BaiduTranslationCredentialsRepository(context).getCredentials()
        validateBaiduTranslationCredentials(context, resolvedCredentials)?.let { message ->
            uiState = uiState.copy(
                commentTranslationLoadingKey = null,
                commentTranslationErrorMessage = context.getString(R.string.workshop_comments_translate_failed, message),
            )
            onOpenBaiduTranslationCredentials(message)
            return
        }

        commentTranslationsInFlight += translationKey
        uiState = uiState.copy(
            commentTranslationLoadingKey = translationKey,
            commentTranslationErrorMessage = null,
        )
        commentTranslationJob = viewModelScope.launch {
            try {
                runCatching {
                    val targetLanguage = mapLocaleLanguageToBaiduLanguage(Locale.getDefault()) ?: BAIDU_DEFAULT_TARGET_LANGUAGE
                    val reference = buildBaiduWorkshopCommentReference(
                        modTitle = details.summary.title,
                        gameTitle = BAIDU_STS_GAME_TITLE,
                    )
                    val translatedByComment = LinkedHashMap<String, String>()
                    var firstError: Exception? = null
                    commentsToTranslate.forEach { comment ->
                        try {
                            val sections = splitTranslationSections(comment.content)
                            val result = StringBuilder()
                            for (section in sections) {
                                if (section.isBlank()) {
                                    result.append(section)
                                } else {
                                    result.append(section.takeWhile(Char::isWhitespace))
                                    result.append(translateWithBaiduCredentials(
                                        text = section.trim(),
                                        targetLanguage = targetLanguage,
                                        credentials = resolvedCredentials,
                                        reference = reference,
                                    ).trim())
                                    result.append(section.takeLastWhile(Char::isWhitespace))
                                }
                            }
                            translatedByComment[comment.translationContentKey()] = result.toString()
                            val current = uiState.selected?.takeIf {
                                it.cacheKey() == detailKey && it.commentTranslationCacheKey() == translationKey
                            }
                            if (current != null) {
                                val updated = current.copy(comments = current.comments.map {
                                    translatedByComment[it.translationContentKey()]?.let { text ->
                                        it.copy(translatedContent = text)
                                    } ?: it
                                })
                                if (uiState.detailUpdate == null) detailsCache[detailKey] = updated
                                uiState = uiState.copy(selected = updated)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            if (firstError == null) firstError = error
                        }
                    }
                    firstError?.let { throw it }
                    translatedByComment
                }.onSuccess { translatedByComment ->
                    val current = uiState.selected?.takeIf { selected ->
                        selected.cacheKey() == detailKey && selected.commentTranslationCacheKey() == translationKey
                    }
                    if (current == null) {
                        if (uiState.commentTranslationLoadingKey == translationKey) {
                            uiState = uiState.copy(commentTranslationLoadingKey = null)
                        }
                        return@onSuccess
                    }
                    val translatedComments = current.comments.map { comment ->
                        val translatedContent = translatedByComment[comment.translationContentKey()]
                            ?.takeIf(String::isNotBlank)
                        if (translatedContent == null) {
                            comment
                        } else {
                            comment.copy(translatedContent = translatedContent)
                        }
                    }
                    val updatedDetails = current.copy(comments = translatedComments)
                    if (uiState.detailUpdate == null) detailsCache[updatedDetails.cacheKey()] = updatedDetails
                    uiState = uiState.copy(
                        selected = updatedDetails,
                        commentTranslationLoadingKey = if (uiState.commentTranslationLoadingKey == translationKey) {
                            null
                        } else {
                            uiState.commentTranslationLoadingKey
                        },
                        commentTranslationErrorMessage = null,
                    )
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    val stillSelectedInTranslationMode = uiState.detailTranslationModeKey == detailKey &&
                        uiState.selected?.let { selected ->
                            selected.cacheKey() == detailKey && selected.commentTranslationCacheKey() == translationKey
                        } == true
                    val nextLoadingKey = if (uiState.commentTranslationLoadingKey == translationKey) {
                        null
                    } else {
                        uiState.commentTranslationLoadingKey
                    }
                    if (!stillSelectedInTranslationMode) {
                        uiState = uiState.copy(commentTranslationLoadingKey = nextLoadingKey)
                        return@onFailure
                    }
                    val message = if (error is BaiduTranslationApiException) {
                        error.message ?: context.getString(R.string.workshop_translate_invalid_api_credentials)
                    } else {
                        error.message ?: context.getString(R.string.workshop_translate_failed)
                    }
                    uiState = uiState.copy(
                        commentTranslationLoadingKey = nextLoadingKey,
                        commentTranslationErrorMessage = context.getString(R.string.workshop_comments_translate_failed, message),
                    )
                    if (error is BaiduTranslationApiException) {
                        onOpenBaiduTranslationCredentials(message)
                    }
                }
            } finally {
                commentTranslationsInFlight -= translationKey
            }
        }
    }

    private suspend fun translateWithBaiduCredentials(
        text: String,
        targetLanguage: String,
        credentials: BaiduTranslationCredentials,
        reference: String,
    ): String {
        val key = listOf(targetLanguage, reference, text)
        translatedParts[key]?.let { return it }
        val translated = translationClient.translate(
            text = text,
            sourceLanguage = BAIDU_AUTO_DETECT_LANGUAGE,
            targetLanguage = targetLanguage,
            credentials = credentials,
            reference = reference,
        )
        translatedParts[key] = translated
        return translated
    }

    private fun validateBaiduTranslationCredentials(
        context: Context,
        credentials: BaiduTranslationCredentials,
    ): String? = when {
        credentials.appId.isBlank() && credentials.apiKey.isBlank() ->
            context.getString(R.string.workshop_translate_missing_app_id_api_key)

        credentials.appId.isBlank() -> context.getString(R.string.workshop_translate_missing_app_id)
        credentials.apiKey.isBlank() -> context.getString(R.string.workshop_translate_missing_api_key)
        else -> null
    }

    fun pauseDownload(context: Context, task: WorkshopDownloadTaskUi) {
        val details = task.details
        if (task.status.isRunningDownload()) {
            updateDownloadTaskAsync(task.publishedFileId) {
                it.copy(
                    status = WorkshopDownloadTaskStatus.Pausing,
                    message = context.getString(R.string.workshop_status_pausing),
                    updatedAtMillis = System.currentTimeMillis(),
                    preservePartialDownload = true,
                )
            }
            WorkshopDownloadProcessService.pause(context, details.summary.appId, details.summary.publishedFileId, createDownloadResultReceiver(context.applicationContext, details.summary))
        } else {
            updateDownloadTaskAsync(task.publishedFileId) {
                it.copy(
                    status = WorkshopDownloadTaskStatus.Paused,
                    message = context.getString(R.string.workshop_status_paused),
                    updatedAtMillis = System.currentTimeMillis(),
                    preservePartialDownload = true,
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                metadataStore?.updateState(details.summary.appId, details.summary.publishedFileId, WorkshopModCardState.DownloadPaused, context.getString(R.string.workshop_status_paused))
            }
        }
        uiState = uiState.copy(downloadStatus = context.getString(R.string.workshop_status_pausing), downloadInProgress = true)
    }

    fun resumeDownload(context: Context, task: WorkshopDownloadTaskUi) {
        restartDownload(
            context = context,
            task = task,
            message = context.getString(R.string.main_mod_workshop_action_continue_download),
            preservePartialDownload = true,
        )
    }

    fun retryDownload(context: Context, task: WorkshopDownloadTaskUi) {
        restartDownload(
            context = context,
            task = task,
            message = context.getString(R.string.main_mod_workshop_action_redownload),
            preservePartialDownload = task.preservePartialDownload || task.downloadedBytes > 0L,
        )
    }

    fun cancelDownload(context: Context, task: WorkshopDownloadTaskUi) {
        val details = task.details
        if (task.status.isRunningDownload()) {
            updateDownloadTaskAsync(task.publishedFileId) {
                it.copy(status = WorkshopDownloadTaskStatus.Cancelling, message = context.getString(R.string.workshop_status_canceling), updatedAtMillis = System.currentTimeMillis())
            }
            WorkshopDownloadProcessService.cancel(context, details.summary.appId, details.summary.publishedFileId, createDownloadResultReceiver(context.applicationContext, details.summary))
            uiState = uiState.copy(downloadStatus = context.getString(R.string.workshop_status_canceling), downloadInProgress = true)
            return
        }
        WorkshopDownloadCenterStore.removeInMemory(task.publishedFileId)
        uiState = uiState.copy(
            downloadStatus = context.getString(R.string.workshop_status_cancelled),
            downloadInProgress = false,
            installedMods = uiState.installedMods.filterNot {
                it.appId == details.summary.appId && it.publishedFileId == details.summary.publishedFileId
            },
        )
        persistDownloadTaskAsync {
            WorkshopDownloadTaskStore(context).removeAndMarkDeleted(task.publishedFileId)
            metadataStore?.remove(details.summary.appId, details.summary.publishedFileId)
                    RuntimePaths.workshopItemDirs(
                        context,
                        details.summary.appId,
                        details.summary.publishedFileId,
                    ).forEach { it.deleteRecursively() }
            WorkshopDownloadProcessService.startNextQueued(context)
        }
    }

    fun download(context: Context, item: WorkshopItemSummary) {
        // The market page is lazy in the launcher pager. Initialize the service on a tutorial-card
        // click so deferring the hidden market page cannot turn the download action into a no-op.
        if (service == null) {
            service = WorkshopService(context.applicationContext)
            loadInstalledState(context)
        }
        val existingTask = WorkshopDownloadCenterStore.find(item.publishedFileId)
        if (blockBlockedWorkshopDownload(context, item, existingTask)) return
        if (existingTask?.status == WorkshopDownloadTaskStatus.Paused) {
            resumeDownload(context, existingTask)
            return
        }
        if (existingTask?.status == WorkshopDownloadTaskStatus.Failed || existingTask?.status == WorkshopDownloadTaskStatus.Cancelled) {
            retryDownload(context, existingTask)
            return
        }
        val state = resolveWorkshopModDownloadState(
            item = item,
            installedMods = uiState.installedMods,
            downloadTasks = WorkshopDownloadCenterStore.tasks,
            preparingDownloadIds = uiState.preparingDownloadIds,
        )
        if (!state.canStartDownload) return
        val selectedDetails = if (state == WorkshopModDownloadState.UpdateAvailable) null
            else findCachedDetails(item.appId, item.publishedFileId)
        markPreparingDownload(item.publishedFileId)
        if (selectedDetails != null) {
            startDownloadAfterDependencyCheck(context, item, selectedDetails)
            return
        }
        val currentService = service ?: run {
            clearPreparingDownload(item.publishedFileId)
            return
        }
        viewModelScope.launch {
            uiState = uiState.copy(downloadStatus = context.getString(R.string.workshop_status_checking_dependencies))
            runCatching {
                withContext(Dispatchers.IO) { currentService.getDetails(item.appId, item.publishedFileId, item) }
            }.onSuccess { details ->
                detailsCache[details.cacheKey()] = details
                uiState = uiState.copy(selected = details)
                startDownloadAfterDependencyCheck(context, details.summary, details)
            }.onFailure { error ->
                uiState = uiState.copy(
                    downloadStatus = context.getString(R.string.workshop_status_dependency_check_failed, error.message ?: error.javaClass.simpleName),
                    preparingDownloadIds = uiState.preparingDownloadIds - item.publishedFileId,
                )
            }
        }
    }

    fun updateAll(context: Context, items: List<WorkshopItemSummary>) {
        if (updateAllJob?.isActive == true) return
        updateAllJob = viewModelScope.launch {
            uiState = uiState.copy(preparingAllUpdates = true)
            try {
                items.distinctBy { it.appId to it.publishedFileId }.forEach { item ->
                    // Reuse the market's dependency confirmation without replacing an unanswered
                    // prompt with the next item's prompt. Actual downloads use the existing queue.
                    snapshotFlow { uiState.pendingDependencyDownload == null && uiState.preparingDownloadIds.isEmpty() }
                        .first { it }
                    download(context, item)
                    snapshotFlow { uiState.pendingDependencyDownload == null && item.publishedFileId !in uiState.preparingDownloadIds }
                        .first { it }
                }
            } finally {
                uiState = uiState.copy(preparingAllUpdates = false)
            }
        }
    }

    fun confirmPendingDependencyDownload(context: Context) {
        val pending = uiState.pendingDependencyDownload ?: return
        uiState = uiState.copy(pendingDependencyDownload = null)
        if (blockBlockedWorkshopDownload(context, pending.details.summary, WorkshopDownloadCenterStore.find(pending.details.summary.publishedFileId))) return
        pending.missingDependencies.forEach { dependency -> startDownload(context, dependency) }
        startDownload(context, pending.details.summary, pending.details)
    }

    fun downloadPendingCurrentOnly(context: Context) {
        val pending = uiState.pendingDependencyDownload ?: return
        uiState = uiState.copy(pendingDependencyDownload = null)
        startDownload(context, pending.details.summary, pending.details)
    }

    fun dismissPendingDependencyDownload() {
        uiState = uiState.copy(pendingDependencyDownload = null)
    }

    private fun startDownloadAfterDependencyCheck(
        context: Context,
        item: WorkshopItemSummary,
        details: WorkshopItemDetails,
    ) {
        if (blockBlockedWorkshopDownload(context, item, WorkshopDownloadCenterStore.find(item.publishedFileId))) {
            clearPreparingDownload(item.publishedFileId)
            return
        }
        val missingDependencies = findMissingWorkshopDependencies(
            dependencies = details.dependencies,
            installedMods = uiState.installedMods,
            downloadTasks = WorkshopDownloadCenterStore.tasks,
        )
        if (missingDependencies.isNotEmpty()) {
            uiState = uiState.copy(
                pendingDependencyDownload = WorkshopPendingDependencyDownload(
                    details = details,
                    missingDependencies = missingDependencies,
                ),
                preparingDownloadIds = uiState.preparingDownloadIds - item.publishedFileId,
            )
            return
        }
        startDownload(context, item, details)
    }

    private fun startDownload(
        context: Context,
        summary: WorkshopItemSummary,
        details: WorkshopItemDetails? = null,
    ) {
        if (blockBlockedWorkshopDownload(context, summary, WorkshopDownloadCenterStore.find(summary.publishedFileId))) {
            clearPreparingDownload(summary.publishedFileId)
            return
        }
        val alreadyRunning = WorkshopDownloadCenterStore.tasks.any { it.status.isRunningDownload() }
        val queuedDetails = details ?: WorkshopItemDetails(summary = summary)
        val queuedTask = WorkshopDownloadTaskUi(
            publishedFileId = summary.publishedFileId,
            title = summary.title,
            status = WorkshopDownloadTaskStatus.Queued,
            message = if (alreadyRunning) context.getString(R.string.workshop_status_added_to_queue) else context.getString(R.string.workshop_download_task_message_waiting),
            details = queuedDetails,
            previewUrl = summary.previewUrl,
            description = summary.description,
            authorName = summary.authorName,
            fileSizeBytes = summary.fileSizeBytes,
            totalBytes = summary.fileSizeBytes.takeIf { it > 0L },
            errorClass = "",
            errorMessage = "",
            errorStackTrace = "",
            downloadLog = "",
        )
        val queuedRecord = uiState.installedMods.firstOrNull {
            it.appId == summary.appId && it.publishedFileId == summary.publishedFileId
        }?.copy(
            cardState = WorkshopModCardState.Downloading,
            statusText = context.getString(R.string.workshop_download_task_message_waiting),
        ) ?: WorkshopInstalledModRecord(
            appId = summary.appId,
            publishedFileId = summary.publishedFileId,
            title = summary.title,
                description = summary.description,
                previewUrl = summary.previewUrl,
                versionText = summary.updatedAtMillis.toString(),
                updatedAtMillis = summary.updatedAtMillis,
                installedAtMillis = System.currentTimeMillis(),
            localJarPath = "",
            cardState = WorkshopModCardState.Downloading,
            statusText = context.getString(R.string.workshop_download_task_message_waiting),
            dependencies = queuedDetails.dependencies,
        )
        WorkshopDownloadCenterStore.upsertInMemory(queuedTask)
        uiState = uiState.copy(
            downloadStatus = if (alreadyRunning) {
                context.getString(R.string.workshop_status_added_to_queue_format, summary.title)
            } else {
                context.getString(R.string.workshop_status_starting_download_format, summary.title)
            },
            downloadInProgress = true,
            preparingDownloadIds = uiState.preparingDownloadIds - summary.publishedFileId,
            installedMods = listOf(queuedRecord) + uiState.installedMods.filterNot {
                it.appId == queuedRecord.appId && it.publishedFileId == queuedRecord.publishedFileId
            },
        )
        persistDownloadTaskAsync {
            WorkshopDownloadCenterStore.persistUpsert(queuedTask)
            metadataStore?.upsert(queuedRecord)
            WorkshopDownloadProcessService.startNextQueued(context)
        }
    }

    private fun restartDownload(context: Context, task: WorkshopDownloadTaskUi, message: String) {
        restartDownload(context, task, message, preservePartialDownload = false)
    }

    private fun restartDownload(
        context: Context,
        task: WorkshopDownloadTaskUi,
        message: String,
        preservePartialDownload: Boolean,
    ) {
        val details = task.details
        if (blockBlockedWorkshopDownload(context, details.summary, task)) return
        val queuedTask = task.copy(
            status = WorkshopDownloadTaskStatus.Queued,
            message = if (WorkshopDownloadCenterStore.hasRunningTask()) context.getString(R.string.workshop_status_added_to_queue) else message,
            updatedAtMillis = System.currentTimeMillis(),
            progressPercent = if (preservePartialDownload) task.progressPercent else null,
            downloadedBytes = if (preservePartialDownload) task.downloadedBytes else 0L,
            completedFiles = if (preservePartialDownload) task.completedFiles else null,
            completedChunks = if (preservePartialDownload) task.completedChunks else null,
            errorClass = "",
            errorMessage = "",
            errorStackTrace = "",
            downloadLog = if (preservePartialDownload) task.downloadLog else "",
            preservePartialDownload = preservePartialDownload,
        )
        WorkshopDownloadCenterStore.upsertInMemory(queuedTask)
        uiState = uiState.copy(downloadInProgress = true)
        persistDownloadTaskAsync {
            WorkshopDownloadCenterStore.persistUpsert(queuedTask)
            metadataStore?.updateState(details.summary.appId, details.summary.publishedFileId, WorkshopModCardState.Downloading, context.getString(R.string.workshop_download_task_message_waiting))
            WorkshopDownloadProcessService.startNextQueued(context)
        }
    }

    private fun blockBlockedWorkshopDownload(
        context: Context,
        summary: WorkshopItemSummary,
        task: WorkshopDownloadTaskUi?,
    ): Boolean {
        if (!WorkshopDownloadBlocklist.isBlocked(summary)) return false
        val message = context.getString(
            R.string.workshop_status_download_blocked,
            summary.title.ifBlank { summary.publishedFileId.toString() },
        )
        val blockedTaskMessage = context.getString(R.string.workshop_download_task_message_blocked)
        if (task != null) {
            updateDownloadTaskAsync(task.publishedFileId) {
                it.copy(
                    status = WorkshopDownloadTaskStatus.Cancelled,
                    message = blockedTaskMessage,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                metadataStore?.updateState(
                    summary.appId,
                    summary.publishedFileId,
                    WorkshopModCardState.DownloadFailed,
                    blockedTaskMessage,
                )
            }
        }
        uiState = uiState.copy(
            downloadStatus = message,
            downloadInProgress = WorkshopDownloadCenterStore.tasks.any { it.status.isActiveDownload() },
            preparingDownloadIds = uiState.preparingDownloadIds - summary.publishedFileId,
        )
        return true
    }

    private fun markPreparingDownload(publishedFileId: ULong) {
        if (publishedFileId in uiState.preparingDownloadIds) return
        uiState = uiState.copy(preparingDownloadIds = uiState.preparingDownloadIds + publishedFileId)
    }

    private fun clearPreparingDownload(publishedFileId: ULong) {
        if (publishedFileId !in uiState.preparingDownloadIds) return
        uiState = uiState.copy(preparingDownloadIds = uiState.preparingDownloadIds - publishedFileId)
    }

    private fun createDownloadResultReceiver(context: Context, summary: WorkshopItemSummary): ResultReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
            val message = resultData.getString(WorkshopDownloadProcessService.EXTRA_MESSAGE).orEmpty()
            val status = resultData.getString(WorkshopDownloadProcessService.EXTRA_TASK_STATUS)?.toTaskStatus()
            val immediateStatus = status.takeUnless {
                resultCode == WorkshopDownloadProcessService.RESULT_COMPLETED && it == WorkshopDownloadTaskStatus.Completed
            }
            if (immediateStatus != null || message.isNotBlank()) {
                updateDownloadTaskAsync(summary.publishedFileId) {
                    it.copy(
                        status = immediateStatus ?: it.status,
                        message = message.ifBlank { it.message },
                        updatedAtMillis = System.currentTimeMillis(),
                        progressPercent = resultData.optionalInt(WorkshopDownloadProcessService.EXTRA_PROGRESS_PERCENT) ?: it.progressPercent,
                        downloadedBytes = resultData.optionalLong(WorkshopDownloadProcessService.EXTRA_WRITTEN_BYTES) ?: it.downloadedBytes,
                        totalBytes = resultData.optionalLong(WorkshopDownloadProcessService.EXTRA_TOTAL_BYTES) ?: it.totalBytes,
                        completedFiles = resultData.optionalInt(WorkshopDownloadProcessService.EXTRA_COMPLETED_FILES) ?: it.completedFiles,
                        totalFiles = resultData.optionalInt(WorkshopDownloadProcessService.EXTRA_TOTAL_FILES) ?: it.totalFiles,
                        completedChunks = resultData.optionalInt(WorkshopDownloadProcessService.EXTRA_COMPLETED_CHUNKS) ?: it.completedChunks,
                        totalChunks = resultData.optionalInt(WorkshopDownloadProcessService.EXTRA_TOTAL_CHUNKS) ?: it.totalChunks,
                        errorClass = resultData.getString(WorkshopDownloadProcessService.EXTRA_ERROR_CLASS).orEmpty().ifBlank { it.errorClass },
                        errorMessage = resultData.getString(WorkshopDownloadProcessService.EXTRA_ERROR_MESSAGE).orEmpty().ifBlank { it.errorMessage },
                        errorStackTrace = resultData.getString(WorkshopDownloadProcessService.EXTRA_ERROR_STACKTRACE).orEmpty().ifBlank { it.errorStackTrace },
                    )
                }
            }
            when (resultCode) {
                WorkshopDownloadProcessService.RESULT_PROGRESS -> {
                    if (message.isNotBlank()) uiState = uiState.copy(downloadStatus = message)
                }
                WorkshopDownloadProcessService.RESULT_COMPLETED -> {
                    val completionMessage = message.ifBlank { context.getString(R.string.workshop_status_download_completed) }
                    val currentMetadataStore = metadataStore
                    viewModelScope.launch {
                        val installedMods = withContext(Dispatchers.IO) {
                            currentMetadataStore?.markMissingFiles()
                            currentMetadataStore?.list().orEmpty()
                        }
                        uiState = uiState.copy(
                            downloadStatus = completionMessage,
                            installedMods = installedMods,
                        )
                        updateDownloadTaskAsync(summary.publishedFileId) {
                            it.copy(
                                status = WorkshopDownloadTaskStatus.Completed,
                                message = completionMessage,
                                progressPercent = 100,
                                downloadedBytes = (it.totalBytes ?: it.downloadedBytes).coerceAtLeast(it.downloadedBytes),
                                completedFiles = it.totalFiles ?: it.completedFiles,
                                updatedAtMillis = System.currentTimeMillis(),
                            )
                        }
                        uiState = uiState.copy(
                            downloadInProgress = WorkshopDownloadCenterStore.tasks.any { it.status.isActiveDownload() },
                        )
                    }
                }
                WorkshopDownloadProcessService.RESULT_FAILURE -> {
                    uiState = uiState.copy(
                        downloadStatus = message.ifBlank { context.getString(R.string.workshop_status_download_failed) },
                        downloadInProgress = false,
                    )
                    refreshDownloadState(context)
                }
                WorkshopDownloadProcessService.RESULT_PAUSED -> {
                    uiState = uiState.copy(
                        downloadStatus = message.ifBlank { context.getString(R.string.workshop_status_paused) },
                        downloadInProgress = false,
                    )
                    refreshDownloadState(context)
                }
                WorkshopDownloadProcessService.RESULT_CANCELLED -> {
                    uiState = uiState.copy(
                        downloadStatus = message.ifBlank { context.getString(R.string.workshop_status_cancelled) },
                        downloadInProgress = false,
                    )
                    refreshDownloadState(context)
                }
            }
        }
    }

    fun checkUpdates(context: Context) {
        viewModelScope.launch {
            uiState = uiState.copy(downloadStatus = context.getString(R.string.workshop_status_checking_updates), updateChecking = true)
            runCatching {
                withContext(Dispatchers.IO) {
                    val report = WorkshopUpdateChecker(context).checkInstalledMods()
                    Pair(report, metadataStore?.list().orEmpty())
                }
            }
                .onSuccess { (report, installedMods) ->
                    val results = report.results
                    val updateCount = results.count { it.hasUpdate }
                    uiState = uiState.copy(
                        updateResults = results,
                        installedMods = installedMods,
                        updateChecking = false,
                        downloadStatus = if (updateCount > 0) {
                            buildString {
                                append(context.getString(R.string.workshop_status_updates_found, updateCount))
                                if (report.failedCount > 0) append(context.getString(R.string.workshop_status_update_failed_suffix, report.failedCount))
                            }
                        } else if (report.failedCount > 0) {
                            context.getString(R.string.workshop_status_update_check_completed_failed, report.failedCount)
                        } else {
                            context.getString(R.string.workshop_status_all_up_to_date)
                        },
                    )
                }
                .onFailure { error ->
                    uiState = uiState.copy(
                        updateChecking = false,
                        downloadStatus = context.getString(R.string.workshop_status_update_check_failed, error.message ?: error.javaClass.simpleName),
                    )
                }
        }
    }
}

internal data class WorkshopUiState(
    val browseLoading: Boolean = false,
    val loadProgress: WorkshopLoadProgress? = null,
    val loadingMore: Boolean = false,
    val nextPage: Int = 1,
    val hasMorePages: Boolean = true,
    val listMode: WorkshopListMode = WorkshopListMode.Browse,
    val detailLoadingId: ULong? = null,
    val detailLoadProgress: WorkshopLoadProgress? = null,
    val detailUpdate: WorkshopDetailUpdate? = null,
    val downloadInProgress: Boolean = false,
    val preparingAllUpdates: Boolean = false,
    val updateChecking: Boolean = false,
    val steamLoggedIn: Boolean = false,
    val items: List<WorkshopItemSummary> = emptyList(),
    val selected: WorkshopItemDetails? = null,
    val downloadStatus: String = "",
    val installedMods: List<WorkshopInstalledModRecord> = emptyList(),
    val updateResults: List<WorkshopUpdateCheckResult> = emptyList(),
    val pendingDependencyDownload: WorkshopPendingDependencyDownload? = null,
    val preparingDownloadIds: Set<ULong> = emptySet(),
    val commentLoadingId: ULong? = null,
    val commentErrorMessage: String? = null,
    val commentTranslationLoadingKey: String? = null,
    val commentTranslationErrorMessage: String? = null,
    val detailTranslationModeKey: String? = null,
    val detailTranslations: Map<String, WorkshopDetailTranslation> = emptyMap(),
    val detailTranslationLoadingId: ULong? = null,
    val detailTranslationErrorMessage: String? = null,
    val detailChangeNotes: Map<String, WorkshopChangeNotes> = emptyMap(),
    val detailChangeNotesLoadingId: ULong? = null,
    val detailChangeNotesErrorMessage: String? = null,
    val detailSubscriptionLoadingId: ULong? = null,
    val detailSubscriptionStatusId: ULong? = null,
    val detailSubscriptionStatus: WorkshopDetailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Unknown,
    val subscribedWorkshopIds: Set<ULong> = emptySet(),
    val detailSubscriptionMessage: String? = null,
    val errorMessage: String? = null,
) {
    fun detailSubscriptionStatusFor(publishedFileId: ULong): WorkshopDetailSubscriptionStatus =
        if (detailSubscriptionStatusId == publishedFileId) detailSubscriptionStatus else WorkshopDetailSubscriptionStatus.Unknown

    companion object {
        const val PAGE_SIZE = 30
    }
}

internal enum class WorkshopListMode {
    Browse,
    Subscriptions,
}

internal enum class WorkshopDetailSubscriptionStatus {
    Unknown,
    Checking,
    NotSubscribed,
    Subscribed,
}

internal data class WorkshopPendingDependencyDownload(
    val details: WorkshopItemDetails,
    val missingDependencies: List<WorkshopItemSummary>,
)

internal data class WorkshopDetailTranslation(
    val title: String,
    val description: String,
    val complete: Boolean = true,
)

private fun WorkshopItemDetails.shouldLoadWorkshopComments(): Boolean =
    commentThreadContext != null && commentCount != 0L

private fun WorkshopItemDetails.shouldLoadInitialWorkshopComments(): Boolean =
    shouldLoadWorkshopComments() && comments.isEmpty()

internal fun WorkshopItemDetails.retainLoadedCommentsFrom(
    current: WorkshopItemDetails?,
    hasNewCommentResponse: Boolean = false,
): WorkshopItemDetails {
    if (current == null || current.cacheKey() != cacheKey() ||
        (current.comments.isEmpty() && !(hasNewCommentResponse && current.commentCount == 0L)) ||
        (commentCount == 0L && !hasNewCommentResponse)
    ) return this
    return copy(
        commentsUrl = current.commentsUrl,
        commentThreadContext = commentThreadContext ?: current.commentThreadContext,
        commentCount = current.commentCount,
        commentPage = current.commentPage,
        commentTotalPages = current.commentTotalPages,
        hasPreviousCommentPage = current.hasPreviousCommentPage,
        hasNextCommentPage = current.hasNextCommentPage,
        comments = current.comments,
    )
}

private fun WorkshopItemDetails.canReuseForDetailOpen(fallbackSummary: WorkshopItemSummary?): Boolean {
    if (!hasReusableCommunityData()) return false
    if (fallbackSummary == null || fallbackSummary.appId != summary.appId || fallbackSummary.publishedFileId != summary.publishedFileId) {
        return true
    }
    return fallbackSummary.updatedAtMillis <= 0L ||
        summary.updatedAtMillis <= 0L ||
        fallbackSummary.updatedAtMillis <= summary.updatedAtMillis
}

/**
 * True when the cached copy is reusable except that the card it was opened from reports a strictly
 * newer published version; those opens go through stale-while-revalidate instead of a full reload.
 */
private fun isCachedDetailStaleVersusFallback(
    cached: WorkshopItemSummary,
    fallbackSummary: WorkshopItemSummary?,
): Boolean {
    if (fallbackSummary == null) return false
    if (fallbackSummary.appId != cached.appId || fallbackSummary.publishedFileId != cached.publishedFileId) {
        return false
    }
    if (cached.updatedAtMillis <= 0L || fallbackSummary.updatedAtMillis <= 0L) return false
    return fallbackSummary.updatedAtMillis > cached.updatedAtMillis
}

private fun WorkshopItemDetails.hasReusableCommunityData(): Boolean =
    commentThreadContext != null || commentCount != null || comments.isNotEmpty()

private fun WorkshopItemDetails.mergeCachedCommunityData(cached: WorkshopItemDetails?): WorkshopItemDetails {
    if (cached == null || cached.cacheKey() != cacheKey()) return this
    val communityMissing = commentThreadContext == null && commentCount == null
    val samePublishedVersion = summary.updatedAtMillis <= 0L ||
        cached.summary.updatedAtMillis <= 0L ||
        summary.updatedAtMillis == cached.summary.updatedAtMillis
    val useCachedDescription = cached.summary.description.length > summary.description.length &&
        samePublishedVersion &&
        (communityMissing || cached.summary.description.contains(summary.description.trim()))
    val useCachedComments = communityMissing && cached.commentThreadContext != null
    val useCachedDependencies = communityMissing && dependencies.isEmpty() && cached.dependencies.isNotEmpty()
    return copy(
        summary = summary.copy(
            description = if (useCachedDescription) cached.summary.description else summary.description,
            authorName = summary.authorName.ifBlank { cached.summary.authorName },
            author = summary.author ?: cached.summary.author,
        ),
        fullDescriptionUnavailable = if (useCachedDescription) {
            cached.fullDescriptionUnavailable
        } else {
            fullDescriptionUnavailable
        },
        changeNotes = changeNotes.ifBlank { cached.changeNotes },
        creators = creators.ifEmpty { cached.creators },
        changeNotesUrl = changeNotesUrl.ifBlank { cached.changeNotesUrl },
        dependencies = if (useCachedDependencies) cached.dependencies else dependencies,
        commentThreadContext = if (useCachedComments) cached.commentThreadContext else commentThreadContext,
        commentCount = if (useCachedComments) cached.commentCount else commentCount,
        commentPage = if (useCachedComments && cached.comments.isNotEmpty()) cached.commentPage else commentPage,
        commentTotalPages = if (useCachedComments) cached.commentTotalPages else commentTotalPages,
        hasPreviousCommentPage = if (useCachedComments && cached.comments.isNotEmpty()) {
            cached.hasPreviousCommentPage
        } else {
            hasPreviousCommentPage
        },
        hasNextCommentPage = if (useCachedComments) cached.hasNextCommentPage else hasNextCommentPage,
        comments = if (useCachedComments && cached.comments.isNotEmpty()) cached.comments else comments,
    )
}

private fun WorkshopItemDetails.retainDetailStateFrom(current: WorkshopItemDetails): WorkshopItemDetails {
    if (current.cacheKey() != cacheKey()) return this
    val merged = mergeCachedCommunityData(current)
    return merged.copy(
        changeNotes = merged.changeNotes.ifBlank { current.changeNotes },
        changeNotesUrl = merged.changeNotesUrl.ifBlank { current.changeNotesUrl },
        dependencies = if (merged.dependencies.isEmpty()) current.dependencies else merged.dependencies,
    )
}

private fun WorkshopItemDetails.cacheKey(): String = detailsCacheKey(summary.appId, summary.publishedFileId)

private fun detailsCacheKey(appId: UInt, publishedFileId: ULong): String = "$appId:$publishedFileId"

internal fun WorkshopItemDetails.commentTranslationCacheKey(): String? {
    if (comments.none { comment -> comment.content.isNotBlank() }) return null
    val contentSignature = comments.joinToString(separator = "|") { comment ->
        comment.translationContentKey()
    }
    return "${detailsCacheKey(summary.appId, summary.publishedFileId)}:comments:$commentPage:$contentSignature"
}

private fun WorkshopItemDetails.commentsNeedingTranslation(): List<WorkshopComment> =
    comments.filter { comment ->
        comment.content.isNotBlank() && comment.translatedContent.isBlank()
    }

private fun WorkshopComment.translationContentKey(): String =
    "${id.ifBlank { "anonymous" }}:${content.hashCode()}"

internal fun WorkshopInstalledModRecord.toWorkshopItemSummary(): WorkshopItemSummary = WorkshopItemSummary(
    publishedFileId = publishedFileId,
    appId = appId,
    title = title,
    previewUrl = previewUrl,
    description = description,
    updatedAtMillis = updatedAtMillis,
)

private fun WorkshopItemDetails.commentUnavailableMessage(context: Context): String? = when {
    commentCount == 0L -> null
    commentThreadContext == null -> context.getString(R.string.workshop_comments_unavailable)
    else -> null
}

private fun String.toTaskStatus(): WorkshopDownloadTaskStatus? = when (this) {
    "Queued" -> WorkshopDownloadTaskStatus.Queued
    "Resolving" -> WorkshopDownloadTaskStatus.Resolving
    "Downloading" -> WorkshopDownloadTaskStatus.Downloading
    "Pausing" -> WorkshopDownloadTaskStatus.Pausing
    "Cancelling" -> WorkshopDownloadTaskStatus.Cancelling
    "Paused" -> WorkshopDownloadTaskStatus.Paused
    "Completed" -> WorkshopDownloadTaskStatus.Completed
    "Failed" -> WorkshopDownloadTaskStatus.Failed
    "Cancelled" -> WorkshopDownloadTaskStatus.Cancelled
    else -> null
}

private fun Bundle.optionalInt(key: String): Int? = if (containsKey(key)) getInt(key) else null

private fun Bundle.optionalLong(key: String): Long? = if (containsKey(key)) getLong(key) else null

private const val BAIDU_AUTO_DETECT_LANGUAGE = "auto"
private const val BAIDU_DEFAULT_TARGET_LANGUAGE = "zh"
private const val BAIDU_STS_GAME_TITLE = "Slay the Spire"
private const val WORKSHOP_SUBSCRIPTION_LOG_TAG = "WorkshopSubscribe"
private const val WORKSHOP_PERF_TAG = "WorkshopPerf"

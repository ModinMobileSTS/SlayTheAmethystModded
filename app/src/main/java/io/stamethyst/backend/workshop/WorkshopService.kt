package io.stamethyst.backend.workshop

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.stamethyst.backend.github.withAcceleratedCookieJar
import io.stamethyst.backend.steamcloud.SteamCloudAcceleratedHttp
import io.stamethyst.backend.steamcloud.SteamAuthenticationCircuitBreaker
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore.AuthSnapshot
import io.stamethyst.ui.preferences.LauncherPreferences
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import top.apricityx.workshop.steam.protocol.CmServer
import top.apricityx.workshop.steam.protocol.SessionContext
import top.apricityx.workshop.steam.protocol.STEAM_PUBLISHED_FILE_QUERY_TYPE_RANKED_BY_TEXT_SEARCH
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.steam.protocol.SteamPublishedFileClient
import top.apricityx.workshop.steam.protocol.SteamPublishedFileQuery
import top.apricityx.workshop.steam.protocol.SteamPublishedFileQueryResult
import top.apricityx.workshop.workshop.WorkshopDownloadEngine

internal class WorkshopService(
    private val context: Context,
    private val client: OkHttpClient = SteamCloudAcceleratedHttp.createClient(
        context = context,
        connectTimeoutMs = 15_000L,
        readTimeoutMs = 60_000L,
        callTimeoutMs = 120_000L,
        // Keep the interceptor installed so a long-lived service can resume acceleration
        // after a VPN/region state change; enabledProvider gates each request dynamically.
        enabled = true,
        enabledProvider = { LauncherPreferences.isWorkshopWattAccelerationEnabled(context) },
    ),
    private val contentDownloaderFactory: ((WorkshopService) -> WorkshopContentDownloader)? = null,
) {
    init {
        synchronized(activeInstances) {
            activeInstances += this
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val identity = WorkshopSteamClientIdentity(context)
    /**
     * Steam directory lookups are plain HTTPS to api.steampowered.com, so they keep
     * acceleration. CM websocket sessions use the same client so WATT can route
     * steamserver.net endpoints too.
     */
    private val directoryHttpClient = client
    private val steamWebSession = WorkshopSteamWebSession(
        context = context,
        directoryClient = directoryHttpClient,
        cmHttpClient = client,
        identity = identity,
    )
    private val steamLanguagePreference: SteamLanguagePreference
        get() = runCatching { LauncherPreferences.readWorkshopSteamLanguage(context) }
            .getOrDefault(SteamLanguagePreference.SimplifiedChinese)
    // The cookie jar must be bound through withAcceleratedCookieJar: a plain cookieJar(...) call is
    // invisible to the acceleration interceptor, which re-issues routed requests on its own call
    // factory and so bypasses OkHttp's cookie bridge entirely. That left every accelerated Steam
    // workshop browse without steamLoginSecure, and Steam answered with the logged-out view.
    private val workshopClient = client.newBuilder()
        .withAcceleratedCookieJar(steamWebSession.cookieJar)
        .addInterceptor(SteamLanguageInterceptor(::steamLanguagePreference))
        .build()
    private val browseDetailClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
    private val communityDetailCacheLock = Any()
    private val communityDetailCache = LinkedHashMap<CommunityDetailCacheKey, CachedCommunityDetail>(16, 0.75f, true)
    private val communityDetailInFlight = mutableMapOf<CommunityDetailCacheKey, CompletableDeferred<LocalizedWorkshopDetail>>()

    fun hasSteamAuth(): Boolean = SteamCloudAuthStore.readSnapshot(context).isComplete

    fun cancelActiveCalls() {
        listOf(client, workshopClient, browseDetailClient).forEach { httpClient ->
            httpClient.dispatcher.queuedCalls().forEach { it.cancel() }
            httpClient.dispatcher.runningCalls().forEach { it.cancel() }
        }
    }

    fun close() {
        cancelActiveCalls()
        synchronized(activeInstances) {
            activeInstances -= this
        }
    }

    fun authSnapshot(): AuthSnapshot = SteamCloudAuthStore.readSnapshot(context)

    /**
     * Progress session that stage reports are attributed to, or null when nobody is listening.
     *
     * The browse pipeline is a plain suspend chain with no progress parameter, so threading a session
     * id through every private helper would touch unrelated call sites. Keeping it on the instance is
     * safe because a [WorkshopService] serves one screen and the market runs one browse at a time.
     */
    @Volatile
    private var progressSessionId: Long? = null

    fun beginProgressSession(sessionId: Long?) {
        progressSessionId = sessionId
    }

    /**
     * Closes a stage-report session only when it is still the installed one.
     *
     * A detail load that was superseded by a newer load (browse restart, another detail open) must
     * not tear down the newer load's narration when it finishes.
     */
    fun endProgressSession(sessionId: Long) {
        if (progressSessionId == sessionId) {
            progressSessionId = null
        }
    }

    /**
     * Fetches one browse page. Card metadata (file size, download count) is intentionally not
     * backfilled here: the parsed page already carries everything a card needs to render, so the
     * caller shows it immediately and calls [loadBrowseItemMetadata] afterwards. On the legacy
     * HTML path that backfill is an extra API round trip, and making the whole list wait for it
     * was the slowest non-network part of a browse.
     */
    suspend fun browse(query: WorkshopBrowseQuery): WorkshopBrowseResult = withContext(Dispatchers.IO) {
        val browseStartedAtMs = SystemClock.elapsedRealtime()
        val page = searchWorkshop(query)
        val searchMs = SystemClock.elapsedRealtime() - browseStartedAtMs
        val items = page.items.take(query.pageSize)
        Log.i(
            TAG,
            "perf browse totalMs=${SystemClock.elapsedRealtime() - browseStartedAtMs} searchMs=$searchMs page=${query.page} pageSize=${query.pageSize} rawItems=${page.items.size} items=${items.size} queryLen=${query.searchText.length} sort=${query.sort} time=${query.timeFilter} category=${query.category}",
        )
        WorkshopBrowseResult(
            items = items,
            total = items.size,
            page = page.page,
            pageSize = query.pageSize,
            hasNextPage = page.hasNextPage,
        )
    }

    /**
     * Backfills missing card metadata (file size, subscription count) for items already on
     * screen. Items the parsed page described completely pass through unchanged.
     */
    suspend fun loadBrowseItemMetadata(items: List<WorkshopItemSummary>): List<WorkshopItemSummary> =
        withContext(Dispatchers.IO) { enrichBrowseMetadata(items) }

    suspend fun browseSubscriptions(
        appId: UInt = 646570u,
        page: Int = 1,
        pageSize: Int = 30,
    ): WorkshopBrowseResult = withContext(Dispatchers.IO) {
        val diagnostic = StringBuilder()
        runCatching {
            val account = readSteamAccountSession()
                ?: error("Steam 登录信息不完整，请重新登录后查看已订阅模组。")
            diagnostic.append("account=").append(account.accountName)
                .append(" steamId=").append(account.steamId)
                .append(" appId=").append(appId)
                .append(" page=").append(page)
                .append(" pageSize=").append(pageSize)
            val protocolResult = SteamPublishedFileClient(
                directoryClient = SteamDirectoryClient(directoryHttpClient),
                sessionFactory = { SharedSteamCmSessions.forProcess(context).asCmSession() },
            ).getUserFiles(
                account = account,
                appId = appId,
                page = page.coerceAtLeast(1),
                pageSize = pageSize,
                type = "mysubscriptions",
                language = steamLanguagePreference.protocolLanguage,
            )
            diagnostic.append(" protocolTotal=").append(protocolResult.total)
                .append(" protocolItems=").append(protocolResult.items.size)
            val parsedPage = protocolResult.toBrowseParseResult(page.coerceAtLeast(1), pageSize)
            val items = parsedPage.items.take(pageSize)
            diagnostic.append(" enrichedItems=").append(items.size)
            WorkshopBrowseResult(
                items = items,
                total = items.size,
                page = parsedPage.page,
                pageSize = pageSize,
                hasNextPage = parsedPage.hasNextPage,
            )
        }.onFailure { error ->
            Log.e(TAG, "browseSubscriptions failed. $diagnostic", error)
        }.getOrThrow()
    }

    suspend fun isSubscribedToPublishedFile(
        appId: UInt,
        publishedFileId: ULong,
    ): Boolean = withContext(Dispatchers.IO) {
        val account = readSteamAccountSession(identity)
            ?: throw WorkshopSteamLoginRequiredException()
        val publishedFileClient = SteamPublishedFileClient(
            directoryClient = SteamDirectoryClient(directoryHttpClient),
            sessionFactory = { SharedSteamCmSessions.forProcess(context).asCmSession() },
        )
        runCatching {
            publishedFileClient.areFilesInSubscriptionList(
                account = account,
                appId = appId,
                publishedFileIds = listOf(publishedFileId),
            )[publishedFileId] == true
        }.onSuccess { subscribed ->
            Log.i(
                TAG,
                "isSubscribedToPublishedFile directResult=$subscribed appId=$appId publishedFileId=$publishedFileId",
            )
            if (subscribed) return@withContext true
        }.onFailure { error ->
            Log.w(
                TAG,
                "isSubscribedToPublishedFile directCheckFailed appId=$appId publishedFileId=$publishedFileId",
                error,
            )
        }
        val pageSize = 100
        var page = 1
        var checkedCount = 0
        var expectedTotal: Int? = null
        while (page <= MAX_SUBSCRIPTION_STATUS_CHECK_PAGES) {
            val result = publishedFileClient.getUserFiles(
                account = account,
                appId = appId,
                page = page,
                pageSize = pageSize,
                type = "mysubscriptions",
                language = steamLanguagePreference.protocolLanguage,
                idsOnly = true,
            )
            if (result.items.any { item -> item.publishedFileId == publishedFileId }) {
                Log.i(TAG, "isSubscribedToPublishedFile result=true appId=$appId publishedFileId=$publishedFileId page=$page")
                return@withContext true
            }
            if (expectedTotal == null) {
                expectedTotal = result.total
            }
            checkedCount += result.items.size
            val total = expectedTotal
            if (result.items.isEmpty() || checkedCount >= total || result.items.size < pageSize) {
                Log.i(
                    TAG,
                    "isSubscribedToPublishedFile result=false appId=$appId publishedFileId=$publishedFileId checked=$checkedCount total=$total pages=$page",
                )
                return@withContext false
            }
            page += 1
        }
        Log.w(
            TAG,
            "isSubscribedToPublishedFile result=false appId=$appId publishedFileId=$publishedFileId reason=maxPages checked=$checkedCount total=${expectedTotal ?: 0}",
        )
        false
    }

    suspend fun subscribeToPublishedFile(
        appId: UInt,
        publishedFileId: ULong,
    ): WorkshopSubscriptionResult = withContext(Dispatchers.IO) {
        var failureLogged = false
        fun logFailure(message: String, error: Throwable? = null) {
            failureLogged = true
            if (error != null) {
                Log.e(TAG, message, error)
            } else {
                Log.e(TAG, message)
            }
        }
        try {
            subscribeToPublishedFileInternal(appId, publishedFileId, ::logFailure)
        } catch (error: Throwable) {
            if (!failureLogged) {
                Log.e(TAG, "subscribeToPublishedFile failed appId=$appId publishedFileId=$publishedFileId", error)
            }
            throw error
        }
    }

    private suspend fun subscribeToPublishedFileInternal(
        appId: UInt,
        publishedFileId: ULong,
        logFailure: (String, Throwable?) -> Unit,
    ): WorkshopSubscriptionResult {
        Log.i(TAG, "subscribeToPublishedFile start appId=$appId publishedFileId=$publishedFileId")
        val account = readSteamAccountSession(identity)
            ?: run {
                logFailure(
                    "subscribeToPublishedFile failed appId=$appId publishedFileId=$publishedFileId reason=missingSteamAuth",
                    null,
                )
                throw WorkshopSteamLoginRequiredException()
            }
        runCatching {
            val publishedFileClient = SteamPublishedFileClient(
                directoryClient = SteamDirectoryClient(directoryHttpClient),
                sessionFactory = { SharedSteamCmSessions.forProcess(context).asCmSession() },
            )
            publishedFileClient.subscribe(
                account = account,
                appId = appId,
                publishedFileId = publishedFileId,
            )
        }.onFailure { error ->
            logFailure(
                "subscribeToPublishedFile failed appId=$appId publishedFileId=$publishedFileId reason=steamProtocol",
                error,
            )
        }.getOrThrow()
        Log.i(TAG, "subscribeToPublishedFile protocolAccepted appId=$appId publishedFileId=$publishedFileId transport=steamProtocol")
        if (!verifySubscribedAfterSubscribe(appId, publishedFileId)) {
            logFailure(
                "subscribeToPublishedFile failed appId=$appId publishedFileId=$publishedFileId reason=verificationFailed",
                null,
            )
            throw WorkshopSubscriptionVerificationException()
        }
        Log.i(TAG, "subscribeToPublishedFile success appId=$appId publishedFileId=$publishedFileId transport=steamProtocol verified=true")
        return WorkshopSubscriptionResult(
            publishedFileId = publishedFileId,
            appId = appId,
            subscribedAtMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun verifySubscribedAfterSubscribe(
        appId: UInt,
        publishedFileId: ULong,
    ): Boolean {
        repeat(SUBSCRIPTION_VERIFY_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                delay(SUBSCRIPTION_VERIFY_DELAY_MS)
            }
            val attemptNumber = attempt + 1
            val subscribed = runCatching {
                isSubscribedToPublishedFile(appId, publishedFileId)
            }.onFailure { error ->
                Log.w(
                    TAG,
                    "subscribeToPublishedFile verificationCheckFailed appId=$appId publishedFileId=$publishedFileId attempt=$attemptNumber",
                    error,
                )
            }.getOrDefault(false)
            Log.i(
                TAG,
                "subscribeToPublishedFile verificationResult=$subscribed appId=$appId publishedFileId=$publishedFileId attempt=$attemptNumber",
            )
            if (subscribed) return true
        }
        return false
    }

    suspend fun unsubscribeFromPublishedFile(
        appId: UInt,
        publishedFileId: ULong,
    ): WorkshopUnsubscriptionResult = withContext(Dispatchers.IO) {
        var failureLogged = false
        fun logFailure(message: String, error: Throwable? = null) {
            failureLogged = true
            if (error != null) {
                Log.e(TAG, message, error)
            } else {
                Log.e(TAG, message)
            }
        }
        try {
            unsubscribeFromPublishedFileInternal(appId, publishedFileId, ::logFailure)
        } catch (error: Throwable) {
            if (!failureLogged) {
                Log.e(TAG, "unsubscribeFromPublishedFile failed appId=$appId publishedFileId=$publishedFileId", error)
            }
            throw error
        }
    }

    private suspend fun unsubscribeFromPublishedFileInternal(
        appId: UInt,
        publishedFileId: ULong,
        logFailure: (String, Throwable?) -> Unit,
    ): WorkshopUnsubscriptionResult {
        Log.i(TAG, "unsubscribeFromPublishedFile start appId=$appId publishedFileId=$publishedFileId")
        val account = readSteamAccountSession(identity)
            ?: run {
                logFailure(
                    "unsubscribeFromPublishedFile failed appId=$appId publishedFileId=$publishedFileId reason=missingSteamAuth",
                    null,
                )
                throw WorkshopSteamLoginRequiredException()
            }
        runCatching {
            val publishedFileClient = SteamPublishedFileClient(
                directoryClient = SteamDirectoryClient(directoryHttpClient),
                sessionFactory = { SharedSteamCmSessions.forProcess(context).asCmSession() },
            )
            publishedFileClient.unsubscribe(
                account = account,
                appId = appId,
                publishedFileId = publishedFileId,
            )
        }.onFailure { error ->
            logFailure(
                "unsubscribeFromPublishedFile failed appId=$appId publishedFileId=$publishedFileId reason=steamProtocol",
                error,
            )
        }.getOrThrow()
        Log.i(TAG, "unsubscribeFromPublishedFile protocolAccepted appId=$appId publishedFileId=$publishedFileId transport=steamProtocol")
        if (!verifyUnsubscribedAfterUnsubscribe(appId, publishedFileId)) {
            logFailure(
                "unsubscribeFromPublishedFile failed appId=$appId publishedFileId=$publishedFileId reason=verificationFailed",
                null,
            )
            throw WorkshopUnsubscriptionVerificationException()
        }
        Log.i(TAG, "unsubscribeFromPublishedFile success appId=$appId publishedFileId=$publishedFileId transport=steamProtocol verified=true")
        return WorkshopUnsubscriptionResult(
            publishedFileId = publishedFileId,
            appId = appId,
            unsubscribedAtMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun verifyUnsubscribedAfterUnsubscribe(
        appId: UInt,
        publishedFileId: ULong,
    ): Boolean {
        repeat(SUBSCRIPTION_VERIFY_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                delay(SUBSCRIPTION_VERIFY_DELAY_MS)
            }
            val attemptNumber = attempt + 1
            val subscribed = runCatching {
                isSubscribedToPublishedFile(appId, publishedFileId)
            }.onFailure { error ->
                Log.w(
                    TAG,
                    "unsubscribeFromPublishedFile verificationCheckFailed appId=$appId publishedFileId=$publishedFileId attempt=$attemptNumber",
                    error,
                )
            }.getOrDefault(true)
            Log.i(
                TAG,
                "unsubscribeFromPublishedFile verificationResult subscribed=$subscribed appId=$appId publishedFileId=$publishedFileId attempt=$attemptNumber",
            )
            if (!subscribed) return true
        }
        return false
    }

    suspend fun getDetails(
        appId: UInt,
        publishedFileId: ULong,
        fallbackSummary: WorkshopItemSummary? = null,
        includeCommunityData: Boolean = true,
        includeDependencyData: Boolean = true,
    ): WorkshopItemDetails = withContext(Dispatchers.IO) {
        val getDetailsStartedAtMs = SystemClock.elapsedRealtime()
        val languagePreference = steamLanguagePreference
        coroutineScope {
            // These requests use independent Steam endpoints. Fetch them together so a
            // slow API or community response does not add its full latency to the other.
            val apiDetail = async(Dispatchers.IO) {
                val apiStartedAtMs = SystemClock.elapsedRealtime()
                val requestBody = FormBody.Builder()
                    .add("itemcount", "1")
                    .add("publishedfileids[0]", publishedFileId.toString())
                    .add("appid", appId.toString())
                    .build()
                val request = Request.Builder()
                    .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/".toHttpUrl())
                    .post(requestBody)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Steam workshop details failed: ${response.code}")
                    val payload = response.body?.string().orEmpty()
                    val envelope = json.decodeFromString<PublishedFileDetailsEnvelope>(payload)
                    val detail = envelope.response.publishedFileDetails.firstOrNull()
                        ?: error("No workshop detail returned")
                    Log.i(
                        TAG,
                        "perf getDetails apiDetail publishedFileId=$publishedFileId code=${response.code} payloadBytes=${payload.length} children=${detail.children.size} elapsedMs=${SystemClock.elapsedRealtime() - apiStartedAtMs}",
                    )
                    detail to payload
                }
            }
            val localizedDetail = if (includeCommunityData) {
                async(Dispatchers.IO) {
                    val communityStartedAtMs = SystemClock.elapsedRealtime()
                    runCatching {
                        loadLocalizedDetailPageWithCache(
                            publishedFileId = publishedFileId,
                            languageRequestValue = languagePreference.requestValue,
                            shouldRetryWithoutUsefulContent = {
                                // A retry costs a full second page download. Only pay it when the
                                // API payload cannot cover the description gap; missing comment
                                // context degrades gracefully and stays retriable from the UI.
                                runCatching { apiDetail.await().first.description.isNullOrBlank() }
                                    .getOrDefault(true)
                            },
                        )
                    }.onFailure { error ->
                        Log.w(
                            TAG,
                            "perf getDetails communityDetail failed publishedFileId=$publishedFileId elapsedMs=${SystemClock.elapsedRealtime() - communityStartedAtMs} error=${error.message ?: error.javaClass.simpleName}",
                        )
                        logWarning(
                            "getDetails community detail failed appId=$appId publishedFileId=$publishedFileId",
                            error,
                        )
                    }.onSuccess { detail ->
                        Log.i(
                            TAG,
                            "perf getDetails communityDetail ok publishedFileId=$publishedFileId descChars=${detail.description.length} previews=${detail.previewMedia.size} required=${detail.requiredItemIds.size} comments=${detail.commentCount} elapsedMs=${SystemClock.elapsedRealtime() - communityStartedAtMs}",
                        )
                    }.getOrNull()
                }
            } else {
                null
            }
            val (detail, payload) = apiDetail.await()
            // Dependencies are almost fully described by the API payload (children), so fetch them
            // while the community page is still downloading instead of after it. The community page
            // only contributes a few extra required-item ids; those go out as a small follow-up
            // batch when it actually adds anything.
            val primaryDependencyIds = if (includeDependencyData) {
                detail.children.mapNotNull { child -> child.publishedFileId.toULongOrNull() }.distinct()
            } else {
                emptyList()
            }
            val depsStartedAtMs = SystemClock.elapsedRealtime()
            val primaryDependencyDetailsDeferred = if (primaryDependencyIds.isNotEmpty()) {
                async(Dispatchers.IO) {
                    loadDependencyDetails(appId, primaryDependencyIds).associateBy { childDetail ->
                        childDetail.publishedFileId.toULongOrNull()
                    }
                }
            } else {
                null
            }
            val communityDetail = localizedDetail?.await()
            val awaitDoneAtMs = SystemClock.elapsedRealtime()
            // The community page can contribute dependency ids the API payload lacks. Fire that
            // small follow-up batch now so it overlaps with the primary dependency batch instead
            // of serializing behind its await.
            val extraDependencyDetailsDeferred = if (includeDependencyData) {
                communityDetail?.requiredItemIds.orEmpty()
                    .filterNot { dependencyId -> dependencyId in primaryDependencyIds }
                    .distinct()
                    .takeIf { extraIds -> extraIds.isNotEmpty() }
                    ?.let { extraIds ->
                        async(Dispatchers.IO) { loadDependencyDetails(appId, extraIds) }
                    }
            } else {
                null
            }
            val cardSummary = fallbackSummary?.takeIf { summary ->
                summary.appId == appId && summary.publishedFileId == publishedFileId
            }
            val apiUpdatedAtMillis = detail.timeUpdated?.let { it * 1000L }
            val fallbackMatchesApiVersion = cardSummary?.let { summary ->
                apiUpdatedAtMillis == null ||
                    summary.updatedAtMillis <= 0L ||
                    summary.updatedAtMillis == apiUpdatedAtMillis
            } == true
            val localizedDescription = communityDetail?.description.orEmpty()
            val fallbackDescription = cardSummary?.description.orEmpty()
                .takeIf { fallbackMatchesApiVersion }
                .orEmpty()
            val apiDescription = detail.description.orEmpty()
            val description = localizedDescription
                .ifBlank { apiDescription }
                .ifBlank { fallbackDescription }
            val fullDescriptionUnavailable = localizedDescription.isBlank() &&
                apiDescription.isBlank() &&
                fallbackDescription.isNotBlank() &&
                description == fallbackDescription
            val summary = WorkshopItemSummary(
                publishedFileId = publishedFileId,
                appId = appId,
                title = detail.title.ifBlank { cardSummary?.title.orEmpty().ifBlank { "Workshop $publishedFileId" } },
                previewUrl = detail.previewUrl.orEmpty().ifBlank { cardSummary?.previewUrl.orEmpty() },
                description = description,
                authorName = detail.creatorName.orEmpty()
                    .ifBlank { communityDetail?.authorName.orEmpty() }
                    .ifBlank { cardSummary?.authorName.orEmpty() },
                fileSizeBytes = detail.fileSize ?: cardSummary?.fileSizeBytes ?: 0L,
                updatedAtMillis = apiUpdatedAtMillis ?: cardSummary?.updatedAtMillis ?: 0L,
                downloadCount = detail.subscriptions ?: cardSummary?.downloadCount ?: 0L,
                rating = normalizedWorkshopRating(detail.voteData?.score) ?: cardSummary?.rating,
            )
            val dependencyIds = if (includeDependencyData) {
                (
                    detail.children.mapNotNull { child -> child.publishedFileId.toULongOrNull() } +
                        communityDetail?.requiredItemIds.orEmpty()
                    ).distinct()
            } else {
                emptyList()
            }
            val depsMs = SystemClock.elapsedRealtime() - depsStartedAtMs
            val dependencyDetailsById = buildMap<ULong?, PublishedFileDetailsDto> {
                primaryDependencyDetailsDeferred?.let { deferred -> putAll(deferred.await()) }
                extraDependencyDetailsDeferred?.let { deferred ->
                    deferred.await().forEach { childDetail ->
                        childDetail.publishedFileId.toULongOrNull()?.let { dependencyId ->
                            putIfAbsent(dependencyId, childDetail)
                        }
                    }
                }
            }
            val commentThreadContext = communityDetail?.commentThreadContext
                ?: detail.toCommentThreadContext(publishedFileId)
            val commentCount = communityDetail?.commentCount
            val previewMedia = buildWorkshopPreviewMedia(
                summary.previewUrl,
                communityDetail?.previewMedia.orEmpty(),
            )
            val result = WorkshopItemDetails(
                summary = summary,
                fileUrl = detail.fileUrl,
                hcontentFile = detail.hcontentFile?.takeIf { it > 0L }?.toULong(),
                depotId = detail.consumerAppId?.takeIf { it > 0 }?.toUInt(),
                jsonMetadata = payload,
                previewMedia = previewMedia,
                previewImageUrls = buildWorkshopPreviewImageUrls(previewMedia),
                fullDescriptionUnavailable = fullDescriptionUnavailable,
                changeNotesUrl = buildWorkshopChangeNotesUrl(publishedFileId, languagePreference.requestValue),
                dependencies = dependencyIds.map { dependencyId ->
                    dependencyDetailsById[dependencyId]?.toSummary(appId, dependencyId)
                        ?: WorkshopItemSummary(
                            publishedFileId = dependencyId,
                            appId = appId,
                            title = knownWorkshopDependencyTitle(dependencyId) ?: "Workshop ID $dependencyId",
                            previewUrl = "",
                            description = "",
                        )
                },
                commentsUrl = buildWorkshopCommentsUrl(publishedFileId, languagePreference.requestValue, page = 1),
                commentThreadContext = commentThreadContext,
                commentCount = commentCount,
                commentTotalPages = commentCount?.let(::resolveCommentTotalPages),
                hasNextCommentPage = commentCount?.let { count -> count > COMMENT_PAGE_SIZE } == true,
            )
            Log.i(
                TAG,
                "perf getDetails total publishedFileId=$publishedFileId parallelAwaitMs=${awaitDoneAtMs - getDetailsStartedAtMs} depsMs=$depsMs deps=${dependencyIds.size} community=${communityDetail != null} totalMs=${SystemClock.elapsedRealtime() - getDetailsStartedAtMs}",
            )
            result
        }
    }

    /** Loads the lightweight data needed by list cards without community-page requests. */
    suspend fun getSummaries(
        appId: UInt,
        publishedFileIds: List<ULong>,
    ): List<WorkshopItemSummary> = withContext(Dispatchers.IO) {
        val requestedIds = normalizePublishedFileIds(publishedFileIds)
        if (requestedIds.isEmpty()) {
            return@withContext emptyList()
        }

        requestedIds
            .chunked(PUBLISHED_FILE_DETAILS_BATCH_SIZE)
            .flatMap { ids -> loadSummaries(appId, ids) }
    }

    /** Emits each list-card summary batch as soon as its API request finishes. */
    fun getSummaryBatches(
        appId: UInt,
        publishedFileIds: List<ULong>,
    ): Flow<List<WorkshopItemSummary>> = flow {
        normalizePublishedFileIds(publishedFileIds)
            .chunked(PUBLISHED_FILE_SUMMARY_PROGRESS_BATCH_SIZE)
            .forEach { ids ->
                emit(withContext(Dispatchers.IO) { loadSummaries(appId, ids) })
            }
    }

    suspend fun getChangeNotes(publishedFileId: ULong): WorkshopChangeNotes = withContext(Dispatchers.IO) {
        val languagePreference = steamLanguagePreference
        val blocks = loadChangeNotesMarkdownBlocks(
            publishedFileId = publishedFileId,
            languageRequestValue = languagePreference.requestValue,
        )
        WorkshopChangeNotes(
            publishedFileId = publishedFileId,
            markdown = blocks.joinToString("\n\n"),
            latestMarkdown = blocks.firstOrNull().orEmpty(),
            url = buildWorkshopChangeNotesUrl(publishedFileId, languagePreference.requestValue),
        )
    }

    suspend fun getCommentsPage(
        details: WorkshopItemDetails,
        page: Int,
    ): WorkshopCommentPage = withContext(Dispatchers.IO) {
        loadWorkshopCommentPage(
            details = details,
            page = page,
            languageRequestValue = steamLanguagePreference.requestValue,
        )
    }

    private fun loadDependencyDetails(appId: UInt, publishedFileIds: List<ULong>): List<PublishedFileDetailsDto> {
        if (publishedFileIds.isEmpty()) return emptyList()
        return runCatching {
            loadPublishedFileDetails(appId, publishedFileIds)
        }.getOrDefault(emptyList())
    }

    private fun loadPublishedFileDetails(
        appId: UInt,
        publishedFileIds: List<ULong>,
    ): List<PublishedFileDetailsDto> {
        val requestBody = FormBody.Builder().apply {
            add("itemcount", publishedFileIds.size.toString())
            publishedFileIds.forEachIndexed { index, publishedFileId ->
                add("publishedfileids[$index]", publishedFileId.toString())
            }
            add("appid", appId.toString())
            add("language", steamLanguagePreference.requestValue)
        }.build()
        val request = Request.Builder()
            .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/".toHttpUrl())
            .post(requestBody)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Steam workshop details failed: ${response.code}")
            val payload = response.body?.string().orEmpty()
            json.decodeFromString<PublishedFileDetailsEnvelope>(payload).response.publishedFileDetails
        }
    }

    private fun loadSummaries(appId: UInt, publishedFileIds: List<ULong>): List<WorkshopItemSummary> {
        val detailsById = loadPublishedFileDetails(appId, publishedFileIds)
            .mapNotNull { detail ->
                detail.publishedFileId.toULongOrNull()?.let { publishedFileId -> publishedFileId to detail }
            }
            .toMap()
        return publishedFileIds.mapNotNull { publishedFileId ->
            detailsById[publishedFileId]?.toSummary(appId, publishedFileId)
        }
    }

    private fun normalizePublishedFileIds(publishedFileIds: List<ULong>): List<ULong> = publishedFileIds
        .filter { it > 0uL }
        .distinct()

    private fun loadLocalizedDetailPage(
        publishedFileId: ULong,
        languageRequestValue: String,
    ): LocalizedWorkshopDetail {
        val pageStartedAtMs = SystemClock.elapsedRealtime()
        val request = Request.Builder()
            .url(
                "https://steamcommunity.com/sharedfiles/filedetails/".toHttpUrl().newBuilder()
                    .addQueryParameter("id", publishedFileId.toString())
                    .addQueryParameter("l", languageRequestValue)
                    .build(),
            )
            .header("Accept-Language", languagePreferenceFor(languageRequestValue).acceptLanguageValue)
            .header("User-Agent", USER_AGENT)
            .build()

        return workshopClient.newCall(request).execute().use { response ->
            val httpMs = SystemClock.elapsedRealtime() - pageStartedAtMs
            if (response.code == HTTP_TOO_MANY_REQUESTS) {
                throw SteamCommunityRateLimitException(response.code)
            }
            if (!response.isSuccessful) error("Steam workshop community detail failed: ${response.code}")
            val payload = response.body?.string().orEmpty()
            if (looksLikeCaptivePortal(payload)) {
                error("Steam workshop community detail returned a captive portal page")
            }
            val parseStartedAtMs = SystemClock.elapsedRealtime()
            val parsed = LocalizedWorkshopDetail(
                description = extractWorkshopDescription(payload),
                authorName = extractWorkshopAuthorName(payload),
                previewMedia = extractPreviewMediaItems(payload),
                requiredItemIds = extractRequiredItemIds(payload),
                commentThreadContext = extractCommentThreadContext(payload),
                commentCount = extractCommentCount(payload),
            )
            Log.i(
                TAG,
                "perf loadLocalizedDetailPage publishedFileId=$publishedFileId lang=$languageRequestValue code=${response.code} httpMs=$httpMs parseMs=${SystemClock.elapsedRealtime() - parseStartedAtMs} htmlBytes=${payload.length} totalMs=${SystemClock.elapsedRealtime() - pageStartedAtMs}",
            )
            parsed
        }
    }

    private suspend fun loadLocalizedDetailPageWithRetry(
        publishedFileId: ULong,
        languageRequestValue: String,
        shouldRetryWithoutUsefulContent: suspend () -> Boolean,
    ): LocalizedWorkshopDetail {
        var lastError: Throwable? = null
        var lastDetail: LocalizedWorkshopDetail? = null
        repeat(COMMUNITY_DETAIL_ATTEMPTS) { attempt ->
            runCatching {
                loadLocalizedDetailPage(
                    publishedFileId = publishedFileId,
                    languageRequestValue = languageRequestValue,
                )
            }.onSuccess { detail ->
                if (
                    detail.hasUsefulContent() ||
                    attempt == COMMUNITY_DETAIL_ATTEMPTS - 1 ||
                    !shouldRetryWithoutUsefulContent()
                ) {
                    return detail
                }
                lastDetail = detail
            }.onFailure { error ->
                if (error is CancellationException) throw error
                lastError = error
                if (error is SteamCommunityRateLimitException) {
                    throw error
                }
                if (attempt == COMMUNITY_DETAIL_ATTEMPTS - 1) {
                    throw error
                }
            }
            delay(COMMUNITY_DETAIL_RETRY_DELAY_MS * (attempt + 1L))
        }
        return lastDetail ?: throw (lastError ?: IllegalStateException("Steam workshop community detail did not return content"))
    }

    private suspend fun loadLocalizedDetailPageWithCache(
        publishedFileId: ULong,
        languageRequestValue: String,
        shouldRetryWithoutUsefulContent: suspend () -> Boolean = { true },
    ): LocalizedWorkshopDetail {
        val cacheStartedAtMs = SystemClock.elapsedRealtime()
        val key = CommunityDetailCacheKey(publishedFileId, languageRequestValue)
        var cachedDetail: LocalizedWorkshopDetail? = null
        var shouldFetch = false
        val deferred = synchronized(communityDetailCacheLock) {
            val nowMillis = System.currentTimeMillis()
            communityDetailCache[key]?.let { cached ->
                if (nowMillis - cached.loadedAtMillis <= COMMUNITY_DETAIL_CACHE_TTL_MS) {
                    cachedDetail = cached.detail
                } else {
                    communityDetailCache.remove(key)
                }
            }
            if (cachedDetail != null) {
                null
            } else {
                communityDetailInFlight[key] ?: CompletableDeferred<LocalizedWorkshopDetail>().also { created ->
                    communityDetailInFlight[key] = created
                    shouldFetch = true
                }
            }
        }
        cachedDetail?.let { hit ->
            Log.i(
                TAG,
                "perf communityDetailCache hit publishedFileId=$publishedFileId lang=$languageRequestValue elapsedMs=${SystemClock.elapsedRealtime() - cacheStartedAtMs}",
            )
            return hit
        }
        val pending = requireNotNull(deferred)
        if (!shouldFetch) {
            val joined = pending.await()
            Log.i(
                TAG,
                "perf communityDetailCache joinInFlight publishedFileId=$publishedFileId lang=$languageRequestValue elapsedMs=${SystemClock.elapsedRealtime() - cacheStartedAtMs}",
            )
            return joined
        }

        try {
            val detail = loadLocalizedDetailPageWithRetry(
                publishedFileId = publishedFileId,
                languageRequestValue = languageRequestValue,
                shouldRetryWithoutUsefulContent = shouldRetryWithoutUsefulContent,
            )
            if (detail.hasUsefulContent()) {
                synchronized(communityDetailCacheLock) {
                    communityDetailCache[key] = CachedCommunityDetail(
                        detail = detail,
                        loadedAtMillis = System.currentTimeMillis(),
                    )
                    trimCommunityDetailCacheLocked()
                }
            }
            pending.complete(detail)
            Log.i(
                TAG,
                "perf communityDetailCache fetch publishedFileId=$publishedFileId lang=$languageRequestValue useful=${detail.hasUsefulContent()} elapsedMs=${SystemClock.elapsedRealtime() - cacheStartedAtMs}",
            )
            return detail
        } catch (error: Throwable) {
            pending.completeExceptionally(error)
            throw error
        } finally {
            synchronized(communityDetailCacheLock) {
                if (communityDetailInFlight[key] === pending) {
                    communityDetailInFlight.remove(key)
                }
            }
        }
    }

    private fun trimCommunityDetailCacheLocked() {
        while (communityDetailCache.size > COMMUNITY_DETAIL_CACHE_MAX_ENTRIES) {
            val eldestKey = communityDetailCache.keys.firstOrNull() ?: return
            communityDetailCache.remove(eldestKey)
        }
    }

    private fun loadChangeNotesMarkdownBlocks(
        publishedFileId: ULong,
        languageRequestValue: String,
    ): List<String> {
        val request = Request.Builder()
            .url(buildWorkshopChangeNotesUrl(publishedFileId, languageRequestValue))
            .header("Accept-Language", languagePreferenceFor(languageRequestValue).acceptLanguageValue)
            .header("User-Agent", USER_AGENT)
            .build()

        return workshopClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Steam workshop changelog failed: ${response.code}")
            extractChangeNotesMarkdownBlocks(response.body.string())
        }
    }

    private fun loadWorkshopCommentPage(
        details: WorkshopItemDetails,
        page: Int,
        languageRequestValue: String,
    ): WorkshopCommentPage {
        val commentThreadContext = details.commentThreadContext ?: error("Workshop comment thread context was missing")
        val safePage = page.coerceAtLeast(1)
        val start = (safePage - 1) * COMMENT_PAGE_SIZE
        val formBody = FormBody.Builder()
            .add("start", start.toString())
            .add("count", COMMENT_PAGE_SIZE.toString())
            .apply {
                commentThreadContext.sessionId?.takeIf(String::isNotBlank)?.let { add("sessionid", it) }
                commentThreadContext.extendedData?.takeIf(String::isNotBlank)?.let { add("extended_data", it) }
                commentThreadContext.feature2?.takeIf { it.isNotBlank() && it != "-1" }?.let { add("feature2", it) }
            }
            .build()
        val request = Request.Builder()
            .url(
                "https://steamcommunity.com/".toHttpUrl().newBuilder()
                    .addPathSegments(
                        "comment/PublishedFile_Public/render/${commentThreadContext.ownerId}/${commentThreadContext.featureId}/",
                    )
                    .addQueryParameter("l", languageRequestValue)
                    .build(),
            )
            .post(formBody)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept-Language", languagePreferenceFor(languageRequestValue).acceptLanguageValue)
            .header("User-Agent", USER_AGENT)
            .build()

        return workshopClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Workshop comments request failed: ${response.code}")
            val payload = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            val commentCount = payload.longValue("total_count")
            val pageSize = payload.intValue("pagesize") ?: COMMENT_PAGE_SIZE
            val responseStart = payload.intValue("start")
            val comments = extractComments(payload.stringValue("comments_html"))
            val resolvedPage = if (responseStart != null && pageSize > 0) {
                (responseStart / pageSize) + 1
            } else {
                safePage
            }
            val totalPages = resolveCommentTotalPages(commentCount)
            WorkshopCommentPage(
                commentsUrl = buildWorkshopCommentsUrl(
                    details.summary.publishedFileId,
                    languageRequestValue,
                    resolvedPage,
                ),
                commentCount = commentCount,
                page = resolvedPage,
                totalPages = totalPages,
                hasPreviousPage = resolvedPage > 1,
                hasNextPage = when {
                    totalPages != null -> resolvedPage < totalPages
                    else -> comments.size >= pageSize
                },
                comments = comments,
            )
        }
    }

    suspend fun download(request: WorkshopDownloadRequest): Flow<WorkshopDownloadEvent> = flow {
        emit(WorkshopDownloadEvent.StateChanged(WorkshopDownloadState.Resolving))
        val details = request.details
        val outputFile = File(request.outputDir, sanitizeFileName(details.summary.title) + ".jar")
        val tempFile = File(request.outputDir, outputFile.name + ".tmp")
        request.outputDir.mkdirs()
        when {
            !details.fileUrl.isNullOrBlank() -> {
                emit(WorkshopDownloadEvent.StateChanged(WorkshopDownloadState.Downloading))
                try {
                    val req = Request.Builder().url(details.fileUrl).build()
                    client.newCall(req).execute().use { response ->
                        if (!response.isSuccessful) error("Workshop download failed: ${response.code}")
                        val body = response.body ?: error("Workshop download body empty")
                        FileOutputStream(tempFile, false).use { output ->
                            body.byteStream().use { input ->
                                val totalBytes = body.contentLength().takeIf { it > 0L }
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var writtenBytes = 0L
                                while (true) {
                                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Workshop download interrupted")
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    output.write(buffer, 0, read)
                                    writtenBytes += read
                                    emit(
                                        WorkshopDownloadEvent.Progress(
                                            WorkshopDownloadProgress(
                                                writtenBytes = writtenBytes,
                                                totalBytes = totalBytes,
                                                completedFiles = 0,
                                                totalFiles = 1,
                                            )
                                        )
                                    )
                                }
                            }
                        }
                    }
                    if (outputFile.exists() && !outputFile.delete()) throw IOException("Failed to replace existing workshop file")
                    if (!tempFile.renameTo(outputFile)) throw IOException("Failed to finalize workshop download")
                } catch (throwable: Throwable) {
                    tempFile.delete()
                    throw throwable
                }
                emit(WorkshopDownloadEvent.Completed(listOf(WorkshopDownloadedArtifact(outputFile.name, outputFile.length(), outputFile.lastModified()))))
                emit(WorkshopDownloadEvent.StateChanged(WorkshopDownloadState.Success))
            }
            details.hcontentFile != null -> {
                emit(WorkshopDownloadEvent.StateChanged(WorkshopDownloadState.Downloading))
                contentDownloader().download(details, request.outputDir).collect { event ->
                    if (event != WorkshopDownloadEvent.Ignored) {
                        emit(event)
                    }
                }
            }
            else -> error("Workshop item has no downloadable source")
        }
    }

    private fun contentDownloader(): WorkshopContentDownloader =
        contentDownloaderFactory?.invoke(this)
            ?: SteamPipeWorkshopContentDownloader(::createEngine)

    private fun createEngine(): WorkshopDownloadEngine {
        val account = readSteamAccountSession(identity)
        return WorkshopDownloadEngine.createDefault(
            client = workshopClient,
            sessionFactory = { SharedSteamCmSessions.forProcess(context).asCmSession() },
            sessionConnector = buildSessionConnector(account),
            maxConcurrentChunks = LauncherPreferences.readWorkshopDownloadThreads(context),
            allowPublicCdnFallbackOnSessionFailure = true,
            publishedFileLanguage = steamLanguagePreference.requestValue,
        )
    }

    private fun readSteamAccountSession(
        identity: WorkshopSteamClientIdentity = WorkshopSteamClientIdentity(context),
    ): SteamAccountSession? {
        if (SteamAuthenticationCircuitBreaker.isOpen()) return null
        return SteamCloudAuthStore.readAuthMaterial(context)?.let { auth ->
            val snapshot = SteamCloudAuthStore.readSnapshot(context)
            val steamId = snapshot.steamId64.toLongOrNull() ?: 0L
            if (steamId > 0L) {
                SteamAccountSession(
                    accountName = auth.accountName,
                    steamId = steamId,
                    refreshToken = auth.refreshToken,
                    machineName = identity.machineName,
                )
            } else {
                null
            }
        }
    }

    private fun buildSessionConnector(
        account: SteamAccountSession?,
    ): suspend (SteamCmSession, List<CmServer>) -> SessionContext =
        if (account == null) {
            { session, servers -> session.connectAnonymous(servers) }
        } else {
            { session, servers -> session.connectWithRefreshToken(servers, account) }
        }

    fun downloadPreviewImage(appId: UInt, publishedFileId: ULong, previewUrl: String): String {
        return WorkshopPreviewImageStore(context, client).download(appId, publishedFileId, previewUrl)
    }

    fun createInstalledRecord(details: WorkshopItemDetails, artifact: WorkshopDownloadedArtifact): WorkshopInstalledModRecord {
        return WorkshopInstalledModRecord(
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            title = details.summary.title,
            description = details.summary.description,
            previewUrl = details.summary.previewUrl,
            versionText = details.summary.updatedAtMillis.toString(),
            updatedAtMillis = details.summary.updatedAtMillis,
            installedAtMillis = System.currentTimeMillis(),
            localJarPath = artifact.relativePath,
            cardState = WorkshopModCardState.ImportedUnpatched,
            statusText = "等待修补",
            dependencies = details.dependencies,
        )
    }

    fun createNonStandardDownloadRecord(details: WorkshopItemDetails, outputDir: File): WorkshopInstalledModRecord {
        val path = outputDir.absolutePath
        return WorkshopInstalledModRecord(
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            title = details.summary.title,
            description = details.summary.description,
            previewUrl = details.summary.previewUrl,
            versionText = details.summary.updatedAtMillis.toString(),
            updatedAtMillis = details.summary.updatedAtMillis,
            installedAtMillis = System.currentTimeMillis(),
            localJarPath = path,
            contentKind = WorkshopInstalledContentKind.NonStandard,
            cardState = WorkshopModCardState.NonStandardDownloaded,
            statusText = "该模组不是标准 jar 格式，请手动处理后导入，已存储到$path",
            dependencies = details.dependencies,
        )
    }

    fun createTexturePackRecord(details: WorkshopItemDetails, texturePackDir: File): WorkshopInstalledModRecord {
        val path = texturePackDir.absolutePath
        return WorkshopInstalledModRecord(
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            title = details.summary.title,
            description = details.summary.description,
            previewUrl = details.summary.previewUrl,
            versionText = details.summary.updatedAtMillis.toString(),
            updatedAtMillis = details.summary.updatedAtMillis,
            installedAtMillis = System.currentTimeMillis(),
            localJarPath = path,
            contentKind = WorkshopInstalledContentKind.TexturePack,
            texturePackPath = path,
            cardState = WorkshopModCardState.TexturePackInstalled,
            statusText = "已作为 Texture Replacer 资源包安装并启用",
            dependencies = details.dependencies,
        )
    }

    private suspend fun searchWorkshop(query: WorkshopBrowseQuery): WorkshopBrowseParseResult {
        val searchStartedAtMs = SystemClock.elapsedRealtime()
        val primeStartedAtMs = SystemClock.elapsedRealtime()
        primeSteamWebSessionIfNeeded()
        val primeMs = SystemClock.elapsedRealtime() - primeStartedAtMs
        val searchUrl = "https://steamcommunity.com/workshop/browse/".toHttpUrl().newBuilder()
            .addQueryParameter("appid", query.appId.toString())
            .addQueryParameter("searchtext", query.searchText)
            .addQueryParameter("childpublishedfileid", "0")
            .addQueryParameter("l", steamLanguagePreference.requestValue)
            .addQueryParameter("browsesort", query.sort.browseSortValue)
            .addQueryParameter("section", "readytouseitems")
            .addQueryParameter("actualsort", query.sort.actualSortValue)
            .addQueryParameter("p", query.page.toString())
            .addQueryParameter("numperpage", query.pageSize.toString())
            .apply {
                query.category.requiredTag?.let { tag ->
                    addQueryParameter("requiredtags[]", tag)
                }
                if (query.sort.usesTimeFilter) {
                    addQueryParameter("days", query.timeFilter.days.toString())
                }
        }
            .build()
        val httpStartedAtMs = SystemClock.elapsedRealtime()
        progressSessionId?.let { sessionId ->
            WorkshopLoadProgressReporter.report(sessionId, WorkshopLoadPhase.Connecting)
        }
        var webFailure: Throwable? = null
        val html = try {
            workshopClient.newCall(
                Request.Builder()
                    .url(searchUrl)
                    .header("Accept-Language", steamLanguagePreference.acceptLanguageValue)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) error("Steam workshop browse failed: ${response.code}")
                response.body?.string().orEmpty()
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            webFailure = error
            null
        }
        val webPage = html?.let { responseHtml ->
            val httpMs = SystemClock.elapsedRealtime() - httpStartedAtMs
            val parseStartedAtMs = SystemClock.elapsedRealtime()
            progressSessionId?.let { sessionId ->
                WorkshopLoadProgressReporter.report(sessionId, WorkshopLoadPhase.Parsing)
            }
            val page = WorkshopBrowseParser.parsePage(responseHtml, query.page)
            val parseMs = SystemClock.elapsedRealtime() - parseStartedAtMs
            Log.i(
                TAG,
                "perf searchWorkshop path=authenticatedHtmlBrowse primeMs=$primeMs httpMs=$httpMs parseMs=$parseMs htmlBytes=${responseHtml.length} items=${page.items.size} totalMs=${SystemClock.elapsedRealtime() - searchStartedAtMs}",
            )
            if (page.items.isEmpty() && looksLikeCaptivePortal(responseHtml)) {
                error("当前网络返回了 Wi-Fi/校园网认证页面，请先完成网络认证后重试")
            }
            page
        }
        if (webPage?.items?.isNotEmpty() == true || query.searchText.isBlank()) {
            return webPage ?: throw checkNotNull(webFailure)
        }

        val authSearchStartedAtMs = SystemClock.elapsedRealtime()
        authenticatedPublishedFileSearch(query)
            ?.takeIf { it.items.isNotEmpty() }
            ?.let { result ->
                Log.i(
                    TAG,
                    "perf searchWorkshop path=authProtocolFallback items=${result.items.size} elapsedMs=${SystemClock.elapsedRealtime() - authSearchStartedAtMs} totalMs=${SystemClock.elapsedRealtime() - searchStartedAtMs}",
                )
                return result
            }
        Log.i(
            TAG,
            "perf searchWorkshop path=authProtocolFallbackMiss elapsedMs=${SystemClock.elapsedRealtime() - authSearchStartedAtMs}",
        )
        return webPage ?: throw checkNotNull(webFailure)
    }

    private suspend fun authenticatedPublishedFileSearch(query: WorkshopBrowseQuery): WorkshopBrowseParseResult? {
        val account = runCatching { readSteamAccountSession() }.getOrNull() ?: return null
        return runCatching {
            SteamPublishedFileClient(
                directoryClient = SteamDirectoryClient(directoryHttpClient),
                sessionFactory = { SharedSteamCmSessions.forProcess(context).asCmSession() },
            ).queryFiles(
                account = account,
                query = SteamPublishedFileQuery(
                    appId = query.appId,
                    searchText = query.searchText.trim(),
                    page = query.page,
                    pageSize = query.pageSize,
                    queryType = STEAM_PUBLISHED_FILE_QUERY_TYPE_RANKED_BY_TEXT_SEARCH,
                    language = steamLanguagePreference.protocolLanguage,
                    requiredTags = query.category.requiredTag?.let(::listOf).orEmpty(),
                ),
            ).toBrowseParseResult(query.page, query.pageSize)
        }.getOrNull()
    }

    private suspend fun primeSteamWebSessionIfNeeded() {
        val account = runCatching { readSteamAccountSession(identity) }.getOrNull()
        if (account != null) {
            progressSessionId?.let { sessionId ->
                WorkshopLoadProgressReporter.report(sessionId, WorkshopLoadPhase.Authenticating)
            }
        }
        runCatching {
            steamWebSession.ensurePrimed(
                account = account,
                client = workshopClient,
                languagePreference = steamLanguagePreference,
            )
        }
    }

    private fun enrichBrowseMetadata(items: List<WorkshopItemSummary>): List<WorkshopItemSummary> {
        if (items.isEmpty() || items.all { it.fileSizeBytes > 0L && it.downloadCount > 0L }) {
            Log.i(TAG, "perf enrichBrowseMetadata skipped items=${items.size}")
            return items
        }
        val enrichStartedAtMs = SystemClock.elapsedRealtime()
        val appId = items.first().appId
        val metadataById = runCatching { loadBrowseMetadata(appId, items) }.getOrDefault(emptyMap())
        if (metadataById.isEmpty()) {
            Log.i(
                TAG,
                "perf enrichBrowseMetadata emptyMap items=${items.size} elapsedMs=${SystemClock.elapsedRealtime() - enrichStartedAtMs}",
            )
            return items
        }
        val enriched = items.map { item ->
            metadataById[item.publishedFileId]?.let { metadata ->
                item.copy(
                    fileSizeBytes = metadata.fileSizeBytes ?: item.fileSizeBytes,
                    downloadCount = metadata.downloadCount ?: item.downloadCount,
                )
            } ?: item
        }
        Log.i(
            TAG,
            "perf enrichBrowseMetadata items=${items.size} metadataHits=${metadataById.size} elapsedMs=${SystemClock.elapsedRealtime() - enrichStartedAtMs}",
        )
        return enriched
    }

    private fun loadBrowseMetadata(appId: UInt, items: List<WorkshopItemSummary>): Map<ULong, BrowseItemMetadata> {
        val missingMetadataItems = items.filter { it.fileSizeBytes <= 0L || it.downloadCount <= 0L }
        if (missingMetadataItems.isEmpty()) return emptyMap()
        val requestBody = FormBody.Builder().apply {
            add("itemcount", missingMetadataItems.size.toString())
            add("appid", appId.toString())
            missingMetadataItems.forEachIndexed { index, item ->
                add("publishedfileids[$index]", item.publishedFileId.toString())
            }
        }.build()
        val request = Request.Builder()
            .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/".toHttpUrl())
            .post(requestBody)
            .build()
        val httpStartedAtMs = SystemClock.elapsedRealtime()
        return browseDetailClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(
                    TAG,
                    "perf loadBrowseMetadata failed code=${response.code} missing=${missingMetadataItems.size} elapsedMs=${SystemClock.elapsedRealtime() - httpStartedAtMs}",
                )
                return emptyMap()
            }
            val payload = response.body?.string().orEmpty()
            val parsed = runCatching {
                json.decodeFromString<PublishedFileDetailsEnvelope>(payload)
                    .response
                    .publishedFileDetails
                    .mapNotNull { detail ->
                        val publishedFileId = detail.publishedFileId.toULongOrNull()
                        if (publishedFileId != null) {
                            publishedFileId to BrowseItemMetadata(
                                fileSizeBytes = detail.fileSize,
                                downloadCount = detail.subscriptions,
                            )
                        } else {
                            null
                        }
                    }
                    .toMap()
            }.getOrDefault(emptyMap())
            Log.i(
                TAG,
                "perf loadBrowseMetadata missing=${missingMetadataItems.size} hits=${parsed.size} payloadBytes=${payload.length} elapsedMs=${SystemClock.elapsedRealtime() - httpStartedAtMs}",
            )
            parsed
        }
    }

    private data class BrowseItemMetadata(
        val fileSizeBytes: Long?,
        val downloadCount: Long?,
    )

    private fun sanitizeFileName(value: String): String = value.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "workshop_mod" }

    private fun languagePreferenceFor(requestValue: String): SteamLanguagePreference =
        SteamLanguagePreference.entries.firstOrNull { it.requestValue == requestValue } ?: steamLanguagePreference

    private fun extractCommentCount(payload: String): Long? =
        totalCommentCountRegex.find(payload)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: totalCommentCountLabelRegex.find(payload)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(commentCountLabelDigitsRegex::find)
                ?.value
                ?.replace(",", "")
                ?.toLongOrNull()
            ?: workshopTabCommentCountRegex.find(payload)
                ?.groups
                ?.get("count")
                ?.value
                ?.replace(",", "")
                ?.toLongOrNull()

    private fun extractCommentThreadContext(payload: String): WorkshopCommentThreadContext? {
        val commentInit = extractCommentInitObject(payload) ?: return null
        val commentInitObject = runCatching { json.parseToJsonElement(commentInit).jsonObject }.getOrNull() ?: return null
        val ownerId = commentInitObject.stringValue("owner").ifBlank { return null }
        val featureId = commentInitObject.stringValue("feature").ifBlank { return null }
        val feature2 = commentInitObject.stringValue("feature2").ifBlank { null }
        val extendedData = commentInitObject.stringValue("extended_data").ifBlank { null }
        val sessionId = sessionIdRegex.find(payload)?.groupValues?.getOrNull(1).orEmpty().ifBlank { null }
        return WorkshopCommentThreadContext(
            ownerId = ownerId,
            featureId = featureId,
            feature2 = feature2,
            extendedData = extendedData,
            sessionId = sessionId,
        )
    }

    private fun extractCommentInitObject(payload: String): String? {
        var searchStart = 0
        while (searchStart < payload.length) {
            val callStart = payload.indexOf(COMMENT_INIT_CALL, searchStart, ignoreCase = true)
            if (callStart < 0) return null
            val openParen = payload.indexOf('(', callStart).takeIf { it >= 0 } ?: return null
            var argumentIndex = 0
            var cursor = openParen + 1
            var inString: Char? = null
            var escaped = false
            while (cursor < payload.length) {
                val char = payload[cursor]
                if (inString != null) {
                    if (escaped) {
                        escaped = false
                    } else if (char == '\\') {
                        escaped = true
                    } else if (char == inString) {
                        inString = null
                    }
                    cursor += 1
                    continue
                }
                when (char) {
                    '\'', '"' -> inString = char
                    ',' -> argumentIndex += 1
                    ')' -> break
                    '{' -> if (argumentIndex == 2) return extractBalancedObject(payload, cursor)
                }
                cursor += 1
            }
            searchStart = openParen + 1
        }
        return null
    }

    private fun extractBalancedObject(payload: String, objectStart: Int): String? {
        var depth = 0
        var cursor = objectStart
        var inString: Char? = null
        var escaped = false
        while (cursor < payload.length) {
            val char = payload[cursor]
            if (inString != null) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == inString) {
                    inString = null
                }
                cursor += 1
                continue
            }
            when (char) {
                '\'', '"' -> inString = char
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return payload.substring(objectStart, cursor + 1)
                }
            }
            cursor += 1
        }
        return null
    }

    private fun extractRequiredItemIds(payload: String): List<ULong> {
        val openingMatch = requiredItemsContainerOpeningRegex.find(payload) ?: return emptyList()
        val section = extractDivBlock(
            payload = payload,
            openingTagStart = openingMatch.range.first,
            openingTagLength = openingMatch.value.length,
        ) ?: return emptyList()
        return requiredItemLinkRegex.findAll(section)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toULongOrNull() }
            .distinct()
            .toList()
    }

    private fun extractWorkshopDescription(payload: String): String =
        workshopDescriptionOpeningRegex.findAll(payload)
            .mapNotNull { openingMatch -> extractWorkshopDescriptionCandidate(payload, openingMatch) }
            .maxByOrNull { description -> description.length }
            .orEmpty()

    private fun extractWorkshopDescriptionCandidate(payload: String, openingMatch: MatchResult): String? {
        val openingEnd = payload.indexOf('>', openingMatch.range.first).takeIf { it >= 0 } ?: return null
        val balancedBlock = extractDivBlock(
            payload = payload,
            openingTagStart = openingMatch.range.first,
            openingTagLength = openingMatch.value.length,
        )
        val balancedInnerHtml = balancedBlock?.let { section ->
            val sectionOpeningEnd = section.indexOf('>').takeIf { it >= 0 } ?: return@let null
            val closingStart = section.lastIndexOf("</div", ignoreCase = true).takeIf { it > sectionOpeningEnd } ?: section.length
            section.substring(sectionOpeningEnd + 1, closingStart)
        }
        val markerInnerHtml = extractWorkshopDescriptionUntilNextDetailSection(payload, openingEnd + 1)
        return listOfNotNull(balancedInnerHtml, markerInnerHtml)
            .map(WorkshopServiceHtmlDecoder::decodeWorkshopHtmlDescription)
            .filter(String::isNotBlank)
            .maxByOrNull(String::length)
    }

    private fun extractWorkshopDescriptionUntilNextDetailSection(payload: String, contentStart: Int): String? {
        val markerMatch = workshopDescriptionEndMarkerRegex.find(payload, contentStart) ?: return null
        val raw = payload.substring(contentStart, markerMatch.range.first)
        val closingStart = raw.lastIndexOf("</div", ignoreCase = true).takeIf { it >= 0 } ?: raw.length
        return raw.substring(0, closingStart)
    }

    private fun extractWorkshopAuthorName(payload: String): String =
        workshopAuthorAnchorRegex.find(payload)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(WorkshopServiceHtmlDecoder::stripTagsAndDecode)
            ?.ifBlank { null }
            ?: extractCreatorBlockAuthorName(payload)
                .ifBlank { null }
            ?: workshopBreadcrumbAuthorRegex.find(payload)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(WorkshopServiceHtmlDecoder::stripTagsAndDecode)
                ?.removeSuffix("的创意工坊")
                ?.trim()
                ?.ifBlank { null }
            ?: workshopAuthorTextRegex.find(payload)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(WorkshopServiceHtmlDecoder::stripTagsAndDecode)
                .orEmpty()

    private fun buildWorkshopPreviewMedia(
        summaryPreviewUrl: String,
        detailPreviewMedia: List<WorkshopPreviewMedia>,
    ): List<WorkshopPreviewMedia> {
        val seenImageUrls = mutableSetOf<String>()
        val seenYoutubeIds = mutableSetOf<String>()
        val result = ArrayList<WorkshopPreviewMedia>(detailPreviewMedia.size + 1)

        detailPreviewMedia.forEach { media ->
            when (media.kind) {
                WorkshopPreviewMediaKind.Image -> {
                    val imageUrl = WorkshopServiceHtmlDecoder.decode(media.imageUrl).trim()
                        .takeIf(::isSupportedPreviewImageUrl)
                        ?.let(::resizeWorkshopPreviewImageUrl)
                        ?: return@forEach
                    val dedupeKey = normalizePreviewImageUrlForDedupe(imageUrl)
                    if (!seenImageUrls.add(dedupeKey)) return@forEach
                    result += media.copy(
                        imageUrl = imageUrl,
                        thumbnailUrl = media.thumbnailUrl.ifBlank { imageUrl },
                    )
                }

                WorkshopPreviewMediaKind.YouTubeVideo -> {
                    val youtubeVideoId = media.youtubeVideoId.trim().takeIf(String::isNotBlank) ?: return@forEach
                    if (!seenYoutubeIds.add(youtubeVideoId)) return@forEach
                    result += media.copy(
                        youtubeVideoId = youtubeVideoId,
                        videoSource = WorkshopPreviewVideoSource.YouTube,
                        thumbnailUrl = normalizeWorkshopYouTubeThumbnailUrl(
                            youtubeVideoId = youtubeVideoId,
                            candidateUrl = media.thumbnailUrl,
                        ),
                    )
                }

                WorkshopPreviewMediaKind.SteamVideo -> {
                    // Steam-hosted workshop videos are hidden until we have a stable in-app playback path.
                    return@forEach
                }
            }
        }

        val normalizedSummaryPreviewUrl = WorkshopServiceHtmlDecoder.decode(summaryPreviewUrl).trim()
            .takeIf(::isSupportedPreviewImageUrl)
            ?.let(::resizeWorkshopPreviewImageUrl)
        if (normalizedSummaryPreviewUrl != null) {
            val dedupeKey = normalizePreviewImageUrlForDedupe(normalizedSummaryPreviewUrl)
            if (seenImageUrls.add(dedupeKey)) {
                result += WorkshopPreviewMedia(
                    id = "summary_preview",
                    kind = WorkshopPreviewMediaKind.Image,
                    imageUrl = normalizedSummaryPreviewUrl,
                    thumbnailUrl = normalizedSummaryPreviewUrl,
                )
            }
        }

        return result
    }

    private fun buildWorkshopPreviewImageUrls(previewMedia: List<WorkshopPreviewMedia>): List<String> =
        previewMedia.asSequence()
            .filter { media -> media.kind == WorkshopPreviewMediaKind.Image }
            .map(WorkshopPreviewMedia::imageUrl)
            .filter(String::isNotBlank)
            .distinctBy(::normalizePreviewImageUrlForDedupe)
            .toList()

    private fun resizeWorkshopPreviewImageUrl(url: String): String =
        runCatching {
            val httpUrl = url.toHttpUrl()
            if (!httpUrl.host.contains("steamusercontent.com", ignoreCase = true)) {
                return@runCatching url
            }
            httpUrl.newBuilder()
                .setQueryParameter("imw", "1280")
                .setQueryParameter("imh", "720")
                .setQueryParameter("ima", "fit")
                .setQueryParameter("impolicy", "Letterbox")
                .setQueryParameter("imcolor", "#000000")
                .setQueryParameter("letterbox", "false")
                .build()
                .toString()
        }.getOrDefault(url)

    private fun normalizePreviewImageUrlForDedupe(url: String): String =
        runCatching {
            val httpUrl = url.toHttpUrl()
            httpUrl.newBuilder()
                .query(null)
                .fragment(null)
                .build()
                .toString()
        }.getOrDefault(url)

    private fun extractPreviewMediaItems(payload: String): List<WorkshopPreviewMedia> {
        val screenshotsByPreviewId = extractPreviewImageUrlsByPreviewId(payload)
        val videosByMovieId = extractPreviewVideoItemsByMovieId(payload)
        val orderedMedia = highlightStripItemOpeningRegex.findAll(payload)
            .mapNotNull { match ->
                val kind = match.groupValues.getOrNull(1).orEmpty().lowercase(Locale.US)
                val id = match.groupValues.getOrNull(2).orEmpty()
                val block = extractDivBlock(
                    payload = payload,
                    openingTagStart = match.range.first,
                    openingTagLength = match.value.length,
                )
                when (kind) {
                    "movie" -> videosByMovieId[id]?.let { video ->
                        buildWorkshopVideoPreviewMedia(id, video, extractFirstImageUrl(block))
                    }

                    "screenshot" -> screenshotsByPreviewId[id]?.let { url ->
                        WorkshopPreviewMedia(
                            id = "screenshot:$id",
                            kind = WorkshopPreviewMediaKind.Image,
                            imageUrl = url,
                            thumbnailUrl = url,
                        )
                    }

                    else -> null
                }
            }
            .filterNotNull()
            .toList()
        if (orderedMedia.isNotEmpty()) return orderedMedia

        val fallbackVideos = videosByMovieId.entries
            .sortedBy { (id, _) -> id.toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { (id, video) ->
                buildWorkshopVideoPreviewMedia(id, video, "")
            }
        val fallbackImages = screenshotsByPreviewId.entries
            .sortedBy { (id, _) -> id.toIntOrNull() ?: Int.MAX_VALUE }
            .map { (id, url) ->
                WorkshopPreviewMedia(
                    id = "screenshot:$id",
                    kind = WorkshopPreviewMediaKind.Image,
                    imageUrl = url,
                    thumbnailUrl = url,
                )
            }
        return (fallbackVideos + fallbackImages).ifEmpty {
            extractPreviewImageUrls(payload).mapIndexed { index, url ->
                WorkshopPreviewMedia(
                    id = "screenshot:$index",
                    kind = WorkshopPreviewMediaKind.Image,
                    imageUrl = url,
                    thumbnailUrl = url,
                )
            }
        }
    }

    private fun buildWorkshopVideoPreviewMedia(
        movieId: String,
        video: PreviewVideoItem,
        thumbnailUrl: String,
    ): WorkshopPreviewMedia? = when (video.videoSource) {
        WorkshopPreviewVideoSource.YouTube -> WorkshopPreviewMedia(
            id = "movie:$movieId",
            kind = WorkshopPreviewMediaKind.YouTubeVideo,
            thumbnailUrl = thumbnailUrl.ifBlank {
                normalizeWorkshopYouTubeThumbnailUrl(
                    youtubeVideoId = video.youtubeVideoId,
                    candidateUrl = video.thumbnailUrl,
                )
            },
            youtubeVideoId = video.youtubeVideoId,
            videoSource = WorkshopPreviewVideoSource.YouTube,
        )

        WorkshopPreviewVideoSource.Steam -> {
            // Steam-hosted workshop videos are hidden until we have a stable in-app playback path.
            null
        }
    }

    private fun extractPreviewImageUrlsByPreviewId(payload: String): Map<String, String> =
        fullScreenshotUrlsBlockRegex.find(payload)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { block ->
                fullScreenshotUrlEntryRegex.findAll(block)
                    .mapNotNull { match ->
                        val previewId = match.groupValues.getOrNull(1)?.trim().orEmpty().takeIf(String::isNotBlank)
                            ?: return@mapNotNull null
                        val url = match.groupValues.getOrNull(2)
                            ?.let(::decodeJavascriptStringLiteral)
                            ?.let(WorkshopServiceHtmlDecoder::decode)
                            ?.trim()
                            ?.takeIf(::isSupportedPreviewImageUrl)
                            ?: return@mapNotNull null
                        previewId to url
                    }
                    .toMap(LinkedHashMap())
            }
            .orEmpty()

    private fun extractPreviewVideoItemsByMovieId(payload: String): Map<String, PreviewVideoItem> =
        movieFlashvarsEntryRegex.findAll(payload)
            .mapNotNull { match ->
                val movieId = match.groupValues.getOrNull(1)?.trim().orEmpty().takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val block = match.groupValues.getOrNull(2).orEmpty()
                val youtubeVideoId = youtubeVideoIdRegex.find(block)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                if (youtubeVideoId != null) {
                    return@mapNotNull movieId to PreviewVideoItem(
                        videoSource = WorkshopPreviewVideoSource.YouTube,
                        youtubeVideoId = youtubeVideoId,
                    )
                }
                // Steam-hosted workshop videos are hidden until we have a stable in-app playback path.
                null
            }
            .toMap(LinkedHashMap())

    private fun normalizeWorkshopYouTubeThumbnailUrl(
        youtubeVideoId: String,
        candidateUrl: String,
    ): String {
        val normalizedCandidate = WorkshopServiceHtmlDecoder.decode(candidateUrl).trim()
        if (normalizedCandidate.startsWith("http://", ignoreCase = true) ||
            normalizedCandidate.startsWith("https://", ignoreCase = true)
        ) {
            return normalizedCandidate
                .replace("/default.jpg", "/hqdefault.jpg")
                .replace("/mqdefault.jpg", "/hqdefault.jpg")
        }
        return "https://img.youtube.com/vi/$youtubeVideoId/hqdefault.jpg"
    }

    private fun extractFirstImageUrl(payload: String?): String {
        if (payload.isNullOrBlank()) return ""
        return imageSrcRegex.find(payload)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(WorkshopServiceHtmlDecoder::decode)
            ?.trim()
            .orEmpty()
    }

    private fun extractPreviewImageUrls(payload: String): List<String> =
        fullScreenshotUrlsBlockRegex.find(payload)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { block ->
                javascriptStringRegex.findAll(block)
                    .mapNotNull { match ->
                        match.groupValues.getOrNull(1)
                            ?.let(::decodeJavascriptStringLiteral)
                            ?.let(WorkshopServiceHtmlDecoder::decode)
                            ?.trim()
                            ?.takeIf(::isSupportedPreviewImageUrl)
                    }
                    .toList()
            }
            .orEmpty()
            .ifEmpty { extractPreviewImageEnlargeableUrls(payload) }
            .distinctBy(::normalizePreviewImageUrlForDedupe)

    private fun extractPreviewImageEnlargeableUrls(payload: String): List<String> =
        previewImageEnlargeableRegex.findAll(payload)
            .mapNotNull { match ->
                match.groupValues.getOrNull(1)
                    ?.let(WorkshopServiceHtmlDecoder::decode)
                    ?.trim()
                    ?.takeIf(::isSupportedPreviewImageUrl)
            }
            .toList()

    private fun isSupportedPreviewImageUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)

    private fun decodeJavascriptStringLiteral(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current != '\\' || index == value.lastIndex) {
                result.append(current)
                index += 1
                continue
            }
            val escaped = value[index + 1]
            when (escaped) {
                '\\', '"', '\'' -> result.append(escaped)
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    val hex = value.substring(index + 2, (index + 6).coerceAtMost(value.length))
                    val charCode = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                    if (charCode != null) {
                        result.append(charCode.toChar())
                        index += 4
                    } else {
                        result.append(escaped)
                    }
                }
                else -> result.append(escaped)
            }
            index += 2
        }
        return result.toString()
    }

    private fun extractCreatorBlockAuthorName(payload: String): String {
        val openingMatch = creatorsBlockOpeningRegex.find(payload) ?: return ""
        val section = extractDivBlock(
            payload = payload,
            openingTagStart = openingMatch.range.first,
            openingTagLength = openingMatch.value.length,
        ) ?: return ""
        return creatorFriendBlockContentRegex.find(section)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(WorkshopServiceHtmlDecoder::stripTagsAndDecode)
            .orEmpty()
    }

    private fun extractComments(payload: String): List<WorkshopComment> =
        commentBlockOpeningRegex.findAll(payload)
            .mapNotNull { openingMatch ->
                val id = openingMatch.groupValues[1]
                val block = extractDivBlock(
                    payload = payload,
                    openingTagStart = openingMatch.range.first,
                    openingTagLength = openingMatch.value.length,
                ) ?: return@mapNotNull null
                val (profileUrl, authorName) = extractCommentAuthor(block)
                val timestampMatch = commentTimestampRegexes.asSequence()
                    .mapNotNull { regex -> regex.find(block) }
                    .firstOrNull()
                val postedEpochSeconds = timestampMatch?.groupValueOrNull("timestamp")?.toLongOrNull()
                val postedDisplayText = timestampMatch?.groupValueOrNull("text")
                    ?.let(WorkshopServiceHtmlDecoder::stripTagsAndDecode)
                    .orEmpty()
                val content = commentTextRegex.find(block)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(WorkshopServiceHtmlDecoder::decodeWorkshopComment)
                    .orEmpty()
                if (content.isBlank()) return@mapNotNull null
                WorkshopComment(
                    id = id,
                    authorName = authorName.ifBlank { "未知用户" },
                    profileUrl = profileUrl,
                    content = content,
                    postedEpochSeconds = postedEpochSeconds,
                    postedDisplayText = postedDisplayText,
                )
            }
            .distinctBy(WorkshopComment::id)
            .toList()

    private fun extractChangeNotesMarkdownBlocks(payload: String): List<String> =
        changeLogBlockOpeningRegex.findAll(payload)
            .mapNotNull { openingMatch ->
                val block = extractDivBlock(
                    payload = payload,
                    openingTagStart = openingMatch.range.first,
                    openingTagLength = openingMatch.value.length,
                ) ?: return@mapNotNull null
                val headline = changeLogHeadlineRegex.find(block)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(WorkshopServiceHtmlDecoder::stripTagsAndDecode)
                    .orEmpty()
                val body = changeLogBodyRegex.findAll(block)
                    .mapNotNull { match ->
                        match.groupValues
                            .getOrNull(1)
                            ?.let(WorkshopServiceHtmlDecoder::decodeWorkshopChangeNotes)
                            ?.takeIf(String::isNotBlank)
                    }
                    .joinToString("\n\n")
                buildString {
                    if (headline.isNotBlank()) {
                        append("### ")
                        append(headline)
                        append("\n\n")
                    }
                    if (body.isNotBlank()) {
                        append(body)
                    }
                }.trim().takeIf(String::isNotBlank)
            }
            .toList()

    private fun extractCommentAuthor(block: String): Pair<String, String> =
        commentAuthorLinkRegex.findAll(block)
            .map { match ->
                val profileUrl = match.groupValues.getOrNull(1)?.let(WorkshopServiceHtmlDecoder::decode)?.trim().orEmpty()
                val authorName = match.groupValues.getOrNull(2)?.let(WorkshopServiceHtmlDecoder::stripTagsAndDecode).orEmpty()
                profileUrl to authorName
            }
            .firstOrNull { (_, authorName) -> authorName.isNotBlank() }
            ?: ("" to "")

    private fun resolveCommentTotalPages(commentCount: Long?): Int? =
        commentCount?.let { count ->
            if (count <= 0L) 1 else ((count + COMMENT_PAGE_SIZE - 1) / COMMENT_PAGE_SIZE).toInt()
        }

    private fun buildWorkshopCommentsUrl(
        publishedFileId: ULong,
        languageRequestValue: String,
        page: Int,
    ): String = buildString {
        append("https://steamcommunity.com/sharedfiles/filedetails/comments/")
        append(publishedFileId)
        append("?l=")
        append(languageRequestValue)
        if (page > 1) {
            append("&ctp=")
            append(resolveSteamCommentsPage(page))
        }
    }

    private fun buildWorkshopChangeNotesUrl(
        publishedFileId: ULong,
        languageRequestValue: String,
    ): String = "https://steamcommunity.com/sharedfiles/filedetails/changelog/$publishedFileId?l=$languageRequestValue"

    private fun resolveSteamCommentsPage(appCommentPage: Int): Int =
        (((appCommentPage - 1) * COMMENT_PAGE_SIZE) / STEAM_COMMENTS_PAGE_SIZE) + 1

    private fun logWarning(message: String, error: Throwable? = null) {
        runCatching {
            if (error != null) {
                Log.w(TAG, message, error)
            } else {
                Log.w(TAG, message)
            }
        }
    }

    private fun looksLikeCaptivePortal(html: String): Boolean {
        val sample = html.take(4096).lowercase()
        return sample.contains("eportal/index.jsp") ||
            sample.contains("wlanuserip=") ||
            sample.contains("wlanacname=") ||
            sample.contains("captive portal") ||
            sample.contains("wifi") && sample.contains("login") && !sample.contains("workshopitem")
    }

    companion object {
        private val activeInstances: MutableSet<WorkshopService> =
            Collections.newSetFromMap(WeakHashMap<WorkshopService, Boolean>())

        fun cancelAllActiveCalls() {
            val instances = synchronized(activeInstances) { activeInstances.toList() }
            instances.forEach { service ->
                runCatching { service.cancelActiveCalls() }
            }
        }

        private const val TAG = "WorkshopService"
        private const val COMMENT_PAGE_SIZE = 5
        private const val STEAM_COMMENTS_PAGE_SIZE = 50
        private const val COMMUNITY_DETAIL_ATTEMPTS = 2
        private const val COMMUNITY_DETAIL_RETRY_DELAY_MS = 350L
        private const val COMMUNITY_DETAIL_CACHE_TTL_MS = 5 * 60_000L
        private const val PUBLISHED_FILE_DETAILS_BATCH_SIZE = 100
        private const val PUBLISHED_FILE_SUMMARY_PROGRESS_BATCH_SIZE = 20
        private const val COMMUNITY_DETAIL_CACHE_MAX_ENTRIES = 64
        private const val MAX_SUBSCRIPTION_STATUS_CHECK_PAGES = 50
        private const val SUBSCRIPTION_VERIFY_ATTEMPTS = 4
        private const val SUBSCRIPTION_VERIFY_DELAY_MS = 750L
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val USER_AGENT = "SlayTheAmethyst/Workshop"
        private const val COMMENT_INIT_CALL = "InitializeCommentThread"
        private val sessionIdRegex = Regex(
            """g_sessionID\s*=\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )
        private val totalCommentCountRegex = Regex(
            """"total_count"\s*:\s*(\d+)""",
            RegexOption.IGNORE_CASE,
        )
        private val totalCommentCountLabelRegex = Regex(
            """id="commentthread_[^"]*_totalcount">([^<]+)<""",
            RegexOption.IGNORE_CASE,
        )
        private val workshopTabCommentCountRegex = Regex(
            """(?is)<a\b[^>]*class="[^"]*\bcomments\b[^"]*"[^>]*>\s*<span\b[^>]*>\s*(?:Comments|留言|评论)\s*<span\b[^>]*class="[^"]*\b(?:tabCount|general_btn_count)\b[^"]*"[^>]*>\s*(?<count>[\d,]+)\s*</span>""",
        )
        private val commentCountLabelDigitsRegex = Regex("""\d[\d,]*""")
        private val commentBlockOpeningRegex = Regex(
            """<div\b[^>]*class="[^"]*\bcommentthread_comment\b[^"]*"[^>]*id="comment_([^"]+)"[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        private val commentAuthorLinkRegex = Regex(
            """<a\b(?=[^>]*class="[^"]*\bcommentthread_author_link\b[^"]*")(?=[^>]*href="([^"]*)")[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val commentTimestampRegexes = listOf(
            Regex(
                """<(?:span|div)\b(?=[^>]*class="[^"]*\bcommentthread_comment_timestamp\b[^"]*")(?=[^>]*\bdata-timestamp="(?<timestamp>\d+)")[^>]*>(?<text>.*?)</(?:span|div)>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            ),
            Regex(
                """<(?:span|div)\b(?=[^>]*class="[^"]*\bcommentthread_comment_timestamp\b[^"]*")[^>]*\btitle="(?<text>[^"]*)"[^>]*>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            ),
            Regex(
                """<(?:span|div)\b(?=[^>]*class="[^"]*\bcommentthread_comment_timestamp\b[^"]*")[^>]*>(?<text>.*?)</(?:span|div)>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            ),
        )
        private val commentTextRegex = Regex(
            """<div\b[^>]*class="[^"]*\bcommentthread_comment_text\b[^"]*"[^>]*>(.*?)</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val changeLogBlockOpeningRegex = Regex(
            """<div\b[^>]*class="[^"]*\bchangeLogCtn\b[^"]*"[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        private val changeLogHeadlineRegex = Regex(
            """<div\b[^>]*class="[^"]*\bheadline\b[^"]*"[^>]*>(.*?)</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val changeLogBodyRegex = Regex(
            """<p\b[^>]*>(.*?)</p>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val requiredItemsContainerOpeningRegex = Regex(
            """<div\b[^>]*\bid="RequiredItems"[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        private val requiredItemLinkRegex = Regex(
            """<a\b[^>]*href="[^"]*(?:sharedfiles|workshop)/filedetails/\?id=(\d+)[^"]*"[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        private val workshopAuthorAnchorRegex = Regex(
            """<div\b[^>]*class="[^"]*\bworkshopItemAuthorName\b[^"]*"[^>]*>.*?<a\b[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val workshopAuthorTextRegex = Regex(
            """<div\b[^>]*class="[^"]*\bworkshopItemAuthorName\b[^"]*"[^>]*>(.*?)</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val creatorsBlockOpeningRegex = Regex(
            """<div\b[^>]*class="[^"]*\bcreatorsBlock\b[^"]*"[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        private val creatorFriendBlockContentRegex = Regex(
            """<div\b[^>]*class="[^"]*\bfriendBlockContent\b[^"]*"[^>]*>\s*(.*?)(?:<br\s*/?>|<span\b[^>]*class="[^"]*\bfriendSmallText\b)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val workshopBreadcrumbAuthorRegex = Regex(
            """<div\b[^>]*class="[^"]*\bbreadcrumbs\b[^"]*"[^>]*>.*?<a\b[^>]*myworkshopfiles/\?appid=\d+[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val workshopDescriptionOpeningRegex = Regex(
            """<div\b(?=[^>]*\bclass="[^"]*\bworkshopItemDescription\b[^"]*")(?=[^>]*\bid="highlightContent")[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        private val workshopDescriptionEndMarkerRegex = Regex(
            """(?is)</div>\s*</div>\s*(?:<script\b|<div\b[^>]*class="[^"]*\bdetailBox\b|$)""",
        )
        private val fullScreenshotUrlsBlockRegex = Regex(
            """var\s+rgFullScreenshotURLs\s*=\s*\[(.*?)\]\s*;""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val fullScreenshotUrlEntryRegex = Regex(
            """\{\s*['"]previewid['"]\s*:\s*['"]([^'"]+)['"]\s*,\s*['"]url['"]\s*:\s*['"]([^'"]+)['"]\s*\}""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val javascriptStringRegex = Regex(
            """['"]((?:\\.|[^'"\\])*)['"]""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val movieFlashvarsEntryRegex = Regex(
            """['"]movie_([^'"]+)['"]\s*:\s*\{(.*?)\}""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val youtubeVideoIdRegex = Regex(
            """YOUTUBE_VIDEO_ID\s*:\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val previewImageEnlargeableRegex = Regex(
            """<a\b[^>]*onclick="ShowEnlargedImagePreview\(\s*'([^']+)'""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val highlightStripItemOpeningRegex = Regex(
            """<div\b(?=[^>]*class="[^"]*\bhighlight_strip_item\b[^"]*\bhighlight_strip_(movie|screenshot)\b[^"]*")(?=[^>]*id="thumb_(?:movie|screenshot)_([^"]+)")[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        private val imageSrcRegex = Regex(
            """<img\b[^>]*src="([^"]+)""",
            RegexOption.IGNORE_CASE,
        )
    }
}

internal enum class WorkshopDownloadState { Resolving, Downloading, Success, Failed }

internal sealed interface WorkshopDownloadEvent {
    data object Ignored : WorkshopDownloadEvent
    data class StateChanged(val state: WorkshopDownloadState) : WorkshopDownloadEvent
    data class Log(val message: String) : WorkshopDownloadEvent
    data class Progress(val progress: WorkshopDownloadProgress) : WorkshopDownloadEvent
    data class Completed(val files: List<WorkshopDownloadedArtifact>) : WorkshopDownloadEvent
    data class Failed(val failure: WorkshopDownloadFailure) : WorkshopDownloadEvent
}

@Serializable
private data class PublishedFileDetailsEnvelope(
    val response: PublishedFileDetailsResponse,
)

@Serializable
private data class PublishedFileDetailsResponse(
    @SerialName("publishedfiledetails") val publishedFileDetails: List<PublishedFileDetailsDto> = emptyList(),
)

@Serializable
private data class PublishedFileDetailsDto(
    @SerialName("publishedfileid") val publishedFileId: String = "",
    val creator: String? = null,
    val title: String = "",
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("hcontent_file") val hcontentFile: Long? = null,
    @SerialName("consumer_app_id") val consumerAppId: Long? = null,
    @SerialName("creator_name") val creatorName: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("time_updated") val timeUpdated: Long? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    val subscriptions: Long? = null,
    @SerialName("vote_data") val voteData: PublishedFileVoteDataDto? = null,
    val children: List<PublishedFileChildDto> = emptyList(),
)

@Serializable
private data class PublishedFileVoteDataDto(
    val score: Float? = null,
)

@Serializable
private data class PublishedFileChildDto(
    @SerialName("publishedfileid") val publishedFileId: String = "",
)

private fun PublishedFileDetailsDto.toSummary(appId: UInt, fallbackPublishedFileId: ULong): WorkshopItemSummary = WorkshopItemSummary(
    publishedFileId = publishedFileId.toULongOrNull() ?: fallbackPublishedFileId,
    appId = consumerAppId?.takeIf { it > 0 }?.toUInt() ?: appId,
    title = title.ifBlank { knownWorkshopDependencyTitle(fallbackPublishedFileId) ?: "Workshop ID $fallbackPublishedFileId" },
    previewUrl = previewUrl.orEmpty(),
    description = description.orEmpty(),
    authorName = creatorName.orEmpty(),
    fileSizeBytes = fileSize ?: 0L,
    updatedAtMillis = (timeUpdated ?: 0L) * 1000L,
    downloadCount = subscriptions ?: 0L,
    rating = normalizedWorkshopRating(voteData?.score),
)

private fun PublishedFileDetailsDto.toCommentThreadContext(fallbackPublishedFileId: ULong): WorkshopCommentThreadContext? {
    val ownerId = creator.orEmpty().trim().takeIf { it.isNotBlank() && it != "0" } ?: return null
    val featureId = publishedFileId
        .toULongOrNull()
        ?.takeIf { it > 0uL }
        ?.toString()
        ?: fallbackPublishedFileId.toString()
    return WorkshopCommentThreadContext(
        ownerId = ownerId,
        featureId = featureId,
        feature2 = "-1",
    )
}

private fun SteamPublishedFileQueryResult.toBrowseParseResult(page: Int, pageSize: Int): WorkshopBrowseParseResult =
    WorkshopBrowseParseResult(
        items = items.map { item ->
            WorkshopItemSummary(
                publishedFileId = item.publishedFileId,
                appId = item.appId,
                title = item.title,
                previewUrl = item.previewUrl,
                description = item.description,
                authorName = "",
                fileSizeBytes = item.fileSizeBytes,
                updatedAtMillis = item.timeUpdatedEpochSeconds * 1000L,
                downloadCount = item.subscriptions.toLong(),
                rating = normalizedWorkshopRating(item.ratingScore),
            )
        },
        page = page,
        hasNextPage = total > page * pageSize || !nextCursor.isNullOrBlank(),
    )

private fun knownWorkshopDependencyTitle(publishedFileId: ULong): String? = when (publishedFileId) {
    1605060445uL -> "ModTheSpire"
    1605833019uL -> "BaseMod"
    1609158507uL -> "StSLib"
    1610056683uL -> "Downfall Expansion Mod"
    else -> null
}

private data class LocalizedWorkshopDetail(
    val description: String,
    val authorName: String = "",
    val previewMedia: List<WorkshopPreviewMedia> = emptyList(),
    val requiredItemIds: List<ULong> = emptyList(),
    val commentThreadContext: WorkshopCommentThreadContext? = null,
    val commentCount: Long? = null,
)

private data class PreviewVideoItem(
    val videoSource: WorkshopPreviewVideoSource,
    val youtubeVideoId: String = "",
    val thumbnailUrl: String = "",
)

private data class CommunityDetailCacheKey(
    val publishedFileId: ULong,
    val languageRequestValue: String,
)

private data class CachedCommunityDetail(
    val detail: LocalizedWorkshopDetail,
    val loadedAtMillis: Long,
)

private class SteamCommunityRateLimitException(statusCode: Int) :
    IllegalStateException("Steam workshop community detail rate limited: $statusCode")

private fun LocalizedWorkshopDetail.hasUsefulContent(): Boolean =
    description.isNotBlank() ||
        authorName.isNotBlank() ||
        previewMedia.isNotEmpty() ||
        requiredItemIds.isNotEmpty() ||
        commentThreadContext != null ||
        commentCount != null

private fun extractDivInnerHtml(
    payload: String,
    openingTag: String,
): String? {
    val start = payload.indexOf(openingTag)
    if (start < 0) return null
    var cursor = start + openingTag.length
    var depth = 1
    while (cursor < payload.length) {
        val nextOpen = payload.indexOf("<div", cursor, ignoreCase = true).takeIf { it >= 0 }
        val nextClose = payload.indexOf("</div", cursor, ignoreCase = true).takeIf { it >= 0 }
        val nextIndex = listOfNotNull(nextOpen, nextClose).minOrNull() ?: break
        if (nextIndex == nextOpen) {
            depth += 1
            cursor = nextIndex + 4
            continue
        }
        depth -= 1
        if (depth == 0) return payload.substring(start + openingTag.length, nextIndex)
        cursor = nextIndex + 5
    }
    return null
}

private fun extractDivBlock(
    payload: String,
    openingTagStart: Int,
    openingTagLength: Int,
): String? {
    var cursor = openingTagStart + openingTagLength
    var depth = 1
    while (cursor < payload.length) {
        val nextOpen = payload.indexOf("<div", cursor, ignoreCase = true).takeIf { it >= 0 }
        val nextClose = payload.indexOf("</div", cursor, ignoreCase = true).takeIf { it >= 0 }
        val nextIndex = listOfNotNull(nextOpen, nextClose).minOrNull() ?: break
        if (nextIndex == nextOpen) {
            depth += 1
            cursor = nextIndex + 4
            continue
        }
        depth -= 1
        if (depth == 0) {
            val closingTagEnd = payload.indexOf('>', nextIndex).takeIf { it >= 0 } ?: return null
            return payload.substring(openingTagStart, closingTagEnd + 1)
        }
        cursor = nextIndex + 5
    }
    return null
}

private fun JsonObject.stringValue(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.longValue(key: String): Long? =
    this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

private fun JsonObject.intValue(key: String): Int? =
    this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

private fun MatchResult.groupValueOrNull(name: String): String? =
    runCatching { groups[name]?.value }.getOrNull()

private object WorkshopServiceHtmlDecoder {
    private val numericEntityRegex = Regex("""&#(x?[0-9A-Fa-f]+);""")
    private val htmlTagRegex = Regex("""<[^>]+>""")
    private val emoticonImageRegex = Regex("""(?is)<img\b[^>]*\balt="([^"]+)"[^>]*\bclass="[^"]*\bemoticon\b[^"]*"[^>]*>""")
    private val whitespaceRegex = Regex("""\s+""")
    private val inlineWhitespaceRegex = Regex("""[^\S\n]+""")

    fun stripTagsAndDecode(value: String): String = decode(value.replace(htmlTagRegex, " "))

    fun decode(value: String): String = decodeEntities(value)
        .replace(whitespaceRegex, " ")
        .trim()

    fun decodeWorkshopHtmlDescription(value: String): String {
        if (value.isBlank()) return ""
        return decodePreservingLineBreaks(
            value
                .replace(Regex("""(?i)<br\s*/?>"""), "\n")
                .replace(Regex("""(?i)<li[^>]*>"""), "- ")
                .replace(Regex("""(?i)</li\s*>"""), "\n")
                .replace(Regex("""(?i)</p\s*>"""), "\n\n")
                .replace(Regex("""(?i)</div\s*>"""), "\n")
                .replace(htmlTagRegex, " "),
        )
    }

    fun decodeWorkshopChangeNotes(value: String): String {
        if (value.isBlank()) return ""
        return decodePreservingLineBreaks(
            value
                .replace(Regex("""(?i)<br\s*/?>"""), "\n")
                .replace(Regex("""(?i)<li[^>]*>"""), "- ")
                .replace(Regex("""(?i)</li\s*>"""), "\n")
                .replace(Regex("""(?i)<(?:ul|ol)[^>]*>"""), "\n")
                .replace(Regex("""(?i)</(?:ul|ol)\s*>"""), "\n")
                .replace(Regex("""(?i)</p\s*>"""), "\n\n")
                .replace(Regex("""(?i)</div\s*>"""), "\n")
                .replace(htmlTagRegex, " "),
        )
    }

    fun decodeWorkshopComment(value: String): String {
        if (value.isBlank()) return ""
        return decodePreservingLineBreaks(
            value
                .replace(emoticonImageRegex) { match -> " ${match.groupValues[1]} " }
                .replace(Regex("""(?i)<br\s*/?>"""), "\n")
                .replace(Regex("""(?i)</p\s*>"""), "\n\n")
                .replace(Regex("""(?i)</div\s*>"""), "\n")
                .replace(htmlTagRegex, " "),
        )
    }

    private fun decodePreservingLineBreaks(value: String): String =
        decodeEntities(value)
            .replace(Regex("""\r\n?"""), "\n")
            .lines()
            .joinToString("\n") { line -> line.replace(inlineWhitespaceRegex, " ").trim() }
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

    private fun decodeEntities(value: String): String {
        val withNumericEntities = numericEntityRegex.replace(value) { match ->
            val token = match.groupValues[1]
            val codePoint = if (token.startsWith("x", ignoreCase = true)) {
                token.substring(1).toIntOrNull(16)
            } else {
                token.toIntOrNull()
            }
            codePoint?.let { String(Character.toChars(it)) } ?: match.value
        }
        return withNumericEntities
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'")
            .replace("&#x27;", "'", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
    }
}

package io.stamethyst.backend.feedback

import android.content.Context
import io.stamethyst.BuildConfig
import io.stamethyst.backend.github.WattToolkitAcceleratedHttp
import io.stamethyst.backend.github.GithubRequestClients
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import io.stamethyst.backend.update.GithubMirrorFallback
import io.stamethyst.backend.update.UpdateMirrorManager
import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.backend.update.toGithubMirrorHttpException
import io.stamethyst.ui.preferences.LauncherPreferences
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object FeedbackIssueSyncService {
    private const val GITHUB_API_BASE = "https://api.github.com"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 18_000
    private const val USER_AGENT = "SlayTheAmethyst-FeedbackSync"
    private const val DEFAULT_ISSUE_PAGE_SIZE = 20
    private const val GITHUB_SEARCH_MAX_RESULTS = 1_000
    internal const val MAX_TRACKED_SUBSCRIPTIONS = 10
    private val issueBodyDeviceLabelRegex = Regex("""(?m)^\s*[-*+]\s*设备\s*[：:]\s*(.*?)\s*$""")

    fun subscribeToIssue(context: Context, issueNumberText: String): FeedbackSubscriptionChangeResult {
        val issueNumber = issueNumberText.trim().toLongOrNull()
            ?: throw IOException("Issue 编号格式不正确。")
        return subscribeToIssue(context, issueNumber)
    }

    fun saveLocalSubscription(
        context: Context,
        issueNumber: Long,
        issueUrl: String?,
        title: String,
        issueBody: String,
        state: String = "open"
    ): FeedbackSubscriptionChangeResult {
        requireValidIssueNumber(issueNumber)
        val now = System.currentTimeMillis()
        val normalizedIssueUrl = issueUrl?.trim().orEmpty().ifEmpty { buildIssueUrl(issueNumber) }
        val existingCache = FeedbackIssueLocalStore.loadIssueCache(context, issueNumber)
        val cache = (existingCache ?: FeedbackIssueThreadCache(
            issueNumber = issueNumber,
            issueUrl = normalizedIssueUrl,
            title = title.ifBlank { "Issue #$issueNumber" },
            state = state.ifBlank { "open" },
            body = issueBody,
            updatedAtMs = now,
            events = emptyList()
        )).copy(
            issueUrl = normalizedIssueUrl,
            title = title.ifBlank { existingCache?.title ?: "Issue #$issueNumber" },
            state = state.ifBlank { existingCache?.state ?: "open" },
            body = issueBody.ifBlank { existingCache?.body.orEmpty() },
            updatedAtMs = maxOf(existingCache?.updatedAtMs ?: 0L, now)
        )
        FeedbackIssueLocalStore.saveIssueCache(context, cache)
        var changeResult: FeedbackSubscriptionChangeResult? = null
        FeedbackIssueLocalStore.updateSubscriptions(context) { current ->
            val existingSubscription = current.firstOrNull { it.issueNumber == issueNumber }
            val subscription = FeedbackIssueSubscription(
                issueNumber = issueNumber,
                issueUrl = cache.issueUrl,
                title = cache.title,
                state = cache.state,
                unread = false,
                followedAtMs = existingSubscription?.followedAtMs ?: now,
                lastSyncedAtMs = now,
                lastViewedAtMs = maxOf(existingSubscription?.lastViewedAtMs ?: 0L, cache.lastEventAtMs),
                updatedAtMs = cache.updatedAtMs
            )
            val upsertResult = upsertSubscriptionWithLimit(current, subscription)
            changeResult = FeedbackSubscriptionChangeResult(
                subscription = subscription,
                displacedSubscriptions = upsertResult.displacedSubscriptions
            )
            upsertResult.subscriptions
        }
        val saved = checkNotNull(changeResult)
        saved.displacedSubscriptions.forEach { displaced ->
            FeedbackIssueLocalStore.deleteIssueCache(context, displaced.issueNumber)
        }
        return saved
    }

    fun subscribeToIssue(context: Context, issueNumber: Long): FeedbackSubscriptionChangeResult {
        requireValidIssueNumber(issueNumber)
        val remote = fetchIssueSummary(context, issueNumber)
        FeedbackIssueLocalStore.saveIssueCache(context, remote)
        val now = System.currentTimeMillis()
        var changeResult: FeedbackSubscriptionChangeResult? = null
        FeedbackIssueLocalStore.updateSubscriptions(context) { current ->
            val existing = current.firstOrNull { it.issueNumber == issueNumber }
            val subscription = FeedbackIssueSubscription(
                issueNumber = issueNumber,
                issueUrl = remote.issueUrl,
                title = remote.title,
                state = remote.state,
                unread = false,
                followedAtMs = existing?.followedAtMs ?: now,
                lastSyncedAtMs = now,
                lastViewedAtMs = maxOf(existing?.lastViewedAtMs ?: 0L, remote.lastEventAtMs),
                updatedAtMs = remote.updatedAtMs
            )
            val upsertResult = upsertSubscriptionWithLimit(current, subscription)
            changeResult = FeedbackSubscriptionChangeResult(
                subscription = subscription,
                displacedSubscriptions = upsertResult.displacedSubscriptions
            )
            upsertResult.subscriptions
        }
        val saved = checkNotNull(changeResult)
        saved.displacedSubscriptions.forEach { displaced ->
            FeedbackIssueLocalStore.deleteIssueCache(context, displaced.issueNumber)
        }
        return saved
    }

    fun listIssues(
        context: Context,
        page: Int,
        pageSize: Int = DEFAULT_ISSUE_PAGE_SIZE,
        searchQuery: String = "",
        state: String = "all"
    ): FeedbackIssueBrowsePage {
        if (page <= 0) {
            throw IOException("议题页码不正确。")
        }
        if (pageSize <= 0) {
            throw IOException("议题分页大小不正确。")
        }
        val clients = createGithubClients(context)
        val preferred = UpdateMirrorManager.current(context)
        val bypassAcceleratedLinks = NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(context)
        try {
            return GithubMirrorFallback.run(
                preferred,
                bypassAcceleratedLinks = bypassAcceleratedLinks,
            ) { source ->
                fetchIssuePageFromSource(
                    clients.pick(source.usesGithubAcceleration),
                    source,
                    page,
                    pageSize,
                    searchQuery,
                    state
                )
            }.value
        } catch (error: Throwable) {
            throw buildWrappedIOException("无法加载议题列表：", error)
        }
    }

    fun syncAllSubscriptions(context: Context): FeedbackSyncResult {
        val initialSubscriptions = FeedbackIssueLocalStore.loadSubscriptions(context)
        if (initialSubscriptions.isEmpty()) {
            return FeedbackSyncResult(
                subscriptions = emptyList(),
                unreadIssueNumbers = emptyList(),
                syncedAtMs = System.currentTimeMillis()
            )
        }

        val syncedAtMs = System.currentTimeMillis()
        val remoteByIssueNumber = LinkedHashMap<Long, FeedbackIssueThreadCache>(initialSubscriptions.size)
        val clients = createGithubClients(context)
        initialSubscriptions.forEach { subscription ->
            val remote = fetchIssueSummary(
                context = context,
                issueNumber = subscription.issueNumber,
                clients = clients,
                existingCache = FeedbackIssueLocalStore.loadIssueCache(context, subscription.issueNumber)
            )
            FeedbackIssueLocalStore.saveIssueCache(context, remote)
            remoteByIssueNumber[subscription.issueNumber] = remote
        }
        val updatedSubscriptions = FeedbackIssueLocalStore.updateSubscriptions(context) { current ->
            mergeSyncedSubscriptions(current, remoteByIssueNumber, syncedAtMs)
        }
        return FeedbackSyncResult(
            subscriptions = updatedSubscriptions,
            unreadIssueNumbers = updatedSubscriptions.filter { it.unread }.map { it.issueNumber },
            syncedAtMs = syncedAtMs
        )
    }

    fun refreshIssue(
        context: Context,
        issueNumber: Long,
        markViewed: Boolean
    ): FeedbackIssueThreadCache {
        FeedbackIssueLocalStore.loadSubscriptions(context)
            .firstOrNull { it.issueNumber == issueNumber }
            ?: throw IOException("未找到对应的反馈订阅。")
        val remote = fetchRemoteIssue(context, issueNumber)
        FeedbackIssueLocalStore.saveIssueCache(context, remote)
        val syncedAtMs = System.currentTimeMillis()
        FeedbackIssueLocalStore.updateSubscriptions(context) { subscriptions ->
            val latest = subscriptions.firstOrNull { it.issueNumber == issueNumber }
                ?: return@updateSubscriptions subscriptions
            mergeSubscription(
                subscriptions,
                latest.withRemoteState(
                    remote = remote,
                    syncedAtMs = syncedAtMs,
                    markViewed = markViewed
                )
            )
        }
        return remote
    }

    fun fetchIssuePreview(
        context: Context,
        issueNumber: Long
    ): FeedbackIssueThreadCache {
        val remote = fetchRemoteIssue(context, issueNumber)
        FeedbackIssueLocalStore.saveIssueCache(context, remote)
        return remote
    }

    fun markIssueViewed(context: Context, issueNumber: Long) {
        val cache = FeedbackIssueLocalStore.loadIssueCache(context, issueNumber)
        FeedbackIssueLocalStore.updateSubscriptions(context) { subscriptions ->
            val subscription = subscriptions.firstOrNull { it.issueNumber == issueNumber }
                ?: return@updateSubscriptions subscriptions
            val updated = subscription.copy(
                unread = false,
                lastViewedAtMs = maxOf(
                    subscription.lastViewedAtMs,
                    cache?.lastEventAtMs ?: subscription.updatedAtMs
                )
            )
            mergeSubscription(subscriptions, updated)
        }
    }

    fun unsubscribe(context: Context, issueNumber: Long) {
        FeedbackIssueLocalStore.updateSubscriptions(context) { current ->
            current.filterNot { it.issueNumber == issueNumber }
        }
        FeedbackIssueLocalStore.deleteIssueCache(context, issueNumber)
    }

    fun buildIssueUrl(issueNumber: Long): String {
        return "https://github.com/${BuildConfig.FEEDBACK_GITHUB_OWNER}/${BuildConfig.FEEDBACK_GITHUB_REPO}/issues/$issueNumber"
    }

    private fun fetchRemoteIssue(
        context: Context,
        issueNumber: Long,
        clients: GithubRequestClients = createGithubClients(context),
    ): FeedbackIssueThreadCache {
        return fetchIssueDetails(context, issueNumber, clients)
    }

    private fun fetchIssueSummary(
        context: Context,
        issueNumber: Long,
        clients: GithubRequestClients = createGithubClients(context),
        existingCache: FeedbackIssueThreadCache? = FeedbackIssueLocalStore.loadIssueCache(context, issueNumber),
    ): FeedbackIssueThreadCache {
        val preferred = UpdateMirrorManager.current(context)
        val bypassAcceleratedLinks = NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(context)
        return try {
            GithubMirrorFallback.run(
                preferred,
                bypassAcceleratedLinks = bypassAcceleratedLinks,
            ) { source ->
                fetchIssueSummaryFromSource(
                    context,
                    clients.pick(source.usesGithubAcceleration),
                    source,
                    issueNumber,
                    existingCache
                )
            }.value
        } catch (error: Throwable) {
            throw buildWrappedIOException("无法同步 Issue #$issueNumber：", error)
        }
    }

    private fun fetchIssueDetails(
        context: Context,
        issueNumber: Long,
        clients: GithubRequestClients = createGithubClients(context),
        existingCache: FeedbackIssueThreadCache? = FeedbackIssueLocalStore.loadIssueCache(context, issueNumber),
    ): FeedbackIssueThreadCache {
        val preferred = UpdateMirrorManager.current(context)
        val bypassAcceleratedLinks = NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(context)
        return try {
            GithubMirrorFallback.run(
                preferred,
                bypassAcceleratedLinks = bypassAcceleratedLinks,
            ) { source ->
                fetchIssueDetailsFromSource(
                    context,
                    clients.pick(source.usesGithubAcceleration),
                    source,
                    issueNumber,
                    existingCache
                )
            }.value
        } catch (error: Throwable) {
            throw buildWrappedIOException("无法同步 Issue #$issueNumber：", error)
        }
    }

    private fun fetchIssuePageFromSource(
        client: OkHttpClient,
        source: UpdateSource,
        page: Int,
        pageSize: Int,
        searchQuery: String,
        state: String
    ): FeedbackIssueBrowsePage {
        val normalizedSearchQuery = searchQuery.trim()
        return if (normalizedSearchQuery.isBlank()) {
            fetchListedIssuePageFromSource(client, source, page, pageSize, state)
        } else {
            fetchSearchedIssuePageFromSource(client, source, page, pageSize, normalizedSearchQuery, state)
        }
    }

    private fun fetchListedIssuePageFromSource(
        client: OkHttpClient,
        source: UpdateSource,
        page: Int,
        pageSize: Int,
        state: String
    ): FeedbackIssueBrowsePage {
        val issues = requestJsonArray(
            client,
            source.buildUrl(buildIssueListUrl(page, pageSize, state))
        )
        val items = parseIssueBrowseItems(issues)
        return FeedbackIssueBrowsePage(
            issues = items,
            nextPage = page + 1,
            hasMore = issues.length() >= pageSize
        )
    }

    private fun fetchSearchedIssuePageFromSource(
        client: OkHttpClient,
        source: UpdateSource,
        page: Int,
        pageSize: Int,
        searchQuery: String,
        state: String
    ): FeedbackIssueBrowsePage {
        val response = requestJsonObject(
            client,
            source.buildUrl(buildIssueSearchUrl(page, pageSize, searchQuery, state))
        )
        val issues = response.optJSONArray("items") ?: JSONArray()
        val items = parseIssueBrowseItems(issues)
        val totalCount = response.optInt("total_count", 0).coerceAtMost(GITHUB_SEARCH_MAX_RESULTS)
        return FeedbackIssueBrowsePage(
            issues = items,
            nextPage = page + 1,
            hasMore = page * pageSize < totalCount && issues.length() >= pageSize
        )
    }

    private fun parseIssueBrowseItems(issues: JSONArray): List<FeedbackIssueBrowseItem> {
        val items = ArrayList<FeedbackIssueBrowseItem>(issues.length())
        for (index in 0 until issues.length()) {
            val item = issues.optJSONObject(index) ?: continue
            parseIssueBrowseItem(item)?.let(items::add)
        }
        return items
    }

    private fun parseIssueBrowseItem(item: JSONObject): FeedbackIssueBrowseItem? {
        if (item.has("pull_request")) {
            return null
        }
        val issueNumber = item.optLong("number")
        if (issueNumber <= 0L) {
            return null
        }
        return FeedbackIssueBrowseItem(
            issueNumber = issueNumber,
            issueUrl = item.optString("html_url").trim().ifEmpty { buildIssueUrl(issueNumber) },
            title = item.optString("title").trim(),
            bodyPreview = buildBodyPreview(item.optString("body")),
            state = item.optString("state").trim().ifEmpty { "open" },
            commentCount = item.optInt("comments"),
            authorLabel = item.optJSONObject("user")
                ?.optString("login")
                ?.trim()
                .orEmpty()
                .ifBlank { "Unknown" },
            updatedAtMs = maxOf(
                parseInstantToMillis(item.optString("updated_at")),
                parseInstantToMillis(item.optString("created_at"))
            )
        )
    }

    private fun buildIssueListUrl(page: Int, pageSize: Int, state: String): String {
        return "$GITHUB_API_BASE/repos/${BuildConfig.FEEDBACK_GITHUB_OWNER}/${BuildConfig.FEEDBACK_GITHUB_REPO}/issues"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("state", normalizeBrowseState(state))
            .addQueryParameter("sort", "updated")
            .addQueryParameter("direction", "desc")
            .addQueryParameter("per_page", pageSize.toString())
            .addQueryParameter("page", page.toString())
            .build()
            .toString()
    }

    private fun buildIssueSearchUrl(page: Int, pageSize: Int, searchQuery: String, state: String): String {
        val queryParts = ArrayList<String>()
        queryParts += "repo:${BuildConfig.FEEDBACK_GITHUB_OWNER}/${BuildConfig.FEEDBACK_GITHUB_REPO}"
        queryParts += "is:issue"
        when (normalizeBrowseState(state)) {
            "open" -> queryParts += "state:open"
            "closed" -> queryParts += "state:closed"
        }
        queryParts += searchQuery
        return "$GITHUB_API_BASE/search/issues"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("q", queryParts.joinToString(" "))
            .addQueryParameter("sort", "updated")
            .addQueryParameter("order", "desc")
            .addQueryParameter("per_page", pageSize.toString())
            .addQueryParameter("page", page.toString())
            .build()
            .toString()
    }

    private fun normalizeBrowseState(state: String): String {
        return when (state.trim().lowercase(Locale.ROOT)) {
            "open" -> "open"
            "closed" -> "closed"
            else -> "all"
        }
    }

    private fun fetchIssueSummaryFromSource(
        context: Context,
        client: OkHttpClient,
        source: UpdateSource,
        issueNumber: Long,
        existingCache: FeedbackIssueThreadCache?
    ): FeedbackIssueThreadCache {
        val issue = requestJsonObject(
            client,
            source.buildUrl(
                "$GITHUB_API_BASE/repos/${BuildConfig.FEEDBACK_GITHUB_OWNER}/${BuildConfig.FEEDBACK_GITHUB_REPO}/issues/$issueNumber"
            )
        )
        if (issue.has("pull_request")) {
            throw IOException("链接指向的是 Pull Request，不是 Issue。")
        }
        return buildIssueSummaryCache(context, issue, existingCache)
    }

    private fun fetchIssueDetailsFromSource(
        context: Context,
        client: OkHttpClient,
        source: UpdateSource,
        issueNumber: Long,
        existingCache: FeedbackIssueThreadCache?
    ): FeedbackIssueThreadCache {
        val summaryCache = fetchIssueSummaryFromSource(
            context = context,
            client = client,
            source = source,
            issueNumber = issueNumber,
            existingCache = existingCache
        )
        val comments = requestJsonArray(
            client,
            source.buildUrl(
                "$GITHUB_API_BASE/repos/${BuildConfig.FEEDBACK_GITHUB_OWNER}/${BuildConfig.FEEDBACK_GITHUB_REPO}/issues/$issueNumber/comments?per_page=100"
            )
        )
        val events = requestJsonArray(
            client,
            source.buildUrl(
                "$GITHUB_API_BASE/repos/${BuildConfig.FEEDBACK_GITHUB_OWNER}/${BuildConfig.FEEDBACK_GITHUB_REPO}/issues/$issueNumber/events?per_page=100"
            )
        )
        return buildThreadCache(context, summaryCache, comments, events)
    }

    private fun buildIssueSummaryCache(
        context: Context,
        issue: JSONObject,
        existingCache: FeedbackIssueThreadCache?
    ): FeedbackIssueThreadCache {
        val issueNumber = issue.optLong("number")
        val rawIssueBody = issue.optString("body")
        val issueEvent = buildGithubThreadEvent(
            context = context,
            id = "issue-$issueNumber",
            item = issue,
            fallbackHtmlUrl = issue.optString("html_url").trim().ifEmpty { buildIssueUrl(issueNumber) },
            fallbackCreatedAtMs = parseInstantToMillis(issue.optString("created_at"))
        )
        val events = (
            listOfNotNull(issueEvent) +
                existingCache?.events.orEmpty().filterNot { it.id == "issue-$issueNumber" }
            ).sortedWith(
            compareBy<FeedbackThreadEvent> { it.createdAtMs }
                .thenBy { it.id }
        )
        return FeedbackIssueThreadCache(
            issueNumber = issueNumber,
            issueUrl = issue.optString("html_url").trim().ifEmpty { buildIssueUrl(issueNumber) },
            title = issue.optString("title").trim(),
            state = issue.optString("state").trim().ifEmpty { "open" },
            body = issueEvent?.body ?: stripFeedbackProxyMetadataForDisplay(rawIssueBody),
            updatedAtMs = maxOf(
                parseInstantToMillis(issue.optString("updated_at")),
                parseInstantToMillis(issue.optString("created_at"))
            ),
            events = events
        )
    }

    private fun buildThreadCache(
        context: Context,
        issue: FeedbackIssueThreadCache,
        comments: JSONArray,
        events: JSONArray
    ): FeedbackIssueThreadCache {
        val issueEventId = "issue-${issue.issueNumber}"
        val parsedEvents = ArrayList<FeedbackThreadEvent>()
        issue.events
            .firstOrNull { it.id == issueEventId }
            ?.let(parsedEvents::add)
        for (index in 0 until comments.length()) {
            val item = comments.optJSONObject(index) ?: continue
            val id = item.optLong("id")
            if (id <= 0L) {
                continue
            }
            buildGithubThreadEvent(
                context = context,
                id = "comment-$id",
                item = item,
                fallbackHtmlUrl = item.optString("html_url").trim().ifEmpty { null },
                fallbackCreatedAtMs = parseInstantToMillis(item.optString("created_at"))
            )?.let(parsedEvents::add)
        }
        for (index in 0 until events.length()) {
            val item = events.optJSONObject(index) ?: continue
            val eventName = item.optString("event").trim().lowercase(Locale.ROOT)
            if (eventName != "closed" && eventName != "reopened") {
                continue
            }
            val id = item.optLong("id")
            if (id <= 0L) {
                continue
            }
            val actor = item.optJSONObject("actor")
            parsedEvents += FeedbackThreadEvent(
                id = "state-$id",
                type = FeedbackThreadEventType.STATE_CHANGE,
                authorType = FeedbackThreadAuthorType.SYSTEM,
                authorLabel = actor?.optString("login").orEmpty().ifBlank { "System" },
                authorAvatarUrl = actor?.optString("avatar_url")?.trim()?.ifEmpty { null },
                body = if (eventName == "closed") {
                    "已关闭这个议题"
                } else {
                    "重新打开了这个议题"
                },
                createdAtMs = parseInstantToMillis(item.optString("created_at")),
                htmlUrl = null,
                state = if (eventName == "closed") "closed" else "open"
            )
        }
        val latestState = parsedEvents
            .asReversed()
            .firstOrNull { !it.state.isNullOrBlank() }
            ?.state
            ?: issue.state
        val updatedAtMs = maxOf(
            issue.updatedAtMs,
            parsedEvents.maxOfOrNull { it.createdAtMs } ?: 0L
        )
        return FeedbackIssueThreadCache(
            issueNumber = issue.issueNumber,
            issueUrl = issue.issueUrl,
            title = issue.title,
            state = latestState,
            body = issue.body,
            updatedAtMs = updatedAtMs,
            events = parsedEvents.sortedWith(
                compareBy<FeedbackThreadEvent> { it.createdAtMs }
                    .thenBy { it.id }
            )
        )
    }

    private fun buildGithubThreadEvent(
        context: Context,
        id: String,
        item: JSONObject,
        fallbackHtmlUrl: String?,
        fallbackCreatedAtMs: Long
    ): FeedbackThreadEvent? {
        val rawBody = item.optString("body")
        val user = item.optJSONObject("user")
        val githubLogin = user?.optString("login")?.trim().orEmpty()
        val githubAvatarUrl = user?.optString("avatar_url")?.trim().orEmpty()
        val isProxyReporter = isFeedbackProxyReporterLogin(githubLogin)
        val proxyPayload = if (isProxyReporter) {
            parseProxyPayload(rawBody)
        } else {
            null
        }
        val proxyFooter = if (isProxyReporter) {
            parseFeedbackProxyFooter(rawBody)
        } else {
            null
        }
        val proxyPlayerName = proxyFooter?.playerName
            ?: proxyPayload?.playerName
            ?: ""
        val proxyDeviceLabel = proxyFooter?.deviceLabel
            ?: proxyPayload?.deviceLabel
            ?: parseIssueBodyDeviceLabel(rawBody).takeIf { isProxyReporter }
            ?: ""
        val proxyAuthorIdentity = if (proxyPlayerName.isNotBlank() && proxyDeviceLabel.isNotBlank()) {
            buildFeedbackProxyAuthorIdentity(
                playerName = proxyPlayerName,
                deviceLabel = proxyDeviceLabel
            )
        } else {
            null
        }
        val localProxyAuthorIdentity = buildFeedbackProxyAuthorIdentity(
            playerName = LauncherPreferences.readPlayerName(context),
            deviceLabel = buildFeedbackDeviceLabel()
        )
        val cleanedBody = if (!proxyPayload?.messageText.isNullOrBlank()) {
            proxyPayload.messageText
        } else {
            stripFeedbackProxyMetadataForDisplay(rawBody)
        }
        val attachments = proxyPayload?.attachments ?: emptyList()
        if (cleanedBody.isBlank() && attachments.isEmpty()) {
            return null
        }
        return FeedbackThreadEvent(
            id = id,
            type = FeedbackThreadEventType.COMMENT,
            authorType = when {
                proxyAuthorIdentity == localProxyAuthorIdentity -> FeedbackThreadAuthorType.ME
                isProxyReporter -> FeedbackThreadAuthorType.OTHER
                user == null -> FeedbackThreadAuthorType.OTHER
                else -> FeedbackThreadAuthorType.DEVELOPER
            },
            authorLabel = when {
                isProxyReporter -> proxyPlayerName.ifBlank { "玩家" }
                else -> githubLogin.ifBlank { "Developer" }
            },
            authorAvatarUrl = githubAvatarUrl.takeIf { !isProxyReporter && it.isNotBlank() },
            authorIdentityKey = proxyAuthorIdentity ?: "proxy-$id".takeIf { isProxyReporter },
            authorDeviceLabel = proxyDeviceLabel.takeIf(String::isNotBlank),
            body = cleanedBody,
            createdAtMs = fallbackCreatedAtMs,
            htmlUrl = item.optString("html_url").trim().ifEmpty { fallbackHtmlUrl },
            attachments = attachments
        )
    }

    private fun parseIssueBodyDeviceLabel(rawBody: String): String {
        return issueBodyDeviceLabelRegex.find(rawBody)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
    }

    private fun createGithubClients(context: Context): GithubRequestClients {
        return WattToolkitAcceleratedHttp.createClientPair(
            context = context,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            followRedirects = true,
        )
    }

    private fun requestJsonObject(client: OkHttpClient, requestUrl: String): JSONObject {
        val text = requestText(client, requestUrl)
        val parsed = JSONTokener(text).nextValue()
        return parsed as? JSONObject ?: throw IOException("Invalid JSON object response.")
    }

    private fun requestJsonArray(client: OkHttpClient, requestUrl: String): JSONArray {
        val text = requestText(client, requestUrl)
        val parsed = JSONTokener(text).nextValue()
        return parsed as? JSONArray ?: throw IOException("Invalid JSON array response.")
    }

    private fun requestText(client: OkHttpClient, requestUrl: String): String {
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorText = response.body.bytes().toString(StandardCharsets.UTF_8)
                // Unauthenticated api.github.com calls are the most rate-limited
                // path in the app, so the status code has to survive for the
                // health store to apply a longer cooldown.
                throw response.toGithubMirrorHttpException(errorText.trim())
            }
            return response.body.bytes().toString(StandardCharsets.UTF_8)
        }
    }

    private fun requireValidIssueNumber(issueNumber: Long) {
        if (issueNumber <= 0L) {
            throw IOException("Issue 编号不正确。")
        }
    }

    private fun buildBodyPreview(body: String?): String {
        val normalized = body
            .orEmpty()
            .replace("\r", "\n")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(" ")
            .trim()
        if (normalized.isEmpty()) {
            return ""
        }
        return if (normalized.length <= 140) {
            normalized
        } else {
            normalized.take(137).trimEnd() + "..."
        }
    }

    private fun buildWrappedIOException(prefix: String, cause: Throwable?): IOException {
        val errorMessage = cause?.message ?: "未知错误"
        return IOException(prefix + errorMessage, cause)
    }

    private fun mergeSubscription(
        subscriptions: List<FeedbackIssueSubscription>,
        target: FeedbackIssueSubscription
    ): List<FeedbackIssueSubscription> {
        val items = subscriptions.filterNot { it.issueNumber == target.issueNumber }.toMutableList()
        items += target
        return sortSubscriptions(items)
    }

    private fun sortSubscriptions(
        subscriptions: List<FeedbackIssueSubscription>
    ): List<FeedbackIssueSubscription> {
        return subscriptions.sortedWith(
            compareByDescending<FeedbackIssueSubscription> { it.unread }
                .thenByDescending { it.updatedAtMs }
        )
    }

    private fun parseInstantToMillis(value: String?): Long {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return 0L
        }
        return runCatching { Instant.parse(normalized).toEpochMilli() }.getOrDefault(0L)
    }

    private fun parseProxyPayload(rawBody: String): ProxyPayload? {
        val jsonText = extractFeedbackProxyPayloadJson(rawBody) ?: return null
        return runCatching {
            val root = JSONObject(jsonText)
            val attachmentsArray = root.optJSONArray("attachments") ?: JSONArray()
            val attachments = ArrayList<FeedbackThreadAttachment>(attachmentsArray.length())
            for (index in 0 until attachmentsArray.length()) {
                val item = attachmentsArray.optJSONObject(index) ?: continue
                val url = item.optString("url").trim()
                if (url.isBlank()) {
                    continue
                }
                attachments += FeedbackThreadAttachment(
                    name = item.optString("name").trim(),
                    url = url,
                    mimeType = item.optString("mimeType").trim()
                )
            }
            ProxyPayload(
                origin = root.optString("origin").trim(),
                messageText = root.optString("messageText"),
                playerName = root.optString("playerName").trim(),
                deviceLabel = root.optString("deviceLabel").trim(),
                attachments = attachments
            )
        }.getOrNull()?.takeIf { it.origin.equals("user", ignoreCase = true) }
    }

    private data class ProxyPayload(
        val origin: String,
        val messageText: String,
        val playerName: String,
        val deviceLabel: String,
        val attachments: List<FeedbackThreadAttachment>
    )
}

internal fun mergeSyncedSubscriptions(
    current: List<FeedbackIssueSubscription>,
    remoteByIssueNumber: Map<Long, FeedbackIssueThreadCache>,
    syncedAtMs: Long
): List<FeedbackIssueSubscription> {
    return current.map { subscription ->
        val remote = remoteByIssueNumber[subscription.issueNumber] ?: return@map subscription
        subscription.withRemoteState(remote = remote, syncedAtMs = syncedAtMs)
    }.sortedWith(
        compareByDescending<FeedbackIssueSubscription> { it.unread }
            .thenByDescending { it.updatedAtMs }
    )
}

internal data class FeedbackSubscriptionUpsertResult(
    val subscriptions: List<FeedbackIssueSubscription>,
    val displacedSubscriptions: List<FeedbackIssueSubscription> = emptyList()
)

internal fun upsertSubscriptionWithLimit(
    current: List<FeedbackIssueSubscription>,
    target: FeedbackIssueSubscription,
    maxTrackedSubscriptions: Int = FeedbackIssueSyncService.MAX_TRACKED_SUBSCRIPTIONS
): FeedbackSubscriptionUpsertResult {
    val items = current.filterNot { it.issueNumber == target.issueNumber }.toMutableList()
    val displacedSubscriptions = ArrayList<FeedbackIssueSubscription>()
    while (items.size >= maxTrackedSubscriptions) {
        val oldestFollowed = items.minWithOrNull(
            compareBy<FeedbackIssueSubscription> { followed ->
                followed.followedAtMs.takeIf { it > 0L } ?: Long.MIN_VALUE
            }.thenBy { it.issueNumber }
        ) ?: break
        items.removeAll { it.issueNumber == oldestFollowed.issueNumber }
        displacedSubscriptions += oldestFollowed
    }
    items += target
    return FeedbackSubscriptionUpsertResult(
        subscriptions = items.sortedWith(
            compareByDescending<FeedbackIssueSubscription> { it.unread }
                .thenByDescending { it.updatedAtMs }
        ),
        displacedSubscriptions = displacedSubscriptions
    )
}

internal fun FeedbackIssueSubscription.withRemoteState(
    remote: FeedbackIssueThreadCache,
    syncedAtMs: Long,
    markViewed: Boolean = false
): FeedbackIssueSubscription {
    val nextViewedAtMs = if (markViewed) {
        maxOf(lastViewedAtMs, remote.lastEventAtMs)
    } else {
        lastViewedAtMs
    }
    return copy(
        issueUrl = remote.issueUrl,
        title = remote.title,
        state = remote.state,
        unread = remote.lastEventAtMs > nextViewedAtMs,
        lastSyncedAtMs = syncedAtMs,
        lastViewedAtMs = nextViewedAtMs,
        updatedAtMs = remote.updatedAtMs
    )
}

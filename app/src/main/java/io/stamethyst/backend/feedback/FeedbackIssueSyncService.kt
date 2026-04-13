package io.stamethyst.backend.feedback

import android.content.Context
import io.stamethyst.BuildConfig
import io.stamethyst.backend.github.GithubAcceleratedHttp
import io.stamethyst.backend.update.GithubMirrorFallback
import io.stamethyst.backend.update.UpdateMirrorManager
import io.stamethyst.backend.update.UpdateSource
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale
import okhttp3.OkHttpClient
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
    private val proxyCommentRegex =
        Regex("""<!--\s*sts-feedback-proxy:(\{.*?\})\s*-->""", setOf(RegexOption.DOT_MATCHES_ALL))

    fun subscribeToIssue(context: Context, issueNumberText: String): FeedbackIssueSubscription {
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
    ): FeedbackIssueSubscription {
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
        var savedSubscription: FeedbackIssueSubscription? = null
        FeedbackIssueLocalStore.updateSubscriptions(context) { current ->
            val existingSubscription = current.firstOrNull { it.issueNumber == issueNumber }
            val subscription = FeedbackIssueSubscription(
                issueNumber = issueNumber,
                issueUrl = cache.issueUrl,
                title = cache.title,
                state = cache.state,
                unread = false,
                lastSyncedAtMs = now,
                lastViewedAtMs = maxOf(existingSubscription?.lastViewedAtMs ?: 0L, cache.lastEventAtMs),
                updatedAtMs = cache.updatedAtMs
            )
            savedSubscription = subscription
            mergeSubscription(current, subscription)
        }
        return checkNotNull(savedSubscription)
    }

    fun subscribeToIssue(context: Context, issueNumber: Long): FeedbackIssueSubscription {
        requireValidIssueNumber(issueNumber)
        val remote = fetchRemoteIssue(context, issueNumber)
        FeedbackIssueLocalStore.saveIssueCache(context, remote)
        val now = System.currentTimeMillis()
        var savedSubscription: FeedbackIssueSubscription? = null
        FeedbackIssueLocalStore.updateSubscriptions(context) { current ->
            val existing = current.firstOrNull { it.issueNumber == issueNumber }
            val subscription = FeedbackIssueSubscription(
                issueNumber = issueNumber,
                issueUrl = remote.issueUrl,
                title = remote.title,
                state = remote.state,
                unread = false,
                lastSyncedAtMs = now,
                lastViewedAtMs = maxOf(existing?.lastViewedAtMs ?: 0L, remote.lastEventAtMs),
                updatedAtMs = remote.updatedAtMs
            )
            savedSubscription = subscription
            mergeSubscription(current, subscription)
        }
        return checkNotNull(savedSubscription)
    }

    fun listIssues(
        context: Context,
        page: Int,
        pageSize: Int = DEFAULT_ISSUE_PAGE_SIZE
    ): FeedbackIssueBrowsePage {
        if (page <= 0) {
            throw IOException("议题页码不正确。")
        }
        if (pageSize <= 0) {
            throw IOException("议题分页大小不正确。")
        }
        val client = createGithubClient(context)
        val preferred = UpdateMirrorManager.current(context)
        try {
            return GithubMirrorFallback.run(preferred) { source ->
                fetchIssuePageFromSource(client, source, page, pageSize)
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
        val client = createGithubClient(context)
        initialSubscriptions.forEach { subscription ->
            val remote = fetchRemoteIssue(context, subscription.issueNumber, client)
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
        client: OkHttpClient = createGithubClient(context),
    ): FeedbackIssueThreadCache {
        val preferred = UpdateMirrorManager.current(context)
        return try {
            GithubMirrorFallback.run(preferred) { source ->
                fetchRemoteIssueFromSource(client, source, issueNumber)
            }.value
        } catch (error: Throwable) {
            throw buildWrappedIOException("无法同步 Issue #$issueNumber：", error)
        }
    }

    private fun fetchIssuePageFromSource(
        client: OkHttpClient,
        source: UpdateSource,
        page: Int,
        pageSize: Int
    ): FeedbackIssueBrowsePage {
        val issues = requestJsonArray(
            client,
            source.buildUrl(
                "$GITHUB_API_BASE/repos/${BuildConfig.FEEDBACK_GITHUB_OWNER}/${BuildConfig.FEEDBACK_GITHUB_REPO}/issues" +
                    "?state=all&sort=updated&direction=desc&per_page=$pageSize&page=$page"
            )
        )
        val items = ArrayList<FeedbackIssueBrowseItem>(issues.length())
        for (index in 0 until issues.length()) {
            val item = issues.optJSONObject(index) ?: continue
            if (item.has("pull_request")) {
                continue
            }
            val issueNumber = item.optLong("number")
            if (issueNumber <= 0L) {
                continue
            }
            items += FeedbackIssueBrowseItem(
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
        return FeedbackIssueBrowsePage(
            issues = items,
            nextPage = page + 1,
            hasMore = issues.length() >= pageSize
        )
    }

    private fun fetchRemoteIssueFromSource(
        client: OkHttpClient,
        source: UpdateSource,
        issueNumber: Long
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
        return buildThreadCache(issue, comments, events)
    }

    private fun buildThreadCache(
        issue: JSONObject,
        comments: JSONArray,
        events: JSONArray
    ): FeedbackIssueThreadCache {
        val issueNumber = issue.optLong("number")
        val issueUrl = issue.optString("html_url").trim().ifEmpty { buildIssueUrl(issueNumber) }
        val parsedEvents = ArrayList<FeedbackThreadEvent>()
        for (index in 0 until comments.length()) {
            val item = comments.optJSONObject(index) ?: continue
            val id = item.optLong("id")
            if (id <= 0L) {
                continue
            }
            val rawBody = item.optString("body")
            val proxyPayload = parseProxyPayload(rawBody)
            val cleanedBody = if (!proxyPayload?.messageText.isNullOrBlank()) {
                proxyPayload.messageText
            } else {
                stripProxyPayload(rawBody).trim()
            }
            val user = item.optJSONObject("user")
            parsedEvents += FeedbackThreadEvent(
                id = "comment-$id",
                type = FeedbackThreadEventType.COMMENT,
                authorType = when {
                    proxyPayload != null -> FeedbackThreadAuthorType.ME
                    user == null -> FeedbackThreadAuthorType.OTHER
                    else -> FeedbackThreadAuthorType.DEVELOPER
                },
                authorLabel = when {
                    proxyPayload != null -> proxyPayload.playerName.ifBlank { "我" }
                    else -> user?.optString("login").orEmpty().ifBlank { "Developer" }
                },
                body = cleanedBody,
                createdAtMs = parseInstantToMillis(item.optString("created_at")),
                htmlUrl = item.optString("html_url").trim().ifEmpty { null },
                attachments = proxyPayload?.attachments ?: emptyList()
            )
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
        val updatedAtMs = maxOf(
            parseInstantToMillis(issue.optString("updated_at")),
            parseInstantToMillis(issue.optString("created_at")),
            parsedEvents.maxOfOrNull { it.createdAtMs } ?: 0L
        )
        return FeedbackIssueThreadCache(
            issueNumber = issueNumber,
            issueUrl = issueUrl,
            title = issue.optString("title").trim(),
            state = issue.optString("state").trim().ifEmpty { "open" },
            body = issue.optString("body"),
            updatedAtMs = updatedAtMs,
            events = parsedEvents.sortedWith(
                compareBy<FeedbackThreadEvent> { it.createdAtMs }
                    .thenBy { it.id }
            )
        )
    }

    private fun createGithubClient(context: Context): OkHttpClient {
        return GithubAcceleratedHttp.createClient(
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
                throw IOException("HTTP ${response.code} ${errorText.trim()}".trim())
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
        return items.sortedWith(
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

    private fun stripProxyPayload(rawBody: String): String {
        return rawBody.replace(proxyCommentRegex, "").trim()
    }

    private fun parseProxyPayload(rawBody: String): ProxyPayload? {
        val match = proxyCommentRegex.find(rawBody) ?: return null
        val jsonText = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (jsonText.isEmpty()) {
            return null
        }
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
                attachments = attachments
            )
        }.getOrNull()?.takeIf { it.origin.equals("user", ignoreCase = true) }
    }

    private data class ProxyPayload(
        val origin: String,
        val messageText: String,
        val playerName: String,
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

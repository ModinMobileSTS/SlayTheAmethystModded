package io.stamethyst.backend.feedback

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object FeedbackIssueLocalStore {
    private const val ROOT_DIR_NAME = "feedback"
    private const val SUBSCRIPTIONS_FILE_NAME = "subscriptions.json"
    private const val ISSUES_DIR_NAME = "issues"
    private val subscriptionsLock = Any()

    fun loadSubscriptions(context: Context): List<FeedbackIssueSubscription> {
        synchronized(subscriptionsLock) {
            return loadSubscriptionsLocked(subscriptionsFile(context))
        }
    }

    fun saveSubscriptions(
        context: Context,
        subscriptions: List<FeedbackIssueSubscription>
    ) {
        synchronized(subscriptionsLock) {
            writeSubscriptionsLocked(subscriptionsFile(context), subscriptions)
        }
    }

    fun updateSubscriptions(
        context: Context,
        transform: (List<FeedbackIssueSubscription>) -> List<FeedbackIssueSubscription>
    ): List<FeedbackIssueSubscription> {
        synchronized(subscriptionsLock) {
            val file = subscriptionsFile(context)
            val current = loadSubscriptionsLocked(file)
            val updated = normalizeSubscriptions(transform(current))
            writeSubscriptionsLocked(file, updated)
            return updated
        }
    }

    fun loadIssueCache(
        context: Context,
        issueNumber: Long
    ): FeedbackIssueThreadCache? {
        val file = issueCacheFile(context, issueNumber)
        val root = readJsonObject(file) ?: return null
        val issueUrl = root.optString("issueUrl").trim()
        if (issueUrl.isBlank()) {
            return null
        }
        val eventsArray = root.optJSONArray("events") ?: JSONArray()
        val events = ArrayList<FeedbackThreadEvent>(eventsArray.length())
        for (index in 0 until eventsArray.length()) {
            val item = eventsArray.optJSONObject(index) ?: continue
            val attachmentsArray = item.optJSONArray("attachments") ?: JSONArray()
            val attachments = ArrayList<FeedbackThreadAttachment>(attachmentsArray.length())
            for (attachmentIndex in 0 until attachmentsArray.length()) {
                val attachment = attachmentsArray.optJSONObject(attachmentIndex) ?: continue
                val url = attachment.optString("url").trim()
                if (url.isBlank()) {
                    continue
                }
                attachments += FeedbackThreadAttachment(
                    name = attachment.optString("name").trim(),
                    url = url,
                    mimeType = attachment.optString("mimeType").trim()
                )
            }
            events += FeedbackThreadEvent(
                id = item.optString("id").trim(),
                type = item.optString("type").trim()
                    .let { value ->
                        FeedbackThreadEventType.entries.firstOrNull { it.name == value }
                    } ?: FeedbackThreadEventType.COMMENT,
                authorType = item.optString("authorType").trim()
                    .let { value ->
                        FeedbackThreadAuthorType.entries.firstOrNull { it.name == value }
                    } ?: FeedbackThreadAuthorType.OTHER,
                authorLabel = item.optString("authorLabel").trim().ifEmpty { "Unknown" },
                body = item.optString("body"),
                createdAtMs = item.optLong("createdAtMs"),
                htmlUrl = item.optString("htmlUrl").trim().ifEmpty { null },
                attachments = attachments,
                state = item.optString("state").trim().ifEmpty { null }
            )
        }
        return FeedbackIssueThreadCache(
            issueNumber = root.optLong("issueNumber"),
            issueUrl = issueUrl,
            title = root.optString("title").trim(),
            state = root.optString("state").trim().ifEmpty { "open" },
            body = root.optString("body"),
            updatedAtMs = root.optLong("updatedAtMs"),
            events = events.sortedBy { it.createdAtMs }
        )
    }

    fun saveIssueCache(
        context: Context,
        cache: FeedbackIssueThreadCache
    ) {
        val root = JSONObject().apply {
            put("issueNumber", cache.issueNumber)
            put("issueUrl", cache.issueUrl)
            put("title", cache.title)
            put("state", cache.state)
            put("body", cache.body)
            put("updatedAtMs", cache.updatedAtMs)
            put(
                "events",
                JSONArray().apply {
                    cache.events.forEach { event ->
                        put(
                            JSONObject().apply {
                                put("id", event.id)
                                put("type", event.type.name)
                                put("authorType", event.authorType.name)
                                put("authorLabel", event.authorLabel)
                                put("body", event.body)
                                put("createdAtMs", event.createdAtMs)
                                put("htmlUrl", event.htmlUrl.orEmpty())
                                put("state", event.state.orEmpty())
                                put(
                                    "attachments",
                                    JSONArray().apply {
                                        event.attachments.forEach { attachment ->
                                            put(
                                                JSONObject().apply {
                                                    put("name", attachment.name)
                                                    put("url", attachment.url)
                                                    put("mimeType", attachment.mimeType)
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }
        writeJsonObject(issueCacheFile(context, cache.issueNumber), root)
    }

    fun deleteIssueCache(context: Context, issueNumber: Long) {
        val file = issueCacheFile(context, issueNumber)
        if (file.exists()) {
            file.delete()
        }
    }

    private fun subscriptionsFile(context: Context): File {
        return File(ensureRootDir(context), SUBSCRIPTIONS_FILE_NAME)
    }

    private fun issueCacheFile(context: Context, issueNumber: Long): File {
        val dir = File(ensureRootDir(context), ISSUES_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "$issueNumber.json")
    }

    private fun ensureRootDir(context: Context): File {
        val dir = File(context.filesDir, ROOT_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun loadSubscriptionsLocked(file: File): List<FeedbackIssueSubscription> {
        val root = readJsonObject(file) ?: return emptyList()
        val array = root.optJSONArray("subscriptions") ?: JSONArray()
        val items = ArrayList<FeedbackIssueSubscription>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            items += FeedbackIssueSubscription(
                issueNumber = item.optLong("issueNumber"),
                issueUrl = item.optString("issueUrl").trim(),
                title = item.optString("title").trim(),
                state = item.optString("state").trim().ifEmpty { "open" },
                unread = item.optBoolean("unread", false),
                followedAtMs = item.optLong("followedAtMs").takeIf { it > 0L }
                    ?: item.optLong("lastSyncedAtMs").takeIf { it > 0L }
                    ?: item.optLong("updatedAtMs"),
                lastSyncedAtMs = item.optLong("lastSyncedAtMs"),
                lastViewedAtMs = item.optLong("lastViewedAtMs"),
                updatedAtMs = item.optLong("updatedAtMs")
            )
        }
        return normalizeSubscriptions(items)
    }

    private fun writeSubscriptionsLocked(
        file: File,
        subscriptions: List<FeedbackIssueSubscription>
    ) {
        val normalized = normalizeSubscriptions(subscriptions)
        val root = JSONObject().apply {
            put(
                "subscriptions",
                JSONArray().apply {
                    normalized.forEach { subscription ->
                        put(
                            JSONObject().apply {
                                put("issueNumber", subscription.issueNumber)
                                put("issueUrl", subscription.issueUrl)
                                put("title", subscription.title)
                                put("state", subscription.state)
                                put("unread", subscription.unread)
                                put("followedAtMs", subscription.followedAtMs)
                                put("lastSyncedAtMs", subscription.lastSyncedAtMs)
                                put("lastViewedAtMs", subscription.lastViewedAtMs)
                                put("updatedAtMs", subscription.updatedAtMs)
                            }
                        )
                    }
                }
            )
        }
        writeJsonObject(file, root)
    }

    private fun normalizeSubscriptions(
        subscriptions: List<FeedbackIssueSubscription>
    ): List<FeedbackIssueSubscription> {
        return subscriptions
            .filter { it.issueNumber > 0L && it.issueUrl.isNotBlank() }
            .sortedWith(
                compareByDescending<FeedbackIssueSubscription> { it.unread }
                    .thenByDescending { it.updatedAtMs }
            )
    }

    private fun readJsonObject(file: File): JSONObject? {
        if (!file.isFile) {
            return null
        }
        return try {
            val text = file.readText(StandardCharsets.UTF_8).trim()
            if (text.isEmpty()) {
                null
            } else {
                JSONTokener(text).nextValue() as? JSONObject
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeJsonObject(file: File, value: JSONObject) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tempFile = File(
            parent ?: throw IOException("JSON target file has no parent directory"),
            ".${file.name}.${System.nanoTime()}.tmp"
        )
        try {
            FileOutputStream(tempFile, false).use { output ->
                output.write(value.toString(2).toByteArray(StandardCharsets.UTF_8))
                output.write('\n'.code)
                output.fd.sync()
            }
            if (file.exists() && !file.delete()) {
                throw IOException("Failed to replace JSON file: ${file.absolutePath}")
            }
            if (!tempFile.renameTo(file)) {
                throw IOException("Failed to move JSON file into place: ${file.absolutePath}")
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}

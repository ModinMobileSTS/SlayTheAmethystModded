package io.stamethyst.backend.workshop

import android.util.Log
import io.stamethyst.backend.github.AcceleratedResponseValidator
import io.stamethyst.backend.github.HttpRetrySafety
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

/** Read-only community requests, using WorkshopService's existing cookies, routing and language. */
internal class WorkshopSocialService(
    private val client: Call.Factory,
) {
    suspend fun authorPage(
        appId: UInt,
        author: WorkshopAuthor,
        page: Int,
        language: SteamLanguagePreference,
    ): WorkshopAuthorPage = withContext(Dispatchers.IO) {
        require(page in 1..MAX_PAGE)
        val profile = WorkshopSocialParser.author(author.name, author.profileUrl) ?: error("Invalid Steam author profile")
        val url = profile.profileUrl.toHttpUrl().newBuilder()
            .addPathSegment("myworkshopfiles").addPathSegment("")
            .addQueryParameter("appid", appId.toString())
            .addQueryParameter("browsefilter", "myfiles")
            .addQueryParameter("sortmethod", "creationorder")
            .addQueryParameter("p", page.toString())
            .addQueryParameter("numperpage", AUTHOR_PAGE_SIZE.toString())
            .addQueryParameter("l", language.requestValue).build()
        fetch(url, language) { WorkshopSocialParser.authorPage(it, appId, author, page, AUTHOR_PAGE_SIZE) }
    }

    suspend fun discussions(
        publishedFileId: ULong,
        language: SteamLanguagePreference,
        previous: WorkshopDiscussionPage? = null,
        page: Int = 1,
    ): WorkshopDiscussionPage = withContext(Dispatchers.IO) {
        require(page in 1..MAX_PAGE && publishedFileId > 0uL)
        if (page == 1) {
            val url = "https://steamcommunity.com/sharedfiles/filedetails/discussions/$publishedFileId".toHttpUrl()
                .newBuilder().addQueryParameter("l", language.requestValue).build()
            fetch(url, language) { WorkshopSocialParser.discussionPage(it, publishedFileId) }
        } else {
            val first = previous ?: error("Steam forum context was missing")
            require(first.publishedFileId == publishedFileId && first.forum.feature == publishedFileId.toString())
            val forum = first.forum
            val url = "https://steamcommunity.com/".toHttpUrl().newBuilder()
                .addPathSegments("forum/${forum.owner}/PublishedFile/render/${forum.feature}/")
                .addQueryParameter("start", ((page - 1).toLong() * first.pageSize).toString())
                .addQueryParameter("count", first.pageSize.toString())
                .addQueryParameter("l", language.requestValue).build()
            fetch(url, language) { WorkshopSocialParser.discussionPageJson(it, first) }
        }
    }

    suspend fun discussionDetails(
        publishedFileId: ULong,
        topic: WorkshopDiscussionSummary,
        language: SteamLanguagePreference,
    ): WorkshopDiscussionDetails = withContext(Dispatchers.IO) {
        val raw = WorkshopSocialParser.topicUrl(topic.url, publishedFileId, topic.id) ?: error("Invalid Steam discussion URL")
        val url = raw.toHttpUrl().newBuilder().addQueryParameter("l", language.requestValue).build()
        fetch(url, language) { WorkshopSocialParser.discussionDetails(it, publishedFileId, topic) }
    }

    suspend fun replies(
        context: WorkshopDiscussionReplyContext,
        page: Int,
        pageSize: Int,
        language: SteamLanguagePreference,
    ): WorkshopDiscussionReplyPage = withContext(Dispatchers.IO) {
        require(page in 1..MAX_PAGE && pageSize in 1..100)
        require(listOf(context.owner, context.forumId, context.topicId).all { it.toULongOrNull()?.let { id -> id > 0uL } == true })
        val url = "https://steamcommunity.com/".toHttpUrl().newBuilder()
            .addPathSegments("comment/ForumTopic/render/${context.owner}/${context.forumId}/")
            .addQueryParameter("l", language.requestValue).build()
        val body = FormBody.Builder()
            .add("start", ((page - 1).toLong() * pageSize).toString())
            .add("count", pageSize.toString())
            .add("feature2", context.topicId)
            .add("oldestfirst", context.oldestFirst.toString())
            .apply { context.extendedData?.takeIf(String::isNotBlank)?.let { add("extended_data", it) } }
            .build()
        fetch(url, language, body) { WorkshopSocialParser.replyPageJson(it, context) }
    }

    private suspend fun <T> fetch(
        url: HttpUrl,
        language: SteamLanguagePreference,
        body: FormBody? = null,
        parse: (String) -> T,
    ): T {
        val request = Request.Builder().url(url)
            .tag(HttpRetrySafety::class.java, HttpRetrySafety.READ_ONLY)
            .tag(AcceleratedResponseValidator::class.java, AcceleratedResponseValidator { response ->
                // Reject malformed candidates inside the existing route executor, so a mirror's
                // HTTP-200 error/login page can fall back instead of being marked successful.
                try {
                    parseResponse(response, parse)
                } catch (error: IOException) {
                    throw error
                } catch (error: Exception) {
                    throw IOException("Invalid Steam community response: ${error.message}", error)
                }
            })
            .header("Accept-Language", language.acceptLanguageValue)
            .header("User-Agent", "Mozilla/5.0 SlayTheAmethyst-Workshop")
            .apply {
                if (body != null) post(body)
                if (url.encodedPath.contains("/render/")) header("X-Requested-With", "XMLHttpRequest")
            }.build()
        val response = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, unused, _ -> unused.close() }
                }
            })
        }
        return response.use {
            // Steam may canonicalize sharedfiles/ to workshop/ (and vanity profiles to numeric
            // profiles). URL equality is not a content validity check. Every caller parses the
            // expected page/context and verifies its file/topic/thread identity below; homepage,
            // login HTML, and JSON for a different thread must still fail those checks.
            if (it.request.url.encodedPath != url.encodedPath) {
                Log.d("WorkshopSocial", "Community endpoint changed requestedPath=${url.encodedPath} resolvedPath=${it.request.url.encodedPath}")
            }
            parseResponse(it, parse)
        }
    }

    private fun <T> parseResponse(response: Response, parse: (String) -> T): T {
        check(response.code != 429) { "Steam community rate limited this request (429)" }
        check(response.isSuccessful) { "Steam community request failed: ${response.code}" }
        check(response.body.contentLength() <= MAX_RESPONSE_BYTES) { "Steam community response is too large" }
        // A bounded, non-consuming peek works for candidate validation and ordinary OkHttp alike.
        val payload = response.peekBody(MAX_RESPONSE_BYTES + 1).use { body ->
            check(body.contentLength() <= MAX_RESPONSE_BYTES) { "Steam community response is too large" }
            body.string()
        }
        check(payload.isNotBlank()) { "Steam returned an empty response" }
        return parse(payload)
    }

    private companion object {
        const val AUTHOR_PAGE_SIZE = 30
        const val MAX_PAGE = 100_000
        const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
    }
}

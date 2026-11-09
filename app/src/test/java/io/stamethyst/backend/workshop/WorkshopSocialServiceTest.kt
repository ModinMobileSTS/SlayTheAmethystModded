package io.stamethyst.backend.workshop

import io.stamethyst.backend.github.HttpRetrySafety
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkshopSocialServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var service: WorkshopSocialService
    private val f = WorkshopSocialFixtures
    private val language = SteamLanguagePreference.SimplifiedChinese
    private val retryTags = mutableListOf<HttpRetrySafety?>()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).addInterceptor { chain ->
            val request = chain.request()
            retryTags += request.tag(HttpRetrySafety::class.java)
            val target = server.url(request.url.encodedPath + request.url.encodedQuery?.let { "?$it" }.orEmpty())
            val response = chain.proceed(request.newBuilder().url(target).build())
            // Like RoutedHttpTransport, expose the final logical URL, not the local test hop.
            response.newBuilder().request(request.newBuilder().url(
                "https://steamcommunity.com".toHttpUrl().newBuilder()
                    .encodedPath(response.request.url.encodedPath).encodedQuery(response.request.url.encodedQuery).build(),
            ).build()).build()
        }.build()
        service = WorkshopSocialService(client)
    }

    @After fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.close()
    }

    @Test fun canonicalSharedfilesToWorkshopRedirectStillLoadsVerifiedForum() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/workshop/filedetails/discussions/${f.FILE_ID}/?l=schinese").build())
        server.enqueue(MockResponse.Builder().body(f.forumPage()).build())
        val result = service.discussions(f.FILE_ID.toULong(), language)
        assertEquals("Bug reports", result.topics.single().title)
        assertEquals(2, server.requestCount)
        assertEquals("/sharedfiles/filedetails/discussions/${f.FILE_ID}", server.takeRequest().url.encodedPath)
        assertEquals("/workshop/filedetails/discussions/${f.FILE_ID}/", server.takeRequest().url.encodedPath)
    }

    @Test fun redirectedHomepageAndLoginHtmlFailInsteadOfBecomingEmptyDiscussionLists() = runBlocking {
        for (path in listOf("/", "/login/home/")) {
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", path).build())
            server.enqueue(MockResponse.Builder().body("<html><h1>Sign in to Steam</h1></html>").build())
            val failure = runCatching { service.discussions(f.FILE_ID.toULong(), language) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message.orEmpty().contains("context was missing"))
        }
    }

    @Test fun lazilyLoadsListThenTopicThenRepliesUsingTheirOwnContext() = runBlocking {
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse.Builder().body(f.forumPage(total = 31)).build())
        val first = service.discussions(f.FILE_ID.toULong(), language)
        assertEquals(1, server.requestCount)
        val initialRequest = server.takeRequest()
        assertEquals("schinese", initialRequest.url.queryParameter("l"))
        assertEquals(language.acceptLanguageValue, initialRequest.headers["Accept-Language"])

        server.enqueue(MockResponse.Builder().body(f.forumJson()).build())
        assertEquals(2, service.discussions(f.FILE_ID.toULong(), language, first, page = 2).page)
        val listRequest = server.takeRequest()
        assertEquals("/forum/${f.FORUM_OWNER}/PublishedFile/render/${f.FILE_ID}/", listRequest.url.encodedPath)
        assertEquals("15", listRequest.url.queryParameter("start"))
        assertEquals("15", listRequest.url.queryParameter("count"))

        server.enqueue(MockResponse.Builder().body(f.topicPage()).build())
        val details = service.discussionDetails(f.FILE_ID.toULong(), first.topics.single(), language)
        assertEquals(3, server.requestCount)
        assertEquals("/workshop/filedetails/discussion/${f.FILE_ID}/${f.TOPIC_ID}/", server.takeRequest().url.encodedPath)

        server.enqueue(MockResponse.Builder().body(f.repliesJson()).build())
        val replies = service.replies(details.replyContext, 2, details.firstReplyPage.pageSize, language)
        assertEquals(2, replies.page)
        val replyRequest = server.takeRequest()
        assertEquals("POST", replyRequest.method)
        assertEquals("/comment/ForumTopic/render/${f.REPLY_OWNER}/${f.FORUM_ID}/", replyRequest.url.encodedPath)
        val body = replyRequest.body!!.utf8()
        assertTrue(body.contains("feature2=${f.TOPIC_ID}"))
        assertTrue(body.contains("start=15"))
        assertTrue(body.contains("count=15"))
        assertTrue(body.contains("extended_data="))
        assertEquals("XMLHttpRequest", replyRequest.headers["X-Requested-With"])
        assertTrue(retryTags.all { it == HttpRetrySafety.READ_ONLY })
    }

    @Test fun vanityAuthorCanonicalRedirectLoadsAllPublicWorksInCurrentGame() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/profiles/${f.AUTHOR_ID}/myworkshopfiles/?appid=646570").build())
        server.enqueue(MockResponse.Builder().body(f.authorPage()).build())
        val author = WorkshopAuthor("Bug Kiooeht", "https://steamcommunity.com/id/example", f.AUTHOR_ID)
        val result = service.authorPage(646570u, author, 1, language)
        assertEquals(18, result.total)
        assertFalse(result.hasNextPage)
        assertEquals(author, result.items.single().author)
        val request = server.takeRequest()
        assertEquals("/id/example/myworkshopfiles/", request.url.encodedPath)
        assertEquals("646570", request.url.queryParameter("appid"))
        assertEquals("creationorder", request.url.queryParameter("sortmethod"))
        assertEquals("myfiles", request.url.queryParameter("browsefilter"))
    }

    @Test fun wrongFileAndRateLimitedResponseStayFailures() = runBlocking {
        server.enqueue(MockResponse.Builder().body(f.forumPage(file = "42")).build())
        assertTrue(runCatching { service.discussions(f.FILE_ID.toULong(), language) }.isFailure)
        server.enqueue(MockResponse.Builder().code(429).body("Try later").build())
        val failure = runCatching { service.discussions(f.FILE_ID.toULong(), language) }.exceptionOrNull()
        assertTrue(failure!!.message.orEmpty().contains("429"))
    }
}

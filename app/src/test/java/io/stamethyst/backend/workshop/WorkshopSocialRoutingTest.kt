package io.stamethyst.backend.workshop

import io.stamethyst.backend.github.ExperimentalGithubDirectAccessRuntime
import io.stamethyst.backend.github.PersistedWattToolkitGithubRoute
import io.stamethyst.backend.github.WattToolkitGithubRoute
import io.stamethyst.backend.github.WattToolkitGithubRouteResolver
import io.stamethyst.backend.github.WattToolkitGithubRouteStore
import io.stamethyst.backend.github.WattToolkitRouteProfile
import io.stamethyst.backend.github.withWattRouting
import io.stamethyst.backend.network.AccelerationStrategy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Exercise the same redirect and candidate-validation executor used by the Steam transport. */
class WorkshopSocialRoutingTest {
    private lateinit var server: MockWebServer
    private val clients = mutableListOf<OkHttpClient>()
    private val officialRequests = CopyOnWriteArrayList<Request>()
    private val f = WorkshopSocialFixtures
    private val language = SteamLanguagePreference.SimplifiedChinese

    @Before fun setUp() { server = MockWebServer().apply { start() } }

    @After fun tearDown() {
        clients.forEach { it.dispatcher.executorService.shutdown(); it.connectionPool.evictAll() }
        server.close()
    }

    @Test fun routedCanonicalRedirectRetainsContextValidationWithoutOfficialFallback() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/workshop/filedetails/discussions/${f.FILE_ID}/?l=schinese").build())
        server.enqueue(MockResponse.Builder().body(f.forumPage()).build())
        val result = service(f.forumPage()).discussions(f.FILE_ID.toULong(), language)
        assertEquals(f.TOPIC_ID, result.topics.single().id)
        assertEquals(0, officialRequests.size)
        assertEquals(2, server.requestCount)
        assertEquals("/sharedfiles/filedetails/discussions/${f.FILE_ID}", server.takeRequest().url.encodedPath)
        assertEquals("/workshop/filedetails/discussions/${f.FILE_ID}/", server.takeRequest().url.encodedPath)
    }

    @Test fun malformedMirrorHtmlFallsBackBeforeBeingRecordedAsSuccessful() = runBlocking {
        server.enqueue(MockResponse.Builder().body("<html>Sign in to Steam</html>").build())
        val result = service(f.forumPage()).discussions(f.FILE_ID.toULong(), language)
        assertEquals(f.TOPIC_ID, result.topics.single().id)
        assertEquals(1, server.requestCount)
        assertEquals("/sharedfiles/filedetails/discussions/${f.FILE_ID}", officialRequests.single().url.encodedPath)
    }

    @Test fun differentWorkshopMirrorResponseFallsBackToMatchingForum() = runBlocking {
        server.enqueue(MockResponse.Builder().body(f.forumPage(file = "42")).build())
        val result = service(f.forumPage()).discussions(f.FILE_ID.toULong(), language)
        assertEquals(f.FILE_ID, result.forum.feature)
        assertEquals(1, officialRequests.size)
    }

    @Test fun readOnlyReplyPostFallsBackWhenMirrorReturnsWrongThread() = runBlocking {
        val details = WorkshopSocialParser.discussionDetails(f.topicPage(), f.FILE_ID.toULong(), f.topic)
        server.enqueue(MockResponse.Builder().body(f.repliesJson(name = "PublishedFile_Public_42")).build())
        val result = service(f.repliesJson()).replies(details.replyContext, 2, 15, language)
        assertEquals(2, result.page)
        val mirrorRequest = server.takeRequest()
        val officialRequest = officialRequests.single()
        assertEquals("POST", mirrorRequest.method)
        assertEquals("POST", officialRequest.method)
        assertEquals(mirrorRequest.url.encodedPath, officialRequest.url.encodedPath)
        val buffer = okio.Buffer()
        officialRequest.body!!.writeTo(buffer)
        assertEquals(mirrorRequest.body!!.utf8(), buffer.readUtf8())
    }

    private fun service(officialPayload: String): WorkshopSocialService {
        val target = server.url("/").toString()
        val route = WattToolkitGithubRoute(setOf("steamcommunity.com"), listOf(target))
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = WattToolkitRouteProfile("workshop-test", "unused.json", route.logicalHosts, listOf(target)),
            routeStore = object : WattToolkitGithubRouteStore {
                override fun load(): PersistedWattToolkitGithubRoute? = null
                override fun save(route: PersistedWattToolkitGithubRoute) = Unit
                override fun clear() = Unit
            },
            backgroundExecutor = Executor { },
            bootstrapRouteProvider = { route },
            accelerationStrategyProvider = { AccelerationStrategy.RMBGAME_FIRST },
        )
        val direct = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build().also(clients::add)
        val official = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).addInterceptor { chain ->
            officialRequests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(officialPayload.toResponseBody()).build()
        }.build().also(clients::add)
        return WorkshopSocialService(official.withWattRouting(
            ExperimentalGithubDirectAccessRuntime(listOf(resolver), direct.hostnameVerifier, direct),
        ))
    }
}

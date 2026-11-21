package io.stamethyst.backend.steamcloud

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkshopTopicIpv4RouteTest {
    private val topic = "https://steamcommunity.com/workshop/filedetails/discussion/1605060445/3818530649090541074/"

    @Test fun resolvesOnlyPublicARecordsForTheFixedSteamHop() = MockWebServer().use { server ->
        server.start()
        server.enqueue(MockResponse.Builder().body("""
            {"Status":0,"Answer":[
              {"type":5,"data":"steamcommunity-a.akamaihd.net."},
              {"type":28,"data":"2001:db8::1"},
              {"type":1,"data":"127.0.0.1"},
              {"type":1,"data":"10.1.2.3"},
              {"type":1,"data":"169.254.169.254"},
              {"type":1,"data":"100.64.0.1"},
              {"type":1,"data":"23.223.17.199"},
              {"type":1,"data":"23.223.17.199"},
              {"type":1,"data":"23.223.17.208"}
            ]}
        """.trimIndent()).build())
        val resolver = WorkshopTopicIpv4Route(OkHttpClient(), server.url("/resolve"))

        val route = requireNotNull(resolver.resolve(Request.Builder().url(topic).build()))
        assertEquals(listOf("23.223.17.199", "23.223.17.208"), route.resolvedForwardAddresses.map { it.hostAddress })
        assertEquals("steamstore-a.akamaihd.net", route.fakeServerName)
        assertEquals(listOf("https://steamcommunity.rmbgame.net"), route.forwardTargets)
        val query = server.takeRequest()
        assertEquals("steamcommunity.rmbgame.net", query.url.queryParameter("name"))
        assertEquals("A", query.url.queryParameter("type"))
        assertEquals(null, query.headers["Cookie"])
    }

    @Test fun doesNotResolveListReplyOrOtherHosts() = MockWebServer().use { server ->
        server.start()
        val resolver = WorkshopTopicIpv4Route(OkHttpClient(), server.url("/resolve"))
        for (url in listOf(
            "https://steamcommunity.com/workshop/filedetails/discussions/1605060445/",
            "https://steamcommunity.com/comment/ForumTopic/render/1/2/",
            topic.replace("steamcommunity.com", "other.example"),
        )) assertNull(resolver.resolve(Request.Builder().url(url).build()))
        assertEquals(0, server.requestCount)
    }

    @Test fun invalidDnsResponseLeavesTheOrdinaryRouteAvailable() = MockWebServer().use { server ->
        server.start()
        server.enqueue(MockResponse.Builder().body("<html>not DNS</html>").build())
        val resolver = WorkshopTopicIpv4Route(OkHttpClient(), server.url("/resolve"))
        assertNull(resolver.resolve(Request.Builder().url(topic).build()))
    }
}

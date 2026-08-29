package io.stamethyst.backend.workshop

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.apricityx.workshop.steam.protocol.CmServer
import top.apricityx.workshop.steam.protocol.SessionContext
import top.apricityx.workshop.steam.protocol.SteamAppProductInfo
import top.apricityx.workshop.steam.protocol.SteamAuthenticationException
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.workshop.ResolvedWorkshopItem
import top.apricityx.workshop.workshop.UgcWorkshopDownloader
import top.apricityx.workshop.workshop.WorkshopDownloadRequest
import top.apricityx.workshop.workshop.WorkshopSteamRateLimitedException

class UgcWorkshopDownloaderRateLimitTest {
    @Test
    fun rateLimitedCmLoginStopsBeforeManifestOrCdnRequests() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder()
                    .body("{\"response\":{\"serverlist\":[{\"endpoint\":\"cm.example:443\",\"type\":\"websockets\"}]}}")
                    .build(),
            )
            val session = RateLimitedSession()
            val downloader = UgcWorkshopDownloader(
                client = OkHttpClient(),
                directoryClient = SteamDirectoryClient(OkHttpClient(), apiBaseUrl = server.url("/")),
                sessionFactory = { session },
                sessionConnector = { _, _ ->
                    throw SteamAuthenticationException(84, "too many requests")
                },
            )

            val error = runCatching {
                runBlocking {
                    downloader.download(
                        request = WorkshopDownloadRequest(646570u, 1uL, File("build/test-rate-limit")),
                        item = ResolvedWorkshopItem.UgcManifestItem(1uL, 2u, "test", "{}"),
                        emit = {},
                        log = {},
                    )
                }
            }.exceptionOrNull()

            assertTrue(error is WorkshopSteamRateLimitedException)
            assertEquals(0, session.serviceMethodCalls)
            assertEquals(0, session.depotKeyRequests)
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }

    private class RateLimitedSession : SteamCmSession {
        override val currentSession = MutableStateFlow<SessionContext?>(null)
        var serviceMethodCalls = 0
        var depotKeyRequests = 0

        override suspend fun connect(servers: List<CmServer>) = Unit

        override suspend fun connectAnonymous(servers: List<CmServer>): SessionContext =
            error("connectAnonymous should not be called directly")

        override suspend fun connectWithRefreshToken(
            servers: List<CmServer>,
            account: SteamAccountSession,
        ): SessionContext = error("connectWithRefreshToken should not be called")

        override suspend fun <T : com.google.protobuf.MessageLite> callServiceMethod(
            methodName: String,
            request: com.google.protobuf.MessageLite,
            parser: com.google.protobuf.Parser<T>,
        ): T {
            serviceMethodCalls += 1
            error("CM service method should not be called after rate limiting")
        }

        override suspend fun <T : com.google.protobuf.MessageLite> sendClientMessage(
            emsg: Int,
            request: com.google.protobuf.MessageLite,
            responseEmsg: Int,
            parser: com.google.protobuf.Parser<T>,
        ): T = error("CM client message should not be called after rate limiting")

        override suspend fun sendClientMessage(
            emsg: Int,
            request: com.google.protobuf.MessageLite,
        ) = error("CM client message should not be called after rate limiting")

        override suspend fun sendClientMessage(
            emsg: Int,
            request: com.google.protobuf.MessageLite,
            routingAppId: UInt,
        ) = error("CM client message should not be called after rate limiting")

        override suspend fun requestDepotDecryptionKey(appId: UInt, depotId: UInt): ByteArray {
            depotKeyRequests += 1
            error("Depot key should not be requested after rate limiting")
        }

        override suspend fun requestAppProductInfo(appId: UInt): SteamAppProductInfo =
            error("Product info should not be requested")

        override fun close() = Unit
    }
}

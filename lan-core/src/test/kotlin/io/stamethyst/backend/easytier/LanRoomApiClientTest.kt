package io.stamethyst.backend.easytier

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanRoomApiClientTest {
    @Test
    fun startSession_usesSharedContractAndReturnsServerCredentials() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse(200, Headers.headersOf("Content-Type", "application/json"), """{"sessionId":"lan-1","roomId":"alpha","entryNodeUrl":"tcp://relay:11010","networkSecret":"secret","sessionToken":"session","ownerToken":"owner","assignedIpv4Cidr":"10.126.4.2/24"}"""))
            server.start()
            val result = LanRoomApiClient(server.url("/").toString(), LanClientIdentity("desktop-test", "Windows test"))
                .startSession("alpha", "pc-1", "PC", macAddress = "02:00:00:00:00:01")
            val request = server.takeRequest()
            assertEquals("/api/lan/session/start", request.target)
            assertTrue(request.body!!.utf8().contains("\"macAddress\":\"02:00:00:00:00:01\""))
            assertEquals("10.126.4.2/24", result.assignedIpv4Cidr)
            assertEquals("owner", result.ownerToken)
        } finally { server.close() }
    }
}

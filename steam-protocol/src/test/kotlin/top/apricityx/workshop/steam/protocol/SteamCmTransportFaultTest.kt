package top.apricityx.workshop.steam.protocol

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

/** Actual local OkHttp/DNS/TLS handshakes complement callback-level network simulation. */
class SteamCmTransportFaultTest {
    @Test fun dnsFailureIsBoundedWithoutSendingLogon() = runBlocking {
        val client = OkHttpClient.Builder()
            .dns(Dns { throw UnknownHostException("isolated test DNS") }).build()
        withClient(client) {
            val selector = SteamCmServerSelector(SteamWebSocketFactory(client::newWebSocket), selectionAndLogonTimeoutMs = 2_000)
            OkHttpSteamCmSession(client, serverSelector = selector).use { session ->
                val error = runCatching { withTimeout(3_000) {
                    session.connect(listOf(CmServer("unresolvable.example:443", "websockets")))
                } }.exceptionOrNull()
                assertTrue(error is SteamProtocolException)
                assertNull(session.currentSession.value)
            }
        }
    }

    @Test fun captivePortalCannotBeMistakenForWebSocketSuccess() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse.Builder().code(200).body("Sign in to Wi-Fi").build()) }
            server.start()
            val client = OkHttpClient.Builder().build()
            withClient(client) {
                val selector = SteamCmServerSelector(SteamWebSocketFactory(client::newWebSocket))
                OkHttpSteamCmSession(client, serverSelector = selector).use { session ->
                    // Cleartext loopback fixture only; production directory supplies WSS URLs.
                    val node = CmServer("portal.example:443", "websockets", server.url("/cmsocket/").toString())
                    val error = runCatching { withTimeout(3_000) { session.connect(listOf(node)) } }.exceptionOrNull()
                    assertTrue("Portal must fail: $error", error is SteamProtocolException)
                    assertNull(session.currentSession.value)
                    assertEquals(3, server.requestCount)
                    repeat(3) {
                        assertEquals("websocket", server.takeRequest(1, TimeUnit.SECONDS)!!.headers["Upgrade"])
                    }
                }
            }
        }
    }

    @Test fun untrustedTlsCertificateIsNeverBypassedForAvailability() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory())
            // A second localhost address can fail with ECONNREFUSED and hide the TLS rejection.
            val loopback = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
            server.start(loopback, 0)
            val client = OkHttpClient.Builder().dns(Dns { listOf(loopback) }).build() // No test CA.
            withClient(client) {
                val selector = SteamCmServerSelector(SteamWebSocketFactory(client::newWebSocket))
                OkHttpSteamCmSession(client, serverSelector = selector).use { session ->
                    val node = CmServer("localhost:${server.port}", "websockets", server.url("/cmsocket/").toString())
                    val error = runCatching { withTimeout(5_000) { session.connect(listOf(node)) } }.exceptionOrNull()
                    assertTrue("TLS rejection must not become success: $error", error is SteamProtocolException)
                    val causes = generateSequence(error) { it.cause }.toList()
                    assertTrue("Expected a TLS handshake rejection, got $causes", causes.any { it is SSLHandshakeException })
                    assertNull(session.currentSession.value)
                    assertEquals(0, server.requestCount)
                }
            }
        }
    }

    private suspend fun withClient(client: OkHttpClient, block: suspend () -> Unit) {
        try { block() } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}

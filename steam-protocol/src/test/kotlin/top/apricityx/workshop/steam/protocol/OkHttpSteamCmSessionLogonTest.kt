package top.apricityx.workshop.steam.protocol

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import top.apricityx.workshop.steam.proto.CMsgClientLogonResponse
import top.apricityx.workshop.steam.proto.CMsgProtoBufHeader

/** In-memory CM responses only: no network, real credentials or cloud writes. */
class OkHttpSteamCmSessionLogonTest {
    private val servers = (1..5).map { CmServer("cm$it.example:443", "websockets") }
    private val account = SteamAccountSession("test-account", 76561198000000001L, "test-token")

    @Test fun connectionFailureNeverSuggestsExpiredCredentialsButKeepsLogonStage() = runBlocking {
        val errors = listOf(
            java.net.UnknownHostException("DNS failed"),
            javax.net.ssl.SSLHandshakeException("TLS failed"),
            java.net.SocketException("Connection reset"),
            IOException("WebSocket failed"),
        )
        for (networkError in errors) {
            var notices = 0
            val factory = SteamWebSocketFactory { request, listener ->
                FakeSocket(request, listener) {}.also { listener.onFailure(it, networkError, null) }
            }
            OkHttpSteamCmSession(webSocketFactory = factory, onAccountLogonFailure = { _, _ -> notices++ }).use { session ->
                val error = runCatching { session.connectWithRefreshToken(servers.take(1), account) }.exceptionOrNull()
                assertTrue(error is SteamProtocolException && error.isCmLogonFailure)
                assertEquals(networkError.toString(), 0, notices)
            }
        }
    }

    @Test fun serviceErrorsRateLimitsAndUnknownLogonResultsDoNotSuggestRelogin() = runBlocking {
        for (code in listOf(2, 8, 15, 20, 48, 63, 65, 84, 85, 87, 88, 999)) {
            var notices = 0
            val factory = FakeFactory(List(3) { Outcome.AuthError(code) })
            OkHttpSteamCmSession(webSocketFactory = factory, onAccountLogonFailure = { _, _ -> notices++ }).use { session ->
                val error = runCatching { session.connectWithRefreshToken(servers.take(1), account) }.exceptionOrNull()
                assertTrue("EResult=$code", error is SteamProtocolException && error.isCmLogonFailure)
                assertEquals("EResult=$code", 0, notices)
            }
        }
    }

    @Test fun onlyExplicitCredentialRejectionsSuggestRelogin() = runBlocking {
        for (code in listOf(5, 27, 106, 109)) {
            var notices = 0
            val factory = FakeFactory(listOf(Outcome.AuthError(code)))
            OkHttpSteamCmSession(webSocketFactory = factory, onAccountLogonFailure = { _, error ->
                assertTrue(error.isSteamCmCredentialRejection())
                notices++
            }).use { session ->
                assertTrue(runCatching { session.connectWithRefreshToken(servers, account) }.exceptionOrNull() is SteamAuthenticationException)
                assertEquals("EResult=$code", 1, notices)
                assertEquals(1, factory.logons)
            }
        }
    }

    @Test fun automaticReconnectNetworkFailureDoesNotSuggestRelogin() = runBlocking {
        var notices = 0
        val factory = FakeFactory(listOf(Outcome.Success, Outcome.HandshakeFailure))
        OkHttpSteamCmSession(webSocketFactory = factory, onAccountLogonFailure = { _, _ -> notices++ }).use { session ->
            session.connectWithRefreshToken(servers.take(1), account)
            val rpc = async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.requestDepotDecryptionKey(1u, 2u) } }
            factory.sockets.first().let { it.listener.onFailure(it, IOException("offline"), null) }
            assertTrue(rpc.await().isFailure)
            assertEquals(0, notices)
        }
    }

    @Test fun selectorCanFindUsableNodeBeyondFirstThreeWithoutConcurrentLogons() = runBlocking {
        SteamCmServerSelectorTest.Fixture(mapOf(
            "cm1.example" to -1L, "cm2.example" to -1L, "cm3.example" to -1L, "cm4.example" to 10L,
        )).use { probes ->
            val factory = FakeFactory(listOf(Outcome.HandshakeFailure, Outcome.Success))
            val selector = SteamCmServerSelector(probes.factory, collectionWindowMs = 20)
            OkHttpSteamCmSession(webSocketFactory = factory, serverSelector = selector).use { session ->
                assertEquals(account.steamId, session.connectWithRefreshToken(servers, account).steamId)
                assertEquals("cm4.example", factory.sockets.last().request().url.host)
                assertEquals(1, factory.logons)
                assertEquals(0, probes.active.get())
            }
        }
    }

    @Test fun selectionAndLogonShareOneDeadlineAndCleanUpTransport() = runBlocking {
        val factory = FakeFactory(listOf(Outcome.NoReply))
        var notices = 0
        val health = SteamCmServerHealth().apply { loggedOn("test", servers.first()) }
        val selector = SteamCmServerSelector(factory, { "test" }, health, selectionAndLogonTimeoutMs = 100)
        OkHttpSteamCmSession(
            webSocketFactory = factory, serverSelector = selector,
            onAccountLogonFailure = { _, _ -> notices++ },
        ).use { session ->
            val failure = runCatching { session.connectWithRefreshToken(servers, account) }.exceptionOrNull()
            assertTrue(failure is SteamProtocolException && failure.isCmLogonFailure)
            assertTrue(failure?.cause is java.net.SocketTimeoutException)
            assertEquals(1, factory.logons)
            assertTrue(factory.sockets.single().cancelled)
            assertNull(session.currentSession.value)
            assertEquals(0, notices)
        }
    }

    @Test fun unavailableCmRoutingNeverSuggestsExpiredCredentials() = runBlocking {
        var notices = 0
        val factory = FakeFactory(listOf(Outcome.Unavailable))
        OkHttpSteamCmSession(webSocketFactory = factory, onAccountLogonFailure = { _, _ -> notices++ }).use { session ->
            val error = runCatching { session.connectWithRefreshToken(servers.take(1), account) }.exceptionOrNull()
            assertTrue(error is SteamProtocolException && error.isCmLogonFailure)
            assertEquals(0, notices)
        }
    }

    @Test fun logoutDuringNodeProbingCancelsAllProbesWithoutLoggingOn() = runBlocking {
        SteamCmServerSelectorTest.Fixture(emptyMap()).use { probes ->
            val factory = FakeFactory(listOf(Outcome.HandshakeFailure))
            val session = OkHttpSteamCmSession(
                webSocketFactory = factory,
                serverSelector = SteamCmServerSelector(probes.factory),
            )
            try {
                val login = async { runCatching { session.connectWithRefreshToken(servers, account) } }
                withTimeout(2_000) {
                    while (probes.sockets.isEmpty()) kotlinx.coroutines.yield()
                }
                session.close()
                assertTrue(login.await().exceptionOrNull() is SteamSessionRevokedException)
                assertEquals(0, factory.logons)
                assertEquals(0, probes.active.get())
            } finally { session.close() }
        }
    }

    @Test fun unavailableAuthenticationRouteTriesTheNextCm() = runBlocking {
        val factory = FakeFactory(listOf(Outcome.Unavailable, Outcome.Success))
        OkHttpSteamCmSession(webSocketFactory = factory).use { session ->
            assertEquals(account.steamId, session.connectWithRefreshToken(servers, account).steamId)
            assertEquals(listOf("cm1.example", "cm2.example"), factory.sockets.map { it.request().url.host })
            assertTrue(factory.sockets.first().cancelled)
            assertEquals(2, factory.logons)
        }
    }

    @Test fun anonymousLogonAlsoFailsOverBeforeContentRequests() = runBlocking {
        val factory = FakeFactory(listOf(Outcome.Unavailable, Outcome.Success))
        OkHttpSteamCmSession(webSocketFactory = factory).use { session ->
            assertNotNull(session.connectAnonymous(servers))
            assertEquals(2, factory.logons)
        }
    }

    @Test fun transportConnectedBeforeLogonStillFailsOver() = runBlocking {
        val factory = FakeFactory(listOf(Outcome.Unavailable, Outcome.Success))
        OkHttpSteamCmSession(webSocketFactory = factory).use { session ->
            session.connect(servers)
            assertEquals(1, factory.sockets.size)
            assertNotNull(session.connectWithRefreshToken(servers, account))
            assertEquals(2, factory.sockets.size)
        }
    }

    @Test fun unavailableEverywhereStopsAfterThreeDistinctCmAttempts() = runBlocking {
        val factory = FakeFactory(List(5) { Outcome.Unavailable })
        OkHttpSteamCmSession(webSocketFactory = factory).use { session ->
            try {
                session.connectWithRefreshToken(servers + servers, account)
                fail("Expected bounded logon failure")
            } catch (error: SteamProtocolException) {
                val unavailable = error.cause as SteamServerUnavailableException
                assertEquals(5514, unavailable.emsgSent)
                assertEquals(-1L, unavailable.jobIdSent)
                assertEquals(3, unavailable.serverTypeUnavailable)
            }
            assertEquals(3, factory.logons)
            assertEquals(3, factory.sockets.size)
            assertNull(session.currentSession.value)
        }
    }

    @Test fun invalidCredentialsAndRateLimitsAreNotRetried() = runBlocking {
        listOf(5, 84, 87).forEach { code ->
            val factory = FakeFactory(listOf(Outcome.AuthError(code), Outcome.Success))
            OkHttpSteamCmSession(webSocketFactory = factory).use { session ->
                try {
                    session.connectWithRefreshToken(servers, account)
                    fail("Expected authentication rejection")
                } catch (error: SteamAuthenticationException) { assertEquals(code, error.resultCode) }
                assertEquals(1, factory.logons)
                assertEquals(1, factory.sockets.size)
            }
        }
    }

    @Test fun serviceUnavailableLogonResultIsRetryable() = runBlocking {
        val factory = FakeFactory(listOf(Outcome.AuthError(20), Outcome.Success))
        OkHttpSteamCmSession(webSocketFactory = factory).use { session ->
            assertNotNull(session.connectWithRefreshToken(servers, account))
            assertEquals(2, factory.logons)
        }
    }

    @Test fun callerCancellationDoesNotTryAnotherCm() = runBlocking {
        val factory = FakeFactory(listOf(Outcome.NoReply, Outcome.Success))
        OkHttpSteamCmSession(webSocketFactory = factory).use { session ->
            try {
                withTimeout(100) { session.connectWithRefreshToken(servers, account) }
                fail("Expected cancellation")
            } catch (_: TimeoutCancellationException) { }
            assertEquals(1, factory.logons)
            assertEquals(1, factory.sockets.size)
            assertTrue(factory.sockets.single().cancelled)
        }
    }

    @Test fun explicitCancellationIsNotRetriedEvenWhenCallerIsStillActive() = runBlocking {
        val factory = FakeFactory(listOf(Outcome.Cancel, Outcome.Success))
        OkHttpSteamCmSession(webSocketFactory = factory).use { session ->
            try {
                session.connectWithRefreshToken(servers, account)
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertEquals(1, factory.logons)
            assertEquals(1, factory.sockets.size)
        }
    }

    @Test fun staleCallbacksFromRejectedCmCannotClearReplacementSession() = runBlocking {
        val factory = FakeFactory(listOf(Outcome.Unavailable, Outcome.Success))
        OkHttpSteamCmSession(webSocketFactory = factory).use { session ->
            val loggedOn = session.connectWithRefreshToken(servers, account)
            val old = factory.sockets.first()
            old.listener.onClosed(old, 1006, "late close")
            old.listener.onFailure(old, IOException("late failure"), null)
            old.listener.onMessage(old, unavailablePacket().toByteString())
            assertEquals(loggedOn, session.currentSession.value)
            assertEquals(loggedOn, session.connectWithRefreshToken(servers, account))
            assertEquals(2, factory.logons)
        }
    }

    @Test fun synchronousHandshakeFailureIsNotMistakenForAnOpenSocket() = runBlocking {
        val factory = FakeFactory(listOf(Outcome.HandshakeFailure, Outcome.Success))
        OkHttpSteamCmSession(webSocketFactory = factory).use { session ->
            assertNotNull(session.connectWithRefreshToken(servers, account))
            assertEquals(2, factory.sockets.size)
            assertEquals(1, factory.logons)
        }
    }

    @Test fun closedSessionCannotReconnectOrRetryRpcWithOldToken() = runBlocking {
        val factory = FakeFactory(listOf(Outcome.Success, Outcome.Success))
        val session = OkHttpSteamCmSession(webSocketFactory = factory)
        session.connectWithRefreshToken(servers, account)
        session.close()
        assertNull(session.currentSession.value)
        assertTrue(runCatching { session.requestDepotDecryptionKey(1u, 2u) }.exceptionOrNull() is SteamSessionRevokedException)
        assertTrue(runCatching { session.connectWithRefreshToken(servers, account) }.exceptionOrNull() is SteamSessionRevokedException)
        assertTrue(runCatching { session.connectAnonymous(servers) }.exceptionOrNull() is SteamSessionRevokedException)
        assertEquals(1, factory.logons)
    }

    @Test fun closingInFlightRpcIsTerminalNotANetworkRetry() = runBlocking {
        val factory = FakeFactory(listOf(Outcome.Success, Outcome.Success))
        val session = OkHttpSteamCmSession(webSocketFactory = factory)
        session.connectWithRefreshToken(servers, account)
        val rpc = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { session.requestDepotDecryptionKey(1u, 2u) }
        }
        session.close()
        assertTrue(rpc.await().exceptionOrNull() is SteamSessionRevokedException)
        assertEquals(1, factory.logons)
    }

    @Test fun logoutCancelsInFlightLogonWithoutFailureNoticeOrFailover() = runBlocking {
        val factory = FakeFactory(listOf(Outcome.NoReply, Outcome.Success))
        var notices = 0
        val session = OkHttpSteamCmSession(webSocketFactory = factory, onAccountLogonFailure = { _, _ -> notices++ })
        val logon = async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.connectWithRefreshToken(servers, account) } }
        session.close()
        assertTrue(logon.await().exceptionOrNull() is SteamSessionRevokedException)
        assertEquals(1, factory.logons)
        assertEquals(0, notices)
    }

    @Test fun revokedCredentialsBlockAutomaticReconnectAfterSocketFailure() = runBlocking {
        val factory = FakeFactory(listOf(Outcome.Success, Outcome.Success))
        var valid = true
        val session = OkHttpSteamCmSession(webSocketFactory = factory, accountValidator = { valid })
        session.connectWithRefreshToken(servers, account)
        val rpc = async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.requestDepotDecryptionKey(1u, 2u) } }
        valid = false
        factory.sockets.single().let { it.listener.onFailure(it, IOException("lost connection"), null) }
        assertTrue(rpc.await().exceptionOrNull() is SteamSessionRevokedException)
        assertEquals(1, factory.logons)
        session.close()
    }

    @Test fun authenticatedLogonFailureNotifiesButAnonymousFailureDoesNot() = runBlocking {
        var notices = 0
        val failed = FakeFactory(listOf(Outcome.AuthError(5)))
        OkHttpSteamCmSession(webSocketFactory = failed, onAccountLogonFailure = { actual, _ ->
            assertEquals(account, actual); notices++
        }).use { session ->
            assertTrue(runCatching { session.connectWithRefreshToken(servers, account) }.isFailure)
        }
        val anonymous = FakeFactory(listOf(Outcome.AuthError(5)))
        OkHttpSteamCmSession(webSocketFactory = anonymous, onAccountLogonFailure = { _, _ -> notices++ }).use { session ->
            assertTrue(runCatching { session.connectAnonymous(servers) }.isFailure)
        }
        assertEquals(1, notices)
    }

    @Test fun automaticReconnectLogonFailureAlsoNotifies() = runBlocking {
        val factory = FakeFactory(listOf(Outcome.Success, Outcome.AuthError(5)))
        var notices = 0
        OkHttpSteamCmSession(webSocketFactory = factory, onAccountLogonFailure = { _, _ -> notices++ }).use { session ->
            session.connectWithRefreshToken(servers, account)
            val rpc = async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.requestDepotDecryptionKey(1u, 2u) } }
            factory.sockets.single().let { it.listener.onFailure(it, IOException("lost connection"), null) }
            assertTrue(rpc.await().exceptionOrNull() is SteamAuthenticationException)
            assertEquals(1, notices)
            assertEquals(2, factory.logons)
        }
    }

    @Test fun closeCancelsPendingHandshakeAndQueuedConnect() = runBlocking {
        val factory = FakeFactory(listOf(Outcome.NoHandshake, Outcome.Success))
        val session = OkHttpSteamCmSession(webSocketFactory = factory)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.connectWithRefreshToken(servers, account) } }
        val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.connectWithRefreshToken(servers, account) } }
        session.close()
        assertTrue(pending.await().exceptionOrNull() is SteamSessionRevokedException)
        assertTrue(queued.await().exceptionOrNull() is SteamSessionRevokedException)
        assertEquals(1, factory.sockets.size)
        assertEquals(0, factory.logons)
    }

    private sealed interface Outcome {
        data object Unavailable : Outcome
        data object Success : Outcome
        data object NoReply : Outcome
        data object NoHandshake : Outcome
        data object Cancel : Outcome
        data object HandshakeFailure : Outcome
        data class AuthError(val code: Int) : Outcome
    }

    private inner class FakeFactory(private val outcomes: List<Outcome>) : SteamWebSocketFactory {
        val sockets = mutableListOf<FakeSocket>()
        var logons = 0
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val outcome = outcomes[sockets.size]
            val socket = FakeSocket(request, listener) { bytes ->
                if (SteamPacketCodec.peekBaseMessageId(bytes.toByteArray()) == SteamPacketCodec.emsgClientLogon) {
                    logons++
                    when (outcome) {
                        Outcome.Unavailable -> listener.onMessage(sockets.last(), unavailablePacket().toByteString())
                        Outcome.Success -> respond(listener, sockets.last(), 1)
                        is Outcome.AuthError -> respond(listener, sockets.last(), outcome.code)
                        Outcome.Cancel -> throw CancellationException("cancelled by test")
                        else -> Unit
                    }
                }
            }
            sockets += socket
            if (outcome == Outcome.HandshakeFailure) listener.onFailure(socket, IOException("CM offline"), null)
            else if (outcome != Outcome.NoHandshake) listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
            return socket
        }
    }

    private class FakeSocket(
        private val request: Request,
        val listener: WebSocketListener,
        private val onSend: (ByteString) -> Unit,
    ) : WebSocket {
        var cancelled = false
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String) = false
        override fun send(bytes: ByteString): Boolean { onSend(bytes); return true }
        override fun close(code: Int, reason: String?): Boolean { cancelled = true; return true }
        override fun cancel() { cancelled = true }
    }

    private fun respond(listener: WebSocketListener, socket: WebSocket, result: Int) {
        listener.onMessage(socket, SteamPacketCodec.encode(
            SteamPacketCodec.emsgClientLogOnResponse,
            CMsgProtoBufHeader.newBuilder().setClientSessionid(123).setSteamid(account.steamId).build(),
            CMsgClientLogonResponse.newBuilder().setEresult(result).build(),
        ).toByteString())
    }

    private fun unavailablePacket(): ByteArray = ByteBuffer.allocate(36 + 16).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(SteamPacketCodec.emsgClientServerUnavailable)
        .put(36.toByte()).putShort(2.toShort()).putLong(-1L).putLong(-1L).put(239.toByte()).putLong(0L).putInt(0)
        .putLong(-1L).putInt(5514).putInt(3).array()
}

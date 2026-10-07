package top.apricityx.workshop.steam.protocol

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
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

    private sealed interface Outcome {
        data object Unavailable : Outcome
        data object Success : Outcome
        data object NoReply : Outcome
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
            else listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
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

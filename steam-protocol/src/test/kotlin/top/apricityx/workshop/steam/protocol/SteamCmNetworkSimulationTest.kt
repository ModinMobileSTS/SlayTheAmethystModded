package top.apricityx.workshop.steam.protocol

import java.io.File
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.math.ceil
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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

/** Virtual time, stable seeds, production timeouts. Never contacts Steam or reads credentials. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SteamCmNetworkSimulationTest {
    private val servers = (1..20).map { CmServer("cm$it.example:443", "websockets") }
    private val account = SteamAccountSession("synthetic-account", 76561198000000001L, "synthetic-token")

    private enum class Fault { NONE, DNS, TLS, RESET, BLACKHOLE, CAPTIVE_PORTAL }
    private data class Link(
        val handshakeMs: Long = 80,
        val loginMs: Long = 80,
        val jitterMs: Int = 0,
        val fault: Fault = Fault.NONE,
        val loginResult: Int = 1,
        val dropEvery: Int = 0,
        val loseFirstLoginReply: Boolean = false,
    )
    private data class Scenario(val name: String, val links: List<Link>, val expectSuccess: Boolean = true)
    private fun scenarios(): List<Scenario> {
        val healthy = Link()
        return listOf(
            Scenario("healthy_wifi", List(20) { healthy }),
            Scenario("mobile_high_latency_jitter", List(20) { Link(1_100, 800, 500) }),
            Scenario("satellite_slow_but_usable", List(20) { Link(3_500, 2_000, 800) }),
            Scenario("first_two_blackholed", List(20) { if (it < 2) Link(fault = Fault.BLACKHOLE) else healthy }),
            Scenario("regional_block_only_last_node_works", List(20) { if (it < 19) Link(fault = Fault.BLACKHOLE) else healthy }),
            Scenario("ipv6_route_broken_ipv4_fallback_nodes", List(20) { if (it < 3) Link(fault = Fault.RESET) else healthy }),
            Scenario("partial_dns_poisoning", List(20) { if (it % 3 != 2) Link(fault = Fault.DNS) else healthy }),
            Scenario("partial_tls_interception", List(20) { if (it < 4) Link(fault = Fault.TLS) else healthy }),
            Scenario("intermittent_loss_and_jitter", List(20) { Link(300, 150, 200, dropEvery = 3) }),
            Scenario("first_login_reply_lost", List(20) { healthy.copy(loseFirstLoginReply = true) }),
            Scenario("first_two_login_routes_unavailable", List(20) { healthy.copy(loginResult = if (it < 2) 20 else 1) }),
            Scenario("all_dns_unavailable", List(20) { Link(fault = Fault.DNS) }, false),
            Scenario("all_tls_certificates_invalid", List(20) { Link(fault = Fault.TLS) }, false),
            Scenario("all_wss_blocked", List(20) { Link(fault = Fault.BLACKHOLE) }, false),
            Scenario("captive_portal_http_200", List(20) { Link(fault = Fault.CAPTIVE_PORTAL) }, false),
            Scenario("steam_rate_limited", List(20) { healthy.copy(loginResult = 84) }, false),
            Scenario("credentials_rejected", List(20) { healthy.copy(loginResult = 5) }, false),
        )
    }

    @Test fun playerNetworkMatrix() = runTest {
        val rounds = System.getProperty("cm.simulation.rounds", "3").toInt().coerceIn(1, 100)
        val rows = mutableListOf("scenario,seed,mode,success,elapsed_ms,connections,peak_sockets,logons,peak_logons,error")
        val summaries = mutableListOf<String>()
        for (scenario in scenarios()) {
            val baseline = mutableListOf<Long>()
            for (selected in listOf(false, true)) {
                val samples = mutableListOf<Long>()
                var successes = 0
                repeat(rounds) { seed ->
                    val fixture = Network(this, scenario.links, seed)
                    val selector = if (selected) SteamCmServerSelector(
                        fixture,
                        health = SteamCmServerHealth { testScheduler.currentTime },
                        nowNanos = { testScheduler.currentTime * 1_000_000 },
                    ) else null
                    val start = testScheduler.currentTime
                    var failure: Throwable? = null
                    val session = OkHttpSteamCmSession(webSocketFactory = fixture, serverSelector = selector)
                    val result = try {
                        withTimeoutOrNull(45_000) {
                            try { session.connectWithRefreshToken(servers, account); true }
                            catch (error: Exception) { failure = error; false }
                        } ?: false
                    } finally { session.close() }
                    val elapsed = testScheduler.currentTime - start
                    samples += elapsed
                    if (!selected) baseline += elapsed
                    if (result) successes++
                    rows += "${scenario.name},$seed,${if (selected) "selected" else "serial"},$result,$elapsed,${fixture.sockets.size},${fixture.peak},${fixture.logons},${fixture.peakLogons},${failure?.javaClass?.simpleName.orEmpty()}"
                    assertEquals("Leaked socket: ${scenario.name}", 0, fixture.active)
                    assertEquals("Leaked login: ${scenario.name}", 0, fixture.activeLogons)
                    assertTrue("Parallel login: ${scenario.name}", fixture.peakLogons <= 1)
                    assertTrue("Too many logins: ${scenario.name}", fixture.logons <= 3)
                    assertTrue("Unbounded deadline: ${scenario.name}", elapsed <= 45_000)
                    if (selected) {
                        assertEquals("Seed $seed: ${scenario.name}: $failure", scenario.expectSuccess, result)
                        // Three probes plus the original still-pending connection.
                        assertTrue("Excess connections", fixture.peak <= 4)
                        if (scenario.name in listOf("healthy_wifi", "mobile_high_latency_jitter", "satellite_slow_but_usable")) {
                            assertTrue("Slow usable network regressed: ${scenario.name} $elapsed vs ${baseline[seed]}", elapsed <= baseline[seed] + 150)
                        }
                    }
                    if (scenario.name in listOf("steam_rate_limited", "credentials_rejected")) {
                        assertEquals(1, fixture.logons)
                        assertEquals(1, fixture.sockets.size)
                    }
                }
                val sorted = samples.sorted()
                summaries += "${scenario.name} ${if (selected) "selected" else "serial"}: $successes/$rounds p50=${sorted[(rounds - 1) / 2]}ms p95=${sorted[ceil(rounds * .95).toInt() - 1]}ms"
            }
        }
        val out = File(System.getProperty("cm.simulation.out", "../agent-tmp/cm-network-simulation-unit.csv"))
        out.parentFile?.mkdirs()
        out.writeText(rows.joinToString("\n", postfix = "\n"))
        File(out.path + ".summary.txt").writeText(summaries.joinToString("\n", postfix = "\n"))
        println(summaries.joinToString("\n"))
        println("Simulation (virtual milliseconds, not measured player traffic): ${out.absolutePath}")
    }

    @Test fun disconnectAndNetworkSwitchDoNotReuseOldNetworkWinner() = runTest {
        var network = "wifi"
        val health = SteamCmServerHealth { testScheduler.currentTime }
        health.loggedOn("wifi", servers[4])
        health.failed("mobile", servers[4])
        val fixture = Network(this, List(20) { Link() }, 7)
        val selector = SteamCmServerSelector(fixture, { network }, health)
        OkHttpSteamCmSession(webSocketFactory = fixture, serverSelector = selector).use { session ->
            session.connectWithRefreshToken(servers, account)
            assertEquals("cm5.example", fixture.sockets.last().request().url.host)
            val old = fixture.sockets.last()
            old.disconnect()
            network = "mobile"
            session.connectWithRefreshToken(servers, account)
            assertEquals("cm1.example", fixture.sockets.last().request().url.host)
            old.listener.onFailure(old, SocketException("late old-network callback"), null)
            assertNotNull(session.currentSession.value)
            assertTrue(health.isCooling("mobile", servers[4]))
        }
        assertEquals(0, fixture.active)
    }

    @Test fun lostReplyNeverReplaysCloudWritesButReadsRecover() = runTest {
        val writes = listOf("BeginAppUploadBatch", "BeginHTTPUpload", "CommitHTTPUpload", "ClientDeleteFile",
            "CompleteAppUploadBatchBlocking")
        for (rpc in writes + listOf("GetAppFileChangelist", "ClientFileDownload")) {
            for (disconnect in listOf(true, false)) {
                val fixture = Network(this, List(20) { Link() }, 7)
                val method = "Cloud.$rpc#1"
                val received = mutableListOf<Long>()
                fixture.onServiceCall = { socket, packet ->
                    assertEquals(method, packet.header.targetJobName)
                    received += packet.header.jobidSource
                    if (received.size == 1) {
                        if (disconnect) socket.disconnect() // Otherwise keep socket open, swallow the reply.
                    } else socket.listener.onMessage(socket, SteamPacketCodec.encode(
                        SteamPacketCodec.emsgServiceMethodResponse,
                        CMsgProtoBufHeader.newBuilder().setJobidTarget(received.last()).setEresult(1).build(),
                        CMsgProtoBufHeader.getDefaultInstance()).toByteString())
                }
                OkHttpSteamCmSession(webSocketFactory = fixture).use { session ->
                    session.connectWithRefreshToken(servers, account)
                    val error = runCatching { session.callServiceMethod(method,
                        CMsgProtoBufHeader.getDefaultInstance(), CMsgProtoBufHeader.parser()) }.exceptionOrNull()
                    if (rpc in writes) {
                        assertEquals("$method, disconnect=$disconnect", 1, received.size)
                        if (disconnect) assertTrue(error is SocketException) else assertTrue(error is SocketTimeoutException)
                    } else {
                        assertNull(error)
                        assertEquals(2, received.distinct().size)
                    }
                }
            }
        }
    }

    @Test fun callerDeadlineTerminatesReadWithoutReconnectOrCredentialFailure() = runTest {
        val fixture = Network(this, List(20) { Link() }, 7)
        var received = 0
        fixture.onServiceCall = { _, _ -> received++ }
        OkHttpSteamCmSession(webSocketFactory = fixture,
            onAccountLogonFailure = { _, _ -> fail("Caller cancelled, no credential rejection") }).use { session ->
            session.connectWithRefreshToken(servers, account)
            val error = runCatching { withTimeout(5) { session.callServiceMethod("Cloud.GetAppFileChangelist#1",
                CMsgProtoBufHeader.getDefaultInstance(), CMsgProtoBufHeader.parser()) } }.exceptionOrNull()
            assertTrue(error is TimeoutCancellationException)
            assertEquals(1, received)
            assertEquals(1, fixture.logons)
        }
    }

    private inner class Network(
        private val scope: TestScope,
        private val links: List<Link>,
        seed: Int,
    ) : SteamWebSocketFactory {
        private val random = Random(seed)
        val sockets = mutableListOf<Socket>()
        var active = 0
        var peak = 0
        var logons = 0
        var activeLogons = 0
        var peakLogons = 0
        var onServiceCall: (Socket, SteamPacket) -> Unit = { _, _ -> }
        private var lostReply = false
        private val attempts = mutableMapOf<String, Int>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val index = request.url.host.removePrefix("cm").substringBefore('.').toInt() - 1
            val link = links[index]
            val socket = Socket(request, listener, link)
            sockets += socket
            active++
            peak = maxOf(peak, active)
            val attempt = attempts.merge(request.url.host, 1, Int::plus)!!
            val drop = link.dropEvery > 0 && attempt % link.dropEvery == 1
            socket.jobs += scope.launch {
                delay(link.handshakeMs + if (link.jitterMs > 0) random.nextInt(link.jitterMs).toLong() else 0)
                when {
                    link.fault == Fault.BLACKHOLE -> Unit // No callback until production timeout cancels us.
                    link.fault == Fault.DNS -> socket.fail(UnknownHostException("simulated DNS failure"))
                    link.fault == Fault.TLS -> socket.fail(SSLHandshakeException("simulated certificate rejection"))
                    link.fault == Fault.RESET || drop -> socket.fail(SocketException("simulated packet loss / route reset"))
                    link.fault == Fault.CAPTIVE_PORTAL -> socket.fail(java.net.ProtocolException("Expected HTTP 101 but got 200"))
                    else -> listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(101).message("Switching Protocols").build())
                }
            }
            return socket
        }

        inner class Socket(private val req: Request, val listener: WebSocketListener, private val link: Link) : WebSocket {
            val jobs = mutableListOf<Job>()
            private var cancelled = false
            private var pendingLogin = false
            override fun request() = req
            override fun queueSize() = 0L
            override fun send(text: String) = false
            override fun send(bytes: ByteString): Boolean {
                if (cancelled) return false
                if (SteamPacketCodec.peekBaseMessageId(bytes.toByteArray()) == SteamPacketCodec.emsgServiceMethodCallFromClient) {
                    onServiceCall(this, SteamPacketCodec.decode(bytes.toByteArray()))
                }
                if (SteamPacketCodec.peekBaseMessageId(bytes.toByteArray()) == SteamPacketCodec.emsgClientLogon) {
                    logons++
                    activeLogons++
                    peakLogons = maxOf(peakLogons, activeLogons)
                    pendingLogin = true
                    if (link.loseFirstLoginReply && !lostReply) { lostReply = true; return true }
                    jobs += scope.launch {
                        delay(link.loginMs)
                        pendingLogin = false
                        activeLogons--
                        listener.onMessage(this@Socket, SteamPacketCodec.encode(
                            SteamPacketCodec.emsgClientLogOnResponse,
                            CMsgProtoBufHeader.newBuilder().setClientSessionid(123).setSteamid(account.steamId).build(),
                            CMsgClientLogonResponse.newBuilder().setEresult(link.loginResult).build(),
                        ).toByteString())
                    }
                }
                return true
            }
            override fun close(code: Int, reason: String?): Boolean { cancel(); return true }
            override fun cancel() {
                if (!cancelled) {
                    cancelled = true
                    active--
                    if (pendingLogin) { pendingLogin = false; activeLogons-- }
                    jobs.forEach { it.cancel() }
                }
            }
            fun disconnect() { listener.onFailure(this, SocketException("network disconnected"), null); cancel() }
            // OkHttp releases transport resources before reporting terminal onFailure.
            fun fail(error: Exception) { cancel(); listener.onFailure(this, error, null) }
        }
    }
}

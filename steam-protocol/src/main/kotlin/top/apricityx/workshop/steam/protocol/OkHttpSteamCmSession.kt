package top.apricityx.workshop.steam.protocol

import top.apricityx.workshop.steam.proto.CContentServerDirectory_GetCDNAuthToken_Response
import top.apricityx.workshop.steam.proto.CContentServerDirectory_GetManifestRequestCode_Response
import top.apricityx.workshop.steam.proto.CContentServerDirectory_GetServersForSteamPipe_Response
import top.apricityx.workshop.steam.proto.CMsgClientGetDepotDecryptionKey
import top.apricityx.workshop.steam.proto.CMsgClientGetDepotDecryptionKeyResponse
import top.apricityx.workshop.steam.proto.CMsgClientHeartBeat
import top.apricityx.workshop.steam.proto.CMsgClientHello
import top.apricityx.workshop.steam.proto.CMsgClientLoggedOff
import top.apricityx.workshop.steam.proto.CMsgClientLogon
import top.apricityx.workshop.steam.proto.CMsgClientLogonResponse
import top.apricityx.workshop.steam.proto.CMsgClientPICSProductInfoRequest
import top.apricityx.workshop.steam.proto.CMsgClientPICSProductInfoResponse
import top.apricityx.workshop.steam.proto.CMsgIPAddress
import top.apricityx.workshop.steam.proto.CMsgProtoBufHeader
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class OkHttpSteamCmSession(
    private val client: OkHttpClient = newDefaultOkHttpClient(),
    private val machineName: String = DEFAULT_MACHINE_NAME,
    private val machineId: ByteArray = defaultSteamMachineId(),
    private val webSocketFactory: SteamWebSocketFactory = SteamWebSocketFactory { request, listener ->
        client.newWebSocket(request, listener)
    },
    private val accountValidator: (SteamAccountSession) -> Boolean = { true },
    private val onAccountLogonFailure: (SteamAccountSession, Throwable) -> Unit = { _, _ -> },
    private val sessionValidator: () -> Boolean = { true },
    private val serverSelector: SteamCmServerSelector? = null,
) : SteamCmSession {
    init {
        activeSessions += this
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingRequests = ConcurrentHashMap<Long, PendingRequest<out MessageLite>>()
    private val _currentSession = MutableStateFlow<SessionContext?>(null)
    private val nextJobId = AtomicLong(1L)
    private val transportGeneration = AtomicLong(0L)
    private val transportLock = Any()

    @Volatile private var closed = false
    private var pendingConnect: CompletableDeferred<Unit>? = null
    private val reconnectMutex = Mutex()
    private val connectMutex = Mutex()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var currentServer: CmServer? = null

    @Volatile
    private var heartbeatJob: Job? = null

    @Volatile
    private var pendingLogon = CompletableDeferred<SessionContext>()

    @Volatile
    private var reconnectPlan: ReconnectPlan? = null

    private var selectionScope: String = "default"
    private var selectionJob: Job? = null

    override val currentSession: StateFlow<SessionContext?> = _currentSession

    override suspend fun connect(servers: List<CmServer>) = connectMutex.withLock {
        synchronized(transportLock) {
            checkOpen()
            reconnectPlan = ReconnectPlan.TransportOnly(servers.toList())
        }
        ensureConnected(servers)
    }

    override suspend fun connectAnonymous(servers: List<CmServer>): SessionContext = connectMutex.withLock {
        checkOpen()
        currentSession.value?.let { return@withLock it }
        synchronized(transportLock) {
            checkOpen()
            reconnectPlan = ReconnectPlan.Anonymous(servers.toList())
        }
        logonWithFailover(
            servers = servers,
            requestLabel = "anonymous",
            buildBody = {
                CMsgClientLogon.newBuilder()
                    .setProtocolVersion(SteamPacketCodec.clientLogonProtocol)
                    .setClientOsType(DEFAULT_CLIENT_OS_TYPE)
                    .setClientLanguage("english")
                    .setCellId(0)
                    .setClientPackageVersion(1771)
                    .setObfuscatedPrivateIp(defaultObfuscatedPrivateIp())
                    .setDeprecatedObfustucatedPrivateIp(defaultObfuscatedPrivateIp().v4)
                    .setMachineName(machineName)
                    .setMachineId(ByteString.copyFrom(machineId))
                    .setSupportsRateLimitResponse(true)
                    .build()
            },
            headerSteamId = anonymousSteamId(),
        )
    }

    override suspend fun connectWithRefreshToken(
        servers: List<CmServer>,
        account: SteamAccountSession,
    ): SessionContext = connectMutex.withLock {
        checkAccount(account)
        currentSession.value?.let { return@withLock it }
        synchronized(transportLock) {
            checkAccount(account)
            reconnectPlan = ReconnectPlan.RefreshToken(servers.toList(), account)
        }
        try {
            logonWithFailover(
                servers = servers,
                requestLabel = "refresh token",
                buildBody = {
                    checkAccount(account)
                    CMsgClientLogon.newBuilder()
                        .setProtocolVersion(SteamPacketCodec.clientLogonProtocol)
                        .setClientOsType(DEFAULT_CLIENT_OS_TYPE)
                        .setClientLanguage("english")
                        .setCellId(0)
                        .setClientPackageVersion(1771)
                        .setObfuscatedPrivateIp(defaultObfuscatedPrivateIp())
                        .setDeprecatedObfustucatedPrivateIp(defaultObfuscatedPrivateIp().v4)
                        .setMachineName(account.machineName.ifBlank { machineName })
                        .setMachineId(ByteString.copyFrom(machineId))
                        .setSupportsRateLimitResponse(true)
                        .setAccountName(account.accountName)
                        .setShouldRememberPassword(account.shouldRememberPassword)
                        .setAccessToken(account.refreshToken)
                        .build()
                },
                headerSteamId = account.steamId,
            )
        } catch (error: Exception) {
            synchronized(transportLock) { reconnectPlan = null }
            // Reaching the logon stage is not evidence of expired credentials. Network,
            // routing and rate-limit errors must retain the account without a re-login dialog.
            if (error.isSteamCmCredentialRejection() && !closed) {
                runCatching { if (accountValidator(account)) onAccountLogonFailure(account, error) }
            }
            if (error is CancellationException || error is InterruptedException) throw error
            throw (error as? SteamProtocolException ?: SteamProtocolException("Steam CM logon failed", error))
                .also { it.isCmLogonFailure = true }
        }
    }

    /** A successful WebSocket handshake does not imply that this CM can route ClientLogon. */
    private suspend fun logonWithFailover(
        servers: List<CmServer>,
        requestLabel: String,
        buildBody: () -> CMsgClientLogon,
        headerSteamId: Long,
    ): SessionContext = try {
        withCmDeadline(serverSelector?.selectionAndLogonTimeoutMs ?: 180_000L, "CM logon") {
            logonSelectedServers(servers, requestLabel, buildBody, headerSteamId)
        }
    } catch (error: Exception) {
        closeTransport()
        throw error
    }

    private suspend fun logonSelectedServers(
        servers: List<CmServer>,
        requestLabel: String,
        buildBody: () -> CMsgClientLogon,
        headerSteamId: Long,
    ): SessionContext {
        require(servers.isNotEmpty()) { "No Steam CM servers available" }
        selectionScope = serverSelector?.scope() ?: "default"
        val selected = prepareCandidates(servers)
        val connected = currentServer?.takeIf { webSocket != null }
        val candidates = (listOfNotNull(connected) + selected)
            .distinctBy(CmServer::websocketUri).take(if (serverSelector == null) MAX_LOGON_ATTEMPTS else 20)
        var lastError: Exception? = null
        var logonAttempts = 0
        candidates.forEachIndexed { index, server ->
            checkOpen()
            currentCoroutineContext().ensureActive()
            val previousLogonAttempts = logonAttempts
            try {
                if (webSocket == null || currentServer != server) connectSingleServer(server)
                logonAttempts++
                return startLogon(requestLabel, buildBody, headerSteamId).also {
                    serverSelector?.loggedOn(selectionScope, server)
                }
            } catch (error: Exception) {
                closeTransport()
                // A local request timeout can use another CM; cancellation of the caller cannot.
                currentCoroutineContext().ensureActive()
                if (error is CancellationException) throw error
                val recoverable = if (error is SteamAuthenticationException) error.resultCode == 20
                    else isRecoverableConnectionFailure(error)
                if (!recoverable) throw error
                serverSelector?.failed(selectionScope, server)
                lastError = error
                if (logonAttempts >= MAX_LOGON_ATTEMPTS) {
                    throw SteamProtocolException("Steam session logon unavailable after $logonAttempts logon attempts", lastError)
                }
                // A dead transport is not an authentication attempt. Do not accumulate
                // seconds of login backoff for a list of DNS/TLS/connection failures.
                if (index < candidates.lastIndex) {
                    if (serverSelector == null) delay(LOGON_RETRY_DELAY_MS * (index + 1))
                    else if (logonAttempts > previousLogonAttempts) delay(LOGON_RETRY_DELAY_MS * logonAttempts)
                }
            }
        }
        throw SteamProtocolException("Steam session logon unavailable after ${candidates.size} CM attempts", lastError)
    }

    private suspend fun ensureConnected(servers: List<CmServer>) {
        if (webSocket != null) {
            return
        }
        try {
            withCmDeadline(serverSelector?.selectionAndLogonTimeoutMs ?: 180_000L, "CM connect") {
                ensureSelectedConnected(servers)
            }
        } catch (error: Exception) {
            closeTransport()
            throw error
        }
    }

    private suspend fun ensureSelectedConnected(servers: List<CmServer>) {
        require(servers.isNotEmpty()) { "No Steam CM servers available" }
        selectionScope = serverSelector?.scope() ?: "default"
        val candidates = prepareCandidates(servers)
        if (webSocket != null) return
        var lastError: Throwable? = null
        for (server in candidates.take(if (serverSelector == null) candidates.size else 20)) {
            try {
                connectSingleServer(server)
                return
            } catch (error: Exception) {
                closeTransport()
                currentCoroutineContext().ensureActive()
                if (error is CancellationException) throw error
                serverSelector?.failed(selectionScope, server)
                lastError = error
            }
        }

        throw SteamProtocolException("Unable to connect to any Steam CM websocket", lastError)
    }

    /** Keep a slow-but-usable connection alive while probing alternatives. Never race logons. */
    private suspend fun prepareCandidates(servers: List<CmServer>): List<CmServer> = coroutineScope {
        val selector = serverSelector ?: return@coroutineScope rotateServers(servers)
        currentServer?.takeIf { webSocket != null }?.let {
            return@coroutineScope listOf(it) + rotateServers(servers)
        }
        val preferred = selector.preferred(selectionScope, servers) ?: return@coroutineScope emptyList()
        val connection = async {
            try {
                connectSingleServer(preferred)
                true
            } catch (error: Exception) {
                closeTransport()
                currentCoroutineContext().ensureActive()
                if (error is CancellationException) throw error
                selector.failed(selectionScope, preferred)
                false
            }
        }
        val fast = withTimeoutOrNull(selector.optimisticConnectTimeoutMs) { connection.await() }
        if (fast == true) return@coroutineScope listOf(preferred) + rotateServers(servers)
        if (fast == false) return@coroutineScope selectServers(servers)
        val probes = async { selectServers(servers) }
        try {
            select {
                connection.onAwait { connected ->
                    if (connected) {
                        probes.cancelAndJoin()
                        listOf(preferred) + rotateServers(servers)
                    } else probes.await()
                }
                probes.onAwait { selected ->
                    connection.cancelAndJoin()
                    closeTransport()
                    selected
                }
            }
        } finally {
            connection.cancel()
            probes.cancel()
        }
    }

    private suspend fun selectServers(servers: List<CmServer>): List<CmServer> = coroutineScope {
        val job = currentCoroutineContext()[Job]!!
        synchronized(transportLock) {
            checkOpen()
            selectionJob = job
        }
        try {
            serverSelector!!.select(servers, selectionScope, forceProbe = true)
        } finally {
            synchronized(transportLock) { if (selectionJob === job) selectionJob = null }
        }
    }

    private suspend fun connectSingleServer(server: CmServer) {
        val deferred = CompletableDeferred<Unit>()
        val generation = synchronized(transportLock) {
            checkOpen()
            pendingConnect = deferred
            transportGeneration.incrementAndGet()
        }
        val request = Request.Builder().url(server.websocketUri).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(transportLock) {
                    if (transportGeneration.get() != generation) {
                        webSocket.cancel()
                        return
                    }
                    this@OkHttpSteamCmSession.webSocket = webSocket
                    this@OkHttpSteamCmSession.currentServer = server
                    sendHello()
                    deferred.complete(Unit)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                synchronized(transportLock) {
                    if (transportGeneration.get() != generation) return
                    handleIncomingPacket(bytes.toByteArray())
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(transportLock) {
                    if (transportGeneration.get() != generation) return
                    if (currentSession.value != null) serverSelector?.failed(selectionScope, server)
                    this@OkHttpSteamCmSession.webSocket = null
                    failActiveState(t)
                    deferred.completeExceptionallyIfNeeded(t)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(transportLock) {
                    if (transportGeneration.get() != generation) return
                    if (currentSession.value != null) serverSelector?.failed(selectionScope, server)
                    this@OkHttpSteamCmSession.webSocket = null
                    val failure = IOException("Steam websocket closed: $code $reason")
                    failActiveState(failure)
                    deferred.completeExceptionallyIfNeeded(failure)
                }
            }
        }

        synchronized(transportLock) {
            checkOpen()
            val socket = webSocketFactory.newWebSocket(request, listener)
            // A custom factory can invoke onFailure before newWebSocket returns.
            if (transportGeneration.get() != generation || deferred.isCompleted && currentServer == null) socket.cancel()
            else webSocket = socket
        }
        withCmDeadline(serverSelector?.probeTimeoutMs ?: REQUEST_TIMEOUT_MS, "CM websocket") { deferred.await() }
    }

    private fun sendHello() {
        val body = CMsgClientHello.newBuilder()
            .setProtocolVersion(SteamPacketCodec.clientLogonProtocol)
            .build()

        val packet = SteamPacketCodec.encode(
            emsg = SteamPacketCodec.emsgClientHello,
            header = CMsgProtoBufHeader.getDefaultInstance(),
            body = body,
        )
        check(webSocket?.send(packet.toByteString()) == true) { "Failed to send Steam ClientHello" }
    }

    private suspend fun startLogon(
        requestLabel: String,
        buildBody: () -> CMsgClientLogon,
        headerSteamId: Long,
    ): SessionContext {
        val logon = CompletableDeferred<SessionContext>()
        synchronized(transportLock) {
            checkOpen()
            pendingLogon = logon
            val packet = SteamPacketCodec.encode(
                emsg = SteamPacketCodec.emsgClientLogon,
                header = CMsgProtoBufHeader.newBuilder()
                    .setClientSessionid(0)
                    .setSteamid(headerSteamId)
                    .build(),
                body = buildBody(),
            )
            if (webSocket?.send(packet.toByteString()) != true) {
                val error = SteamProtocolException("Failed to send Steam $requestLabel logon")
                logon.completeExceptionallyIfNeeded(error)
                throw error
            }
        }
        return withCmDeadline(REQUEST_TIMEOUT_MS, "ClientLogon") { logon.await() }
    }

    private fun handleIncomingPacket(rawPacket: ByteArray) {
        if (!SteamPacketCodec.isProtoPacket(rawPacket)) {
            handleLegacyPacket(rawPacket)
            return
        }

        val packet = SteamPacketCodec.decode(rawPacket)
        if (packet.emsg == SteamPacketCodec.emsgMulti) {
            SteamPacketCodec.expandMulti(packet).forEach { nested ->
                handleIncomingPacket(nested)
            }
            return
        }

        val targetJobId = packet.header.jobidTarget
        if (targetJobId > 0L) {
            @Suppress("UNCHECKED_CAST")
            val request = pendingRequests[targetJobId] as PendingRequest<MessageLite>?
            if (request != null && request.accepts(packet.emsg)) {
                pendingRequests.remove(targetJobId)
                request.complete(packet)
                return
            }
        }

        when (packet.emsg) {
            SteamPacketCodec.emsgClientLogOnResponse -> {
                val response = CMsgClientLogonResponse.parseFrom(packet.body)
                if (response.eresult != 1) {
                    pendingLogon.completeExceptionallyIfNeeded(
                        SteamAuthenticationException(
                            resultCode = response.eresult,
                            message = buildSteamAuthenticationErrorMessage(
                                prefix = "Steam 会话登录失败",
                                resultCode = response.eresult,
                            ),
                        ),
                    )
                    return
                }
                val session = SessionContext(
                    sessionId = packet.header.clientSessionid,
                    steamId = packet.header.steamid,
                    cellId = response.cellId.toUInt(),
                    heartbeatSeconds = response.legacyOutOfGameHeartbeatSeconds.takeIf { it > 0 }
                        ?: response.heartbeatSeconds.takeIf { it > 0 }
                        ?: 30,
                )
                _currentSession.value = session
                startHeartbeat(session.heartbeatSeconds)
                pendingLogon.completeIfNeeded(session)
            }

            SteamPacketCodec.emsgClientLoggedOff -> {
                val body = CMsgClientLoggedOff.parseFrom(packet.body)
                val failure = SteamProtocolException(
                    "Steam session logged off by remote server EResult=${body.eresult}",
                )
                failActiveState(failure)
            }
        }
    }

    private fun handleLegacyPacket(rawPacket: ByteArray) {
        val packet = SteamPacketCodec.decodeLegacyPacket(rawPacket)
        when (packet.emsg) {
            SteamPacketCodec.emsgClientLoggedOff -> {
                val body = SteamPacketCodec.decodeLegacyLoggedOffBody(packet)
                failActiveState(
                    SteamProtocolException(
                        "Steam session logged off by remote server EResult=${body.resultCode} " +
                            "(legacy minReconnect=${body.minReconnectHintSeconds}s maxReconnect=${body.maxReconnectHintSeconds}s)",
                    ),
                )
            }

            SteamPacketCodec.emsgClientServerUnavailable -> {
                val body = SteamPacketCodec.decodeLegacyServerUnavailableBody(packet)
                failActiveState(
                    SteamServerUnavailableException(body.emsgSent, body.jobIdSent, body.serverTypeUnavailable),
                )
            }
        }
    }

    private fun startHeartbeat(intervalSeconds: Int) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(intervalSeconds.coerceAtLeast(10).toLong() * 1_000L)
                val session = _currentSession.value ?: break
                val packet = SteamPacketCodec.encode(
                    emsg = SteamPacketCodec.emsgClientHeartBeat,
                    header = CMsgProtoBufHeader.newBuilder()
                        .setClientSessionid(session.sessionId)
                        .setSteamid(session.steamId)
                        .build(),
                    body = CMsgClientHeartBeat.getDefaultInstance(),
                )
                webSocket?.send(packet.toByteString())
            }
        }
    }

    override suspend fun <T : MessageLite> callServiceMethod(
        methodName: String,
        request: MessageLite,
        parser: Parser<T>,
    ): T = retryRecoverableRequest { preventReplay ->
        if (webSocket == null) {
            throw SteamProtocolException("Steam CM session is not connected")
        }
        val session = currentSession.value
        val sourceJobId = nextJobId.getAndIncrement()
        val response = CompletableDeferred<T>()
        pendingRequests[sourceJobId] = PendingRequest(
            methodName = methodName,
            expectedEmsg = SteamPacketCodec.emsgServiceMethodResponse,
            parser = parser,
            deferred = response,
        )

        val packet = SteamPacketCodec.encode(
            emsg = if (session == null) {
                SteamPacketCodec.emsgServiceMethodCallFromClientNonAuthed
            } else {
                SteamPacketCodec.emsgServiceMethodCallFromClient
            },
            header = CMsgProtoBufHeader.newBuilder()
                .apply {
                    if (session != null) {
                        setClientSessionid(session.sessionId)
                        setSteamid(session.steamId)
                    }
                }
                .setJobidSource(sourceJobId)
                .setTargetJobName(methodName)
                .build(),
            body = request,
        )

        try {
            // Cloud writes have no client idempotency key. Once send is entered, neither a
            // disconnect nor a missing reply proves Steam did not execute this job.
            if (methodName.startsWith("Cloud.") && methodName !in replayableCloudReads) preventReplay()
            if (webSocket?.send(packet.toByteString()) != true) {
                throw SteamProtocolException("Failed to send Steam service request: $methodName")
            }
            withCmDeadline(REQUEST_TIMEOUT_MS, methodName) { response.await() }
        } finally {
            pendingRequests.remove(sourceJobId)
        }
    }

    override suspend fun <T : MessageLite> sendClientMessage(
        emsg: Int,
        request: MessageLite,
        responseEmsg: Int,
        parser: Parser<T>,
    ): T = sendClientMessage(emsg, request, responseEmsg, parser, null)

    override suspend fun <T : MessageLite> sendClientMessage(
        emsg: Int,
        request: MessageLite,
        responseEmsg: Int,
        parser: Parser<T>,
        routingAppId: UInt?,
    ): T = retryRecoverableRequest {
        val session = currentSession.value
            ?: throw SteamProtocolException("Steam CM session is not logged on")
        val sourceJobId = nextJobId.getAndIncrement()
        val response = CompletableDeferred<T>()
        pendingRequests[sourceJobId] = PendingRequest(
            methodName = "EMsg.$emsg",
            expectedEmsg = responseEmsg,
            parser = parser,
            deferred = response,
        )
        val header = CMsgProtoBufHeader.newBuilder()
            .setClientSessionid(session.sessionId)
            .setSteamid(session.steamId)
            .setJobidSource(sourceJobId)
            .apply { routingAppId?.let { setRoutingAppid(it.toInt()) } }
            .build()
        val packet = SteamPacketCodec.encode(
            emsg = emsg,
            header = header,
            body = request,
        )
        if (webSocket?.send(packet.toByteString()) != true) {
            pendingRequests.remove(sourceJobId)
            throw SteamProtocolException("Failed to send EMsg.$emsg")
        }
        try {
            withCmDeadline(REQUEST_TIMEOUT_MS, "EMsg.$emsg") { response.await() }
        } catch (error: Throwable) {
            pendingRequests.remove(sourceJobId)
            throw error
        }
    }

    override suspend fun sendClientMessage(emsg: Int, request: MessageLite) {
        checkOpen()
        (reconnectPlan as? ReconnectPlan.RefreshToken)?.let { checkAccount(it.account) }
        val session = currentSession.value
            ?: throw SteamProtocolException("Steam CM session is not logged on")
        val packet = SteamPacketCodec.encode(
            emsg = emsg,
            header = CMsgProtoBufHeader.newBuilder()
                .setClientSessionid(session.sessionId)
                .setSteamid(session.steamId)
                .setJobidSource(-1L)
                .setJobidTarget(-1L)
                .build(),
            body = request,
        )
        check(webSocket?.send(packet.toByteString()) == true) { "Failed to send EMsg.$emsg" }
    }

    override suspend fun sendClientMessage(emsg: Int, request: MessageLite, routingAppId: UInt) {
        checkOpen()
        (reconnectPlan as? ReconnectPlan.RefreshToken)?.let { checkAccount(it.account) }
        val session = currentSession.value
            ?: throw SteamProtocolException("Steam CM session is not logged on")
        val packet = SteamPacketCodec.encode(
            emsg = emsg,
            header = CMsgProtoBufHeader.newBuilder()
                .setClientSessionid(session.sessionId)
                .setSteamid(session.steamId)
                .setJobidSource(-1L)
                .setJobidTarget(-1L)
                .setRoutingAppid(routingAppId.toInt())
                .build(),
            body = request,
        )
        check(webSocket?.send(packet.toByteString()) == true) { "Failed to send EMsg.$emsg (routing_appid=$routingAppId)" }
    }

    override suspend fun requestDepotDecryptionKey(appId: UInt, depotId: UInt): ByteArray = retryRecoverableRequest {
        val session = currentSession.value
            ?: throw SteamProtocolException("Steam CM session is not connected")
        val sourceJobId = nextJobId.getAndIncrement()
        val response = CompletableDeferred<CMsgClientGetDepotDecryptionKeyResponse>()
        pendingRequests[sourceJobId] = PendingRequest(
            methodName = "ClientGetDepotDecryptionKey",
            expectedEmsg = SteamPacketCodec.emsgClientGetDepotDecryptionKeyResponse,
            parser = CMsgClientGetDepotDecryptionKeyResponse.parser(),
            deferred = response,
        )

        val packet = SteamPacketCodec.encode(
            emsg = SteamPacketCodec.emsgClientGetDepotDecryptionKey,
            header = CMsgProtoBufHeader.newBuilder()
                .setClientSessionid(session.sessionId)
                .setSteamid(session.steamId)
                .setJobidSource(sourceJobId)
                .build(),
            body = CMsgClientGetDepotDecryptionKey.newBuilder()
                .setAppId(appId.toInt())
                .setDepotId(depotId.toInt())
                .build(),
        )

        if (webSocket?.send(packet.toByteString()) != true) {
            pendingRequests.remove(sourceJobId)
            throw SteamProtocolException("Failed to request depot decryption key for depot=$depotId")
        }

        val body = withCmDeadline(REQUEST_TIMEOUT_MS, "ClientGetDepotDecryptionKey") { response.await() }
        if (body.eresult != 1) {
            throw SteamProtocolException(
                "Steam depot key request failed for depot=$depotId app=$appId with EResult=${body.eresult}",
            )
        }
        if (body.depotId.toUInt() != depotId) {
            throw SteamProtocolException(
                "Steam depot key response depot mismatch: expected=$depotId actual=${body.depotId.toUInt()}",
            )
        }
        val key = body.depotEncryptionKey.toByteArray()
        if (key.isEmpty()) {
            throw SteamProtocolException("Steam returned an empty depot key for depot=$depotId")
        }
        key
    }

    override suspend fun requestAppProductInfo(appId: UInt): SteamAppProductInfo = retryRecoverableRequest {
        val session = currentSession.value
            ?: throw SteamProtocolException("Steam CM session is not connected")
        val sourceJobId = nextJobId.getAndIncrement()
        val response = CompletableDeferred<CMsgClientPICSProductInfoResponse>()
        pendingRequests[sourceJobId] = PendingRequest(
            methodName = "ClientPICSProductInfo",
            expectedEmsg = SteamPacketCodec.emsgClientPICSProductInfoResponse,
            parser = CMsgClientPICSProductInfoResponse.parser(),
            deferred = response,
        )

        val packet = SteamPacketCodec.encode(
            emsg = SteamPacketCodec.emsgClientPICSProductInfoRequest,
            header = CMsgProtoBufHeader.newBuilder()
                .setClientSessionid(session.sessionId)
                .setSteamid(session.steamId)
                .setJobidSource(sourceJobId)
                .build(),
            body = CMsgClientPICSProductInfoRequest.newBuilder()
                .addApps(
                    CMsgClientPICSProductInfoRequest.AppInfo.newBuilder()
                        .setAppid(appId.toInt())
                        .setOnlyPublicObsolete(false)
                        .build(),
                )
                .setMetaDataOnly(false)
                .setSingleResponse(true)
                .build(),
        )

        if (webSocket?.send(packet.toByteString()) != true) {
            pendingRequests.remove(sourceJobId)
            throw SteamProtocolException("Failed to request Steam app product info for app=$appId")
        }

        val body = withCmDeadline(REQUEST_TIMEOUT_MS, "ClientPICSProductInfo") { response.await() }
        if (body.unknownAppidsList.any { it.toUInt() == appId }) {
            throw SteamProtocolException("Steam product info returned unknown app=$appId")
        }
        val appInfo = body.appsList.firstOrNull { it.appid.toUInt() == appId }
            ?: throw SteamProtocolException("Steam product info response did not include app=$appId")
        if (appInfo.missingToken) {
            throw SteamProtocolException("Steam product info requires an app access token for app=$appId")
        }
        val buffer = appInfo.buffer.toByteArray()
        if (buffer.isEmpty()) {
            throw SteamProtocolException("Steam product info returned an empty appinfo buffer for app=$appId")
        }
        SteamAppProductInfo(
            appId = appInfo.appid.toUInt(),
            changeNumber = appInfo.changeNumber.toUInt(),
            missingToken = appInfo.missingToken,
            buffer = buffer,
        )
    }

    override fun close() {
        synchronized(transportLock) {
            if (closed) return
            closed = true
            selectionJob?.cancel(SteamSessionRevokedException())
            selectionJob = null
            reconnectPlan = null
            activeSessions -= this
            closeTransport()
            scope.cancel()
        }
    }

    private fun checkOpen() {
        if (closed) throw SteamSessionRevokedException()
        if (!runCatching { sessionValidator() }.getOrDefault(false)) {
            close()
            throw SteamSessionRevokedException()
        }
    }

    private fun checkAccount(account: SteamAccountSession) {
        checkOpen()
        if (!runCatching { accountValidator(account) }.getOrDefault(false)) {
            close()
            throw SteamSessionRevokedException()
        }
    }

    companion object {
        private val activeSessions = ConcurrentHashMap.newKeySet<OkHttpSteamCmSession>()
        private const val OBFUSCATION_MASK = 0xBAADF00D.toInt()
        private const val REQUEST_TIMEOUT_MS = DEFAULT_HTTP_TIMEOUT_SECONDS * 1_000L
        private const val MAX_LOGON_ATTEMPTS = 3
        private const val LOGON_RETRY_DELAY_MS = 500L

        fun closeAllActiveSessions() {
            activeSessions.toList().forEach { session ->
                runCatching { session.close() }
            }
        }
    }

    private fun closeTransport() = synchronized(transportLock) {
        // A failed CM may send its onClosed/onFailure after the replacement already logged on.
        // Such callbacks must not clear the new session or fail its pending requests.
        transportGeneration.incrementAndGet()
        heartbeatJob?.cancel()
        heartbeatJob = null
        _currentSession.value = null
        currentServer = null
        val failure = if (closed) SteamSessionRevokedException() else SteamProtocolException("Steam CM session closed")
        pendingConnect?.completeExceptionallyIfNeeded(failure)
        pendingConnect = null
        failActiveState(failure)
        webSocket?.cancel()
        webSocket = null
    }

    private fun anonymousSteamId(): Long {
        val universe = 1L
        val accountType = 10L
        return (universe shl 56) or (accountType shl 52)
    }

    private fun defaultObfuscatedPrivateIp(): CMsgIPAddress {
        val ipv4 = detectLocalIpv4Address()
        val asUInt = ipv4.toUnsignedInt()
        val obfuscated = asUInt xor OBFUSCATION_MASK
        return CMsgIPAddress.newBuilder()
            .setV4(obfuscated.toInt())
            .build()
    }

    private fun detectLocalIpv4Address(): ByteArray =
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) {
                    continue
                }
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address) {
                        return@runCatching address.address
                    }
                }
            }
            null
        }.getOrNull()
            ?: byteArrayOf(127.toByte(), 0, 0, 1)

    private fun failActiveState(error: Throwable) {
        _currentSession.value = null
        currentServer = null
        pendingRequests.values.forEach { it.fail(error) }
        pendingRequests.clear()
        pendingLogon.completeExceptionallyIfNeeded(error)
    }

    private suspend fun reconnectForRetry() {
        val plan = reconnectPlan ?: throw SteamProtocolException("No saved Steam connection plan to retry request")
        closeTransport()
        when (plan) {
            is ReconnectPlan.TransportOnly -> connect(plan.servers)
            is ReconnectPlan.Anonymous -> connectAnonymous(plan.servers)
            is ReconnectPlan.RefreshToken -> connectWithRefreshToken(plan.servers, plan.account)
        }
    }

    private suspend fun <T> retryRecoverableRequest(block: suspend (preventReplay: () -> Unit) -> T): T {
        var shouldRetry = true
        while (true) {
            currentCoroutineContext().ensureActive()
            checkOpen()
            (reconnectPlan as? ReconnectPlan.RefreshToken)?.let { checkAccount(it.account) }
            val generation = transportGeneration.get()
            try {
                return block { shouldRetry = false }
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!shouldRetry || !isRecoverableConnectionFailure(error)) {
                    throw error
                }
                shouldRetry = false
                currentServer?.let { serverSelector?.failed(selectionScope, it) }
                reconnectMutex.lock()
                try {
                    checkOpen()
                    if (transportGeneration.get() == generation || currentSession.value == null) reconnectForRetry()
                } finally {
                    reconnectMutex.unlock()
                }
            }
        }
    }

    private fun isRecoverableConnectionFailure(error: Throwable): Boolean =
        when (error) {
            is SteamServiceMethodException -> false
            is SteamAuthenticationException -> false
            is IOException -> true
            is SteamProtocolException -> true
            else -> false
        }

    private fun rotateServers(servers: List<CmServer>): List<CmServer> {
        if (servers.size <= 1) {
            return servers
        }
        val active = currentServer ?: return servers
        val index = servers.indexOfFirst { it.endpoint == active.endpoint }
        if (index == -1) {
            return servers
        }
        return List(servers.size) { offset ->
            servers[(index + offset + 1) % servers.size]
        }
    }

    private sealed interface ReconnectPlan {
        val servers: List<CmServer>

        data class TransportOnly(
            override val servers: List<CmServer>,
        ) : ReconnectPlan

        data class Anonymous(
            override val servers: List<CmServer>,
        ) : ReconnectPlan

        data class RefreshToken(
            override val servers: List<CmServer>,
            val account: SteamAccountSession,
        ) : ReconnectPlan
    }

    private class PendingRequest<T : MessageLite>(
        private val methodName: String,
        private val expectedEmsg: Int,
        private val parser: Parser<T>,
        private val deferred: CompletableDeferred<T>,
    ) {
        fun accepts(emsg: Int): Boolean = emsg == expectedEmsg

        fun complete(packet: SteamPacket) {
            if (packet.header.hasEresult() && packet.header.eresult != 1) {
                deferred.completeExceptionallyIfNeeded(
                    SteamServiceMethodException(
                        methodName = methodName,
                        resultCode = packet.header.eresult,
                        steamMessage = if (packet.header.hasErrorMessage()) packet.header.errorMessage else null,
                    ),
                )
                return
            }
            if (!deferred.isCompleted) {
                deferred.complete(parser.parseFrom(packet.body))
            }
        }

        fun fail(error: Throwable) {
            if (!deferred.isCompleted) {
                deferred.completeExceptionally(error)
            }
        }
    }

    private fun ByteArray.toUnsignedInt(): Int {
        require(size == 4) { "IPv4 address must contain exactly 4 bytes" }
        return ((this[0].toInt() and 0xFF) shl 24) or
            ((this[1].toInt() and 0xFF) shl 16) or
            ((this[2].toInt() and 0xFF) shl 8) or
            (this[3].toInt() and 0xFF)
    }

}

private val replayableCloudReads = setOf("Cloud.GetAppFileChangelist#1", "Cloud.ClientFileDownload#1")

// withTimeoutOrNull consumes only this deadline. Parent cancellation is propagated unchanged.
private suspend fun <T : Any> withCmDeadline(ms: Long, operation: String, block: suspend () -> T): T =
    withTimeoutOrNull(ms) { block() } ?: throw java.net.SocketTimeoutException("Timed out waiting for $operation")

private fun <T> CompletableDeferred<T>.completeIfNeeded(value: T) {
    if (!isCompleted) {
        complete(value)
    }
}

private fun <T> CompletableDeferred<T>.completeExceptionallyIfNeeded(error: Throwable) {
    if (!isCompleted) {
        completeExceptionally(error)
    }
}

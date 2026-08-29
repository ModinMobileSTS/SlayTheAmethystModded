package io.stamethyst.backend.easytier

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.stamethyst.config.CloudControlConfig
import io.stamethyst.config.CloudControlEasyTierSettings
import io.stamethyst.config.CloudControlSettings

class EasyTierRoomApiClientTest {
    @Test
    fun parseStartSessionResponse_mapsRoomSessionFields() {
        val roots = TestRoots.create("easytier-room-api-parse-start")
        try {
            val client = EasyTierRoomApiClient(roots.context, OkHttpClient())
            val parsed = client.parseStartSessionResponse(
                """
                {
                  "sessionId": "sess-1",
                  "roomId": "room-alpha",
                  "mode": "community",
                  "entryNodeUrl": "tcp://online.example.com:11010",
                  "configServerUrl": "udp://online.example.com:22020",
                  "aclGroup": "player",
                  "networkSecret": "short-secret",
                  "assignedIpv4Cidr": "10.126.42.17/24",
                  "macAddress": "02:AA:BB:CC:DD:EE",
                  "expiresAt": 1720000000
                }
                """.trimIndent()
            )

            assertEquals("sess-1", parsed.sessionId)
            assertEquals("room-alpha", parsed.roomId)
            assertEquals(EasyTierNetworkMode.Community, parsed.mode)
            assertEquals("tcp://online.example.com:11010", parsed.entryNodeUrl)
            assertEquals("udp://online.example.com:22020", parsed.configServerUrl)
            assertEquals("player", parsed.aclGroup)
            assertEquals("short-secret", parsed.networkSecret)
            assertEquals("10.126.42.17/24", parsed.assignedIpv4Cidr)
            assertEquals("02:AA:BB:CC:DD:EE", parsed.macAddress)
            assertEquals(1720000000L, parsed.expiresAtEpochSeconds)
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun parseRoomInfoResponse_mapsMembers() {
        val roots = TestRoots.create("easytier-room-api-parse-room")
        try {
            val client = EasyTierRoomApiClient(roots.context, OkHttpClient())
            val parsed = client.parseRoomInfoResponse(
                """
                {
                  "roomId": "room-alpha",
                  "ownerPlayerId": "host-1",
                  "ownerDisplayName": "Host",
                  "description": "A relaxed run for new players",
                  "mode": "room",
                  "allowNewJoins": true,
                  "closedAtMs": 0,
                  "memberCount": 2,
                  "members": [
                    {
                      "playerId": "host-1",
                      "displayName": "Host",
                      "role": "owner",
                      "online": true,
                      "assignedIpv4Cidr": "10.144.0.1/24",
                      "mods": [
                        { "name": "Together in Spire", "workshopId": "2384072973" },
                        { "name": "Local test mod" }
                      ]
                    },
                    { "playerId": "player-2", "displayName": "Player 2", "role": "member", "online": false, "assignedIpv4Cidr": "10.144.0.2/24" }
                  ]
                }
                """.trimIndent()
            )

            assertEquals("room-alpha", parsed.roomId)
            assertEquals("host-1", parsed.ownerPlayerId)
            assertEquals("Host", parsed.ownerDisplayName)
            assertEquals("A relaxed run for new players", parsed.description)
            assertEquals(EasyTierNetworkMode.Room, parsed.mode)
            assertTrue(parsed.allowNewJoins)
            assertEquals(0L, parsed.closedAtMs)
            assertEquals(2, parsed.memberCount)
            assertEquals(2, parsed.members.size)
            assertEquals("member", parsed.members[1].role)
            assertEquals("10.144.0.1/24", parsed.members[0].assignedIpv4Cidr)
            assertEquals("10.144.0.2/24", parsed.members[1].assignedIpv4Cidr)
            assertEquals(
                listOf(
                    EasyTierRoomMod("Together in Spire", "2384072973"),
                    EasyTierRoomMod("Local test mod"),
                ),
                parsed.members[0].mods,
            )
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun startSession_postsRequestAndParsesResponse() {
        val roots = TestRoots.create("easytier-room-api-http")
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse(
                    200,
                    Headers.headersOf("Content-Type", "application/json"),
                    """
                    {
                      "sessionId": "sess-http",
                      "roomId": "room-http",
                      "mode": "room",
                      "entryNodeUrl": "tcp://127.0.0.1:11010",
                      "configServerUrl": "udp://127.0.0.1:22020",
                      "aclGroup": "player",
                      "networkSecret": "temporary-key",
                      "expiresAt": 1800000000
                    }
                    """.trimIndent()
                )
            )
            server.start()

            val client = EasyTierRoomApiClient(
                context = roots.context,
                client = OkHttpClient(),
            )
            roots.overrideBaseUrl(server.url("/").toString().removeSuffix("/"))

            val session = client.startSession(
                roomId = "room-http",
                playerId = "player-a",
                displayName = "Player A",
                roomDescriptionWhenCreating = "Two players wanted",
                mods = listOf(
                    EasyTierRoomMod("Together in Spire", "2384072973"),
                    EasyTierRoomMod("Local test mod"),
                ),
            )

            val request = server.takeRequest()
            assertEquals("/api/lan/session/start", request.target)
            assertEquals("POST", request.method)
            val body = request.body?.utf8().orEmpty()
            assertTrue(body.contains("\"roomId\":\"room-http\""))
            assertTrue(body.contains("\"playerId\":\"player-a\""))
            assertTrue(body.contains("\"displayName\":\"Player A\""))
            assertTrue(body.contains("\"description\":\"Two players wanted\""))
            assertReportedMods(body)
            assertEquals("sess-http", session.sessionId)
            assertEquals("room-http", session.roomId)
            assertEquals("temporary-key", session.networkSecret)
        } finally {
            server.close()
            roots.restoreDefaults()
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun parseSessionStatusResponse_mapsKickDetails() {
        val roots = TestRoots.create("easytier-room-api-parse-kicked")
        try {
            val client = EasyTierRoomApiClient(roots.context, OkHttpClient())
            val parsed = client.parseSessionStatusResponse(
                """
                {
                  "sessionId": "sess-kicked",
                  "roomId": "room-alpha",
                  "sessionState": "kicked",
                  "roomState": "active",
                  "peerCount": 1,
                  "kickMessage": "Please update the mod.",
                  "kickedAtMs": 1720000000123
                }
                """.trimIndent()
            )

            assertEquals("kicked", parsed.sessionState)
            assertEquals("Please update the mod.", parsed.kickMessage)
            assertEquals(1720000000123L, parsed.kickedAtMs)
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun startSession_summarizesStructuredServerErrorsWithoutDumpingTheWholePayload() {
        val roots = TestRoots.create("easytier-room-api-error-summary")
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse(
                    code = 403,
                    body = """
                        {
                          "ok": false,
                          "error": "bad_request",
                          "message": "Existing player credential is required",
                          "debug": "do-not-show-this-payload"
                        }
                    """.trimIndent(),
                )
            )
            server.start()

            val client = EasyTierRoomApiClient(roots.context, OkHttpClient())
            roots.overrideBaseUrl(server.url("/").toString().removeSuffix("/"))

            val error = runCatching {
                client.startSession(
                    roomId = "room-error",
                    playerId = "player-a",
                    displayName = "Player A",
                )
            }.exceptionOrNull() as EasyTierRoomApiHttpException

            assertEquals(403, error.statusCode)
            assertTrue(error.message.orEmpty().contains("HTTP 403"))
            assertTrue(error.message.orEmpty().contains("Existing player credential is required"))
            assertFalse(error.message.orEmpty().contains("do-not-show-this-payload"))
        } finally {
            server.close()
            roots.restoreDefaults()
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun fetchSessionStatus_parsesServerSnapshot() {
        val roots = TestRoots.create("easytier-room-api-status")
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse(
                    200,
                    Headers.headersOf("Content-Type", "application/json"),
                    """
                    {
                      "sessionId": "sess-2",
                      "roomId": "room-status",
                      "sessionState": "connected",
                      "roomState": "open",
                      "peerCount": 3,
                      "assignedIpv4Cidr": "10.144.0.2/24",
                      "relayServerDescription": "online.example.com:11010"
                    }
                    """.trimIndent()
                )
            )
            server.start()

            val client = EasyTierRoomApiClient(roots.context, OkHttpClient())
            roots.overrideBaseUrl(server.url("/").toString().removeSuffix("/"))

            val status = client.fetchSessionStatus("sess-2", "A".repeat(43))

            val request = server.takeRequest()
            assertEquals("/api/lan/session/status?sessionId=sess-2", request.target)
            assertEquals("GET", request.method)
            assertEquals("Bearer ${"A".repeat(43)}", request.headers["Authorization"])
            assertEquals("connected", status.sessionState)
            assertEquals(3, status.peerCount)
            assertEquals("10.144.0.2/24", status.assignedIpv4Cidr)
        } finally {
            server.close()
            roots.restoreDefaults()
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun fetchSessionStatus_encodesQueryParameter() {
        val roots = TestRoots.create("easytier-room-api-status-encoding")
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse(
                    200,
                    Headers.headersOf("Content-Type", "application/json"),
                    """
                    {
                      "sessionId": "sess with/slash?and=eq",
                      "roomId": "room-status",
                      "sessionState": "issued",
                      "roomState": "active"
                    }
                    """.trimIndent()
                )
            )
            server.start()

            val client = EasyTierRoomApiClient(roots.context, OkHttpClient())
            roots.overrideBaseUrl(server.url("/").toString().removeSuffix("/"))

            client.fetchSessionStatus("sess with/slash?and=eq", "A".repeat(43))

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertTrue(request.target.startsWith("/api/lan/session/status?sessionId="))
            assertTrue(request.target.contains("sess%20with"))
            assertTrue(request.target.contains("and%3Deq"))
            assertEquals("Bearer ${"A".repeat(43)}", request.headers["Authorization"])
        } finally {
            server.close()
            roots.restoreDefaults()
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun reportSessionRuntime_postsPayloadAndParsesResponse() {
        val roots = TestRoots.create("easytier-room-api-runtime-report")
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse(
                    200,
                    Headers.headersOf("Content-Type", "application/json"),
                    """
                    {
                      "sessionId": "sess-runtime",
                      "roomId": "room-runtime",
                      "sessionState": "connected",
                      "roomState": "active",
                      "peerCount": 2,
                      "assignedIpv4Cidr": "10.144.0.9/24",
                      "relayServerDescription": "single-server relay"
                    }
                    """.trimIndent()
                )
            )
            server.start()

            val client = EasyTierRoomApiClient(roots.context, OkHttpClient())
            roots.overrideBaseUrl(server.url("/").toString().removeSuffix("/"))

            val status = client.reportSessionRuntime(
                sessionId = "sess-runtime",
                sessionToken = "A".repeat(43),
                assignedIpv4Cidr = "10.144.0.9/24",
                relayServerDescription = "single-server relay",
            )

            val request = server.takeRequest()
            assertEquals("/api/lan/session/runtime", request.target)
            assertEquals("POST", request.method)
            val body = request.body?.utf8().orEmpty()
            assertTrue(body.contains("\"sessionId\":\"sess-runtime\""))
            assertFalse(body.contains("sessionToken"))
            assertEquals("Bearer ${"A".repeat(43)}", request.headers["Authorization"])
            assertTrue(body.contains("\"assignedIpv4Cidr\":\"10.144.0.9/24\""))
            assertTrue(body.contains("\"relayServerDescription\":\"single-server relay\""))
            assertEquals("connected", status.sessionState)
            assertEquals("10.144.0.9/24", status.assignedIpv4Cidr)
        } finally {
            server.close()
            roots.restoreDefaults()
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun reportSessionMods_postsModListWithSessionCredential() {
        val roots = TestRoots.create("easytier-room-api-mod-report")
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse(
                    200,
                    Headers.headersOf("Content-Type", "application/json"),
                    """{ "ok": true, "sessionId": "sess-mods", "roomId": "room-mods", "reportedModCount": 2 }""",
                )
            )
            server.start()

            val client = EasyTierRoomApiClient(roots.context, OkHttpClient())
            roots.overrideBaseUrl(server.url("/").toString().removeSuffix("/"))

            client.reportSessionMods(
                sessionId = "sess-mods",
                sessionToken = "A".repeat(43),
                mods = listOf(
                    EasyTierRoomMod("Together in Spire", "2384072973"),
                    EasyTierRoomMod("Local test mod"),
                ),
            )

            val request = server.takeRequest()
            assertEquals("/api/lan/session/mods", request.target)
            assertEquals("POST", request.method)
            assertEquals("Bearer ${"A".repeat(43)}", request.headers["Authorization"])
            val body = request.body?.utf8().orEmpty()
            assertTrue(body.contains("\"sessionId\":\"sess-mods\""))
            assertReportedMods(body)
            assertFalse(body.contains("sessionToken"))
        } finally {
            server.close()
            roots.restoreDefaults()
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun reportSessionRuntime_exposesHttpStatusForCompatibilityFallback() {
        val roots = TestRoots.create("easytier-room-api-runtime-report-404")
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse(
                    code = 404,
                    body = "{\"message\":\"route not found\"}",
                )
            )
            server.start()

            val client = EasyTierRoomApiClient(roots.context, OkHttpClient())
            roots.overrideBaseUrl(server.url("/").toString().removeSuffix("/"))

            val error = runCatching {
                client.reportSessionRuntime(
                    sessionId = "sess-runtime",
                    sessionToken = "A".repeat(43),
                    assignedIpv4Cidr = "10.144.0.9/24",
                )
            }.exceptionOrNull()

            assertEquals(404, (error as EasyTierRoomApiHttpException).statusCode)
        } finally {
            server.close()
            roots.restoreDefaults()
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun fetchRoomInfo_encodesRoomIdPathSegment() {
        val roots = TestRoots.create("easytier-room-api-room-encoding")
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse(
                    200,
                    Headers.headersOf("Content-Type", "application/json"),
                    """
                    {
                      "roomId": "room/a b",
                      "ownerPlayerId": "owner",
                      "ownerDisplayName": "Owner",
                      "mode": "room",
                      "allowNewJoins": true,
                      "closedAtMs": 0,
                      "memberCount": 0,
                      "members": []
                    }
                    """.trimIndent()
                )
            )
            server.start()

            val client = EasyTierRoomApiClient(roots.context, OkHttpClient())
            roots.overrideBaseUrl(server.url("/").toString().removeSuffix("/"))

            val room = client.fetchRoomInfo("room/a b")

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/lan/rooms/room%2Fa%20b", request.target)
            assertEquals("room/a b", room.roomId)
        } finally {
            server.close()
            roots.restoreDefaults()
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun parseRoomListResponse_mapsRoomCards() {
        val roots = TestRoots.create("easytier-room-api-parse-room-list")
        try {
            val client = EasyTierRoomApiClient(roots.context, OkHttpClient())
            val parsed = client.parseRoomListResponse(
                """
                {
                  "rooms": [
                    {
                      "roomId": "alpha-room",
                      "ownerPlayerId": "alice",
                      "ownerDisplayName": "Alice",
                      "description": "Two players wanted",
                      "mode": "room",
                      "allowNewJoins": true,
                      "closedAtMs": 0,
                      "memberCount": 3,
                      "onlineMemberCount": 2,
                      "roomState": "active",
                      "lastSessionStartedAtMs": 123,
                      "updatedAtMs": 456
                    }
                  ]
                }
                """.trimIndent()
            )

            assertEquals(1, parsed.size)
            assertEquals("alpha-room", parsed[0].roomId)
            assertEquals("alice", parsed[0].ownerPlayerId)
            assertEquals("Alice", parsed[0].ownerDisplayName)
            assertEquals("Two players wanted", parsed[0].description)
            assertEquals(EasyTierNetworkMode.Room, parsed[0].mode)
            assertTrue(parsed[0].allowNewJoins)
            assertEquals(0L, parsed[0].closedAtMs)
            assertEquals(3, parsed[0].memberCount)
            assertEquals(2, parsed[0].onlineMemberCount)
            assertEquals("active", parsed[0].roomState)
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun startSession_postsCreationJoinPolicy() {
        val roots = TestRoots.create("easytier-room-api-start-session-policy")
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse(
                    200,
                    Headers.headersOf("Content-Type", "application/json"),
                    """
                    {
                      "sessionId": "lan_alpha",
                      "roomId": "alpha-room",
                      "mode": "room",
                      "entryNodeUrl": "tcp://online.example.com:11010",
                      "configServerUrl": "udp://online.example.com:22020",
                      "aclGroup": "room-alpha",
                      "networkSecret": "temporary-key",
                      "expiresAt": 1800000000
                    }
                    """.trimIndent()
                )
            )
            server.start()

            val client = EasyTierRoomApiClient(roots.context, OkHttpClient())
            roots.overrideBaseUrl(server.url("/").toString().removeSuffix("/"))

            val session = client.startSession(
                roomId = "alpha-room",
                playerId = "alice",
                displayName = "Alice",
                allowNewJoinsWhenCreating = false,
                createOnly = true,
            )

            val request = server.takeRequest()
            assertEquals("/api/lan/session/start", request.target)
            assertEquals("POST", request.method)
            val body = request.body?.utf8().orEmpty()
            assertTrue(body.contains("\"roomId\":\"alpha-room\""))
            assertTrue(body.contains("\"playerId\":\"alice\""))
            assertTrue(body.contains("\"allowNewJoins\":false"))
            assertTrue(body.contains("\"createOnly\":true"))
            assertEquals("lan_alpha", session.sessionId)
            assertEquals("alpha-room", session.roomId)
        } finally {
            server.close()
            roots.restoreDefaults()
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun lockRoom_postsActionAndParsesResponse() {
        val roots = TestRoots.create("easytier-room-api-lock-room")
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse(
                    200,
                    Headers.headersOf("Content-Type", "application/json"),
                    """
                    {
                      "roomId": "alpha-room",
                      "ownerPlayerId": "alice",
                      "ownerDisplayName": "Alice",
                      "mode": "room",
                      "allowNewJoins": false,
                      "closedAtMs": 0,
                      "memberCount": 1,
                      "members": []
                    }
                    """.trimIndent()
                )
            )
            server.start()

            val client = EasyTierRoomApiClient(roots.context, OkHttpClient())
            roots.overrideBaseUrl(server.url("/").toString().removeSuffix("/"))

            val room = client.lockRoom(
                roomId = "alpha-room",
                ownerToken = "A".repeat(43),
                sessionToken = "B".repeat(43),
            )

            val request = server.takeRequest()
            assertEquals("/api/lan/rooms/alpha-room/action", request.target)
            assertEquals("POST", request.method)
            val body = request.body?.utf8().orEmpty()
            assertFalse(body.contains("ownerToken"))
            assertEquals("A".repeat(43), request.headers["X-Lan-Owner-Token"])
            assertEquals("Bearer ${"B".repeat(43)}", request.headers["Authorization"])
            assertTrue(body.contains("\"action\":\"lock\""))
            assertEquals(false, room.allowNewJoins)
        } finally {
            server.close()
            roots.restoreDefaults()
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun kickMember_postsTargetAndOptionalMessage() {
        val roots = TestRoots.create("easytier-room-api-kick-member")
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse(
                    200,
                    Headers.headersOf("Content-Type", "application/json"),
                    """
                    {
                      "roomId": "alpha-room",
                      "ownerPlayerId": "alice",
                      "ownerDisplayName": "Alice",
                      "mode": "room",
                      "allowNewJoins": true,
                      "closedAtMs": 0,
                      "memberCount": 1,
                      "members": []
                    }
                    """.trimIndent()
                )
            )
            server.start()

            val client = EasyTierRoomApiClient(roots.context, OkHttpClient())
            roots.overrideBaseUrl(server.url("/").toString().removeSuffix("/"))

            client.kickMember(
                roomId = "alpha-room",
                ownerToken = "A".repeat(43),
                sessionToken = "B".repeat(43),
                targetPlayerId = "bob",
                message = "Please update the mod.",
            )

            val request = server.takeRequest()
            assertEquals("/api/lan/rooms/alpha-room/action", request.target)
            assertEquals("POST", request.method)
            val body = request.body?.utf8().orEmpty()
            assertTrue(body.contains("\"action\":\"kick\""))
            assertTrue(body.contains("\"targetPlayerId\":\"bob\""))
            assertTrue(body.contains("\"message\":\"Please update the mod.\""))
            assertFalse(body.contains("ownerToken"))
            assertEquals("A".repeat(43), request.headers["X-Lan-Owner-Token"])
            assertEquals("Bearer ${"B".repeat(43)}", request.headers["Authorization"])
        } finally {
            server.close()
            roots.restoreDefaults()
            roots.rootDir.deleteRecursively()
        }
    }

    /**
     * The Room API is polled every few seconds, so the client must be shared and bounded.
     *
     * A per-call [OkHttpClient] gave every request its own connection pool, so each poll reopened a
     * TCP+TLS connection while the server still held the previous one for its keep-alive window, and
     * a bare builder left OkHttp's 10s connect default as the only bound — longer than the poll
     * interval itself, which pushed lease renewal past the server lease and expired live sessions.
     */
    @Test
    fun defaultHttpClient_isSharedAcrossCalls() {
        assertTrue(
            EasyTierRoomApiClient.defaultHttpClient() === EasyTierRoomApiClient.defaultHttpClient()
        )
    }

    @Test
    fun defaultHttpClient_boundsEveryPhaseOfACall() {
        val client = EasyTierRoomApiClient.defaultHttpClient()

        assertTrue(client.connectTimeoutMillis > 0)
        assertTrue(client.readTimeoutMillis > 0)
        assertTrue(client.writeTimeoutMillis > 0)
        // Without a call timeout a request that keeps making slow progress can still outlive
        // several poll intervals even when each individual phase is bounded.
        assertTrue(client.callTimeoutMillis > 0)
    }

    @Test
    fun defaultHttpClient_connectBudgetStaysUnderThePollInterval() {
        // A connect that outlives the tick which scheduled it is what let polls pile up and drift
        // the effective renewal cadence past the server's session lease.
        val pollIntervalMillis = 5_000
        assertTrue(
            EasyTierRoomApiClient.defaultHttpClient().connectTimeoutMillis < pollIntervalMillis
        )
    }

    private fun assertReportedMods(body: String) {
        val mods = requireNotNull(Json.parseToJsonElement(body).jsonObject["mods"]).jsonArray
        assertEquals(2, mods.size)
        assertEquals("Together in Spire", mods[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("2384072973", mods[0].jsonObject["workshopId"]?.jsonPrimitive?.content)
        assertEquals("Local test mod", mods[1].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals(null, mods[1].jsonObject["workshopId"])
    }

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context,
        private val originalSettings: CloudControlSettings,
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                val externalFilesDir = File(rootDir, "external-files").apply { mkdirs() }
                val original = CloudControlConfig.current()
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getExternalFilesDir(type: String?): File = externalFilesDir

                        override fun getApplicationContext(): Context = this

                        override fun getPackageName(): String = "io.stamethyst.test"
                    },
                    originalSettings = original,
                )
            }
        }

        fun overrideBaseUrl(baseUrl: String) {
            setCurrentSettings(
                CloudControlSettings(
                    heartbeatIntervalSeconds = originalSettings.heartbeatIntervalSeconds,
                    heartbeatWsUrl = originalSettings.heartbeatWsUrl,
                    qqGroupNumber = originalSettings.qqGroupNumber,
                    steamDepotKeys = originalSettings.steamDepotKeys,
                    easyTier = CloudControlEasyTierSettings(
                        enabled = true,
                        roomApiBaseUrl = baseUrl,
                        webConsoleApiBaseUrl = originalSettings.easyTier.webConsoleApiBaseUrl,
                        configServerUrl = originalSettings.easyTier.configServerUrl,
                        entryNodeUrl = "tcp://online.example.com:11010",
                        connectTimeoutSeconds = originalSettings.easyTier.connectTimeoutSeconds,
                        statusPollIntervalSeconds = originalSettings.easyTier.statusPollIntervalSeconds,
                        allowSharedCommunityNetwork = originalSettings.easyTier.allowSharedCommunityNetwork,
                        defaultMode = originalSettings.easyTier.defaultMode,
                    )
                )
            )
        }

        fun restoreDefaults() {
            setCurrentSettings(originalSettings)
        }

        private fun setCurrentSettings(settings: CloudControlSettings) {
            val field = CloudControlConfig::class.java.getDeclaredField("currentSettings")
            field.isAccessible = true
            field.set(CloudControlConfig, settings)
        }
    }
}

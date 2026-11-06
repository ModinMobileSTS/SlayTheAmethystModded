package io.stamethyst.backend.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyTierSessionControllerTest {
    @Test
    fun runtimeRoutesAreClearedOnFailureDisconnectPermissionChangeAndNewSession() {
        val connected = EasyTierConnectionSnapshot(
            enabled = true, canConnect = true, status = EasyTierConnectionStatus.CONNECTED,
            mode = EasyTierNetworkMode.Room, sessionId = "old-session", roomId = "old-room",
            peerConnections = listOf(EasyTierPeerConnection("10.0.0.2", EasyTierPeerConnectionKind.Direct, "udp")),
            peerConnectionsValidUntilMs = 30_000L,
        )
        val roots = EasyTierTestRoots.create("easytier-peer-lifecycle")
        try {
            val changed = listOf(
                EasyTierSessionController.buildFailureSnapshot(connected, "runtime failed"),
                EasyTierSessionController.buildDisconnectedSnapshot(connected),
                EasyTierSessionController.buildPermissionRequiredSnapshot(roots.context, connected),
                EasyTierSessionController.buildPermissionRevokedSnapshot(roots.context, connected),
                EasyTierSessionController.buildSessionReadySnapshot(connected, EasyTierRoomSessionConfig(
                    sessionId = "new-session", roomId = "new-room", mode = EasyTierNetworkMode.Room,
                    entryNodeUrl = "udp://example.com:55550",
                )),
                EasyTierSessionController.persistSnapshot(roots.context, connected.copy(status = EasyTierConnectionStatus.RECONNECTING)),
            )
            changed.forEach { snapshot ->
                assertTrue(snapshot.peerConnections.isEmpty())
                assertEquals(0L, snapshot.peerConnectionsValidUntilMs)
            }
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun resolveRequestedRoomId_fallsBackToSharedLobbyWhenBlank() {
        assertEquals(
            DEFAULT_EASYTIER_SHARED_ROOM_ID,
            EasyTierSessionController.resolveRequestedRoomId(
                mode = EasyTierNetworkMode.Room,
                requestedRoomId = "",
            )
        )
    }

    @Test
    fun buildInitialSnapshot_marksDisconnectedWhenCloudControlIsReady() {
        val roots = EasyTierTestRoots.create("easytier-session-ready")
        try {
            val snapshot = EasyTierSessionController.buildInitialSnapshot(
                context = roots.context,
                config = EasyTierResolvedConfig(
                    enabled = true,
                    defaultMode = EasyTierNetworkMode.Room,
                    roomApiBaseUrl = "https://online.example.com",
                    webConsoleApiBaseUrl = "https://online.example.com",
                    configServerUrl = "udp://online.example.com:22020",
                    entryNodeUrl = "tcp://online.example.com:11010",
                    connectTimeoutSeconds = 20,
                    statusPollIntervalSeconds = 5,
                    allowSharedCommunityNetwork = false,
                ),
                nowMs = 42L,
            )

            assertEquals(EasyTierConnectionStatus.DISCONNECTED, snapshot.status)
            assertEquals(EasyTierFailureCategory.None, snapshot.failureCategory)
            assertEquals("", snapshot.lastErrorSummary)
            assertEquals(42L, snapshot.lastUpdatedAtMs)
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun buildInitialSnapshot_reportsConfigFailureWhenEntryNodeMissing() {
        val roots = EasyTierTestRoots.create("easytier-session-config-missing")
        try {
            val snapshot = EasyTierSessionController.buildInitialSnapshot(
                context = roots.context,
                config = EasyTierResolvedConfig(
                    enabled = true,
                    defaultMode = EasyTierNetworkMode.Room,
                    roomApiBaseUrl = "",
                    webConsoleApiBaseUrl = "",
                    configServerUrl = "",
                    entryNodeUrl = "",
                    connectTimeoutSeconds = 20,
                    statusPollIntervalSeconds = 5,
                    allowSharedCommunityNetwork = false,
                ),
                nowMs = 84L,
            )

            assertEquals(EasyTierConnectionStatus.FAILED, snapshot.status)
            assertEquals(EasyTierFailureCategory.ConfigMissing, snapshot.failureCategory)
            assertTrue(snapshot.lastErrorSummary.isNotBlank())
            assertEquals(84L, snapshot.lastUpdatedAtMs)
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun buildDisconnectedSnapshot_clearsRoomSessionFields() {
        val snapshot = EasyTierSessionController.buildDisconnectedSnapshot(
            previous = EasyTierConnectionSnapshot(
                enabled = true,
                canConnect = true,
                status = EasyTierConnectionStatus.CONNECTING,
                mode = EasyTierNetworkMode.Room,
                sessionId = "sess-1",
                roomId = "room-1",
                entryNodeUrl = "tcp://online.example.com:11010",
                configServerUrl = "udp://online.example.com:22020",
                aclGroup = "player",
                expiresAtEpochSeconds = 1_720_000_000L,
                startedAtMs = 10L,
                connectedAtMs = 20L,
                lastUpdatedAtMs = 30L,
                currentPlayerId = "player-1",
                roomOwnerPlayerId = "player-1",
                roomOwnerIpv4Cidr = "10.144.0.1/24",
            ),
            summary = "disconnect",
            failureCategory = EasyTierFailureCategory.SessionKicked,
            terminalSessionState = "kicked",
            nowMs = 40L,
        )

        assertEquals(EasyTierConnectionStatus.DISCONNECTED, snapshot.status)
        assertEquals("", snapshot.sessionId)
        assertEquals("", snapshot.aclGroup)
        assertNull(snapshot.expiresAtEpochSeconds)
        assertEquals("disconnect", snapshot.lastErrorSummary)
        assertEquals(EasyTierFailureCategory.SessionKicked, snapshot.failureCategory)
        assertEquals("kicked", snapshot.lastSessionState)
        assertEquals("player-1", snapshot.currentPlayerId)
        assertEquals("", snapshot.roomOwnerPlayerId)
        assertEquals("", snapshot.roomOwnerIpv4Cidr)
        assertEquals(40L, snapshot.lastUpdatedAtMs)
    }

    @Test
    fun buildSessionReadySnapshot_carriesRoomSessionAndStatusDetails() {
        val snapshot = EasyTierSessionController.buildSessionReadySnapshot(
            previous = EasyTierConnectionSnapshot(
                enabled = true,
                canConnect = true,
                status = EasyTierConnectionStatus.CONNECTING,
                mode = EasyTierNetworkMode.Room,
                entryNodeUrl = "tcp://online.example.com:11010",
                configServerUrl = "udp://online.example.com:22020",
                lastUpdatedAtMs = 10L,
                currentPlayerId = "player-1",
            ),
            sessionConfig = EasyTierRoomSessionConfig(
                sessionId = "sess-1",
                roomId = "room-1",
                mode = EasyTierNetworkMode.Room,
                entryNodeUrl = "tcp://online.example.com:11010",
                configServerUrl = "udp://online.example.com:22020",
                aclGroup = "room-alpha",
                expiresAtEpochSeconds = 1_720_000_000L,
            ),
            sessionStatus = EasyTierSessionStatusSnapshot(
                sessionId = "sess-1",
                roomId = "room-1",
                sessionState = "issued",
                roomState = "active",
                peerCount = 2,
                assignedIpv4Cidr = "10.144.0.2/24",
                relayServerDescription = "online.example.com:11010",
            ),
            currentPlayerId = "player-1",
            nowMs = 20L,
        )

        assertEquals(EasyTierConnectionStatus.SESSION_READY, snapshot.status)
        assertEquals("sess-1", snapshot.sessionId)
        assertEquals("room-1", snapshot.roomId)
        assertEquals("room-alpha", snapshot.aclGroup)
        assertEquals("10.144.0.2/24", snapshot.assignedIpv4Cidr)
        assertEquals("player-1", snapshot.currentPlayerId)
        assertEquals(2, snapshot.peerCount)
        assertEquals("online.example.com:11010", snapshot.relayServerDescription)
        assertEquals(20L, snapshot.lastUpdatedAtMs)
    }
}

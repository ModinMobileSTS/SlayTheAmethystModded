package io.stamethyst.backend.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the lease-renewal and error-classification rules that keep an EasyTier session alive.
 *
 * The failure these guard against: the Room API only renews a session lease when the client posts
 * a runtime report, the client only posted one on the fully healthy path, and any 404 was read as
 * "this server has no runtime endpoint". A brief runtime stall therefore stopped renewal, the
 * server expired the session after its TTL, and every subsequent request returned 404 — an
 * unrecoverable disconnect from a recoverable hiccup.
 */
class EasyTierSessionLeaseTest {

    @Test
    fun sessionGone_whenServerReportsSessionNotFound() {
        assertTrue(
            isEasyTierSessionGone(
                EasyTierRoomApiHttpException(
                    statusCode = 404,
                    message = "LAN session not found",
                    errorCode = EasyTierRoomApiHttpException.ERROR_CODE_SESSION_NOT_FOUND,
                )
            )
        )
    }

    @Test
    fun sessionGone_whenServerReportsRoomNotFound() {
        // The room being deleted takes the session with it, so this is equally terminal.
        assertTrue(
            isEasyTierSessionGone(
                EasyTierRoomApiHttpException(
                    statusCode = 404,
                    message = "LAN room not found",
                    errorCode = "lan_room_not_found",
                )
            )
        )
    }

    @Test
    fun sessionGone_whenLegacyServerSendsUnlabelled404() {
        // Servers predating the error codes cannot say more than the status, so an unlabelled 404
        // from the status endpoint is still honoured as terminal for backwards compatibility.
        assertTrue(
            isEasyTierSessionGone(
                EasyTierRoomApiHttpException(statusCode = 404, message = "Not Found")
            )
        )
    }

    @Test
    fun sessionNotGone_forNonNotFoundStatuses() {
        // A conflict means the session exists but is not accepting this report; a 5xx is a server
        // fault. Neither should end the session.
        assertFalse(
            isEasyTierSessionGone(
                EasyTierRoomApiHttpException(
                    statusCode = 409,
                    message = "LAN session is no longer active",
                )
            )
        )
        assertFalse(
            isEasyTierSessionGone(
                EasyTierRoomApiHttpException(statusCode = 500, message = "Boom")
            )
        )
        assertFalse(
            isEasyTierSessionGone(
                EasyTierRoomApiHttpException(statusCode = 502, message = "Bad Gateway")
            )
        )
    }

    @Test
    fun sessionMissing_isDistinctFromUnimplementedEndpoint() {
        val missingSession = EasyTierRoomApiHttpException(
            statusCode = 404,
            message = "LAN session not found",
            errorCode = EasyTierRoomApiHttpException.ERROR_CODE_SESSION_NOT_FOUND,
        )
        // The whole point of the error code: this must not disable lease renewal.
        assertTrue(missingSession.isSessionMissing)
        assertFalse(missingSession.isPossiblyUnimplementedEndpoint)

        val unlabelled404 = EasyTierRoomApiHttpException(statusCode = 404, message = "Not Found")
        assertFalse(unlabelled404.isSessionMissing)
        assertTrue(unlabelled404.isPossiblyUnimplementedEndpoint)
    }

    @Test
    fun unimplementedEndpoint_requiresNotFoundStatus() {
        // Only a 404 can mean "no such route"; other failures must not latch the renewal off.
        assertFalse(
            EasyTierRoomApiHttpException(statusCode = 409, message = "Conflict")
                .isPossiblyUnimplementedEndpoint
        )
        assertFalse(
            EasyTierRoomApiHttpException(statusCode = 500, message = "Boom")
                .isPossiblyUnimplementedEndpoint
        )
    }

    private fun snapshot(sessionId: String) = EasyTierConnectionSnapshot(
        enabled = true,
        canConnect = true,
        status = EasyTierConnectionStatus.CONNECTED,
        mode = EasyTierNetworkMode.Room,
        sessionId = sessionId,
    )

    @Test
    fun runtimeReportGate_requiresSessionIdAndAddress() {
        val withSession = snapshot("lan_abc")
        assertTrue(
            shouldReportEasyTierRuntime(
                snapshot = withSession,
                assignedIpv4Cidr = "10.126.5.184/24",
            )
        )
        // Without an address the server would reject a static-IP session, so there is nothing to
        // send yet.
        assertFalse(
            shouldReportEasyTierRuntime(snapshot = withSession, assignedIpv4Cidr = "")
        )
        assertFalse(
            shouldReportEasyTierRuntime(
                snapshot = snapshot(""),
                assignedIpv4Cidr = "10.126.5.184/24",
            )
        )
    }

    @Test
    fun errorCodeDefaultsToBlank() {
        // Callers must be able to rely on a non-null code, so the default has to be empty rather
        // than absent.
        assertEquals("", EasyTierRoomApiHttpException(statusCode = 404, message = "x").errorCode)
    }

    private fun config(connectTimeoutSeconds: Int = 12) = EasyTierResolvedConfig(
        enabled = true,
        defaultMode = EasyTierNetworkMode.Room,
        roomApiBaseUrl = "https://online.example.com",
        webConsoleApiBaseUrl = "https://online.example.com",
        configServerUrl = "udp://online.example.com:22020",
        entryNodeUrl = "tcp://online.example.com:11010",
        connectTimeoutSeconds = connectTimeoutSeconds,
        statusPollIntervalSeconds = 5,
        allowSharedCommunityNetwork = false,
    )

    @Test
    fun failureSnapshot_keepsAddressSoLeaseRenewalSurvives() {
        // Blanking the address here stopped runtime reports, which are the only lease heartbeat.
        // The server then expired the session after its TTL and the next poll got a 404, turning
        // one failed request into a permanent disconnect.
        val connected = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.CONNECTED,
            mode = EasyTierNetworkMode.Room,
            sessionId = "lan_abc",
            startedAtMs = 1_000L,
            connectedAtMs = 5_000L,
            assignedIpv4Cidr = "10.126.5.184/24",
            peerCount = 3,
            relayServerDescription = "relay-1",
        )

        val failed = EasyTierSessionController.buildFailureSnapshot(
            previous = connected,
            summary = "timeout",
            nowMs = 30_000L,
        )

        assertEquals(EasyTierConnectionStatus.FAILED, failed.status)
        assertEquals("10.126.5.184/24", failed.assignedIpv4Cidr)
        assertTrue(
            shouldReportEasyTierRuntime(
                snapshot = failed,
                assignedIpv4Cidr = failed.assignedIpv4Cidr,
            )
        )
        // Point-in-time room observations must not be presented as current while broken.
        assertEquals(null, failed.peerCount)
        assertEquals("", failed.relayServerDescription)
    }

    @Test
    fun failureSnapshot_keepsConnectedAtSoRetriesAreNotKilled() {
        // connectedAtMs is the only evidence the tunnel ever worked, which is what stops the
        // connect budget from acting as a kill switch for an established session.
        val connected = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.CONNECTED,
            mode = EasyTierNetworkMode.Room,
            sessionId = "lan_abc",
            startedAtMs = 1_000L,
            connectedAtMs = 5_000L,
            assignedIpv4Cidr = "10.126.5.184/24",
        )

        val failed = EasyTierSessionController.buildFailureSnapshot(
            previous = connected,
            summary = "timeout",
            nowMs = 30_000L,
        )

        assertEquals(5_000L, failed.connectedAtMs)
    }

    @Test
    fun connectTimeout_doesNotFireForSessionThatAlreadyConnected() {
        // The regression: startedAtMs is set once and never refreshed, so for a session that has
        // been up for a while the elapsed time is the age of the session, not the age of the
        // problem. A single failure therefore tripped the connect budget on the very next poll.
        val failedAfterLongUptime = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.FAILED,
            mode = EasyTierNetworkMode.Room,
            sessionId = "lan_abc",
            startedAtMs = 1_000L,
            connectedAtMs = 6_000L,
            assignedIpv4Cidr = "10.126.5.184/24",
        )

        assertFalse(
            hasEasyTierConnectionTimedOut(
                snapshot = failedAfterLongUptime,
                config = config(connectTimeoutSeconds = 12),
                // 25 minutes after the session started: far beyond the connect budget.
                nowMs = 1_000L + 25 * 60 * 1_000L,
            )
        )
    }

    @Test
    fun connectTimeout_doesNotFireWhileReconnectingAnEstablishedSession() {
        val reconnecting = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.RECONNECTING,
            mode = EasyTierNetworkMode.Room,
            sessionId = "lan_abc",
            startedAtMs = 1_000L,
            connectedAtMs = 6_000L,
        )

        assertFalse(
            hasEasyTierConnectionTimedOut(
                snapshot = reconnecting,
                config = config(connectTimeoutSeconds = 12),
                nowMs = 1_000L + 10 * 60 * 1_000L,
            )
        )
    }

    @Test
    fun connectTimeout_stillFiresForInitialHandshakeThatNeverConnected() {
        // The budget must keep working for its actual purpose: a session that never got a tunnel.
        val neverConnected = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.SESSION_READY,
            mode = EasyTierNetworkMode.Room,
            sessionId = "lan_abc",
            startedAtMs = 1_000L,
            connectedAtMs = null,
        )

        assertTrue(
            hasEasyTierConnectionTimedOut(
                snapshot = neverConnected,
                config = config(connectTimeoutSeconds = 12),
                nowMs = 1_000L + 12_000L,
            )
        )
        assertFalse(
            hasEasyTierConnectionTimedOut(
                snapshot = neverConnected,
                config = config(connectTimeoutSeconds = 12),
                nowMs = 1_000L + 11_999L,
            )
        )
    }

    @Test
    fun connectTimeout_ignoresSnapshotWithoutStartTime() {
        val idle = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.DISCONNECTED,
            mode = EasyTierNetworkMode.Room,
            startedAtMs = null,
        )

        assertFalse(
            hasEasyTierConnectionTimedOut(
                snapshot = idle,
                config = config(),
                nowMs = 10_000_000L,
            )
        )
    }

    /** Alias for the production mapping, so these cases read as a scenario rather than a call. */
    private fun pollSuccessStatus(
        sessionState: String,
        current: EasyTierConnectionSnapshot,
    ): EasyTierConnectionStatus = resolveEasyTierPollSuccessStatus(
        sessionState = sessionState,
        current = current,
    )

    @Test
    fun establishedTunnel_requiresBothConnectedAtAndAddress() {
        val established = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.FAILED,
            mode = EasyTierNetworkMode.Room,
            sessionId = "lan_abc",
            connectedAtMs = 5_000L,
            assignedIpv4Cidr = "10.126.188.164/24",
        )
        assertTrue(hasEasyTierEstablishedTunnel(established))

        // Never connected: the poll must not invent a tunnel the VPN service never established.
        assertFalse(
            hasEasyTierEstablishedTunnel(established.copy(connectedAtMs = null))
        )
        // Address cleared: this is how the revoke and disconnect paths mark the tunnel as gone, so
        // it must not read as connected even though it once was.
        assertFalse(
            hasEasyTierEstablishedTunnel(established.copy(assignedIpv4Cidr = ""))
        )
    }

    @Test
    fun pollRecovery_returnsToConnectedAfterATransientFailure() {
        // The regression: the old rule only preserved CONNECTED when the status already was
        // CONNECTED. That is self-referential, and the only other writer of CONNECTED is the VPN
        // service, which is skipped while the routes are unchanged. One dip into FAILED therefore
        // pinned the session on SESSION_READY forever, withholding the Together in Spire launch
        // properties and silencing the in-game and mod-inventory reports.
        val connected = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.CONNECTED,
            mode = EasyTierNetworkMode.Room,
            sessionId = "lan_abc",
            startedAtMs = 1_000L,
            connectedAtMs = 2_000L,
            assignedIpv4Cidr = "10.126.188.164/24",
        )

        val failed = EasyTierSessionController.buildFailureSnapshot(
            previous = connected,
            summary = "timeout",
            nowMs = 60_000L,
        )
        assertEquals(EasyTierConnectionStatus.FAILED, failed.status)

        // The very next healthy poll must restore CONNECTED, not settle on SESSION_READY.
        assertEquals(
            EasyTierConnectionStatus.CONNECTED,
            pollSuccessStatus(sessionState = "connected", current = failed),
        )

        // And it must stay there across subsequent polls.
        var status = pollSuccessStatus("connected", failed)
        repeat(5) {
            status = pollSuccessStatus("connected", failed.copy(status = status))
        }
        assertEquals(EasyTierConnectionStatus.CONNECTED, status)
    }

    @Test
    fun pollRecovery_staysOnSessionReadyBeforeTheTunnelExists() {
        // The initial handshake must still report SESSION_READY: the server has issued a session but
        // the VPN service has not established a tunnel yet.
        val sessionReady = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.SESSION_READY,
            mode = EasyTierNetworkMode.Room,
            sessionId = "lan_abc",
            startedAtMs = 1_000L,
            connectedAtMs = null,
            assignedIpv4Cidr = "",
        )

        assertEquals(
            EasyTierConnectionStatus.SESSION_READY,
            pollSuccessStatus(sessionState = "issued", current = sessionReady),
        )
        assertEquals(
            EasyTierConnectionStatus.SESSION_READY,
            pollSuccessStatus(sessionState = "connected", current = sessionReady),
        )
    }

    @Test
    fun pollRecovery_doesNotReviveARevokedTunnel() {
        // onRevoke clears the address, which is what stops a revoked VPN from being reported as
        // connected by the next healthy poll.
        val revoked = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.PERMISSION_REQUIRED,
            mode = EasyTierNetworkMode.Room,
            sessionId = "lan_abc",
            connectedAtMs = null,
            assignedIpv4Cidr = "",
        )

        assertEquals(
            EasyTierConnectionStatus.SESSION_READY,
            pollSuccessStatus(sessionState = "connected", current = revoked),
        )
    }

    @Test
    fun pollRecovery_keepsReconnectingForUnknownSessionStates() {
        val established = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.CONNECTED,
            mode = EasyTierNetworkMode.Room,
            sessionId = "lan_abc",
            connectedAtMs = 2_000L,
            assignedIpv4Cidr = "10.126.188.164/24",
        )

        // An unrecognised non-terminal state must not be laundered into CONNECTED by the new rule.
        assertEquals(
            EasyTierConnectionStatus.RECONNECTING,
            pollSuccessStatus(sessionState = "pending", current = established),
        )
    }
}

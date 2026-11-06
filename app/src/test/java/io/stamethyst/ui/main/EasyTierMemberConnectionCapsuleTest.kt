package io.stamethyst.ui.main

import io.stamethyst.backend.easytier.EasyTierPeerConnection
import io.stamethyst.backend.easytier.EasyTierPeerConnectionKind
import io.stamethyst.backend.easytier.EasyTierRoomMember
import org.junit.Assert.assertEquals
import org.junit.Test

class EasyTierMemberConnectionCapsuleTest {
    private val member = EasyTierRoomMember("other", "Player", "member", true, assignedIpv4Cidr = "10.0.0.2/24")
    private val indicator = MainScreenViewModel.EasyTierIndicatorUi(
        state = MainScreenViewModel.EasyTierIndicatorState.CONNECTED,
        sessionId = "session", roomId = "room",
        peerConnections = listOf(EasyTierPeerConnection("10.0.0.2", EasyTierPeerConnectionKind.Direct, "udp")),
        peerConnectionsValidUntilMs = 200L,
    )

    @Test
    fun resolvesVirtualHostRegardlessOfPrefixAndNeverUsesBootstrapPeer() {
        assertEquals(EasyTierMemberLink(EasyTierMemberLinkState.Direct, "udp"), resolve())
        assertEquals(EasyTierMemberLinkState.Direct, resolve(member.copy(assignedIpv4Cidr = "10.0.0.2/32")).state)
        assertEquals(EasyTierMemberLinkState.Connecting, resolve(member.copy(assignedIpv4Cidr = "10.0.0.3/24")).state)
        assertEquals(EasyTierMemberLinkState.Connecting, resolve(member.copy(assignedIpv4Cidr = "")).state)
        assertEquals(EasyTierMemberLinkState.Relay, resolve(ui = indicator.copy(
            peerConnections = listOf(EasyTierPeerConnection("10.0.0.2", EasyTierPeerConnectionKind.Relay)),
        )).state)
    }

    @Test
    fun staleDataAndConnectionLifecycleOverrideOldRoutes() {
        assertEquals(EasyTierMemberLinkState.Unknown, resolve(nowMs = 200L).state)
        assertEquals(EasyTierMemberLinkState.Unknown, resolve(ui = indicator.copy(peerConnectionsValidUntilMs = 0L)).state)
        assertEquals(EasyTierMemberLinkState.Disconnected, resolve(ui = indicator.copy(roomId = "other-room")).state)
        assertEquals(EasyTierMemberLinkState.Disconnected, resolve(ui = indicator.copy(sessionId = "")).state)
        assertEquals(EasyTierMemberLinkState.Disconnected, resolve(ui = indicator.copy(state = MainScreenViewModel.EasyTierIndicatorState.CONNECTION_FAILED)).state)
        assertEquals(EasyTierMemberLinkState.Connecting, resolve(ui = indicator.copy(state = MainScreenViewModel.EasyTierIndicatorState.RECONNECTING)).state)
        assertEquals(EasyTierMemberLinkState.Connecting, resolve(ui = indicator.copy(
            sessionId = "", state = MainScreenViewModel.EasyTierIndicatorState.CONNECTING,
        )).state)
        assertEquals(EasyTierMemberLinkState.Offline, resolve(member.copy(online = false)).state)
        assertEquals(EasyTierMemberLinkState.Self, resolve(member.copy(playerId = "me")).state)
    }

    private fun resolve(
        peer: EasyTierRoomMember = member,
        ui: MainScreenViewModel.EasyTierIndicatorUi = indicator,
        nowMs: Long = 100L,
    ) = resolveEasyTierMemberLink(peer, "me", "room", ui, nowMs)
}

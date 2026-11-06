package io.stamethyst.ui.main

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.easytier.EasyTierPeerConnectionKind
import io.stamethyst.backend.easytier.EasyTierRoomMember

internal enum class EasyTierMemberLinkState { Self, Direct, Relay, Connecting, Unknown, Disconnected, Offline }

internal data class EasyTierMemberLink(
    val state: EasyTierMemberLinkState,
    val protocol: String = "",
)

internal fun resolveEasyTierMemberLink(
    member: EasyTierRoomMember,
    currentPlayerId: String,
    roomId: String,
    indicator: MainScreenViewModel.EasyTierIndicatorUi,
    nowMs: Long,
): EasyTierMemberLink {
    if (!member.online) return EasyTierMemberLink(EasyTierMemberLinkState.Offline)
    if (currentPlayerId.isNotBlank() && member.playerId.trim() == currentPlayerId.trim()) {
        return EasyTierMemberLink(EasyTierMemberLinkState.Self)
    }
    if (indicator.roomId != roomId) {
        return EasyTierMemberLink(EasyTierMemberLinkState.Disconnected)
    }
    when (indicator.state) {
        MainScreenViewModel.EasyTierIndicatorState.CONNECTING,
        MainScreenViewModel.EasyTierIndicatorState.RECONNECTING ->
            return EasyTierMemberLink(EasyTierMemberLinkState.Connecting)
        MainScreenViewModel.EasyTierIndicatorState.CONNECTED,
        MainScreenViewModel.EasyTierIndicatorState.SESSION_READY -> Unit
        else -> return EasyTierMemberLink(EasyTierMemberLinkState.Disconnected)
    }
    if (indicator.sessionId.isBlank()) return EasyTierMemberLink(EasyTierMemberLinkState.Disconnected)
    if (indicator.peerConnectionsValidUntilMs <= nowMs) {
        return EasyTierMemberLink(EasyTierMemberLinkState.Unknown)
    }
    val host = member.assignedIpv4Cidr.trim().substringBefore('/')
    val connection = indicator.peerConnections.firstOrNull { it.virtualIpv4 == host }
        ?: return EasyTierMemberLink(EasyTierMemberLinkState.Connecting)
    return EasyTierMemberLink(
        state = when (connection.kind) {
            EasyTierPeerConnectionKind.Direct -> EasyTierMemberLinkState.Direct
            EasyTierPeerConnectionKind.Relay -> EasyTierMemberLinkState.Relay
        },
        protocol = connection.protocol,
    )
}

@Composable
internal fun EasyTierMemberConnectionCapsule(link: EasyTierMemberLink) {
    val label = stringResource(when (link.state) {
        EasyTierMemberLinkState.Self -> R.string.main_easytier_link_self
        EasyTierMemberLinkState.Direct -> R.string.main_easytier_link_direct
        EasyTierMemberLinkState.Relay -> R.string.main_easytier_link_relay
        EasyTierMemberLinkState.Connecting -> R.string.main_easytier_link_connecting
        EasyTierMemberLinkState.Unknown -> R.string.main_easytier_link_unknown
        EasyTierMemberLinkState.Disconnected -> R.string.main_easytier_link_disconnected
        EasyTierMemberLinkState.Offline -> R.string.main_easytier_member_offline
    })
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (link.state) {
        EasyTierMemberLinkState.Direct -> colors.primaryContainer to colors.onPrimaryContainer
        EasyTierMemberLinkState.Relay -> colors.tertiaryContainer to colors.onTertiaryContainer
        EasyTierMemberLinkState.Connecting -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Text(
            text = if (link.protocol.isBlank()) label else "$label · ${link.protocol.uppercase()}",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

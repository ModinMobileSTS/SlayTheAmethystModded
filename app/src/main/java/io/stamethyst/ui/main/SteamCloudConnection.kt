package io.stamethyst.ui.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.steamcloud.SteamCloudSyncPhase
import io.stamethyst.ui.main.MainScreenViewModel.SteamCloudIndicatorState as Status
import io.stamethyst.ui.main.MainScreenViewModel.SteamCloudIndicatorUi as CloudState

internal const val CLOUD_SIZE_DURATION = 420
internal val CloudSizeEasing = androidx.compose.animation.core.CubicBezierEasing(.22f, 1f, .36f, 1f)

/** A relationship diagram, not an overall progress indicator. No animation when idle. */
@Composable
internal fun SteamCloudConnection(state: CloudState, tint: Color, compact: Boolean, modifier: Modifier = Modifier) {
    val displayState = steamCloudCardDisplayState(state)
    @Composable fun line(modifier: Modifier) {
        val packet = if (state.operationInFlight) {
            val transition = rememberInfiniteTransition(label = "cloudTransfer")
            transition.animateFloat(0f, 1f,
                infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "cloudPacket")
        } else null
        val outline = MaterialTheme.colorScheme.outlineVariant
        Canvas(modifier.height(20.dp)) {
            val y = size.height / 2
            drawLine(outline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            drawCircle(tint, 1.5.dp.toPx(), Offset(0f, y))
            drawCircle(tint, 1.5.dp.toPx(), Offset(size.width, y))
            if (packet != null) {
                // Read animation state only in drawing; endpoint text never recomposes per frame.
                val position = packet.value
                val x = (if (state.phase == SteamCloudSyncPhase.DOWNLOADING) 1f - position else position) * size.width
                val alpha = (minOf(position, 1f - position) * 6f).coerceIn(0f, 1f)
                drawLine(tint.copy(alpha = alpha), Offset((x - 6.dp.toPx()).coerceAtLeast(0f), y),
                    Offset((x + 6.dp.toPx()).coerceAtMost(size.width), y), 3.dp.toPx(), StrokeCap.Round)
            }
        }
    }
    @Composable fun endpoint(local: Boolean) {
        if (compact) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(if (local) R.drawable.ic_inventory else R.drawable.ic_cloud_queue), null,
                Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(if (local) R.string.cloud_card_local_short else R.string.cloud_card_steam),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(if (local) R.drawable.ic_inventory else R.drawable.ic_cloud_queue), null, Modifier.size(25.dp), tint)
            Text(stringResource(if (local) R.string.cloud_card_local_endpoint else R.string.cloud_card_cloud_endpoint),
                style = MaterialTheme.typography.labelMedium)
            Text(stringResource(if (local) R.string.cloud_card_this_device else R.string.cloud_card_steam),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    @Composable fun content() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            endpoint(true)
            if (compact) line(Modifier.weight(1f)) else Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when {
                    state.operationInFlight -> cloudTitle(state)
                    displayState == Status.UP_TO_DATE -> stringResource(R.string.cloud_card_agreement)
                    state.state == Status.RECOVERY_REQUIRED -> stringResource(R.string.cloud_card_resolution_required)
                    displayState == Status.CONFLICT -> stringResource(R.string.cloud_card_both_changed)
                    displayState == Status.CONNECTION_FAILED -> stringResource(R.string.cloud_card_connection_interrupted)
                    else -> stringResource(R.string.cloud_card_provider)
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    line(Modifier.fillMaxWidth())
                    if (!state.operationInFlight) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Icon(painterResource(when (displayState) {
                            Status.UP_TO_DATE -> R.drawable.ic_check_circle
                            Status.CONFLICT -> R.drawable.ic_cloud_alert
                            else -> R.drawable.ic_cloud_queue
                        }), null, Modifier.size(20.dp), tint)
                    }
                }
            }
            endpoint(false)
        }
    }
    if (compact) Row(modifier.testTag("steam-cloud-connection")) { content() }
    else Surface(modifier.fillMaxWidth().testTag("steam-cloud-connection"), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 18.dp)) { content() }
    }
}

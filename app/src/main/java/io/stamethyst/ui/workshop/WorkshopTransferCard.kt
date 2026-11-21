package io.stamethyst.ui.workshop

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopDownloadBlocklist
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus as Status

@Composable
internal fun WorkshopTransferCard(
    task: WorkshopDownloadTaskUi,
    trace: TransferTrace,
    onClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val wash = lerp(scheme.surfaceContainer, scheme.primaryContainer, .24f)
    val cover = rememberWorkshopCover(task.previewUrl)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = wash),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 600.dp
            if (cover != null) {
                Image(cover.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                val veil = arrayOf(
                    0f to Color.Black.copy(alpha = .55f),
                    .23f to Color.Black.copy(alpha = .32f),
                    .40f to wash.copy(alpha = .83f),
                    .64f to wash.copy(alpha = .94f),
                    1f to wash,
                )
                Box(Modifier.matchParentSize().background(if (wide) Brush.horizontalGradient(*veil) else Brush.verticalGradient(*veil)))
            }
            val title: @Composable (Modifier) -> Unit = { modifier ->
                Column(
                    modifier.clickable(onClick = onClick).padding(16.dp),
                    verticalArrangement = if (wide) Arrangement.Bottom else Arrangement.Top,
                ) {
                    val ink = if (cover == null) scheme.onSurface else Color.White
                    Text(task.title, color = ink, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (task.authorName.isNotBlank()) Text(task.authorName, color = ink, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    title(Modifier.weight(.44f).heightIn(min = 140.dp))
                    TransferReadout(task, trace, onPause, onResume, Modifier.weight(.56f))
                }
            } else {
                Column {
                    title(Modifier.fillMaxWidth().heightIn(min = 112.dp))
                    TransferReadout(task, trace, onPause, onResume, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun TransferReadout(task: WorkshopDownloadTaskUi, trace: TransferTrace, onPause: () -> Unit, onResume: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val netColor = scheme.primary
    val diskColor = if (scheme.surface.luminance() < .5f) Color(0xFF9AD6AC) else Color(0xFF357356)
    val paused = task.status == Status.Paused
    val latest = if (paused) TransferRates(0L, 0L) else trace.samples.lastOrNull()
    Column(modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Metric(R.string.transfer_network, latest?.network, Modifier.weight(1f))
            Metric(R.string.transfer_peak, trace.peak, Modifier.weight(1f))
            Metric(R.string.transfer_disk, latest?.disk, Modifier.weight(1f))
        }
        Canvas(Modifier.fillMaxWidth().height(34.dp)) {
            val hi = (trace.samples.maxOfOrNull { maxOf(it.network ?: 0, it.disk ?: 0) } ?: 0L).coerceAtLeast(1)
            val step = size.width / 36
            fun y(value: Long) = size.height * (1f - .92f * value.toFloat() / hi)
            drawLine(scheme.outlineVariant, Offset(0f, size.height), Offset(size.width, size.height))
            var previous: Offset? = null
            trace.samples.forEachIndexed { i, sample ->
                val x = (36 - trace.samples.size + i + .5f) * step
                sample.network?.let { speed ->
                    val top = y(speed)
                    drawRect(netColor.copy(alpha = .65f), Offset(x - step * .32f, top), Size(step * .64f, size.height - top))
                }
                val point = sample.disk?.let { Offset(x, y(it)) }
                if (point != null) previous?.let { drawLine(diskColor, it, point, 1.6.dp.toPx()) }
                previous = point
            }
        }
        val total = task.totalBytes?.takeIf { it > 0 }
        TransferProgressRails(task, netColor, diskColor)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val speed = trace.progressBytesPerSecond
            val eta = if (total != null && total > task.downloadedBytes && speed != null && speed > 0) {
                android.text.format.DateUtils.formatElapsedTime(kotlin.math.ceil((total - task.downloadedBytes).toDouble() / speed).toLong())
            } else null
            val footer = when {
                paused && !task.preservePartialDownload -> stringResource(R.string.workshop_resume_restart_warning)
                task.status != Status.Downloading -> task.status.defaultMessage()
                eta != null -> stringResource(R.string.transfer_eta, eta)
                task.message.isNotBlank() && task.message != "正在下载" && task.message != task.status.defaultMessage() -> task.message
                else -> stringResource(R.string.transfer_eta, "—")
            }
            Text(footer, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!WorkshopDownloadBlocklist.isBlocked(task.publishedFileId)) {
                Button(onClick = if (paused) onResume else onPause, enabled = paused || task.status == Status.Downloading || task.status == Status.Resolving) {
                    Icon(painterResource(if (paused) R.drawable.ic_play_arrow else R.drawable.ic_pause), null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(if (paused) R.string.workshop_action_continue else R.string.workshop_action_pause))
                }
            }
        }
    }
}

@Composable
private fun Metric(@StringRes label: Int, speed: Long?, modifier: Modifier) {
    Column(modifier) {
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
        Text(speed?.let { stringResource(R.string.workshop_download_speed_format, formatTransferBytes(it)) } ?: "—",
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun TransferProgressRails(
    task: WorkshopDownloadTaskUi,
    netColor: Color = MaterialTheme.colorScheme.primary,
    diskColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    TransferRail(R.string.transfer_data, task.downloadedBytes, task.totalBytes, netColor)
    TransferRail(R.string.transfer_files, task.writeProgressBytes, task.writeTotalBytes, diskColor)
}

@Composable
private fun TransferRail(@StringRes label: Int, bytes: Long?, total: Long?, color: Color) {
    val knownTotal = total?.takeIf { it > 0 }
    val fraction = if (bytes != null && knownTotal != null) {
        (bytes.toDouble() / knownTotal).coerceIn(0.0, 1.0)
    } else null
    val caption = when {
        bytes == null -> "—"
        knownTotal == null -> formatTransferBytes(bytes)
        else -> "${formatTransferBytes(bytes)} / ${formatTransferBytes(knownTotal)}"
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
        val detail = fraction?.let { "$caption · ${(it * 100).toInt()}%" } ?: caption
        Text(detail, style = MaterialTheme.typography.labelSmall)
    }
    if (fraction != null) {
        val displayed by animateFloatAsState(
            targetValue = fraction.toFloat(),
            animationSpec = tween(360, easing = LinearOutSlowInEasing),
            label = "workshopTransferRail",
        )
        LinearProgressIndicator(progress = { displayed }, modifier = Modifier.fillMaxWidth().height(5.dp), color = color)
    } else Spacer(Modifier.fillMaxWidth().height(5.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)))
}

@Composable
internal fun DownloadSectionHeading(@StringRes title: Int, count: Int, expanded: Boolean? = null, toggle: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().then(if (expanded != null) Modifier.clickable(onClick = toggle).heightIn(min = 48.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text("$count", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
        }
        if (expanded != null) {
            Spacer(Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.rotate(if (expanded) 180f else 0f))
        }
    }
}

@Composable
internal fun DownloadQueueRow(task: WorkshopDownloadTaskUi, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WorkshopDownloadPreviewImage(task.previewUrl, task.title, Modifier.size(46.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                val failed = task.status == Status.Failed
                val caption = when {
                    failed -> task.errorMessage.ifBlank { task.message.ifBlank { task.status.defaultMessage() } }
                    task.status == Status.Completed -> (task.totalBytes ?: task.fileSizeBytes).takeIf { it > 0 }?.let { formatTransferBytes(it) }
                    task.status == Status.Queued && task.fileSizeBytes > 0 -> formatTransferBytes(task.fileSizeBytes)
                    else -> task.status.defaultMessage()
                }
                if (caption != null) Text(caption, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing()
        }
    }
}

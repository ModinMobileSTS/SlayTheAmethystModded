package io.stamethyst.ui.workshop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopDownloadBlocklist
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus as Status

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadTaskSheet(
    task: WorkshopDownloadTaskUi,
    networkSpeed: Long?,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onExportLog: () -> Unit,
    onShareLog: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    var expanded by rememberSaveable(task.publishedFileId.toString()) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WorkshopDownloadPreviewImage(task.previewUrl, task.title, Modifier.size(64.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(task.status.defaultMessage(), style = type.labelMedium,
                            color = if (task.status == Status.Failed) colors.error else colors.primary)
                        Text(task.title, style = type.titleLarge, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (task.authorName.isNotBlank()) Text(task.authorName, style = type.bodySmall, color = colors.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_action_close))
                    }
                }
                when (task.status) {
                    Status.Failed -> Surface(shape = RoundedCornerShape(16.dp), color = colors.errorContainer) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(stringResource(R.string.workshop_detail_error), style = type.labelMedium)
                            Text(task.errorMessage.ifBlank { task.message.ifBlank { task.status.defaultMessage() } }, style = type.bodyMedium)
                        }
                    }
                    Status.Downloading, Status.Paused, Status.Pausing, Status.Cancelling -> Surface(
                        shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            TransferProgressRails(task)
                            if (networkSpeed != null) SheetFact(stringResource(R.string.transfer_network),
                                stringResource(R.string.workshop_download_speed_format, formatTransferBytes(networkSpeed)))
                            if (task.status == Status.Paused && !task.preservePartialDownload) {
                                Text(stringResource(R.string.workshop_resume_restart_warning), style = type.bodySmall)
                            }
                            if (task.status == Status.Downloading && task.message.isNotBlank() &&
                                task.message != "正在下载" && task.message != task.status.defaultMessage()) {
                                Text(task.message, style = type.bodySmall)
                            }
                        }
                    }
                    else -> SheetFact(stringResource(R.string.workshop_detail_size), formatTransferBytes(task.totalBytes ?: task.fileSizeBytes))
                }
                Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 52.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_log), null, Modifier.size(18.dp))
                            Text(stringResource(R.string.transfer_technical), Modifier.weight(1f), style = type.titleSmall)
                            Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.rotate(if (expanded) 180f else 0f))
                        }
                        AnimatedVisibility(expanded) {
                            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SheetFact("Workshop ID", task.publishedFileId.toString())
                                task.totalFiles?.let { SheetFact(stringResource(R.string.workshop_detail_file), "${task.completedFiles ?: 0} / $it") }
                                task.totalChunks?.let { SheetFact(stringResource(R.string.workshop_detail_chunks), "${task.completedChunks ?: 0} / $it") }
                                if (task.errorClass.isNotBlank()) SheetFact(stringResource(R.string.workshop_detail_type), task.errorClass)
                                if (task.description.isNotBlank()) Text(task.description, style = type.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                                Row {
                                    TextButton(onClick = onExportLog) { Text(stringResource(R.string.workshop_action_export_log)) }
                                    TextButton(onClick = onShareLog) { Text(stringResource(R.string.workshop_action_share_log)) }
                                }
                            }
                        }
                    }
                }
            }
            val primary = when (task.status) {
                Status.Queued, Status.Downloading, Status.Resolving -> R.string.workshop_action_pause to onPause
                Status.Paused -> R.string.workshop_action_continue to onResume
                Status.Failed, Status.Cancelled -> R.string.workshop_action_retry to onRetry
                else -> null
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (task.status != Status.Completed) TextButton(onClick = onCancel, enabled = task.status != Status.Cancelling) {
                    Text(stringResource(R.string.workshop_action_cancel_task), color = colors.error)
                }
                Spacer(Modifier.weight(1f))
                if (primary != null && !WorkshopDownloadBlocklist.isBlocked(task.publishedFileId)) {
                    Button(onClick = primary.second) { Text(stringResource(primary.first)) }
                } else {
                    FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) }
                }
            }
        }
    }
}

@Composable
private fun SheetFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.25f))
    }
}

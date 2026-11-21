package io.stamethyst.ui.workshop

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.stamethyst.ui.LauncherTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopDownloadBlocklist
import io.stamethyst.backend.workshop.WorkshopDownloadLogService
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.ui.Icons
import io.stamethyst.ui.RemoteBitmapCacheStore
import io.stamethyst.ui.icon.ArrowBack
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext


@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun WorkshopDownloadCenterScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onPause: (WorkshopDownloadTaskUi) -> Unit = {},
    onResume: (WorkshopDownloadTaskUi) -> Unit = {},
    onCancel: (WorkshopDownloadTaskUi) -> Unit = {},
    onRetry: (WorkshopDownloadTaskUi) -> Unit = {},
) {
    val activity = LocalActivity.current
    val tasks = WorkshopDownloadCenterStore.tasks
    val sections = remember(tasks.toList()) { downloadSections(tasks) }
    val scope = rememberCoroutineScope()
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var traces by remember { mutableStateOf(emptyMap<ULong, TransferTrace>()) }
    var selectedTaskId by remember { mutableStateOf<ULong?>(null) }
    var pendingExportTaskId by remember { mutableStateOf<ULong?>(null) }
    val exportLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val taskId = pendingExportTaskId
        pendingExportTaskId = null
        val task = taskId?.let(WorkshopDownloadCenterStore::find) ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            runCatching { WorkshopDownloadLogService.exportLog(requireNotNull(activity), task.toRecord(), uri) }
                .onSuccess { Toast.makeText(activity, activity?.getString(R.string.workshop_download_log_exported), Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(activity, activity?.getString(R.string.workshop_download_log_export_failed, it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show() }
        }
    }

    LaunchedEffect(activity) {
        val context = activity ?: return@LaunchedEffect
        val sampler = TransferSampler()
        while (true) {
            val loadedTasks = withContext(Dispatchers.IO) {
                WorkshopDownloadCenterStore.loadTasksWithRecovery(context)
            }
            WorkshopDownloadCenterStore.replaceInMemory(loadedTasks)
            traces = sampler.sample(loadedTasks, SystemClock.elapsedRealtime())
            delay(WORKSHOP_DOWNLOAD_PROGRESS_REFRESH_INTERVAL_MS)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            LauncherTopAppBar(
                title = { Text(stringResource(R.string.workshop_download_center_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tasks.isEmpty()) {
                item { EmptyDownloadCenterCard(onBack = onBack) }
            } else {
                items(sections.transfers, key = { it.publishedFileId.toString() }) { task ->
                    WorkshopTransferCard(
                        task = task,
                        trace = traces[task.publishedFileId] ?: TransferTrace(),
                        onClick = { selectedTaskId = task.publishedFileId },
                        onPause = { onPause(task) },
                        onResume = { onResume(task) },
                    )
                }
                if (sections.queue.isNotEmpty()) {
                    item("queue") { DownloadSectionHeading(R.string.transfer_queue, sections.queue.size) }
                    items(sections.queue, key = { it.publishedFileId.toString() }) { task ->
                        DownloadQueueRow(task, onClick = { selectedTaskId = task.publishedFileId }) {
                            if (task.publishedFileId == sections.queue.first().publishedFileId) {
                                Text(stringResource(R.string.transfer_next), style = MaterialTheme.typography.labelMedium)
                            } else if (!WorkshopDownloadBlocklist.isBlocked(task.publishedFileId)) {
                                IconButton(onClick = {
                                    scope.launch(Dispatchers.IO) { WorkshopDownloadCenterStore.moveQueuedTaskToFront(task.publishedFileId) }
                                }) {
                                    Icon(painterResource(R.drawable.ic_arrow_upward), stringResource(R.string.transfer_prioritize))
                                }
                            }
                        }
                    }
                }
                if (sections.attention.isNotEmpty()) {
                    item("attention") { DownloadSectionHeading(R.string.transfer_attention, sections.attention.size) }
                    items(sections.attention, key = { it.publishedFileId.toString() }) { task ->
                        DownloadQueueRow(task, onClick = { selectedTaskId = task.publishedFileId }) {
                            if (!WorkshopDownloadBlocklist.isBlocked(task.publishedFileId)) {
                                TextButton(onClick = { onRetry(task) }) {
                                    Text(stringResource(R.string.workshop_action_retry))
                                }
                            }
                        }
                    }
                }
                if (sections.history.isNotEmpty()) {
                    item("history") {
                        DownloadSectionHeading(R.string.transfer_history, sections.history.size, historyOpen) { historyOpen = !historyOpen }
                    }
                    if (historyOpen) items(sections.history, key = { it.publishedFileId.toString() }) { task ->
                        DownloadQueueRow(task, onClick = { selectedTaskId = task.publishedFileId })
                    }
                }
            }
        }
    }

    selectedTaskId?.let { taskId ->
        val task = tasks.firstOrNull { it.publishedFileId == taskId }
        if (task == null) {
            selectedTaskId = null
            return@let
        }
        DownloadTaskSheet(
            task = task,
            networkSpeed = traces[taskId]?.samples?.lastOrNull()?.network?.takeIf { task.status == WorkshopDownloadTaskStatus.Downloading },
            onDismiss = { selectedTaskId = null },
            onPause = { onPause(task) },
            onResume = { onResume(task) },
            onCancel = {
                onCancel(task)
                selectedTaskId = null
            },
            onRetry = { onRetry(task) },
            onExportLog = {
                pendingExportTaskId = task.publishedFileId
                exportLogLauncher.launch(WorkshopDownloadLogService.fileName(task.toRecord()))
            },
            onShareLog = { activity?.shareDownloadLog(task) },
        )
    }
}

@Composable
internal fun rememberWorkshopCover(url: String): Bitmap? {
    val context = LocalContext.current
    return androidx.compose.runtime.key(url) {
        produceState<Bitmap?>(null, url) {
            if (url.isNotBlank()) value = withContext(Dispatchers.IO) { RemoteBitmapCacheStore.load(context.applicationContext, url) }
        }.value
    }
}

@Composable
internal fun WorkshopDownloadPreviewImage(url: String, contentDescription: String, modifier: Modifier = Modifier.size(72.dp)) {
    val bitmap = rememberWorkshopCover(url)
    Box(
        modifier = modifier
            .clip(CardDefaults.shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) Text("MOD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
    }
}

@Composable
private fun EmptyDownloadCenterCard(onBack: () -> Unit) {
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.workshop_download_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = stringResource(R.string.workshop_download_empty_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.workshop_action_back_to_workshop)) }
        }
    }
}

@Composable
internal fun WorkshopDownloadTaskStatus.defaultMessage(): String = when (this) {
    WorkshopDownloadTaskStatus.Queued -> stringResource(R.string.workshop_download_task_message_waiting)
    WorkshopDownloadTaskStatus.Resolving -> stringResource(R.string.workshop_download_task_message_resolving)
    WorkshopDownloadTaskStatus.Downloading -> stringResource(R.string.workshop_download_task_message_downloading)
    WorkshopDownloadTaskStatus.Pausing -> stringResource(R.string.workshop_download_task_message_pausing)
    WorkshopDownloadTaskStatus.Cancelling -> stringResource(R.string.workshop_download_task_message_cancelling)
    WorkshopDownloadTaskStatus.Paused -> stringResource(R.string.workshop_download_task_message_paused)
    WorkshopDownloadTaskStatus.Completed -> stringResource(R.string.workshop_download_task_status_completed)
    WorkshopDownloadTaskStatus.Failed -> stringResource(R.string.workshop_download_task_message_failed)
    WorkshopDownloadTaskStatus.Cancelled -> stringResource(R.string.workshop_download_task_message_cancelled)
}

@Composable
internal fun formatTransferBytes(bytes: Long): String {
    if (bytes < 0L) return stringResource(R.string.workshop_unknown_value)
    if (bytes == 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) {
        "$bytes ${units[unitIndex]}"
    } else {
        "${String.format(Locale.US, "%.1f", value)} ${units[unitIndex]}"
    }
}

private fun Activity.shareDownloadLog(task: WorkshopDownloadTaskUi) {
    runCatching {
        val shareIntent = WorkshopDownloadLogService.prepareShareIntent(this, task.toRecord())
        startActivity(Intent.createChooser(shareIntent, getString(R.string.workshop_share_download_log_chooser_title)))
    }.onFailure {
        Toast.makeText(this, getString(R.string.workshop_share_download_log_failed, it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show()
    }
}

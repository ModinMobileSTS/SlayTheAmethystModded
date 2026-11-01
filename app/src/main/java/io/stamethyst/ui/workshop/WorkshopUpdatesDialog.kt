package io.stamethyst.ui.workshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopDownloadBlocklist
import io.stamethyst.backend.workshop.WorkshopInstalledModRecord
import io.stamethyst.backend.workshop.WorkshopModCardState
import io.stamethyst.config.RuntimePaths
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.LauncherNavigationRequestBus
import io.stamethyst.ui.LauncherPanelDialog
import java.io.File
import kotlinx.coroutines.delay

internal fun availableWorkshopUpdates(records: List<WorkshopInstalledModRecord>): List<WorkshopInstalledModRecord> =
    records.filter { record ->
        record.cardState == WorkshopModCardState.UpdateAvailable &&
            !WorkshopDownloadBlocklist.isBlocked(record.toWorkshopItemSummary())
    }.distinctBy { it.appId to it.publishedFileId }

/** Local metadata drives both the header icon and cards; only an update click needs Steam. */
@Composable
internal fun WorkshopUpdatesAction(
    localMods: List<ModItemUi>,
    lastCheckedAtMs: Long,
    enabled: Boolean,
    viewModel: WorkshopViewModel = viewModel(key = "workshop-updates"),
) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = viewModel.uiState
    val available = remember(state.installedMods) { availableWorkshopUpdates(state.installedMods) }
    var showDialog by remember { mutableStateOf(false) }
    // Keep rows after queuing/completion so their market action can show progress, retry or done.
    var displayedUpdates by remember { mutableStateOf(emptyList<WorkshopInstalledModRecord>()) }

    LaunchedEffect(localMods, lastCheckedAtMs) {
        viewModel.loadInstalledState(context)
    }
    LaunchedEffect(showDialog, available) {
        if (showDialog) {
            displayedUpdates = (displayedUpdates + available).distinctBy { it.appId to it.publishedFileId }
        }
    }
    LaunchedEffect(lifecycleOwner, showDialog, state.downloadInProgress) {
        if (!showDialog && !state.downloadInProgress) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.refreshDownloadState(context)
                delay(WORKSHOP_DOWNLOAD_PROGRESS_REFRESH_INTERVAL_MS)
            }
        }
    }

    if (available.isNotEmpty()) {
        IconButton(
            enabled = enabled,
            onClick = {
                displayedUpdates = available
                showDialog = true
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_workshop_update),
                contentDescription = stringResource(R.string.workshop_updates_available, available.size),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }

    fun downloadState(record: WorkshopInstalledModRecord): WorkshopModDownloadState =
        resolveWorkshopModDownloadState(
            item = record.toWorkshopItemSummary(),
            installedMods = state.installedMods,
            downloadTaskStatuses = WorkshopDownloadCenterStore.taskStatuses,
            preparingDownloadIds = state.preparingDownloadIds,
        )

    if (showDialog) {
        WorkshopUpdatesDialog(
            updates = displayedUpdates,
            downloadState = ::downloadState,
            actionsEnabled = enabled && !state.preparingAllUpdates && state.pendingDependencyDownload == null,
            status = state.downloadStatus,
            onUpdate = { viewModel.download(context, it.toWorkshopItemSummary()) },
            onUpdateAll = {
                viewModel.updateAll(
                    context,
                    displayedUpdates.filter { downloadState(it).canStartDownload }.map { it.toWorkshopItemSummary() },
                )
            },
            onDismiss = { showDialog = false },
        )
    }
    state.pendingDependencyDownload?.let { pending ->
        MissingWorkshopDependenciesDialog(
            modTitle = pending.details.summary.title,
            missingDependencies = pending.missingDependencies,
            onConfirm = { viewModel.confirmPendingDependencyDownload(context) },
            onDownloadCurrentOnly = { viewModel.downloadPendingCurrentOnly(context) },
            onDismiss = viewModel::dismissPendingDependencyDownload,
        )
    }
}

@Composable
internal fun WorkshopUpdatesDialog(
    updates: List<WorkshopInstalledModRecord>,
    downloadState: (WorkshopInstalledModRecord) -> WorkshopModDownloadState,
    actionsEnabled: Boolean,
    status: String,
    onUpdate: (WorkshopInstalledModRecord) -> Unit,
    onUpdateAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    LauncherPanelDialog(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.workshop_updates_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = actionsEnabled && updates.any { downloadState(it).canStartDownload },
                onClick = onUpdateAll,
            ) { Text(stringResource(R.string.workshop_updates_all)) }
            IconButton(onClick = onDismiss) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_action_close))
            }
        }
        HorizontalDivider()
        if (status.isNotBlank()) {
            Text(status, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(updates, key = { "${it.appId}:${it.publishedFileId}" }) { record ->
                val imagePath = remember(record.appId, record.publishedFileId, record.localPreviewImagePath) {
                    val path = record.localPreviewImagePath.trim()
                    when {
                        path.isEmpty() -> ""
                        File(path).isAbsolute -> path
                        else -> RuntimePaths.workshopItemDirs(context, record.appId, record.publishedFileId)
                            .map { File(it, path) }.firstOrNull(File::isFile)?.absolutePath.orEmpty()
                    }
                }
                WorkshopItemCard(
                    item = record.toWorkshopItemSummary(),
                    localPreviewImagePath = imagePath,
                    downloadState = downloadState(record),
                    downloadEnabled = actionsEnabled,
                    onClick = {
                        onDismiss()
                        LauncherNavigationRequestBus.requestWorkshopDetail(record.toWorkshopItemSummary())
                    },
                    onDownload = { onUpdate(record) },
                )
            }
        }
    }
}

package io.stamethyst.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.stamethyst.R
import io.stamethyst.backend.mods.CommunityPatchMod
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopService
import io.stamethyst.ui.workshop.MissingWorkshopDependenciesDialog
import io.stamethyst.ui.workshop.WORKSHOP_DOWNLOAD_PROGRESS_REFRESH_INTERVAL_MS
import io.stamethyst.ui.workshop.WorkshopDownloadCenterStore
import io.stamethyst.ui.workshop.WorkshopItemCard
import io.stamethyst.ui.workshop.WorkshopModDownloadState
import io.stamethyst.ui.workshop.WorkshopViewModel
import io.stamethyst.ui.workshop.resolveWorkshopModDownloadState
import kotlinx.coroutines.delay

@Composable
internal fun CommunityPatchDetail(
    mod: CommunityPatchMod,
    service: WorkshopService,
    marketViewModel: CommunityPatchMarketViewModel,
    workshopViewModel: WorkshopViewModel,
    refreshToken: Int,
    onOpenDetails: (WorkshopItemSummary) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val workshopState = workshopViewModel.uiState
    val marketState = marketViewModel.stateFor(mod)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(mod.modId, mod.workshopId, refreshToken) {
        marketViewModel.load(mod, service, refreshToken)
    }
    LaunchedEffect(workshopViewModel, context) {
        workshopViewModel.loadInstalledState(context)
    }
    LaunchedEffect(workshopState.downloadInProgress) {
        if (!workshopState.downloadInProgress) return@LaunchedEffect
        while (true) {
            delay(WORKSHOP_DOWNLOAD_PROGRESS_REFRESH_INTERVAL_MS)
            workshopViewModel.refreshDownloadTaskState(context)
        }
    }
    val summary = marketState.summary ?: mod.workshopFallback()
    val downloadState = summary?.let {
        resolveWorkshopModDownloadState(
            item = it,
            installedMods = workshopState.installedMods,
            downloadTaskStatuses = WorkshopDownloadCenterStore.taskStatuses,
            preparingDownloadIds = workshopState.preparingDownloadIds,
        )
    } ?: WorkshopModDownloadState.Unavailable
    var downloadRequested by remember { mutableStateOf(false) }
    CommunityPatchDetailContent(
        mod = mod,
        marketState = marketState,
        downloadState = downloadState,
        onOpenDetails = onOpenDetails,
        onDownload = { item ->
            requestNotificationPermission()
            downloadRequested = true
            workshopViewModel.download(context, item)
        },
        onRetryMarket = { marketViewModel.load(mod, service, refreshToken, force = true) },
        downloadStatus = workshopState.downloadStatus.takeIf { downloadRequested }.orEmpty(),
    )
    workshopState.pendingDependencyDownload?.takeIf { it.details.summary.publishedFileId == mod.workshopId }?.let { pending ->
        MissingWorkshopDependenciesDialog(
            modTitle = pending.details.summary.title,
            missingDependencies = pending.missingDependencies,
            onDismiss = workshopViewModel::dismissPendingDependencyDownload,
            onDownloadCurrentOnly = {
                requestNotificationPermission()
                workshopViewModel.downloadPendingCurrentOnly(context)
            },
            onConfirm = {
                requestNotificationPermission()
                workshopViewModel.confirmPendingDependencyDownload(context)
            },
        )
    }
}

/** Presentation stays independently testable, without live catalogue or Steam requests. */
@Composable
internal fun CommunityPatchDetailContent(
    mod: CommunityPatchMod,
    marketState: CommunityPatchMarketState,
    downloadState: WorkshopModDownloadState,
    onOpenDetails: (WorkshopItemSummary) -> Unit,
    onDownload: (WorkshopItemSummary) -> Unit,
    onRetryMarket: () -> Unit,
    downloadStatus: String = "",
) {
    val listState = rememberLazyListState()
    val fallback = mod.workshopFallback()
    val summary = marketState.summary ?: fallback
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("community_patch_details"),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "market_card") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    summary != null -> WorkshopItemCard(
                        item = summary,
                        downloadState = downloadState,
                        onClick = { onOpenDetails(summary) },
                        onDownload = { onDownload(summary) },
                        downloadEnabled = marketState.summary != null,
                        showDescriptionFallback = false,
                        authorLoading = marketState.authorLoading,
                        ratingLoading = marketState.ratingLoading,
                        downloadCountLoading = marketState.loading && marketState.summary == null,
                        useCachedPreviewWhenUrlMissing = true,
                        modifier = Modifier.testTag("community_market_card"),
                    )
                    else -> CommunityPatchUnlinkedMod(mod)
                }
                if (fallback != null && marketState.error != null) {
                    Column(Modifier.padding(horizontal = 2.dp)) {
                        Text(
                            stringResource(R.string.community_patch_market_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onRetryMarket, enabled = !marketState.loading) {
                            Text(stringResource(R.string.community_patch_market_retry))
                        }
                    }
                }
                if (downloadStatus.isNotBlank()) {
                    Text(downloadStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(key = "patch_heading") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_code), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.main_mod_agent_patch_badge), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        stringResource(R.string.community_patch_section_subtitle), Modifier.padding(start = 28.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.community_patch_entry_count, mod.patches.size),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(mod.patches, key = { it.communityItemKey() }) { patch -> CommunityPatchCard(patch) }
        item(key = "install_hint") {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(
                    painterResource(R.drawable.ic_info_outline), null,
                    Modifier.padding(top = 2.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.community_patch_install_hint),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CommunityPatchUnlinkedMod(mod: CommunityPatchMod) {
    androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_image_mod), null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(mod.name.ifBlank { mod.modId }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.community_patch_market_unlinked),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

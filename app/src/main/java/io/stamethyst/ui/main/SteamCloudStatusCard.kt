package io.stamethyst.ui.main

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.stamethyst.R
import io.stamethyst.backend.steamcloud.SteamCloudSyncPhase
import io.stamethyst.backend.steamcloud.SteamCloudUploadPlan
import io.stamethyst.backend.steamcloud.SteamCloudUserWarning
import io.stamethyst.backend.steamcloud.steamCloudPhaseLabel
import io.stamethyst.navigation.LocalNavigator
import io.stamethyst.navigation.Route
import io.stamethyst.ui.main.MainScreenViewModel.SteamCloudIndicatorState as Status
import io.stamethyst.ui.main.MainScreenViewModel.SteamCloudIndicatorUi as CloudState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** No timers, networking or duplicate synchronization state belong to this card. */
@Composable
internal fun SteamCloudStatusCard(state: CloudState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = cloudTint(state)
    val workingState = rememberCloudExitState(state)
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag("steam-cloud-status-card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cloudSurface()),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
    ) {
        Column(Modifier.fillMaxWidth().animateContentSize(tween(CLOUD_SIZE_DURATION, easing = CloudSizeEasing))) {
            Row(Modifier.fillMaxWidth().heightIn(min = 83.dp).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(42.dp), shape = RoundedCornerShape(14.dp), color = tint.copy(alpha = .12f)) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(cloudIcon(state)), null, Modifier.size(25.dp), tint)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.cloud_card_title), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    CloudStatusLine(state)
                }
                Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(state.operationInFlight, enter = fadeIn(tween(300)), exit = fadeOut(tween(180))) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SteamCloudConnection(workingState, cloudTint(workingState), compact = true)
                }
            }
        }
    }
}

@Composable
internal fun SteamCloudCardStatusButton(state: CloudState, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(cloudIcon(state)), contentDescription = cloudTitle(state), tint = cloudTint(state))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloudStatusLine(state: CloudState) {
    val stageDescription = stringResource(R.string.cloud_card_stage_count, state.completedFiles, state.totalFiles)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SlidingTextSwap(cloudTitle(state), style = MaterialTheme.typography.bodySmall, color = cloudTint(state),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (steamCloudStageFraction(state) != null) {
            Text("${state.completedFiles}/${state.totalFiles}", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = stageDescription
                })
        }
        if (state.state == Status.UP_TO_DATE) state.lastCheckedAtMs?.let { timestamp ->
            val relative = remember(timestamp) {
                DateUtils.getRelativeTimeSpanString(timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            }
            Text("· $relative", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Keep outgoing progress present while fading/shrinking rather than leaving a blank height. */
@Composable
private fun rememberCloudExitState(state: CloudState): CloudState {
    var lastWorking by remember { mutableStateOf(state) }
    SideEffect { if (state.operationInFlight) lastWorking = state }
    return if (state.operationInFlight) state else lastWorking
}

private sealed interface CloudIntent {
    data object Check : CloudIntent
    data object Background : CloudIntent
    data class Resolve(val local: Boolean, val plan: SteamCloudUploadPlan?) : CloudIntent
}

/** Only the visible sheet owns dialog drafts. The service/ViewModel own the operation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SteamCloudStatusSheet(
    state: CloudState,
    actions: MainScreenActions,
    controlsEnabled: Boolean,
    onDismiss: () -> Unit,
) {
    var confirmation by remember(state.state, state.plan) { mutableStateOf<CloudIntent.Resolve?>(null) }
    var networkIntent by remember(state.state, state.plan) { mutableStateOf<CloudIntent?>(null) }
    var confirmSkip by remember(state.state) { mutableStateOf(false) }
    val navigator = LocalNavigator.current
    val windowSize = LocalWindowInfo.current.containerSize
    val windowHeight = with(LocalDensity.current) { windowSize.height.toDp().coerceAtLeast(240.dp) }
    val windowWidth = with(LocalDensity.current) { windowSize.width.toDp().coerceAtLeast(1.dp) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun dismiss(after: () -> Unit = {}) {
        scope.launch {
            sheetState.hide()
            onDismiss()
            after()
        }
    }

    fun execute(intent: CloudIntent) {
        when (intent) {
            CloudIntent.Check -> actions.onRefreshSteamCloudStatus()
            CloudIntent.Background -> if (actions.onBackgroundSteamCloudSyncAndLaunch()) dismiss()
            is CloudIntent.Resolve -> if (intent.local) actions.onUseLocalSteamCloudProgress(intent.plan)
                else actions.onUseCloudSteamCloudProgress(intent.plan)
        }
    }
    fun request(intent: CloudIntent) {
        if (actions.shouldPromptSteamCloudDirectMode()) networkIntent = intent else execute(intent)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = cloudSurface(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetMaxWidth = if (windowWidth < 560.dp) windowWidth else 440.dp,
        sheetState = sheetState) {
        SteamCloudStatusDetails(
            state = state,
            controlsEnabled = controlsEnabled,
            modifier = Modifier.heightIn(max = (windowHeight * if (windowHeight < 480.dp) .96f else .9f) - 48.dp),
            onRefresh = { request(CloudIntent.Check) },
            onCancel = {
                // Either function is guarded by the ViewModel's operation flags, including reattachment.
                actions.onCancelSteamCloudCheck()
                actions.onCancelSteamCloudSync()
            },
            onResolve = { local -> confirmation = CloudIntent.Resolve(local, state.plan) },
            onSkip = { confirmSkip = true },
            onLaunch = { if (actions.onLaunch() != LaunchRequestAction.OPEN_STEAM_CLOUD_SHEET) dismiss() },
            onBackgroundSync = {
                // Explicit immediate launch must not be held behind a cloud-network mode prompt.
                execute(CloudIntent.Background)
            },
            onUnavailable = {
                dismiss {
                    navigator.push(if (state.state == Status.SIGNED_OUT) Route.SteamCloudLoginMethod else Route.SteamCloudSaveSettings)
                }
            },
        )
    }

    confirmation?.let { intent ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            icon = { Icon(painterResource(if (intent.local) R.drawable.ic_cloud_queue else R.drawable.ic_inventory), contentDescription = null) },
            title = { Text(stringResource(if (intent.local) R.string.cloud_card_confirm_local_title else R.string.cloud_card_confirm_cloud_title)) },
            text = { Text(stringResource(if (intent.local) R.string.cloud_card_confirm_local else R.string.cloud_card_confirm_cloud)) },
            confirmButton = {
                Button(enabled = controlsEnabled && canResolveSteamCloudCard(state), onClick = {
                    confirmation = null
                    request(intent)
                }) { Text(stringResource(R.string.cloud_card_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    networkIntent?.let { intent ->
        AlertDialog(
            onDismissRequest = { networkIntent = null },
            title = { Text(stringResource(R.string.main_steam_cloud_direct_mode_prompt_title)) },
            text = { Text(stringResource(R.string.main_steam_cloud_direct_mode_prompt_message)) },
            confirmButton = {
                TextButton(enabled = controlsEnabled, onClick = {
                    networkIntent = null
                    actions.onSwitchSteamCloudDirectMode()
                    execute(intent)
                }) { Text(stringResource(R.string.main_steam_cloud_direct_mode_prompt_switch_action)) }
            },
            dismissButton = {
                TextButton(enabled = controlsEnabled, onClick = { networkIntent = null; execute(intent) }) {
                    Text(stringResource(R.string.main_steam_cloud_direct_mode_prompt_keep_action))
                }
            },
        )
    }
    if (confirmSkip) AlertDialog(
        onDismissRequest = { confirmSkip = false },
        title = { Text(stringResource(R.string.main_steam_cloud_launch_warning_title)) },
        text = { Text(stringResource(R.string.cloud_card_skip_hint)) },
        confirmButton = {
            TextButton(enabled = controlsEnabled && !state.operationInFlight, onClick = {
                confirmSkip = false
                actions.onLaunchAfterSteamCloudError()
                dismiss()
            }) { Text(stringResource(R.string.cloud_card_skip)) }
        },
        dismissButton = { TextButton(onClick = { confirmSkip = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SteamCloudStatusDetails(
    state: CloudState,
    controlsEnabled: Boolean,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onResolve: (Boolean) -> Unit,
    onSkip: () -> Unit,
    onLaunch: () -> Unit,
    onBackgroundSync: () -> Unit,
    modifier: Modifier = Modifier,
    onUnavailable: () -> Unit = {},
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val tint = cloudTint(state)
    val conflicts = state.plan?.conflicts.orEmpty()
    val chevronAngle by animateFloatAsState(if (showDetails) 180f else 0f, tween(250), label = "cloudDetailsChevron")
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)
            .animateContentSize(tween(CLOUD_SIZE_DURATION, easing = CloudSizeEasing), alignment = Alignment.BottomCenter)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)
                .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SlidingTextSwap(cloudTitle(state), style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp),
                        fontWeight = FontWeight.SemiBold, color = tint,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    SlidingTextSwap(cloudHint(state), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 23.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SteamCloudConnection(state, tint, compact = false)
                if (steamCloudCardDisplayState(state) == Status.CONFLICT) Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_cloud_alert), null, Modifier.size(17.dp), tint)
                    Text(if (conflicts.isEmpty()) stringResource(R.string.cloud_card_resolution_required)
                        else stringResource(R.string.cloud_card_conflict_count, conflicts.size), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = tint)
                    TextButton(onClick = { showDetails = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.cloud_card_view_files), style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (state.state == Status.UP_TO_DATE) FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CloudMetric(R.drawable.ic_arrow_upward, stringResource(R.string.cloud_card_upload_count, state.uploadedFiles),
                        stringResource(R.string.cloud_card_upload_count, state.uploadedFiles))
                    CloudMetric(R.drawable.ic_workshop_download, stringResource(R.string.cloud_card_download_count, state.downloadedFiles),
                        stringResource(R.string.cloud_card_download_count, state.downloadedFiles))
                    if (state.deletedFiles > 0) CloudMetric(R.drawable.ic_delete,
                        stringResource(R.string.cloud_card_delete_count, state.deletedFiles),
                        stringResource(R.string.cloud_card_delete_count, state.deletedFiles))
                }
                Column(Modifier.fillMaxWidth().animateContentSize(tween(CLOUD_SIZE_DURATION, easing = CloudSizeEasing))) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val disclosureDescription = stringResource(if (showDetails) R.string.cloud_card_hide_details else R.string.cloud_card_details)
                    TextButton(onClick = { showDetails = !showDetails },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("steam-cloud-details")
                            .semantics { stateDescription = disclosureDescription }) {
                        Text(stringResource(R.string.cloud_card_details), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(18.dp).rotate(chevronAngle),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AnimatedVisibility(showDetails, enter = fadeIn(tween(300)), exit = fadeOut(tween(180))) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("steam-cloud-technical-content")) {
                            state.lastCheckedAtMs?.let { timestamp ->
                                val formatted = remember(timestamp) {
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
                                }
                                Text(stringResource(R.string.cloud_card_checked_at, formatted), style = MaterialTheme.typography.bodySmall)
                            }
                            if (state.state == Status.DEFERRED) Text(stringResource(R.string.cloud_card_deferred_hint), style = MaterialTheme.typography.bodySmall)
                            if (state.state == Status.RECOVERY_REQUIRED) Text(stringResource(R.string.cloud_card_recovery_hint), style = MaterialTheme.typography.bodySmall)
                            if (state.errorSummary.isNotBlank()) Text(state.errorSummary, style = MaterialTheme.typography.bodySmall)
                            if (state.progressCurrentPath.isNotBlank()) Text(state.progressCurrentPath,
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            state.warnings.forEach { warning -> Text(cloudWarning(warning), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary) }
                            val absent = stringResource(R.string.cloud_card_absent)
                            conflicts.forEach { conflict ->
                                HorizontalDivider()
                                Text(conflict.localRelativePath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                val localSize = conflict.currentLocal?.fileSize?.let { "$it B" } ?: absent
                                val cloudSize = conflict.currentRemote?.takeIf { it.isLive }?.rawSize?.let { "$it B" } ?: absent
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(stringResource(R.string.cloud_card_local_size, localSize), style = MaterialTheme.typography.bodySmall)
                                    Text(stringResource(R.string.cloud_card_cloud_size, cloudSize), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CloudSheetActions(state, controlsEnabled, onRefresh, onCancel, onResolve, onSkip, onLaunch, onBackgroundSync, onUnavailable)
        }
    }
}

/** Outside the body scroll: actions stay reachable as details and status heights change. */
@Composable
private fun CloudSheetActions(state: CloudState, enabled: Boolean, onRefresh: () -> Unit, onCancel: () -> Unit,
    onResolve: (Boolean) -> Unit, onSkip: () -> Unit, onLaunch: () -> Unit, onBackground: () -> Unit, onUnavailable: () -> Unit) {
    val buttonShape = RoundedCornerShape(12.dp)
    val buttonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    val tonalColors = ButtonDefaults.filledTonalButtonColors(
        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
        contentColor = MaterialTheme.colorScheme.primary)
    val outlineColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    val outlineBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
    Column(Modifier.fillMaxWidth().animateContentSize(tween(CLOUD_SIZE_DURATION, easing = CloudSizeEasing), alignment = Alignment.BottomCenter)
        .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp).testTag("steam-cloud-actions"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            state.operationInFlight -> OutlinedButton(onClick = onCancel, enabled = enabled && state.state != Status.CANCELLING,
                shape = buttonShape, contentPadding = buttonPadding, colors = outlineColors, border = outlineBorder,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("steam-cloud-cancel")) {
                CloudActionLabel(R.drawable.ic_stop, stringResource(if (state.state == Status.CANCELLING)
                    R.string.cloud_card_cancelling else R.string.cloud_card_cancel))
            }
            canResolveSteamCloudCard(state) -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onResolve(true) }, enabled = enabled, shape = buttonShape, contentPadding = buttonPadding,
                    colors = outlineColors, border = outlineBorder,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("steam-cloud-local")) { Text(stringResource(R.string.cloud_card_local_title)) }
                OutlinedButton(onClick = { onResolve(false) }, enabled = enabled, shape = buttonShape, contentPadding = buttonPadding,
                    colors = outlineColors, border = outlineBorder,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("steam-cloud-remote")) { Text(stringResource(R.string.cloud_card_cloud_title)) }
            }
            state.state == Status.UP_TO_DATE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRefresh, enabled = enabled, shape = buttonShape, colors = tonalColors, contentPadding = buttonPadding,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("steam-cloud-refresh")) {
                    Text(stringResource(if (state.state == Status.HIDDEN) R.string.cloud_card_check_now else R.string.cloud_card_check))
                }
                Button(onClick = onLaunch, enabled = enabled, shape = buttonShape, contentPadding = buttonPadding,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("steam-cloud-launch")) { Text(stringResource(R.string.main_launch_game)) }
            }
            canRefreshSteamCloudCard(state) -> Button(onClick = onRefresh, enabled = enabled, shape = buttonShape, contentPadding = buttonPadding,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("steam-cloud-refresh")) {
                CloudActionLabel(R.drawable.ic_refresh, stringResource(if (state.state == Status.HIDDEN)
                    R.string.cloud_card_check_now else R.string.cloud_card_check))
            }
            state.state in setOf(Status.DISABLED, Status.INDEPENDENT, Status.SIGNED_OUT) -> Button(onClick = onUnavailable,
                enabled = enabled, shape = buttonShape, contentPadding = buttonPadding,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("steam-cloud-settings")) {
                Text(stringResource(when (state.state) {
                    Status.SIGNED_OUT -> R.string.cloud_card_login
                    Status.INDEPENDENT -> R.string.cloud_card_manage
                    else -> R.string.cloud_card_settings
                }))
            }
        }
        if (!state.operationInFlight && state.state in setOf(Status.CONNECTION_FAILED, Status.CANCELLED, Status.CONFLICT, Status.RECOVERY_REQUIRED)) {
            TextButton(onClick = onSkip, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("steam-cloud-skip")) {
                Text(stringResource(R.string.cloud_card_skip), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (shouldShowSteamCloudBackgroundSyncAction(state)) FilledTonalButton(onClick = onBackground,
            enabled = enabled && state.state != Status.CANCELLING, shape = buttonShape, colors = tonalColors, contentPadding = buttonPadding,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("steam-cloud-background")) {
            Text(stringResource(R.string.cloud_card_background))
        }
        if (canResolveSteamCloudCard(state)) Text(stringResource(R.string.cloud_card_overwrite_note),
            modifier = Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun cloudTitle(state: CloudState): String = stringResource(when (steamCloudCardDisplayState(state)) {
    Status.HIDDEN -> R.string.cloud_card_unchecked
    Status.CHECKING -> R.string.cloud_card_checking
    Status.SYNCING -> state.phase?.let(::steamCloudPhaseLabel) ?: R.string.cloud_card_preparing
    Status.UP_TO_DATE -> R.string.cloud_card_synced
    Status.CONFLICT -> R.string.cloud_card_conflict
    Status.CONNECTION_FAILED -> R.string.cloud_card_failed
    Status.CANCELLING -> R.string.cloud_card_cancelling
    Status.CANCELLED -> R.string.cloud_card_cancelled
    Status.DEFERRED -> R.string.cloud_card_unchecked
    Status.RECOVERY_REQUIRED -> R.string.cloud_card_conflict
    Status.DISABLED -> R.string.cloud_card_disabled
    Status.INDEPENDENT -> R.string.cloud_card_independent
    Status.SIGNED_OUT -> R.string.cloud_card_signed_out
})

@Composable
private fun cloudHint(state: CloudState): String = when (state.state) {
    Status.CANCELLING -> stringResource(R.string.cloud_card_cancelling_hint)
    Status.CANCELLED -> stringResource(R.string.cloud_card_cancelled_hint)
    Status.DEFERRED -> stringResource(R.string.cloud_card_deferred_hint)
    Status.RECOVERY_REQUIRED -> stringResource(R.string.cloud_card_resolution_hint)
    Status.DISABLED -> stringResource(R.string.cloud_card_disabled_hint)
    Status.INDEPENDENT -> stringResource(R.string.cloud_card_independent_hint)
    Status.SIGNED_OUT -> stringResource(R.string.cloud_card_signed_out_hint)
    Status.UP_TO_DATE -> stringResource(R.string.cloud_card_synced_hint)
    Status.CHECKING -> stringResource(R.string.cloud_card_checking_hint)
    Status.SYNCING -> stringResource(when (state.phase) {
        SteamCloudSyncPhase.DOWNLOADING -> R.string.cloud_card_downloading_hint
        SteamCloudSyncPhase.UPLOADING -> R.string.cloud_card_uploading_hint
        else -> R.string.cloud_card_syncing_hint
    })
    Status.CONFLICT -> stringResource(R.string.cloud_card_conflict_hint)
    Status.CONNECTION_FAILED -> stringResource(R.string.cloud_card_failed_hint)
    else -> stringResource(R.string.cloud_card_unchecked_hint)
}

private fun cloudIcon(state: CloudState): Int = when (steamCloudCardDisplayState(state)) {
    Status.UP_TO_DATE -> R.drawable.ic_cloud_done
    Status.CONFLICT, Status.RECOVERY_REQUIRED -> R.drawable.ic_cloud_alert
    Status.SYNCING -> when (state.phase) {
        SteamCloudSyncPhase.DOWNLOADING -> R.drawable.ic_workshop_download
        SteamCloudSyncPhase.UPLOADING -> R.drawable.ic_arrow_upward
        SteamCloudSyncPhase.DELETING_REMOTE -> R.drawable.ic_delete
        SteamCloudSyncPhase.VERIFYING_REMOTE, SteamCloudSyncPhase.FINALIZING -> R.drawable.ic_check_circle
        SteamCloudSyncPhase.APPLYING_TO_LOCAL, SteamCloudSyncPhase.BACKING_UP_LOCAL -> R.drawable.ic_inventory
        else -> R.drawable.ic_cloud_sync
    }
    Status.CHECKING, Status.CANCELLING -> R.drawable.ic_cloud_sync
    Status.INDEPENDENT -> R.drawable.ic_inventory
    Status.SIGNED_OUT -> R.drawable.ic_account_circle
    Status.DEFERRED -> R.drawable.ic_pause
    Status.CONNECTION_FAILED, Status.CANCELLED, Status.DISABLED -> R.drawable.ic_cloud_off
    else -> R.drawable.ic_cloud_queue
}

@Composable
private fun cloudTint(state: CloudState): Color = when (steamCloudCardDisplayState(state)) {
    Status.CONNECTION_FAILED, Status.RECOVERY_REQUIRED -> MaterialTheme.colorScheme.error
    Status.CONFLICT -> if (MaterialTheme.colorScheme.surface.luminance() < .5f) Color(0xFFEAC58A) else Color(0xFF815919)
    Status.UP_TO_DATE -> if (MaterialTheme.colorScheme.surface.luminance() < .5f) Color(0xFF9BD5BD) else Color(0xFF2A6557)
    Status.CHECKING, Status.SYNCING -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun cloudSurface(): Color = if (MaterialTheme.colorScheme.surface.luminance() < .5f)
    MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest

@Composable
private fun CloudActionLabel(@DrawableRes icon: Int, label: String) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
    Text(label, modifier = Modifier.padding(start = 6.dp))
}

@Composable
private fun CloudMetric(@DrawableRes icon: Int, value: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics { contentDescription = description }) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun cloudWarning(warning: String): String {
    val resources = LocalResources.current
    return when (val parsed = SteamCloudUserWarning.parse(warning)) {
        is SteamCloudUserWarning.UnsupportedLocalPath -> stringResource(R.string.main_steam_cloud_warning_unsupported_local_path, parsed.localRelativePath)
        is SteamCloudUserWarning.FailedToMapLocalFile -> stringResource(R.string.main_steam_cloud_warning_failed_to_map_local_file, parsed.localRelativePath)
        SteamCloudUserWarning.BaselineRequired -> stringResource(R.string.cloud_card_baseline_required)
        is SteamCloudUserWarning.IgnoredLocalDeletions -> resources.getQuantityString(
            R.plurals.cloud_card_ignored_preference_deletions, parsed.count, parsed.count)
        is SteamCloudUserWarning.UnsupportedRemotePath -> stringResource(R.string.main_steam_cloud_warning_unsupported_remote_path, parsed.remotePath)
        is SteamCloudUserWarning.DuplicateMappedLocalPath -> stringResource(R.string.main_steam_cloud_warning_duplicate_mapped_local_path, parsed.localRelativePath)
        null -> warning
    }
}

@Preview(showBackground = true, widthDp = 360)
@Preview(name = "Chinese, large text", showBackground = true, widthDp = 320, fontScale = 1.5f, locale = "zh-rCN")
@Composable
private fun CloudCardDownloadPreview() {
    MaterialTheme {
        SteamCloudStatusCard(CloudState(visible = true, state = Status.SYNCING,
            phase = SteamCloudSyncPhase.DOWNLOADING, completedFiles = 3, totalFiles = 8), {})
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun CloudCardRecoveryPreview() {
    MaterialTheme { SteamCloudStatusCard(CloudState(visible = true, state = Status.RECOVERY_REQUIRED), {}) }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun CloudCardWarningsPreview() {
    MaterialTheme {
        SteamCloudStatusCard(CloudState(visible = true, state = Status.UP_TO_DATE,
            warnings = listOf("Preview warning")), {})
    }
}

@Preview(showBackground = true, widthDp = 360, locale = "zh-rCN")
@Preview(name = "Narrow, large text", showBackground = true, widthDp = 320, fontScale = 1.5f)
@Composable
private fun CloudRecoveryDetailsPreview() {
    MaterialTheme {
        SteamCloudStatusDetails(
            state = CloudState(visible = true, state = Status.RECOVERY_REQUIRED),
            controlsEnabled = true,
            onRefresh = {},
            onCancel = {},
            onResolve = {},
            onSkip = {},
            onLaunch = {},
            onBackgroundSync = {},
        )
    }
}

package io.stamethyst.ui.resources

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.launch.StartupProgressCallback
import io.stamethyst.backend.launch.progressText
import io.stamethyst.backend.resources.ExternalResourcePackService
import io.stamethyst.backend.resources.ResourcePackDownloadMirrorSwitchController
import io.stamethyst.backend.resources.ResourcePackSlowDownloadMirrorSwitch
import io.stamethyst.backend.update.GithubMirrorFallback
import io.stamethyst.backend.update.LauncherUpdateService
import io.stamethyst.backend.update.UpdateMirrorManager
import io.stamethyst.backend.update.UpdateSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val QUARK_BROWSER_PACKAGE_NAME = "com.quark.browser"

@Composable
fun LauncherResourceGate(
    modifier: Modifier = Modifier,
    onResourcesReady: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val coroutineScope = rememberCoroutineScope()
    val quarkDownloadUrl = stringResource(R.string.update_dialog_quark_download_url)
    var gateState by remember {
        mutableStateOf<ResourceGateState>(
            ResourceGateState.Preparing(
                percent = 0,
                message = context.progressText(R.string.startup_progress_checking_external_resources)
            )
        )
    }
    var selectedMirror by remember {
        mutableStateOf(UpdateMirrorManager.current(context))
    }
    val mirrorSwitchController = remember { ResourcePackDownloadMirrorSwitchController() }
    var slowDownloadSwitch by remember {
        mutableStateOf<ResourcePackSlowDownloadMirrorSwitch?>(null)
    }
    var retryNonce by remember { mutableIntStateOf(0) }
    var readyNotified by remember { mutableStateOf(false) }
    var resolvingFullRelease by remember { mutableStateOf(false) }

    LaunchedEffect(mirrorSwitchController) {
        val listener: (ResourcePackSlowDownloadMirrorSwitch?) -> Unit = { prompt ->
            coroutineScope.launch {
                slowDownloadSwitch = prompt
            }
        }
        mirrorSwitchController.addSlowDownloadListener(listener)
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            mirrorSwitchController.removeSlowDownloadListener(listener)
        }
    }

    LaunchedEffect(retryNonce) {
        mirrorSwitchController.clearSlowDownloadPrompt()
        slowDownloadSwitch = null
        readyNotified = false
        gateState = ResourceGateState.Preparing(
            percent = 0,
            message = context.progressText(R.string.startup_progress_checking_external_resources)
        )
        runCatching {
            withContext(Dispatchers.IO) {
                ExternalResourcePackService.ensureAvailable(
                    context = applicationContext,
                    progressCallback = StartupProgressCallback { percent, message ->
                        gateState = ResourceGateState.Preparing(
                            percent = percent.coerceIn(0, 100),
                            message = message
                        )
                    },
                    mirrorSwitchController = mirrorSwitchController
                )
            }
        }.fold(
            onSuccess = {
                mirrorSwitchController.clearSlowDownloadPrompt()
                gateState = ResourceGateState.Ready
            },
            onFailure = { error ->
                mirrorSwitchController.clearSlowDownloadPrompt()
                gateState = ResourceGateState.Failed(
                    summary = GithubMirrorFallback.summarize(error)
                        .ifBlank { error.javaClass.simpleName }
                )
            }
        )
    }

    val resourcesReady = gateState is ResourceGateState.Ready
    if (resourcesReady) {
        LaunchedEffect(Unit) {
            if (!readyNotified) {
                readyNotified = true
                onResourcesReady()
            }
        }
    }

    AnimatedContent(
        targetState = resourcesReady,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            (fadeIn(
                animationSpec = tween(durationMillis = 220, delayMillis = 100)
            ) + scaleIn(
                initialScale = 0.98f,
                animationSpec = tween(durationMillis = 260, delayMillis = 100)
            )) togetherWith
                (fadeOut(animationSpec = tween(durationMillis = 160)) +
                    slideOutVertically(
                        targetOffsetY = { -it / 8 },
                        animationSpec = tween(durationMillis = 220)
                    )) using SizeTransform(clip = false)
        },
        label = "resource-gate-transition"
    ) { ready ->
        if (ready) {
            content()
        } else {
            ResourcePreparationScreen(
                state = gateState,
                selectedMirror = selectedMirror,
                availableMirrors = remember(context) { UpdateMirrorManager.selectableSources(context) },
                slowDownloadSwitch = slowDownloadSwitch,
                onMirrorSelected = { source ->
                    selectedMirror = source
                    UpdateMirrorManager.saveCurrent(context, source)
                    retryNonce++
                },
                onSlowDownloadMirrorSwitch = {
                    val prompt = slowDownloadSwitch
                    if (prompt != null) {
                        prompt.nextPreferredMirrorSource?.let { nextMirror ->
                            selectedMirror = nextMirror
                            UpdateMirrorManager.saveCurrent(context, nextMirror)
                        }
                        mirrorSwitchController.requestSwitchToNextMirror()
                    }
                },
                onRetry = { retryNonce++ },
                onOpenFullRelease = {
                    if (!resolvingFullRelease) {
                        resolvingFullRelease = true
                        coroutineScope.launch {
                            val downloadTarget = resolveLatestFullReleaseDownloadTarget(
                                context = applicationContext,
                                selectedMirror = selectedMirror
                            )
                            resolvingFullRelease = false
                            when (downloadTarget) {
                                is FullReleaseDownloadTarget.Github -> openExternalUrl(
                                    context,
                                    downloadTarget.url
                                )

                                FullReleaseDownloadTarget.Quark -> copyAndOpenQuarkDownload(
                                    context,
                                    quarkDownloadUrl
                                )
                            }
                        }
                    }
                },
                resolvingFullRelease = resolvingFullRelease,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ResourcePreparationScreen(
    state: ResourceGateState,
    selectedMirror: UpdateSource,
    availableMirrors: List<UpdateSource>,
    slowDownloadSwitch: ResourcePackSlowDownloadMirrorSwitch?,
    onMirrorSelected: (UpdateSource) -> Unit,
    onSlowDownloadMirrorSwitch: () -> Unit,
    onRetry: () -> Unit,
    onOpenFullRelease: () -> Unit,
    resolvingFullRelease: Boolean,
    modifier: Modifier = Modifier,
) {
    var showMirrorDialog by remember { mutableStateOf(false) }
    val preparing = state as? ResourceGateState.Preparing
    val failed = state as? ResourceGateState.Failed

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.resource_gate_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (preparing != null) {
                        val progress = preparing.percent.coerceIn(0, 100)
                        val animatedProgress by animateFloatAsState(
                            targetValue = progress / 100f,
                            animationSpec = tween(
                                durationMillis = 450,
                                easing = FastOutSlowInEasing
                            ),
                            label = "resource-gate-progress"
                        )
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(R.string.resource_gate_progress_percent, progress),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = preparing.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (preparing != null && slowDownloadSwitch != null) {
                        Text(
                            text = stringResource(R.string.resource_gate_slow_download_switch_mirror),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable(onClick = onSlowDownloadMirrorSwitch)
                                .padding(vertical = 4.dp)
                        )
                    }

                    MirrorSelectionRow(
                        selectedMirror = selectedMirror,
                        enabled = preparing == null,
                        onClick = { showMirrorDialog = true }
                    )

                    if (failed != null) {
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.resource_gate_retry))
                        }
                        Text(
                            text = stringResource(R.string.resource_gate_failure_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        SelectionContainer {
                            Text(
                                text = failed.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = stringResource(R.string.resource_gate_full_release_fallback),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.resource_gate_full_release_quick_download),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .clickable(onClick = onOpenFullRelease)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }

    if (showMirrorDialog) {
        AlertDialog(
            onDismissRequest = { showMirrorDialog = false },
//            title = { Text(stringResource(R.string.resource_gate_mirror_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableMirrors.forEach { source ->
                        MirrorOptionRow(
                            selected = selectedMirror == source,
                            text = source.displayName,
                            onClick = {
                                showMirrorDialog = false
                                onMirrorSelected(source)
                            }
                        )
                    }
//                    Text(
//                        text = stringResource(R.string.resource_gate_mirror_description),
//                        style = MaterialTheme.typography.bodySmall,
//                        color = MaterialTheme.colorScheme.onSurfaceVariant
//                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showMirrorDialog = false }) {
                    Text(stringResource(R.string.main_folder_dialog_confirm))
                }
            }
        )
    }

    if (resolvingFullRelease) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.resource_gate_fetching_latest_full_release)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.resource_gate_fetching_latest_full_release_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun MirrorSelectionRow(
    selectedMirror: UpdateSource,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
//        Text(
//            text = stringResource(R.string.resource_gate_mirror_title),
//            style = MaterialTheme.typography.labelLarge
//        )
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = selectedMirror.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = stringResource(R.string.resource_gate_mirror_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MirrorOptionRow(
    selected: Boolean,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private sealed interface ResourceGateState {
    data class Preparing(
        val percent: Int,
        val message: String
    ) : ResourceGateState

    data object Ready : ResourceGateState

    data class Failed(
        val summary: String
    ) : ResourceGateState
}

private sealed interface FullReleaseDownloadTarget {
    data class Github(val url: String) : FullReleaseDownloadTarget
    data object Quark : FullReleaseDownloadTarget
}

private suspend fun resolveLatestFullReleaseDownloadTarget(
    context: Context,
    selectedMirror: UpdateSource,
): FullReleaseDownloadTarget {
    return withContext(Dispatchers.IO) {
        runCatching<FullReleaseDownloadTarget> {
            val latestFullRelease = LauncherUpdateService.fetchLatestFullRelease(
                context = context,
                preferredUserSource = selectedMirror
            )
            FullReleaseDownloadTarget.Github(selectedMirror.buildUrl(latestFullRelease.assetDownloadUrl))
        }.getOrDefault(FullReleaseDownloadTarget.Quark)
    }
}

private fun copyAndOpenQuarkDownload(context: Context, url: String) {
    copyToClipboard(context, "quark-download-url", url)
    val quarkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .setPackage(QUARK_BROWSER_PACKAGE_NAME)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (!tryStartActivity(context, quarkIntent)) {
        openExternalUrl(context, url)
    }
}

private fun openExternalUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    tryStartActivity(context, intent)
}

private fun tryStartActivity(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
} catch (_: IllegalArgumentException) {
    false
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

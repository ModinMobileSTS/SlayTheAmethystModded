package io.stamethyst.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import io.stamethyst.ui.LauncherAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.ui.LauncherDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import io.stamethyst.R
import io.stamethyst.backend.mods.AgentPatchCatalogueService
import io.stamethyst.backend.mods.AgentPatchInstallProgress
import io.stamethyst.backend.mods.AgentPatchInstallStage
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.backend.mods.AgentPatchUploadAuthor
import io.stamethyst.backend.mods.AgentPatchUploadProgress
import io.stamethyst.backend.mods.AgentPatchUploadResult
import io.stamethyst.backend.mods.AgentPatchUploadStage
import io.stamethyst.backend.mods.RemoteAgentPatch
import io.stamethyst.model.AgentPatchModUi
import io.stamethyst.model.ModItemUi
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun interface AgentPatchFileAction {
    fun invoke(patch: AgentPatchModUi)
}

@Composable
internal fun AgentPatchCatalogueDialog(
    mod: ModItemUi,
    localPatches: List<AgentPatchModUi>,
    workshopId: ULong?,
    initialLocalActionPatchId: String? = null,
    initialLocalUploadPatchId: String? = null,
    localOnly: Boolean = false,
    contributionsOnly: Boolean = false,
    actionsOnly: Boolean = false,
    onDismiss: () -> Unit,
    onInstalled: () -> Unit,
    onCatalogueRefreshed: () -> Unit = {},
    onExport: AgentPatchFileAction,
    onShare: AgentPatchFileAction,
    onRename: (AgentPatchModUi, String, String) -> Unit,
) {
    val context = LocalContext.current
    // Configuration changes do not invalidate LocalContext reads, so strings come from Resources.
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("ai_patch_catalogue_upload", 0) }
    val parentModId = mod.manifestModId.ifBlank { mod.modId }
    val parentVersion = mod.version.trim()
    var snapshot by remember(parentModId) {
        mutableStateOf(
            if (localOnly || actionsOnly) null
            else AgentPatchCatalogueService.readCached(context.applicationContext, parentModId)
        )
    }
    var error by remember(parentModId) { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(AgentPatchFilter.ALL) }
    var details by remember { mutableStateOf<RemoteAgentPatch?>(null) }
    var localDetails by remember { mutableStateOf<AgentPatchModUi?>(null) }
    var localActionsPatchId by remember(initialLocalActionPatchId) { mutableStateOf(initialLocalActionPatchId) }
    var showStandaloneActions by remember(initialLocalActionPatchId, actionsOnly) { mutableStateOf(actionsOnly) }
    var pendingUploadPatch by remember(initialLocalUploadPatchId, localPatches) {
        mutableStateOf(localPatches.firstOrNull { it.patchId == initialLocalUploadPatchId })
    }
    var renameTarget by remember { mutableStateOf<AgentPatchModUi?>(null) }
    var workingPatchModId by remember { mutableStateOf<String?>(null) }
    var installState by remember { mutableStateOf<AgentPatchInstallUiState?>(null) }
    var uploadPhase by remember { mutableStateOf(AgentPatchUploadPhase.FORM) }
    var uploadProgress by remember {
        mutableStateOf(AgentPatchUploadProgress(AgentPatchUploadStage.PREPARING, 0f))
    }
    var uploadCheckpointProgress by remember {
        mutableStateOf(AgentPatchUploadProgress(AgentPatchUploadStage.PREPARING, 0f))
    }
    var uploadError by remember { mutableStateOf<String?>(null) }
    var uploadResult by remember { mutableStateOf<AgentPatchUploadResult?>(null) }
    var deleteTarget by remember { mutableStateOf<AgentPatchModUi?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var username by remember { mutableStateOf(prefs.getString("username", "").orEmpty()) }
    var email by remember { mutableStateOf(prefs.getString("email", "").orEmpty()) }
    var consent by remember { mutableStateOf(false) }

    LaunchedEffect(parentModId, localOnly, actionsOnly) {
        if (localOnly || actionsOnly) return@LaunchedEffect
        error = null
        runCatching { AgentPatchCatalogueService.refresh(context.applicationContext, parentModId) }
            .onSuccess {
                snapshot = it
                if (!it.fromCache) onCatalogueRefreshed()
            }
            .onFailure {
                if (it is CancellationException) throw it
                error = it.message ?: "Catalogue request failed."
            }
    }

    LaunchedEffect(pendingUploadPatch?.patchId) {
        if (pendingUploadPatch != null) {
            uploadPhase = AgentPatchUploadPhase.FORM
            uploadProgress = AgentPatchUploadProgress(AgentPatchUploadStage.PREPARING, 0f)
            uploadError = null
            uploadResult = null
            consent = false
        }
    }

    val startInstall: (RemoteAgentPatch) -> Unit = { patch ->
        if (workingPatchModId == null) {
            details = null
            installState = AgentPatchInstallUiState(
                patch = patch,
                progress = AgentPatchInstallProgress(AgentPatchInstallStage.PREPARING, 0f),
            )
            workingPatchModId = patch.patchModId
            scope.launch {
                runCatching {
                    AgentPatchCatalogueService.install(context.applicationContext, parentModId, patch) { progress ->
                        scope.launch(Dispatchers.Main.immediate) {
                            installState = installState
                                ?.takeIf {
                                    it.patch.patchModId == patch.patchModId &&
                                        !it.done &&
                                        it.error == null
                                }
                                ?.copy(progress = progress)
                        }
                    }
                }.onSuccess {
                    installState = installState
                        ?.takeIf { it.patch.patchModId == patch.patchModId }
                        ?.copy(
                            progress = AgentPatchInstallProgress(AgentPatchInstallStage.DONE, 1f),
                            done = true,
                        )
                    notice = resources.getString(R.string.main_mod_agent_patch_install_success, patch.name)
                    onInstalled()
                }.onFailure {
                    if (it is CancellationException) throw it
                    installState = installState
                        ?.takeIf { it.patch.patchModId == patch.patchModId }
                        ?.copy(error = it.message.orEmpty())
                }
            }
        }
    }

    val closeUpload: () -> Unit = {
        if (uploadPhase != AgentPatchUploadPhase.UPLOADING) {
            pendingUploadPatch = null
            uploadPhase = AgentPatchUploadPhase.FORM
            uploadError = null
            uploadResult = null
            workingPatchModId = null
            if (actionsOnly) onDismiss()
        }
    }

    val startUpload: () -> Unit = {
        val patch = pendingUploadPatch
        if (patch != null && uploadPhase != AgentPatchUploadPhase.UPLOADING) {
            uploadPhase = AgentPatchUploadPhase.UPLOADING
            uploadProgress = AgentPatchUploadProgress(AgentPatchUploadStage.PREPARING, 0f)
            uploadCheckpointProgress = uploadProgress
            uploadError = null
            uploadResult = null
            workingPatchModId = patch.patchModId
            scope.launch {
                val info = withContext(Dispatchers.IO) {
                    AgentPatchModManager.listPackaged(context.applicationContext, parentModId)
                        .firstOrNull { it.patchId == patch.patchId }
                }
                if (info == null) {
                    uploadError = resources.getString(R.string.main_mod_agent_patch_upload_failed, "Patch file is missing.")
                    uploadPhase = AgentPatchUploadPhase.ERROR
                    workingPatchModId = null
                    return@launch
                }
                val progressUpdates = Channel<AgentPatchUploadProgress>(Channel.CONFLATED)
                val progressJob = launch {
                    val presenter = AgentPatchUploadProgressPresenter(show = { uploadCheckpointProgress = it })
                    for (value in progressUpdates) presenter.update(value)
                }
                val result = runCatching {
                    AgentPatchCatalogueService.upload(
                        context.applicationContext,
                        parentModId,
                        workshopId,
                        info,
                        AgentPatchUploadAuthor(username, email),
                        consent,
                        listOf(mod.version),
                        onProgress = { value ->
                            scope.launch(Dispatchers.Main.immediate) {
                                uploadProgress = value
                            }
                            progressUpdates.trySend(value)
                        },
                    )
                }
                progressUpdates.close()
                if (result.isSuccess) progressJob.join() else progressJob.cancel()
                result.onSuccess { uploaded ->
                    prefs.edit().putString("username", username.trim()).putString("email", email.trim()).apply()
                    uploadResult = uploaded
                    uploadProgress = AgentPatchUploadProgress(AgentPatchUploadStage.DONE, 1f)
                    workingPatchModId = null
                }.onFailure {
                    if (it is CancellationException) throw it
                    uploadError = resources.getString(R.string.main_mod_agent_patch_upload_failed, it.message.orEmpty())
                    uploadPhase = AgentPatchUploadPhase.ERROR
                    workingPatchModId = null
                }
                if (result.isSuccess) {
                    uploadPhase = AgentPatchUploadPhase.THANKS
                }
            }
        }
    }

    val localByModId = remember(localPatches) { localPatches.associateBy { it.patchModId } }
    val visibleRemote = remember(snapshot, filter, localByModId, parentVersion) {
        val matching = snapshot?.patches.orEmpty().asSequence()
            .map { patch -> patch to localByModId[patch.patchModId] }
            .filter { (remote, installed) ->
                when (filter) {
                    AgentPatchFilter.ALL -> true
                    AgentPatchFilter.INSTALL -> installed == null
                    AgentPatchFilter.UPDATE -> installed != null && compareVersions(remote.version, installed.version) > 0
                }
            }
            .toList()
        val (compatible, otherVersions) = matching.partition { isCompatibleWithVersion(it.first, parentVersion) }
        compatible + otherVersions
    }

    if (!actionsOnly && contributionsOnly && !localOnly) {
        AgentPatchContributionDialog(
            mod = mod,
            patches = localPatches,
            remotePatches = snapshot?.patches,
            error = error,
            onDismiss = onDismiss,
            onUpload = { pendingUploadPatch = it; notice = null },
            onRetry = {
                scope.launch {
                    error = null
                    runCatching { AgentPatchCatalogueService.refresh(context.applicationContext, parentModId, force = true) }
                        .onSuccess { snapshot = it; onCatalogueRefreshed() }
                        .onFailure {
                            if (it is CancellationException) throw it
                            error = it.message ?: "Catalogue request failed."
                        }
                }
            },
        )
    }

    if (!actionsOnly && (!contributionsOnly || localOnly)) Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .then(if (localOnly) Modifier.animateContentSize(animationSpec = tween(durationMillis = 180)) else Modifier),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        if (localOnly) stringResource(R.string.main_mod_agent_patch_action_title)
                        else stringResource(R.string.main_mod_agent_patch_catalogue_title, mod.name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!localOnly) {
                        Text(
                            stringResource(R.string.main_mod_agent_patch_source_github),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider()
                if (!localOnly) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AgentPatchFilter.entries.forEach { item ->
                            FilterChip(
                                selected = filter == item,
                                onClick = { filter = item },
                                label = {
                                    Text(stringResource(when (item) {
                                        AgentPatchFilter.ALL -> R.string.main_mod_agent_patch_filter_all
                                        AgentPatchFilter.INSTALL -> R.string.main_mod_agent_patch_filter_install
                                        AgentPatchFilter.UPDATE -> R.string.main_mod_agent_patch_filter_update
                                    }))
                                },
                            )
                        }
                    }
                    // Keep the same viewport for every filter, including empty results. Animating
                    // the wrap-content dialog height also moves its centered Android window.
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .height(420.dp)
                            .clipToBounds(),
                    ) {
                        if (snapshot == null && error == null) {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                repeat(AGENT_PATCH_SKELETON_ROWS) {
                                    AgentPatchSkeletonRow()
                                }
                            }
                        } else if (error != null && snapshot == null) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    scope.launch {
                                        error = null
                                        runCatching { AgentPatchCatalogueService.refresh(context.applicationContext, parentModId, force = true) }
                                            .onSuccess {
                                                snapshot = it
                                                if (!it.fromCache) onCatalogueRefreshed()
                                            }
                                            .onFailure {
                                                if (it is CancellationException) throw it
                                                error = it.message ?: "Catalogue request failed."
                                            }
                                    }
                                }) { Text(stringResource(R.string.main_mod_agent_patch_retry)) }
                            }
                        } else if (visibleRemote.isEmpty()) {
                            Text(stringResource(R.string.main_mod_agent_patch_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                items(visibleRemote, key = { it.first.patchModId }) { (patch, installed) ->
                                    val hasUpdate = installed != null && compareVersions(patch.version, installed.version) > 0
                                    RemoteAgentPatchRow(
                                        modifier = Modifier.animateItem(),
                                        patch = patch,
                                        parentVersion = parentVersion,
                                        installed = installed != null,
                                        hasUpdate = hasUpdate,
                                        working = workingPatchModId == patch.patchModId,
                                        onDetails = { details = patch },
                                        onInstall = { startInstall(patch) },
                                    )
                                }
                            }
                        }
                    }
                    if (error != null && snapshot != null) {
                        Text(error.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    if (localPatches.isEmpty()) {
                        Text(stringResource(R.string.main_mod_agent_patch_local_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = false).heightIn(max = 480.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            items(localPatches, key = { it.patchId }) { patch ->
                                LocalAgentPatchRow(
                                    modifier = Modifier.animateItem(),
                                    patch = patch,
                                    onDetails = { localDetails = patch },
                                    onExport = { onExport.invoke(patch) },
                                    onShare = { onShare.invoke(patch) },
                                    onUpload = { pendingUploadPatch = patch; notice = null },
                                    onRename = { renameTarget = patch },
                                    onDelete = { deleteTarget = patch },
                                    openActionsOnLoad = localActionsPatchId == patch.patchId,
                                )
                            }
                        }
                    }
                }

                notice?.let { text ->
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (showStandaloneActions) {
        val selectedPatch = localPatches.firstOrNull { it.patchId == localActionsPatchId }
        if (selectedPatch == null) {
            LocalAgentPatchChooserDialog(
                patches = localPatches,
                onSelect = { localActionsPatchId = it.patchId },
                onDismiss = onDismiss,
            )
        } else {
            LocalAgentPatchActionsDialog(
                patch = selectedPatch,
                onDismiss = onDismiss,
                onUpload = { showStandaloneActions = false; pendingUploadPatch = selectedPatch; notice = null },
                onExport = { onDismiss(); onExport.invoke(selectedPatch) },
                onShare = { onDismiss(); onShare.invoke(selectedPatch) },
                onRename = { showStandaloneActions = false; renameTarget = selectedPatch },
                onDelete = { showStandaloneActions = false; deleteTarget = selectedPatch },
            )
        }
    }

    if (actionsOnly && !showStandaloneActions && pendingUploadPatch == null && renameTarget == null && notice != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(notice.orEmpty()) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) } },
        )
    }

    details?.let { patch ->
        val installed = localByModId[patch.patchModId]
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(patch.name.ifBlank { stringResource(R.string.main_mod_agent_patch_unnamed) }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.animateContentSize()) {
                    Text("${stringResource(R.string.main_mod_agent_patch_version_format, patch.version)} · ${compatibilityText(patch, parentVersion)}")
                    if (patch.authorName.isNotBlank()) {
                        Text(stringResource(R.string.main_mod_agent_patch_author, patch.authorName), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(patch.description.ifBlank { stringResource(R.string.main_mod_agent_patch_description_empty) }, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.main_mod_agent_patch_catalogue_source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (installed != null && compareVersions(patch.version, installed.version) > 0) {
                        Text(stringResource(R.string.main_mod_agent_patch_update), color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = workingPatchModId == null &&
                        (installed == null || compareVersions(patch.version, installed.version) > 0),
                    onClick = { startInstall(patch) },
                ) {
                    Text(
                        stringResource(
                            if (installed != null) R.string.main_mod_agent_patch_update
                            else R.string.main_mod_agent_patch_install
                        )
                    )
                }
            },
            dismissButton = { TextButton(onClick = { details = null }) { Text(stringResource(R.string.common_action_close)) } },
        )
    }

    localDetails?.let { patch ->
        AgentPatchDetailsDialog(
            patch = patch,
            onDismiss = { localDetails = null },
        )
    }

    installState?.let { state ->
        AgentPatchInstallProgressDialog(
            state = state,
            onDismiss = {
                installState = null
                workingPatchModId = null
            },
        )
    }

    pendingUploadPatch?.let { patch ->
        AgentPatchUploadDialog(
            patch = patch,
            phase = uploadPhase,
            progress = uploadProgress,
            checkpointProgress = uploadCheckpointProgress,
            error = uploadError,
            result = uploadResult,
            username = username,
            onUsernameChange = {
                username = it
                prefs.edit().putString("username", it).apply()
            },
            email = email,
            onEmailChange = {
                email = it
                prefs.edit().putString("email", it).apply()
            },
            consent = consent,
            onConsentChange = { consent = it },
            onDismissRequest = closeUpload,
            onStartUpload = startUpload,
            onBackToForm = {
                uploadPhase = AgentPatchUploadPhase.FORM
                uploadError = null
                uploadResult = null
            },
            onClose = closeUpload,
        )
    }

    renameTarget?.let { patch ->
        var newName by remember(patch.patchId) { mutableStateOf(patch.name) }
        var newDescription by remember(patch.patchId) { mutableStateOf(patch.description) }
        AlertDialog(
            onDismissRequest = {
                renameTarget = null
                if (actionsOnly) onDismiss()
            },
            title = { Text(stringResource(R.string.main_mod_agent_patch_rename_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.main_mod_agent_patch_rename_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newDescription,
                        onValueChange = { newDescription = it.take(4000) },
                        label = { Text(stringResource(R.string.main_mod_agent_patch_rename_description)) },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameTarget = null
                        onRename(patch, newName, newDescription)
                        if (actionsOnly) onDismiss()
                    },
                    enabled = newName.trim().isNotEmpty(),
                ) {
                    Text(stringResource(R.string.main_folder_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    renameTarget = null
                    if (actionsOnly) onDismiss()
                }) { Text(stringResource(R.string.main_folder_dialog_cancel)) }
            },
        )
    }

    deleteTarget?.let { patch ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.main_mod_agent_patch_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.main_mod_agent_patch_delete_message,
                        patch.name.ifBlank { stringResource(R.string.main_mod_agent_patch_unnamed) },
                    )
                )
            },
            confirmButton = {
                TextButton(
                    enabled = workingPatchModId == null,
                    onClick = {
                        deleteTarget = null
                        workingPatchModId = patch.patchId
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    AgentPatchModManager.delete(context.applicationContext, parentModId, patch.patchId)
                                }
                            }.onSuccess {
                                localDetails = null
                                notice = resources.getString(
                                    R.string.main_mod_agent_patch_delete_success,
                                    patch.name.ifBlank { resources.getString(R.string.main_mod_agent_patch_unnamed) },
                                )
                                onInstalled()
                            }.onFailure {
                                if (it is CancellationException) throw it
                                notice = resources.getString(R.string.main_mod_agent_patch_delete_failed, it.message.orEmpty())
                            }
                            workingPatchModId = null
                        }
                    },
                ) {
                    Text(
                        stringResource(R.string.main_mod_agent_patch_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.main_folder_dialog_cancel))
                }
            },
        )
    }
}

private enum class AgentPatchFilter { ALL, INSTALL, UPDATE }

@Composable
internal fun AgentPatchDetailsDialog(
    patch: AgentPatchModUi,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(patch.name.ifBlank { stringResource(R.string.main_mod_agent_patch_unnamed) }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.main_mod_agent_patch_version_format, patch.version))
                Text(
                    patch.description.ifBlank { stringResource(R.string.main_mod_agent_patch_description_empty) },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (patch.enabled) {
                        stringResource(R.string.main_mod_agent_patch_installed)
                    } else {
                        stringResource(R.string.main_mod_agent_patch_local_tab)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) }
        },
    )
}

private const val AGENT_PATCH_SKELETON_ROWS = 3

@Composable
private fun AgentPatchSkeletonRow() {
    val transition = rememberInfiniteTransition(label = "agent_patch_skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "agent_patch_skeleton_alpha",
    )
    val placeholder = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    Card(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(placeholder)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.78f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(placeholder)
                )
            }
            Box(
                modifier = Modifier
                    .width(70.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(placeholder)
            )
        }
    }
}

@Composable
private fun LocalAgentPatchChooserDialog(
    patches: List<AgentPatchModUi>,
    onSelect: (AgentPatchModUi) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.main_mod_agent_patch_action_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.main_mod_agent_patch_choose_for_actions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                if (patches.isEmpty()) {
                    Text(
                        stringResource(R.string.main_mod_agent_patch_local_empty),
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(patches, key = { it.patchId }) { patch ->
                            ModActionOptionItem(
                                text = patch.name.ifBlank { stringResource(R.string.main_mod_agent_patch_unnamed) },
                                description = stringResource(R.string.main_mod_agent_patch_version_format, patch.version),
                                icon = R.drawable.ic_code,
                                enabled = true,
                                onClick = { onSelect(patch) },
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillCancelButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_action_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteAgentPatchRow(
    modifier: Modifier = Modifier,
    patch: RemoteAgentPatch,
    parentVersion: String,
    installed: Boolean,
    hasUpdate: Boolean,
    working: Boolean,
    onDetails: () -> Unit,
    onInstall: () -> Unit,
) {
    Card(
        modifier = modifier.animateContentSize(animationSpec = tween(durationMillis = 180)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onDetails).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(patch.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (patch.authorName.isNotBlank()) {
                        Text(patch.authorName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(stringResource(R.string.main_mod_agent_patch_version_format, patch.version), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(compatibilityText(patch, parentVersion), style = MaterialTheme.typography.labelSmall, color = if (isCompatibleWithVersion(patch, parentVersion)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(
                    enabled = !working && (!installed || hasUpdate),
                    onClick = onInstall,
                    modifier = Modifier.animateContentSize(animationSpec = tween(durationMillis = 180)),
                ) {
                    Text(
                        stringResource(
                            if (hasUpdate) R.string.main_mod_agent_patch_update
                            else if (installed) R.string.main_mod_agent_patch_installed
                            else R.string.main_mod_agent_patch_install
                        )
                    )
                }
            }
            if (hasUpdate) Text("${stringResource(R.string.main_mod_agent_patch_update)} · ${patch.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun compatibilityText(patch: RemoteAgentPatch, parentVersion: String): String {
    val versions = patch.targetVersions.takeIf { it.isNotEmpty() }?.joinToString().orEmpty().ifBlank { "*" }
    return if (isCompatibleWithVersion(patch, parentVersion)) {
        stringResource(R.string.main_mod_agent_patch_compatible, versions)
    } else {
        stringResource(R.string.main_mod_agent_patch_targets, versions)
    }
}

private fun isCompatibleWithVersion(patch: RemoteAgentPatch, parentVersion: String): Boolean =
    patch.targetVersions.isEmpty() || "*" in patch.targetVersions || parentVersion in patch.targetVersions

@Composable
private fun LocalAgentPatchRow(
    modifier: Modifier = Modifier,
    patch: AgentPatchModUi,
    onDetails: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onUpload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    openActionsOnLoad: Boolean,
) {
    var showActions by remember(patch.patchId, openActionsOnLoad) { mutableStateOf(openActionsOnLoad) }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onDetails)) {
                Text(patch.name.ifBlank { stringResource(R.string.main_mod_agent_patch_unnamed) }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(patch.version, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            androidx.compose.material3.IconButton(onClick = { showActions = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
        }
    }
    if (showActions) LocalAgentPatchActionsDialog(
        patch = patch,
        onDismiss = { showActions = false },
        onUpload = { showActions = false; onUpload() },
        onExport = { showActions = false; onExport() },
        onShare = { showActions = false; onShare() },
        onRename = { showActions = false; onRename() },
        onDelete = { showActions = false; onDelete() },
    )
}

@Composable
private fun LocalAgentPatchActionsDialog(
    patch: AgentPatchModUi,
    onDismiss: () -> Unit,
    onUpload: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.main_mod_agent_patch_action_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    patch.name.ifBlank { stringResource(R.string.main_mod_agent_patch_unnamed) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ModActionOptionItem(
                        text = stringResource(R.string.main_mod_agent_patch_upload),
                        description = stringResource(R.string.main_mod_agent_patch_upload_action_desc),
                        icon = R.drawable.ic_cloud_sync,
                        enabled = true,
                        onClick = onUpload,
                    )
                    ModActionOptionItem(
                        text = stringResource(R.string.main_mod_agent_patch_export),
                        description = stringResource(R.string.main_mod_agent_patch_export_action_desc),
                        icon = R.drawable.ic_workshop_download,
                        enabled = true,
                        onClick = onExport,
                    )
                    ModActionOptionItem(
                        text = stringResource(R.string.main_mod_agent_patch_share),
                        description = stringResource(R.string.main_mod_agent_patch_share_action_desc),
                        icon = R.drawable.ic_lan_room_share,
                        enabled = true,
                        onClick = onShare,
                    )
                    ModActionOptionItem(
                        text = stringResource(R.string.main_mod_agent_patch_rename),
                        description = stringResource(R.string.main_mod_agent_patch_rename_action_desc),
                        icon = R.drawable.ic_edit,
                        enabled = true,
                        onClick = onRename,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                    ModActionOptionItem(
                        text = stringResource(R.string.main_mod_agent_patch_delete),
                        description = stringResource(R.string.main_mod_agent_patch_delete_action_desc),
                        icon = R.drawable.ic_delete,
                        enabled = true,
                        danger = true,
                        onClick = onDelete,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillCancelButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) }
                }
            }
        }
    }
}

private enum class AgentPatchUploadPhase { FORM, UPLOADING, THANKS, ERROR }

private data class AgentPatchInstallUiState(
    val patch: RemoteAgentPatch,
    val progress: AgentPatchInstallProgress,
    val done: Boolean = false,
    val error: String? = null,
) {
    val working: Boolean get() = !done && error == null
}

private enum class AgentPatchStepStatus { DONE, ACTIVE, PENDING }

private val INSTALL_STAGES = listOf(
    AgentPatchInstallStage.PREPARING,
    AgentPatchInstallStage.DOWNLOADING,
    AgentPatchInstallStage.VERIFYING,
    AgentPatchInstallStage.INSPECTING,
    AgentPatchInstallStage.PACKAGING,
    AgentPatchInstallStage.INSTALLING,
)

private val INSTALL_INDETERMINATE_STAGES = setOf(
    AgentPatchInstallStage.VERIFYING,
    AgentPatchInstallStage.INSPECTING,
    AgentPatchInstallStage.PACKAGING,
    AgentPatchInstallStage.INSTALLING,
)

private val UPLOAD_STAGES = AGENT_PATCH_UPLOAD_CHECKPOINTS

@Composable
private fun AgentPatchInstallProgressDialog(
    state: AgentPatchInstallUiState,
    onDismiss: () -> Unit,
) {
    val working = state.working
    val progress = state.progress
    val animated by animateFloatAsState(
        targetValue = progress.fraction.coerceIn(0f, 1f),
        animationSpec = tween(320),
        label = "agent_patch_install_progress",
    )
    Dialog(
        onDismissRequest = { if (!working) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !working,
            dismissOnClickOutside = !working,
        ),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(220)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    when {
                        state.done -> stringResource(R.string.main_mod_agent_patch_install_done_title)
                        state.error != null -> stringResource(R.string.main_mod_agent_patch_install_failed_title)
                        else -> stringResource(R.string.main_mod_agent_patch_install_progress_title)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    state.patch.name.ifBlank { stringResource(R.string.main_mod_agent_patch_unnamed) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${(animated * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (progress.stage == AgentPatchInstallStage.DOWNLOADING && progress.totalBytes > 0L) {
                        Text(
                            "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!state.done && state.error == null && progress.stage in INSTALL_INDETERMINATE_STAGES) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    val currentIndex = INSTALL_STAGES.indexOf(progress.stage)
                        .let { if (it < 0) INSTALL_STAGES.size else it }
                    INSTALL_STAGES.forEachIndexed { index, stage ->
                        val status = when {
                            state.done || index < currentIndex -> AgentPatchStepStatus.DONE
                            index == currentIndex -> AgentPatchStepStatus.ACTIVE
                            else -> AgentPatchStepStatus.PENDING
                        }
                        val detail = when {
                            status != AgentPatchStepStatus.ACTIVE -> null
                            stage == AgentPatchInstallStage.DOWNLOADING && progress.totalBytes > 0L -> {
                                val percent = ((progress.downloadedBytes * 100) / progress.totalBytes)
                                    .toInt()
                                    .coerceIn(0, 100)
                                stringResource(
                                    R.string.main_mod_agent_patch_progress_detail,
                                    percent,
                                    formatBytes(progress.downloadedBytes),
                                    formatBytes(progress.totalBytes),
                                )
                            }
                            else -> stringResource(R.string.main_mod_agent_patch_stage_processing)
                        }
                        AgentPatchStepRow(
                            label = installStageLabel(stage),
                            detail = detail,
                            status = status,
                        )
                    }
                }
                state.error?.let { message ->
                    Text(
                        stringResource(R.string.main_mod_agent_patch_install_failed, message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.done || state.error != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.common_action_close))
                        }
                    }
                }
            }
        }
    }
    if (state.done) {
        LaunchedEffect(state.patch.patchModId) {
            delay(1200)
            onDismiss()
        }
    }
}

@Composable
private fun AgentPatchUploadDialog(
    patch: AgentPatchModUi,
    phase: AgentPatchUploadPhase,
    progress: AgentPatchUploadProgress,
    checkpointProgress: AgentPatchUploadProgress,
    error: String?,
    result: AgentPatchUploadResult?,
    username: String,
    onUsernameChange: (String) -> Unit,
    email: String,
    onEmailChange: (String) -> Unit,
    consent: Boolean,
    onConsentChange: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
    onStartUpload: () -> Unit,
    onBackToForm: () -> Unit,
    onClose: () -> Unit,
) {
    val busy = phase == AgentPatchUploadPhase.UPLOADING
    val patchName = patch.name.ifBlank { stringResource(R.string.main_mod_agent_patch_unnamed) }
    Dialog(
        onDismissRequest = { if (!busy) onDismissRequest() },
        properties = DialogProperties(
            dismissOnBackPress = !busy,
            dismissOnClickOutside = !busy,
        ),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(220)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "agent_patch_upload_title",
                ) { current ->
                    Text(
                        when (current) {
                            AgentPatchUploadPhase.FORM -> stringResource(R.string.main_mod_agent_patch_upload_author_title)
                            AgentPatchUploadPhase.UPLOADING -> stringResource(R.string.main_mod_agent_patch_uploading_title)
                            AgentPatchUploadPhase.THANKS -> stringResource(R.string.main_mod_agent_patch_upload_thanks_title)
                            AgentPatchUploadPhase.ERROR -> stringResource(R.string.main_mod_agent_patch_upload_failed_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    patchName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 14 })
                            .togetherWith(fadeOut(tween(140)))
                    },
                    label = "agent_patch_upload_content",
                ) { current ->
                    when (current) {
                        AgentPatchUploadPhase.FORM -> AgentPatchUploadForm(
                            username = username,
                            onUsernameChange = onUsernameChange,
                            email = email,
                            onEmailChange = onEmailChange,
                            consent = consent,
                            onConsentChange = onConsentChange,
                            onCancel = onClose,
                            onUpload = onStartUpload,
                        )
                        AgentPatchUploadPhase.UPLOADING -> AgentPatchUploadProgressContent(progress, checkpointProgress)
                        AgentPatchUploadPhase.THANKS -> AgentPatchUploadThanksContent(
                            result = result,
                            onClose = onClose,
                        )
                        AgentPatchUploadPhase.ERROR -> AgentPatchUploadErrorContent(
                            error = error.orEmpty(),
                            onBack = onBackToForm,
                            onClose = onClose,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentPatchUploadForm(
    username: String,
    onUsernameChange: (String) -> Unit,
    email: String,
    onEmailChange: (String) -> Unit,
    consent: Boolean,
    onConsentChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onUpload: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(
            modifier = Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(stringResource(R.string.main_mod_agent_patch_upload_author_prompt), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.main_mod_agent_patch_upload_identity_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.main_mod_agent_patch_upload_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = email,
                onValueChange = onEmailChange,
                label = { Text(stringResource(R.string.main_mod_agent_patch_upload_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.main_mod_agent_patch_upload_no_email), style = MaterialTheme.typography.labelSmall)
            Text(
                stringResource(R.string.main_mod_agent_patch_upload_public_identity),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.main_mod_agent_patch_upload_password_warning),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.clickable { onConsentChange(!consent) },
            ) {
                androidx.compose.material3.Checkbox(checked = consent, onCheckedChange = onConsentChange)
                Text(stringResource(R.string.main_mod_agent_patch_upload_consent), style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_action_close)) }
            Button(
                onClick = onUpload,
                enabled = consent && username.isNotBlank() && email.isNotBlank(),
            ) {
                Text(stringResource(R.string.main_mod_agent_patch_upload_confirm))
            }
        }
    }
}

@Composable
private fun AgentPatchUploadProgressContent(
    progress: AgentPatchUploadProgress,
    checkpointProgress: AgentPatchUploadProgress,
) {
    val animated by animateFloatAsState(
        targetValue = progress.fraction.coerceIn(0f, 1f),
        animationSpec = tween(320),
        label = "agent_patch_upload_progress",
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${(animated * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress.stage == AgentPatchUploadStage.UPLOADING && progress.totalBytes > 0L) {
                Text(
                    "${formatBytes(progress.uploadedBytes)} / ${formatBytes(progress.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            val currentIndex = UPLOAD_STAGES.indexOf(checkpointProgress.stage)
                .let { if (it < 0) UPLOAD_STAGES.size else it }
            UPLOAD_STAGES.forEachIndexed { index, stage ->
                val status = when {
                    checkpointProgress.stage == AgentPatchUploadStage.DONE || index < currentIndex -> AgentPatchStepStatus.DONE
                    index == currentIndex -> AgentPatchStepStatus.ACTIVE
                    else -> AgentPatchStepStatus.PENDING
                }
                val detail = when {
                    status != AgentPatchStepStatus.ACTIVE -> null
                    stage == AgentPatchUploadStage.UPLOADING && checkpointProgress.totalBytes > 0L -> {
                        val percent = ((checkpointProgress.uploadedBytes * 100) / checkpointProgress.totalBytes)
                            .toInt()
                            .coerceIn(0, 100)
                        stringResource(
                            R.string.main_mod_agent_patch_progress_detail,
                            percent,
                            formatBytes(checkpointProgress.uploadedBytes),
                            formatBytes(checkpointProgress.totalBytes),
                        )
                    }
                    else -> stringResource(R.string.main_mod_agent_patch_stage_processing)
                }
                AgentPatchStepRow(
                    label = uploadStageLabel(stage),
                    detail = detail,
                    status = status,
                )
            }
        }
        if (progress.stage == AgentPatchUploadStage.PACKAGING_SOURCES ||
            progress.stage == AgentPatchUploadStage.PROCESSING
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
        }
        when (progress.stage) {
            AgentPatchUploadStage.PROCESSING -> Text(
                stringResource(R.string.main_mod_agent_patch_upload_processing_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AgentPatchUploadStage.DONE -> Unit
            else -> Text(
                stringResource(R.string.main_mod_agent_patch_upload_busy_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AgentPatchUploadThanksContent(
    result: AgentPatchUploadResult?,
    onClose: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AgentPatchCelebrationParticles(
            modifier = Modifier.fillMaxWidth().height(96.dp).clipToBounds(),
        )
        Text(
            stringResource(R.string.main_mod_agent_patch_upload_thanks_message),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        result?.let {
            Text(
                stringResource(
                    R.string.main_mod_agent_patch_upload_thanks_status,
                    it.indexing.ifBlank { "unknown" },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (it.commitUrl.isNotBlank()) {
                TextButton(onClick = { runCatching { uriHandler.openUri(it.commitUrl) } }) {
                    Text(stringResource(R.string.main_mod_agent_patch_upload_view_commit))
                }
            }
        }
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_action_close))
        }
    }
}

@Composable
private fun AgentPatchUploadErrorContent(
    error: String,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.main_mod_agent_patch_upload_failed, error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.common_action_close)) }
            Button(onClick = onBack) { Text(stringResource(R.string.main_mod_agent_patch_upload_back)) }
        }
    }
}

@Composable
private fun AgentPatchStepRow(
    label: String,
    detail: String?,
    status: AgentPatchStepStatus,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(
                    when (status) {
                        AgentPatchStepStatus.DONE -> MaterialTheme.colorScheme.primary
                        AgentPatchStepStatus.ACTIVE -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        AgentPatchStepStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (status) {
                AgentPatchStepStatus.DONE -> Text(
                    "✓",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                AgentPatchStepStatus.ACTIVE -> Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                AgentPatchStepStatus.PENDING -> Unit
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (status == AgentPatchStepStatus.ACTIVE) FontWeight.SemiBold else FontWeight.Normal,
                color = when (status) {
                    AgentPatchStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun installStageLabel(stage: AgentPatchInstallStage): String = stringResource(
    when (stage) {
        AgentPatchInstallStage.PREPARING -> R.string.main_mod_agent_patch_install_stage_preparing
        AgentPatchInstallStage.DOWNLOADING -> R.string.main_mod_agent_patch_install_stage_downloading
        AgentPatchInstallStage.VERIFYING -> R.string.main_mod_agent_patch_install_stage_verifying
        AgentPatchInstallStage.INSPECTING -> R.string.main_mod_agent_patch_install_stage_inspecting
        AgentPatchInstallStage.PACKAGING -> R.string.main_mod_agent_patch_install_stage_packaging
        AgentPatchInstallStage.INSTALLING -> R.string.main_mod_agent_patch_install_stage_installing
        AgentPatchInstallStage.DONE -> R.string.main_mod_agent_patch_install_done_title
    }
)

@Composable
private fun uploadStageLabel(stage: AgentPatchUploadStage): String = stringResource(
    when (stage) {
        AgentPatchUploadStage.PREPARING -> R.string.main_mod_agent_patch_upload_stage_preparing
        AgentPatchUploadStage.PACKAGING_SOURCES -> R.string.main_mod_agent_patch_upload_stage_packaging_sources
        AgentPatchUploadStage.UPLOADING -> R.string.main_mod_agent_patch_upload_stage_uploading
        AgentPatchUploadStage.PROCESSING -> R.string.main_mod_agent_patch_upload_stage_processing
        AgentPatchUploadStage.DONE -> R.string.main_mod_agent_patch_upload_thanks_title
    }
)

private data class AgentPatchParticle(
    val velocityX: Float,
    val velocityY: Float,
    val gravity: Float,
    val radius: Float,
    val colorIndex: Int,
    val delay: Float,
)

@Composable
private fun AgentPatchCelebrationParticles(modifier: Modifier = Modifier) {
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
    )
    val particles = remember {
        val random = Random(20261020)
        List(30) {
            val angle = random.nextFloat() * 2f * PI.toFloat()
            val speed = 0.32f + random.nextFloat() * 0.55f
            AgentPatchParticle(
                velocityX = cos(angle) * speed,
                velocityY = sin(angle) * speed - 0.12f,
                gravity = 0.85f + random.nextFloat() * 0.5f,
                radius = 2.5f + random.nextFloat() * 4f,
                colorIndex = it % 5,
                delay = random.nextFloat() * 0.12f,
            )
        }
    }
    val progress = remember { Animatable(0f) }
    val celebrationScale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.snapTo(0f)
        launch {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 2200, easing = LinearEasing),
            )
        }
        celebrationScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        )
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val time = progress.value
            val centerX = size.width / 2f
            val centerY = size.height * 0.58f
            particles.forEach { particle ->
                val localTime = ((time - particle.delay) / (1f - particle.delay)).coerceIn(0f, 1f)
                if (localTime <= 0f || localTime >= 1f) return@forEach
                val x = centerX + particle.velocityX * size.width * localTime
                val y = centerY + particle.velocityY * size.height * localTime +
                    0.5f * particle.gravity * size.height * localTime * localTime
                val alpha = ((1f - localTime) * 0.9f).coerceIn(0f, 1f)
                drawCircle(
                    color = colors[particle.colorIndex].copy(alpha = alpha),
                    radius = particle.radius * (1f - localTime * 0.25f),
                    center = Offset(x, y),
                )
            }
        }
        Text(
            "🎉",
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.graphicsLayer {
                scaleX = celebrationScale.value
                scaleY = celebrationScale.value
                alpha = celebrationScale.value.coerceIn(0f, 1f)
            },
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) {
        "$bytes B"
    } else {
        String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
    }
}

private fun compareVersions(left: String, right: String): Int {
    fun tokens(value: String): List<Long> = Regex("\\d+").findAll(value).mapNotNull { it.value.toLongOrNull() }.toList()
    val a = tokens(left)
    val b = tokens(right)
    for (index in 0 until maxOf(a.size, b.size)) {
        val difference = (a.getOrNull(index) ?: 0L).compareTo(b.getOrNull(index) ?: 0L)
        if (difference != 0) return difference
    }
    return 0
}

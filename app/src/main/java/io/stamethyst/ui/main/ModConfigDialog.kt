package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import io.stamethyst.ui.LauncherAlertDialog as AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import io.stamethyst.ui.LauncherSwitch as Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.launch.GameLaunchReturnTracker
import io.stamethyst.backend.mods.ModConfigConflictException
import io.stamethyst.backend.mods.ModConfigDocument
import io.stamethyst.backend.mods.ModConfigEntry
import io.stamethyst.backend.mods.ModConfigFile
import io.stamethyst.backend.mods.ModConfigLocator
import io.stamethyst.backend.mods.ModConfigLocationResult
import io.stamethyst.backend.mods.ModConfigRepository
import io.stamethyst.backend.mods.ModConfigValueType
import io.stamethyst.config.RuntimePaths
import io.stamethyst.model.ModItemUi
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ModConfigDialog(
    mod: ModItemUi,
    editingEnabled: Boolean,
    onDismiss: () -> Unit,
    configRoot: File? = null,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context, configRoot) {
        ModConfigRepository(configRoot ?: RuntimePaths.modTheSpireConfigDir(context))
    }
    val scope = rememberCoroutineScope()
    val contentState = rememberLazyListState()
    var files by remember { mutableStateOf<List<ModConfigFile>>(emptyList()) }
    var document by remember { mutableStateOf<ModConfigDocument?>(null) }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var codePaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var associatedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showUnassociated by remember { mutableStateOf(false) }
    var pendingAssociation by remember { mutableStateOf<ModConfigFile?>(null) }
    var scanIncomplete by remember { mutableStateOf(false) }
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val failureMessage = stringResource(R.string.mod_config_error)
    val conflictMessage = stringResource(R.string.mod_config_conflict)
    val readOnlyMessage = stringResource(R.string.mod_config_read_only)
    val dirty = document?.entries?.any { values[it.key] != it.value } == true
    val valid = document?.entries?.all { it.type.accepts(values[it.key].orEmpty()) } == true

    LaunchedEffect(document?.relativePath) { contentState.scrollToItem(0) }

    fun requestLeave(action: () -> Unit) {
        if (busy) return
        if (dirty) pendingLeave = action else action()
    }

    fun loadFile(file: ModConfigFile) {
        scope.launch {
            busy = true
            error = null
            saved = false
            try {
                val loaded = withContext(Dispatchers.IO) { repository.load(file.relativePath) }
                values = loaded.entries.associate { it.key to it.value }
                document = loaded
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                error = "$failureMessage\n${exception.message.orEmpty()}"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(repository, mod.storagePath) {
        try {
            val discovered = withContext(Dispatchers.IO) {
                val locations = try {
                    ModConfigLocator.locate(File(mod.storagePath))
                } catch (_: Exception) {
                    ModConfigLocationResult(emptySet(), true)
                }
                Triple(
                    repository.discover(listOf(mod.manifestModId, mod.modId, mod.name, File(mod.storagePath).nameWithoutExtension)),
                    locations,
                    if (configRoot == null) ModConfigAssociationStore.load(context, mod) else emptySet(),
                )
            }
            files = discovered.first
            codePaths = discovered.second.paths
            scanIncomplete = discovered.second.incomplete
            associatedPaths = discovered.third
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            error = "$failureMessage\n${exception.message.orEmpty()}"
        } finally {
            busy = false
        }
    }

    AlertDialog(
        scrollableText = false,
        onDismissRequest = { requestLeave(onDismiss) },
        title = {
            Column {
                Text(stringResource(R.string.mod_config_title))
                Text(resolveModDisplayName(mod, showModFileName = false), style = MaterialTheme.typography.bodyMedium)
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp).testTag("mod-config-list"),
                state = contentState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(stringResource(R.string.mod_config_hint), style = MaterialTheme.typography.bodySmall)
                }
                if (!editingEnabled) {
                    item {
                        Text(stringResource(R.string.mod_config_read_only), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (busy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                if (saved) item { Text(stringResource(R.string.mod_config_saved), color = MaterialTheme.colorScheme.primary) }
                val current = document
                if (current == null) {
                    item { Text(stringResource(R.string.mod_config_select_file), style = MaterialTheme.typography.titleSmall) }
                    val linkedFiles = files.filter { it.relativePath in codePaths || it.relativePath in associatedPaths }
                    if (scanIncomplete || (!busy && linkedFiles.isEmpty())) {
                        item { Text(stringResource(R.string.mod_config_unresolved), style = MaterialTheme.typography.bodySmall) }
                    }
                    val missingPaths = (codePaths + associatedPaths) - files.map { it.relativePath }.toSet()
                    if (!busy && missingPaths.isNotEmpty()) {
                        item { Text(stringResource(R.string.mod_config_missing, missingPaths.joinToString("\n")), style = MaterialTheme.typography.bodySmall) }
                    }
                    item {
                        TextButton(enabled = !busy, onClick = {
                            showUnassociated = !showUnassociated
                            filter = ""
                        }) {
                            Text(stringResource(if (showUnassociated) R.string.mod_config_linked_only else R.string.mod_config_manual_select))
                        }
                    }
                    if (showUnassociated) {
                        item { Text(stringResource(R.string.mod_config_matching_hint), style = MaterialTheme.typography.bodySmall) }
                    }
                    if (showUnassociated && files.isNotEmpty()) {
                        item {
                            OutlinedTextField(
                                value = filter,
                                onValueChange = { filter = it },
                                singleLine = true,
                                label = { Text(stringResource(R.string.mod_config_search)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (!busy && files.isEmpty() && error == null) {
                        item { Text(stringResource(R.string.mod_config_empty)) }
                    }
                    items(
                        (if (showUnassociated) files else linkedFiles)
                            .filter { it.relativePath.contains(filter, ignoreCase = true) },
                        key = { it.relativePath },
                    ) { file ->
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                if (file.relativePath in codePaths || file.relativePath in associatedPaths) loadFile(file)
                                else pendingAssociation = file
                            },
                            modifier = Modifier.fillMaxWidth().testTag("mod-config-file:${file.relativePath}"),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(file.relativePath)
                                val sourceLabel = when (file.relativePath) {
                                    in associatedPaths -> R.string.mod_config_manually_linked
                                    in codePaths -> R.string.mod_config_code_reference
                                    else -> if (file.recommended) R.string.mod_config_recommended else null
                                }
                                if (sourceLabel != null) {
                                    Text(stringResource(sourceLabel), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                } else {
                    item { Text(current.relativePath, style = MaterialTheme.typography.labelMedium) }
                    if (current.relativePath in associatedPaths) {
                        item {
                            TextButton(enabled = !busy, onClick = {
                                requestLeave {
                                    if (configRoot == null) ModConfigAssociationStore.remove(context, mod, current.relativePath)
                                    associatedPaths = associatedPaths - current.relativePath
                                    document = null
                                    values = emptyMap()
                                    error = null
                                    saved = false
                                }
                            }) { Text(stringResource(R.string.mod_config_unlink)) }
                        }
                    }
                    item {
                        TextButton(enabled = !busy, onClick = {
                            requestLeave {
                                document = null
                                values = emptyMap()
                                error = null
                                saved = false
                            }
                        }) { Text(stringResource(R.string.mod_config_change_file)) }
                    }
                    if (current.entries.isEmpty()) item { Text(stringResource(R.string.mod_config_no_entries)) }
                    items(current.entries, key = { it.key }) { entry ->
                        ModConfigEntryField(
                            entry = entry,
                            value = values.getValue(entry.key),
                            enabled = editingEnabled && !busy,
                            onValueChange = {
                                values = values + (entry.key to it)
                                saved = false
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (document != null) {
                TextButton(
                    enabled = editingEnabled && !busy && dirty && valid,
                    onClick = {
                        // Recheck the process at the point of saving, not just when the sheet opened.
                        if (!editingEnabled || GameLaunchReturnTracker.isGameProcessRunning(context)) {
                            error = readOnlyMessage
                            return@TextButton
                        }
                        val current = document ?: return@TextButton
                        val changes = values.toMap()
                        scope.launch {
                            busy = true
                            error = null
                            try {
                                document = withContext(Dispatchers.IO) { repository.save(current, changes) }
                                saved = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: ModConfigConflictException) {
                                error = conflictMessage
                            } catch (exception: Exception) {
                                error = "$failureMessage\n${exception.message.orEmpty()}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("mod-config-save"),
                ) { Text(stringResource(R.string.mod_config_save)) }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { requestLeave(onDismiss) }) {
                Text(stringResource(R.string.mod_config_close))
            }
        },
    )
    pendingAssociation?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingAssociation = null },
            title = { Text(stringResource(R.string.mod_config_associate_title)) },
            text = { Text(stringResource(R.string.mod_config_associate_message, file.relativePath)) },
            confirmButton = {
                TextButton(onClick = {
                    if (configRoot == null) ModConfigAssociationStore.associate(context, mod, file.relativePath)
                    associatedPaths = associatedPaths + file.relativePath
                    pendingAssociation = null
                    loadFile(file)
                }) { Text(stringResource(R.string.mod_config_associate_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingAssociation = null }) { Text(stringResource(R.string.mod_config_close)) }
            },
        )
    }
    if (pendingLeave != null) {
        AlertDialog(
            onDismissRequest = { pendingLeave = null },
            title = { Text(stringResource(R.string.mod_config_discard_title)) },
            text = { Text(stringResource(R.string.mod_config_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    val action = pendingLeave
                    pendingLeave = null
                    action?.invoke()
                }) { Text(stringResource(R.string.mod_config_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingLeave = null }) { Text(stringResource(R.string.mod_config_keep_editing)) }
            },
        )
    }
}

@Composable
private fun ModConfigEntryField(entry: ModConfigEntry, value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    if (entry.type == ModConfigValueType.BOOLEAN) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.key, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = value == "true",
                    enabled = enabled,
                    onCheckedChange = { onValueChange(it.toString()) },
                    modifier = Modifier.testTag("mod-config-entry:${entry.key}"),
                )
            }
            HorizontalDivider()
        }
    } else {
        val valid = entry.type.accepts(value)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("mod-config-entry:${entry.key}"),
            enabled = enabled,
            label = { Text(entry.key) },
            isError = !valid,
            supportingText = if (!valid) {
                { Text(stringResource(R.string.mod_config_invalid_number)) }
            } else null,
            keyboardOptions = KeyboardOptions(
                keyboardType = when (entry.type) {
                    ModConfigValueType.INTEGER -> KeyboardType.Number
                    ModConfigValueType.DECIMAL -> KeyboardType.Decimal
                    else -> KeyboardType.Text
                },
            ),
        )
    }
}

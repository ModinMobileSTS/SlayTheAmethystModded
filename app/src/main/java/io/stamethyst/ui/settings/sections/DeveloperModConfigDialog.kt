package io.stamethyst.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import io.stamethyst.ui.LauncherAlertDialog as AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.launch.GameLaunchReturnTracker
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.main.ModConfigDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun DeveloperModConfigDialog(controlsEnabled: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var mods by remember { mutableStateOf<List<ModItemUi>>(emptyList()) }
    var selectedMod by remember { mutableStateOf<ModItemUi?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var gameActive by remember { mutableStateOf(true) }

    LaunchedEffect(context) {
        try {
            mods = withContext(Dispatchers.IO) {
                ModManager.listInstalledMods(context).filter { it.installed && it.jarFile.isFile }.map { mod ->
                    ModItemUi(
                        modId = mod.modId,
                        manifestModId = mod.manifestModId,
                        storagePath = mod.jarFile.absolutePath,
                        name = mod.name,
                        version = mod.version,
                        description = mod.description,
                        dependencies = mod.dependencies,
                        required = mod.required,
                        installed = mod.installed,
                        enabled = mod.enabled,
                        explicitPriority = mod.explicitPriority,
                        effectivePriority = mod.effectivePriority,
                    )
                }.sortedBy { it.name.lowercase() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            error = exception.message ?: exception.javaClass.simpleName
        } finally {
            loading = false
        }
    }
    LaunchedEffect(context) {
        while (true) {
            gameActive = withContext(Dispatchers.IO) {
                GameLaunchReturnTracker.isGameProcessRunning(context) ||
                    GameLaunchReturnTracker.readPendingGameLaunchStartedAt(context)?.let {
                        GameLaunchReturnTracker.isWithinFreshLaunchHandoffWindow(it)
                    } == true
            }
            delay(1000)
        }
    }

    val selected = selectedMod
    if (selected != null) {
        key(selected.storagePath) {
            ModConfigDialog(
                mod = selected,
                editingEnabled = controlsEnabled && !gameActive,
                onDismiss = { selectedMod = null },
            )
        }
    } else {
        AlertDialog(
            scrollableText = false,
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.mod_config_developer_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.mod_config_developer_description), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        label = { Text(stringResource(R.string.mod_config_select_mod)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!loading && error == null && mods.isEmpty()) {
                        Text(stringResource(R.string.mod_config_no_mods))
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp).weight(1f, fill = false)) {
                        items(
                            mods.filter {
                                it.name.contains(filter, ignoreCase = true) ||
                                    it.manifestModId.contains(filter, ignoreCase = true)
                            },
                            key = { it.storagePath },
                        ) { mod ->
                            TextButton(onClick = { selectedMod = mod }, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(mod.name)
                                    Text(mod.manifestModId, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.mod_config_close)) }
            },
        )
    }
}

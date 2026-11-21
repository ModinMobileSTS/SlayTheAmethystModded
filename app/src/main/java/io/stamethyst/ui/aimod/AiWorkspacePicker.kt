package io.stamethyst.ui.aimod

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.mods.AgentWorkspaceEntry
import io.stamethyst.ui.LauncherAlertDialog

@Composable
internal fun AiWorkspacePicker(vm: AiModEditorViewModel) {
    val session = vm.sessions.firstOrNull { it.id == vm.currentSessionId }
    val binding = session?.workspaceBinding
    var open by remember(vm.currentSessionId) { mutableStateOf(false) }
    val enabled = vm.storageReady && !vm.switchingConversation && !vm.busy
    val title = stringResource(R.string.ai_workspace_select)
    val label = when {
        binding?.origin == "missing" -> stringResource(R.string.ai_workspace_missing, binding.id.orEmpty())
        binding?.origin == "ambiguous" -> stringResource(R.string.ai_workspace_ambiguous)
        binding?.id != null -> "${binding.name ?: binding.id} · ${binding.id.takeLast(8)}"
        else -> stringResource(R.string.ai_workspace_unbound)
    }
    TextButton(onClick = { open = true }, enabled = enabled) {
        Icon(painterResource(R.drawable.ic_mod_folder), title, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
    }
    if (!open) return
    var rows by remember { mutableStateOf<List<Pair<AgentWorkspaceEntry, Boolean>>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { rows = vm.workspaceChoices() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    LauncherAlertDialog(scrollableText = false, onDismissRequest = { open = false }, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            when {
                failed -> Text(stringResource(R.string.ai_workspace_failed))
                rows == null -> CircularProgressIndicator()
                rows!!.isEmpty() -> Text(stringResource(R.string.ai_workspace_empty))
            }
            rows.orEmpty().forEach { (entry, packaged) ->
                TextButton(onClick = { open = false; vm.chooseWorkspace(entry.id) }, enabled = enabled) {
                    Text("${if (entry.id == binding?.id) "✓ " else ""}${entry.name} · ${entry.id.takeLast(8)}\n" +
                        stringResource(if (packaged) R.string.ai_workspace_packaged else R.string.ai_workspace_draft))
                }
            }
        }
    }, dismissButton = {
        TextButton(onClick = { open = false; vm.chooseWorkspace(null) }, enabled = enabled) {
            Text(stringResource(R.string.ai_workspace_unbound))
        }
    }, confirmButton = {
        TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_action_close)) }
    })
}

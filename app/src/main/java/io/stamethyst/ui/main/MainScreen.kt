package io.stamethyst.ui.main

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherMainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = MainScreenViewModel(),
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val hostActivity = context as? Activity
    val uiState = viewModel.uiState

    LaunchedEffect(hostActivity) {
        if (hostActivity != null) {
            viewModel.refresh(hostActivity)
        }
    }

    LauncherMainScreenContent(
        modifier = modifier,
        uiState = uiState,
        onOpenSettings = onOpenSettings,
        onDeleteMod = { mod ->
            if (hostActivity != null) {
                viewModel.onDeleteMod(hostActivity, mod)
            }
        },
        onToggleMod = { mod, checked ->
            if (hostActivity != null) {
                viewModel.onToggleMod(hostActivity, mod, checked)
            }
        },
        onLaunch = {
            if (hostActivity != null) {
                viewModel.onLaunch(hostActivity)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun LauncherMainScreenPreview() {
    LauncherMainScreenContent(
        uiState = MainScreenViewModel.UiState(
            busy = false,
            statusSummary = "desktop-1.0.jar: OK\nBaseMod.jar: OK\nStSLib.jar: OK",
            optionalMods = listOf(
                ModItemUi(
                    modId = "samplemod",
                    manifestModId = "SampleMod",
                    name = "Sample Mod",
                    version = "1.0.0",
                    description = "这是一个示例模组",
                    dependencies = listOf("basemod"),
                    required = false,
                    installed = true,
                    enabled = true
                )
            ),
            controlsEnabled = true
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherMainScreenContent(
    modifier: Modifier = Modifier,
    uiState: MainScreenViewModel.UiState,
    onOpenSettings: () -> Unit = {},
    onDeleteMod: (ModItemUi) -> Unit = {},
    onToggleMod: (ModItemUi, Boolean) -> Unit = { _, _ -> },
    onLaunch: () -> Unit = {},
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SlayTheAmethyst") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Settings,
                            contentDescription = "设置"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                uiState.busyMessage?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Text(
                text = uiState.statusSummary,
                style = MaterialTheme.typography.bodyMedium
            )

            HorizontalDivider()

            Text(
                text = "选择模组",
                style = MaterialTheme.typography.titleMedium
            )

            if (uiState.optionalMods.isEmpty()) {
                Text(
                    text = "暂无模组，请在创意工坊进行下载，并在设置中导入。",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                uiState.optionalMods.forEach { mod ->
                    OptionalModCard(
                        mod = mod,
                        controlsEnabled = uiState.controlsEnabled && mod.installed,
                        deleteEnabled = uiState.controlsEnabled && mod.installed,
                        onDeleteClick = { onDeleteMod(mod) },
                        onCheckedChange = { checked -> onToggleMod(mod, checked) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onLaunch,
                enabled = uiState.controlsEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("启动游戏")
            }
        }
    }
}

@Composable
private fun OptionalModCard(
    mod: ModItemUi,
    controlsEnabled: Boolean,
    deleteEnabled: Boolean,
    onDeleteClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    val resolvedName = mod.name.ifBlank { mod.manifestModId.ifBlank { mod.modId } }
    val resolvedModId = mod.manifestModId.ifBlank { mod.modId }
    val resolvedVersion = mod.version.ifBlank { "未知" }
    val resolvedDescription = mod.description.ifBlank { "暂无简介" }
    val resolvedDependencies = mod.dependencies
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                enabled = controlsEnabled,
                value = mod.enabled,
                onValueChange = onCheckedChange
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (mod.enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = mod.enabled,
                    onCheckedChange = null,
                    enabled = controlsEnabled
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = resolvedName,
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Text(
                text = "modid: $resolvedModId",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "版本: $resolvedVersion",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "简介: $resolvedDescription",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            if (resolvedDependencies.isNotEmpty()) {
                Text(
                    text = "前置: ${resolvedDependencies.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onDeleteClick,
                    enabled = deleteEnabled,
                    modifier = Modifier.heightIn(min = 30.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

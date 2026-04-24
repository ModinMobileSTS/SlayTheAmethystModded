package io.stamethyst.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.steamcloud.SteamCloudSyncBlacklist
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.ArrowBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherSteamCloudSaveSettingsScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState

    LaunchedEffect(activity) {
        viewModel.bind(activity)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_steam_cloud_save_settings_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = navigator::goBack,
                        enabled = !uiState.busy,
                    ) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSectionCard(title = stringResource(R.string.settings_steam_cloud_save_settings_title)) {
                SteamCloudSaveSettingsContent(
                    uiState = uiState,
                    onSteamCloudSaveModeChanged = { mode ->
                        viewModel.onSteamCloudSaveModeChanged(activity, mode)
                    },
                    onSteamCloudSyncBlacklistPathChanged = { localRelativePath, selected ->
                        viewModel.onSteamCloudSyncBlacklistPathChanged(
                            host = activity,
                            localRelativePath = localRelativePath,
                            selected = selected,
                        )
                    },
                    onForceIndependentSaveOverwriteCloud = {
                        viewModel.onForceIndependentSaveOverwriteCloud(activity)
                    },
                )
            }
        }
    }
}

@Composable
private fun SteamCloudSaveSettingsContent(
    uiState: SettingsScreenViewModel.UiState,
    onSteamCloudSaveModeChanged: (SteamCloudSaveMode) -> Unit,
    onSteamCloudSyncBlacklistPathChanged: (String, Boolean) -> Unit,
    onForceIndependentSaveOverwriteCloud: () -> Unit,
) {
    val loggedIn = uiState.steamCloudRefreshTokenConfigured
    var showForceOverwriteConfirmDialog by rememberSaveable { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.settings_steam_cloud_save_settings_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SteamCloudSaveModeOptionRow(
        selected = uiState.steamCloudSaveMode == SteamCloudSaveMode.INDEPENDENT,
        enabled = !uiState.busy,
        title = stringResource(R.string.settings_steam_cloud_save_mode_independent_title),
        description = stringResource(R.string.settings_steam_cloud_save_mode_independent_desc),
        onSelect = { onSteamCloudSaveModeChanged(SteamCloudSaveMode.INDEPENDENT) },
    )
    SteamCloudSaveModeOptionRow(
        selected = uiState.steamCloudSaveMode == SteamCloudSaveMode.STEAM_CLOUD,
        enabled = !uiState.busy && loggedIn,
        title = stringResource(R.string.settings_steam_cloud_save_mode_cloud_title),
        description = if (loggedIn) {
            stringResource(R.string.settings_steam_cloud_save_mode_cloud_desc)
        } else {
            stringResource(R.string.settings_steam_cloud_save_mode_cloud_disabled_desc)
        },
        onSelect = { onSteamCloudSaveModeChanged(SteamCloudSaveMode.STEAM_CLOUD) },
    )
    Text(
        text = stringResource(R.string.settings_steam_cloud_sync_blacklist_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        text = stringResource(R.string.settings_steam_cloud_sync_blacklist_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    uiState.steamCloudSyncBlacklistCandidates.forEach { localRelativePath ->
        SteamCloudSyncBlacklistOptionRow(
            localRelativePath = localRelativePath,
            selected = localRelativePath in uiState.steamCloudSyncBlacklistPaths,
            enabled = !uiState.busy,
            description = when (localRelativePath) {
                SteamCloudSyncBlacklist.DEFAULT_LOCAL_RELATIVE_PATH ->
                    stringResource(
                        R.string.settings_steam_cloud_sync_blacklist_path_desc_gameplay_settings
                    )

                else -> null
            },
            onCheckedChange = { selected ->
                onSteamCloudSyncBlacklistPathChanged(localRelativePath, selected)
            },
        )
    }
    if (loggedIn && uiState.steamCloudSaveMode == SteamCloudSaveMode.STEAM_CLOUD) {
        SteamCloudForceIndependentOverwritePanel(
            busy = uiState.busy,
            onClick = { showForceOverwriteConfirmDialog = true },
        )
    }

    if (showForceOverwriteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showForceOverwriteConfirmDialog = false },
            title = {
                Text(stringResource(R.string.settings_steam_cloud_force_independent_override_confirm_title))
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_steam_cloud_force_independent_override_confirm_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    enabled = !uiState.busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    onClick = {
                        showForceOverwriteConfirmDialog = false
                        onForceIndependentSaveOverwriteCloud()
                    },
                ) {
                    Text(stringResource(R.string.settings_steam_cloud_force_independent_override_confirm_action))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !uiState.busy,
                    onClick = { showForceOverwriteConfirmDialog = false },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SteamCloudSyncBlacklistOptionRow(
    localRelativePath: String,
    selected: Boolean,
    enabled: Boolean,
    description: String?,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled) { onCheckedChange(!selected) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Checkbox(
            checked = selected,
            enabled = enabled,
            onCheckedChange = null,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = localRelativePath,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                )
            }
        }
    }
}

@Composable
private fun SteamCloudSaveModeOptionRow(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    description: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(
            selected = selected,
            enabled = enabled,
            onClick = null,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
            )
        }
    }
}

@Composable
private fun SteamCloudForceIndependentOverwritePanel(
    busy: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_steam_cloud_force_independent_override_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Button(
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_steam_cloud_force_independent_override_action))
        }
    }
}

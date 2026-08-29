package io.stamethyst.ui.settings.sections

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.common.*
import io.stamethyst.ui.settings.core.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.services.*
import io.stamethyst.ui.settings.steamcloud.*

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.easytier.EasyTierNetworkMode
import io.stamethyst.backend.steamcloud.SteamCloudAvatarCacheStore
import io.stamethyst.backend.steamcloud.SteamCloudConflict
import io.stamethyst.backend.steamcloud.SteamCloudConflictKind
import io.stamethyst.backend.steamcloud.SteamCloudManifestEntry
import io.stamethyst.backend.steamcloud.SteamCloudRemoteOnlyChange
import io.stamethyst.backend.steamcloud.SteamCloudRemoteOnlyChangeKind
import io.stamethyst.backend.steamcloud.SteamCloudUploadCandidate
import io.stamethyst.backend.steamcloud.SteamCloudUploadCandidateKind
import io.stamethyst.backend.workshop.SteamLanguagePreference
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.config.RichPresenceDisplayPreferences
import io.stamethyst.config.RichPresencePrefix
import io.stamethyst.ui.preferences.LauncherPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


internal data class SteamCloudSettingsActions(
    val onOpenSteamCloudLogin: () -> Unit,
    val onSteamCloudWattAccelerationChanged: (Boolean) -> Unit,
    val onSteamCloudAutoLaunchAfterSyncChanged: (Boolean) -> Unit,
    val onSteamGamePresenceChanged: (Boolean) -> Unit,
    val onRichPresenceDisplayPreferencesChanged: (RichPresenceDisplayPreferences) -> Unit,
    val onSteamAchievementSyncChanged: (Boolean) -> Unit,
    val onAchievementUnlockNotificationChanged: (Boolean) -> Unit,
    val onOpenSteamCloudSaveSettings: () -> Unit,
    val onClearSteamCloudCredentials: () -> Unit,
    val onClearSteamCloudNetworkCache: () -> Unit,
)


internal data class MarketSettingsActions(
    val onWorkshopMaxConcurrentDownloadsChanged: (Int) -> Unit,
    val onWorkshopDownloadThreadsChanged: (Int) -> Unit,
    val onWorkshopWattAccelerationChanged: (Boolean) -> Unit,
    val onWorkshopSteamLanguageChanged: (SteamLanguagePreference) -> Unit,
    val onWorkshopAutoImportChanged: (Boolean) -> Unit,
    val onOpenWorkshopAutoImportDefaults: () -> Unit,
    val onClearWorkshopPreviewCache: () -> Unit,
    val onOpenBaiduTranslationCredentials: () -> Unit,
)


@Composable
internal fun SettingsEasyTierSection(
    uiState: SettingsScreenViewModel.UiState,
) {
    val settings = uiState.easyTierSettings
    val statusText = when {
        settings.canConnect -> stringResource(R.string.settings_easytier_status_ready)
        else -> stringResource(R.string.settings_easytier_status_config_missing)
    }
    val sharedNetworkText = if (settings.allowSharedCommunityNetwork) {
        stringResource(R.string.settings_easytier_shared_network_allowed)
    } else {
        stringResource(R.string.settings_easytier_shared_network_blocked)
    }
    val missingValueText = stringResource(R.string.settings_easytier_value_missing)

    Text(
        text = stringResource(R.string.settings_easytier_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.size(8.dp))
    EasyTierSettingsInfoCard(
        title = stringResource(R.string.settings_easytier_status_title),
        value = statusText,
    )
    EasyTierSettingsInfoCard(
        title = stringResource(R.string.settings_easytier_mode_title),
        value = easyTierSettingsModeLabel(settings.defaultMode),
    )
    EasyTierSettingsInfoCard(
        title = stringResource(R.string.settings_easytier_shared_network_title),
        value = sharedNetworkText,
    )
    EasyTierSettingsInfoCard(
        title = stringResource(R.string.settings_easytier_entry_node_title),
        value = settings.entryNodeUrl.ifBlank { missingValueText },
    )
    EasyTierSettingsInfoCard(
        title = stringResource(R.string.settings_easytier_room_api_title),
        value = settings.roomApiBaseUrl.ifBlank { missingValueText },
    )
    EasyTierSettingsInfoCard(
        title = stringResource(R.string.settings_easytier_config_server_title),
        value = settings.configServerUrl.ifBlank { missingValueText },
    )
    EasyTierSettingsInfoCard(
        title = stringResource(R.string.settings_easytier_polling_title),
        value = stringResource(
            R.string.settings_easytier_polling_summary,
            settings.connectTimeoutSeconds,
            settings.statusPollIntervalSeconds,
        ),
    )
    Text(
        text = stringResource(R.string.settings_easytier_vpn_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}


@Composable
private fun EasyTierSettingsInfoCard(
    title: String,
    value: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}


@Composable
private fun easyTierSettingsModeLabel(mode: EasyTierNetworkMode): String {
    return when (mode) {
        EasyTierNetworkMode.Room -> stringResource(R.string.main_easytier_mode_room)
        EasyTierNetworkMode.Community -> stringResource(R.string.main_easytier_mode_community)
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSteamCloudSection(
    uiState: SettingsScreenViewModel.UiState,
    actions: SteamCloudSettingsActions,
) {
    val currentSaveModeText = steamCloudSaveModeDisplayName(uiState.steamCloudSaveMode)

    Text(
        text = stringResource(R.string.settings_steam_cloud_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.size(8.dp))
    SettingsActionListItem(
        title = stringResource(R.string.settings_steam_cloud_save_settings_title),
        supportingText = stringResource(
            R.string.settings_steam_cloud_save_settings_summary,
            currentSaveModeText,
        ),
        enabled = !uiState.busy,
        onClick = actions.onOpenSteamCloudSaveSettings,
    )

    Spacer(modifier = Modifier.size(8.dp))
    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.steamCloudAutoLaunchAfterSyncEnabled,
            enabled = !uiState.busy,
            title = stringResource(R.string.settings_steam_cloud_auto_launch_after_sync_title),
            description = stringResource(R.string.settings_steam_cloud_auto_launch_after_sync_desc),
            onCheckedChange = actions.onSteamCloudAutoLaunchAfterSyncChanged,
        )
    )

}



@Composable
internal fun SteamNetworkSection(
    uiState: SettingsScreenViewModel.UiState,
    actions: SteamCloudSettingsActions,
) {
    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.steamCloudWattAccelerationEnabled,
            enabled = !uiState.busy,
            title = stringResource(R.string.settings_steam_cloud_watt_acceleration_enabled_title),
            description = stringResource(R.string.settings_steam_cloud_watt_acceleration_desc),
            onCheckedChange = actions.onSteamCloudWattAccelerationChanged,
        )
    )

    Spacer(modifier = Modifier.size(8.dp))
    SettingsActionListItem(
        title = stringResource(R.string.settings_steam_cloud_clear_network_cache_title),
        supportingText = stringResource(R.string.settings_steam_cloud_clear_network_cache_desc),
        enabled = !uiState.busy,
        onClick = actions.onClearSteamCloudNetworkCache,
    )
}



@Composable
internal fun SteamAccountSection(
    uiState: SettingsScreenViewModel.UiState,
    actions: SteamCloudSettingsActions,
) {
    var showLogoutConfirmDialog by rememberSaveable { mutableStateOf(false) }
    val accountName = uiState.steamCloudAccountName.ifBlank {
        stringResource(R.string.settings_steam_cloud_account_unknown)
    }
    val accountDisplayName = uiState.steamCloudPersonaName.ifBlank { accountName }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_steam_services_account_section_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SteamCloudAvatarImage(
                    loggedIn = uiState.steamCloudRefreshTokenConfigured,
                    avatarUrl = uiState.steamCloudAvatarUrl,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = if (uiState.steamCloudRefreshTokenConfigured) accountDisplayName
                               else stringResource(R.string.settings_steam_cloud_account_not_signed_in),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (uiState.steamCloudRefreshTokenConfigured)
                                   stringResource(R.string.settings_steam_cloud_account_signed_in)
                               else stringResource(R.string.settings_steam_cloud_account_login_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (uiState.steamCloudRefreshTokenConfigured) {
                    HapticTextButton(
                        enabled = !uiState.busy,
                        onClick = { showLogoutConfirmDialog = true },
                    ) {
                        Text(
                            text = stringResource(R.string.settings_steam_cloud_logout_action),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                } else {
                    Button(
                        enabled = !uiState.busy,
                        onClick = actions.onOpenSteamCloudLogin,
                    ) {
                        Text(stringResource(R.string.settings_steam_cloud_login_action))
                    }
                }
            }
        }
    }

    if (showLogoutConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmDialog = false },
            title = { Text(stringResource(R.string.settings_steam_cloud_logout_confirm_title)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_steam_cloud_logout_confirm_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                HapticTextButton(
                    enabled = !uiState.busy,
                    onClick = {
                        showLogoutConfirmDialog = false
                        actions.onClearSteamCloudCredentials()
                    }
                ) {
                    Text(stringResource(R.string.settings_steam_cloud_logout_action))
                }
            },
            dismissButton = {
                HapticTextButton(
                    enabled = !uiState.busy,
                    onClick = { showLogoutConfirmDialog = false }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}


@Composable
internal fun SteamGamePresenceSection(
    uiState: SettingsScreenViewModel.UiState,
    actions: SteamCloudSettingsActions,
) {
    var showRiskWarning by rememberSaveable { mutableStateOf(false) }
    var showRichPresenceEditor by rememberSaveable { mutableStateOf(false) }

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.steamGamePresenceEnabled,
            enabled = !uiState.busy && uiState.steamCloudRefreshTokenConfigured,
            title = stringResource(R.string.settings_steam_services_presence_enabled_title),
            description = stringResource(R.string.settings_steam_services_presence_desc),
            onCheckedChange = { enabled ->
                if (enabled) showRiskWarning = true
                else actions.onSteamGamePresenceChanged(false)
            },
        )
    )

    Spacer(modifier = Modifier.size(8.dp))
    SettingsActionListItem(
        title = stringResource(R.string.settings_steam_services_rich_presence_display_title),
        supportingText = richPresenceDisplaySummary(uiState.richPresenceDisplayPreferences),
        enabled = !uiState.busy && uiState.steamGamePresenceEnabled,
        onClick = { showRichPresenceEditor = true },
    )

    if (showRichPresenceEditor) {
        RichPresenceDisplayEditorDialog(
            initialSettings = uiState.richPresenceDisplayPreferences,
            onDismiss = { showRichPresenceEditor = false },
            onSave = { settings ->
                actions.onRichPresenceDisplayPreferencesChanged(settings)
                showRichPresenceEditor = false
            },
        )
    }

    if (showRiskWarning) {
        AlertDialog(
            onDismissRequest = { showRiskWarning = false },
            title = { Text(stringResource(R.string.settings_steam_services_risk_warning_title)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_steam_services_risk_warning_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                HapticTextButton(onClick = {
                    showRiskWarning = false
                    actions.onSteamGamePresenceChanged(true)
                }) {
                    Text(stringResource(R.string.settings_steam_services_risk_warning_confirm))
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { showRiskWarning = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun RichPresenceDisplayEditorDialog(
    initialSettings: RichPresenceDisplayPreferences,
    onDismiss: () -> Unit,
    onSave: (RichPresenceDisplayPreferences) -> Unit,
) {
    var settings by remember(initialSettings) { mutableStateOf(initialSettings) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_steam_services_rich_presence_display_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_steam_services_rich_presence_prefix_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                RichPresencePrefix.entries.forEach { prefix ->
                    SettingsRadioOptionRow(
                        selected = settings.prefix == prefix,
                        enabled = true,
                        text = richPresencePrefixLabel(prefix),
                        onSelect = { settings = settings.copy(prefix = prefix) },
                    )
                }
                SettingsSwitchItem(
                    SettingsSwitchSpec(
                        checked = settings.showCharacter,
                        enabled = true,
                        title = stringResource(R.string.settings_steam_services_rich_presence_show_character),
                        description = stringResource(R.string.settings_steam_services_rich_presence_show_character_desc),
                        onCheckedChange = { settings = settings.copy(showCharacter = it) },
                    ),
                )
                SettingsSwitchItem(
                    SettingsSwitchSpec(
                        checked = settings.showFloor,
                        enabled = true,
                        title = stringResource(R.string.settings_steam_services_rich_presence_show_floor),
                        description = stringResource(R.string.settings_steam_services_rich_presence_show_floor_desc),
                        onCheckedChange = { settings = settings.copy(showFloor = it) },
                    ),
                )
                SettingsSwitchItem(
                    SettingsSwitchSpec(
                        checked = settings.showAscension,
                        enabled = true,
                        title = stringResource(R.string.settings_steam_services_rich_presence_show_ascension),
                        description = stringResource(R.string.settings_steam_services_rich_presence_show_ascension_desc),
                        onCheckedChange = { settings = settings.copy(showAscension = it) },
                    ),
                )
                SettingsSwitchItem(
                    SettingsSwitchSpec(
                        checked = settings.showAct,
                        enabled = true,
                        title = stringResource(R.string.settings_steam_services_rich_presence_show_act),
                        description = stringResource(R.string.settings_steam_services_rich_presence_show_act_desc),
                        onCheckedChange = { settings = settings.copy(showAct = it) },
                    ),
                )
                Text(
                    text = stringResource(R.string.settings_steam_services_rich_presence_restart_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            HapticTextButton(onClick = { onSave(settings) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun richPresenceDisplaySummary(settings: RichPresenceDisplayPreferences): String {
    val parts = buildList {
        add(richPresencePrefixLabel(settings.prefix))
        if (settings.showCharacter) add(stringResource(R.string.settings_steam_services_rich_presence_summary_character))
        if (settings.showFloor) add(stringResource(R.string.settings_steam_services_rich_presence_summary_floor))
        if (settings.showAscension) add(stringResource(R.string.settings_steam_services_rich_presence_summary_ascension))
        if (settings.showAct) add(stringResource(R.string.settings_steam_services_rich_presence_summary_act))
    }
    return parts.joinToString(" · ")
}

@Composable
private fun richPresencePrefixLabel(prefix: RichPresencePrefix): String = when (prefix) {
    RichPresencePrefix.GAME -> stringResource(R.string.settings_steam_services_rich_presence_prefix_game)
    RichPresencePrefix.DEVICE -> stringResource(R.string.settings_steam_services_rich_presence_prefix_device)
    RichPresencePrefix.NONE -> stringResource(R.string.settings_steam_services_rich_presence_prefix_none)
}


@Composable
internal fun SteamAchievementSection(
    uiState: SettingsScreenViewModel.UiState,
    actions: SteamCloudSettingsActions,
) {
    var showRiskWarning by rememberSaveable { mutableStateOf(false) }

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.steamAchievementSyncEnabled,
            enabled = !uiState.busy && uiState.steamCloudRefreshTokenConfigured,
            title = stringResource(R.string.settings_steam_services_achievement_enabled_title),
            description = stringResource(R.string.settings_steam_services_achievement_desc),
            onCheckedChange = { enabled ->
                if (enabled) showRiskWarning = true
                else actions.onSteamAchievementSyncChanged(false)
            },
        )
    )

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.achievementUnlockNotificationEnabled,
            enabled = !uiState.busy,
            title = stringResource(R.string.settings_steam_services_achievement_notification_enabled_title),
            description = stringResource(R.string.settings_steam_services_achievement_notification_desc),
            onCheckedChange = actions.onAchievementUnlockNotificationChanged,
        )
    )

    if (showRiskWarning) {
        AlertDialog(
            onDismissRequest = { showRiskWarning = false },
            title = { Text(stringResource(R.string.settings_steam_services_risk_warning_title)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_steam_services_risk_warning_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                HapticTextButton(onClick = {
                    showRiskWarning = false
                    actions.onSteamAchievementSyncChanged(true)
                }) {
                    Text(stringResource(R.string.settings_steam_services_risk_warning_confirm))
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { showRiskWarning = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}



@Composable
internal fun SettingsMarketSection(
    uiState: SettingsScreenViewModel.UiState,
    actions: MarketSettingsActions,
) {
    Text(
        text = stringResource(R.string.settings_market_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.size(8.dp))
    NumberStepperSettingRow(
        title = stringResource(R.string.settings_market_concurrent_downloads_title),
        value = uiState.workshopMaxConcurrentDownloads,
        minValue = LauncherPreferences.MIN_WORKSHOP_MAX_CONCURRENT_DOWNLOADS,
        maxValue = LauncherPreferences.MAX_WORKSHOP_MAX_CONCURRENT_DOWNLOADS,
        description = stringResource(R.string.settings_market_concurrent_downloads_desc),
        enabled = !uiState.busy,
        onValueChange = actions.onWorkshopMaxConcurrentDownloadsChanged,
    )
    Spacer(modifier = Modifier.size(8.dp))
    NumberStepperSettingRow(
        title = stringResource(R.string.settings_market_download_threads_title),
        value = uiState.workshopDownloadThreads,
        minValue = LauncherPreferences.MIN_WORKSHOP_DOWNLOAD_THREADS,
        maxValue = LauncherPreferences.MAX_WORKSHOP_DOWNLOAD_THREADS,
        description = stringResource(R.string.settings_market_download_threads_desc),
        enabled = !uiState.busy,
        onValueChange = actions.onWorkshopDownloadThreadsChanged,
    )
    Spacer(modifier = Modifier.size(8.dp))
    SettingsDropdownField(
        label = stringResource(R.string.settings_market_workshop_language_title),
        valueText = uiState.workshopSteamLanguage.displayName,
        enabled = !uiState.busy,
        supportingText = stringResource(R.string.settings_market_workshop_language_desc),
        options = SteamLanguagePreference.entries,
        optionLabel = { it.displayName },
        onOptionSelected = actions.onWorkshopSteamLanguageChanged,
    )
    Spacer(modifier = Modifier.size(8.dp))
    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.workshopAutoImportEnabled,
            enabled = !uiState.busy,
            title = stringResource(R.string.settings_market_workshop_auto_import_enabled_title),
            description = stringResource(R.string.settings_market_workshop_auto_import_desc),
            onCheckedChange = actions.onWorkshopAutoImportChanged,
        )
    )
    Spacer(modifier = Modifier.size(8.dp))
    SettingsActionListItem(
        title = stringResource(R.string.settings_market_workshop_auto_import_defaults_title),
        supportingText = if (uiState.workshopAutoImportAtlasDownscaleEnabled) {
            stringResource(
                R.string.settings_market_workshop_auto_import_defaults_summary_enabled,
                uiState.workshopAutoImportAtlasDownscaleMaxEdgePx,
            )
        } else {
            stringResource(R.string.settings_market_workshop_auto_import_defaults_summary_disabled)
        },
        enabled = !uiState.busy,
        onClick = actions.onOpenWorkshopAutoImportDefaults,
    )
    Spacer(modifier = Modifier.size(8.dp))
    SettingsActionListItem(
        title = stringResource(R.string.settings_baidu_translation_credentials_title),
        supportingText = stringResource(
            if (uiState.baiduTranslationCredentialsConfigured) {
                R.string.settings_baidu_translation_credentials_configured
            } else {
                R.string.settings_baidu_translation_credentials_not_configured
            }
        ),
        enabled = !uiState.busy,
        onClick = actions.onOpenBaiduTranslationCredentials,
    )
    Spacer(modifier = Modifier.size(8.dp))
    SettingsActionListItem(
        title = stringResource(R.string.settings_market_clear_preview_cache_title),
        supportingText = stringResource(R.string.settings_market_clear_preview_cache_desc),
        enabled = !uiState.busy,
        onClick = actions.onClearWorkshopPreviewCache,
    )

}



@Composable
internal fun NumberStepperSettingRow(
    title: String,
    value: Int,
    minValue: Int,
    maxValue: Int,
    description: String,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
) {
    val view = LocalView.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HapticIconButton(
            enabled = enabled && value > minValue,
            onClick = {
                onValueChange((value - 1).coerceAtLeast(minValue))
                performTapHapticFeedback(view)
            }
        ) { Text("-") }
        Text(text = value.toString(), style = MaterialTheme.typography.titleMedium)
        HapticIconButton(
            enabled = enabled && value < maxValue,
            onClick = {
                onValueChange((value + 1).coerceAtMost(maxValue))
                performTapHapticFeedback(view)
            }
        ) { Text("+") }
    }
}



@Composable
internal fun steamCloudSaveModeDisplayName(mode: SteamCloudSaveMode): String {
    return when (mode) {
        SteamCloudSaveMode.INDEPENDENT ->
            stringResource(R.string.settings_steam_cloud_save_mode_independent_title)

        SteamCloudSaveMode.STEAM_CLOUD ->
            stringResource(R.string.settings_steam_cloud_save_mode_cloud_title)
    }
}



@Composable
internal fun SteamCloudAccountCard(
    loggedIn: Boolean,
    accountName: String,
    avatarUrl: String,
    busy: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SteamCloudAvatarImage(
            loggedIn = loggedIn,
            avatarUrl = avatarUrl,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = if (loggedIn) {
                    accountName
                } else {
                    stringResource(R.string.settings_steam_cloud_account_not_signed_in)
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (loggedIn) {
                    stringResource(R.string.settings_steam_cloud_account_signed_in)
                } else {
                    stringResource(R.string.settings_steam_cloud_account_login_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (loggedIn) {
            HapticTextButton(
                enabled = !busy,
                onClick = onLogout
            ) {
                Text(stringResource(R.string.settings_steam_cloud_logout_action))
            }
        } else {
            Button(
                enabled = !busy,
                onClick = onLogin
            ) {
                Text(stringResource(R.string.settings_steam_cloud_login_action))
            }
        }
    }
}



@Composable
internal fun SteamCloudAvatarImage(
    loggedIn: Boolean,
    avatarUrl: String,
) {
    val context = LocalContext.current.applicationContext
    val avatarBitmap by produceState<Bitmap?>(initialValue = null, loggedIn, avatarUrl) {
        value = if (loggedIn && avatarUrl.isNotBlank()) {
            withContext(Dispatchers.IO) {
                SteamCloudAvatarCacheStore.load(context, avatarUrl)
            }
        } else {
            null
        }
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (loggedIn && avatarBitmap != null) {
            Image(
                bitmap = requireNotNull(avatarBitmap).asImageBitmap(),
                contentDescription = stringResource(R.string.settings_steam_cloud_account_avatar_content_desc),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_account_circle),
                contentDescription = stringResource(R.string.settings_steam_cloud_account_default_icon_content_desc),
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}



@Composable
internal fun SteamCloudUploadCandidateCard(candidate: SteamCloudUploadCandidate) {
    SteamCloudPlanRowCard(
        title = candidate.localRelativePath,
        subtitle = stringResource(
            when (candidate.kind) {
                SteamCloudUploadCandidateKind.NEW_FILE ->
                    R.string.settings_steam_cloud_upload_candidate_new
                SteamCloudUploadCandidateKind.MODIFIED_FILE ->
                    R.string.settings_steam_cloud_upload_candidate_modified
            },
            formatSteamCloudBytes(candidate.fileSize)
        )
    )
}



@Composable
internal fun SteamCloudConflictCard(conflict: SteamCloudConflict) {
    SteamCloudPlanRowCard(
        title = conflict.localRelativePath,
        subtitle = stringResource(
            when (conflict.kind) {
                SteamCloudConflictKind.BASELINE_REQUIRED ->
                    R.string.settings_steam_cloud_conflict_baseline_required
                SteamCloudConflictKind.BOTH_CHANGED ->
                    R.string.settings_steam_cloud_conflict_both_changed
            }
        )
    )
}



@Composable
internal fun SteamCloudRemoteOnlyChangeCard(change: SteamCloudRemoteOnlyChange) {
    SteamCloudPlanRowCard(
        title = change.localRelativePath,
        subtitle = stringResource(
            when (change.kind) {
                SteamCloudRemoteOnlyChangeKind.NEW_REMOTE_FILE ->
                    R.string.settings_steam_cloud_remote_only_new
                SteamCloudRemoteOnlyChangeKind.MODIFIED_REMOTE_FILE ->
                    R.string.settings_steam_cloud_remote_only_modified
                SteamCloudRemoteOnlyChangeKind.REMOTE_FILE_DELETED ->
                    R.string.settings_steam_cloud_remote_only_deleted
            }
        )
    )
}



@Composable
internal fun SteamCloudPlanRowCard(
    title: String,
    subtitle: String,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SelectionContainer {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}



@Composable
internal fun SteamCloudManifestEntryCard(entry: SteamCloudManifestEntry) {
    val timestampText = remember(entry.timestamp) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(entry.timestamp))
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SelectionContainer {
                Text(
                    text = entry.remotePath,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = entry.localRelativePath,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = stringResource(
                    R.string.settings_steam_cloud_manifest_entry_meta,
                    entry.rawSize,
                    timestampText,
                    entry.persistState
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}



internal fun formatSteamCloudBytes(bytes: Long): String {
    val kib = 1024.0
    val mib = kib * 1024.0
    return when {
        bytes >= mib -> String.format(Locale.US, "%.1f MiB", bytes / mib)
        bytes >= kib -> String.format(Locale.US, "%.1f KiB", bytes / kib)
        else -> "$bytes B"
    }
}

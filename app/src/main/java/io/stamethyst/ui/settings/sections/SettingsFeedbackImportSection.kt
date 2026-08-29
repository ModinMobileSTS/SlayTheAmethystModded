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

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R


internal const val WORKSHOP_DOWNLOADER_PACKAGE_NAME = "top.apricityx.workshop"


@Composable
internal fun SettingsFeedbackEntryCard(
    busy: Boolean,
    onOpenFeedback: () -> Unit,
    onOpenFeedbackSubscriptions: () -> Unit,
    onOpenFeedbackIssueBrowser: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings_feedback),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.settings_feedback_entry_title),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                text = stringResource(R.string.settings_feedback_entry_desc),
                style = MaterialTheme.typography.bodySmall
            )
            SettingsActionListItem(
                title = stringResource(R.string.settings_feedback_entry_new),
                enabled = !busy,
                onClick = onOpenFeedback
            )
            SettingsActionListItem(
                title = stringResource(R.string.settings_feedback_entry_subscriptions),
                enabled = !busy,
                onClick = onOpenFeedbackSubscriptions
            )
            SettingsActionListItem(
                title = stringResource(R.string.settings_feedback_entry_issue_browser),
                enabled = !busy,
                onClick = onOpenFeedbackIssueBrowser
            )
        }
    }
}


internal enum class SettingsResourceOperationGroup {
    MODS,
    SAVES,
    LOGS,
}


@Composable
internal fun SettingsImportSection(
    busy: Boolean,
    onImportJar: () -> Unit,
    onImportMods: () -> Unit,
    onExportMods: () -> Unit,
    onImportSaves: () -> Unit,
    onExportSaves: () -> Unit,
    onExportLogs: () -> Unit,
    onExportLogsToFile: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val getNewModsProjectUrl = stringResource(R.string.main_get_new_mods_dialog_url)
    var showGetNewModsDialog by rememberSaveable { mutableStateOf(false) }
    var visibleOperationGroup by rememberSaveable {
        mutableStateOf<SettingsResourceOperationGroup?>(null)
    }

    val openGetNewMods = {
        if (!openWorkshopDownloader(context)) {
            showGetNewModsDialog = true
        }
    }

    GetNewModsDialog(
        visible = showGetNewModsDialog,
        onDismiss = { showGetNewModsDialog = false },
        onOpenProject = {
            showGetNewModsDialog = false
            uriHandler.openUri(getNewModsProjectUrl)
        }
    )
    SettingsResourceOperationDialog(
        group = visibleOperationGroup,
        busy = busy,
        onDismiss = { visibleOperationGroup = null },
        onGetNewMods = openGetNewMods,
        onImportMods = onImportMods,
        onExportMods = onExportMods,
        onImportSaves = onImportSaves,
        onExportSaves = onExportSaves,
        onExportLogs = onExportLogs,
        onExportLogsToFile = onExportLogsToFile,
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsActionListItem(
            title = stringResource(R.string.settings_mod_operations),
            supportingText = stringResource(R.string.settings_mod_operations_desc),
            enabled = !busy,
            onClick = { visibleOperationGroup = SettingsResourceOperationGroup.MODS }
        )
        SettingsActionListItem(
            title = stringResource(R.string.settings_save_operations),
            supportingText = stringResource(R.string.settings_save_operations_desc),
            enabled = !busy,
            onClick = { visibleOperationGroup = SettingsResourceOperationGroup.SAVES }
        )
        SettingsActionListItem(
            title = stringResource(R.string.settings_log_operations),
            supportingText = stringResource(R.string.settings_log_operations_desc),
            enabled = !busy,
            onClick = { visibleOperationGroup = SettingsResourceOperationGroup.LOGS }
        )
        SettingsActionListItem(
            title = stringResource(R.string.settings_reimport_sts_jar_title),
            supportingText = stringResource(R.string.settings_reimport_sts_jar_desc),
            enabled = !busy,
            onClick = onImportJar
        )
    }
}


@Composable
internal fun SettingsResourceOperationDialog(
    group: SettingsResourceOperationGroup?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onGetNewMods: () -> Unit,
    onImportMods: () -> Unit,
    onExportMods: () -> Unit,
    onImportSaves: () -> Unit,
    onExportSaves: () -> Unit,
    onExportLogs: () -> Unit,
    onExportLogsToFile: () -> Unit,
) {
    val visibleGroup = group ?: return
    val titleRes = when (visibleGroup) {
        SettingsResourceOperationGroup.MODS -> R.string.settings_mod_operations
        SettingsResourceOperationGroup.SAVES -> R.string.settings_save_operations
        SettingsResourceOperationGroup.LOGS -> R.string.settings_log_operations
    }

    fun runOperation(operation: () -> Unit) {
        onDismiss()
        operation()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (visibleGroup) {
                    SettingsResourceOperationGroup.MODS -> {
                        SettingsActionListItem(
                            title = stringResource(R.string.main_get_new_mods),
                            enabled = !busy,
                            onClick = { runOperation(onGetNewMods) }
                        )
                        SettingsActionListItem(
                            title = stringResource(R.string.main_import_mods),
                            enabled = !busy,
                            onClick = { runOperation(onImportMods) }
                        )
                        SettingsActionListItem(
                            title = stringResource(R.string.settings_export_all_mods),
                            enabled = !busy,
                            onClick = { runOperation(onExportMods) }
                        )
                    }

                    SettingsResourceOperationGroup.SAVES -> {
                        SettingsActionListItem(
                            title = stringResource(R.string.settings_import_saves),
                            enabled = !busy,
                            onClick = { runOperation(onImportSaves) }
                        )
                        SettingsActionListItem(
                            title = stringResource(R.string.settings_export_saves),
                            enabled = !busy,
                            onClick = { runOperation(onExportSaves) }
                        )
                    }

                    SettingsResourceOperationGroup.LOGS -> {
                        SettingsActionListItem(
                            title = stringResource(R.string.sts_share_crash_report),
                            enabled = !busy,
                            onClick = { runOperation(onExportLogs) }
                        )
                        SettingsActionListItem(
                            title = stringResource(R.string.settings_export_error_logs),
                            enabled = !busy,
                            onClick = { runOperation(onExportLogsToFile) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        }
    )
}


internal fun openWorkshopDownloader(context: Context): Boolean {
    if (!isPackageInstalled(context, WORKSHOP_DOWNLOADER_PACKAGE_NAME)) {
        return false
    }
    val launchIntent =
        context.packageManager.getLaunchIntentForPackage(WORKSHOP_DOWNLOADER_PACKAGE_NAME)
            ?: return false
    context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}


internal fun isPackageInstalled(context: Context, packageName: String): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}


@Composable
internal fun GetNewModsDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpenProject: () -> Unit,
) {
    if (!visible) {
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.main_get_new_mods_dialog_title)) },
        text = {
            SelectionContainer {
                Text(
                    text = stringResource(
                        R.string.main_get_new_mods_dialog_message,
                        stringResource(R.string.main_get_new_mods_dialog_url)
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenProject) {
                Text(text = stringResource(R.string.main_get_new_mods_dialog_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.main_get_new_mods_dialog_close))
            }
        }
    )
}



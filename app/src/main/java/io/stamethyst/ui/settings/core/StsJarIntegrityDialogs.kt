package io.stamethyst.ui.settings.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import io.stamethyst.ui.LauncherAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import kotlinx.coroutines.delay

private const val FORCE_IMPORT_COUNTDOWN_SECONDS = 10

@Composable
internal fun StsJarIntegrityDialogHost(
    dialogState: SettingsScreenViewModel.StsJarIntegrityDialogState,
    onDismiss: () -> Unit,
    onRequestForceImport: () -> Unit,
    onDismissForceConfirm: () -> Unit,
    onConfirmForceImport: () -> Unit,
) {
    var forceImportCountdownSeconds by remember(
        dialogState.displayName,
        dialogState.expectedSha1,
        dialogState.actualSha1
    ) {
        mutableStateOf(FORCE_IMPORT_COUNTDOWN_SECONDS)
    }
    LaunchedEffect(
        dialogState.displayName,
        dialogState.expectedSha1,
        dialogState.actualSha1
    ) {
        forceImportCountdownSeconds = FORCE_IMPORT_COUNTDOWN_SECONDS
        while (forceImportCountdownSeconds > 0) {
            delay(1_000L)
            forceImportCountdownSeconds -= 1
        }
    }
    val forceImportEnabled = forceImportCountdownSeconds <= 0

    if (dialogState.forceConfirmationVisible) {
        AlertDialog(
            onDismissRequest = onDismissForceConfirm,
            title = {
                Text(stringResource(R.string.sts_jar_integrity_force_confirm_title))
            },
            text = {
                Text(stringResource(R.string.sts_jar_integrity_force_confirm_message))
            },
            confirmButton = {
                TextButton(onClick = onConfirmForceImport) {
                    Text(stringResource(R.string.sts_jar_integrity_force_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissForceConfirm) {
                    Text(stringResource(R.string.sts_jar_integrity_force_confirm_back_action))
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.sts_jar_integrity_warning_title))
        },
        scrollableText = false,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.sts_jar_integrity_warning_message),
                    style = MaterialTheme.typography.bodyMedium
                )
                StsJarIntegrityValueBlock(
                    label = stringResource(R.string.sts_jar_integrity_file_label),
                    value = dialogState.displayName
                )
                StsJarIntegrityValueBlock(
                    label = stringResource(R.string.sts_jar_integrity_expected_sha1_label),
                    value = dialogState.expectedSha1
                )
                StsJarIntegrityValueBlock(
                    label = stringResource(R.string.sts_jar_integrity_actual_sha1_label),
                    value = dialogState.actualSha1
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onRequestForceImport,
                enabled = forceImportEnabled
            ) {
                Text(
                    if (forceImportEnabled) {
                        stringResource(R.string.sts_jar_integrity_force_action)
                    } else {
                        stringResource(
                            R.string.sts_jar_integrity_force_action_countdown,
                            forceImportCountdownSeconds
                        )
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.sts_jar_integrity_cancel_action))
            }
        }
    )
}

@Composable
private fun StsJarIntegrityValueBlock(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SelectionContainer {
            Text(
                text = value,
                modifier = Modifier.padding(start = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

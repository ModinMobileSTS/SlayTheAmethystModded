package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import io.stamethyst.ui.LauncherAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.model.ModItemUi

@Composable
internal fun ImportPatchUpdateLaunchWarningDialog(
    mods: List<ModItemUi>,
    showModFileName: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    onDontShowAgain: () -> Unit,
) {
    AlertDialog(
        scrollableText = false,
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(R.string.main_launch_import_patch_update_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.main_launch_import_patch_update_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                mods.forEach { mod ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            ModCardBodyContent(
                                mod = mod,
                                isExpanded = false,
                                showModFileName = showModFileName,
                                importPatchBadgeEnabled = false,
                                headerTrailing = {},
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = stringResource(R.string.main_launch_import_patch_update_continue))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDontShowAgain,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(text = stringResource(R.string.main_launch_import_patch_update_dont_show_again))
            }
        },
    )
}

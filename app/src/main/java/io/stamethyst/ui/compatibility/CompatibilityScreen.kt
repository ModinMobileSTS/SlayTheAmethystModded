package io.stamethyst.ui.compatibility

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.stamethyst.R
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.ArrowBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherCompatibilityScreen(
    modifier: Modifier = Modifier,
) {
    val activity = LocalActivity.current!!
    val navigator = currentNavigator
    val viewModel: CompatibilityScreenViewModel = viewModel()
    val uiState = viewModel.uiState

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compat_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = navigator::goBack) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = "返回"
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                uiState.busyMessage?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }
            }

            CompatibilitySwitchRow(
                title = stringResource(R.string.compat_original_fbo_title),
                description = stringResource(R.string.compat_original_fbo_desc),
                checked = uiState.originalFboPatchEnabled,
                enabled = !uiState.busy,
                onCheckedChange = { enabled -> viewModel.onOriginalFboPatchToggled(activity, enabled) }
            )

            CompatibilitySwitchRow(
                title = stringResource(R.string.compat_downfall_fbo_title),
                description = stringResource(R.string.compat_downfall_fbo_desc),
                checked = uiState.downfallFboPatchEnabled,
                enabled = !uiState.busy,
                onCheckedChange = { enabled -> viewModel.onDownfallFboPatchToggled(activity, enabled) }
            )

            CompatibilitySwitchRow(
                title = stringResource(R.string.compat_virtual_fbo_poc_title),
                description = stringResource(R.string.compat_virtual_fbo_poc_desc),
                checked = uiState.virtualFboPocEnabled,
                enabled = !uiState.busy,
                onCheckedChange = { enabled -> viewModel.onVirtualFboPocToggled(activity, enabled) }
            )

            CompatibilitySwitchRow(
                title = stringResource(R.string.compat_global_atlas_filter_compat_title),
                description = stringResource(R.string.compat_global_atlas_filter_compat_desc),
                checked = uiState.globalAtlasFilterCompatEnabled,
                enabled = !uiState.busy,
                onCheckedChange = { enabled -> viewModel.onGlobalAtlasFilterCompatToggled(activity, enabled) }
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refresh(activity)
    }
}

@Composable
private fun CompatibilitySwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = onCheckedChange
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

package io.stamethyst.ui.compatibility

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
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
    val context = LocalContext.current
    val navigator = currentNavigator
    val viewModel: CompatibilityScreenViewModel = viewModel()
    val uiState = viewModel.uiState

    LaunchedEffect(Unit) {
        viewModel.refresh(context)
    }

    LauncherCompatibilityScreenContent(
        modifier = modifier,
        uiState = uiState,
        onGoBack = navigator::goBack,
        onVirtualFboPocToggled = { enabled -> viewModel.onVirtualFboPocToggled(context, enabled) },
        onGlobalAtlasFilterCompatToggled = { enabled -> viewModel.onGlobalAtlasFilterCompatToggled(context, enabled) },
        onModManifestRootCompatToggled = { enabled -> viewModel.onModManifestRootCompatToggled(context, enabled) },
        onFrierenModCompatToggled = { enabled -> viewModel.onFrierenModCompatToggled(context, enabled) },
        onDownfallImportCompatToggled = { enabled -> viewModel.onDownfallImportCompatToggled(context, enabled) },
        onVupShionModCompatToggled = { enabled -> viewModel.onVupShionModCompatToggled(context, enabled) },
        onFragmentShaderPrecisionCompatToggled = { enabled ->
            viewModel.onFragmentShaderPrecisionCompatToggled(context, enabled)
        },
        onRuntimeTextureCompatToggled = { enabled -> viewModel.onRuntimeTextureCompatToggled(context, enabled) },
        onForceLinearMipmapFilterToggled = { enabled -> viewModel.onForceLinearMipmapFilterToggled(context, enabled) },
        onNonRenderableFboFormatCompatToggled = { enabled ->
            viewModel.onNonRenderableFboFormatCompatToggled(context, enabled)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun LauncherCompatibilityScreenPreview() {
    LauncherCompatibilityScreenContent(
        uiState = CompatibilityScreenViewModel.UiState(
            busy = false,
            virtualFboPocEnabled = false,
            globalAtlasFilterCompatEnabled = true,
            modManifestRootCompatEnabled = true,
            frierenModCompatEnabled = true,
            downfallImportCompatEnabled = true,
            vupShionModCompatEnabled = true,
            fragmentShaderPrecisionCompatEnabled = true,
            runtimeTextureCompatEnabled = false,
            forceLinearMipmapFilterEnabled = true,
            nonRenderableFboFormatCompatEnabled = true
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherCompatibilityScreenContent(
    modifier: Modifier = Modifier,
    uiState: CompatibilityScreenViewModel.UiState,
    onGoBack: () -> Unit = {},
    onVirtualFboPocToggled: (Boolean) -> Unit = {},
    onGlobalAtlasFilterCompatToggled: (Boolean) -> Unit = {},
    onModManifestRootCompatToggled: (Boolean) -> Unit = {},
    onFrierenModCompatToggled: (Boolean) -> Unit = {},
    onDownfallImportCompatToggled: (Boolean) -> Unit = {},
    onVupShionModCompatToggled: (Boolean) -> Unit = {},
    onFragmentShaderPrecisionCompatToggled: (Boolean) -> Unit = {},
    onRuntimeTextureCompatToggled: (Boolean) -> Unit = {},
    onForceLinearMipmapFilterToggled: (Boolean) -> Unit = {},
    onNonRenderableFboFormatCompatToggled: (Boolean) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compat_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onGoBack) {
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
                title = stringResource(R.string.compat_virtual_fbo_poc_title),
                description = stringResource(R.string.compat_virtual_fbo_poc_desc),
                checked = uiState.virtualFboPocEnabled,
                enabled = !uiState.busy,
                onCheckedChange = onVirtualFboPocToggled
            )

            CompatibilitySwitchRow(
                title = stringResource(R.string.compat_global_atlas_filter_compat_title),
                description = stringResource(R.string.compat_global_atlas_filter_compat_desc),
                checked = uiState.globalAtlasFilterCompatEnabled,
                enabled = !uiState.busy,
                onCheckedChange = onGlobalAtlasFilterCompatToggled
            )

            CompatibilitySwitchRow(
                title = stringResource(R.string.compat_mod_manifest_root_compat_title),
                description = stringResource(R.string.compat_mod_manifest_root_compat_desc),
                checked = uiState.modManifestRootCompatEnabled,
                enabled = !uiState.busy,
                onCheckedChange = onModManifestRootCompatToggled
            )

            CompatibilitySwitchRow(
                title = stringResource(R.string.compat_frieren_mod_compat_title),
                description = stringResource(R.string.compat_frieren_mod_compat_desc),
                checked = uiState.frierenModCompatEnabled,
                enabled = !uiState.busy,
                onCheckedChange = onFrierenModCompatToggled
            )

            CompatibilitySwitchRow(
                title = stringResource(R.string.compat_downfall_import_compat_title),
                description = stringResource(R.string.compat_downfall_import_compat_desc),
                checked = uiState.downfallImportCompatEnabled,
                enabled = !uiState.busy,
                onCheckedChange = onDownfallImportCompatToggled
            )

            CompatibilitySwitchRow(
                title = stringResource(R.string.compat_vupshion_mod_compat_title),
                description = stringResource(R.string.compat_vupshion_mod_compat_desc),
                checked = uiState.vupShionModCompatEnabled,
                enabled = !uiState.busy,
                onCheckedChange = onVupShionModCompatToggled
            )

            CompatibilitySwitchRow(
                title = stringResource(R.string.compat_fragment_shader_precision_compat_title),
                description = stringResource(R.string.compat_fragment_shader_precision_compat_desc),
                checked = uiState.fragmentShaderPrecisionCompatEnabled,
                enabled = !uiState.busy,
                onCheckedChange = onFragmentShaderPrecisionCompatToggled
            )

            CompatibilitySwitchRow(
                title = stringResource(R.string.compat_runtime_texture_compat_title),
                description = stringResource(R.string.compat_runtime_texture_compat_desc),
                checked = uiState.runtimeTextureCompatEnabled,
                enabled = !uiState.busy,
                onCheckedChange = onRuntimeTextureCompatToggled
            )

            CompatibilitySwitchRow(
                title = stringResource(R.string.compat_force_linear_mipmap_filter_title),
                description = stringResource(R.string.compat_force_linear_mipmap_filter_desc),
                checked = uiState.forceLinearMipmapFilterEnabled,
                enabled = !uiState.busy,
                onCheckedChange = onForceLinearMipmapFilterToggled
            )

            CompatibilitySwitchRow(
                title = stringResource(R.string.compat_non_renderable_fbo_format_compat_title),
                description = stringResource(R.string.compat_non_renderable_fbo_format_compat_desc),
                checked = uiState.nonRenderableFboFormatCompatEnabled,
                enabled = !uiState.busy,
                onCheckedChange = onNonRenderableFboFormatCompatToggled
            )
        }
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

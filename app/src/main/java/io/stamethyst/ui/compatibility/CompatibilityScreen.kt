package io.stamethyst.ui.compatibility

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import io.stamethyst.ui.settings.SettingsDropdownField

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
        onImportAtlasDownscaleCompatToggled = { enabled ->
            viewModel.onImportAtlasDownscaleCompatToggled(context, enabled)
        },
        onModManifestRootCompatToggled = { enabled -> viewModel.onModManifestRootCompatToggled(context, enabled) },
        onFrierenModCompatToggled = { enabled -> viewModel.onFrierenModCompatToggled(context, enabled) },
        onDownfallImportCompatToggled = { enabled -> viewModel.onDownfallImportCompatToggled(context, enabled) },
        onVupShionModCompatToggled = { enabled -> viewModel.onVupShionModCompatToggled(context, enabled) },
        onFragmentShaderPrecisionCompatToggled = { enabled ->
            viewModel.onFragmentShaderPrecisionCompatToggled(context, enabled)
        },
        onRuntimeTextureCompatToggled = { enabled -> viewModel.onRuntimeTextureCompatToggled(context, enabled) },
        onLargeTextureDownscaleCompatToggled = { enabled ->
            viewModel.onLargeTextureDownscaleCompatToggled(context, enabled)
        },
        onTexturePressureDownscaleDivisorChanged = { divisor ->
            viewModel.onTexturePressureDownscaleDivisorChanged(context, divisor)
        },
        onForceLinearMipmapFilterToggled = { enabled -> viewModel.onForceLinearMipmapFilterToggled(context, enabled) },
        onNonRenderableFboFormatCompatToggled = { enabled ->
            viewModel.onNonRenderableFboFormatCompatToggled(context, enabled)
        },
        onFboIdleReclaimCompatToggled = { enabled ->
            viewModel.onFboIdleReclaimCompatToggled(context, enabled)
        },
        onFboPressureDownscaleCompatToggled = { enabled ->
            viewModel.onFboPressureDownscaleCompatToggled(context, enabled)
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
            importAtlasDownscaleCompatEnabled = false,
            modManifestRootCompatEnabled = true,
            frierenModCompatEnabled = true,
            downfallImportCompatEnabled = true,
            vupShionModCompatEnabled = true,
            fragmentShaderPrecisionCompatEnabled = true,
            runtimeTextureCompatEnabled = false,
            largeTextureDownscaleCompatEnabled = true,
            texturePressureDownscaleDivisor = 2,
            forceLinearMipmapFilterEnabled = true,
            nonRenderableFboFormatCompatEnabled = true,
            fboIdleReclaimCompatEnabled = true,
            fboPressureDownscaleCompatEnabled = true
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
    onImportAtlasDownscaleCompatToggled: (Boolean) -> Unit = {},
    onModManifestRootCompatToggled: (Boolean) -> Unit = {},
    onFrierenModCompatToggled: (Boolean) -> Unit = {},
    onDownfallImportCompatToggled: (Boolean) -> Unit = {},
    onVupShionModCompatToggled: (Boolean) -> Unit = {},
    onFragmentShaderPrecisionCompatToggled: (Boolean) -> Unit = {},
    onRuntimeTextureCompatToggled: (Boolean) -> Unit = {},
    onLargeTextureDownscaleCompatToggled: (Boolean) -> Unit = {},
    onTexturePressureDownscaleDivisorChanged: (Int) -> Unit = {},
    onForceLinearMipmapFilterToggled: (Boolean) -> Unit = {},
    onNonRenderableFboFormatCompatToggled: (Boolean) -> Unit = {},
    onFboIdleReclaimCompatToggled: (Boolean) -> Unit = {},
    onFboPressureDownscaleCompatToggled: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current

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

            CompatibilitySectionCard(
                title = stringResource(R.string.compat_runtime_section_title),
                description = stringResource(R.string.compat_runtime_section_desc)
            ) {
                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_virtual_fbo_poc_title),
                    description = stringResource(R.string.compat_virtual_fbo_poc_desc),
                    checked = uiState.virtualFboPocEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onVirtualFboPocToggled
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
                    title = stringResource(R.string.compat_large_texture_downscale_title),
                    description = stringResource(R.string.compat_large_texture_downscale_desc),
                    checked = uiState.largeTextureDownscaleCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onLargeTextureDownscaleCompatToggled
                )

                SettingsDropdownField(
                    label = stringResource(R.string.compat_texture_pressure_downscale_divisor_title),
                    valueText = texturePressureDownscaleDivisorLabel(
                        context,
                        uiState.texturePressureDownscaleDivisor
                    ),
                    enabled = !uiState.busy,
                    supportingText = stringResource(
                        R.string.compat_texture_pressure_downscale_divisor_desc
                    ),
                    options = listOf(2, 3, 4),
                    optionLabel = { divisor ->
                        texturePressureDownscaleDivisorLabel(context, divisor)
                    },
                    optionDescription = { divisor ->
                        texturePressureDownscaleDivisorOptionDescription(context, divisor)
                    },
                    onOptionSelected = onTexturePressureDownscaleDivisorChanged
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

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_fbo_idle_reclaim_title),
                    description = stringResource(R.string.compat_fbo_idle_reclaim_desc),
                    checked = uiState.fboIdleReclaimCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onFboIdleReclaimCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_fbo_pressure_downscale_title),
                    description = stringResource(R.string.compat_fbo_pressure_downscale_desc),
                    checked = uiState.fboPressureDownscaleCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onFboPressureDownscaleCompatToggled
                )
            }

            CompatibilitySectionCard(
                title = stringResource(R.string.compat_import_patch_section_title),
                description = stringResource(R.string.compat_import_patch_section_desc)
            ) {
                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_global_atlas_filter_compat_title),
                    description = stringResource(R.string.compat_global_atlas_filter_compat_desc),
                    checked = uiState.globalAtlasFilterCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onGlobalAtlasFilterCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_import_atlas_downscale_compat_title),
                    description = stringResource(R.string.compat_import_atlas_downscale_compat_desc),
                    checked = uiState.importAtlasDownscaleCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onImportAtlasDownscaleCompatToggled
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
            }
        }
    }
}

@Composable
private fun CompatibilitySectionCard(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = description, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
            }
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

private fun texturePressureDownscaleDivisorLabel(context: Context, divisor: Int): String {
    return context.getString(R.string.compat_texture_pressure_downscale_divisor_value, divisor)
}

private fun texturePressureDownscaleDivisorOptionDescription(
    context: Context,
    divisor: Int
): String {
    val scaledSize = (2048 + divisor - 1) / divisor
    return context.getString(
        R.string.compat_texture_pressure_downscale_divisor_option_desc,
        scaledSize,
        scaledSize
    )
}

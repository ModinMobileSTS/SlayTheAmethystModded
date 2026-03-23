package io.stamethyst.ui.settings

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.render.MobileGluesAngleDepthClearFixMode
import io.stamethyst.backend.render.MobileGluesAnglePolicy
import io.stamethyst.backend.render.MobileGluesCustomGlVersion
import io.stamethyst.backend.render.MobileGluesFsr1QualityPreset
import io.stamethyst.backend.render.MobileGluesGlslCacheSizePreset
import io.stamethyst.backend.render.MobileGluesMultidrawMode
import io.stamethyst.backend.render.MobileGluesNoErrorPolicy
import io.stamethyst.backend.render.MobileGluesPreset
import io.stamethyst.backend.render.MobileGluesSettings
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.ArrowBack
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherMobileGluesSettingsScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState
    var showWarningDialog by rememberSaveable { mutableStateOf(true) }
    var showCustomSettings by rememberSaveable { mutableStateOf(false) }
    val currentSettings = currentMobileGluesSettings(uiState)
    val exactPreset = MobileGluesPreset.entries.firstOrNull { it.settings == currentSettings }
    val nearestPreset = exactPreset ?: MobileGluesPreset.closestTo(currentSettings)
    var presetSliderValue by rememberSaveable { mutableFloatStateOf(nearestPreset.ordinal.toFloat()) }
    val selectedPreset = MobileGluesPreset.fromSliderIndex(presetSliderValue.roundToInt())

    LaunchedEffect(Unit) {
        viewModel.bind(activity)
    }

    LaunchedEffect(
        uiState.mobileGluesAnglePolicy,
        uiState.mobileGluesNoErrorPolicy,
        uiState.mobileGluesMultidrawMode,
        uiState.mobileGluesExtComputeShaderEnabled,
        uiState.mobileGluesExtTimerQueryEnabled,
        uiState.mobileGluesExtDirectStateAccessEnabled,
        uiState.mobileGluesGlslCacheSizePreset,
        uiState.mobileGluesAngleDepthClearFixMode,
        uiState.mobileGluesCustomGlVersion,
        uiState.mobileGluesFsr1QualityPreset
    ) {
        presetSliderValue = nearestPreset.ordinal.toFloat()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_mobileglues_entry_title)) },
                navigationIcon = {
                    IconButton(onClick = navigator::goBack) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back)
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
            SettingsBusyIndicator(uiState = uiState)

            SettingsSectionCard(title = stringResource(R.string.mobileglues_section_info_title)) {
                Text(
                    text = stringResource(R.string.mobileglues_info_body),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            SettingsSectionCard(title = stringResource(R.string.mobileglues_section_preset_title)) {
                Text(
                    text = if (exactPreset != null) {
                        stringResource(
                            R.string.mobileglues_preset_current_exact,
                            exactPreset.title(activity)
                        )
                    } else {
                        stringResource(
                            R.string.mobileglues_preset_current_custom,
                            nearestPreset.title(activity)
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.mobileglues_preset_intro),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.mobileglues_preset_slider_left),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.mobileglues_preset_slider_right),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Slider(
                    value = presetSliderValue,
                    onValueChange = { presetSliderValue = it },
                    onValueChangeFinished = {
                        viewModel.onApplyMobileGluesPreset(
                            activity,
                            MobileGluesPreset.fromSliderIndex(presetSliderValue.roundToInt())
                        )
                    },
                    valueRange = 0f..MobileGluesPreset.entries.lastIndex.toFloat(),
                    steps = (MobileGluesPreset.entries.size - 2).coerceAtLeast(0),
                    enabled = !uiState.busy,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(
                        R.string.mobileglues_preset_slider_position,
                        MobileGluesPreset.fromSliderIndex(
                            presetSliderValue.roundToInt()
                        ).title(activity)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "${selectedPreset.shortLabel(activity)} | ${selectedPreset.title(activity)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    text = selectedPreset.description(activity),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            SettingsSectionCard(title = stringResource(R.string.mobileglues_section_current_params_title)) {
                currentMobileGluesParameterLines(activity, currentSettings).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                SwitchSettingRow(
                    checked = showCustomSettings,
                    enabled = !uiState.busy,
                    enabledText = stringResource(R.string.mobileglues_custom_settings_enabled),
                    disabledText = stringResource(R.string.mobileglues_custom_settings_disabled),
                    description = stringResource(R.string.mobileglues_custom_settings_desc),
                    onCheckedChange = { showCustomSettings = it }
                )
                TextButton(
                    onClick = { viewModel.onResetMobileGluesSettings(activity) },
                    enabled = !uiState.busy
                ) {
                    Text(stringResource(R.string.mobileglues_reset_defaults))
                }
            }

            if (showCustomSettings) {
                SettingsSectionCard(title = stringResource(R.string.mobileglues_section_driver_title)) {
                    SettingsDropdownField(
                        label = stringResource(R.string.mobileglues_field_angle_policy_label),
                        valueText = uiState.mobileGluesAnglePolicy.displayName(activity),
                        enabled = !uiState.busy,
                        supportingText = stringResource(
                            R.string.mobileglues_field_angle_policy_supporting
                        ),
                        options = MobileGluesAnglePolicy.entries,
                        optionLabel = { it.displayName(activity) },
                        optionDescription = { it.description(activity) },
                        onOptionSelected = { viewModel.onMobileGluesAnglePolicyChanged(activity, it) }
                    )
                    SettingsDropdownField(
                        label = stringResource(R.string.mobileglues_field_multidraw_label),
                        valueText = uiState.mobileGluesMultidrawMode.displayName(activity),
                        enabled = !uiState.busy,
                        supportingText = stringResource(
                            R.string.mobileglues_field_multidraw_supporting
                        ),
                        options = MobileGluesMultidrawMode.entries,
                        optionLabel = { it.displayName(activity) },
                        optionDescription = { it.description(activity) },
                        onOptionSelected = { viewModel.onMobileGluesMultidrawModeChanged(activity, it) }
                    )
                    SettingsDropdownField(
                        label = stringResource(R.string.mobileglues_field_no_error_label),
                        valueText = uiState.mobileGluesNoErrorPolicy.displayName(activity),
                        enabled = !uiState.busy,
                        supportingText = stringResource(
                            R.string.mobileglues_field_no_error_supporting
                        ),
                        options = MobileGluesNoErrorPolicy.entries,
                        optionLabel = { it.displayName(activity) },
                        optionDescription = { it.description(activity) },
                        onOptionSelected = { viewModel.onMobileGluesNoErrorPolicyChanged(activity, it) }
                    )
                    SettingsDropdownField(
                        label = stringResource(R.string.mobileglues_field_custom_gl_label),
                        valueText = uiState.mobileGluesCustomGlVersion.displayName(activity),
                        enabled = !uiState.busy,
                        supportingText = stringResource(
                            R.string.mobileglues_field_custom_gl_supporting
                        ),
                        options = MobileGluesCustomGlVersion.entries,
                        optionLabel = { it.displayName(activity) },
                        optionDescription = { it.description(activity) },
                        onOptionSelected = { viewModel.onMobileGluesCustomGlVersionChanged(activity, it) }
                    )
                }

                SettingsSectionCard(title = stringResource(R.string.mobileglues_section_extension_title)) {
                    SwitchSettingRow(
                        checked = uiState.mobileGluesExtComputeShaderEnabled,
                        enabled = !uiState.busy,
                        enabledText = stringResource(R.string.mobileglues_compute_shader_enabled),
                        disabledText = stringResource(R.string.mobileglues_compute_shader_disabled),
                        description = stringResource(R.string.mobileglues_compute_shader_desc),
                        onCheckedChange = {
                            viewModel.onMobileGluesExtComputeShaderChanged(activity, it)
                        }
                    )
                    SwitchSettingRow(
                        checked = uiState.mobileGluesExtTimerQueryEnabled,
                        enabled = !uiState.busy,
                        enabledText = stringResource(R.string.mobileglues_timer_query_enabled),
                        disabledText = stringResource(R.string.mobileglues_timer_query_disabled),
                        description = stringResource(R.string.mobileglues_timer_query_desc),
                        onCheckedChange = {
                            viewModel.onMobileGluesExtTimerQueryChanged(activity, it)
                        }
                    )
                    SwitchSettingRow(
                        checked = uiState.mobileGluesExtDirectStateAccessEnabled,
                        enabled = !uiState.busy,
                        enabledText = stringResource(
                            R.string.mobileglues_direct_state_access_enabled
                        ),
                        disabledText = stringResource(
                            R.string.mobileglues_direct_state_access_disabled
                        ),
                        description = stringResource(R.string.mobileglues_direct_state_access_desc),
                        onCheckedChange = {
                            viewModel.onMobileGluesExtDirectStateAccessChanged(activity, it)
                        }
                    )
                    SettingsDropdownField(
                        label = stringResource(R.string.mobileglues_field_angle_depth_clear_label),
                        valueText = uiState.mobileGluesAngleDepthClearFixMode.displayName(activity),
                        enabled = !uiState.busy,
                        supportingText = stringResource(
                            R.string.mobileglues_field_angle_depth_clear_supporting
                        ),
                        options = MobileGluesAngleDepthClearFixMode.entries,
                        optionLabel = { it.displayName(activity) },
                        optionDescription = { it.description(activity) },
                        onOptionSelected = {
                            viewModel.onMobileGluesAngleDepthClearFixModeChanged(activity, it)
                        }
                    )
                }

                SettingsSectionCard(title = stringResource(R.string.mobileglues_section_cache_image_title)) {
                    SettingsDropdownField(
                        label = stringResource(R.string.mobileglues_field_glsl_cache_label),
                        valueText = uiState.mobileGluesGlslCacheSizePreset.displayName(activity),
                        enabled = !uiState.busy,
                        supportingText = stringResource(
                            R.string.mobileglues_field_glsl_cache_supporting
                        ),
                        options = MobileGluesGlslCacheSizePreset.entries,
                        optionLabel = { it.displayName(activity) },
                        optionDescription = { it.description(activity) },
                        onOptionSelected = {
                            viewModel.onMobileGluesGlslCacheSizePresetChanged(activity, it)
                        }
                    )
                    SettingsDropdownField(
                        label = stringResource(R.string.mobileglues_field_fsr1_label),
                        valueText = uiState.mobileGluesFsr1QualityPreset.displayName(activity),
                        enabled = !uiState.busy,
                        supportingText = stringResource(R.string.mobileglues_field_fsr1_supporting),
                        options = MobileGluesFsr1QualityPreset.entries,
                        optionLabel = { it.displayName(activity) },
                        optionDescription = { it.description(activity) },
                        onOptionSelected = {
                            viewModel.onMobileGluesFsr1QualityPresetChanged(activity, it)
                        }
                    )
                }
            }

            Text(
                text = stringResource(
                    R.string.mobileglues_effective_renderer,
                    uiState.effectiveRendererBackend.displayName
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.mobileglues_warning_title)) },
            text = {
                Text(
                    text = stringResource(R.string.mobileglues_warning_message),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { showWarningDialog = false }) {
                    Text(stringResource(R.string.common_action_acknowledge))
                }
            }
        )
    }
}

private fun currentMobileGluesSettings(
    uiState: SettingsScreenViewModel.UiState
): MobileGluesSettings {
    return MobileGluesSettings(
        anglePolicy = uiState.mobileGluesAnglePolicy,
        noErrorPolicy = uiState.mobileGluesNoErrorPolicy,
        multidrawMode = uiState.mobileGluesMultidrawMode,
        extComputeShaderEnabled = uiState.mobileGluesExtComputeShaderEnabled,
        extTimerQueryEnabled = uiState.mobileGluesExtTimerQueryEnabled,
        extDirectStateAccessEnabled = uiState.mobileGluesExtDirectStateAccessEnabled,
        glslCacheSizePreset = uiState.mobileGluesGlslCacheSizePreset,
        angleDepthClearFixMode = uiState.mobileGluesAngleDepthClearFixMode,
        customGlVersion = uiState.mobileGluesCustomGlVersion,
        fsr1QualityPreset = uiState.mobileGluesFsr1QualityPreset,
    )
}

private fun currentMobileGluesParameterLines(
    context: Context,
    settings: MobileGluesSettings
): List<String> {
    val enabledText = context.getString(R.string.settings_status_enabled)
    val disabledText = context.getString(R.string.settings_status_disabled)
    fun state(enabled: Boolean): String = if (enabled) enabledText else disabledText
    return listOf(
        context.getString(
            R.string.mobileglues_param_angle_policy,
            settings.anglePolicy.displayName(context)
        ),
        context.getString(
            R.string.mobileglues_param_multidraw_mode,
            settings.multidrawMode.displayName(context)
        ),
        context.getString(
            R.string.mobileglues_param_no_error_policy,
            settings.noErrorPolicy.displayName(context)
        ),
        context.getString(
            R.string.mobileglues_param_custom_gl_version,
            settings.customGlVersion.displayName(context)
        ),
        context.getString(
            R.string.mobileglues_param_compute_shader,
            state(settings.extComputeShaderEnabled)
        ),
        context.getString(
            R.string.mobileglues_param_timer_query,
            state(settings.extTimerQueryEnabled)
        ),
        context.getString(
            R.string.mobileglues_param_direct_state_access,
            state(settings.extDirectStateAccessEnabled)
        ),
        context.getString(
            R.string.mobileglues_param_angle_depth_clear,
            settings.angleDepthClearFixMode.displayName(context)
        ),
        context.getString(
            R.string.mobileglues_param_glsl_cache,
            settings.glslCacheSizePreset.displayName(context)
        ),
        context.getString(
            R.string.mobileglues_param_fsr1,
            settings.fsr1QualityPreset.displayName(context)
        ),
    )
}

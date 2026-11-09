package io.stamethyst.ui.compatibility

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import io.stamethyst.ui.LauncherSwitch as Switch
import androidx.compose.material3.Text
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
import io.stamethyst.backend.mods.RuntimeTextureAtlasDownscaleQuality
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.settings.common.SettingsDropdownField
import io.stamethyst.ui.settings.common.SettingsDetailLayout
import io.stamethyst.ui.settings.common.LocalSettingsActionGate
import io.stamethyst.ui.settings.common.settingsControlEnabled

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherCompatibilityScreen(
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    val navigator = if (embedded) null else currentNavigator
    val viewModel: CompatibilityScreenViewModel = viewModel()
    val uiState = viewModel.uiState

    LaunchedEffect(Unit) {
        viewModel.refresh(context)
    }

    LauncherCompatibilityScreenContent(
        modifier = modifier,
        embedded = embedded,
        uiState = uiState,
        onGoBack = { navigator?.goBack() },
        onGlobalAtlasFilterCompatToggled = { enabled -> viewModel.onGlobalAtlasFilterCompatToggled(context, enabled) },
        onModManifestRootCompatToggled = { enabled -> viewModel.onModManifestRootCompatToggled(context, enabled) },
        onFrierenModCompatToggled = { enabled -> viewModel.onFrierenModCompatToggled(context, enabled) },
        onDownfallImportCompatToggled = { enabled -> viewModel.onDownfallImportCompatToggled(context, enabled) },
        onVupShionModCompatToggled = { enabled -> viewModel.onVupShionModCompatToggled(context, enabled) },
        onChaofanModCompatToggled = { enabled -> viewModel.onChaofanModCompatToggled(context, enabled) },
        onFragmentShaderPrecisionCompatToggled = { enabled ->
            viewModel.onFragmentShaderPrecisionCompatToggled(context, enabled)
        },
        onRuntimeTextureCompatToggled = { enabled -> viewModel.onRuntimeTextureCompatToggled(context, enabled) },
        onMainMenuPreviewReuseCompatToggled = { enabled ->
            viewModel.onMainMenuPreviewReuseCompatToggled(context, enabled)
        },
        onRoomContextHandLayoutRescueCompatToggled = { enabled ->
            viewModel.onRoomContextHandLayoutRescueCompatToggled(context, enabled)
        },
        onRoomTransitionRescueCompatToggled = { enabled ->
            viewModel.onRoomTransitionRescueCompatToggled(context, enabled)
        },
        onEventRoomRescueCompatToggled = { enabled ->
            viewModel.onEventRoomRescueCompatToggled(context, enabled)
        },
        onShopRoomRescueCompatToggled = { enabled ->
            viewModel.onShopRoomRescueCompatToggled(context, enabled)
        },
        onBaseModSaveLoadRescueCompatToggled = { enabled ->
            viewModel.onBaseModSaveLoadRescueCompatToggled(context, enabled)
        },
        onRelicEnterRoomRescueCompatToggled = { enabled ->
            viewModel.onRelicEnterRoomRescueCompatToggled(context, enabled)
        },
        onDungeonRenderRoomContextRescueCompatToggled = { enabled ->
            viewModel.onDungeonRenderRoomContextRescueCompatToggled(context, enabled)
        },
        onPowerIconRenderRescueCompatToggled = { enabled ->
            viewModel.onPowerIconRenderRescueCompatToggled(context, enabled)
        },
        onBaseModCustomMonsterRenderRescueCompatToggled = { enabled ->
            viewModel.onBaseModCustomMonsterRenderRescueCompatToggled(context, enabled)
        },
        onNonCombatPlayerRenderRescueCompatToggled = { enabled ->
            viewModel.onNonCombatPlayerRenderRescueCompatToggled(context, enabled)
        },
        onCardTooltipKeywordRescueCompatToggled = { enabled ->
            viewModel.onCardTooltipKeywordRescueCompatToggled(context, enabled)
        },
        onLargeTextureDownscaleCompatToggled = { enabled ->
            viewModel.onLargeTextureDownscaleCompatToggled(context, enabled)
        },
        onTextureResidencyManagerCompatToggled = { enabled ->
            viewModel.onTextureResidencyManagerCompatToggled(context, enabled)
        },
        onTexturePressureDownscaleDivisorChanged = { divisor ->
            viewModel.onTexturePressureDownscaleDivisorChanged(context, divisor)
        },
        onForceLinearMipmapFilterToggled = { enabled -> viewModel.onForceLinearMipmapFilterToggled(context, enabled) },
        onHinaCharacterRenderCompatToggled = { enabled ->
            viewModel.onHinaCharacterRenderCompatToggled(context, enabled)
        },
        onNonRenderableFboFormatCompatToggled = { enabled ->
            viewModel.onNonRenderableFboFormatCompatToggled(context, enabled)
        },
        onFboManagerCompatToggled = { enabled ->
            viewModel.onFboManagerCompatToggled(context, enabled)
        },
        onFboIdleReclaimCompatToggled = { enabled ->
            viewModel.onFboIdleReclaimCompatToggled(context, enabled)
        },
        onFboPressureDownscaleCompatToggled = { enabled ->
            viewModel.onFboPressureDownscaleCompatToggled(context, enabled)
        },
        onRuntimeDownscaleOrdinaryTexturesToggled = { enabled ->
            viewModel.onRuntimeDownscaleOrdinaryTexturesToggled(context, enabled)
        },
        onRuntimeDownscaleTextureAtlasPagesQualityChanged = { quality ->
            viewModel.onRuntimeDownscaleTextureAtlasPagesQualityChanged(context, quality)
        },
        onRuntimeDownscaleSpineTexturesToggled = { enabled ->
            viewModel.onRuntimeDownscaleSpineTexturesToggled(context, enabled)
        },
        onRuntimeDownscaleOffscreenFrameBuffersToggled = { enabled ->
            viewModel.onRuntimeDownscaleOffscreenFrameBuffersToggled(context, enabled)
        },
        onImportDownscaleSpineAtlasPagesToggled = { enabled ->
            viewModel.onImportDownscaleSpineAtlasPagesToggled(context, enabled)
        },
        onImportDownscaleOrdinaryAtlasPagesToggled = { enabled ->
            viewModel.onImportDownscaleOrdinaryAtlasPagesToggled(context, enabled)
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
            globalAtlasFilterCompatEnabled = true,
            modManifestRootCompatEnabled = true,
            frierenModCompatEnabled = true,
            downfallImportCompatEnabled = true,
            vupShionModCompatEnabled = true,
            chaofanModCompatEnabled = true,
            fragmentShaderPrecisionCompatEnabled = true,
            runtimeTextureCompatEnabled = false,
            mainMenuPreviewReuseCompatEnabled = true,
            nativeTouchscreenAllowlistCompatEnabled = true,
            largeTextureDownscaleCompatEnabled = true,
            textureResidencyManagerCompatEnabled = true,
            texturePressureDownscaleDivisor = 2,
            forceLinearMipmapFilterEnabled = true,
            hinaCharacterRenderCompatEnabled = true,
            nonRenderableFboFormatCompatEnabled = true,
            fboManagerCompatEnabled = true,
            fboIdleReclaimCompatEnabled = true,
            fboPressureDownscaleCompatEnabled = true
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherCompatibilityScreenContent(
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    uiState: CompatibilityScreenViewModel.UiState,
    onGoBack: () -> Unit = {},
    onGlobalAtlasFilterCompatToggled: (Boolean) -> Unit = {},
    onModManifestRootCompatToggled: (Boolean) -> Unit = {},
    onFrierenModCompatToggled: (Boolean) -> Unit = {},
    onDownfallImportCompatToggled: (Boolean) -> Unit = {},
    onVupShionModCompatToggled: (Boolean) -> Unit = {},
    onChaofanModCompatToggled: (Boolean) -> Unit = {},
    onFragmentShaderPrecisionCompatToggled: (Boolean) -> Unit = {},
    onRuntimeTextureCompatToggled: (Boolean) -> Unit = {},
    onMainMenuPreviewReuseCompatToggled: (Boolean) -> Unit = {},
    onRoomContextHandLayoutRescueCompatToggled: (Boolean) -> Unit = {},
    onRoomTransitionRescueCompatToggled: (Boolean) -> Unit = {},
    onEventRoomRescueCompatToggled: (Boolean) -> Unit = {},
    onShopRoomRescueCompatToggled: (Boolean) -> Unit = {},
    onBaseModSaveLoadRescueCompatToggled: (Boolean) -> Unit = {},
    onRelicEnterRoomRescueCompatToggled: (Boolean) -> Unit = {},
    onDungeonRenderRoomContextRescueCompatToggled: (Boolean) -> Unit = {},
    onPowerIconRenderRescueCompatToggled: (Boolean) -> Unit = {},
    onBaseModCustomMonsterRenderRescueCompatToggled: (Boolean) -> Unit = {},
    onNonCombatPlayerRenderRescueCompatToggled: (Boolean) -> Unit = {},
    onCardTooltipKeywordRescueCompatToggled: (Boolean) -> Unit = {},
    onLargeTextureDownscaleCompatToggled: (Boolean) -> Unit = {},
    onTextureResidencyManagerCompatToggled: (Boolean) -> Unit = {},
    onTexturePressureDownscaleDivisorChanged: (Int) -> Unit = {},
    onForceLinearMipmapFilterToggled: (Boolean) -> Unit = {},
    onHinaCharacterRenderCompatToggled: (Boolean) -> Unit = {},
    onNonRenderableFboFormatCompatToggled: (Boolean) -> Unit = {},
    onFboManagerCompatToggled: (Boolean) -> Unit = {},
    onFboIdleReclaimCompatToggled: (Boolean) -> Unit = {},
    onFboPressureDownscaleCompatToggled: (Boolean) -> Unit = {},
    onRuntimeDownscaleOrdinaryTexturesToggled: (Boolean) -> Unit = {},
    onRuntimeDownscaleTextureAtlasPagesQualityChanged: (RuntimeTextureAtlasDownscaleQuality) -> Unit = {},
    onRuntimeDownscaleSpineTexturesToggled: (Boolean) -> Unit = {},
    onRuntimeDownscaleOffscreenFrameBuffersToggled: (Boolean) -> Unit = {},
    onImportDownscaleSpineAtlasPagesToggled: (Boolean) -> Unit = {},
    onImportDownscaleOrdinaryAtlasPagesToggled: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current

    SettingsDetailLayout(
        embedded = embedded,
        modifier = modifier,
        title = stringResource(R.string.compat_settings_title),
        onGoBack = onGoBack,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (uiState.busy && !embedded) {
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
                    title = stringResource(R.string.compat_main_menu_preview_reuse_title),
                    description = stringResource(R.string.compat_main_menu_preview_reuse_desc),
                    checked = uiState.mainMenuPreviewReuseCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onMainMenuPreviewReuseCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_large_texture_downscale_title),
                    description = stringResource(R.string.compat_large_texture_downscale_desc),
                    checked = uiState.largeTextureDownscaleCompatEnabled,
                    enabled = false,
                    onCheckedChange = onLargeTextureDownscaleCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_texture_residency_manager_title),
                    description = stringResource(R.string.compat_texture_residency_manager_desc),
                    checked = uiState.textureResidencyManagerCompatEnabled,
                    enabled = false,
                    onCheckedChange = onTextureResidencyManagerCompatToggled
                )

                SettingsDropdownField(
                    label = stringResource(R.string.compat_texture_pressure_downscale_divisor_title),
                    valueText = texturePressureDownscaleDivisorLabel(
                        context,
                        uiState.texturePressureDownscaleDivisor
                    ),
                    enabled = !uiState.busy && uiState.largeTextureDownscaleCompatEnabled,
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
                    title = stringResource(R.string.compat_hina_character_render_title),
                    description = stringResource(R.string.compat_hina_character_render_desc),
                    checked = uiState.hinaCharacterRenderCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onHinaCharacterRenderCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_non_renderable_fbo_format_compat_title),
                    description = stringResource(R.string.compat_non_renderable_fbo_format_compat_desc),
                    checked = uiState.nonRenderableFboFormatCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onNonRenderableFboFormatCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_fbo_manager_title),
                    description = stringResource(R.string.compat_fbo_manager_desc),
                    checked = uiState.fboManagerCompatEnabled,
                    enabled = false,
                    onCheckedChange = onFboManagerCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_fbo_idle_reclaim_title),
                    description = stringResource(R.string.compat_fbo_idle_reclaim_desc),
                    checked = uiState.fboIdleReclaimCompatEnabled,
                    enabled = false,
                    onCheckedChange = onFboIdleReclaimCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_fbo_pressure_downscale_title),
                    description = stringResource(R.string.compat_fbo_pressure_downscale_desc),
                    checked = uiState.fboPressureDownscaleCompatEnabled,
                    enabled = false,
                    onCheckedChange = onFboPressureDownscaleCompatToggled
                )
            }

            CompatibilitySectionCard(
                title = stringResource(R.string.compat_save_rescue_section_title),
                description = stringResource(R.string.compat_save_rescue_section_desc)
            ) {
                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_room_context_hand_layout_rescue_title),
                    description = stringResource(R.string.compat_room_context_hand_layout_rescue_desc),
                    checked = uiState.roomContextHandLayoutRescueCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onRoomContextHandLayoutRescueCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_room_transition_rescue_title),
                    description = stringResource(R.string.compat_room_transition_rescue_desc),
                    checked = uiState.roomTransitionRescueCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onRoomTransitionRescueCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_event_room_rescue_title),
                    description = stringResource(R.string.compat_event_room_rescue_desc),
                    checked = uiState.eventRoomRescueCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onEventRoomRescueCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_shop_room_rescue_title),
                    description = stringResource(R.string.compat_shop_room_rescue_desc),
                    checked = uiState.shopRoomRescueCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onShopRoomRescueCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_basemod_save_load_rescue_title),
                    description = stringResource(R.string.compat_basemod_save_load_rescue_desc),
                    checked = uiState.baseModSaveLoadRescueCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onBaseModSaveLoadRescueCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_relic_enter_room_rescue_title),
                    description = stringResource(R.string.compat_relic_enter_room_rescue_desc),
                    checked = uiState.relicEnterRoomRescueCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onRelicEnterRoomRescueCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_dungeon_render_room_context_rescue_title),
                    description = stringResource(R.string.compat_dungeon_render_room_context_rescue_desc),
                    checked = uiState.dungeonRenderRoomContextRescueCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onDungeonRenderRoomContextRescueCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_power_icon_render_rescue_title),
                    description = stringResource(R.string.compat_power_icon_render_rescue_desc),
                    checked = uiState.powerIconRenderRescueCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onPowerIconRenderRescueCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_basemod_custom_monster_render_rescue_title),
                    description = stringResource(R.string.compat_basemod_custom_monster_render_rescue_desc),
                    checked = uiState.baseModCustomMonsterRenderRescueCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onBaseModCustomMonsterRenderRescueCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_non_combat_player_render_rescue_title),
                    description = stringResource(R.string.compat_non_combat_player_render_rescue_desc),
                    checked = uiState.nonCombatPlayerRenderRescueCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onNonCombatPlayerRenderRescueCompatToggled
                )

                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_card_tooltip_keyword_rescue_title),
                    description = stringResource(R.string.compat_card_tooltip_keyword_rescue_desc),
                    checked = uiState.cardTooltipKeywordRescueCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onCardTooltipKeywordRescueCompatToggled
                )
            }

            CompatibilitySectionCard(
                title = stringResource(R.string.compat_downscale_material_section_title),
                description = stringResource(R.string.compat_downscale_material_section_desc)
            ) {
                Text(
                    text = stringResource(R.string.compat_downscale_material_runtime_group_title),
                    style = MaterialTheme.typography.titleSmall
                )
                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_runtime_downscale_ordinary_texture_title),
                    description = stringResource(R.string.compat_runtime_downscale_ordinary_texture_desc),
                    checked = uiState.runtimeDownscaleOrdinaryTexturesEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onRuntimeDownscaleOrdinaryTexturesToggled
                )
                SettingsDropdownField(
                    label = stringResource(R.string.compat_runtime_downscale_texture_atlas_title),
                    valueText = runtimeTextureAtlasDownscaleQualityLabel(
                        context,
                        uiState.runtimeDownscaleTextureAtlasPagesQuality
                    ),
                    enabled = !uiState.busy,
                    supportingText = stringResource(R.string.compat_runtime_downscale_texture_atlas_desc),
                    options = RuntimeTextureAtlasDownscaleQuality.entries.toList(),
                    optionLabel = { quality ->
                        runtimeTextureAtlasDownscaleQualityLabel(context, quality)
                    },
                    optionDescription = { quality ->
                        runtimeTextureAtlasDownscaleQualityDescription(context, quality)
                    },
                    onOptionSelected = onRuntimeDownscaleTextureAtlasPagesQualityChanged
                )
                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_runtime_downscale_spine_title),
                    description = stringResource(R.string.compat_runtime_downscale_spine_desc),
                    checked = uiState.runtimeDownscaleSpineTexturesEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onRuntimeDownscaleSpineTexturesToggled
                )
                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_runtime_downscale_fbo_title),
                    description = stringResource(R.string.compat_runtime_downscale_fbo_desc),
                    checked = uiState.runtimeDownscaleOffscreenFrameBuffersEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onRuntimeDownscaleOffscreenFrameBuffersToggled
                )

                HorizontalDivider()
                Text(
                    text = stringResource(R.string.compat_downscale_material_import_group_title),
                    style = MaterialTheme.typography.titleSmall
                )
                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_import_downscale_spine_atlas_title),
                    description = stringResource(R.string.compat_import_downscale_spine_atlas_desc),
                    checked = uiState.importDownscaleSpineAtlasPagesEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onImportDownscaleSpineAtlasPagesToggled
                )
                CompatibilitySwitchRow(
                    title = stringResource(R.string.compat_import_downscale_ordinary_atlas_title),
                    description = stringResource(R.string.compat_import_downscale_ordinary_atlas_desc),
                    checked = uiState.importDownscaleOrdinaryAtlasPagesEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onImportDownscaleOrdinaryAtlasPagesToggled
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
                    title = stringResource(R.string.compat_chaofanmod_compat_title),
                    description = stringResource(R.string.compat_chaofanmod_compat_desc),
                    checked = uiState.chaofanModCompatEnabled,
                    enabled = !uiState.busy,
                    onCheckedChange = onChaofanModCompatToggled
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
    val actionGate = LocalSettingsActionGate.current
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
                    enabled = settingsControlEnabled(enabled),
                    onCheckedChange = { changed ->
                        val action = { onCheckedChange(changed) }
                        if (actionGate != null) actionGate(action) else action()
                    },
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

private fun runtimeTextureAtlasDownscaleQualityLabel(
    context: Context,
    quality: RuntimeTextureAtlasDownscaleQuality
): String {
    return when (quality) {
        RuntimeTextureAtlasDownscaleQuality.P720 ->
            context.getString(R.string.compat_runtime_downscale_texture_atlas_quality_720p)
        RuntimeTextureAtlasDownscaleQuality.P1080 ->
            context.getString(R.string.compat_runtime_downscale_texture_atlas_quality_1080p)
        RuntimeTextureAtlasDownscaleQuality.P2K ->
            context.getString(R.string.compat_runtime_downscale_texture_atlas_quality_2k)
        RuntimeTextureAtlasDownscaleQuality.NATIVE ->
            context.getString(R.string.compat_runtime_downscale_texture_atlas_quality_native)
    }
}

private fun runtimeTextureAtlasDownscaleQualityDescription(
    context: Context,
    quality: RuntimeTextureAtlasDownscaleQuality
): String {
    return when (quality) {
        RuntimeTextureAtlasDownscaleQuality.P720 ->
            context.getString(R.string.compat_runtime_downscale_texture_atlas_quality_720p_desc)
        RuntimeTextureAtlasDownscaleQuality.P1080 ->
            context.getString(R.string.compat_runtime_downscale_texture_atlas_quality_1080p_desc)
        RuntimeTextureAtlasDownscaleQuality.P2K ->
            context.getString(R.string.compat_runtime_downscale_texture_atlas_quality_2k_desc)
        RuntimeTextureAtlasDownscaleQuality.NATIVE ->
            context.getString(R.string.compat_runtime_downscale_texture_atlas_quality_native_desc)
    }
}

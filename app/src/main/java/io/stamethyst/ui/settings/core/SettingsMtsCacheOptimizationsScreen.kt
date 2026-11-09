package io.stamethyst.ui.settings.core

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.stamethyst.R
import io.stamethyst.config.MtsCacheStartupOptimization
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.settings.common.SettingsRouteScaffold
import io.stamethyst.ui.settings.common.SettingsRouteSpec
import io.stamethyst.ui.settings.common.SettingsSectionCard
import io.stamethyst.ui.settings.common.SettingsSwitchItem
import io.stamethyst.ui.settings.common.SettingsSwitchSpec

internal val MtsCacheStartupOptimization.titleResId: Int
    get() = when (this) {
        MtsCacheStartupOptimization.CLASS_FINDER_SCAN_CACHE -> R.string.settings_mts_cache_class_scan_title
        MtsCacheStartupOptimization.LAZY_CUSTOM_CARD_IMAGES -> R.string.settings_mts_cache_card_images_title
        MtsCacheStartupOptimization.LAZY_STARTUP_CARD_DESCRIPTIONS -> R.string.settings_mts_cache_card_descriptions_title
        MtsCacheStartupOptimization.LAZY_CARD_LIBRARY_SCREEN -> R.string.settings_mts_cache_card_library_title
        MtsCacheStartupOptimization.FAST_CACHE_SPLASH -> R.string.settings_mts_cache_splash_title
    }

internal val MtsCacheStartupOptimization.descriptionResId: Int
    get() = when (this) {
        MtsCacheStartupOptimization.CLASS_FINDER_SCAN_CACHE -> R.string.settings_mts_cache_class_scan_desc
        MtsCacheStartupOptimization.LAZY_CUSTOM_CARD_IMAGES -> R.string.settings_mts_cache_card_images_desc
        MtsCacheStartupOptimization.LAZY_STARTUP_CARD_DESCRIPTIONS -> R.string.settings_mts_cache_card_descriptions_desc
        MtsCacheStartupOptimization.LAZY_CARD_LIBRARY_SCREEN -> R.string.settings_mts_cache_card_library_desc
        MtsCacheStartupOptimization.FAST_CACHE_SPLASH -> R.string.settings_mts_cache_splash_desc
    }

@Composable
fun LauncherSettingsMtsCacheOptimizationsScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    LaunchedEffect(activity) { viewModel.bind(activity) }
    SettingsMtsCacheOptimizationsScreenContent(
        uiState = viewModel.uiState,
        modifier = modifier,
        onGoBack = navigator::goBack,
        onOptimizationChanged = { optimization, enabled ->
            viewModel.onMtsCacheStartupOptimizationChanged(activity, optimization, enabled)
        },
    )
}

@Composable
internal fun SettingsMtsCacheOptimizationsScreenContent(
    uiState: SettingsScreenViewModel.UiState,
    modifier: Modifier = Modifier,
    onGoBack: () -> Unit = {},
    onOptimizationChanged: (MtsCacheStartupOptimization, Boolean) -> Unit = { _, _ -> },
) {
    SettingsRouteScaffold(
        modifier = modifier,
        uiState = uiState,
        spec = SettingsRouteSpec(
            titleResId = R.string.settings_mts_cache_optimizations_title,
            subtitleResId = R.string.settings_mts_cache_optimizations_desc,
            iconResId = R.drawable.ic_speed,
        ),
        onGoBack = onGoBack,
    ) {
        item {
            SettingsSectionCard(
                title = stringResource(R.string.settings_mts_cache_optimizations_title),
                iconResId = R.drawable.ic_speed,
            ) {
                SettingsMtsCacheOptimizationControls(uiState, onOptimizationChanged)
            }
        }
    }
}

@Composable
internal fun SettingsMtsCacheOptimizationControls(
    uiState: SettingsScreenViewModel.UiState,
    onOptimizationChanged: (MtsCacheStartupOptimization, Boolean) -> Unit,
) {
    if (!uiState.mtsPatchCacheEnabled) {
        Text(stringResource(R.string.settings_mts_cache_optimizations_inactive))
    }
    MtsCacheStartupOptimization.entries.forEach { optimization ->
        SettingsSwitchItem(
            SettingsSwitchSpec(
                checked = uiState.mtsCacheStartupOptimizations[optimization] ?: false,
                enabled = !uiState.busy,
                title = stringResource(optimization.titleResId),
                description = stringResource(optimization.descriptionResId),
                onCheckedChange = { onOptimizationChanged(optimization, it) },
            )
        )
    }
}

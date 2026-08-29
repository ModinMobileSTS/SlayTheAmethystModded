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

import android.view.HapticFeedbackConstants
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.BootOverlayArtBackground
import io.stamethyst.R
import io.stamethyst.config.BootOverlayAnimation
import io.stamethyst.config.BootOverlayImageConfig
import io.stamethyst.config.BootOverlayImageMode
import io.stamethyst.config.BootOverlayImageSlot
import io.stamethyst.config.BootOverlayStyle
import io.stamethyst.config.LauncherIconMode
import io.stamethyst.config.LauncherThemeColor
import io.stamethyst.config.LauncherThemeMode
import io.stamethyst.ui.loading.BootAnimationPreviewGrid
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.resources.FileImage
import java.io.File
import kotlin.math.roundToInt


internal data class AppearanceSettingsActions(
    val onThemeModeChanged: (LauncherThemeMode) -> Unit,
    val onThemeColorChanged: (LauncherThemeColor) -> Unit,
    val onLauncherIconModeChanged: (LauncherIconMode) -> Unit,
    val onChromeBackgroundOpacityChanged: (Float) -> Unit,
    val onBootOverlayStyleChanged: (BootOverlayStyle) -> Unit,
    val onBootOverlayAnimationChanged: (BootOverlayAnimation) -> Unit,
    val onBootOverlayImageModeChanged: (BootOverlayImageMode) -> Unit,
    val onPickBootOverlayImage: (BootOverlayImageSlot) -> Unit,
    val onResetBootOverlayImages: () -> Unit,
    val onShowModFileNameChanged: (Boolean) -> Unit,
)


@Composable
internal fun SettingsAppearanceSection(
    uiState: SettingsScreenViewModel.UiState,
    actions: AppearanceSettingsActions,
) {
    val view = LocalView.current
    var showThemeColorDialog by rememberSaveable { mutableStateOf(false) }
    var showBootOverlayStyleDialog by rememberSaveable { mutableStateOf(false) }
    var showBootOverlayAnimationDialog by rememberSaveable { mutableStateOf(false) }
    var showBootOverlayCustomImageDialog by rememberSaveable { mutableStateOf(false) }
    var chromeBackgroundOpacitySliderValue by remember(uiState.chromeBackgroundOpacity) {
        mutableFloatStateOf(uiState.chromeBackgroundOpacity)
    }
    var lastChromeBackgroundOpacityStep by remember(uiState.chromeBackgroundOpacity) {
        mutableIntStateOf(chromeBackgroundOpacityToStep(uiState.chromeBackgroundOpacity))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsChoiceDialogItem(
            SettingsChoiceSpec(
                title = stringResource(R.string.settings_theme_mode_title),
                valueText = themeModeDisplayName(uiState.themeMode),
                enabled = !uiState.busy,
                selectedValue = uiState.themeMode,
                options = LauncherThemeMode.entries,
                optionLabel = { themeMode -> themeModeDisplayName(themeMode) },
                onOptionSelected = actions.onThemeModeChanged,
            )
        )

        SettingsChoiceDialogItem(
            SettingsChoiceSpec(
                title = stringResource(R.string.settings_app_icon_title),
                valueText = launcherIconModeDisplayName(uiState.launcherIconMode),
                enabled = !uiState.busy,
                selectedValue = uiState.launcherIconMode,
                options = LauncherIconMode.entries,
                optionLabel = { iconMode -> launcherIconModeDisplayName(iconMode) },
                onOptionSelected = actions.onLauncherIconModeChanged,
            )
        )

        SettingsActionListItem(
            title = stringResource(R.string.settings_theme_color_title),
            supportingText = themeColorDisplayName(uiState.themeColor),
            enabled = !uiState.busy,
            onClick = { showThemeColorDialog = true }
        )
        ThemeColorPreviewRow(selectedThemeColor = uiState.themeColor)

        Text(
            text = stringResource(R.string.settings_chrome_background_opacity_title),
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = chromeBackgroundOpacitySliderValue,
            onValueChange = { value ->
                val normalized = LauncherPreferences.normalizeChromeBackgroundOpacity(value)
                chromeBackgroundOpacitySliderValue = normalized
                val step = chromeBackgroundOpacityToStep(normalized)
                if (step != lastChromeBackgroundOpacityStep) {
                    lastChromeBackgroundOpacityStep = step
                    performHapticFeedback(view, HapticFeedbackConstants.CLOCK_TICK)
                }
            },
            onValueChangeFinished = {
                actions.onChromeBackgroundOpacityChanged(chromeBackgroundOpacitySliderValue)
            },
            valueRange = LauncherPreferences.MIN_CHROME_BACKGROUND_OPACITY..
                LauncherPreferences.MAX_CHROME_BACKGROUND_OPACITY,
            steps = (
                ((LauncherPreferences.MAX_CHROME_BACKGROUND_OPACITY -
                    LauncherPreferences.MIN_CHROME_BACKGROUND_OPACITY) / 0.05f)
                    .roundToInt() - 1
                ).coerceAtLeast(0),
            enabled = !uiState.busy,
            modifier = Modifier.fillMaxWidth()
        )

        SettingsActionListItem(
            title = stringResource(R.string.settings_boot_overlay_style_title),
            supportingText = bootOverlayStyleDisplayName(uiState.bootOverlayStyle),
            enabled = !uiState.busy,
            onClick = { showBootOverlayStyleDialog = true }
        )

        if (uiState.bootOverlayStyle == BootOverlayStyle.MODERN) {
            SettingsActionListItem(
                title = stringResource(R.string.settings_boot_overlay_custom_image_title),
                supportingText = bootOverlayCustomImageSummary(uiState.bootOverlayImageConfig),
                enabled = !uiState.busy,
                onClick = { showBootOverlayCustomImageDialog = true }
            )
        }

        if (uiState.bootOverlayStyle.supportsLoadingAnimation) {
            SettingsActionListItem(
                title = stringResource(R.string.settings_loading_animation_title),
                supportingText = loadingAnimationDisplayName(uiState.bootOverlayAnimation),
                enabled = !uiState.busy,
                onClick = { showBootOverlayAnimationDialog = true }
            )
        }
    }

    if (showBootOverlayStyleDialog) {
        AlertDialog(
            onDismissRequest = { showBootOverlayStyleDialog = false },
            title = { Text(stringResource(R.string.settings_boot_overlay_style_dialog_title)) },
            text = {
                BootOverlayStylePreviewGrid(
                    selectedStyle = uiState.bootOverlayStyle,
                    enabled = !uiState.busy,
                    onSelect = { style ->
                        actions.onBootOverlayStyleChanged(style)
                        showBootOverlayStyleDialog = false
                    }
                )
            },
            confirmButton = {
                HapticTextButton(onClick = { showBootOverlayStyleDialog = false }) {
                    Text(stringResource(R.string.main_folder_dialog_confirm))
                }
            }
        )
    }

    if (showBootOverlayCustomImageDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.settings_boot_overlay_custom_image_dialog_title)) },
            text = {
                BootOverlayCustomImageDialogContent(
                    config = uiState.bootOverlayImageConfig,
                    enabled = !uiState.busy,
                    onModeChanged = actions.onBootOverlayImageModeChanged,
                    onPickImage = actions.onPickBootOverlayImage,
                    onReset = actions.onResetBootOverlayImages,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                )
            },
            confirmButton = {
                HapticTextButton(onClick = { showBootOverlayCustomImageDialog = false }) {
                    Text(stringResource(R.string.common_action_close))
                }
            }
        )
    }

    if (showThemeColorDialog) {
        AlertDialog(
            onDismissRequest = { showThemeColorDialog = false },
            title = { Text(stringResource(R.string.settings_theme_color_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LauncherThemeColor.entries.forEach { themeColor ->
                        ThemeColorOptionRow(
                            themeColor = themeColor,
                            selected = uiState.themeColor == themeColor,
                            enabled = !uiState.busy,
                            onSelect = {
                                actions.onThemeColorChanged(themeColor)
                                showThemeColorDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showThemeColorDialog = false }) {
                    Text(stringResource(R.string.main_folder_dialog_confirm))
                }
            }
        )
    }

    if (showBootOverlayAnimationDialog) {
        val animationNames = BootOverlayAnimation.entries.associateWith { animation ->
            loadingAnimationDisplayName(animation)
        }
        AlertDialog(
            onDismissRequest = { showBootOverlayAnimationDialog = false },
            title = { Text(stringResource(R.string.settings_loading_animation_dialog_title)) },
            text = {
                BootAnimationPreviewGrid(
                    selectedAnimation = uiState.bootOverlayAnimation,
                    animationNames = animationNames,
                    enabled = !uiState.busy,
                    onSelect = { animation ->
                        actions.onBootOverlayAnimationChanged(animation)
                        showBootOverlayAnimationDialog = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                )
            },
            confirmButton = {
                HapticTextButton(onClick = { showBootOverlayAnimationDialog = false }) {
                    Text(stringResource(R.string.common_action_close))
                }
            }
        )
    }
}


@Composable
internal fun bootOverlayStyleDisplayName(style: BootOverlayStyle): String {
    return stringResource(
        when (style) {
            BootOverlayStyle.MODERN -> R.string.settings_boot_overlay_style_modern
            BootOverlayStyle.LEGACY -> R.string.settings_boot_overlay_style_legacy
            BootOverlayStyle.CLASSIC_LOG -> R.string.settings_boot_overlay_style_classic_log
            BootOverlayStyle.MATERIAL_LOG -> R.string.settings_boot_overlay_style_material_log
        }
    )
}


@Composable
internal fun bootOverlayCustomImageSummary(config: BootOverlayImageConfig): String {
    return when {
        !config.hasCustomImages -> stringResource(R.string.settings_boot_overlay_custom_image_default)
        config.mode == BootOverlayImageMode.SINGLE ->
            stringResource(R.string.settings_boot_overlay_custom_image_single_summary)
        else -> stringResource(R.string.settings_boot_overlay_custom_image_dual_summary)
    }
}


@Composable
internal fun BootOverlayCustomImageDialogContent(
    config: BootOverlayImageConfig,
    enabled: Boolean,
    onModeChanged: (BootOverlayImageMode) -> Unit,
    onPickImage: (BootOverlayImageSlot) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier.verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BootOverlayCustomImagePreview(
            config = config,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        )
        BootOverlayImageModeSelector(
            mode = config.mode,
            enabled = enabled,
            onModeChanged = onModeChanged
        )
        Crossfade(
            targetState = config.mode,
            animationSpec = tween(durationMillis = 260),
            label = "boot_overlay_image_mode_crossfade"
        ) { mode ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BootOverlayImageSlotPicker(
                    slot = BootOverlayImageSlot.START,
                    config = config,
                    enabled = enabled,
                    title = stringResource(
                        if (mode == BootOverlayImageMode.SINGLE) {
                            R.string.settings_boot_overlay_custom_image_single_pick
                        } else {
                            R.string.settings_boot_overlay_custom_image_start_pick
                        }
                    ),
                    description = stringResource(
                        if (mode == BootOverlayImageMode.SINGLE) {
                            R.string.settings_boot_overlay_custom_image_single_pick_desc
                        } else {
                            R.string.settings_boot_overlay_custom_image_start_pick_desc
                        }
                    ),
                    onPickImage = onPickImage
                )
                if (mode == BootOverlayImageMode.DUAL) {
                    BootOverlayImageSlotPicker(
                        slot = BootOverlayImageSlot.END,
                        config = config,
                        enabled = enabled,
                        title = stringResource(R.string.settings_boot_overlay_custom_image_end_pick),
                        description = stringResource(R.string.settings_boot_overlay_custom_image_end_pick_desc),
                        onPickImage = onPickImage
                    )
                }
            }
        }
        HapticTextButton(
            onClick = onReset,
            enabled = enabled && config.hasCustomImages
        ) {
            Text(stringResource(R.string.settings_boot_overlay_custom_image_reset))
        }
    }
}


@Composable
internal fun BootOverlayCustomImagePreview(
    config: BootOverlayImageConfig,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .border(
                width = 1.dp,
                color = colorScheme.outlineVariant.copy(alpha = 0.52f),
                shape = RoundedCornerShape(8.dp)
            )
    ) {
        Crossfade(
            targetState = config,
            animationSpec = tween(durationMillis = 260),
            label = "boot_overlay_custom_image_preview_crossfade",
            modifier = Modifier.fillMaxSize()
        ) { previewConfig ->
            BootOverlayArtBackground(
                imageConfig = previewConfig,
                revealProgress = 0.56f,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.10f),
                            0.56f to Color.Black.copy(alpha = 0.08f),
                            1.00f to Color.Black.copy(alpha = 0.70f)
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.boot_overlay_title_starting),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BootProgressBarPreview(progress = 0.56f)
            Text(
                text = stringResource(R.string.settings_boot_overlay_custom_image_preview_status),
                color = Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


@Composable
internal fun BootProgressBarPreview(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.22f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary,
                            MaterialTheme.colorScheme.secondary
                        )
                    )
                )
        )
    }
}


@Composable
internal fun BootOverlayImageModeSelector(
    mode: BootOverlayImageMode,
    enabled: Boolean,
    onModeChanged: (BootOverlayImageMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BootOverlayImageModeOption(
            selected = mode == BootOverlayImageMode.SINGLE,
            enabled = enabled,
            title = stringResource(R.string.settings_boot_overlay_custom_image_single_mode),
            onClick = { onModeChanged(BootOverlayImageMode.SINGLE) },
            modifier = Modifier.weight(1f)
        )
        BootOverlayImageModeOption(
            selected = mode == BootOverlayImageMode.DUAL,
            enabled = enabled,
            title = stringResource(R.string.settings_boot_overlay_custom_image_dual_mode),
            onClick = { onModeChanged(BootOverlayImageMode.DUAL) },
            modifier = Modifier.weight(1f)
        )
    }
}


@Composable
internal fun BootOverlayImageModeOption(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .height(46.dp)
            .clip(shape)
            .background(
                colorScheme.surfaceVariant.copy(alpha = if (selected) 0.34f else 0.16f)
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    colorScheme.primary
                } else {
                    colorScheme.outlineVariant.copy(alpha = 0.52f)
                },
                shape = shape
            )
            .hapticClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}


@Composable
internal fun BootOverlayImageSlotPicker(
    slot: BootOverlayImageSlot,
    config: BootOverlayImageConfig,
    enabled: Boolean,
    title: String,
    description: String,
    onPickImage: (BootOverlayImageSlot) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val imagePath = config.imagePathFor(slot)
    val imageVersion = when (slot) {
        BootOverlayImageSlot.START -> config.startImageVersion
        BootOverlayImageSlot.END -> config.endImageVersion
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colorScheme.surfaceContainerHigh)
            .hapticClickable(enabled = enabled, onClick = { onPickImage(slot) })
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 84.dp, height = 48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colorScheme.surfaceVariant.copy(alpha = 0.50f))
                .border(
                    width = 1.dp,
                    color = colorScheme.outlineVariant.copy(alpha = 0.48f),
                    shape = RoundedCornerShape(6.dp)
                )
        ) {
            if (imagePath != null) {
                FileImage(
                    path = imagePath,
                    version = imageVersion,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_boot_overlay_custom_image_default_badge),
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 6.dp)
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = imagePath?.let(::bootOverlayImageFileName) ?: description,
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
    }
}


internal fun bootOverlayImageFileName(path: String): String {
    return File(path).name.ifBlank { path }
}


@Composable
internal fun BootOverlayStylePreviewGrid(
    selectedStyle: BootOverlayStyle,
    enabled: Boolean,
    onSelect: (BootOverlayStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = 10.dp
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        BootOverlayStyle.entries.chunked(2).forEach { rowStyles ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                rowStyles.forEach { style ->
                    BootOverlayStyleOption(
                        style = style,
                        selected = selectedStyle == style,
                        enabled = enabled,
                        onSelect = { onSelect(style) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowStyles.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}


@Composable
internal fun BootOverlayStyleOption(
    style: BootOverlayStyle,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val borderColor = if (selected) {
        colorScheme.primary
    } else {
        colorScheme.outlineVariant.copy(alpha = 0.58f)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(colorScheme.surfaceVariant.copy(alpha = if (selected) 0.28f else 0.14f))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = shape
            )
            .hapticClickable(enabled = enabled, onClick = onSelect)
            .padding(8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BootOverlayStyleWireframePreview(
                style = style,
                selected = selected,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected,
                    onClick = null,
                    enabled = enabled,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = bootOverlayStyleDisplayName(style),
                    color = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}


@Composable
internal fun BootOverlayStyleWireframePreview(
    style: BootOverlayStyle,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val frameColor = if (selected) {
        colorScheme.primary.copy(alpha = 0.92f)
    } else {
        colorScheme.onSurfaceVariant.copy(alpha = 0.74f)
    }
    val mutedFrameColor = frameColor.copy(alpha = 0.42f)
    val fillColor = colorScheme.surface.copy(alpha = 0.64f)
    val accentFillColor = colorScheme.primary.copy(alpha = if (selected) 0.22f else 0.12f)
    val strokeWidth = if (selected) 2.2f else 1.6f

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colorScheme.surface.copy(alpha = 0.72f))
    ) {
        drawRoundRect(
            color = fillColor,
            size = size,
            cornerRadius = CornerRadius(10f, 10f)
        )
        drawRoundRect(
            color = mutedFrameColor,
            size = size,
            cornerRadius = CornerRadius(10f, 10f),
            style = Stroke(width = strokeWidth)
        )
        when (style) {
            BootOverlayStyle.MODERN -> drawModernBootOverlayWireframe(
                frameColor = frameColor,
                mutedFrameColor = mutedFrameColor,
                accentFillColor = accentFillColor,
                strokeWidth = strokeWidth
            )
            BootOverlayStyle.LEGACY -> drawLegacyBootOverlayWireframe(
                frameColor = frameColor,
                mutedFrameColor = mutedFrameColor,
                accentFillColor = accentFillColor,
                strokeWidth = strokeWidth
            )
            BootOverlayStyle.CLASSIC_LOG -> drawClassicLogBootOverlayWireframe(
                frameColor = frameColor,
                mutedFrameColor = mutedFrameColor,
                accentFillColor = accentFillColor,
                strokeWidth = strokeWidth
            )
            BootOverlayStyle.MATERIAL_LOG -> drawMaterialLogBootOverlayWireframe(
                frameColor = frameColor,
                mutedFrameColor = mutedFrameColor,
                accentFillColor = accentFillColor,
                strokeWidth = strokeWidth
            )
        }
    }
}


internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawModernBootOverlayWireframe(
    frameColor: Color,
    mutedFrameColor: Color,
    accentFillColor: Color,
    strokeWidth: Float,
) {
    val revealWidth = size.width * 0.58f
    val bottomPanelTop = size.height * 0.68f
    val progressLeft = size.width * 0.08f
    val progressTop = size.height * 0.86f
    val progressWidth = size.width * 0.84f
    val progressHeight = size.height * 0.065f

    drawRect(
        color = accentFillColor,
        size = Size(width = revealWidth, height = size.height)
    )
    drawLine(
        color = frameColor,
        start = Offset(revealWidth, 0f),
        end = Offset(revealWidth, size.height),
        strokeWidth = strokeWidth
    )
    drawRect(
        color = mutedFrameColor.copy(alpha = 0.18f),
        topLeft = Offset(0f, bottomPanelTop),
        size = Size(size.width, size.height - bottomPanelTop)
    )
    drawRoundRect(
        color = frameColor.copy(alpha = 0.82f),
        topLeft = Offset(progressLeft, progressTop),
        size = Size(progressWidth * 0.62f, progressHeight),
        cornerRadius = CornerRadius(progressHeight / 2f, progressHeight / 2f)
    )
    drawRoundRect(
        color = mutedFrameColor,
        topLeft = Offset(progressLeft, progressTop),
        size = Size(progressWidth, progressHeight),
        cornerRadius = CornerRadius(progressHeight / 2f, progressHeight / 2f),
        style = Stroke(width = strokeWidth)
    )
    drawLine(
        color = frameColor,
        start = Offset(progressLeft, size.height * 0.76f),
        end = Offset(size.width * 0.48f, size.height * 0.76f),
        strokeWidth = strokeWidth
    )
    drawLine(
        color = mutedFrameColor,
        start = Offset(progressLeft, size.height * 0.81f),
        end = Offset(size.width * 0.68f, size.height * 0.81f),
        strokeWidth = strokeWidth
    )
}


internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLegacyBootOverlayWireframe(
    frameColor: Color,
    mutedFrameColor: Color,
    accentFillColor: Color,
    strokeWidth: Float,
) {
    val gutter = size.width * 0.08f
    val panelTop = size.height * 0.14f
    val panelHeight = size.height * 0.62f
    val leftWidth = size.width * 0.34f
    val rightLeft = size.width * 0.48f
    val rightWidth = size.width * 0.44f
    val progressTop = size.height * 0.86f
    val progressHeight = size.height * 0.06f

    drawRoundRect(
        color = accentFillColor,
        topLeft = Offset(gutter, panelTop),
        size = Size(leftWidth, panelHeight),
        cornerRadius = CornerRadius(8f, 8f),
        style = Stroke(width = strokeWidth)
    )
    drawRoundRect(
        color = mutedFrameColor.copy(alpha = 0.18f),
        topLeft = Offset(rightLeft, panelTop),
        size = Size(rightWidth, panelHeight),
        cornerRadius = CornerRadius(8f, 8f)
    )
    drawRoundRect(
        color = mutedFrameColor,
        topLeft = Offset(rightLeft, panelTop),
        size = Size(rightWidth, panelHeight),
        cornerRadius = CornerRadius(8f, 8f),
        style = Stroke(width = strokeWidth)
    )
    repeat(5) { index ->
        val y = panelTop + panelHeight * (0.18f + index * 0.13f)
        drawLine(
            color = if (index == 0) frameColor else mutedFrameColor,
            start = Offset(rightLeft + rightWidth * 0.12f, y),
            end = Offset(rightLeft + rightWidth * (0.86f - index * 0.05f), y),
            strokeWidth = strokeWidth
        )
    }
    drawRoundRect(
        color = frameColor.copy(alpha = 0.82f),
        topLeft = Offset(gutter, progressTop),
        size = Size(size.width * 0.52f, progressHeight),
        cornerRadius = CornerRadius(progressHeight / 2f, progressHeight / 2f)
    )
    drawRoundRect(
        color = mutedFrameColor,
        topLeft = Offset(gutter, progressTop),
        size = Size(size.width * 0.84f, progressHeight),
        cornerRadius = CornerRadius(progressHeight / 2f, progressHeight / 2f),
        style = Stroke(width = strokeWidth)
    )
}


internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawClassicLogBootOverlayWireframe(
    frameColor: Color,
    mutedFrameColor: Color,
    accentFillColor: Color,
    strokeWidth: Float,
) {
    val panelLeft = size.width * 0.12f
    val panelTop = size.height * 0.24f
    val panelWidth = size.width * 0.76f
    val progressTop = size.height * 0.42f
    val logTop = size.height * 0.58f
    val logHeight = size.height * 0.24f

    drawRect(
        color = mutedFrameColor.copy(alpha = 0.22f),
        size = size
    )
    drawLine(
        color = frameColor,
        start = Offset(panelLeft, panelTop),
        end = Offset(panelLeft + panelWidth * 0.36f, panelTop),
        strokeWidth = strokeWidth
    )
    drawRoundRect(
        color = frameColor.copy(alpha = 0.82f),
        topLeft = Offset(panelLeft, progressTop),
        size = Size(panelWidth * 0.58f, size.height * 0.055f),
        cornerRadius = CornerRadius(8f, 8f)
    )
    drawRoundRect(
        color = mutedFrameColor,
        topLeft = Offset(panelLeft, progressTop),
        size = Size(panelWidth, size.height * 0.055f),
        cornerRadius = CornerRadius(8f, 8f),
        style = Stroke(width = strokeWidth)
    )
    drawRoundRect(
        color = accentFillColor.copy(alpha = 0.42f),
        topLeft = Offset(panelLeft, logTop),
        size = Size(panelWidth, logHeight),
        cornerRadius = CornerRadius(6f, 6f)
    )
    repeat(4) { index ->
        val y = logTop + logHeight * (0.20f + index * 0.17f)
        drawLine(
            color = if (index == 0) frameColor else mutedFrameColor,
            start = Offset(panelLeft + panelWidth * 0.08f, y),
            end = Offset(panelLeft + panelWidth * (0.86f - index * 0.08f), y),
            strokeWidth = strokeWidth
        )
    }
}


internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMaterialLogBootOverlayWireframe(
    frameColor: Color,
    mutedFrameColor: Color,
    accentFillColor: Color,
    strokeWidth: Float,
) {
    val gutter = size.width * 0.08f
    val titleTop = size.height * 0.16f
    val progressTop = size.height * 0.32f
    val logTop = size.height * 0.48f
    val logWidth = size.width * 0.84f
    val logHeight = size.height * 0.38f
    val progressHeight = size.height * 0.06f

    drawLine(
        color = frameColor,
        start = Offset(gutter, titleTop),
        end = Offset(size.width * 0.46f, titleTop),
        strokeWidth = strokeWidth
    )
    drawRoundRect(
        color = frameColor.copy(alpha = 0.82f),
        topLeft = Offset(gutter, progressTop),
        size = Size(logWidth * 0.60f, progressHeight),
        cornerRadius = CornerRadius(progressHeight / 2f, progressHeight / 2f)
    )
    drawRoundRect(
        color = mutedFrameColor,
        topLeft = Offset(gutter, progressTop),
        size = Size(logWidth, progressHeight),
        cornerRadius = CornerRadius(progressHeight / 2f, progressHeight / 2f),
        style = Stroke(width = strokeWidth)
    )
    drawRoundRect(
        color = accentFillColor.copy(alpha = 0.20f),
        topLeft = Offset(gutter, logTop),
        size = Size(logWidth, logHeight),
        cornerRadius = CornerRadius(8f, 8f)
    )
    repeat(6) { index ->
        val y = logTop + logHeight * (0.14f + index * 0.13f)
        drawLine(
            color = if (index == 0) frameColor else mutedFrameColor,
            start = Offset(gutter + logWidth * 0.06f, y),
            end = Offset(gutter + logWidth * (0.92f - index * 0.06f), y),
            strokeWidth = strokeWidth
        )
    }
}


@Composable
private fun themeModeDisplayName(themeMode: LauncherThemeMode): String {
    return when (themeMode) {
        LauncherThemeMode.FOLLOW_SYSTEM ->
            stringResource(R.string.settings_theme_mode_follow_system)
        LauncherThemeMode.LIGHT ->
            stringResource(R.string.settings_theme_mode_light)
        LauncherThemeMode.DARK ->
            stringResource(R.string.settings_theme_mode_dark)
    }
}


@Composable
private fun launcherIconModeDisplayName(iconMode: LauncherIconMode): String {
    return when (iconMode) {
        LauncherIconMode.AMETHYST ->
            stringResource(R.string.settings_app_icon_amethyst)
        LauncherIconMode.WATCHER ->
            stringResource(R.string.settings_app_icon_watcher)
    }
}


@Composable
private fun loadingAnimationDisplayName(animation: BootOverlayAnimation): String {
    return stringResource(
        when (animation) {
            BootOverlayAnimation.INFINITY_ORBIT ->
                R.string.settings_loading_animation_infinity_orbit
            BootOverlayAnimation.COMET -> R.string.settings_loading_animation_comet
            BootOverlayAnimation.WAVE -> R.string.settings_loading_animation_wave
            BootOverlayAnimation.HALO -> R.string.settings_loading_animation_halo
            BootOverlayAnimation.ELASTIC_DOTS ->
                R.string.settings_loading_animation_elastic_dots
            BootOverlayAnimation.SPIRAL -> R.string.settings_loading_animation_spiral
            BootOverlayAnimation.PULSE_RINGS -> R.string.settings_loading_animation_pulse_rings
            BootOverlayAnimation.ORBITAL_ECLIPSE ->
                R.string.settings_loading_animation_orbital_eclipse
            BootOverlayAnimation.RUNIC_GATE -> R.string.settings_loading_animation_runic_gate
            BootOverlayAnimation.CARD_SHUFFLE -> R.string.settings_loading_animation_card_shuffle
            BootOverlayAnimation.PRISM_SWEEP -> R.string.settings_loading_animation_prism_sweep
            BootOverlayAnimation.HELIX_LADDER -> R.string.settings_loading_animation_helix_ladder
            BootOverlayAnimation.LIQUID_ORB -> R.string.settings_loading_animation_liquid_orb
            BootOverlayAnimation.SIGNAL_STACK -> R.string.settings_loading_animation_signal_stack
            BootOverlayAnimation.DIAMOND_FLOW -> R.string.settings_loading_animation_diamond_flow
            BootOverlayAnimation.GRAVITY_WELL -> R.string.settings_loading_animation_gravity_well
        }
    )
}

@Composable
private fun themeColorDisplayName(themeColor: LauncherThemeColor): String {
    return when (themeColor) {
        LauncherThemeColor.ZHANSHIGE ->
            stringResource(R.string.settings_theme_color_zhanshige)
        LauncherThemeColor.LIEBAO ->
            stringResource(R.string.settings_theme_color_liebao)
        LauncherThemeColor.JIBAO ->
            stringResource(R.string.settings_theme_color_jibao)
        LauncherThemeColor.GUANJIE ->
            stringResource(R.string.settings_theme_color_guanjie)
        LauncherThemeColor.COLORLESS ->
            stringResource(R.string.settings_theme_color_colorless)
    }
}


private fun chromeBackgroundOpacityToStep(value: Float): Int {
    return (
        (value - LauncherPreferences.MIN_CHROME_BACKGROUND_OPACITY) / 0.05f
        ).roundToInt()
}


@Composable
internal fun ThemeColorPreviewRow(selectedThemeColor: LauncherThemeColor) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LauncherThemeColor.entries.forEach { themeColor ->
            ThemeColorSwatch(
                themeColor = themeColor,
                selected = themeColor == selectedThemeColor
            )
        }
    }
}


@Composable
internal fun ThemeColorOptionRow(
    themeColor: LauncherThemeColor,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hapticToggleable(
                value = selected,
                enabled = enabled,
                onValueChange = { onSelect() }
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled
        )
        Spacer(modifier = Modifier.width(10.dp))
        ThemeColorSwatch(themeColor = themeColor, selected = selected)
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = themeColorDisplayName(themeColor))
    }
}


@Composable
internal fun ThemeColorSwatch(
    themeColor: LauncherThemeColor,
    selected: Boolean,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    }
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(themeColor.seedColor)
            .border(width = 2.dp, color = borderColor, shape = CircleShape)
    )
}


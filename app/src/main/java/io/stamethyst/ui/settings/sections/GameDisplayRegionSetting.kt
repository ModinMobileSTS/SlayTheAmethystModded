package io.stamethyst.ui.settings.sections

import android.graphics.Paint
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.render.DisplayRegionCorner
import io.stamethyst.backend.render.GameDisplayRegion
import io.stamethyst.backend.render.GameResolutionDraft
import io.stamethyst.backend.render.GameResolutionPreset
import io.stamethyst.backend.render.VirtualResolutionPolicy
import io.stamethyst.ui.LauncherAlertDialog
import io.stamethyst.ui.settings.common.SettingsActionListItem
import io.stamethyst.ui.settings.common.settingsControlEnabled
import io.stamethyst.ui.settings.core.SettingsScreenViewModel
import kotlin.math.roundToInt

@Composable
internal fun GameResolutionSetting(
    uiState: SettingsScreenViewModel.UiState,
    onSave: (GameResolutionDraft) -> Unit,
) {
    val activity = LocalActivity.current
    val hostView = LocalView.current
    val configuration = LocalConfiguration.current
    // Capture the activity window, not the smaller dialog window; refresh after a rotation/fold.
    var showEditor by rememberSaveable { mutableStateOf(false) }
    val geometry = remember(activity, hostView, configuration, showEditor) {
        activity?.let { DeviceDisplayGeometry.read(it, hostView) }
    }
    val initial = geometry?.let {
        GameResolutionDraft.fromSaved(
            uiState.gameDisplayRegion ?: it.legacyRegion(uiState.avoidDisplayCutout, uiState.cropScreenBottom),
            uiState.selectedRenderScale, uiState.virtualResolutionMode, it.width, it.height,
        )
    }
    val area = if (geometry != null && initial != null) initial.region.toPixels(geometry.width, geometry.height) else null
    SettingsActionListItem(
        title = stringResource(R.string.settings_game_resolution_title),
        supportingText = area?.let {
            stringResource(R.string.settings_game_resolution_dimensions, it.width, it.height)
        },
        enabled = !uiState.busy && initial != null,
        onClick = { showEditor = true },
    )
    if (showEditor && geometry != null && initial != null) {
        GameResolutionDialog(
            initial, geometry, !uiState.busy,
            onDismiss = { showEditor = false },
            onSave = { onSave(it); showEditor = false },
        )
    }
}

@Composable
internal fun GameResolutionDialog(
    initial: GameResolutionDraft,
    geometry: DeviceDisplayGeometry,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (GameResolutionDraft) -> Unit,
) {
    var serializedDraft by rememberSaveable { mutableStateOf(initial.region.persistedValue()) }
    var scale by rememberSaveable { mutableStateOf(initial.scale) }
    var showSizeInputs by rememberSaveable { mutableStateOf(false) }
    var chosenPreset by rememberSaveable { mutableStateOf<String?>(null) }
    val region = GameDisplayRegion.fromPersistedValue(serializedDraft) ?: initial.region
    val (screenWidth, screenHeight) = geometry
    val rect = region.toPixels(screenWidth, screenHeight)
    var widthInput by rememberSaveable(screenWidth, screenHeight) { mutableStateOf(rect.width.toString()) }
    var heightInput by rememberSaveable(screenWidth, screenHeight) { mutableStateOf(rect.height.toString()) }
    val minWidth = minOf(rect.width, GameDisplayRegion.minPixelExtent(screenWidth))
    val minHeight = minOf(rect.height, GameDisplayRegion.minPixelExtent(screenHeight))
    val widthValid = widthInput.toIntOrNull()?.let { it in minWidth..screenWidth } == true
    val heightValid = heightInput.toIntOrNull()?.let { it in minHeight..screenHeight } == true
    val controlsEnabled = settingsControlEnabled(enabled)
    val minimumScale = VirtualResolutionPolicy.minimumScale(rect.width, rect.height)
    val render = VirtualResolutionPolicy.resolve(rect.width, rect.height, scale)
    val draft = GameResolutionDraft(region, render.effectiveScale)
    val pixelSize = stringResource(R.string.settings_game_resolution_dimensions, render.width, render.height)
    val renderSize = if (render.width.toLong() * rect.height == render.height.toLong() * rect.width) {
        pixelSize
    } else {
        stringResource(R.string.settings_game_resolution_approximate, pixelSize)
    }
    val scaleLabel = stringResource(R.string.settings_game_resolution_percent, (render.effectiveScale * 100).roundToInt())
    val presets = remember(screenWidth, screenHeight) {
        GameResolutionPreset.entries.associateWith { it.resolve(screenWidth, screenHeight) }
    }
    val matchingPresets = GameResolutionPreset.entries.filter { presets[it] == draft }
    val selectedPreset = matchingPresets.firstOrNull { it.name == chosenPreset } ?: matchingPresets.firstOrNull()

    fun updateFromFields() {
        val width = widthInput.toIntOrNull() ?: return
        val height = heightInput.toIntOrNull() ?: return
        val current = GameDisplayRegion.fromPersistedValue(serializedDraft) ?: initial.region
        current.withPixelSize(screenWidth, screenHeight, width, height)?.let {
            serializedDraft = it.persistedValue()
            scale = GameResolutionDraft(it, scale).withRegion(it, screenWidth, screenHeight).scale
            chosenPreset = null
        }
    }

    fun updateFromPreview(next: GameDisplayRegion) {
        serializedDraft = next.persistedValue()
        scale = GameResolutionDraft(next, scale).withRegion(next, screenWidth, screenHeight).scale
        chosenPreset = null
        val size = next.toPixels(screenWidth, screenHeight)
        widthInput = size.width.toString()
        heightInput = size.height.toString()
    }

    LauncherAlertDialog(
        scrollableText = false,
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("game_display_region_dialog"),
        title = { Text(stringResource(R.string.settings_game_resolution_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GameDisplayRegionPreview(
                    region, geometry, controlsEnabled,
                    onRegionChange = ::updateFromPreview,
                )
                Text(
                    stringResource(R.string.settings_game_display_screen_pixels, screenWidth, screenHeight),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.settings_game_display_drag_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Row(
                    Modifier.fillMaxWidth().testTag("game_display_resolution_toggle")
                        .clickable(enabled = controlsEnabled) {
                            if (!showSizeInputs || (widthValid && heightValid)) showSizeInputs = !showSizeInputs
                        }
                        .heightIn(min = 48.dp)
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_game_display_resolution), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.settings_game_display_resolution_short), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.settings_game_resolution_dimensions, rect.width, rect.height))
                    Text(if (showSizeInputs) "⌃" else "⌄")
                }
                if (showSizeInputs) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = widthInput,
                            onValueChange = { widthInput = it; updateFromFields() },
                            modifier = Modifier.weight(1f).testTag("game_display_region_width"),
                            enabled = controlsEnabled,
                            label = { Text(stringResource(R.string.settings_game_display_width_px)) },
                            singleLine = true,
                            isError = !widthValid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = if (widthValid) null else {
                                { Text(stringResource(R.string.settings_game_display_input_range, minWidth, screenWidth)) }
                            },
                        )
                        OutlinedTextField(
                            value = heightInput,
                            onValueChange = { heightInput = it; updateFromFields() },
                            modifier = Modifier.weight(1f).testTag("game_display_region_height"),
                            enabled = controlsEnabled,
                            label = { Text(stringResource(R.string.settings_game_display_height_px)) },
                            singleLine = true,
                            isError = !heightValid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = if (heightValid) null else {
                                { Text(stringResource(R.string.settings_game_display_input_range, minHeight, screenHeight)) }
                            },
                        )
                    }
                    if (geometry.cutoutBounds.isNotEmpty()) {
                        TextButton(enabled = controlsEnabled, onClick = {
                            updateFromPreview(geometry.legacyRegion(true, false))
                        }) { Text(stringResource(R.string.settings_game_display_avoid_cutout)) }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_game_render_resolution), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.settings_game_render_resolution_short), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(renderSize, modifier = Modifier.testTag("game_render_resolution_value"))
                }
                Slider(
                    value = render.effectiveScale,
                    onValueChange = { scale = it; chosenPreset = null },
                    enabled = controlsEnabled && minimumScale < 1f,
                    valueRange = minimumScale..1f,
                    modifier = Modifier.fillMaxWidth().testTag("game_render_resolution_slider")
                        .semantics { stateDescription = "$scaleLabel · $renderSize" },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.settings_game_render_lower), style = MaterialTheme.typography.labelSmall)
                    Text(scaleLabel, style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.settings_game_render_higher), style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
                Text(stringResource(R.string.settings_game_resolution_presets), style = MaterialTheme.typography.titleSmall)
                GameResolutionPreset.entries.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { preset ->
                            val draft = presets[preset]
                            FilterChip(
                                selected = selectedPreset == preset && widthValid && heightValid,
                                onClick = {
                                    if (draft != null) {
                                        updateFromPreview(draft.region)
                                        scale = draft.scale
                                        chosenPreset = preset.name
                                        showSizeInputs = false
                                    }
                                },
                                enabled = controlsEnabled && draft != null,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                                label = { Text(stringResource(preset.title())) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = controlsEnabled && widthInput.toIntOrNull() == rect.width && heightInput.toIntOrNull() == rect.height,
                onClick = { if (draft == initial && chosenPreset == null) onDismiss() else onSave(draft) },
            ) { Text(stringResource(R.string.common_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.main_folder_dialog_cancel)) }
        },
    )
}

private fun GameResolutionPreset.title(): Int = when (this) {
    GameResolutionPreset.FULLSCREEN -> R.string.settings_game_display_reset
    GameResolutionPreset.HD -> R.string.settings_virtual_resolution_mode_1080p
    GameResolutionPreset.PERFORMANCE -> R.string.settings_virtual_resolution_mode_720p
    GameResolutionPreset.CLASSIC -> R.string.settings_virtual_resolution_mode_4_3
}

@Composable
internal fun GameDisplayRegionPreview(
    region: GameDisplayRegion,
    geometry: DeviceDisplayGeometry,
    enabled: Boolean = true,
    onRegionChange: (GameDisplayRegion) -> Unit,
) {
    val latestRegion by rememberUpdatedState(region)
    val latestChange by rememberUpdatedState(onRegionChange)
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }
    var activeCorner by remember { mutableStateOf<DisplayRegionCorner?>(null) }
    val density = LocalDensity.current
    val padding = with(density) { 24.dp.toPx() }
    val touchRadius = with(density) { 26.dp.toPx() }
    val handleSize = with(density) { 48.dp.roundToPx() }
    val screen = displayPreviewRect(measuredSize.width.toFloat(), measuredSize.height.toFloat(), geometry, padding)
    val primary = MaterialTheme.colorScheme.primary
    val cornerNames = listOf(R.string.settings_game_display_top_left, R.string.settings_game_display_top_right,
        R.string.settings_game_display_bottom_left, R.string.settings_game_display_bottom_right).map { stringResource(it) }
    val moveNames = listOf(R.string.settings_game_display_move_left, R.string.settings_game_display_move_right,
        R.string.settings_game_display_move_up, R.string.settings_game_display_move_down).map { stringResource(it) }
    val gameLabel = stringResource(R.string.settings_game_display_size_preview)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val previewHeight = (maxWidth - 48.dp).coerceAtLeast(1.dp) *
        (geometry.height.toFloat() / geometry.width) + 48.dp
    Box(Modifier.fillMaxWidth().height(previewHeight)
        .onSizeChanged { measuredSize = it }
        .testTag("game_display_region_preview")
        .pointerInput(enabled, geometry.width, geometry.height, padding) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                val panel = displayPreviewRect(size.width.toFloat(), size.height.toFloat(), geometry, padding)
                val corner = DisplayRegionCorner.entries.minByOrNull {
                    (cornerPosition(latestRegion, it, panel) - down.position).getDistance()
                } ?: return@awaitEachGesture
                if ((cornerPosition(latestRegion, corner, panel) - down.position).getDistance() > touchRadius) {
                    return@awaitEachGesture
                }
                down.consume()
                activeCorner = corner
                var dragging = latestRegion
                try {
                    drag(down.id) { change ->
                        val delta = change.positionChange()
                        dragging = dragging.moveCorner(corner,
                            (if (corner.isLeft) dragging.left else dragging.right) + delta.x / panel.width,
                            (if (corner.isTop) dragging.top else dragging.bottom) + delta.y / panel.height)
                        latestChange(dragging)
                        change.consume()
                    }
                } finally { activeCorner = null }
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val panel = displayPreviewRect(size.width, size.height, geometry, padding)
            // A thin chassis gives depth without inventing a different screen aspect ratio.
            drawRoundRect(Color.Black.copy(alpha = .25f), Offset(panel.left - 5, panel.top + 3),
                Size(panel.width + 10, panel.height + 7), CornerRadius(10.dp.toPx()))
            drawRoundRect(Brush.linearGradient(listOf(Color(0xFF8B929B), Color(0xFF222730), Color(0xFF626973))),
                Offset(panel.left - 4, panel.top - 4), Size(panel.width + 8, panel.height + 8), CornerRadius(8.dp.toPx()))
            val factor = panel.width / geometry.width
            val radii = geometry.cornerRadii.map { CornerRadius(it * factor) }
            val screenPath = Path().apply {
                addRoundRect(RoundRect(panel.left, panel.top, panel.right, panel.bottom,
                    radii[0], radii[1], radii[2], radii[3]))
            }
            val selected = Rect(panel.left + region.left * panel.width, panel.top + region.top * panel.height,
                panel.left + region.right * panel.width, panel.top + region.bottom * panel.height)
            clipPath(screenPath) {
                drawRect(Color(0xFF030507), panel.topLeft, panel.size)
                clipRect(selected.left, selected.top, selected.right, selected.bottom) {
                    drawGameScene(selected, gameLabel)
                }
                if (geometry.cutoutPath != null) {
                    translate(panel.left, panel.top) {
                        scale(factor, factor, Offset.Zero) { drawPath(geometry.cutoutPath.asComposePath(), Color.Black) }
                    }
                } else geometry.cutoutBounds.forEach { cutout ->
                    // Before API 31 the system exposes a bounding box, not the exact contour.
                    drawRoundRect(Color.Black,
                        Offset(panel.left + cutout.left * factor, panel.top + cutout.top * factor),
                        Size(cutout.width * factor, cutout.height * factor),
                        CornerRadius(minOf(cutout.width, cutout.height) * factor / 2))
                }
            }
            drawRect(primary, selected.topLeft, selected.size, style = Stroke(1.5.dp.toPx()))
            DisplayRegionCorner.entries.forEach { corner ->
                val center = cornerPosition(region, corner, panel)
                drawCircle(primary.copy(alpha = if (activeCorner == corner) .30f else .14f), 17.dp.toPx(), center)
                drawCircle(Color(0xFF11151B), 8.dp.toPx(), center)
                drawCircle(primary, 6.dp.toPx(), center)
                drawCircle(Color.White, 2.dp.toPx(), center)
            }
        }
        // Separate accessible handles also provide deterministic test targets.
        DisplayRegionCorner.entries.forEachIndexed { index, corner ->
            val point = cornerPosition(region, corner, screen)
            Box(Modifier.offset { IntOffset((point.x - handleSize / 2).roundToInt(), (point.y - handleSize / 2).roundToInt()) }
                .size(48.dp).testTag("display_region_${corner.name.lowercase()}")
                .semantics {
                    contentDescription = cornerNames[index]
                    customActions = listOf(Offset(-.01f, 0f), Offset(.01f, 0f), Offset(0f, -.01f), Offset(0f, .01f))
                        .mapIndexed { direction, delta -> CustomAccessibilityAction(moveNames[direction]) {
                            if (!enabled) false else {
                                latestChange(latestRegion.moveCorner(corner,
                                    (if (corner.isLeft) latestRegion.left else latestRegion.right) + delta.x,
                                    (if (corner.isTop) latestRegion.top else latestRegion.bottom) + delta.y))
                                true
                            }
                        } }
                }.focusable(enabled))
        }
    }
    }
}

private fun displayPreviewRect(width: Float, height: Float, geometry: DeviceDisplayGeometry, padding: Float): Rect {
    val factor = minOf((width - padding * 2).coerceAtLeast(1f) / geometry.width,
        (height - padding * 2).coerceAtLeast(1f) / geometry.height)
    val w = geometry.width * factor
    val h = geometry.height * factor
    return Rect((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
}

private fun cornerPosition(region: GameDisplayRegion, corner: DisplayRegionCorner, panel: Rect) = Offset(
    panel.left + (if (corner.isLeft) region.left else region.right) * panel.width,
    panel.top + (if (corner.isTop) region.top else region.bottom) * panel.height,
)

/** Small illustrative game scene; hardware geometry and the selected viewport remain exact. */
private fun DrawScope.drawGameScene(viewport: Rect, label: String) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF21394A), Color(0xFF62756A), Color(0xFF242A2A)),
        viewport.top, viewport.bottom), viewport.topLeft, viewport.size)
    val w = viewport.width
    val h = viewport.height
    fun point(x: Float, y: Float) = Offset(viewport.left + x * w, viewport.top + y * h)
    val mountain = Path().apply {
        moveTo(viewport.left, viewport.bottom)
        listOf(0f to .65f, .14f to .32f, .28f to .63f, .43f to .18f, .56f to .58f,
            .73f to .26f, 1f to .66f, 1f to 1f).forEach { (x, y) -> val p = point(x, y); lineTo(p.x, p.y) }
        close()
    }
    drawPath(mountain, Color(0xFF263B3D))
    drawRect(Color(0xFF172329), point(.48f, .20f), Size(w * .04f, h * .48f))
    drawRect(Color(0xFF10191B), point(0f, .68f), Size(w, h * .32f))
    drawRect(Color(0xFF11151A).copy(alpha = .85f), viewport.topLeft, Size(w, h * .10f))
    repeat(5) { i ->
        drawCircle(if (i == 0) Color(0xFFE08775) else Color(0xFFBEAF75), h * .019f, point(.04f + i * .055f, .05f))
    }
    // Settings mark at the real top-right edge, plus cards close to the lower edge.
    drawCircle(Color(0xFFDED7BA), h * .026f, point(.96f, .05f), style = Stroke(h * .009f))
    repeat(5) { i ->
        val card = point(.32f + i * .075f, .75f + kotlin.math.abs(2 - i) * .018f)
        drawRoundRect(Color(0xFFAD9B6C), card, Size(w * .065f, h * .22f), CornerRadius(h * .014f))
        drawRoundRect(if (i % 2 == 0) Color(0xFF6E3934) else Color(0xFF365368),
            card + Offset(w * .004f, h * .008f), Size(w * .057f, h * .14f), CornerRadius(h * .01f))
        drawLine(Color(0xFF52483C), card + Offset(w * .01f, h * .17f),
            card + Offset(w * .05f, h * .17f), h * .01f, StrokeCap.Round)
    }
    drawCircle(Color(0xFF8FB3BA), h * .054f, point(.065f, .88f))
    drawRoundRect(Color(0xFF7E7844), point(.87f, .83f), Size(w * .10f, h * .09f), CornerRadius(h * .02f))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(236, 230, 208)
        textAlign = Paint.Align.CENTER
        textSize = h * .065f
        setShadowLayer(h * .012f, 0f, h * .006f, android.graphics.Color.BLACK)
    }
    drawContext.canvas.nativeCanvas.drawText(label, viewport.center.x, viewport.top + h * .43f, paint)
}

package io.stamethyst.ui.settings.sections

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.stamethyst.BootOverlayArtBackground
import io.stamethyst.R
import io.stamethyst.config.BootOverlayAnimation
import io.stamethyst.config.BootOverlayImageConfig
import io.stamethyst.config.BootOverlayStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import io.stamethyst.ui.loading.BootLoadingAnimation

/** Decorative simulations only: no JVM, WebView, or game session is started here. */
@Composable
internal fun BootOverlayStyleAnimatedPreview(
    style: BootOverlayStyle,
    imageConfig: BootOverlayImageConfig,
    loadingAnimation: BootOverlayAnimation,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "boot_style_preview")
    val clock = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing)),
        label = "preview_launch_cycle",
    )
    // A fixed miniature viewport keeps all five previews legible and proportional.
    // Semantics belong to the option label, not to the simulated progress/log output.
    Layout(
        modifier = modifier.clip(RoundedCornerShape(6.dp)).clearAndSetSemantics {},
        content = {
            PreviewScene(style, imageConfig, loadingAnimation, clock)
        },
    ) { measurables, constraints ->
        val width = 480.dp.roundToPx()
        val height = 270.dp.roundToPx()
        val child = measurables.single().measure(Constraints.fixed(width, height))
        val outputWidth = width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val outputHeight = height.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(outputWidth, outputHeight) {
            child.placeWithLayer(0, 0) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = outputWidth.toFloat() / width
                scaleY = outputHeight.toFloat() / height
            }
        }
    }
}

@Composable
private fun PreviewScene(
    style: BootOverlayStyle,
    imageConfig: BootOverlayImageConfig,
    loadingAnimation: BootOverlayAnimation,
    clock: State<Float>,
) {
    val cycle by clock
    // Hold a complete frame before replaying, rather than running progress backwards.
    val progress = (cycle / 0.86f).coerceIn(0f, 1f)
    val status = stringResource(
        when {
            progress < 0.20f -> R.string.boot_overlay_status_starting_jvm
            progress < 0.55f -> R.string.boot_overlay_stage_injecting_patches
            progress < 0.85f -> R.string.boot_overlay_stage_initializing_mods
            progress < 1f -> R.string.boot_overlay_stage_starting_game_entry
            else -> R.string.boot_overlay_status_game_frame_ready
        }
    )
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.surface)) {
        when (style) {
            BootOverlayStyle.MODERN -> {
                BootOverlayArtBackground(imageConfig, progress, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.12f),
                    0.5f to Color.Black.copy(alpha = 0.12f),
                    1f to Color.Black.copy(alpha = 0.92f),
                )))
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PreviewTitle(Color.White)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PreviewText(status, Color.White.copy(alpha = 0.88f), Modifier.weight(1f))
                        PreviewText("${(progress * 100).toInt()}%", Color.White)
                    }
                    PreviewProgress(progress, gradient = true)
                }
            }
            BootOverlayStyle.LEGACY -> {
                Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(
                        Modifier.weight(0.42f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        PreviewTitle(colors.onSurface)
                        BootLoadingAnimation(loadingAnimation, Modifier.size(104.dp))
                        PreviewProgress(progress, gradient = true)
                        Spacer(Modifier.height(8.dp))
                        PreviewText("${(progress * 100).toInt()}%", colors.primary)
                        PreviewText(status, colors.onSurfaceVariant)
                    }
                    Column(
                        Modifier.weight(0.58f).fillMaxHeight()
                            .background(colors.surfaceVariant.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                            .border(1.dp, colors.outlineVariant.copy(alpha = 0.52f), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PreviewText(stringResource(R.string.boot_overlay_logs_title), colors.onSurface)
                        PreviewLogs(progress, colors.onSurfaceVariant, Modifier.weight(1f))
                    }
                }
            }
            BootOverlayStyle.CLASSIC_LOG -> {
                // The real classic overlay is translucent black, not a Material card.
                Box(Modifier.fillMaxSize().background(Color(0xCC000000)))
                Column(
                    Modifier.align(Alignment.Center).padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PreviewTitle(Color.White)
                    PreviewProgress(progress)
                    PreviewText(stringResource(R.string.boot_overlay_status_with_progress, status, (progress * 100).toInt()), Color.White)
                    PreviewText(stringResource(R.string.boot_overlay_logs_title), Color.White, Modifier.fillMaxWidth())
                    PreviewLogs(progress, Color.White, Modifier.fillMaxWidth().height(105.dp).background(Color(0x22000000)).padding(8.dp))
                }
            }
            BootOverlayStyle.MATERIAL_LOG -> {
                Column(
                    Modifier.fillMaxSize().padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PreviewTitle(colors.onSurface)
                    PreviewProgress(progress)
                    PreviewText(stringResource(R.string.boot_overlay_status_with_progress, status, (progress * 100).toInt()), colors.onSurfaceVariant)
                    // Match MaterialBootLogPane: plain monospace output, no invented card chrome.
                    PreviewLogs(progress, colors.onSurfaceVariant, Modifier.fillMaxWidth().weight(1f))
                }
            }
            BootOverlayStyle.SLING_BREAK -> SlingBreakPreview(progress, cycle, status)
        }
    }
}

@Composable
private fun PreviewTitle(color: Color) {
    Text(stringResource(R.string.boot_overlay_title_starting), color = color,
        fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
}

@Composable
private fun PreviewText(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, modifier, color = color, fontSize = 18.sp, lineHeight = 23.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun PreviewProgress(progress: Float, gradient: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(99.dp))
        .background(colors.primaryContainer.copy(alpha = 0.42f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(
            if (gradient) Brush.horizontalGradient(listOf(colors.primary, colors.tertiary, colors.secondary))
            else Brush.horizontalGradient(listOf(colors.primary, colors.primary))
        ))
    }
}

@Composable
private fun PreviewLogs(progress: Float, color: Color, modifier: Modifier = Modifier) {
    val lines = listOf(
        stringResource(R.string.boot_overlay_logs_placeholder),
        stringResource(R.string.boot_overlay_stage_jvm_bootstrapped),
        stringResource(R.string.boot_overlay_stage_begin_patching),
        stringResource(R.string.boot_overlay_stage_finding_patches),
        stringResource(R.string.boot_overlay_stage_injecting_patches),
        stringResource(R.string.boot_overlay_stage_compiling_patched_classes),
        stringResource(R.string.boot_overlay_stage_initializing_mods),
        stringResource(R.string.boot_overlay_stage_starting_game_entry),
        stringResource(R.string.boot_overlay_status_game_frame_ready),
    )
    val position = progress * (lines.size - 1)
    val last = position.toInt()
    Box(modifier.clip(RoundedCornerShape(2.dp))) {
        Column(Modifier.graphicsLayer {
            translationY = if (last >= 4) -(position - last) * 25.dp.toPx() else 0f
        }) {
            for (index in (last - 4).coerceAtLeast(0)..last) {
                Text(lines[index], color = color.copy(alpha = if (index == last) 1f else 0.65f),
                    fontFamily = FontFamily.Monospace, fontSize = 16.sp, lineHeight = 25.sp,
                    maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
    }
}

@Composable
private fun SlingBreakPreview(progress: Float, cycle: Float, status: String) {
    // Colors and geometry mirror assets/slingbreak/{launcher-mode.css,render.js}.
    val ink = Color(0xFF293525)
    val green = Color(0xFF85B64D)
    Column(Modifier.fillMaxSize().background(Color(0xFFF5F6F3))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PreviewText(status, ink, Modifier.weight(1f))
                    PreviewText("${(progress * 100).toInt()}%", ink)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFFDFE4DA))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(green))
                }
            }
            if (progress >= 1f) {
                // Deliberately not clickable: tapping anywhere still selects this style.
                PreviewText(stringResource(R.string.main_launch_game), Color(0xFFC4F589),
                    Modifier.background(ink, RoundedCornerShape(6.dp)).padding(10.dp))
            }
        }
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val sx = size.width / 480f
            val sy = size.height / 200f
            fun point(x: Float, y: Float) = Offset(x * sx, y * sy)
            fun line(x: Float, y: Float, tx: Float, ty: Float, color: Color, width: Float) =
                drawLine(color, point(x, y), point(tx, ty), width * sx, StrokeCap.Round)
            val shot = (cycle * 4f) % 1f
            val flight = ((shot - 0.25f) / 0.4f).coerceIn(0f, 1f)
            val impact = ((shot - 0.65f) / 0.35f).coerceIn(0f, 1f)
            drawRect(Color(0xFFCCD4C4), size = Size(size.width, sy))
            repeat(3) { row ->
                repeat(8) { col ->
                    if (!(row == 2 && col == 5 && impact > 0f)) {
                        val fill = when {
                            row == 0 && col == 2 -> Color(0xFFEAC66A)
                            row == 1 && col == 5 -> Color(0xFFB4DCE6)
                            row == 2 && col == 1 -> Color(0xFFEEAC98)
                            else -> Color(0xFFD4E7B5)
                        }
                        drawRoundRect(fill, point(62f + col * 45f, 18f + row * 26f),
                            Size(38f * sx, 19f * sy), androidx.compose.ui.geometry.CornerRadius(3f * sx))
                        line(77f + col * 45f, 27f + row * 26f, 85f + col * 45f, 27f + row * 26f, Color(0xFF75944F), 1f)
                    }
                }
            }
            val origin = point(240f, 162f)
            drawCircle(Color(0xFFEEF2E5), 26f * sy, origin)
            drawCircle(Color(0xFFCCDAB9), 34f * sy, origin, style = Stroke(sx))
            line(240f, 187f, 240f, 168f, ink, 6f)
            line(240f, 168f, 226f, 150f, ink, 5f)
            line(240f, 168f, 254f, 150f, ink, 5f)
            val pull = if (shot < 0.25f) shot / 0.25f * 12f else 0f
            line(226f, 150f, 240f, 156f + pull, green, 2f)
            line(254f, 150f, 240f, 156f + pull, green, 2f)
            if (shot < 0.65f) {
                val x = 240f + 65f * flight
                val y = 154f + pull - 75f * flight
                if (shot < 0.25f) repeat(7) { i ->
                    drawCircle(green.copy(alpha = 0.4f), 1.5f * sx, point(249f + i * 8f, 140f - i * 9f))
                }
                rotate(41f, point(x, y)) {
                    line(x, y + 10f, x, y - 10f, ink, 2f)
                    line(x, y - 10f, x - 4f, y - 4f, ink, 2f)
                    line(x, y - 10f, x + 4f, y - 4f, ink, 2f)
                }
            } else {
                repeat(8) { i ->
                    val angle = i * PI.toFloat() / 4f
                    drawCircle(green.copy(alpha = 1f - impact), (3f - 2f * impact) * sx,
                        point(305f + cos(angle) * impact * 38f, 79f + sin(angle) * impact * 30f))
                }
            }
        }
    }
}

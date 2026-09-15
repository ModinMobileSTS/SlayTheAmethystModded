package io.stamethyst.ui.main

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Lucide 0.468.0 geometry from the canonical prototype. See assets/licenses/lucide.txt.
// SVG rounded rectangles/circles are converted to equivalent elliptical-arc paths.
internal object RendererIcons {
    private fun icon(name: String, stroke: Float = 2f, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { data ->
                addPath(
                    pathData = PathParser().parsePathString(data).toNodes(),
                    stroke = SolidColor(Color.Black), strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private val cpuPaths = arrayOf(
        "M6 4H18A2 2 0 0 1 20 6V18A2 2 0 0 1 18 20H6A2 2 0 0 1 4 18V6A2 2 0 0 1 6 4Z",
        "M10 9H14A1 1 0 0 1 15 10V14A1 1 0 0 1 14 15H10A1 1 0 0 1 9 14V10A1 1 0 0 1 10 9Z",
        "M15 2v2", "M15 20v2", "M2 15h2", "M2 9h2", "M20 15h2", "M20 9h2", "M9 2v2", "M9 20v2",
    )
    val Cpu = icon("Cpu", 1.8f, *cpuPaths)
    val CpuBackground = icon("CpuBackground", 1f, *cpuPaths)
    val LayersBackground = icon("Layers2", 1f,
        "m16.02 12 5.48 3.13a1 1 0 0 1 0 1.74L13 21.74a2 2 0 0 1-2 0l-8.5-4.87a1 1 0 0 1 0-1.74L7.98 12",
        "M13 13.74a2 2 0 0 1-2 0L2.5 8.87a1 1 0 0 1 0-1.74L11 2.26a2 2 0 0 1 2 0l8.5 4.87a1 1 0 0 1 0 1.74Z",
    )
    val Switch = icon("ArrowLeftRight", 2f, "M8 3 4 7l4 4", "M4 7h16", "m16 21 4-4-4-4", "M20 17H4")
    val SwitchTile = icon("ArrowLeftRightTile", 1.8f, "M8 3 4 7l4 4", "M4 7h16", "m16 21 4-4-4-4", "M20 17H4")
    val Arrow = icon("ArrowRight", 2f, "M5 12h14", "m12 5 7 7-7 7")
    val Check = icon("Check", 2.5f, "M20 6 9 17l-5-5")
    val Clock = icon("Clock3", 2f, "M22 12A10 10 0 1 1 2 12A10 10 0 1 1 22 12Z", "M12 6 12 12 16.5 12")
    val Restore = icon("RotateCcw", 2f, "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")
    val Close = icon("X", 2f, "M18 6 6 18", "m6 6 12 12")
}

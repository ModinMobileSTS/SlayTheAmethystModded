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

    // Same builder, but each path carries its own stroke width so a nested form
    // can sit lighter than the outline it lives in.
    private fun strokeIcon(name: String, vararg paths: Pair<Float, String>): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { (stroke, data) ->
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
    val RenderLayer = icon("RenderLayer", 0.8f,
        "M13 17.74a2 2 0 0 1-2 0L2.5 12.87a1 1 0 0 1 0-1.74L11 6.26a2 2 0 0 1 2 0l8.5 4.87a1 1 0 0 1 0 1.74Z",
    )
    val Switch = icon("ArrowLeftRight", 2f, "M8 3 4 7l4 4", "M4 7h16", "m16 21 4-4-4-4", "M20 17H4")
    val SwitchTile = icon("ArrowLeftRightTile", 1.8f, "M8 3 4 7l4 4", "M4 7h16", "m16 21 4-4-4-4", "M20 17H4")
    val Arrow = icon("ArrowRight", 2f, "M5 12h14", "m12 5 7 7-7 7")
    val Check = icon("Check", 2.5f, "M20 6 9 17l-5-5")
    val Clock = icon("Clock3", 2f, "M22 12A10 10 0 1 1 2 12A10 10 0 1 1 22 12Z", "M12 6 12 12 16.5 12")
    val Restore = icon("RotateCcw", 2f, "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")
    val Close = icon("X", 2f, "M18 6 6 18", "m6 6 12 12")
    val Gamepad2 = icon(
        "Gamepad2",
        2f,
        "M6 11h4",
        "M8 9v4",
        "M15 12h0.01",
        "M18 10h0.01",
        "M17.32 5H6.68a4 4 0 0 0-3.978 3.59c-0.006 0.052-0.01 0.101-0.017 0.152C2.604 9.416 2 14.456 2 16a3 3 0 0 0 3 3c1 0 1.5-0.5 2-1l1.414-1.414A2 2 0 0 1 9.828 16h4.344a2 2 0 0 1 1.414 0.586L17 18c0.5 0.5 1 1 2 1a3 3 0 0 0 3-3c0-1.545-0.604-6.584-0.685-7.258-0.007-0.05-0.011-0.1-0.017-0.151A4 4 0 0 0 17.32 5z",
    )
    val Package = icon(
        "Package",
        2f,
        "M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73z",
        "M12 22V12",
        "M3.29 7 12 12 20.71 7",
        "m7.5 4.27 9 5.15",
    )
    val Store = icon(
        "Store",
        2f,
        "M15 21v-5a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v5",
        "M17.774 10.31a1.12 1.12 0 0 0-1.549 0 2.5 2.5 0 0 1-3.451 0 1.12 1.12 0 0 0-1.548 0 2.5 2.5 0 0 1-3.452 0 1.12 1.12 0 0 0-1.549 0 2.5 2.5 0 0 1-3.77-3.248l2.889-4.184A2 2 0 0 1 7 2h10a2 2 0 0 1 1.653 0.873l2.895 4.192a2.5 2.5 0 0 1-3.774 3.244",
        "M4 10.95V19a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8.05",
    )
    val Settings2 = icon(
        "Settings2",
        2f,
        "M14 17H5",
        "M19 7h-9",
        "M17 20a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
        "M7 10a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
    )

    // Three peer nodes joined through a shared bus. Replaces the chain-link glyph
    // for the virtual LAN card, which read as "hyperlink" rather than "network".
    val Network = icon(
        "Network",
        2f,
        "M17 16H21A1 1 0 0 1 22 17V21A1 1 0 0 1 21 22H17A1 1 0 0 1 16 21V17A1 1 0 0 1 17 16Z",
        "M3 16H7A1 1 0 0 1 8 17V21A1 1 0 0 1 7 22H3A1 1 0 0 1 2 21V17A1 1 0 0 1 3 16Z",
        "M10 2H14A1 1 0 0 1 15 3V7A1 1 0 0 1 14 8H10A1 1 0 0 1 9 7V3A1 1 0 0 1 10 2Z",
        "M5 16v-3a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v3M12 12V8",
    )

    // Ribbon, disc and a small tick. Replaces the Material Symbols rosette so the
    // achievement card matches the stroke weight of the launcher dock icons.
    val Medal = icon(
        "Medal",
        2f,
        "M7.21 15 2.66 7.14a2 2 0 0 1 .13-2.2L4.4 2.8A2 2 0 0 1 6 2h12a2 2 0 0 1 1.6.8l1.6 2.14a2 2 0 0 1 .14 2.2L16.79 15M11 12 5.12 2.2M13 12 18.88 2.2M8 7h8",
        "M17 17A5 5 0 1 1 7 17A5 5 0 1 1 17 17Z",
        "M12 18v-2h-.5",
    )

    // Scan-line corner brackets around a wireframe cube: a render viewport framing
    // geometry. Deliberately not a bare box, which is already the mods Package icon.
    // The cube is the Lucide box scaled to 0.55 and centred at (12, 12).
    val RenderViewport = strokeIcon(
        "RenderViewport",
        2f to "M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2",
        1.65f to
            "M16.95 9.8A1.1 1.1 0 0 0 16.4 8.849L12.55 6.649A1.1 1.1 0 0 0 11.45 6.649L7.6 8.849A1.1 1.1 0 0 0 7.05 9.8L7.05 14.2A1.1 1.1 0 0 0 7.6 15.152L11.45 17.352A1.1 1.1 0 0 0 12.55 17.352L16.4 15.152A1.1 1.1 0 0 0 16.95 14.2ZM7.215 9.25L12 12L16.785 9.25M12 17.5L12 12",
    )
}

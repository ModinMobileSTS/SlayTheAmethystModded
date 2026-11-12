package io.stamethyst.backend.diag.refresh

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.ceil

@Serializable
internal data class RefreshRateObservation(
    val elapsedMs: Long,
    val reason: String,
    val displayId: Int?,
    val appRateHz: Float?,
    val modeId: Int?,
    val modeRateHz: Float?,
    val resolution: String,
    val preferredModeId: Int,
    val preferredRateHz: Float,
    val focused: Boolean,
    val powerSave: Boolean?,
    val thermalStatus: Int?,
)

@Serializable
internal data class RefreshRateFrameStats(
    val intervals: Int = 0,
    val callbackRateHz: Double = 0.0,
    val medianMs: Double = 0.0,
    val p95Ms: Double = 0.0,
    val maxMs: Double = 0.0,
    val slowIntervalPercent: Double = 0.0,
) {
    companion object {
        // Use callback arrival times, not Choreographer's scheduled frameTimeNanos: delayed
        // callbacks must not be mistaken for frames actually delivered at the target rate.
        fun fromIntervals(intervalsNs: List<Long>, targetHz: Float): RefreshRateFrameStats {
            val values = intervalsNs.filter { it > 0 }.map { it / 1_000_000.0 }.sorted()
            if (values.isEmpty()) return RefreshRateFrameStats()
            val middle = values.size / 2
            val median = if (values.size % 2 == 0) (values[middle - 1] + values[middle]) / 2 else values[middle]
            val budgetMs = 1000.0 / targetHz.coerceAtLeast(1f)
            return RefreshRateFrameStats(
                intervals = values.size,
                callbackRateHz = 1000.0 / values.average(),
                medianMs = median,
                p95Ms = values[ceil(values.size * .95).toInt().coerceAtLeast(1) - 1],
                maxMs = values.last(),
                slowIntervalPercent = 100.0 * values.count { it > budgetMs * 1.5 } / values.size,
            )
        }
    }
}

internal enum class RefreshRatePhase(val key: String) {
    BASELINE("baseline"), MODE("highest_mode"), WINDOW("window_120"),
    SURFACE("surface_view_120"), TEXTURE("texture_view_window_120"),
}

@Serializable
internal data class RefreshRateStage(
    val phase: String,
    val targetHz: Float,
    val durationMs: Long,
    val completed: Boolean,
    val request: String,
    val requestError: String?,
    val observations: List<RefreshRateObservation>,
    val frames: RefreshRateFrameStats,
    val drawCount: Int,
    val drawErrors: Int,
    val lastDrawError: String?,
    val probeSize: String,
    val events: List<String>,
) {
    // Include the machine-readable conclusion in exported JSON, not only localized report.txt.
    val outcomeCode: String = outcome().name

    fun outcome(): RefreshRateOutcome {
        if (!completed) return RefreshRateOutcome.INTERRUPTED
        if (requestError != null) return RefreshRateOutcome.REQUEST_FAILED
        if (frames.intervals < 30 || drawCount < 30 || drawErrors > 0) return RefreshRateOutcome.INSUFFICIENT
        val rates = observations.filter { it.reason == "sample" }.mapNotNull { it.appRateHz?.takeIf { rate -> rate.isFinite() && rate > 0f } }
        if (rates.size < 5) return RefreshRateOutcome.INSUFFICIENT
        if (observations.filter { it.reason == "sample" }.any { !it.focused }) return RefreshRateOutcome.INSUFFICIENT
        val threshold = targetHz - 1f // Accept e.g. 119.88 Hz without rounding observations.
        val reached = rates.count { it >= threshold }
        if (reached >= ceil(rates.size * .8).toInt()) {
            return if (frames.callbackRateHz >= targetHz * .9) RefreshRateOutcome.OBSERVED
            else RefreshRateOutcome.CALLBACKS_SLOW
        }
        if (reached > 0) return RefreshRateOutcome.VARIABLE
        return RefreshRateOutcome.BELOW_TARGET
    }
}

internal enum class RefreshRateOutcome { OBSERVED, CALLBACKS_SLOW, BELOW_TARGET, VARIABLE, REQUEST_FAILED, INSUFFICIENT, INTERRUPTED }

@Serializable
internal data class RefreshRateReport(
    val schemaVersion: Int = 1,
    val startedAt: String,
    val finishedAt: String,
    val interruption: String?,
    val environment: Map<String, String>,
    val stages: List<RefreshRateStage>,
    val cleanup: String,
)

internal object RefreshRateReportFormat {
    val json = Json { prettyPrint = true; encodeDefaults = true }
    fun json(report: RefreshRateReport): String = json.encodeToString(report)

    fun text(report: RefreshRateReport, phases: Map<String, String>, outcomes: Map<RefreshRateOutcome, String>,
             scope: String, nextStep: String): String = buildString {
        appendLine("刷新率诊断 / Refresh rate diagnostics (schema ${report.schemaVersion})")
        appendLine("${report.startedAt} → ${report.finishedAt}")
        appendLine(scope)
        appendLine("\n${report.interruption?.let { "INTERRUPTED: $it" } ?: "COMPLETED"}")
        appendLine("\n概览 / Summary")
        report.stages.forEach { stage ->
            appendLine("${phases[stage.phase] ?: stage.phase}: ${outcomes[stage.outcome()]}")
            val samples = stage.observations.filter { it.reason == "sample" }
            val rates = samples.mapNotNull { it.appRateHz }
            appendLine("  appHz=${rates.minOrNull()}..${rates.maxOrNull()}; modeHz=${samples.mapNotNull { it.modeRateHz }.distinct().joinToString()}")
            appendLine("  target=${stage.targetHz} Hz; callback=${number(stage.frames.callbackRateHz)} Hz; " +
                "median=${number(stage.frames.medianMs)} ms; p95=${number(stage.frames.p95Ms)} ms")
        }
        appendLine("\n设备与配置 / Environment")
        report.environment.forEach { (key, value) -> appendLine("$key=$value") }
        report.stages.forEach { stage ->
            appendLine("\n--- ${phases[stage.phase] ?: stage.phase} [${stage.phase}] ---")
            appendLine("targetHz=${stage.targetHz}; completed=${stage.completed}; durationMs=${stage.durationMs}")
            appendLine("request=${stage.request}")
            appendLine("requestError=${stage.requestError ?: "none"}")
            appendLine("probeSize=${stage.probeSize}; draws=${stage.drawCount}; drawErrors=${stage.drawErrors}")
            appendLine("lastDrawError=${stage.lastDrawError ?: "none"}")
            appendLine("intervals=${stage.frames.intervals}; callbackHz=${number(stage.frames.callbackRateHz)}; " +
                "medianMs=${number(stage.frames.medianMs)}; p95Ms=${number(stage.frames.p95Ms)}; " +
                "maxMs=${number(stage.frames.maxMs)}; slowIntervalsPercent=${number(stage.frames.slowIntervalPercent)}")
            appendLine("显示观测 / Display observations (elapsed ms from test start)")
            stage.observations.forEach { sample ->
                appendLine("${sample.elapsedMs} ${sample.reason}: display=${sample.displayId}; appHz=${sample.appRateHz}; " +
                    "mode=${sample.modeId}@${sample.modeRateHz}; size=${sample.resolution}; " +
                    "windowMode=${sample.preferredModeId}; windowHz=${sample.preferredRateHz}; " +
                    "focus=${sample.focused}; powerSave=${sample.powerSave}; thermal=${sample.thermalStatus}")
            }
            stage.events.forEach { appendLine("event=$it") }
        }
        appendLine("\n恢复 / Cleanup: ${report.cleanup}")
        appendLine("\n$nextStep")
    }

    private fun number(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
}

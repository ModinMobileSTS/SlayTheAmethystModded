package io.stamethyst

import android.os.SystemClock
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.stamethyst.config.RuntimePaths
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

/**
 * Manages the boot overlay UI: progress bar, status text, and dismiss button.
 */
class BootOverlayController(
    private val activity: StsGameActivity,
    private val waitForMainMenu: Boolean,
    private val manualDismissBootOverlay: Boolean,
    private val useTextureViewSurface: Boolean,
    private val onDismissed: () -> Unit,
    private val onRequestEarlyDismiss: () -> Unit,
    private val onSignalMainMenuReady: (String) -> Unit,
    private val onSignalLaunchFailure: (String) -> Unit
) {
    companion object {
        private const val BOOT_OVERLAY_MIN_VISIBLE_MS = 1200L
        private const val BOOT_OVERLAY_READY_DELAY_MS = 700L
        private const val JVM_LOG_POLL_INTERVAL_MS = 360L
        private const val JVM_LOG_MAX_TAIL_BYTES = 32 * 1024
        private const val JVM_LOG_MAX_LINES = 100
        private const val JVM_LOG_STAGE_SCAN_MAX_BYTES = 64 * 1024
        private const val MAX_STATUS_LINE_LENGTH = 180
    }

    private enum class BootLogStage(
        val progress: Int,
        @param:StringRes val fallbackStatusResId: Int,
        vararg keywords: String
    ) {
        NONE(0, 0, ""),
        JVM_BOOTSTRAPPED(
            30,
            R.string.boot_overlay_stage_jvm_bootstrapped,
            "registered forkandexec",
            "launched using jre 51",
            "jre 51 exists"
        ),
        MTS_BEGIN_PATCHING(34, R.string.boot_overlay_stage_begin_patching, "begin patching"),
        MTS_PATCH_ENUMS(
            40,
            R.string.boot_overlay_stage_patching_enums,
            "patching enums",
            "busting enums",
            "bust enums",
            "enumbuster"
        ),
        MTS_FIND_CORE_PATCHES(46, R.string.boot_overlay_stage_finding_core_patches, "finding core patches"),
        MTS_FIND_PATCHES(54, R.string.boot_overlay_stage_finding_patches, "finding patches..."),
        MTS_PATCH_OVERRIDES(60, R.string.boot_overlay_stage_patching_overrides, "patching overrides"),
        MTS_INJECT_PATCHES(68, R.string.boot_overlay_stage_injecting_patches, "injecting patches"),
        MTS_COMPILE_PATCHED_CLASSES(
            76,
            R.string.boot_overlay_stage_compiling_patched_classes,
            "compiling patched classes"
        ),
        MTS_ADD_VERSION_TAG(
            84,
            R.string.boot_overlay_stage_adding_modthespire_version,
            "adding modthespire to version"
        ),
        MTS_INITIALIZING_MODS(92, R.string.boot_overlay_stage_initializing_mods, "initializing mods"),
        GAME_MAIN_BOOT(
            96,
            R.string.boot_overlay_stage_starting_game_entry,
            "cardcrawlgame.create",
            "cardcrawlgame create"
        );

        private val loweredKeywords = keywords.map { it.lowercase() }

        fun matches(loweredLine: String): Boolean {
            if (this == NONE) {
                return false
            }
            return loweredKeywords.any { keyword ->
                keyword.isNotEmpty() && loweredLine.contains(keyword)
            }
        }
    }

    private var bootOverlay: ComposeView? = null
    private var bootOverlayProgress = 0
    private var bootOverlayMessage = ""
    private var bootOverlayShownAtMs = -1L
    private var bootOverlayDismissed = false
    private var mainMenuReadySignaled = false
    private var launchFailureSignaled = false
    private var lastJvmLogLength = -1L
    private var lastJvmLogModifiedMs = -1L
    private var parsedJvmLogOffset = 0L
    private var parsedJvmLogRemainder = ""
    private var bootLogStage = BootLogStage.NONE
    private val defaultDismissButtonText = text(R.string.boot_overlay_button_close)
    private val readyDismissButtonText = text(R.string.boot_overlay_button_enter_game)
    private val jvmLogPlaceholderText = text(R.string.boot_overlay_logs_placeholder)

    private var overlayUiState by mutableStateOf(
        BootOverlayUiState(
            progress = 0,
            statusText = text(R.string.boot_overlay_status_starting_jvm),
            showDismissButton = false,
            dismissButtonEnabled = false,
            dismissButtonText = defaultDismissButtonText,
            jvmLogText = jvmLogPlaceholderText
        )
    )

    private val jvmLogPollRunnable = object : Runnable {
        override fun run() {
            pollJvmLogSnapshot()
            scheduleJvmLogPolling()
        }
    }

    @Volatile
    var earlyOverlayDismissOnNextFrame = false
        private set

    @Volatile
    var earlyOverlayDismissRequestFrameTimestampNs = 0L
        private set

    val isDismissed: Boolean get() = bootOverlayDismissed

    fun init() {
        bootOverlay = activity.findViewById(R.id.bootOverlay)
        if (bootOverlay == null) {
            return
        }
        bootOverlay?.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        bootOverlay?.setContent {
            BootOverlayPanel(
                uiState = overlayUiState,
                manualDismissBootOverlay = manualDismissBootOverlay,
                onDismissClick = {
                    if (!manualDismissBootOverlay || bootOverlayDismissed) {
                        return@BootOverlayPanel
                    }
                    updateProgress(
                        bootOverlayProgress.coerceAtLeast(99),
                        text(R.string.boot_overlay_status_manual_dismiss_requested)
                    )
                    dismiss()
                }
            )
        }

        overlayUiState = overlayUiState.copy(
            showDismissButton = manualDismissBootOverlay,
            dismissButtonEnabled = manualDismissBootOverlay,
            dismissButtonText = defaultDismissButtonText
        )

        bootOverlay?.visibility = View.VISIBLE

        if (!manualDismissBootOverlay) {
            bootOverlay?.setOnTouchListener { _, _ -> true }
        } else {
            bootOverlay?.setOnTouchListener(null)
        }

        bootOverlayShownAtMs = SystemClock.uptimeMillis()
        lastJvmLogLength = -1L
        lastJvmLogModifiedMs = -1L
        parsedJvmLogOffset = 0L
        parsedJvmLogRemainder = ""
        bootLogStage = BootLogStage.NONE
        scheduleJvmLogPolling(initial = true)

        if (manualDismissBootOverlay) {
            updateProgress(1, text(R.string.boot_overlay_status_starting_pipeline_manual))
        } else {
            updateProgress(1, text(R.string.boot_overlay_status_starting_pipeline))
        }
    }

    fun onDestroy() {
        stopJvmLogPolling()
        bootOverlay?.disposeComposition()
        bootOverlay = null
        earlyOverlayDismissOnNextFrame = false
    }

    fun updateProgress(percent: Int, message: String?) {
        val bounded = percent.coerceIn(0, 100)
        val normalizedMessage = message?.trim() ?: ""

        if (bounded < bootOverlayProgress) return
        if (bounded == bootOverlayProgress && normalizedMessage == bootOverlayMessage) return

        bootOverlayProgress = bounded
        bootOverlayMessage = normalizedMessage

        activity.runOnUiThread {
            if (bootOverlayDismissed || bootOverlay == null) return@runOnUiThread
            val nextStatus = if (normalizedMessage.isNotEmpty()) {
                format(
                    R.string.boot_overlay_status_with_progress,
                    normalizedMessage,
                    bootOverlayProgress
                )
            } else {
                overlayUiState.statusText
            }
            overlayUiState = overlayUiState.copy(
                progress = bootOverlayProgress,
                statusText = nextStatus
            )
        }
    }

    fun signalMainMenuReady(message: String) {
        if (!waitForMainMenu || mainMenuReadySignaled) return

        mainMenuReadySignaled = true

        if (manualDismissBootOverlay) {
            updateProgress(100, format(R.string.boot_overlay_status_tap_close_overlay, message))
            activity.runOnUiThread {
                overlayUiState = overlayUiState.copy(
                    dismissButtonText = readyDismissButtonText,
                    dismissButtonEnabled = true
                )
            }
            return
        }

        updateProgress(100, message)

        val now = SystemClock.uptimeMillis()
        val elapsed = if (bootOverlayShownAtMs <= 0L) BOOT_OVERLAY_MIN_VISIBLE_MS else (now - bootOverlayShownAtMs)
        val minDelay = (BOOT_OVERLAY_MIN_VISIBLE_MS - elapsed).coerceAtLeast(0)
        val delay = minDelay.coerceAtLeast(BOOT_OVERLAY_READY_DELAY_MS)

        activity.runOnUiThread {
            bootOverlay?.postDelayed({ dismiss() }, delay)
        }
    }

    fun signalLaunchFailure(detail: String) {
        if (launchFailureSignaled) return
        launchFailureSignaled = true

        val crashDetail = if (isOutOfMemoryFailure(detail)) {
            format(R.string.boot_overlay_oom_detail_with_reason, detail)
        } else {
            detail
        }
        onSignalLaunchFailure(crashDetail)
    }

    fun requestEarlyDismiss() {
        if (bootOverlayDismissed || bootOverlay == null) return

        if (manualDismissBootOverlay) {
            updateProgress(
                bootOverlayProgress.coerceAtLeast(98),
                text(R.string.boot_overlay_status_game_ready_tap_close)
            )
            activity.runOnUiThread {
                overlayUiState = overlayUiState.copy(
                    dismissButtonText = readyDismissButtonText,
                    dismissButtonEnabled = true
                )
            }
            return
        }

        if (useTextureViewSurface) {
            onRequestEarlyDismiss()
            return
        }
        dismiss()
    }

    fun dismiss() {
        if (bootOverlayDismissed || bootOverlay == null) return

        bootOverlayDismissed = true
        stopJvmLogPolling()
        earlyOverlayDismissOnNextFrame = false
        earlyOverlayDismissRequestFrameTimestampNs = 0L

        overlayUiState = overlayUiState.copy(showDismissButton = false)
        bootOverlay?.visibility = View.GONE

        onDismissed()
    }

    fun setEarlyDismissRequestTimestamp(timestampNs: Long) {
        earlyOverlayDismissRequestFrameTimestampNs = timestampNs
        earlyOverlayDismissOnNextFrame = true
    }

    fun onTextureFrameUpdate(currentTimestampNs: Long) {
        if (earlyOverlayDismissOnNextFrame &&
            currentTimestampNs > earlyOverlayDismissRequestFrameTimestampNs
        ) {
            earlyOverlayDismissOnNextFrame = false
            activity.runOnUiThread {
                updateProgress(
                    bootOverlayProgress.coerceAtLeast(99),
                    text(R.string.boot_overlay_status_game_frame_ready)
                )
                dismiss()
            }
        }
    }

    private fun isOutOfMemoryFailure(detail: String?): Boolean {
        if (detail == null || detail.isEmpty()) return false
        val lower = detail.lowercase()
        return lower.contains("outofmemoryerror") ||
            lower.contains("java heap space") ||
            lower.contains("gc overhead limit exceeded")
    }

    fun mapBootOverlayPreparationProgress(percent: Int): Int {
        val bounded = percent.coerceIn(0, 100)
        val ratio = bounded / 100f
        return 12 + ((24 - 12) * ratio).roundToInt()
    }

    private fun text(@StringRes resId: Int): String {
        return activity.getString(resId)
    }

    private fun format(@StringRes resId: Int, vararg args: Any): String {
        return activity.getString(resId, *args)
    }

    private fun scheduleJvmLogPolling(initial: Boolean = false) {
        val overlay = bootOverlay ?: return
        if (bootOverlayDismissed) return
        overlay.removeCallbacks(jvmLogPollRunnable)
        if (initial) {
            overlay.post(jvmLogPollRunnable)
        } else {
            overlay.postDelayed(jvmLogPollRunnable, JVM_LOG_POLL_INTERVAL_MS)
        }
    }

    private fun stopJvmLogPolling() {
        bootOverlay?.removeCallbacks(jvmLogPollRunnable)
    }

    private fun pollJvmLogSnapshot() {
        if (bootOverlayDismissed) {
            return
        }
        val logFile = RuntimePaths.latestLog(activity)
        val length = if (logFile.isFile) logFile.length() else -1L
        val modified = if (logFile.isFile) logFile.lastModified() else -1L
        if (length == lastJvmLogLength && modified == lastJvmLogModifiedMs) {
            return
        }
        lastJvmLogLength = length
        lastJvmLogModifiedMs = modified
        parseJvmLogStages(logFile, length)
        val snapshot = readTailLogText(logFile)
        if (snapshot == overlayUiState.jvmLogText) {
            return
        }
        overlayUiState = overlayUiState.copy(
            jvmLogText = snapshot
        )
    }

    private fun parseJvmLogStages(file: java.io.File, knownLength: Long) {
        if (!file.isFile || knownLength <= 0L) {
            return
        }
        if (parsedJvmLogOffset > knownLength) {
            parsedJvmLogOffset = 0L
            parsedJvmLogRemainder = ""
        }

        var startOffset = parsedJvmLogOffset
        var bytesToReadLong = knownLength - startOffset
        if (bytesToReadLong <= 0L) {
            return
        }
        if (bytesToReadLong > JVM_LOG_STAGE_SCAN_MAX_BYTES) {
            startOffset = knownLength - JVM_LOG_STAGE_SCAN_MAX_BYTES
            bytesToReadLong = JVM_LOG_STAGE_SCAN_MAX_BYTES.toLong()
            parsedJvmLogRemainder = ""
        }

        val bytesToRead = bytesToReadLong.toInt()
        if (bytesToRead <= 0) {
            return
        }

        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(startOffset)
                val buffer = ByteArray(bytesToRead)
                raf.readFully(buffer)
                parsedJvmLogOffset = startOffset + bytesToRead

                var chunkText = String(buffer, StandardCharsets.UTF_8)
                if (parsedJvmLogRemainder.isNotEmpty()) {
                    chunkText = parsedJvmLogRemainder + chunkText
                }

                val endsWithLineBreak = chunkText.endsWith("\n") || chunkText.endsWith("\r")
                val parts = chunkText.split('\n')
                val lines = if (endsWithLineBreak) {
                    parsedJvmLogRemainder = ""
                    parts
                } else {
                    parsedJvmLogRemainder = parts.lastOrNull() ?: ""
                    if (parts.isNotEmpty()) parts.dropLast(1) else emptyList()
                }

                for (line in lines) {
                    advanceProgressFromLogLine(line)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun advanceProgressFromLogLine(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) {
            return
        }
        val lowered = line.lowercase()
        val nextStage = BootLogStage.entries.firstOrNull { stage -> stage.matches(lowered) } ?: return
        if (!shouldAcceptStage(nextStage)) {
            return
        }
        if (nextStage.ordinal <= bootLogStage.ordinal) {
            return
        }

        bootLogStage = nextStage
        val stageMessage = buildStageMessage(nextStage, line)
        updateProgress(nextStage.progress, stageMessage)
    }

    private fun shouldAcceptStage(stage: BootLogStage): Boolean {
        if (stage != BootLogStage.GAME_MAIN_BOOT) {
            return true
        }
        // Guard against early false positives from class/arg lines before MTS patch stages.
        return bootLogStage.ordinal >= BootLogStage.MTS_INITIALIZING_MODS.ordinal ||
            bootOverlayProgress >= BootLogStage.MTS_INITIALIZING_MODS.progress
    }

    private fun buildStageMessage(stage: BootLogStage, rawLine: String): String {
        val localized = if (stage.fallbackStatusResId != 0) {
            text(stage.fallbackStatusResId)
        } else {
            ""
        }
        val source = localized.ifBlank { rawLine }.trim()
        if (source.length <= MAX_STATUS_LINE_LENGTH) {
            return source
        }
        return source.take(MAX_STATUS_LINE_LENGTH - 3) + "..."
    }

    private fun readTailLogText(file: java.io.File): String {
        if (!file.isFile) {
            return jvmLogPlaceholderText
        }
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val fileLength = raf.length()
                if (fileLength <= 0L) {
                    return jvmLogPlaceholderText
                }
                val bytesToRead = minOf(fileLength, JVM_LOG_MAX_TAIL_BYTES.toLong()).toInt()
                val startOffset = fileLength - bytesToRead
                raf.seek(startOffset)
                val buffer = ByteArray(bytesToRead)
                raf.readFully(buffer)
                val raw = String(buffer, StandardCharsets.UTF_8)
                val lines = raw.lineSequence().toList()
                if (lines.isEmpty()) {
                    return jvmLogPlaceholderText
                }
                lines.takeLast(JVM_LOG_MAX_LINES).joinToString("\n").ifBlank { jvmLogPlaceholderText }
            }
        } catch (_: Throwable) {
            jvmLogPlaceholderText
        }
    }
}

@Immutable
private data class BootOverlayUiState(
    val progress: Int,
    val statusText: String,
    val showDismissButton: Boolean,
    val dismissButtonEnabled: Boolean,
    val dismissButtonText: String,
    val jvmLogText: String
)

@Composable
private fun BootOverlayPanel(
    uiState: BootOverlayUiState,
    manualDismissBootOverlay: Boolean,
    onDismissClick: () -> Unit
) {
    val targetProgress = (uiState.progress / 100f).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(
            durationMillis = 620,
            easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
        ),
        label = "boot_overlay_progress"
    )

    val consumeBackgroundTapModifier = if (manualDismissBootOverlay) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) {}
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .then(consumeBackgroundTapModifier)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.boot_overlay_title_loading_mods),
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            )
            Text(
                text = uiState.statusText,
                color = Color.White,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                text = stringResource(R.string.boot_overlay_title_jvm_logs),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
            )
            val logScrollState = rememberScrollState()
            LaunchedEffect(uiState.jvmLogText) {
                logScrollState.scrollTo(logScrollState.maxValue)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 220.dp)
                    .padding(top = 8.dp)
                    .background(Color(0x22000000))
                    .verticalScroll(logScrollState)
                    .padding(10.dp)
            ) {
                Text(
                    text = uiState.jvmLogText,
                    color = Color.White
                )
            }
            if (uiState.showDismissButton) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismissClick,
                        enabled = uiState.dismissButtonEnabled
                    ) {
                        Text(text = uiState.dismissButtonText)
                    }
                }
            }
        }
    }
}

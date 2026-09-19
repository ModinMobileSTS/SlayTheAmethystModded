package io.stamethyst

import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.config.BootOverlayAnimation
import io.stamethyst.config.BootOverlayImageConfig
import io.stamethyst.config.BootOverlayStyle
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import io.stamethyst.ui.loading.BootLoadingAnimation
import io.stamethyst.ui.resources.FileImage
import io.stamethyst.ui.resources.RuntimeResourceImage
import io.stamethyst.ui.resources.RuntimeUiResourcePaths
import io.stamethyst.ui.theme.LauncherTheme
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

internal class BootOverlayFrameDismissGate(
    private val readyDelayMs: Long,
    private val requiredPostSignalFrames: Int
) {
    private var requested = false
    private var requestFrameTimestampNs = 0L
    private var requestedAtMs = 0L
    private var lastAcceptedFrameTimestampNs = 0L
    private var postSignalFrameCount = 0

    val pending: Boolean get() = requested

    fun request(frameTimestampNs: Long, nowMs: Long) {
        requested = true
        requestFrameTimestampNs = frameTimestampNs
        requestedAtMs = nowMs
        lastAcceptedFrameTimestampNs = frameTimestampNs
        postSignalFrameCount = 0
    }

    fun reset() {
        requested = false
        requestFrameTimestampNs = 0L
        requestedAtMs = 0L
        lastAcceptedFrameTimestampNs = 0L
        postSignalFrameCount = 0
    }

    fun shouldDismissOnFrame(frameTimestampNs: Long, nowMs: Long): Boolean {
        if (!requested || frameTimestampNs <= requestFrameTimestampNs) {
            return false
        }
        if (frameTimestampNs == lastAcceptedFrameTimestampNs) {
            return false
        }

        lastAcceptedFrameTimestampNs = frameTimestampNs
        postSignalFrameCount += 1

        if (nowMs - requestedAtMs < readyDelayMs) {
            return false
        }
        if (postSignalFrameCount < requiredPostSignalFrames) {
            return false
        }

        reset()
        return true
    }
}


/**
 * Manages the boot overlay UI: progress bar, status text, and dismiss button.
 */
class BootOverlayController(
    private val activity: StsGameActivity,
    private val manualDismissBootOverlay: Boolean,
    private val useTextureViewSurface: Boolean,
    private val onDismissed: () -> Unit,
    private val onRuntimePauseRequested: () -> Unit,
    private val onRequestEarlyDismiss: () -> Unit,
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
        private const val TEXTURE_VIEW_READY_POST_SIGNAL_FRAMES = 2
        private const val LOGCAT_TAG = "STS-BootOverlay"
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
        GAME_ENTRY_LAUNCHING(
            96,
            R.string.boot_overlay_stage_starting_game_entry,
            "launching application",
            "distributorplatform=",
            "initializing display settings"
        ),
        GAME_MAIN_BOOT(
            98,
            R.string.boot_overlay_stage_starting_game_entry,
            "loading character stats",
            "generating seeds:",
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
    private var slingBreakBootOverlay: View? = null
    private var slingBreakBootGame: android.webkit.WebView? = null
    private var slingBreakLauncherPageReady = false
    private var usesSlingBreakBootOverlay = false
    private var bootOverlayProgress = 0
    private var bootOverlayMessage = ""
    private var bootOverlayShownAtMs = -1L
    private var bootOverlayDismissed = false
    private var launchFailureSignaled = false
    private var lastJvmLogLength = -1L
    private var lastJvmLogModifiedMs = -1L
    private var parsedJvmLogOffset = 0L
    private var parsedJvmLogRemainder = ""
    private var bootLogStage = BootLogStage.NONE
    private var surfaceViewLateDismissScheduled = false
    private val textureViewDismissGate = BootOverlayFrameDismissGate(
        readyDelayMs = BOOT_OVERLAY_READY_DELAY_MS,
        requiredPostSignalFrames = TEXTURE_VIEW_READY_POST_SIGNAL_FRAMES
    )
    @Volatile
    private var manualEnterGameReady = false
    private val surfaceViewLateDismissRunnable = Runnable {
        surfaceViewLateDismissScheduled = false
        if (bootOverlayDismissed || bootOverlay == null) {
            return@Runnable
        }
        updateProgress(
            bootOverlayProgress.coerceAtLeast(99),
            text(R.string.boot_overlay_status_game_frame_ready)
        )
        dismiss()
    }

    private var overlayUiState by mutableStateOf(
        BootOverlayUiState(
            progress = 0,
            statusText = text(R.string.boot_overlay_status_starting_jvm),
            enterGameReady = false,
            jvmLogText = "",
            hasJvmLogOutput = false
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

    val shouldPauseRuntimeUntilEntry: Boolean
        get() = usesSlingBreakBootOverlay && manualEnterGameReady && !bootOverlayDismissed

    private val requiresManualDismiss: Boolean
        get() = usesSlingBreakBootOverlay || manualDismissBootOverlay

    fun init() {
        bootOverlay = activity.findViewById(R.id.bootOverlay)
        slingBreakBootOverlay = activity.findViewById(R.id.slingBreakBootOverlay)
        slingBreakBootGame = activity.findViewById(R.id.slingBreakBootGame)
        usesSlingBreakBootOverlay =
            LauncherConfig.readBootOverlayStyle(activity) == BootOverlayStyle.SLING_BREAK &&
                slingBreakBootOverlay != null && slingBreakBootGame != null
        if (bootOverlay == null && !usesSlingBreakBootOverlay) {
            activity.setBootOverlayKeepScreenOn(false)
            return
        }
        if (usesSlingBreakBootOverlay) {
            bootOverlay?.visibility = View.GONE
            slingBreakBootGame?.apply {
                configureSlingBreakGame()
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun onPageReady() {
                        activity.runOnUiThread {
                            slingBreakLauncherPageReady = true
                            pushSlingBreakProgress()
                            if (manualEnterGameReady) {
                                slingBreakBootGame?.evaluateJavascript(
                                    "window.SlingBreakLauncher?.setReady?.()",
                                    null
                                )
                            }
                        }
                    }

                    @android.webkit.JavascriptInterface
                    fun enterGame() {
                        activity.runOnUiThread {
                            if (manualEnterGameReady) {
                                dismiss()
                            }
                        }
                    }
                }, "AndroidSlingBreakLauncher")
                loadUrl("$SLING_BREAK_GAME_URL?launcher=1")
            }
            slingBreakBootOverlay?.visibility = View.VISIBLE
        } else {
            slingBreakBootOverlay?.visibility = View.GONE
            bootOverlay?.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            val themeMode = LauncherConfig.readThemeMode(activity)
            val themeColor = LauncherConfig.readThemeColor(activity)
            val bootOverlayStyle = LauncherConfig.readBootOverlayStyle(activity)
            val loadingAnimation = LauncherConfig.readBootOverlayAnimation(activity)
            val bootOverlayImageConfig = LauncherConfig.readBootOverlayImageConfig(activity)
            bootOverlay?.setContent {
                LauncherTheme(
                    themeMode = themeMode,
                    themeColor = themeColor
                ) {
                    BootOverlayPanel(
                        uiState = overlayUiState,
                        overlayStyle = bootOverlayStyle,
                        loadingAnimation = loadingAnimation,
                        imageConfig = bootOverlayImageConfig,
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
            }
            bootOverlay?.visibility = View.VISIBLE
        }
        activity.setBootOverlayKeepScreenOn(true)

        if (!requiresManualDismiss) {
            bootOverlay?.setOnTouchListener { _, _ -> true }
        } else {
            bootOverlay?.setOnTouchListener(null)
        }

        bootOverlayShownAtMs = SystemClock.uptimeMillis()
        val currentLog = RuntimePaths.latestLog(activity)
        if (currentLog.isFile) {
            val currentLength = currentLog.length().coerceAtLeast(0L)
            parsedJvmLogOffset = currentLength
            lastJvmLogLength = currentLength
            lastJvmLogModifiedMs = currentLog.lastModified()
        } else {
            lastJvmLogLength = -1L
            lastJvmLogModifiedMs = -1L
            parsedJvmLogOffset = 0L
        }
        parsedJvmLogRemainder = ""
        bootLogStage = BootLogStage.NONE
        surfaceViewLateDismissScheduled = false
        manualEnterGameReady = false
        textureViewDismissGate.reset()
        earlyOverlayDismissOnNextFrame = false
        earlyOverlayDismissRequestFrameTimestampNs = 0L
        overlayUiState = overlayUiState.copy(
            enterGameReady = false,
            jvmLogText = "",
            hasJvmLogOutput = false
        )
        bootOverlay?.removeCallbacks(surfaceViewLateDismissRunnable)
        scheduleJvmLogPolling(initial = true)

        if (requiresManualDismiss) {
            updateProgress(1, text(R.string.boot_overlay_status_starting_pipeline_manual))
        } else {
            updateProgress(1, text(R.string.boot_overlay_status_starting_pipeline))
        }
    }

    fun onDestroy() {
        stopJvmLogPolling()
        surfaceViewLateDismissScheduled = false
        bootOverlay?.removeCallbacks(surfaceViewLateDismissRunnable)
        bootOverlay?.disposeComposition()
        bootOverlay = null
        slingBreakBootGame?.destroy()
        slingBreakBootGame = null
        slingBreakLauncherPageReady = false
        slingBreakBootOverlay = null
        earlyOverlayDismissOnNextFrame = false
        earlyOverlayDismissRequestFrameTimestampNs = 0L
        textureViewDismissGate.reset()
        activity.setBootOverlayKeepScreenOn(false)
    }

    fun onActivityResumed() {
        if (usesSlingBreakBootOverlay && !bootOverlayDismissed) {
            slingBreakBootGame?.onResume()
        }
    }

    fun onActivityPaused() {
        if (usesSlingBreakBootOverlay && !bootOverlayDismissed) {
            slingBreakBootGame?.onPause()
        }
    }

    fun updateProgress(percent: Int, message: String?) {
        val bounded = percent.coerceIn(0, 100)
        val normalizedMessage = message?.trim() ?: ""

        if (bounded < bootOverlayProgress) return
        if (bounded == bootOverlayProgress && normalizedMessage == bootOverlayMessage) return

        bootOverlayProgress = bounded
        bootOverlayMessage = normalizedMessage

        activity.runOnUiThread {
            if (bootOverlayDismissed || (!usesSlingBreakBootOverlay && bootOverlay == null)) return@runOnUiThread
            if (usesSlingBreakBootOverlay) {
                pushSlingBreakProgress()
                return@runOnUiThread
            }
            val nextStatus = if (normalizedMessage.isNotEmpty()) {
                normalizedMessage
            } else {
                overlayUiState.statusText
            }
            overlayUiState = overlayUiState.copy(
                progress = bootOverlayProgress,
                statusText = nextStatus
            )
        }
    }

    fun mapLaunchProgressMessage(percent: Int, message: String?): String? {
        val bounded = percent.coerceIn(0, 100)
        val normalizedMessage = message?.trim().orEmpty()
        if (normalizedMessage.isEmpty()) {
            return message
        }
        return if (bounded > BootLogStage.MTS_INITIALIZING_MODS.progress) {
            text(R.string.boot_overlay_stage_starting_game_entry)
        } else {
            normalizedMessage
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

    fun signalSplashPhase(_message: String?) {
        if (bootOverlayDismissed || (!usesSlingBreakBootOverlay && bootOverlay == null)) return

        val phaseMessage = text(R.string.boot_overlay_stage_starting_game_entry)
        updateProgress(
            bootOverlayProgress.coerceAtLeast(BootLogStage.GAME_ENTRY_LAUNCHING.progress),
            phaseMessage
        )

        if (requiresManualDismiss) {
            if (useTextureViewSurface) {
                onRequestEarlyDismiss()
            } else {
                updateProgress(
                    bootOverlayProgress.coerceAtLeast(99),
                    text(R.string.boot_overlay_status_game_frame_ready)
                )
                markManualEnterGameReady()
            }
            return
        }
        if (useTextureViewSurface) {
            onRequestEarlyDismiss()
            return
        }
        scheduleSurfaceViewLateDismiss(
            readyDelayMs = 0L,
            respectMinVisible = false
        )
    }

    fun dismiss() {
        if (bootOverlayDismissed || (!usesSlingBreakBootOverlay && bootOverlay == null)) return

        bootOverlayDismissed = true
        stopJvmLogPolling()
        surfaceViewLateDismissScheduled = false
        bootOverlay?.removeCallbacks(surfaceViewLateDismissRunnable)
        earlyOverlayDismissOnNextFrame = false
        earlyOverlayDismissRequestFrameTimestampNs = 0L
        textureViewDismissGate.reset()

        if (usesSlingBreakBootOverlay) {
            slingBreakBootGame?.apply {
                stopLoading()
                onPause()
                loadUrl("about:blank")
                destroy()
            }
            slingBreakBootGame = null
            slingBreakLauncherPageReady = false
            slingBreakBootOverlay?.visibility = View.GONE
            slingBreakBootOverlay = null
            activity.finishSlingBreakBoot()
        } else {
            bootOverlay?.visibility = View.GONE
        }
        activity.setBootOverlayKeepScreenOn(false)
        Log.i(
            LOGCAT_TAG,
            "BOOT_OVERLAY_DISMISSED uptimeMs=${SystemClock.uptimeMillis()} " +
                "progress=$bootOverlayProgress " +
                "surface=${if (useTextureViewSurface) "textureView" else "surfaceView"}"
        )

        onDismissed()
    }

    fun setEarlyDismissRequestTimestamp(timestampNs: Long) {
        earlyOverlayDismissRequestFrameTimestampNs = timestampNs
        textureViewDismissGate.request(timestampNs, SystemClock.uptimeMillis())
        earlyOverlayDismissOnNextFrame = true
    }

    fun onTextureFrameUpdate(currentTimestampNs: Long) {
        if (!earlyOverlayDismissOnNextFrame) {
            return
        }
        if (!textureViewDismissGate.shouldDismissOnFrame(
                frameTimestampNs = currentTimestampNs,
                nowMs = SystemClock.uptimeMillis()
            )
        ) {
            return
        }

        earlyOverlayDismissOnNextFrame = false
        earlyOverlayDismissRequestFrameTimestampNs = 0L
        activity.runOnUiThread {
            updateProgress(
                bootOverlayProgress.coerceAtLeast(99),
                text(R.string.boot_overlay_status_game_frame_ready)
            )
            if (requiresManualDismiss) {
                markManualEnterGameReady()
            } else {
                dismiss()
            }
        }
    }

    private fun markManualEnterGameReady() {
        if (!requiresManualDismiss || manualEnterGameReady) {
            return
        }
        manualEnterGameReady = true
        if (usesSlingBreakBootOverlay) {
            onRuntimePauseRequested()
        }
        activity.runOnUiThread {
            if (bootOverlayDismissed || (!usesSlingBreakBootOverlay && bootOverlay == null)) return@runOnUiThread
            if (usesSlingBreakBootOverlay) {
                if (slingBreakLauncherPageReady) {
                    slingBreakBootGame?.evaluateJavascript(
                        "window.SlingBreakLauncher?.setReady?.()",
                        null
                    )
                }
                return@runOnUiThread
            }
            if (overlayUiState.enterGameReady) return@runOnUiThread
            overlayUiState = overlayUiState.copy(enterGameReady = true)
        }
    }

    private fun pushSlingBreakProgress() {
        if (!usesSlingBreakBootOverlay || !slingBreakLauncherPageReady) return
        val escapedMessage = org.json.JSONObject.quote(bootOverlayMessage)
        slingBreakBootGame?.evaluateJavascript(
            "window.SlingBreakLauncher?.setProgress?.($bootOverlayProgress, $escapedMessage)",
            null
        )
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
            jvmLogText = snapshot,
            hasJvmLogOutput = snapshot.isNotBlank()
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

    private fun scheduleSurfaceViewLateDismiss(
        readyDelayMs: Long = BOOT_OVERLAY_READY_DELAY_MS,
        respectMinVisible: Boolean = true
    ) {
        if (surfaceViewLateDismissScheduled || bootOverlayDismissed) {
            return
        }
        surfaceViewLateDismissScheduled = true
        val now = SystemClock.uptimeMillis()
        val elapsed = if (bootOverlayShownAtMs <= 0L) {
            BOOT_OVERLAY_MIN_VISIBLE_MS
        } else {
            now - bootOverlayShownAtMs
        }
        val minDelay = if (respectMinVisible) {
            (BOOT_OVERLAY_MIN_VISIBLE_MS - elapsed).coerceAtLeast(0L)
        } else {
            0L
        }
        val delay = minDelay.coerceAtLeast(readyDelayMs.coerceAtLeast(0L))
        activity.runOnUiThread {
            val overlay = bootOverlay
            if (overlay == null || bootOverlayDismissed) {
                surfaceViewLateDismissScheduled = false
                return@runOnUiThread
            }
            overlay.removeCallbacks(surfaceViewLateDismissRunnable)
            overlay.postDelayed(surfaceViewLateDismissRunnable, delay)
        }
    }

    private fun readTailLogText(file: java.io.File): String {
        if (!file.isFile) {
            return ""
        }
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val fileLength = raf.length()
                if (fileLength <= 0L) {
                    return ""
                }
                val bytesToRead = minOf(fileLength, JVM_LOG_MAX_TAIL_BYTES.toLong()).toInt()
                val startOffset = fileLength - bytesToRead
                raf.seek(startOffset)
                val buffer = ByteArray(bytesToRead)
                raf.readFully(buffer)
                val raw = String(buffer, StandardCharsets.UTF_8)
                val lines = raw.lineSequence().toList()
                if (lines.isEmpty()) {
                    return ""
                }
                lines.takeLast(JVM_LOG_MAX_LINES)
                    .joinToString("\n")
                    .let { text -> if (text.isBlank()) "" else text.trimEnd() }
            }
        } catch (_: Throwable) {
            ""
        }
    }
}

@Immutable
private data class BootOverlayUiState(
    val progress: Int,
    val statusText: String,
    val enterGameReady: Boolean,
    val jvmLogText: String,
    val hasJvmLogOutput: Boolean
)

@Composable
private fun BootOverlayPanel(
    uiState: BootOverlayUiState,
    overlayStyle: BootOverlayStyle,
    loadingAnimation: BootOverlayAnimation,
    imageConfig: BootOverlayImageConfig,
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

    when (overlayStyle) {
        BootOverlayStyle.MODERN -> ModernBootOverlayPanel(
            uiState = uiState,
            animatedProgress = animatedProgress,
            imageConfig = imageConfig,
            manualDismissBootOverlay = manualDismissBootOverlay,
            onDismissClick = onDismissClick
        )
        BootOverlayStyle.LEGACY -> LegacyBootOverlayPanel(
            uiState = uiState,
            loadingAnimation = loadingAnimation,
            animatedProgress = animatedProgress,
            manualDismissBootOverlay = manualDismissBootOverlay,
            onDismissClick = onDismissClick
        )
        BootOverlayStyle.CLASSIC_LOG -> ClassicLogBootOverlayPanel(
            uiState = uiState,
            animatedProgress = animatedProgress,
            manualDismissBootOverlay = manualDismissBootOverlay,
            onDismissClick = onDismissClick
        )
        BootOverlayStyle.MATERIAL_LOG -> MaterialLogBootOverlayPanel(
            uiState = uiState,
            animatedProgress = animatedProgress,
            manualDismissBootOverlay = manualDismissBootOverlay,
            onDismissClick = onDismissClick
        )
        BootOverlayStyle.SLING_BREAK -> ModernBootOverlayPanel(
            uiState = uiState,
            animatedProgress = animatedProgress,
            imageConfig = imageConfig,
            manualDismissBootOverlay = manualDismissBootOverlay,
            onDismissClick = onDismissClick
        )
    }
}

@Composable
private fun ModernBootOverlayPanel(
    uiState: BootOverlayUiState,
    animatedProgress: Float,
    imageConfig: BootOverlayImageConfig,
    manualDismissBootOverlay: Boolean,
    onDismissClick: () -> Unit
) {
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
            .background(Color.Black)
            .then(consumeBackgroundTapModifier)
    ) {
        var artHintExpanded by remember { mutableStateOf(false) }
        val revealProgress = animatedProgress.coerceIn(0f, 1f)
        BootOverlayArtBackground(
            imageConfig = imageConfig,
            revealProgress = revealProgress,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.18f),
                            0.48f to Color.Black.copy(alpha = 0.12f),
                            0.76f to Color.Black.copy(alpha = 0.64f),
                            1.00f to Color.Black.copy(alpha = 0.92f)
                        )
                    )
                )
        )
        BootOverlayArtHint(
            expanded = artHintExpanded,
            onToggle = { artHintExpanded = !artHintExpanded },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 12.dp, end = 12.dp)
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.boot_overlay_title_starting),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = uiState.statusText,
                    color = Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${uiState.progress.coerceIn(0, 100)}%",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    maxLines = 1
                )
            }
            BootProgressBar(
                progress = animatedProgress,
                modifier = Modifier.fillMaxWidth()
            )
            if (manualDismissBootOverlay) {
                val dismissButtonText = if (uiState.enterGameReady) {
                    stringResource(R.string.boot_overlay_button_enter_game)
                } else {
                    stringResource(R.string.boot_overlay_button_close)
                }
                Button(
                    onClick = onDismissClick,
                    enabled = true,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(text = dismissButtonText)
                }
            }
        }
    }
}

@Composable
internal fun BootOverlayArtBackground(
    imageConfig: BootOverlayImageConfig,
    revealProgress: Float,
    modifier: Modifier = Modifier
) {
    val startImagePath = imageConfig.resolvedStartImagePath()
    val endImagePath = imageConfig.resolvedEndImagePath()
    if (startImagePath != null) {
        FileImage(
            path = startImagePath,
            version = imageConfig.resolvedStartImageVersion(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopStart,
            modifier = modifier
        )
    } else {
        RuntimeResourceImage(
            path = RuntimeUiResourcePaths.BOOT_OVERLAY_BACKGROUND_DARK,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopStart,
            modifier = modifier
        )
    }
    val revealedModifier = modifier.drawWithContent {
        clipRect(right = size.width * revealProgress.coerceIn(0f, 1f)) {
            this@drawWithContent.drawContent()
        }
    }
    if (endImagePath != null) {
        FileImage(
            path = endImagePath,
            version = imageConfig.resolvedEndImageVersion(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopStart,
            modifier = revealedModifier
        )
    } else {
        RuntimeResourceImage(
            path = RuntimeUiResourcePaths.BOOT_OVERLAY_BACKGROUND_BRIGHT,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopStart,
            modifier = revealedModifier
        )
    }
}

@Composable
private fun BootOverlayArtHint(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End
    ) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = Color.Black.copy(alpha = if (expanded) 0.36f else 0.16f),
                    shape = RoundedCornerShape(999.dp)
                )
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_info_outline),
                contentDescription = stringResource(R.string.boot_overlay_art_hint_button),
                tint = Color.White.copy(alpha = if (expanded) 0.86f else 0.48f),
                modifier = Modifier.size(18.dp)
            )
        }
        if (expanded) {
            Text(
                text = stringResource(R.string.boot_overlay_art_hint_message),
                color = Color.White.copy(alpha = 0.88f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .widthIn(max = 280.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.46f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun ClassicLogBootOverlayPanel(
    uiState: BootOverlayUiState,
    animatedProgress: Float,
    manualDismissBootOverlay: Boolean,
    onDismissClick: () -> Unit
) {
    val consumeBackgroundTapModifier = if (manualDismissBootOverlay) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) {}
    } else {
        Modifier
    }
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .then(consumeBackgroundTapModifier)
            .padding(24.dp)
    ) {
        val contentBottomPadding = if (manualDismissBootOverlay) 72.dp else 0.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(bottom = contentBottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.boot_overlay_title_starting),
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                color = colorScheme.primary,
                trackColor = colorScheme.primary.copy(alpha = 0.28f)
            )
            Text(
                text = bootOverlayStatusWithProgress(uiState),
                color = Color.White,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                text = stringResource(R.string.boot_overlay_logs_title),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
            )
            ClassicBootLogPane(
                uiState = uiState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 220.dp)
                    .padding(top = 8.dp)
            )
        }
        if (manualDismissBootOverlay) {
            val dismissButtonText = if (uiState.enterGameReady) {
                stringResource(R.string.boot_overlay_button_enter_game)
            } else {
                stringResource(R.string.boot_overlay_button_close)
            }
            Button(
                onClick = onDismissClick,
                enabled = true,
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                Text(text = dismissButtonText)
            }
        }
    }
}

@Composable
private fun MaterialLogBootOverlayPanel(
    uiState: BootOverlayUiState,
    animatedProgress: Float,
    manualDismissBootOverlay: Boolean,
    onDismissClick: () -> Unit
) {
    val consumeBackgroundTapModifier = if (manualDismissBootOverlay) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) {}
    } else {
        Modifier
    }
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.surface)
            .then(consumeBackgroundTapModifier)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp)
    ) {
        val contentBottomPadding = if (manualDismissBootOverlay) 72.dp else 0.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = contentBottomPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.boot_overlay_title_starting),
                color = colorScheme.onSurface,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp)),
                color = colorScheme.primary,
                trackColor = colorScheme.primaryContainer.copy(alpha = 0.42f)
            )
            Text(
                text = bootOverlayStatusWithProgress(uiState),
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            MaterialBootLogPane(
                uiState = uiState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 6.dp)
            )
        }
        if (manualDismissBootOverlay) {
            val dismissButtonText = if (uiState.enterGameReady) {
                stringResource(R.string.boot_overlay_button_enter_game)
            } else {
                stringResource(R.string.boot_overlay_button_close)
            }
            Button(
                onClick = onDismissClick,
                enabled = true,
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                Text(text = dismissButtonText)
            }
        }
    }
}

@Composable
private fun bootOverlayStatusWithProgress(uiState: BootOverlayUiState): String {
    return stringResource(
        R.string.boot_overlay_status_with_progress,
        uiState.statusText,
        uiState.progress.coerceIn(0, 100)
    )
}

@Composable
private fun ClassicBootLogPane(
    uiState: BootOverlayUiState,
    modifier: Modifier = Modifier
) {
    val logScrollState = rememberScrollState()
    val logText = uiState.jvmLogText.ifBlank {
        stringResource(R.string.boot_overlay_logs_placeholder)
    }
    LaunchedEffect(logText, logScrollState.maxValue) {
        val target = logScrollState.maxValue
        if (target > 0 && target != logScrollState.value) {
            logScrollState.animateScrollTo(
                value = target,
                animationSpec = tween(
                    durationMillis = 240,
                    easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
                )
            )
        }
    }
    Box(
        modifier = modifier
            .background(Color(0x22000000))
            .verticalScroll(logScrollState)
            .padding(10.dp)
    ) {
        Text(
            text = logText,
            color = Color.White,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun MaterialBootLogPane(
    uiState: BootOverlayUiState,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val logScrollState = rememberScrollState()
    val logText = uiState.jvmLogText.ifBlank {
        stringResource(R.string.boot_overlay_logs_placeholder)
    }
    LaunchedEffect(logText, logScrollState.maxValue) {
        val target = logScrollState.maxValue
        if (target > 0 && target != logScrollState.value) {
            logScrollState.animateScrollTo(
                value = target,
                animationSpec = tween(
                    durationMillis = 240,
                    easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
                )
            )
        }
    }
    Text(
        text = logText,
        color = if (uiState.hasJvmLogOutput) {
            colorScheme.onSurfaceVariant.copy(alpha = 0.88f)
        } else {
            colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
        },
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = modifier.verticalScroll(logScrollState)
    )
}

@Composable
private fun LegacyBootOverlayPanel(
    uiState: BootOverlayUiState,
    loadingAnimation: BootOverlayAnimation,
    animatedProgress: Float,
    manualDismissBootOverlay: Boolean,
    onDismissClick: () -> Unit
) {
    val consumeBackgroundTapModifier = if (manualDismissBootOverlay) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) {}
    } else {
        Modifier
    }
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.surface)
            .then(consumeBackgroundTapModifier)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 28.dp, vertical = 24.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compactHeight = maxHeight < 420.dp
            val animationSize = if (compactHeight) 112.dp else 148.dp
            val contentBottomPadding = if (manualDismissBootOverlay) 58.dp else 0.dp
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = contentBottomPadding),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BootLoadingStatusPane(
                    uiState = uiState,
                    loadingAnimation = loadingAnimation,
                    animatedProgress = animatedProgress,
                    animationSize = animationSize,
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                )
                BootLogPane(
                    uiState = uiState,
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                )
            }
        }
        if (manualDismissBootOverlay) {
            val dismissButtonText = if (uiState.enterGameReady) {
                stringResource(R.string.boot_overlay_button_enter_game)
            } else {
                stringResource(R.string.boot_overlay_button_close)
            }
            Button(
                onClick = onDismissClick,
                enabled = true,
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                Text(text = dismissButtonText)
            }
        }
    }
}

@Composable
private fun BootLoadingStatusPane(
    uiState: BootOverlayUiState,
    loadingAnimation: BootOverlayAnimation,
    animatedProgress: Float,
    animationSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.boot_overlay_title_starting),
            color = colorScheme.onSurface,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        BootLoadingAnimation(
            animation = loadingAnimation,
            modifier = Modifier
                .size(animationSize)
                .padding(6.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        BootProgressBar(
            progress = animatedProgress,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "${uiState.progress.coerceIn(0, 100)}%",
            color = colorScheme.primary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = uiState.statusText,
            color = colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun BootProgressBar(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .height(10.dp)
            .clip(shape)
            .background(colorScheme.primaryContainer.copy(alpha = 0.36f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            colorScheme.primary,
                            colorScheme.tertiary,
                            colorScheme.secondary
                        )
                    )
                )
        )
    }
}

@Composable
private fun BootLogPane(
    uiState: BootOverlayUiState,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val logScrollState = rememberScrollState()
    val logText = uiState.jvmLogText.ifBlank {
        stringResource(R.string.boot_overlay_logs_placeholder)
    }
    LaunchedEffect(logText, logScrollState.maxValue) {
        val target = logScrollState.maxValue
        if (target > 0 && target != logScrollState.value) {
            logScrollState.animateScrollTo(
                value = target,
                animationSpec = tween(
                    durationMillis = 240,
                    easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
                )
            )
        }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colorScheme.surfaceVariant.copy(alpha = 0.18f))
            .border(
                width = 1.dp,
                color = colorScheme.outlineVariant.copy(alpha = 0.52f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(colorScheme.primary)
            )
            Text(
                text = stringResource(R.string.boot_overlay_logs_title),
                color = colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Text(
                text = logText,
                color = if (uiState.hasJvmLogOutput) {
                    colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                } else {
                    colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(logScrollState)
            )
        }
    }
}

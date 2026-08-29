package io.stamethyst

import android.app.ActivityManager
import android.content.Context
import android.graphics.Typeface
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.stamethyst.backend.launch.JvmRuntimeMemorySnapshot
import io.stamethyst.config.RuntimePaths
import org.lwjgl.glfw.CallbackBridge
import java.io.RandomAccessFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale

internal class GamePerformanceOverlayController(
    private val activity: AppCompatActivity,
    private val overlayView: TextView,
    private val rendererSummary: String,
    private val readJvmRuntimeMemorySnapshot: () -> JvmRuntimeMemorySnapshot?,
    private val readJvmLaunchStartedElapsedMs: () -> Long,
    private val snapshotFile: java.io.File,
    private val performanceDeepDiagnostics: Boolean
) {
    companion object {
        private const val REFRESH_INTERVAL_MS = 1000L
        private const val PSS_REFRESH_INTERVAL_MS = 10_000L
        private const val GC_WINDOW_DURATION_MS = 60_000L
        private const val GC_LOG_SCAN_MAX_BYTES = 8 * 1024
        private const val GC_LOG_HISTORY_SCAN_MAX_BYTES = 64 * 1024
        private const val GPU_SUMMARY_SCAN_MAX_BYTES = 64 * 1024
        private const val BYTES_PER_MB = 1024.0 * 1024.0
        private const val BYTES_PER_KB = 1024L
        private val SUMMARY_NUMBER_REGEX_CACHE = HashMap<String, Regex>()
    }

    private data class PssMemorySnapshot(
        val totalPssBytes: Long,
        val dalvikPssBytes: Long,
        val nativePssBytes: Long,
        val otherPssBytes: Long,
        val nativeHeapSummaryBytes: Long?,
        val graphicsSummaryBytes: Long?,
        val glMtrackPssBytes: Long?,
        val unknownPssBytes: Long?
    )

    private data class GpuResourceSummary(
        val estimatedBytes: Long?,
        val guardianReclaimedBytes: Long?
    )

    private object MemoryInfoDetailReader {
        private const val FALLBACK_OTHER_UNKNOWN_DEV = 5
        private const val FALLBACK_OTHER_GL = 15
        private const val FALLBACK_OTHER_UNKNOWN_MAP = 17

        private val memoryInfoClass = Debug.MemoryInfo::class.java
        private val getOtherPssMethod = runCatching {
            memoryInfoClass.getMethod("getOtherPss", Int::class.javaPrimitiveType!!).apply {
                isAccessible = true
            }
        }.getOrNull()

        private val otherGlIndex = resolveIndex("OTHER_GL", FALLBACK_OTHER_GL)
        private val otherUnknownDevIndex =
            resolveIndex("OTHER_UNKNOWN_DEV", FALLBACK_OTHER_UNKNOWN_DEV)
        private val otherUnknownMapIndex =
            resolveIndex("OTHER_UNKNOWN_MAP", FALLBACK_OTHER_UNKNOWN_MAP)

        fun readGlMtrackPssBytes(info: Debug.MemoryInfo): Long? {
            return readOtherPssBytes(info, otherGlIndex)
        }

        fun readUnknownPssBytes(info: Debug.MemoryInfo): Long? {
            return sumBytes(
                readOtherPssBytes(info, otherUnknownDevIndex),
                readOtherPssBytes(info, otherUnknownMapIndex)
            )
        }

        private fun resolveIndex(fieldName: String, fallbackValue: Int): Int {
            return runCatching {
                memoryInfoClass.getField(fieldName).getInt(null)
            }.recoverCatching {
                memoryInfoClass.getDeclaredField(fieldName).apply {
                    isAccessible = true
                }.getInt(null)
            }.getOrElse {
                fallbackValue
            }
        }

        private fun readOtherPssBytes(info: Debug.MemoryInfo, index: Int): Long? {
            val method = getOtherPssMethod ?: return null
            return runCatching {
                val valueKb = (method.invoke(info, index) as? Number)
                    ?.toLong()
                    ?.coerceAtLeast(0L)
                    ?: return null
                valueKb * BYTES_PER_KB
            }.getOrNull()
        }

        private fun sumBytes(first: Long?, second: Long?): Long? {
            if (first == null && second == null) {
                return null
            }
            return (first ?: 0L) + (second ?: 0L)
        }
    }

    private val activityManager =
        activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val processId = Process.myPid()
    private val procStatusFile = File("/proc/self/status")
    private val latestLogFile = RuntimePaths.latestLog(activity)
    private val jvmGcLogFile = RuntimePaths.jvmGcLog(activity)

    private var running = false
    private var visible = false
    private var lastSampleElapsedMs = 0L
    private var lastSwapCount = 0
    private var jvmGcLogOffset = 0L
    private var jvmGcLogRemainder = ""

    @Volatile
    private var latestRssBytes: Long? = null

    @Volatile
    private var latestPssSnapshot: PssMemorySnapshot? = null

    @Volatile
    private var latestPssSampleElapsedMs = 0L

    @Volatile
    private var latestGcEventsPerMinute = 0

    @Volatile
    private var latestGcWarmupAgeSeconds: Int? = null

    @Volatile
    private var latestGpuEstimatedBytes: Long? = null

    @Volatile
    private var latestGpuGuardianReclaimedBytes: Long? = null

    private var labelsExpanded = false

    @Volatile
    private var memorySamplerRunning = false

    @Volatile
    private var memorySamplerThread: Thread? = null

    private val gcEventTimestampsMs = ArrayDeque<Long>()

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!running || !visible) {
                return
            }
            renderSnapshot()
            overlayView.removeCallbacks(this)
            overlayView.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    fun init() {
        overlayView.visibility = View.GONE
        overlayView.text = ""
        overlayView.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        overlayView.includeFontPadding = false
        overlayView.setHorizontallyScrolling(true)
        overlayView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    labelsExpanded = true
                    renderSnapshot()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    labelsExpanded = false
                    renderSnapshot()
                    true
                }
                else -> true
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(overlayView) { view, insets ->
            applyInsets(view, insets)
            insets
        }
        ViewCompat.requestApplyInsets(overlayView)
    }

    fun onResume() {
        running = true
        if (visible) {
            primeSamplingState()
            startMemorySampler()
            renderSnapshot()
            scheduleNextUpdate()
        }
    }

    fun onPause() {
        running = false
        stopUpdates()
        stopMemorySampler()
    }

    fun onDestroy() {
        stopUpdates()
        stopMemorySampler()
    }

    fun setVisible(visible: Boolean) {
        if (this.visible == visible) {
            return
        }
        this.visible = visible
        // deep diag 模式时 view 始终隐藏（由 mod 侧浮层替代）
        overlayView.visibility = if (visible && !performanceDeepDiagnostics) View.VISIBLE else View.GONE
        if (!visible) {
            stopUpdates()
            stopMemorySampler()
            return
        }
        primeSamplingState()
        startMemorySampler()
        renderSnapshot()
        if (running) {
            scheduleNextUpdate()
        }
    }

    private fun primeSamplingState() {
        val nowMs = SystemClock.elapsedRealtime()
        lastSampleElapsedMs = SystemClock.elapsedRealtime()
        lastSwapCount = readSwapCount()
        jvmGcLogRemainder = ""
        gcEventTimestampsMs.clear()
        latestGcEventsPerMinute = 0
        latestGcWarmupAgeSeconds = null
        primeGcHistory(nowMs)
    }

    private fun scheduleNextUpdate() {
        overlayView.removeCallbacks(updateRunnable)
        if (running && visible) {
            overlayView.postDelayed(updateRunnable, REFRESH_INTERVAL_MS)
        }
    }

    private fun stopUpdates() {
        overlayView.removeCallbacks(updateRunnable)
    }

    private fun renderSnapshot() {
        val nowMs = SystemClock.elapsedRealtime()
        val swapCount = readSwapCount()
        val elapsedMs = (nowMs - lastSampleElapsedMs).coerceAtLeast(1L)
        val renderedFrames = if (swapCount >= lastSwapCount) {
            swapCount - lastSwapCount
        } else {
            swapCount
        }
        val fps = renderedFrames.toFloat() * 1000f / elapsedMs.toFloat()
        lastSampleElapsedMs = nowMs
        lastSwapCount = swapCount

        val totalMemoryBytes = latestRssBytes ?: latestPssSnapshot?.totalPssBytes
        val gcEventsPerMinute = latestGcEventsPerMinute
        val gcWarmupAgeSeconds = latestGcWarmupAgeSeconds
        val jvmRuntimeMemorySnapshot = readJvmRuntimeMemorySnapshot()
        val jvmText = formatJvmMemory(jvmRuntimeMemorySnapshot)
        val gpuEstimatedText = latestGpuEstimatedBytes?.let(::formatMb) ?: "--"
        val totalText = totalMemoryBytes?.let(::formatMb) ?: "--"
        val gcText = buildString {
            append(gcEventsPerMinute)
            append("/min")
            gcWarmupAgeSeconds?.let { append("*") }
        }

        // deep diag 模式时启动器浮层隐藏，由 mod 侧 FrameHud 渲染统一浮层
        if (!performanceDeepDiagnostics) {
            overlayView.text = buildString(240) {
                appendMetric("▣", "渲染", rendererSummary)
                appendMetric("◷", "FPS", String.format(Locale.US, "%.1f", fps))
                appendMetric("J", "JVM", jvmText)
                appendMetric("G", "GPU估算", gpuEstimatedText)
                appendMetric("Σ", "总计", totalText)
                appendMetric("↺", "GC", gcText)
            }
        } else {
            overlayView.text = ""
        }
        // 每次刷新都写快照，mod 侧 FrameHud 低频读取
        writeSnapshotFile(fps, jvmText, gpuEstimatedText, totalText, gcText)
    }

    private fun startMemorySampler() {
        if (memorySamplerRunning) {
            return
        }
        memorySamplerRunning = true
        if (latestPssSampleElapsedMs == 0L) {
            latestPssSampleElapsedMs = SystemClock.elapsedRealtime() - PSS_REFRESH_INTERVAL_MS
        }
        val samplerThread = Thread({
            while (memorySamplerRunning && !Thread.currentThread().isInterrupted) {
                val nowMs = SystemClock.elapsedRealtime()
                latestRssBytes = readRssBytes()
                if (nowMs - latestPssSampleElapsedMs >= PSS_REFRESH_INTERVAL_MS) {
                    latestPssSnapshot = readPssSnapshot()
                    readLatestGpuResourceSummary()?.let { summary ->
                        latestGpuEstimatedBytes = summary.estimatedBytes
                        latestGpuGuardianReclaimedBytes = summary.guardianReclaimedBytes
                    }
                    latestPssSampleElapsedMs = nowMs
                }
                updateGcFrequency(nowMs)
                try {
                    Thread.sleep(REFRESH_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "STS-PerfOverlayMemory")
        samplerThread.isDaemon = true
        memorySamplerThread = samplerThread
        samplerThread.start()
    }

    private fun stopMemorySampler() {
        memorySamplerRunning = false
        val samplerThread = memorySamplerThread
        memorySamplerThread = null
        samplerThread?.interrupt()
    }

    private fun readSwapCount(): Int {
        return try {
            CallbackBridge.nativeGetGlSwapCount().coerceAtLeast(0)
        } catch (_: Throwable) {
            0
        }
    }

    private fun updateGcFrequency(nowMs: Long) {
        scanJvmGcLog(nowMs)
        pruneGcEvents(nowMs)
        val windowMs = resolveGcObservedWindowMs(nowMs)
        latestGcEventsPerMinute = calculateGcEventsPerMinute(windowMs)
        latestGcWarmupAgeSeconds = if (windowMs < GC_WINDOW_DURATION_MS) {
            (windowMs / 1000L).toInt()
        } else {
            null
        }
    }

    private fun scanJvmGcLog(nowMs: Long) {
        if (!jvmGcLogFile.isFile) {
            return
        }
        val knownLength = jvmGcLogFile.length().coerceAtLeast(0L)
        if (jvmGcLogOffset > knownLength) {
            jvmGcLogOffset = 0L
            jvmGcLogRemainder = ""
        }

        var startOffset = jvmGcLogOffset
        var bytesToReadLong = knownLength - startOffset
        if (bytesToReadLong <= 0L) {
            return
        }
        if (bytesToReadLong > GC_LOG_SCAN_MAX_BYTES) {
            startOffset = knownLength - GC_LOG_SCAN_MAX_BYTES
            bytesToReadLong = GC_LOG_SCAN_MAX_BYTES.toLong()
            jvmGcLogRemainder = ""
        }

        val bytesToRead = bytesToReadLong.toInt()
        if (bytesToRead <= 0) {
            return
        }

        RandomAccessFile(jvmGcLogFile, "r").use { raf ->
            raf.seek(startOffset)
            val buffer = ByteArray(bytesToRead)
            raf.readFully(buffer)
            jvmGcLogOffset = startOffset + bytesToRead
            consumeGcLogChunk(
                chunkBytes = buffer,
                nowMs = nowMs,
                preserveRemainder = true
            )
        }
    }

    private fun primeGcHistory(nowMs: Long) {
        if (!jvmGcLogFile.isFile) {
            jvmGcLogOffset = 0L
            return
        }
        val knownLength = jvmGcLogFile.length().coerceAtLeast(0L)
        val startOffset = (knownLength - GC_LOG_HISTORY_SCAN_MAX_BYTES).coerceAtLeast(0L)
        val bytesToRead = (knownLength - startOffset).toInt()
        if (bytesToRead <= 0) {
            jvmGcLogOffset = knownLength
            return
        }

        RandomAccessFile(jvmGcLogFile, "r").use { raf ->
            raf.seek(startOffset)
            val buffer = ByteArray(bytesToRead)
            raf.readFully(buffer)
            jvmGcLogOffset = knownLength
            jvmGcLogRemainder = ""
            consumeGcLogChunk(
                chunkBytes = buffer,
                nowMs = nowMs,
                preserveRemainder = false
            )
        }
        pruneGcEvents(nowMs)
        val windowMs = resolveGcObservedWindowMs(nowMs)
        latestGcEventsPerMinute = calculateGcEventsPerMinute(windowMs)
        latestGcWarmupAgeSeconds = if (windowMs < GC_WINDOW_DURATION_MS) {
            (windowMs / 1000L).toInt()
        } else {
            null
        }
    }

    private fun consumeGcLogChunk(
        chunkBytes: ByteArray,
        nowMs: Long,
        preserveRemainder: Boolean
    ) {
        var chunkText = String(chunkBytes, StandardCharsets.UTF_8)
        if (jvmGcLogRemainder.isNotEmpty()) {
            chunkText = jvmGcLogRemainder + chunkText
        }

        val endsWithLineBreak = chunkText.endsWith("\n") || chunkText.endsWith("\r")
        val parts = chunkText.split('\n')
        val lines = if (preserveRemainder && !endsWithLineBreak) {
            jvmGcLogRemainder = parts.lastOrNull().orEmpty()
            if (parts.isNotEmpty()) parts.dropLast(1) else emptyList()
        } else {
            jvmGcLogRemainder = ""
            parts
        }
        appendGcEventsFromLines(lines, nowMs)
    }

    private fun appendGcEventsFromLines(lines: List<String>, nowMs: Long) {
        if (lines.isEmpty()) {
            return
        }
        val gcLines = ArrayList<Pair<String, Double?>>(lines.size)
        var latestLogSeconds: Double? = null
        for (rawLine in lines) {
            val parsedSeconds = parseGcLogSeconds(rawLine)
            if (parsedSeconds != null) {
                latestLogSeconds = maxOf(latestLogSeconds ?: parsedSeconds, parsedSeconds)
            }
            if (isGcEventLine(rawLine)) {
                gcLines.add(rawLine to parsedSeconds)
            }
        }
        if (gcLines.isEmpty()) {
            return
        }

        for ((_, parsedSeconds) in gcLines) {
            val eventElapsedMs = if (parsedSeconds != null && latestLogSeconds != null) {
                val deltaMs = ((latestLogSeconds - parsedSeconds) * 1000.0).toLong()
                nowMs - deltaMs.coerceAtLeast(0L)
            } else {
                nowMs
            }
            gcEventTimestampsMs.addLast(eventElapsedMs.coerceAtMost(nowMs))
        }
    }

    private fun isGcEventLine(rawLine: String): Boolean {
        val line = rawLine.trim()
        if (line.isEmpty()) {
            return false
        }
        if (line.contains("[Full GC")) {
            return true
        }
        if (line.contains("[GC pause")) {
            return true
        }
        return line.contains("[GC ") &&
            !line.contains("[GC concurrent") &&
            !line.contains("[GC remark") &&
            !line.contains("[GC cleanup")
    }

    private fun parseGcLogSeconds(rawLine: String): Double? {
        val trimmed = rawLine.trimStart()
        val separatorIndex = trimmed.indexOf(':')
        if (separatorIndex <= 0) {
            return null
        }
        return trimmed.substring(0, separatorIndex).trim().toDoubleOrNull()
    }

    private fun pruneGcEvents(nowMs: Long) {
        val cutoffMs = nowMs - GC_WINDOW_DURATION_MS
        while (gcEventTimestampsMs.isNotEmpty() && gcEventTimestampsMs.first() < cutoffMs) {
            gcEventTimestampsMs.removeFirst()
        }
    }

    private fun resolveGcObservedWindowMs(nowMs: Long): Long {
        val sessionStartElapsedMs = readJvmLaunchStartedElapsedMs()
        if (sessionStartElapsedMs <= 0L || nowMs <= sessionStartElapsedMs) {
            return GC_WINDOW_DURATION_MS
        }
        return (nowMs - sessionStartElapsedMs)
            .coerceAtLeast(REFRESH_INTERVAL_MS)
            .coerceAtMost(GC_WINDOW_DURATION_MS)
    }

    private fun calculateGcEventsPerMinute(windowMs: Long): Int {
        if (gcEventTimestampsMs.isEmpty()) {
            return 0
        }
        if (windowMs >= GC_WINDOW_DURATION_MS) {
            return gcEventTimestampsMs.size
        }
        val scaled = gcEventTimestampsMs.size.toDouble() *
            GC_WINDOW_DURATION_MS.toDouble() /
            windowMs.coerceAtLeast(1L).toDouble()
        return scaled.toInt()
    }

    private fun readRssBytes(): Long? {
        return try {
            procStatusFile.useLines { lines ->
                lines.firstNotNullOfOrNull { line ->
                    if (!line.startsWith("VmRSS:")) {
                        return@firstNotNullOfOrNull null
                    }
                    val parts = line.trim().split(Regex("\\s+"))
                    parts.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0L)?.times(1024L)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readPssSnapshot(): PssMemorySnapshot? {
        val manager = activityManager ?: return null
        return try {
            val info = manager.getProcessMemoryInfo(intArrayOf(processId))
                ?.firstOrNull()
                ?: return null
            val nativeHeapSummaryBytes = readMemoryStatBytes(info, "summary.native-heap")
            val graphicsSummaryBytes = readMemoryStatBytes(info, "summary.graphics")
            val glMtrackPssBytes = MemoryInfoDetailReader.readGlMtrackPssBytes(info)
            val unknownPssBytes = MemoryInfoDetailReader.readUnknownPssBytes(info)
                ?: readMemoryStatBytes(info, "summary.private-other")
            PssMemorySnapshot(
                totalPssBytes = info.totalPss.toLong().coerceAtLeast(0L) * BYTES_PER_KB,
                dalvikPssBytes = info.dalvikPss.toLong().coerceAtLeast(0L) * BYTES_PER_KB,
                nativePssBytes = info.nativePss.toLong().coerceAtLeast(0L) * BYTES_PER_KB,
                otherPssBytes = info.otherPss.toLong().coerceAtLeast(0L) * BYTES_PER_KB,
                nativeHeapSummaryBytes = nativeHeapSummaryBytes,
                graphicsSummaryBytes = graphicsSummaryBytes,
                glMtrackPssBytes = glMtrackPssBytes,
                unknownPssBytes = unknownPssBytes
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun readLatestGpuResourceSummary(): GpuResourceSummary? {
        if (!latestLogFile.isFile) {
            return null
        }
        return try {
            val length = latestLogFile.length().coerceAtLeast(0L)
            val startOffset = (length - GPU_SUMMARY_SCAN_MAX_BYTES).coerceAtLeast(0L)
            val bytesToRead = (length - startOffset).toInt()
            if (bytesToRead <= 0) {
                return null
            }
            RandomAccessFile(latestLogFile, "r").use { raf ->
                raf.seek(startOffset)
                val buffer = ByteArray(bytesToRead)
                raf.readFully(buffer)
                val text = String(buffer, StandardCharsets.UTF_8)
                val latestSummary = text.lineSequence()
                    .filter { line -> line.contains("[gdx-diag] GpuResources summary") }
                    .lastOrNull()
                    ?: return null
                val textureBytes = parseSummaryLong(latestSummary, "textureBytes") ?: 0L
                val frameBufferBytes = parseSummaryLong(latestSummary, "frameBufferBytes") ?: 0L
                val estimatedBytes = textureBytes + frameBufferBytes
                val guardianTextureBytes = parseSummaryLong(latestSummary, "guardianTextureBytes") ?: 0L
                val guardianFboBytes = parseSummaryLong(latestSummary, "guardianFboBytes") ?: 0L
                val guardianReclaimedBytes = guardianTextureBytes + guardianFboBytes
                GpuResourceSummary(
                    estimatedBytes = estimatedBytes.takeIf { it > 0L },
                    guardianReclaimedBytes = guardianReclaimedBytes.takeIf { it > 0L }
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseSummaryLong(line: String, key: String): Long? {
        val regex = synchronized(SUMMARY_NUMBER_REGEX_CACHE) {
            SUMMARY_NUMBER_REGEX_CACHE.getOrPut(key) { Regex("(?:^| )${Regex.escape(key)}=([0-9]+)") }
        }
        return regex.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    private fun readMemoryStatBytes(info: Debug.MemoryInfo, key: String): Long? {
        return info.getMemoryStat(key)
            ?.trim()
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?.times(BYTES_PER_KB)
    }

    private fun formatMb(bytes: Long): String {
        val valueMb = (bytes.coerceAtLeast(0L) / BYTES_PER_MB).toInt()
        return String.format(Locale.US, "%dMB", valueMb)
    }

    private fun formatJvmMemory(snapshot: JvmRuntimeMemorySnapshot?): String {
        if (snapshot == null) {
            return "--/--"
        }
        return formatMb(snapshot.heapUsedBytes) + "/" + formatMb(snapshot.heapMaxBytes)
    }

    private fun StringBuilder.appendMetric(icon: String, label: String, value: String) {
        if (isNotEmpty()) {
            append("   ")
        }
        append(if (labelsExpanded) label else icon)
        append(' ')
        append(value)
    }

    /**
     * 写启动器侧性能快照，供 amethyst-frame-probe mod 的 FrameHud 读取。
     * 格式：key=value;key=value…\n，与 jvm_heap_snapshot.txt 保持一致。
     * 仅在浮层可见时写入，避免浮层关闭后文件残留误导 mod 侧读取。
     */
    private fun writeSnapshotFile(
        fps: Float,
        jvmText: String,
        gpuEstimatedText: String,
        totalMemText: String,
        gcText: String
    ) {
        try {
            val line = "renderer=${rendererSummary};fps=${String.format(Locale.US, "%.1f", fps)};jvm=${jvmText};gpu=${gpuEstimatedText};mem=${totalMemText};gc=${gcText}\n"
            snapshotFile.writeText(line, Charsets.UTF_8)
        } catch (_: Throwable) {}
    }

    private fun applyInsets(view: View, insets: WindowInsetsCompat) {
        val layoutParams = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.navigationBars()
        )
        layoutParams.leftMargin = 0
        layoutParams.rightMargin = 0
        layoutParams.topMargin = 0
        layoutParams.bottomMargin = bars.bottom
        view.layoutParams = layoutParams
    }
}

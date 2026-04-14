package io.stamethyst.backend.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.util.Log
import io.stamethyst.backend.crash.ProcessExitInfoCapture
import io.stamethyst.backend.crash.ProcessExitSummary
import io.stamethyst.backend.launch.JvmRuntimeMemorySnapshot
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

internal object MemoryDiagnosticsLogger {
    private const val LOGCAT_TAG = "STS-MemDiag"
    private const val MAX_BYTES_PER_FILE = 256L * 1024L
    private const val MAX_ROTATED_FILES = 4
    private const val DEFAULT_TOP_FILES = 3
    private val installLock = Any()

    @Volatile
    private var installed = false

    @Volatile
    private var cachedProcessName: String? = null

    @JvmStatic
    fun install(context: Context) {
        if (!shouldLogCurrentProcess(context)) {
            return
        }
        synchronized(installLock) {
            if (installed) {
                return
            }
            installed = true
        }
        val safeContext = context.applicationContext
        val extras = linkedMapOf<String, Any?>(
            "deviceMemory" to buildDeviceMemoryProfile(safeContext)
        )
        ProcessExitInfoCapture.peekLatestMemoryPressureExitInfo(safeContext)?.let { summary ->
            extras["recentMemoryPressureExit"] = buildProcessExitPayload(summary)
        }
        logEvent(
            context = safeContext,
            event = "process_started",
            extras = extras,
            includeMemorySnapshot = true
        )
    }

    @JvmStatic
    @JvmOverloads
    fun logEvent(
        context: Context,
        event: String,
        extras: Map<String, Any?> = emptyMap(),
        includeMemorySnapshot: Boolean = true
    ) {
        if (!shouldLogCurrentProcess(context)) {
            return
        }
        runCatching {
            val payload = buildEventPayload(
                context = context.applicationContext,
                event = event,
                extras = extras,
                includeMemorySnapshot = includeMemorySnapshot
            )
            val line = payload.toString()
            Log.i(LOGCAT_TAG, line)
            appendLineToRotatingFile(context.applicationContext, line)
        }.onFailure { error ->
            Log.w(LOGCAT_TAG, "Failed to record memory diagnostics for event=$event", error)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun logLowMemory(
        context: Context,
        source: String,
        extras: Map<String, Any?> = emptyMap()
    ) {
        val fields = LinkedHashMap<String, Any?>(extras.size + 1)
        fields.putAll(extras)
        fields["source"] = source
        logEvent(context, "on_low_memory", fields)
    }

    @JvmStatic
    @JvmOverloads
    fun logTrimMemory(
        context: Context,
        source: String,
        level: Int,
        extras: Map<String, Any?> = emptyMap()
    ) {
        val fields = LinkedHashMap<String, Any?>(extras.size + 3)
        fields.putAll(extras)
        fields["source"] = source
        fields["trimLevel"] = level
        fields["trimLevelName"] = trimMemoryLevelName(level)
        logEvent(context, "on_trim_memory", fields)
    }

    @JvmStatic
    @JvmOverloads
    fun logJvmHeapSnapshot(
        context: Context,
        event: String,
        snapshot: JvmRuntimeMemorySnapshot,
        extras: Map<String, Any?> = emptyMap()
    ) {
        val fields = LinkedHashMap<String, Any?>(extras.size + 3)
        fields.putAll(extras)
        fields["jvmHeapUsedBytes"] = snapshot.heapUsedBytes
        fields["jvmHeapMaxBytes"] = snapshot.heapMaxBytes
        if (snapshot.heapMaxBytes > 0L) {
            fields["jvmHeapUsagePercent"] = (
                snapshot.heapUsedBytes.toDouble() / snapshot.heapMaxBytes.toDouble() * 100.0
                ).roundToInt()
        }
        logEvent(context, event, fields)
    }

    @JvmStatic
    fun logModSnapshot(
        context: Context,
        event: String,
        launchMode: String,
        enabledLibraryFiles: Collection<File>,
        runtimeModFiles: Collection<File>,
        launchModIds: Collection<String>? = null
    ) {
        val fields = linkedMapOf<String, Any?>(
            "launchMode" to launchMode,
            "enabledLibraryMods" to buildFileStatsPayload(enabledLibraryFiles),
            "runtimeMods" to buildFileStatsPayload(runtimeModFiles)
        )
        launchModIds?.let { modIds ->
            val cleaned = modIds
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            fields["launchModCount"] = cleaned.size
            fields["launchModIds"] = JSONArray(cleaned)
        }
        logEvent(context, event, fields)
    }

    internal fun trimMemoryLevelName(level: Int): String {
        return when (level) {
            5 -> "TRIM_MEMORY_RUNNING_MODERATE"
            10 -> "TRIM_MEMORY_RUNNING_LOW"
            15 -> "TRIM_MEMORY_RUNNING_CRITICAL"
            20 -> "TRIM_MEMORY_UI_HIDDEN"
            40 -> "TRIM_MEMORY_BACKGROUND"
            60 -> "TRIM_MEMORY_MODERATE"
            80 -> "TRIM_MEMORY_COMPLETE"
            else -> "LEVEL_$level"
        }
    }

    internal fun buildFileStatsPayload(
        files: Collection<File>,
        topFilesLimit: Int = DEFAULT_TOP_FILES
    ): JSONObject {
        val sortedFiles = files
            .asSequence()
            .filter { it.isFile }
            .sortedWith(compareByDescending<File> { it.length() }.thenBy { it.name })
            .toList()
        val payload = JSONObject()
        payload.put("count", sortedFiles.size)
        payload.put("totalBytes", sortedFiles.sumOf { it.length().coerceAtLeast(0L) })
        val largest = sortedFiles.firstOrNull()
        payload.put("largestFileName", largest?.name ?: "none")
        payload.put("largestFileBytes", largest?.length()?.coerceAtLeast(0L) ?: 0L)
        val topFiles = JSONArray()
        sortedFiles.take(topFilesLimit.coerceAtLeast(0)).forEach { file ->
            topFiles.put(
                JSONObject().apply {
                    put("name", file.name)
                    put("bytes", file.length().coerceAtLeast(0L))
                }
            )
        }
        payload.put("topFiles", topFiles)
        return payload
    }

    private fun buildEventPayload(
        context: Context,
        event: String,
        extras: Map<String, Any?>,
        includeMemorySnapshot: Boolean
    ): JSONObject {
        val nowMs = System.currentTimeMillis()
        return JSONObject().apply {
            put("timestampMs", nowMs)
            put("timestamp", formatTimestamp(nowMs))
            put("processName", resolveProcessName(context))
            put("pid", Process.myPid())
            put("event", event)
            if (includeMemorySnapshot) {
                runCatching { captureMemorySnapshot(context) }
                    .getOrNull()
                    ?.let { put("memory", it) }
            }
            extras.forEach { (key, value) ->
                putJsonValue(this, key, value)
            }
        }
    }

    private fun captureMemorySnapshot(context: Context): JSONObject {
        val runtime = Runtime.getRuntime()
        val javaTotalBytes = runtime.totalMemory().coerceAtLeast(0L)
        val javaFreeBytes = runtime.freeMemory().coerceAtLeast(0L)
        val javaMaxBytes = runtime.maxMemory().coerceAtLeast(0L)
        val javaUsedBytes = (javaTotalBytes - javaFreeBytes).coerceAtLeast(0L)

        val snapshot = JSONObject()
        snapshot.put("javaUsedBytes", javaUsedBytes)
        snapshot.put("javaTotalBytes", javaTotalBytes)
        snapshot.put("javaFreeBytes", javaFreeBytes)
        snapshot.put("javaMaxBytes", javaMaxBytes)
        if (javaMaxBytes > 0L) {
            snapshot.put(
                "javaUsagePercent",
                (javaUsedBytes.toDouble() / javaMaxBytes.toDouble() * 100.0).roundToInt()
            )
        }

        snapshot.put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L))
        snapshot.put("nativeSizeBytes", Debug.getNativeHeapSize().coerceAtLeast(0L))
        snapshot.put("nativeFreeBytes", Debug.getNativeHeapFreeSize().coerceAtLeast(0L))

        val activityManager = context.getSystemService(ActivityManager::class.java)
        if (activityManager != null) {
            val systemMemory = ActivityManager.MemoryInfo()
            runCatching { activityManager.getMemoryInfo(systemMemory) }
            snapshot.put("systemAvailMemBytes", systemMemory.availMem.coerceAtLeast(0L))
            snapshot.put("systemTotalMemBytes", systemMemory.totalMem.coerceAtLeast(0L))
            snapshot.put("systemThresholdBytes", systemMemory.threshold.coerceAtLeast(0L))
            snapshot.put("systemLowMemory", systemMemory.lowMemory)
            snapshot.put("isLowRamDevice", activityManager.isLowRamDevice)
            snapshot.put("memoryClassMb", activityManager.memoryClass)
            snapshot.put("largeMemoryClassMb", activityManager.largeMemoryClass)

            val memoryInfo = runCatching {
                activityManager.getProcessMemoryInfo(intArrayOf(Process.myPid()))
                    ?.getOrNull(0)
            }.getOrNull()
            if (memoryInfo != null) {
                snapshot.put("totalPssBytes", memoryInfo.totalPss.toLong().coerceAtLeast(0L) * 1024L)
                snapshot.put("dalvikPssBytes", memoryInfo.dalvikPss.toLong().coerceAtLeast(0L) * 1024L)
                snapshot.put("nativePssBytes", memoryInfo.nativePss.toLong().coerceAtLeast(0L) * 1024L)
                snapshot.put("otherPssBytes", memoryInfo.otherPss.toLong().coerceAtLeast(0L) * 1024L)
                snapshot.put(
                    "totalPrivateDirtyBytes",
                    memoryInfo.totalPrivateDirty.toLong().coerceAtLeast(0L) * 1024L
                )
                snapshot.put(
                    "totalSharedDirtyBytes",
                    memoryInfo.totalSharedDirty.toLong().coerceAtLeast(0L) * 1024L
                )
                memoryInfo.getMemoryStat("summary.graphics")
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0L }
                    ?.let { snapshot.put("graphicsBytes", it * 1024L) }
            }
        }
        return snapshot
    }

    private fun buildDeviceMemoryProfile(context: Context): JSONObject? {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        return JSONObject().apply {
            put("memoryClassMb", activityManager.memoryClass)
            put("largeMemoryClassMb", activityManager.largeMemoryClass)
            put("isLowRamDevice", activityManager.isLowRamDevice)
            put("sdkInt", Build.VERSION.SDK_INT)
        }
    }

    private fun buildProcessExitPayload(summary: ProcessExitSummary): JSONObject {
        return JSONObject().apply {
            put("pid", summary.pid)
            put("processName", summary.processName)
            put("reason", summary.reasonName)
            put("status", summary.status)
            put("timestamp", summary.timestamp)
            put("description", summary.description)
            put("isSignal", summary.isSignal)
        }
    }

    private fun putJsonValue(target: JSONObject, key: String, value: Any?) {
        when (value) {
            null -> Unit
            is JSONObject -> target.put(key, value)
            is JSONArray -> target.put(key, value)
            is Boolean, is Int, is Long, is Float, is Double, is String -> target.put(key, value)
            is File -> target.put(key, value.absolutePath)
            is Collection<*> -> {
                val array = JSONArray()
                value.forEach { entry ->
                    when (val converted = toJsonValue(entry)) {
                        null -> Unit
                        else -> array.put(converted)
                    }
                }
                target.put(key, array)
            }
            else -> target.put(key, value.toString())
        }
    }

    private fun toJsonValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is JSONObject, is JSONArray -> value
            is Boolean, is Int, is Long, is Float, is Double, is String -> value
            is File -> value.absolutePath
            else -> value.toString()
        }
    }

    private fun shouldLogCurrentProcess(context: Context): Boolean {
        val processName = resolveProcessName(context)
        val packageName = context.packageName
        return processName == packageName ||
            processName == "$packageName:game" ||
            processName == "$packageName:prep"
    }

    private fun resolveProcessName(context: Context): String {
        cachedProcessName?.let { return it }
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.app.Application.getProcessName()
            } else {
                readProcessNameFromProcfs()
            }
        }.getOrNull()
            ?.trim()
            ?.ifEmpty { null }
            ?: context.packageName
        cachedProcessName = resolved
        return resolved
    }

    private fun readProcessNameFromProcfs(): String? {
        return runCatching {
            FileInputStream("/proc/self/cmdline").use { input ->
                val buffer = ByteArray(256)
                val read = input.read(buffer)
                if (read <= 0) {
                    return null
                }
                val end = buffer.indexOf(0).takeIf { it >= 0 } ?: read
                String(buffer, 0, end, StandardCharsets.UTF_8).trim()
            }
        }.getOrNull()
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(timestampMs))
    }

    private fun appendLineToRotatingFile(context: Context, line: String) {
        val baseFile = RuntimePaths.memoryDiagnosticsLog(context)
        val bytes = (line + '\n').toByteArray(StandardCharsets.UTF_8)
        synchronized(installLock) {
            val parent = baseFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val currentLength = if (baseFile.isFile) {
                baseFile.length().coerceAtLeast(0L)
            } else {
                0L
            }
            if (currentLength > 0L && currentLength + bytes.size > MAX_BYTES_PER_FILE) {
                rotateFiles(baseFile)
            }
            FileOutputStream(baseFile, true).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
        }
    }

    private fun rotateFiles(baseFile: File) {
        val parent = baseFile.parentFile ?: return
        val oldest = File(parent, "${baseFile.name}.$MAX_ROTATED_FILES")
        if (oldest.exists()) {
            oldest.delete()
        }
        for (index in MAX_ROTATED_FILES - 1 downTo 0) {
            val source = if (index == 0) {
                baseFile
            } else {
                File(parent, "${baseFile.name}.$index")
            }
            if (!source.exists()) {
                continue
            }
            val target = File(parent, "${baseFile.name}.${index + 1}")
            if (target.exists()) {
                target.delete()
            }
            source.renameTo(target)
        }
    }
}

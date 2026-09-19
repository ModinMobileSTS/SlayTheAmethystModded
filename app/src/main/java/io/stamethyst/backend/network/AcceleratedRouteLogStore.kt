package io.stamethyst.backend.network

import android.content.Context
import android.util.Log
import io.stamethyst.backend.diag.RollingTextLogWriter
import io.stamethyst.backend.process.AppProcess
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the Watt acceleration layer's routing decisions.
 *
 * [AcceleratedRouteEvents] only feeds an in-memory progress bar, so a market or Steam Cloud load
 * that fails "out in the field" left no trace of which forward nodes were tried, in what order, and
 * whether the request finally fell back to the blocked official origin. This store installs a cheap
 * listener once per launcher process and appends every routing event to a rolling text log plus
 * logcat, so a diagnostics archive can show the full route trace for an unreachable market.
 */
internal object AcceleratedRouteLogStore {
    private const val LOGCAT_TAG = "WattRoute"
    private const val MAX_BYTES_PER_FILE = 256L * 1024L
    private const val MAX_ROTATED_FILES = 4

    private val installLock = Any()

    @Volatile
    private var installed = false

    @Volatile
    private var writer: RollingTextLogWriter? = null

    @JvmStatic
    fun install(context: Context) {
        val appContext = context.applicationContext
        // Route events only matter for the launcher process; a single writer per process keeps two
        // processes from appending to the same rotating file.
        if (!AppProcess.isDefaultProcess(appContext)) {
            return
        }
        synchronized(installLock) {
            if (installed) {
                return
            }
            installed = true
            writer = RollingTextLogWriter(
                baseFile = RuntimePaths.acceleratedRouteLog(appContext),
                maxBytesPerFile = MAX_BYTES_PER_FILE,
                maxFiles = MAX_ROTATED_FILES,
                appendExisting = true,
            )
        }
        AcceleratedRouteEvents.addListener(::recordEvent)
    }

    @JvmStatic
    fun listLogFiles(context: Context): List<File> =
        RuntimePaths.listAcceleratedRouteLogFiles(context)

    private fun recordEvent(event: AcceleratedRouteEvent) {
        val line = formatEvent(event)
        Log.i(LOGCAT_TAG, line)
        val currentWriter = writer ?: return
        runCatching { currentWriter.appendLine(line) }
    }

    private fun formatEvent(event: AcceleratedRouteEvent): String {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date())
        val host = event.host
        return when (event) {
            is AcceleratedRouteEvent.RouteDiscoveryStarted ->
                "ts=$ts event=route_discovery_started host=$host"

            is AcceleratedRouteEvent.RouteDiscovered ->
                "ts=$ts event=route_discovered host=$host forwardTargetCount=${event.forwardTargetCount} preferOfficial=${event.preferOfficial}"

            is AcceleratedRouteEvent.RouteDiscoveryFailed ->
                "ts=$ts event=route_discovery_failed host=$host"

            is AcceleratedRouteEvent.ForwardTargetAttempt ->
                "ts=$ts event=forward_attempt host=$host target=${event.target}"

            is AcceleratedRouteEvent.ForwardTargetFailed ->
                "ts=$ts event=forward_failed host=$host target=${event.target} reason=${event.reason}"

            is AcceleratedRouteEvent.ForwardTargetSucceeded ->
                "ts=$ts event=forward_succeeded host=$host target=${event.target}"

            is AcceleratedRouteEvent.OfficialAttempt ->
                "ts=$ts event=official_attempt host=$host"

            is AcceleratedRouteEvent.OfficialFailed ->
                "ts=$ts event=official_failed host=$host reason=${event.reason}"

            is AcceleratedRouteEvent.OfficialSucceeded ->
                "ts=$ts event=official_succeeded host=$host"
        }
    }
}

package io.stamethyst.backend.diag.refresh

import android.app.Activity
import android.app.GameManager
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import io.stamethyst.BuildConfig
import io.stamethyst.config.LauncherConfig
import io.stamethyst.backend.render.DisplayRefreshRatePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.coroutines.resume

/** Runs on the main thread; deliberately makes no shell, privileged, network or settings writes. */
internal class RefreshRateDiagnosticsRunner(private val activity: Activity, private val probe: RefreshRateProbeView) {
    private val window get() = activity.window
    private val manager = activity.getSystemService(DisplayManager::class.java)
    private val power = activity.getSystemService(PowerManager::class.java)
    private val startNs = SystemClock.elapsedRealtimeNanos()
    private val startedAt = Instant.now().toString()
    private val events = mutableListOf<String>()
    private fun elapsed() = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000

    private fun display(): Display? = probe.display ?: manager?.getDisplay(Display.DEFAULT_DISPLAY)
    private fun Float.valid(): Float? = takeIf { isFinite() && it > 0f }
    private fun error(error: Throwable) = "${error.javaClass.name}: ${error.message}\n${error.stackTraceToString()}"

    private fun observation(reason: String): RefreshRateObservation {
        val display = display()
        val mode = display?.mode
        val attrs = window.attributes
        return RefreshRateObservation(
            elapsed(), reason, display?.displayId, display?.refreshRate?.valid(), mode?.modeId,
            mode?.refreshRate?.valid(), mode?.let { "${it.physicalWidth}x${it.physicalHeight}" }.orEmpty(),
            attrs.preferredDisplayModeId, attrs.preferredRefreshRate.takeIf { it.isFinite() } ?: 0f,
            window.decorView.hasWindowFocus(), power?.isPowerSaveMode,
            if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else null,
            probe.requestedViewRate(), probe.composeHighVoteInstalled(),
        )
    }

    private fun environment(): Map<String, String> = buildMap {
        put("package", activity.packageName)
        put("version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); ${BuildConfig.BUILD_TYPE}")
        put("pid.uid", "${Process.myPid()}.${Process.myUid()}")
        put("activity", activity.javaClass.name)
        put("device", "${Build.MANUFACTURER} / ${Build.MODEL} / ${Build.DEVICE}")
        put("android", "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        put("firmware", Build.DISPLAY)
        put("fingerprint", Build.FINGERPRINT)
        put("colorOs", "Not available via a stable public API; firmware/fingerprint supplied above")
        put("multiWindow", activity.isInMultiWindowMode.toString())
        put("supportedModes", display()?.supportedModes?.joinToString("\n") { mode ->
            "id=${mode.modeId}; ${mode.physicalWidth}x${mode.physicalHeight}; ${mode.refreshRate} Hz" +
                if (Build.VERSION.SDK_INT >= 31) "; alternatives=${mode.alternativeRefreshRates.joinToString()}" else ""
        } ?: "unavailable")
        fun setting(name: String, block: () -> String?) { put(name, runCatching(block).getOrElse { "unavailable: ${it.javaClass.simpleName}" } ?: "not exposed") }
        setting("settings.peak_refresh_rate") { Settings.System.getString(activity.contentResolver, "peak_refresh_rate") }
        setting("settings.min_refresh_rate") { Settings.System.getString(activity.contentResolver, "min_refresh_rate") }
        setting("settings.brightness") { Settings.System.getString(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }
        setting("settings.brightness_mode") { Settings.System.getString(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) }
        val battery = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        put("battery.level_scale", "${battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)}/${battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)}")
        put("battery.plugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1).toString())
        put("battery.temperatureTenthsC", battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1).toString())
        if (Build.VERSION.SDK_INT >= 31) setting("gameMode") { activity.getSystemService(GameManager::class.java)?.gameMode?.toString() }
        put("game.targetFps", LauncherConfig.readTargetFpsWithoutMigration(activity).toString())
        put("game.framePacing", LauncherConfig.readFramePacingMode(activity).persistedValue)
        put("game.surfaceBackend", LauncherConfig.readRenderSurfaceBackend(activity).toString())
        put("game.rendererSelection", LauncherConfig.readRendererSelectionMode(activity).toString())
        put("game.manualRenderer", LauncherConfig.readManualRendererBackend(activity).toString())
        put("game.settingsScope", "Persisted configuration only; this test does not start the game JVM or inspect its effective FPS/Swappy state")
        put("test.timing", "Each phase: 700 ms settle + 2500 ms sample; callback arrival intervals, not presentation timestamps")
        put("test.surface", "SurfaceView uses hardware Canvas; TextureView uses Canvas; Compose phases draw the same pattern on a Compose Canvas. None reproduces the game's EGL/GPU workload")
        put("test.phases", RefreshRatePhase.entries.joinToString { it.key })
        put("test.requestDifference", "Original five comparison phases retain their requests (including Surface ONLY_IF_SEAMLESS). Production stages share same-resolution window mode selection, Surface DEFAULT/ALWAYS, and View High helpers with the app; no request preferences are persisted by diagnostics. Drawing workload and Activity still differ from gameplay")
        setting("display.arrSupported") {
            if (Build.VERSION.SDK_INT >= 36) display()?.hasArrSupport()?.toString() else "requires API 36"
        }
        setting("window.frameRatePowerSavingsBalanced") {
            if (Build.VERSION.SDK_INT >= 35) window.isFrameRatePowerSavingsBalanced.toString() else "requires API 35"
        }
        put("test.highVote", "High is a category, not a fixed 120 Hz command. View/Compose stages use the highest same-resolution mode as reference; production combinations use the production Surface target. composeHighVoteInstalled records modifier installation, not compositor acceptance")
        put("systemPolicy", "No privileged DUMP/READ_LOGS access; no per-UID override, physical scanout or vendor rejection reason can be established here")
    }

    suspend fun run(onPhase: (RefreshRatePhase, Int) -> Unit): RefreshRateReport {
        val original = WindowManager.LayoutParams().also { it.copyFrom(window.attributes) }
        val stages = mutableListOf<RefreshRateStage>()
        var interruption: String? = null
        var cleanup = "not attempted"
        var environment = emptyMap<String, String>()
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { events += "${elapsed()} display_added=$displayId" }
            override fun onDisplayRemoved(displayId: Int) { events += "${elapsed()} display_removed=$displayId" }
            override fun onDisplayChanged(displayId: Int) {
                if (events.size < 200) events += "${elapsed()} display_changed=$displayId appHz=${display()?.refreshRate}"
            }
        }
        var registered = false
        try {
            environment = environment()
            manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            registered = manager != null
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            for ((index, phase) in RefreshRatePhase.entries.withIndex()) {
                currentCoroutineContext().ensureActive()
                onPhase(phase, index)
                events.clear()
                probe.select(phase)
                val observations = mutableListOf(observation("before_request"))
                val phaseStart = elapsed()
                var request = ""
                var requestError: String? = null
                var completed = false
                var target = 120f
                val intervals = mutableListOf<Long>()
                var stageFailure: Throwable? = null
                try {
                    withTimeout(5000) { while (!probe.ready()) frame() }
                    try {
                        val activeDisplay = display()
                        val mode = activeDisplay?.let(DisplayRefreshRatePolicy::highestModeAtCurrentResolution)
                        if (phase.usesHighestRateReference) target = mode?.refreshRate ?: 120f
                        val attrs = window.attributes
                        attrs.preferredDisplayModeId = 0
                        attrs.preferredRefreshRate = 0f
                        window.attributes = attrs
                        when (phase.requestKind) {
                            RefreshRateRequestKind.DEFAULT -> request =
                                "Clear diagnostic window preferences; ${phase.probeKind} without explicit vote (${target} Hz reference only)"
                            RefreshRateRequestKind.MODE -> {
                                check(mode != null) { "No valid display mode at current resolution" }
                                DisplayRefreshRatePolicy.applyWindowMode(window, mode)
                                request = "preferredDisplayModeId=${mode.modeId}; ${mode.refreshRate} Hz; same resolution; setter returned, NOT system acceptance"
                            }
                            RefreshRateRequestKind.WINDOW, RefreshRateRequestKind.SURFACE_SEAMLESS -> {
                                attrs.preferredRefreshRate = 120f
                                window.attributes = attrs
                                request = "preferredDisplayModeId=0; preferredRefreshRate=120; setter returned, NOT system acceptance"
                                if (phase.requestKind == RefreshRateRequestKind.SURFACE_SEAMLESS) {
                                    if (Build.VERSION.SDK_INT < 30) {
                                        throw IllegalStateException("Surface.setFrameRate requires API 30")
                                    }
                                    val surface = checkNotNull(probe.surface()) { "Display Surface unavailable" }
                                    if (Build.VERSION.SDK_INT >= 31) surface.setFrameRate(120f,
                                        Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
                                    else surface.setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                                    request += "; Surface.setFrameRate(120, DEFAULT, ONLY_IF_SEAMLESS)"
                                }
                            }
                            RefreshRateRequestKind.VIEW_HIGH -> {
                                request = "View.setRequestedFrameRate(HIGH); no window/Surface vote; ${target} Hz reference only"
                                probe.requestHighViewVote()
                            }
                            RefreshRateRequestKind.COMPOSE_HIGH -> {
                                request = "Compose Modifier.preferredFrameRate(High), with production lifecycle/focus guard; no window/Surface vote; ${target} Hz reference only"
                                probe.requestHighComposeVote()
                            }
                            RefreshRateRequestKind.PRODUCTION -> {
                                check(mode != null) { "No valid display mode at current resolution" }
                                val surfaceRate = DisplayRefreshRatePolicy.highestSupportedRefreshRateHz(activeDisplay)
                                target = surfaceRate.takeIf { it.isFinite() && it > 0f } ?: mode.refreshRate
                                request = "Production helpers; same-resolution window mode=${mode.modeId}@${mode.refreshRate}; setter return is NOT acceptance"
                                DisplayRefreshRatePolicy.applyWindowMode(window, mode)
                                if (Build.VERSION.SDK_INT >= 30) {
                                    request += "; Surface.setFrameRate($surfaceRate, DEFAULT, " +
                                        if (Build.VERSION.SDK_INT >= 31) "ALWAYS)" else "two-argument API 30 overload)"
                                    check(surfaceRate > 0f) { "No valid highest Surface target" }
                                    DisplayRefreshRatePolicy.applySurfaceFrameRate(
                                        checkNotNull(probe.producerSurface()) { "Producer Surface unavailable" }, surfaceRate)
                                } else request += "; Surface vote skipped: API < 30 (same as production)"
                                if (Build.VERSION.SDK_INT >= 35) {
                                    request += "; View.setRequestedFrameRate(HIGH)"
                                    probe.requestHighViewVote()
                                } else request += "; View vote skipped: API < 35 (same as production)"
                            }
                        }
                    } catch (failure: Exception) { requestError = error(failure) }
                    observations += observation("after_request")
                    withTimeout(6000) {
                        val settleStart = elapsed()
                        while (elapsed() - settleStart < 700) { val now = frame(); probe.render(now) }
                        probe.resetCounts()
                        var previous = 0L
                        val sampleStart = elapsed()
                        var nextObservation = sampleStart
                        while (elapsed() - sampleStart < 2500) {
                            val now = frame()
                            if (previous != 0L) intervals += now - previous
                            previous = now
                            probe.render(now)
                            if (elapsed() >= nextObservation) {
                                observations += observation("sample")
                                nextObservation = elapsed() + 250
                            }
                        }
                        completed = true
                    }
                } catch (failure: Exception) { stageFailure = failure }
                observations += observation("end")
                stages += RefreshRateStage(phase.key, target, elapsed() - phaseStart, completed, request,
                    requestError, observations, RefreshRateFrameStats.fromIntervals(intervals, target),
                    probe.draws, probe.drawErrors, probe.lastDrawError, "${probe.width}x${probe.height}",
                    events.toList() + probe.lifecycleEvents().map { "elapsedRealtimeMs=$it" })
                stageFailure?.let { throw it }
            }
            currentCoroutineContext().ensureActive()
        } catch (failure: TimeoutCancellationException) {
            interruption = "Timed out waiting for a ready surface or frame callbacks"
        } catch (failure: CancellationException) {
            interruption = failure.message ?: "Cancelled / Activity no longer foreground"
        } catch (failure: Exception) {
            interruption = error(failure)
        } finally {
            if (registered) runCatching { manager?.unregisterDisplayListener(listener) }
            val restore = runCatching {
                val attrs = window.attributes
                attrs.preferredDisplayModeId = original.preferredDisplayModeId
                attrs.preferredRefreshRate = original.preferredRefreshRate
                if (original.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0) {
                    attrs.flags = attrs.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                }
                window.attributes = attrs
            }
            val release = runCatching { probe.release() }
            cleanup = "window=${restore.exceptionOrNull()?.let { error(it) } ?: "restored"}; probe=${release.exceptionOrNull()?.let { error(it) } ?: "released"}"
        }
        return RefreshRateReport(startedAt = startedAt, finishedAt = Instant.now().toString(),
            interruption = interruption, environment = environment, stages = stages, cleanup = cleanup)
    }

    private suspend fun frame(): Long = suspendCancellableCoroutine { continuation ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback {
            if (continuation.isActive) continuation.resume(SystemClock.elapsedRealtimeNanos())
        }
        choreographer.postFrameCallback(callback)
        continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
    }
}

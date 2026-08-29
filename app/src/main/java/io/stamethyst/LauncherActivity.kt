package io.stamethyst

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.stamethyst.backend.diag.LauncherLogcatCaptureProcessClient
import io.stamethyst.backend.diag.LogcatCaptureProcessClient
import io.stamethyst.backend.launch.AutoplayMode
import io.stamethyst.backend.launch.AutoplaySaveMode
import io.stamethyst.backend.launch.ExpectedGameExitNotice
import io.stamethyst.backend.launch.GameLaunchReturnTracker
import io.stamethyst.backend.launch.MainProcessLaunchPreparationCoordinator
import io.stamethyst.backend.launch.StartupTraceEvents
import io.stamethyst.backend.launch.StsLaunchSpec
import io.stamethyst.backend.workshop.WorkshopUpdateCheckCoordinator
import io.stamethyst.config.LegacyStsStorageMigration
import io.stamethyst.config.RuntimePaths
import io.stamethyst.backend.mods.StsJarValidator
import io.stamethyst.navigation.Route
import io.stamethyst.ui.LauncherContent
import io.stamethyst.ui.UiBusyOperation
import io.stamethyst.ui.main.MainScreenViewModel
import io.stamethyst.ui.modimport.ModImportRequestBus
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.resources.LauncherResourceGate
import io.stamethyst.ui.settings.files.SettingsFileService
import io.stamethyst.ui.settings.core.SettingsScreenViewModel
import io.stamethyst.ui.theme.LauncherTheme
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class LauncherActivity : AppCompatActivity() {
    companion object {
        private const val GAME_RETURN_ANALYSIS_DELAY_MS = 250L
        private const val GAME_RETURN_ANALYSIS_ATTEMPTS = 12
        const val EXTRA_DEBUG_LAUNCH_MODE = "io.stamethyst.debug_launch_mode"
        const val EXTRA_DEBUG_FORCE_JVM_CRASH = "io.stamethyst.debug_force_jvm_crash"
        const val EXTRA_DEBUG_FORCE_RUNTIME_CRASH = "io.stamethyst.debug_force_runtime_crash"
        const val EXTRA_DEBUG_MODE = "io.stamethyst.debug_mode"
        const val EXTRA_DEBUG_AUTOPLAY = "io.stamethyst.debug_autoplay"
        const val EXTRA_DEBUG_AUTOPLAY_SAVE_MODE = "io.stamethyst.debug_autoplay_save_mode"
        const val EXTRA_DEBUG_AUTOPLAY_MODE = "io.stamethyst.debug_autoplay_mode"
        const val EXTRA_DEBUG_AUTOPLAY_SINGLE_ROOM_SPEC = "io.stamethyst.debug_autoplay_single_room_spec"
        const val EXTRA_DEBUG_AUTOPLAY_CHOICE_DELAY_MS =
            "io.stamethyst.debug_autoplay_choice_delay_ms"
        const val EXTRA_DEBUG_PERFORMANCE_DEEP_DIAGNOSTICS =
            "io.stamethyst.debug_performance_deep_diagnostics"
        const val EXTRA_DEBUG_DISABLE_CARD_OBTAIN_EFFECT_OWNERSHIP_COMPAT =
            "io.stamethyst.debug_disable_card_obtain_effect_ownership_compat"
        const val EXTRA_CRASH_OCCURRED = "io.stamethyst.crash_occurred"
        const val EXTRA_CRASH_CODE = "io.stamethyst.crash_code"
        const val EXTRA_CRASH_IS_SIGNAL = "io.stamethyst.crash_is_signal"
        const val EXTRA_CRASH_DETAIL = "io.stamethyst.crash_detail"
        const val EXTRA_HEAP_PRESSURE_WARNING = "io.stamethyst.heap_pressure_warning"
        const val EXTRA_HEAP_PRESSURE_PEAK_USED_BYTES = "io.stamethyst.heap_pressure_peak_used_bytes"
        const val EXTRA_HEAP_PRESSURE_HEAP_MAX_BYTES = "io.stamethyst.heap_pressure_heap_max_bytes"
        const val EXTRA_HEAP_PRESSURE_CURRENT_HEAP_MB = "io.stamethyst.heap_pressure_current_heap_mb"
        const val EXTRA_HEAP_PRESSURE_SUGGESTED_HEAP_MB = "io.stamethyst.heap_pressure_suggested_heap_mb"
        private const val EXTRA_EXTERNAL_STS_IMPORT_NOTICE = "io.stamethyst.external_sts_import_notice"
        private const val EXTRA_EXTERNAL_STS_IMPORT_FILE_NAME = "io.stamethyst.external_sts_import_file_name"
        private const val STS_JAR_FILE_NAME = "desktop-1.0.jar"

        private val JAR_MIME_TYPES = setOf(
            "application/java-archive",
            "application/x-java-archive",
            "application/jar",
            "application/x-jar",
            "application/octet-stream"
        )
    }

    private sealed interface IncomingJarIntentTarget {
        data class StsJar(
            val uri: Uri,
            val displayName: String
        ) : IncomingJarIntentTarget

        data class ModJar(
            val uri: Uri
        ) : IncomingJarIntentTarget
    }

    private sealed interface ExternalImportRequest {
        data class ImportUri(
            val uri: Uri
        ) : ExternalImportRequest

        object Unsupported : ExternalImportRequest
    }

    private data class DebugLaunchRequest(
        val launchMode: String,
        val debugMode: Boolean,
        val forceJvmCrash: Boolean,
        val forceRuntimeCrash: Boolean,
        val autoplay: Boolean,
        val autoplaySaveMode: AutoplaySaveMode,
        val autoplayMode: AutoplayMode,
        val autoplaySingleRoomSpecPath: String,
        val autoplayChoiceDelayMs: Long,
        val performanceDeepDiagnostics: Boolean?,
        val cardObtainEffectOwnershipCompatEnabled: Boolean
    )

    private val mainViewModel: MainScreenViewModel by viewModels()
    private val settingsViewModel: SettingsScreenViewModel by viewModels()
    private var pendingImportDialog: AlertDialog? = null
    private var pendingImportLoadingDialog: AlertDialog? = null
    private var pendingStorageMigrationDialog: AlertDialog? = null
    private var queuedExternalImportRequest: ExternalImportRequest? = null
    private var pendingModImportFlow = false
    private var pendingGameReturnAnalysis: Runnable? = null
    private var launchedWithoutImportedStsJar = false
    private var startupAfterResourcesCompleted = false
    private var backgroundForGameLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupBackground = StartupWindowBackground.launcherColor(this)
        StartupWindowBackground.applyToWindow(window, startupBackground)
        super.onCreate(savedInstanceState)
        StartupTraceEvents.append(
            this,
            "launcher_activity_on_create",
            mapOf("debugLaunch" to intent.hasExtra(EXTRA_DEBUG_LAUNCH_MODE))
        )
        if (startDirectDebugLaunchIfRequested(intent)) {
            return
        }
        StartupWindowBackground.applyToDecorView(window, startupBackground)
        LauncherOrientationPolicy.applyTo(this)
        syncLauncherLogcatCapture()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val storageMigrationResult = runCatching {
            LegacyStsStorageMigration.migrateIfNeeded(this)
        }.getOrNull()

        val hasImportedStsJar = StsJarValidator.isValid(RuntimePaths.importedStsJar(this))
        launchedWithoutImportedStsJar = !hasImportedStsJar
        val initialRoute = if (hasImportedStsJar) {
            if (LauncherPreferences.isFirstRunSetupCompleted(this)) {
                Route.Main
            } else {
                Route.FirstRunSetup
            }
        } else {
            Route.QuickStart
        }

        settingsViewModel.syncThemeAppearance(this)
        setContent {
            LauncherTheme(
                themeMode = settingsViewModel.uiState.themeMode,
                themeColor = settingsViewModel.uiState.themeColor
            ) {
                LauncherResourceGate(
                    onResourcesReady = {
                        onStartupResourcesReady(
                            storageMigrationResult = storageMigrationResult,
                            startupIntent = intent
                        )
                    }
                ) {
                    LauncherContent(
                        initialRoute = initialRoute,
                        mainViewModel = mainViewModel,
                        settingsViewModel = settingsViewModel,
                        onMainScreenOpened = ::onMainScreenOpened,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LauncherOrientationPolicy.applyTo(this)
        setIntent(intent)
        if (!startupAfterResourcesCompleted) {
            return
        }
        handleIncomingLauncherIntent(intent)
        maybeShowExternalStsImportNotice(intent)
        maybeHandleJarIntent(intent)
        maybeScheduleGameReturnAnalysis()
    }

    override fun onResume() {
        super.onResume()
        LauncherOrientationPolicy.applyTo(this)
        syncLauncherLogcatCapture()
        maybeScheduleGameReturnAnalysis()
    }

    override fun onPause() {
        cancelPendingGameReturnAnalysis()
        super.onPause()
        if (backgroundForGameLaunch && !isFinishing && !isDestroyed) {
            finish()
        }
    }

    override fun onDestroy() {
        cancelPendingGameReturnAnalysis()
        queuedExternalImportRequest = null
        pendingImportDialog?.dismiss()
        pendingImportDialog = null
        pendingImportLoadingDialog?.dismiss()
        pendingImportLoadingDialog = null
        pendingStorageMigrationDialog?.dismiss()
        pendingStorageMigrationDialog = null
        super.onDestroy()
    }

    private fun handleIncomingLauncherIntent(incomingIntent: Intent?) {
        mainViewModel.handleIncomingIntent(this, incomingIntent)
    }

    fun markBackgroundForGameLaunch() {
        backgroundForGameLaunch = true
    }

    fun clearBackgroundForGameLaunch() {
        backgroundForGameLaunch = false
    }

    private fun startDirectDebugLaunchIfRequested(startupIntent: Intent?): Boolean {
        val request = buildDebugLaunchRequest(startupIntent) ?: return false
        StartupTraceEvents.append(
            this,
            "direct_debug_launch_start",
            mapOf("launchMode" to request.launchMode)
        )
        Thread({
            try {
                LegacyStsStorageMigration.migrateIfNeeded(this)
                val settingsSynced = runCatching {
                    LauncherPreferences.syncLauncherPrefsToDisk(this)
                }.getOrDefault(false)
                if (!settingsSynced) {
                    throw IllegalStateException("Launcher settings sync failed")
                }
                if (GameLaunchReturnTracker.isGameProcessRunning(this, includeCached = true)) {
                    StartupTraceEvents.append(
                        this,
                        "direct_debug_launch_cleanup_game_process",
                        mapOf("launchMode" to request.launchMode)
                    )
                    GameLaunchReturnTracker.terminateTrackedGameProcessAndWait(this)
                    GameLaunchReturnTracker.clearPendingGameLaunch(this)
                }
                MainProcessLaunchPreparationCoordinator.prepareBeforeLaunch(
                    context = applicationContext,
                    launchMode = request.launchMode,
                    progressCallback = null
                )
                val backBehavior = LauncherPreferences.readBackBehavior(this)
                val manualDismissBootOverlay =
                    LauncherPreferences.readManualDismissBootOverlay(this)
                val launchStartedAtMs = GameLaunchReturnTracker.markGameLaunchStarted(this)
                ExpectedGameExitNotice.clearExpectedGameExit(this)
                if (LauncherPreferences.isLogcatCaptureEnabled(this)) {
                    LogcatCaptureProcessClient.startCapture(this, launchStartedAtMs)
                } else {
                    LogcatCaptureProcessClient.stopAndClearCapture(this)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    markBackgroundForGameLaunch()
                    stopLauncherLogcatCaptureForGameLaunch()
                    StartupTraceEvents.append(
                        this,
                        "direct_debug_launch_game_activity",
                        mapOf("launchMode" to request.launchMode)
                    )
                    StsGameActivity.launch(
                        context = this,
                        launchMode = request.launchMode,
                        backBehavior = backBehavior,
                        manualDismissBootOverlay = manualDismissBootOverlay,
                        forceJvmCrash = request.forceJvmCrash,
                        forceRuntimeCrash = request.forceRuntimeCrash,
                        debugMode = request.debugMode,
                        autoplay = request.autoplay,
                        autoplaySaveMode = request.autoplaySaveMode,
                        autoplayMode = request.autoplayMode,
                        autoplaySingleRoomSpecPath = request.autoplaySingleRoomSpecPath,
                        autoplayChoiceDelayMs = request.autoplayChoiceDelayMs,
                        performanceDeepDiagnostics = request.performanceDeepDiagnostics,
                        cardObtainEffectOwnershipCompatEnabled =
                            request.cardObtainEffectOwnershipCompatEnabled
                    )
                }
            } catch (error: Throwable) {
                StartupTraceEvents.append(
                    this,
                    "direct_debug_launch_failed",
                    mapOf(
                        "launchMode" to request.launchMode,
                        "error" to (error.message ?: error.javaClass.simpleName)
                    )
                )
                writeDirectDebugLaunchFailureEvent(
                    "Direct debug launch preparation failed: " +
                        (error.message ?: error.javaClass.simpleName)
                )
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        clearBackgroundForGameLaunch()
                        finish()
                    }
                }
            }
        }, "STS-DirectDebugLaunch").start()
        return true
    }

    private fun buildDebugLaunchRequest(startupIntent: Intent?): DebugLaunchRequest? {
        val launchMode = startupIntent?.getStringExtra(EXTRA_DEBUG_LAUNCH_MODE) ?: return null
        if (launchMode != StsLaunchSpec.LAUNCH_MODE_VANILLA &&
            !StsLaunchSpec.isMtsLaunchMode(launchMode)
        ) {
            return null
        }
        return DebugLaunchRequest(
            launchMode = launchMode,
            debugMode = startupIntent.getBooleanExtra(EXTRA_DEBUG_MODE, false),
            forceJvmCrash = startupIntent.getBooleanExtra(EXTRA_DEBUG_FORCE_JVM_CRASH, false),
            forceRuntimeCrash = startupIntent.getBooleanExtra(EXTRA_DEBUG_FORCE_RUNTIME_CRASH, false),
            autoplay = startupIntent.getBooleanExtra(EXTRA_DEBUG_AUTOPLAY, false),
            autoplaySaveMode = AutoplaySaveMode.fromPersistedValue(
                startupIntent.getStringExtra(EXTRA_DEBUG_AUTOPLAY_SAVE_MODE)
            ),
            autoplayMode = AutoplayMode.fromPersistedValue(
                startupIntent.getStringExtra(EXTRA_DEBUG_AUTOPLAY_MODE)
            ),
            autoplaySingleRoomSpecPath =
                startupIntent.getStringExtra(EXTRA_DEBUG_AUTOPLAY_SINGLE_ROOM_SPEC).orEmpty(),
            autoplayChoiceDelayMs = startupIntent.getLongExtra(
                EXTRA_DEBUG_AUTOPLAY_CHOICE_DELAY_MS,
                0L
            ).coerceAtLeast(0L),
            performanceDeepDiagnostics = if (startupIntent.hasExtra(EXTRA_DEBUG_PERFORMANCE_DEEP_DIAGNOSTICS)) {
                startupIntent.getBooleanExtra(EXTRA_DEBUG_PERFORMANCE_DEEP_DIAGNOSTICS, false)
            } else {
                null
            },
            cardObtainEffectOwnershipCompatEnabled = !startupIntent.getBooleanExtra(
                EXTRA_DEBUG_DISABLE_CARD_OBTAIN_EFFECT_OWNERSHIP_COMPAT,
                false
            )
        )
    }

    private fun writeDirectDebugLaunchFailureEvent(message: String) {
        try {
            val eventsFile = RuntimePaths.bootBridgeEventsLog(this)
            eventsFile.parentFile?.mkdirs()
            val safeMessage = message
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
            FileOutputStream(eventsFile, true).use { output ->
                output.write("FAIL\t-1\t$safeMessage\n".toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (_: Throwable) {
        }
    }

    private fun onStartupResourcesReady(
        storageMigrationResult: LegacyStsStorageMigration.Result?,
        startupIntent: Intent?
    ) {
        if (startupAfterResourcesCompleted || isFinishing || isDestroyed) {
            return
        }
        StartupTraceEvents.append(
            this,
            "launcher_resources_ready",
            mapOf("debugLaunch" to (startupIntent?.hasExtra(EXTRA_DEBUG_LAUNCH_MODE) == true))
        )
        startupAfterResourcesCompleted = true
        handleIncomingLauncherIntent(startupIntent)
        if (storageMigrationResult != null) {
            showStorageMigrationDialog(storageMigrationResult)
        }
        maybeShowExternalStsImportNotice(startupIntent)
        maybeHandleJarIntent(startupIntent)
        WorkshopUpdateCheckCoordinator.checkOnAppStartIfDue(this)
    }

    private fun syncLauncherLogcatCapture() {
        if (LauncherPreferences.isLauncherLogcatCaptureEnabled(this)) {
            LauncherLogcatCaptureProcessClient.startCapture(this)
        } else {
            LauncherLogcatCaptureProcessClient.stopAndClearCapture(this)
        }
    }

    /**
     * The launcher capture's idle-stop guard tracks the launcher main process, which stays alive for
     * the whole game session, so the guard can never fire on its own. Left running it costs a
     * resident `logcat` child process plus a periodic `getRunningAppProcesses()` binder enumeration
     * for the entire time the player is in game. The game session starts its own capture
     * ([LogcatCaptureProcessClient]) that already covers every app process including the launcher,
     * so keeping the launcher capture alive during a session is redundant.
     *
     * Uses stop (not stop-and-clear) so launcher logs already on disk stay available for feedback
     * bundles. [syncLauncherLogcatCapture] restarts it from `onResume` when the launcher returns to
     * the foreground.
     */
    fun stopLauncherLogcatCaptureForGameLaunch() {
        LauncherLogcatCaptureProcessClient.stopCapture(this)
    }

    /**
     * Restores the launcher capture when a launch attempt aborts before the launcher is backgrounded.
     * `onResume` never fires in that case because the launcher was never paused.
     */
    fun restoreLauncherLogcatCaptureAfterFailedGameLaunch() {
        syncLauncherLogcatCapture()
    }

    private fun maybeScheduleGameReturnAnalysis() {
        if (isFinishing || isDestroyed) {
            return
        }
        if (GameLaunchReturnTracker.readPendingGameLaunchStartedAt(this) == null) {
            return
        }
        if (pendingGameReturnAnalysis != null) {
            return
        }
        val decorView = window.decorView
        val analysisRunnable = object : Runnable {
            private var remainingAttempts = GAME_RETURN_ANALYSIS_ATTEMPTS
            private var killRequested = false

            override fun run() {
                if (pendingGameReturnAnalysis !== this) {
                    return
                }
                if (isFinishing || isDestroyed) {
                    cancelPendingGameReturnAnalysis()
                    return
                }
                val currentLaunchStartedAt =
                    GameLaunchReturnTracker.readPendingGameLaunchStartedAt(this@LauncherActivity)
                        ?: run {
                            cancelPendingGameReturnAnalysis()
                            return
                        }
                if (GameLaunchReturnTracker.isGameProcessRunning(this@LauncherActivity)) {
                    if (
                        !killRequested &&
                        GameLaunchReturnTracker.isWithinFreshLaunchHandoffWindow(
                            currentLaunchStartedAt
                        )
                    ) {
                        decorView.postDelayed(this, GAME_RETURN_ANALYSIS_DELAY_MS)
                        return
                    }
                    if (!killRequested) {
                        GameLaunchReturnTracker.terminateTrackedGameProcess(this@LauncherActivity)
                        LogcatCaptureProcessClient.stopCapture(this@LauncherActivity)
                        killRequested = true
                    }
                    remainingAttempts--
                    if (remainingAttempts <= 0) {
                        GameLaunchReturnTracker.clearPendingGameLaunch(this@LauncherActivity)
                        mainViewModel.refresh(this@LauncherActivity)
                        cancelPendingGameReturnAnalysis()
                        return
                    }
                    decorView.postDelayed(this, GAME_RETURN_ANALYSIS_DELAY_MS)
                    return
                }
                if (mainViewModel.handleGameProcessExitAnalysis(
                        this@LauncherActivity,
                        intent,
                        currentLaunchStartedAt,
                        allowProcessExitCrashFallback = !killRequested
                    )
                ) {
                    GameLaunchReturnTracker.clearPendingGameLaunch(this@LauncherActivity)
                    if (!(mainViewModel.uiState.busy &&
                            mainViewModel.uiState.busyOperation == UiBusyOperation.STEAM_CLOUD_SYNC)
                    ) {
                        mainViewModel.refresh(this@LauncherActivity)
                    }
                    settingsViewModel.startGameReturnAutoUpdateCheck(this@LauncherActivity)
                    cancelPendingGameReturnAnalysis()
                    return
                }
                if (killRequested) {
                    GameLaunchReturnTracker.clearPendingGameLaunch(this@LauncherActivity)
                    mainViewModel.refresh(this@LauncherActivity)
                    settingsViewModel.startGameReturnAutoUpdateCheck(this@LauncherActivity)
                    cancelPendingGameReturnAnalysis()
                    return
                }
                remainingAttempts--
                if (remainingAttempts <= 0) {
                    GameLaunchReturnTracker.clearPendingGameLaunch(this@LauncherActivity)
                    settingsViewModel.startGameReturnAutoUpdateCheck(this@LauncherActivity)
                    cancelPendingGameReturnAnalysis()
                    return
                }
                decorView.postDelayed(this, GAME_RETURN_ANALYSIS_DELAY_MS)
            }
        }
        pendingGameReturnAnalysis = analysisRunnable
        decorView.postDelayed(analysisRunnable, if (GameLaunchReturnTracker.isGameProcessRunning(this)) GAME_RETURN_ANALYSIS_DELAY_MS else 0L)
    }

    private fun cancelPendingGameReturnAnalysis() {
        val pending = pendingGameReturnAnalysis ?: return
        window.decorView.removeCallbacks(pending)
        pendingGameReturnAnalysis = null
    }

    private fun maybeHandleJarIntent(incomingIntent: Intent?) {
        val request = consumeExternalJarIntent(incomingIntent) ?: return
        pendingModImportFlow = true
        if (pendingStorageMigrationDialog?.isShowing == true) {
            queuedExternalImportRequest = request
            return
        }
        handleExternalImportRequest(request)
    }

    private fun consumeExternalJarIntent(incomingIntent: Intent?): ExternalImportRequest? {
        if (incomingIntent == null) {
            return null
        }
        val request = when (incomingIntent.action) {
            Intent.ACTION_VIEW -> consumeViewExternalJarIntent(incomingIntent)
            Intent.ACTION_SEND -> consumeSendExternalJarIntent(incomingIntent)
            Intent.ACTION_SEND_MULTIPLE -> consumeSendMultipleExternalJarIntent(incomingIntent)
            else -> null
        } ?: return null

        clearConsumedExternalImportIntent(incomingIntent)
        return request
    }

    private fun consumeViewExternalJarIntent(incomingIntent: Intent): ExternalImportRequest? {
        val uri = incomingIntent.data ?: firstClipDataUri(incomingIntent)
        if (uri != null) {
            return if (isSupportedExternalImportUri(uri)) {
                ExternalImportRequest.ImportUri(uri)
            } else if (looksLikeJarRequest(incomingIntent, uri)) {
                ExternalImportRequest.Unsupported
            } else {
                null
            }
        }
        return if (looksLikeJarRequest(incomingIntent, null) || incomingIntent.clipData != null) {
            ExternalImportRequest.Unsupported
        } else {
            null
        }
    }

    private fun consumeSendExternalJarIntent(incomingIntent: Intent): ExternalImportRequest? {
        val uri = readSendStreamUri(incomingIntent)
            ?: firstClipDataUri(incomingIntent)
            ?: incomingIntent.data
        if (uri != null) {
            return if (isSupportedExternalImportUri(uri)) {
                ExternalImportRequest.ImportUri(uri)
            } else {
                ExternalImportRequest.Unsupported
            }
        }
        return if (
            looksLikeJarRequest(incomingIntent, null) ||
            incomingIntent.hasExtra(Intent.EXTRA_STREAM) ||
            incomingIntent.clipData != null
        ) {
            ExternalImportRequest.Unsupported
        } else {
            null
        }
    }

    private fun consumeSendMultipleExternalJarIntent(incomingIntent: Intent): ExternalImportRequest? {
        val uris = readSendStreamUris(incomingIntent)
            .ifEmpty { readAllClipDataUris(incomingIntent) }
            .ifEmpty { listOfNotNull(incomingIntent.data) }
        if (uris.size == 1) {
            val uri = uris.first()
            return if (isSupportedExternalImportUri(uri)) {
                ExternalImportRequest.ImportUri(uri)
            } else {
                ExternalImportRequest.Unsupported
            }
        }
        if (uris.isNotEmpty()) {
            return ExternalImportRequest.Unsupported
        }
        return if (
            looksLikeJarRequest(incomingIntent, null) ||
            incomingIntent.hasExtra(Intent.EXTRA_STREAM) ||
            incomingIntent.clipData != null
        ) {
            ExternalImportRequest.Unsupported
        } else {
            null
        }
    }

    private fun clearConsumedExternalImportIntent(incomingIntent: Intent) {
        incomingIntent.action = null
        incomingIntent.data = null
        incomingIntent.type = null
        incomingIntent.clipData = null
        incomingIntent.removeExtra(Intent.EXTRA_STREAM)
    }

    private fun firstClipDataUri(incomingIntent: Intent): Uri? {
        val clipData = incomingIntent.clipData ?: return null
        for (index in 0 until clipData.itemCount) {
            val uri = clipData.getItemAt(index)?.uri ?: continue
            return uri
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun readSendStreamUri(incomingIntent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            incomingIntent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            incomingIntent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    private fun readAllClipDataUris(incomingIntent: Intent): List<Uri> {
        val clipData = incomingIntent.clipData ?: return emptyList()
        val uris = ArrayList<Uri>(clipData.itemCount)
        for (index in 0 until clipData.itemCount) {
            val uri = clipData.getItemAt(index)?.uri ?: continue
            uris += uri
        }
        return uris
    }

    @Suppress("DEPRECATION")
    private fun readSendStreamUris(incomingIntent: Intent): List<Uri> {
        val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            incomingIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            incomingIntent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }
        return uris.orEmpty()
    }

    private fun isSupportedExternalImportUri(uri: Uri): Boolean {
        val normalizedScheme = uri.scheme
            ?.trim()
            ?.lowercase(Locale.ROOT)
        return normalizedScheme.isNullOrEmpty() ||
            normalizedScheme == "content" ||
            normalizedScheme == "file"
    }

    private fun looksLikeJarRequest(incomingIntent: Intent, uri: Uri?): Boolean {
        val normalizedMime = incomingIntent.type
            ?.trim()
            ?.lowercase(Locale.ROOT)
        if (!normalizedMime.isNullOrEmpty() && normalizedMime in JAR_MIME_TYPES) {
            return true
        }
        val lowerPath = (uri?.lastPathSegment ?: uri?.path)
            .orEmpty()
            .lowercase(Locale.ROOT)
        return lowerPath.endsWith(".jar")
    }

    private fun handleExternalImportRequest(request: ExternalImportRequest) {
        when (request) {
            is ExternalImportRequest.ImportUri -> loadIncomingJarIntentTarget(request.uri)
            ExternalImportRequest.Unsupported -> showUnsupportedExternalImportDialog()
        }
    }

    private fun loadIncomingJarIntentTarget(uri: Uri) {
        showImportLoadingDialog()
        Thread {
            val displayName = SettingsFileService.resolveDisplayName(this, uri)
            val target = buildIncomingJarIntentTarget(uri, displayName)
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    dismissImportLoadingDialog()
                    finishPendingJarIntentFlow()
                    return@runOnUiThread
                }
                when (target) {
                    is IncomingJarIntentTarget.StsJar -> importExternalStsJar(target)
                    is IncomingJarIntentTarget.ModJar -> startExternalModImport(target.uri)
                }
            }
        }.start()
    }

    private fun buildIncomingJarIntentTarget(
        uri: Uri,
        displayName: String
    ): IncomingJarIntentTarget {
        if (displayName.trim().equals(STS_JAR_FILE_NAME, ignoreCase = true) || isExternalStsJar(uri)) {
            return IncomingJarIntentTarget.StsJar(uri = uri, displayName = displayName)
        }
        return IncomingJarIntentTarget.ModJar(uri = uri)
    }

    private fun isExternalStsJar(uri: Uri): Boolean {
        val tempJar = File(cacheDir, "external-sts-check-${System.nanoTime()}.jar")
        return try {
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) {
                    return false
                }
                tempJar.outputStream().use { output -> input.copyTo(output) }
            }
            StsJarValidator.isValidStreaming(tempJar)
        } catch (_: Throwable) {
            false
        } finally {
            if (tempJar.exists()) {
                tempJar.delete()
            }
        }
    }

    private fun startExternalModImport(uri: Uri) {
        dismissImportLoadingDialog()
        ModImportRequestBus.requestImport(listOf(uri))
        finishPendingJarIntentFlow()
    }

    private fun importExternalStsJar(target: IncomingJarIntentTarget.StsJar) {
        // Close the native loading layer first so Compose integrity dialogs stay visible.
        dismissImportLoadingDialog()
        settingsViewModel.onJarPicked(this, target.uri, showSuccessToast = false) { success ->
            if (!success) {
                finishPendingJarIntentFlow()
                return@onJarPicked
            }
            if (launchedWithoutImportedStsJar) {
                intent.putExtra(EXTRA_EXTERNAL_STS_IMPORT_NOTICE, true)
                intent.putExtra(EXTRA_EXTERNAL_STS_IMPORT_FILE_NAME, target.displayName)
                recreate()
                return@onJarPicked
            }
            mainViewModel.refresh(this)
            showExternalStsImportNoticeDialog(target.displayName)
        }
    }

    private fun showUnsupportedExternalImportDialog() {
        dismissImportLoadingDialog()
        val previousDialog = pendingImportDialog
        pendingImportDialog = null
        previousDialog?.dismiss()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.external_import_unsupported_title)
            .setMessage(R.string.external_import_unsupported_message)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnDismissListener {
            if (pendingImportDialog === dialog) {
                pendingImportDialog = null
                finishPendingJarIntentFlow()
            }
        }
        pendingImportDialog = dialog
        dialog.show()
    }

    private fun showImportLoadingDialog() {
        val previousDialog = pendingImportLoadingDialog
        pendingImportLoadingDialog = null
        previousDialog?.dismiss()

        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val spacing = (16 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val progressBar = ProgressBar(this).apply {
            isIndeterminate = true
        }
        val messageView = TextView(this).apply {
            text = getString(R.string.external_import_loading_message_generic)
            setPadding(0, spacing, 0, 0)
        }
        container.addView(progressBar)
        container.addView(messageView)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.external_import_loading_title)
            .setView(container)
            .setCancelable(false)
            .create()
        pendingImportLoadingDialog = dialog
        dialog.show()
    }

    private fun dismissImportLoadingDialog() {
        val dialog = pendingImportLoadingDialog ?: return
        pendingImportLoadingDialog = null
        dialog.dismiss()
    }

    private fun maybeShowExternalStsImportNotice(incomingIntent: Intent?) {
        val displayName = consumeExternalStsImportNoticeDisplayName(incomingIntent) ?: return
        showExternalStsImportNoticeDialog(displayName)
    }

    private fun consumeExternalStsImportNoticeDisplayName(incomingIntent: Intent?): String? {
        if (incomingIntent == null ||
            !incomingIntent.getBooleanExtra(EXTRA_EXTERNAL_STS_IMPORT_NOTICE, false)
        ) {
            return null
        }
        val displayName = incomingIntent.getStringExtra(EXTRA_EXTERNAL_STS_IMPORT_FILE_NAME)
            .orEmpty()
            .trim()
            .ifBlank { STS_JAR_FILE_NAME }
        incomingIntent.removeExtra(EXTRA_EXTERNAL_STS_IMPORT_NOTICE)
        incomingIntent.removeExtra(EXTRA_EXTERNAL_STS_IMPORT_FILE_NAME)
        return displayName
    }

    private fun showExternalStsImportNoticeDialog(displayName: String) {
        val previousDialog = pendingImportDialog
        pendingImportDialog = null
        previousDialog?.dismiss()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.sts_jar_external_import_notice_title)
            .setMessage(getString(R.string.sts_jar_external_import_notice_message, displayName))
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnDismissListener {
            if (pendingImportDialog === dialog) {
                pendingImportDialog = null
                finishPendingJarIntentFlow()
            }
        }
        pendingImportDialog = dialog
        dialog.show()
    }

    private fun showStorageMigrationDialog(result: LegacyStsStorageMigration.Result) {
        pendingStorageMigrationDialog?.dismiss()
        val dialog = AlertDialog.Builder(this)
            .setTitle("存储位置已迁移")
            .setMessage(buildStorageMigrationDialogMessage(result))
            .setPositiveButton("知道了", null)
            .create()
        dialog.setOnDismissListener {
            if (pendingStorageMigrationDialog === dialog) {
                pendingStorageMigrationDialog = null
            }
            drainQueuedExternalImportRequest()
        }
        pendingStorageMigrationDialog = dialog
        dialog.show()
    }

    private fun buildStorageMigrationDialogMessage(result: LegacyStsStorageMigration.Result): String {
        return buildString {
            append("检测到旧版数据保存在应用私有存储，现已自动迁移到：\n")
            append(result.targetRootPath)
            append("\n\n")
            append("旧目录共扫描到 ")
            append(result.scannedFileCount)
            append(" 个文件")
            if (result.copiedFileCount > 0) {
                append("，本次同步了 ")
                append(result.copiedFileCount)
                append(" 个文件（约 ")
                append(formatByteCount(result.copiedByteCount))
                append("）")
            }
            append("。\n后续存档、导入模组和相关配置都会继续写入这个新目录。")
            append("\n\n旧目录：\n")
            append(result.sourceRootPath)
        }
    }

    private fun drainQueuedExternalImportRequest() {
        if (isFinishing || isDestroyed) {
            queuedExternalImportRequest = null
            finishPendingJarIntentFlow()
            return
        }
        if (pendingStorageMigrationDialog?.isShowing == true) {
            return
        }
        val request = queuedExternalImportRequest ?: return
        queuedExternalImportRequest = null
        handleExternalImportRequest(request)
    }

    private fun finishPendingJarIntentFlow() {
        pendingModImportFlow = false
    }

    private fun onMainScreenOpened() {
        settingsViewModel.startMainScreenAutoUpdateCheck(this)
    }

    private fun formatByteCount(bytes: Long): String {
        if (bytes <= 0L) {
            return "0 B"
        }
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${value.toLong()} ${units[unitIndex]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

}

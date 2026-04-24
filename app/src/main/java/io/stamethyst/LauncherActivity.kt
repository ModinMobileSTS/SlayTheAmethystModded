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
import io.stamethyst.backend.launch.GameLaunchReturnTracker
import io.stamethyst.config.LegacyStsStorageMigration
import io.stamethyst.config.RuntimePaths
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.mods.StsJarValidator
import io.stamethyst.navigation.Route
import io.stamethyst.ui.LauncherContent
import io.stamethyst.ui.UiBusyOperation
import io.stamethyst.ui.main.MainScreenViewModel
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.settings.InvalidModImportFailure
import io.stamethyst.ui.settings.JarImportInspectionService
import io.stamethyst.ui.settings.SettingsFileService
import io.stamethyst.ui.settings.SettingsScreenViewModel
import io.stamethyst.ui.theme.LauncherTheme
import java.io.File
import java.util.Locale

class LauncherActivity : AppCompatActivity() {
    companion object {
        private const val GAME_RETURN_ANALYSIS_DELAY_MS = 250L
        private const val GAME_RETURN_ANALYSIS_ATTEMPTS = 12
        const val EXTRA_DEBUG_LAUNCH_MODE = "io.stamethyst.debug_launch_mode"
        const val EXTRA_DEBUG_FORCE_JVM_CRASH = "io.stamethyst.debug_force_jvm_crash"
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

    private data class ModImportPreview(
        val uri: Uri,
        val displayName: String,
        val manifest: ModJarSupport.ModManifestInfo?,
        val parseError: String?
    )

    private sealed interface IncomingJarIntentTarget {
        data class StsJar(
            val uri: Uri,
            val displayName: String
        ) : IncomingJarIntentTarget

        data class ModJar(
            val preview: ModImportPreview
        ) : IncomingJarIntentTarget
    }

    private sealed interface ExternalImportRequest {
        data class ImportUri(
            val uri: Uri
        ) : ExternalImportRequest

        object Unsupported : ExternalImportRequest
    }

    private val mainViewModel: MainScreenViewModel by viewModels()
    private val settingsViewModel: SettingsScreenViewModel by viewModels()
    private var pendingImportDialog: AlertDialog? = null
    private var pendingImportLoadingDialog: AlertDialog? = null
    private var pendingStorageMigrationDialog: AlertDialog? = null
    private var queuedExternalImportRequest: ExternalImportRequest? = null
    private var pendingModImportFlow = false
    private var pendingGameReturnAnalysis: Runnable? = null
    private var launchedWithoutImportedStsJar = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                LauncherContent(
                    initialRoute = initialRoute,
                    mainViewModel = mainViewModel,
                    settingsViewModel = settingsViewModel,
                    onMainScreenOpened = ::onMainScreenOpened,
                )
            }
        }

        handleIncomingLauncherIntent(intent)
        if (storageMigrationResult != null) {
            showStorageMigrationDialog(storageMigrationResult)
        }
        maybeShowExternalStsImportNotice(intent)
        maybeHandleJarIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingLauncherIntent(intent)
        maybeShowExternalStsImportNotice(intent)
        maybeHandleJarIntent(intent)
        maybeScheduleGameReturnAnalysis()
    }

    override fun onResume() {
        super.onResume()
        syncLauncherLogcatCapture()
        maybeScheduleGameReturnAnalysis()
    }

    override fun onPause() {
        cancelPendingGameReturnAnalysis()
        super.onPause()
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

    private fun syncLauncherLogcatCapture() {
        if (LauncherPreferences.isLauncherLogcatCaptureEnabled(this)) {
            LauncherLogcatCaptureProcessClient.startCapture(this)
        } else {
            LauncherLogcatCaptureProcessClient.stopAndClearCapture(this)
        }
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
                    is IncomingJarIntentTarget.ModJar -> showModImportDialog(target.preview)
                }
            }
        }.start()
    }

    private fun buildIncomingJarIntentTarget(
        uri: Uri,
        displayName: String
    ): IncomingJarIntentTarget {
        if (displayName.trim().equals(STS_JAR_FILE_NAME, ignoreCase = true)) {
            return IncomingJarIntentTarget.StsJar(uri = uri, displayName = displayName)
        }
        val tempJar = File(cacheDir, "import-preview-${System.nanoTime()}.jar")
        var previewDisplayName = displayName
        var manifest: ModJarSupport.ModManifestInfo? = null
        var parseError: String? = null
        try {
            val preparedDisplayName = JarImportInspectionService.prepareImportedJar(this, uri, tempJar)
            previewDisplayName = preparedDisplayName
            if (StsJarValidator.isValid(tempJar)) {
                return IncomingJarIntentTarget.StsJar(uri = uri, displayName = preparedDisplayName)
            }
            val inspection = JarImportInspectionService.inspectPreparedModJar(this, tempJar, preparedDisplayName)
            manifest = inspection.manifest
            parseError = inspection.parseError
        } catch (error: Throwable) {
            parseError = error.message ?: error.javaClass.simpleName
        } finally {
            if (tempJar.exists()) {
                tempJar.delete()
            }
        }
        return IncomingJarIntentTarget.ModJar(
            ModImportPreview(
                uri = uri,
                displayName = previewDisplayName,
                manifest = manifest,
                parseError = parseError
            )
        )
    }

    private fun importExternalStsJar(target: IncomingJarIntentTarget.StsJar) {
        settingsViewModel.onJarPicked(this, target.uri, showSuccessToast = false) { success ->
            if (!success) {
                dismissImportLoadingDialog()
                finishPendingJarIntentFlow()
                return@onJarPicked
            }
            if (launchedWithoutImportedStsJar) {
                dismissImportLoadingDialog()
                intent.putExtra(EXTRA_EXTERNAL_STS_IMPORT_NOTICE, true)
                intent.putExtra(EXTRA_EXTERNAL_STS_IMPORT_FILE_NAME, target.displayName)
                recreate()
                return@onJarPicked
            }
            dismissImportLoadingDialog()
            mainViewModel.refresh(this)
            showExternalStsImportNoticeDialog(target.displayName)
        }
    }

    private fun showModImportDialog(preview: ModImportPreview) {
        dismissImportLoadingDialog()
        val previousDialog = pendingImportDialog
        pendingImportDialog = null
        previousDialog?.dismiss()
        val dialog = if (preview.manifest != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.main_import_mods)
                .setMessage(buildModImportDialogMessage(preview))
                .setNegativeButton(R.string.main_folder_dialog_cancel, null)
                .setPositiveButton(R.string.mod_import_confirm_dialog_action_import) { _, _ ->
                    settingsViewModel.onModJarsPicked(this, listOf(preview.uri)) {
                        mainViewModel.refresh(this)
                    }
                }
                .create()
        } else {
            val failure = InvalidModImportFailure(
                displayName = preview.displayName,
                reason = preview.parseError.orEmpty()
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.mod_import_dialog_invalid_title)
                .setMessage(SettingsFileService.buildInvalidModImportMessage(this, listOf(failure)))
                .setPositiveButton(android.R.string.ok, null)
                .create()
        }
        dialog.setOnDismissListener {
            if (pendingImportDialog === dialog) {
                pendingImportDialog = null
                finishPendingJarIntentFlow()
            }
        }
        pendingImportDialog = dialog
        dialog.show()
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

    private fun buildModImportDialogMessage(preview: ModImportPreview): String {
        val manifest = preview.manifest
        val builder = StringBuilder()
        builder.append(getString(R.string.mod_import_preview_file_label, preview.displayName))
        if (manifest != null) {
            val name = manifest.name.ifBlank { manifest.modId.ifBlank { preview.displayName } }
            val modId = manifest.modId.ifBlank { getString(R.string.main_mod_unknown_version) }
            val version = manifest.version.ifBlank { getString(R.string.main_mod_unknown_version) }
            val description = manifest.description.ifBlank { getString(R.string.main_mod_no_description) }
            val dependencies = manifest.dependencies
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()

            builder.append("\n\n").append(getString(R.string.mod_import_preview_name_label, name))
            builder.append("\n").append(getString(R.string.main_mod_modid_format, modId))
            builder.append("\n").append(getString(R.string.main_mod_version_format, version))
            builder.append("\n").append(getString(R.string.mod_import_preview_description_label, description))
            if (dependencies.isNotEmpty()) {
                builder.append(
                    "\n" + getString(
                        R.string.main_mod_dependencies_format,
                        dependencies.joinToString(", ")
                    )
                )
            }
        } else {
            val error = preview.parseError
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: getString(R.string.mod_import_error_unknown)
            builder.append("\n\n").append(getString(R.string.mod_import_preview_manifest_read_failed, error))
        }
        return builder.toString()
    }
}

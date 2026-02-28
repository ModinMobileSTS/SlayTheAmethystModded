package io.stamethyst.ui.settings

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.LauncherIcon
import io.stamethyst.LauncherIconManager
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.R
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.backend.render.RendererConfig
import io.stamethyst.backend.core.RuntimePaths
import io.stamethyst.backend.mods.StsJarValidator
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Arrays
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import org.json.JSONTokener

@Stable
class SettingsScreenViewModel : ViewModel() {
    companion object {
        private const val PREF_NAME_LAUNCHER = "sts_launcher_prefs"
        private const val PREF_KEY_BACK_IMMEDIATE_EXIT = "back_immediate_exit"
        private const val PREF_KEY_TARGET_FPS = "target_fps"
        private const val PREF_KEY_MANUAL_DISMISS_BOOT_OVERLAY = "manual_dismiss_boot_overlay"

        private const val DEFAULT_RENDER_SCALE = 1.0f
        private const val MIN_RENDER_SCALE = 0.50f
        private const val MAX_RENDER_SCALE = 1.00f
        private const val DEFAULT_TARGET_FPS = 120
        private const val DEFAULT_TOUCHSCREEN_ENABLED = true

        private const val GAMEPLAY_SETTINGS_FILE_NAME = "STSGameplaySettings"
        private const val GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN = "Touchscreen Enabled"
        private const val GAMEPLAY_SETTINGS_DEFAULT_ASSET_PATH =
            "components/default_saves/preferences/STSGameplaySettings"

        val TARGET_FPS_OPTIONS = intArrayOf(60, 90, 120, 240)

        private val SAVE_IMPORT_TOP_LEVEL_DIRS = arrayOf(
            "betaPreferences",
            "betapreferences",
            "betaPerferences",
            "betaperferences",
            "preferences",
            "perferences",
            "saves",
            "runs",
            "metrics",
            "home",
            "sendToDevs",
            "sendtodevs",
            "multiplayer",
            "multiple"
        )

        private val SAVE_EXPORT_FOLDER_MAPPINGS = arrayOf(
            "multiplayer" to "multiple",
            "saves" to "saves",
            "betaPreferences" to "preferences",
            "runs" to "runs"
        )
    }

    sealed interface Effect {
        data object OpenImportJarPicker : Effect
        data object OpenImportModsPicker : Effect
        data object OpenImportSavesPicker : Effect
        data class OpenExportSavesPicker(val fileName: String) : Effect
        data object OpenCompatibility : Effect
    }

    data class UiState(
        val busy: Boolean = false,
        val busyMessage: String? = null,
        val renderScaleInput: String = "",
        val selectedRenderer: RendererBackend = RendererBackend.OPENGL_ES2,
        val selectedTargetFps: Int = DEFAULT_TARGET_FPS,
        val selectedLauncherIcon: LauncherIcon = LauncherIcon.AMBER,
        val backImmediateExit: Boolean = true,
        val manualDismissBootOverlay: Boolean = false,
        val touchscreenEnabled: Boolean = true,
        val statusText: String = "",
        val logPathText: String = "",
        val targetFpsOptions: List<Int> = TARGET_FPS_OPTIONS.toList()
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 16)

    var uiState by mutableStateOf(UiState())
        private set

    val effects = _effects.asSharedFlow()

    fun bind(activity: Activity, targetFpsOptions: IntArray = TARGET_FPS_OPTIONS) {
        val options = targetFpsOptions.toList()
        if (uiState.targetFpsOptions != options) {
            uiState = uiState.copy(targetFpsOptions = options)
        }

        if (uiState.statusText.isBlank()) {
            uiState = uiState.copy(renderScaleInput = formatRenderScale(readRenderScaleValue(activity)))
            refreshStatus(activity)
        }
    }

    fun refreshStatus(host: Activity) {
        val hasJar = RuntimePaths.importedStsJar(host).exists()
        val hasMts = RuntimePaths.importedMtsJar(host).exists() || hasBundledAsset(host, "components/mods/ModTheSpire.jar")
        val hasBaseMod = RuntimePaths.importedBaseModJar(host).exists() || hasBundledAsset(host, "components/mods/BaseMod.jar")
        val hasStsLib = RuntimePaths.importedStsLibJar(host).exists() || hasBundledAsset(host, "components/mods/StSLib.jar")

        val renderScale = readRenderScaleValue(host)
        val selectedRenderer = RendererConfig.readPreferredBackend(host)
        val targetFps = readTargetFpsSelection(host)
        val backImmediateExit = readBackBehaviorSelection(host)
        val manualDismissBootOverlay = readManualDismissBootOverlaySelection(host)
        val touchscreenEnabled = readTouchscreenEnabledSelection(host)
        val selectedLauncherIcon = LauncherIconManager.readEffectiveSelection(host)
        val originalFboPatchEnabled = CompatibilitySettings.isOriginalFboPatchEnabled(host)
        val downfallFboPatchEnabled = CompatibilitySettings.isDownfallFboPatchEnabled(host)
        val virtualFboPocEnabled = CompatibilitySettings.isVirtualFboPocEnabled(host)
        val globalAtlasFilterCompatEnabled = CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(host)

        val rendererDecision = RendererConfig.resolveEffectiveBackend(host, selectedRenderer)
        val rendererSelectedLine = host.getString(R.string.renderer_selected_format, selectedRenderer.statusLabel())
        val rendererEffectiveLine = if (rendererDecision.isFallback) {
            host.getString(
                R.string.renderer_effective_reason_format,
                rendererDecision.effective.statusLabel(),
                rendererDecision.reason
            )
        } else {
            host.getString(
                R.string.renderer_effective_format,
                rendererDecision.effective.statusLabel()
            )
        }

        val mods = ModManager.listInstalledMods(host)
        var optionalTotal = 0
        var optionalEnabled = 0
        mods.forEach { mod ->
            if (!mod.required) {
                optionalTotal++
                if (mod.enabled) {
                    optionalEnabled++
                }
            }
        }

        val status = (if (hasJar) "desktop-1.0.jar: OK" else "desktop-1.0.jar: missing") +
            "\nModTheSpire.jar: " + if (hasMts) "OK (bundled)" else "missing" +
            "\nBaseMod.jar: " + if (hasBaseMod) "OK (required)" else "missing (required)" +
            "\nStSLib.jar: " + if (hasStsLib) "OK (required, bundled)" else "missing (required)" +
            "\nOptional mods enabled: $optionalEnabled/$optionalTotal" +
            "\nRender scale: ${formatRenderScale(renderScale)} (0.50-1.00)" +
            "\nTarget FPS: $targetFps" +
            "\nTouchscreen Enabled: " + if (touchscreenEnabled) "ON" else "OFF" +
            "\nManual dismiss boot overlay: " + if (manualDismissBootOverlay) "ON" else "OFF" +
            "\nLauncher icon: ${selectedLauncherIcon.title}" +
            "\nOriginal FBO patch: " + if (originalFboPatchEnabled) "ON" else "OFF" +
            "\nDownfall FBO patch: " + if (downfallFboPatchEnabled) "ON" else "OFF" +
            "\nVirtual FBO PoC: " + if (virtualFboPocEnabled) "ON" else "OFF" +
            "\nGlobal atlas filter compat: " + if (globalAtlasFilterCompatEnabled) "ON" else "OFF" +
            "\n$rendererSelectedLine" +
            "\n$rendererEffectiveLine" +
            "\nBundled JRE path: app/src/main/assets/components/jre"

        uiState = uiState.copy(
            busy = false,
            busyMessage = null,
            renderScaleInput = uiState.renderScaleInput.ifBlank { formatRenderScale(renderScale) },
            selectedRenderer = selectedRenderer,
            selectedTargetFps = targetFps,
            selectedLauncherIcon = selectedLauncherIcon,
            backImmediateExit = backImmediateExit,
            manualDismissBootOverlay = manualDismissBootOverlay,
            touchscreenEnabled = touchscreenEnabled,
            statusText = status,
            logPathText = buildLogPathText(host)
        )
    }

    fun onRenderScaleInputChange(value: String) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(renderScaleInput = value)
    }

    fun onImportJar() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(Effect.OpenImportJarPicker)
    }

    fun onImportMods() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(Effect.OpenImportModsPicker)
    }

    fun onImportSaves() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(Effect.OpenImportSavesPicker)
    }

    fun onExportSaves() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(Effect.OpenExportSavesPicker(buildSaveExportFileName()))
    }

    fun onShareCrashReport(host: Activity) {
        if (uiState.busy) {
            return
        }
        setBusy(true, "Preparing error report for sharing...")
        executor.execute {
            try {
                val shareFile = createShareDebugBundleFile(host)
                val uri = FileProvider.getUriForFile(
                    host,
                    "${host.packageName}.fileprovider",
                    shareFile
                )
                host.runOnUiThread {
                    refreshStatus(host)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "*/*"
                        putExtra(Intent.EXTRA_SUBJECT, host.getString(R.string.sts_crash_dialog_title))
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = android.content.ClipData.newUri(host.contentResolver, shareFile.name, uri)
                    }
                    val chooser = Intent.createChooser(shareIntent, host.getString(R.string.sts_share_crash_chooser_title))
                    chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        host.startActivity(chooser)
                    } catch (_: Throwable) {
                        Toast.makeText(host, R.string.sts_share_crash_report_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    Toast.makeText(host, "Debug share failed: ${error.message}", Toast.LENGTH_LONG).show()
                    refreshStatus(host)
                }
            }
        }
    }

    fun onSaveRenderScale(host: Activity) {
        if (uiState.busy) {
            return
        }
        saveRenderScaleFromInput(host, showToast = true)
    }

    fun onTargetFpsSelected(host: Activity, targetFps: Int) {
        if (uiState.busy) {
            return
        }
        val normalizedTargetFps = normalizeTargetFps(targetFps)
        uiState = uiState.copy(selectedTargetFps = normalizedTargetFps)
        saveTargetFpsSelection(host, normalizedTargetFps)
        refreshStatus(host)
    }

    fun onRendererSelected(host: Activity, backend: RendererBackend) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(selectedRenderer = backend)
        if (!saveRendererSelection(host, backend)) {
            return
        }
        refreshStatus(host)
    }

    fun onBackBehaviorChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(backImmediateExit = enabled)
        saveBackBehaviorSelection(host, enabled)
        refreshStatus(host)
    }

    fun onManualDismissBootOverlayChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(manualDismissBootOverlay = enabled)
        saveManualDismissBootOverlaySelection(host, enabled)
        refreshStatus(host)
    }

    fun onTouchscreenEnabledChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        if (!saveTouchscreenEnabledSelection(host, enabled)) {
            return
        }
        uiState = uiState.copy(touchscreenEnabled = enabled)
        refreshStatus(host)
    }

    fun onOpenCompatibility() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(Effect.OpenCompatibility)
    }

    fun onLauncherIconSelected(host: Activity, icon: LauncherIcon) {
        if (uiState.busy || uiState.selectedLauncherIcon == icon) {
            return
        }
        val effectiveIcon = LauncherIconManager.applySelection(host, icon)
        uiState = uiState.copy(selectedLauncherIcon = effectiveIcon)
        if (effectiveIcon == icon) {
            Toast.makeText(host, "已切换启动器图标为：${icon.title}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                host,
                "Debug 构建固定使用观者图标，已保存你的选择",
                Toast.LENGTH_SHORT
            ).show()
        }
        refreshStatus(host)
    }

    fun onJarPicked(host: Activity, uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(true, "Importing desktop-1.0.jar...")
        executor.execute {
            try {
                copyUriToFile(host, uri, RuntimePaths.importedStsJar(host))
                StsJarValidator.validate(RuntimePaths.importedStsJar(host))
                host.runOnUiThread {
                    Toast.makeText(host, "Imported desktop-1.0.jar", Toast.LENGTH_SHORT).show()
                    refreshStatus(host)
//                    todo: host.notifyMainDataChanged()
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    Toast.makeText(host, "Import failed: ${error.message}", Toast.LENGTH_LONG).show()
                    refreshStatus(host)
                }
            }
        }
    }

    fun onModJarsPicked(host: Activity, uris: List<Uri>?) {
        if (uris.isNullOrEmpty()) {
            return
        }
        setBusy(true, "Importing selected mod jars...")
        executor.execute {
            var imported = 0
            val errors = ArrayList<String>()
            for (uri in uris) {
                try {
                    importModJar(host, uri)
                    imported++
                } catch (error: Throwable) {
                    val name = resolveDisplayName(host, uri)
                    errors.add("$name: ${error.message}")
                }
            }

            val importedCount = imported
            val failedCount = errors.size
            val firstError = if (failedCount > 0) errors[0] else null
            host.runOnUiThread {
                when {
                    importedCount > 0 && failedCount == 0 -> {
                        Toast.makeText(host, "Imported $importedCount mod jar(s)", Toast.LENGTH_SHORT).show()
                    }

                    importedCount > 0 -> {
                        Toast.makeText(
                            host,
                            "Imported $importedCount, failed $failedCount ($firstError)",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    else -> {
                        Toast.makeText(host, "Mod import failed: $firstError", Toast.LENGTH_LONG).show()
                    }
                }
                refreshStatus(host)
//                todo host.notifyMainDataChanged()
            }
        }
    }

    fun onSavesArchivePicked(host: Activity, uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(true, "Importing save archive...")
        executor.execute {
            try {
                val result = importSaveArchive(host, uri)
                host.runOnUiThread {
                    val message = if (result.backupLabel.isNullOrEmpty()) {
                        "Imported ${result.importedFiles} save files"
                    } else {
                        "Imported ${result.importedFiles} save files (backup: ${result.backupLabel})"
                    }
                    Toast.makeText(host, message, Toast.LENGTH_LONG).show()
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    Toast.makeText(host, "Save import failed: ${error.message}", Toast.LENGTH_LONG).show()
                    refreshStatus(host)
                }
            }
        }
    }

    fun onSavesExportPicked(host: Activity, uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(true, "Exporting save archive...")
        executor.execute {
            try {
                val exportedCount = exportSaveBundle(host, uri)
                host.runOnUiThread {
                    Toast.makeText(host, "Save archive exported ($exportedCount files)", Toast.LENGTH_LONG).show()
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    Toast.makeText(host, "Save export failed: ${error.message}", Toast.LENGTH_LONG).show()
                    refreshStatus(host)
                }
            }
        }
    }

    private fun setBusy(busy: Boolean, message: String?) {
        uiState = if (busy) {
            uiState.copy(
                busy = true,
                busyMessage = message
            )
        } else {
            uiState.copy(
                busy = false,
                busyMessage = null
            )
        }
    }

    private fun hasBundledAsset(host: Activity, assetPath: String): Boolean {
        return try {
            host.assets.open(assetPath).use {
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun saveRendererSelection(host: Activity, backend: RendererBackend): Boolean {
        return try {
            RendererConfig.writePreferredBackend(host, backend)
            true
        } catch (error: IOException) {
            Toast.makeText(
                host,
                host.getString(R.string.renderer_save_failed, error.message ?: "unknown"),
                Toast.LENGTH_SHORT
            ).show()
            false
        }
    }

    private fun readRenderScaleValue(host: Activity): Float {
        val config = renderScaleConfigFile(host)
        if (!config.exists()) {
            return DEFAULT_RENDER_SCALE
        }
        return try {
            FileInputStream(config).use { input ->
                val bytes = ByteArray(minOf(config.length().toInt(), 64))
                val read = input.read(bytes)
                if (read <= 0) {
                    return DEFAULT_RENDER_SCALE
                }
                val value = String(bytes, 0, read, StandardCharsets.UTF_8)
                    .trim()
                    .replace(',', '.')
                if (value.isEmpty()) {
                    return DEFAULT_RENDER_SCALE
                }
                val parsed = value.toFloat()
                when {
                    parsed < MIN_RENDER_SCALE -> MIN_RENDER_SCALE
                    parsed > MAX_RENDER_SCALE -> MAX_RENDER_SCALE
                    else -> parsed
                }
            }
        } catch (_: Throwable) {
            DEFAULT_RENDER_SCALE
        }
    }

    private fun saveRenderScaleFromInput(host: Activity, showToast: Boolean): Boolean {
        val input = uiState.renderScaleInput.trim().replace(',', '.')
        if (input.isEmpty()) {
            val config = renderScaleConfigFile(host)
            if (config.exists() && !config.delete()) {
                Toast.makeText(host, "Failed to reset render scale", Toast.LENGTH_SHORT).show()
                return false
            }
            uiState = uiState.copy(renderScaleInput = formatRenderScale(DEFAULT_RENDER_SCALE))
            if (showToast) {
                Toast.makeText(host, "Render scale reset to default 1.00", Toast.LENGTH_SHORT).show()
            }
            refreshStatus(host)
            return true
        }

        val parsed = try {
            input.toFloat()
        } catch (_: NumberFormatException) {
            Toast.makeText(host, "Invalid render scale, use 0.50 to 1.00", Toast.LENGTH_SHORT).show()
            return false
        }

        if (parsed !in MIN_RENDER_SCALE..MAX_RENDER_SCALE) {
            Toast.makeText(host, "Render scale must be between 0.50 and 1.00", Toast.LENGTH_SHORT).show()
            return false
        }

        val config = renderScaleConfigFile(host)
        val parent = config.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Toast.makeText(host, "Failed to create config directory", Toast.LENGTH_SHORT).show()
            return false
        }

        val normalized = formatRenderScale(parsed)
        try {
            FileOutputStream(config, false).use { out ->
                out.write(normalized.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (error: IOException) {
            Toast.makeText(host, "Failed to save render scale: ${error.message}", Toast.LENGTH_SHORT).show()
            return false
        }

        uiState = uiState.copy(renderScaleInput = normalized)
        if (showToast) {
            Toast.makeText(host, "Render scale saved: $normalized", Toast.LENGTH_SHORT).show()
        }
        refreshStatus(host)
        return true
    }

    private fun renderScaleConfigFile(host: Activity): File {
        return File(RuntimePaths.stsRoot(host), "render_scale.txt")
    }

    private fun formatRenderScale(value: Float): String {
        return String.format(Locale.US, "%.2f", value)
    }

    private fun copyUriToFile(host: Activity, uri: Uri, targetFile: File) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: $parent")
        }
        host.contentResolver.openInputStream(uri).use { input ->
            if (input == null) {
                throw IOException("Unable to open file from picker")
            }
            FileOutputStream(targetFile, false).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun importModJar(host: Activity, uri: Uri): String {
        val modsDir = RuntimePaths.modsDir(host)
        if (!modsDir.exists() && !modsDir.mkdirs()) {
            throw IOException("Failed to create mods directory")
        }

        val tempFile = File(modsDir, ".import-${System.nanoTime()}.tmp.jar")
        copyUriToFile(host, uri, tempFile)
        try {
            val modId = ModManager.normalizeModId(ModJarSupport.resolveModId(tempFile))
            if (modId.isBlank()) {
                throw IOException("modid is empty")
            }
            if (ModManager.MOD_ID_BASEMOD == modId) {
                ModJarSupport.validateBaseModJar(tempFile)
            } else if (ModManager.MOD_ID_STSLIB == modId) {
                ModJarSupport.validateStsLibJar(tempFile)
            }
            val targetFile = ModManager.resolveStorageFileForModId(host, modId)
            moveFileReplacing(tempFile, targetFile)
            return modId
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    @Throws(IOException::class)
    private fun moveFileReplacing(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        if (target.exists() && !target.delete()) {
            throw IOException("Failed to replace existing file: ${target.absolutePath}")
        }
        if (source.renameTo(target)) {
            return
        }

        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        if (!source.delete()) {
            throw IOException("Failed to clean temp file: ${source.absolutePath}")
        }
    }

    private fun resolveDisplayName(host: Activity, uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = host.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    val value = cursor.getString(index)
                    if (!value.isNullOrBlank()) {
                        return value
                    }
                }
            }
            "unknown.jar"
        } catch (_: Throwable) {
            "unknown.jar"
        } finally {
            cursor?.close()
        }
    }

    private data class SaveImportResult(
        val importedFiles: Int,
        val backupLabel: String?
    )

    @Throws(IOException::class)
    private fun importSaveArchive(host: Activity, uri: Uri): SaveImportResult {
        val stsRoot = RuntimePaths.stsRoot(host)
        if (!stsRoot.exists() && !stsRoot.mkdirs()) {
            throw IOException("Failed to create save root: ${stsRoot.absolutePath}")
        }

        val importableCount = countImportableSaveEntries(host, uri)
        if (importableCount <= 0) {
            throw IOException("Archive did not contain importable save files")
        }

        val backupLabel = backupExistingSavesToDownloads(host, stsRoot)
        clearExistingSaveTargets(stsRoot)
        val importedFiles = extractSaveArchive(host, uri, stsRoot)
        if (importedFiles <= 0) {
            throw IOException("Archive did not contain importable save files")
        }
        return SaveImportResult(
            importedFiles = importedFiles,
            backupLabel = backupLabel
        )
    }

    @Throws(IOException::class)
    private fun countImportableSaveEntries(host: Activity, uri: Uri): Int {
        var importableFiles = 0
        host.contentResolver.openInputStream(uri).use { rawInput ->
            if (rawInput == null) {
                throw IOException("Unable to open selected archive")
            }
            java.util.zip.ZipInputStream(rawInput).use { zipInput ->
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    val mappedPath = resolveImportableArchivePath(entry.name)
                    if (mappedPath.isNullOrEmpty() || entry.isDirectory) {
                        continue
                    }
                    importableFiles++
                }
            }
        }
        return importableFiles
    }

    @Throws(IOException::class)
    private fun backupExistingSavesToDownloads(host: Activity, stsRoot: File): String? {
        val sourceFiles = collectSaveFilesForBackup(stsRoot)
        if (sourceFiles.isEmpty()) {
            return null
        }

        val backupFileName = buildSaveBackupFileName()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backupExistingSavesToScopedDownloads(host, stsRoot, sourceFiles, backupFileName)
            "Download/$backupFileName"
        } else {
            backupExistingSavesToLegacyDownloads(stsRoot, sourceFiles, backupFileName)
        }
    }

    private fun collectSaveFilesForBackup(stsRoot: File): List<File> {
        val files = ArrayList<File>()
        for (folderName in SAVE_IMPORT_TOP_LEVEL_DIRS) {
            collectRegularFiles(File(stsRoot, folderName), files)
        }
        return files
    }

    private fun collectRegularFiles(root: File, sink: MutableList<File>) {
        if (!root.exists()) {
            return
        }
        if (root.isFile) {
            sink.add(root)
            return
        }
        val children = root.listFiles() ?: return
        for (child in children) {
            collectRegularFiles(child, sink)
        }
    }

    @Throws(IOException::class)
    private fun backupExistingSavesToScopedDownloads(
        host: Activity,
        stsRoot: File,
        sourceFiles: List<File>,
        backupFileName: String
    ) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, backupFileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val backupUri = host.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create backup archive in Downloads")

        var success = false
        try {
            host.contentResolver.openOutputStream(backupUri).use { output ->
                if (output == null) {
                    throw IOException("Unable to open backup archive destination")
                }
                writeSaveFilesToZip(output, stsRoot, sourceFiles)
            }
            success = true
        } finally {
            if (success) {
                val pendingValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                host.contentResolver.update(backupUri, pendingValues, null, null)
            } else {
                host.contentResolver.delete(backupUri, null, null)
            }
        }
    }

    @Suppress("DEPRECATION")
    @Throws(IOException::class)
    private fun backupExistingSavesToLegacyDownloads(
        stsRoot: File,
        sourceFiles: List<File>,
        backupFileName: String
    ): String {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("Downloads directory is unavailable")
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw IOException("Failed to create Downloads directory: ${downloadsDir.absolutePath}")
        }

        val backupFile = File(downloadsDir, backupFileName)
        FileOutputStream(backupFile, false).use { output ->
            writeSaveFilesToZip(output, stsRoot, sourceFiles)
        }
        return backupFile.absolutePath
    }

    @Throws(IOException::class)
    private fun writeSaveFilesToZip(output: OutputStream, stsRoot: File, sourceFiles: List<File>) {
        ZipOutputStream(output).use { zipOutput ->
            for (sourceFile in sourceFiles) {
                writeFileToZip(zipOutput, stsRoot, sourceFile)
            }
        }
    }

    private fun buildSaveBackupFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-saves-backup-${formatter.format(Date())}.zip"
    }

    @Throws(IOException::class)
    private fun clearExistingSaveTargets(stsRoot: File) {
        for (folderName in SAVE_IMPORT_TOP_LEVEL_DIRS) {
            val target = File(stsRoot, folderName)
            if (!target.exists()) {
                continue
            }
            if (!target.deleteRecursively()) {
                throw IOException("Failed to clear old save path: ${target.absolutePath}")
            }
        }
    }

    @Throws(IOException::class)
    private fun extractSaveArchive(host: Activity, uri: Uri, stsRoot: File): Int {
        val rootCanonical = stsRoot.canonicalPath
        var importedFiles = 0

        host.contentResolver.openInputStream(uri).use { rawInput ->
            if (rawInput == null) {
                throw IOException("Unable to open selected archive")
            }
            java.util.zip.ZipInputStream(rawInput).use { zipInput ->
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    val mappedPath = resolveImportableArchivePath(entry.name)
                    if (mappedPath.isNullOrEmpty()) {
                        continue
                    }

                    val output = File(stsRoot, mappedPath)
                    val outputCanonical = output.canonicalPath
                    if (outputCanonical != rootCanonical
                        && !outputCanonical.startsWith("$rootCanonical${File.separator}")
                    ) {
                        throw IOException("Unsafe archive entry: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        if (!output.exists() && !output.mkdirs()) {
                            throw IOException("Failed to create directory: ${output.absolutePath}")
                        }
                        continue
                    }

                    val parent = output.parentFile
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw IOException("Failed to create directory: ${parent.absolutePath}")
                    }

                    FileOutputStream(output, false).use { out ->
                        while (true) {
                            val read = zipInput.read(buffer)
                            if (read <= 0) {
                                break
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                    importedFiles++
                }
            }
        }
        return importedFiles
    }

    private fun resolveImportableArchivePath(rawEntryName: String?): String? {
        val mappedPath = mapArchiveEntryPath(rawEntryName) ?: return null
        val normalizedPath = normalizeImportTargetPath(mappedPath) ?: return null
        if (normalizedPath.equals("desktop-1.0.jar", ignoreCase = true)) {
            return null
        }
        if (normalizedPath.startsWith("__MACOSX/")) {
            return null
        }
        return normalizedPath
    }

    private fun mapArchiveEntryPath(rawEntryName: String?): String? {
        if (rawEntryName == null) {
            return null
        }

        var path = rawEntryName.replace('\\', '/')
        while (path.startsWith("/")) {
            path = path.substring(1)
        }
        if (path.isEmpty() || path.contains("../")) {
            return null
        }

        val filesSts = path.indexOf("files/sts/")
        path = if (filesSts >= 0) {
            path.substring(filesSts + "files/sts/".length)
        } else if (path.startsWith("sts/")) {
            path.substring("sts/".length)
        } else {
            val nestedSts = path.indexOf("/sts/")
            if (nestedSts >= 0) {
                path.substring(nestedSts + "/sts/".length)
            } else {
                stripWrapperFolder(path)
            }
        }

        while (path.startsWith("/")) {
            path = path.substring(1)
        }
        if (path.isEmpty() || path.contains("../")) {
            return null
        }
        return path
    }

    private fun stripWrapperFolder(path: String): String {
        val firstSlash = path.indexOf('/')
        if (firstSlash <= 0 || firstSlash >= path.length - 1) {
            return path
        }
        val first = path.substring(0, firstSlash).lowercase(Locale.ROOT)
        val remainder = path.substring(firstSlash + 1)
        var second = remainder
        val secondSlash = remainder.indexOf('/')
        if (secondSlash > 0) {
            second = remainder.substring(0, secondSlash)
        }
        second = second.lowercase(Locale.ROOT)

        return if (!isLikelySaveTopLevel(first) && isLikelySaveTopLevel(second)) {
            remainder
        } else {
            path
        }
    }

    private fun normalizeImportTargetPath(path: String): String? {
        var normalizedPath = path.replace('\\', '/')
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1)
        }
        if (normalizedPath.isEmpty() || normalizedPath.contains("../")) {
            return null
        }

        val firstSlash = normalizedPath.indexOf('/')
        val folder = if (firstSlash >= 0) {
            normalizedPath.substring(0, firstSlash)
        } else {
            normalizedPath
        }
        val rest = if (firstSlash >= 0 && firstSlash < normalizedPath.length - 1) {
            normalizedPath.substring(firstSlash + 1)
        } else {
            ""
        }

        val mappedFolder = when (folder.lowercase(Locale.ROOT)) {
            "preferences", "perferences", "betapreferences", "betaperferences" -> "betaPreferences"
            "multiple", "multiplayer" -> "multiplayer"
            else -> folder
        }
        return if (rest.isEmpty()) {
            mappedFolder
        } else {
            "$mappedFolder/$rest"
        }
    }

    private fun isLikelySaveTopLevel(folder: String): Boolean {
        return folder == "betapreferences"
            || folder == "preferences"
            || folder == "perferences"
            || folder == "betaperferences"
            || folder == "saves"
            || folder == "sendtodevs"
            || folder == "runs"
            || folder == "metrics"
            || folder == "home"
            || folder == "multiplayer"
            || folder == "multiple"
    }

    private fun buildSaveExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-saves-export-${formatter.format(Date())}.zip"
    }

    private fun buildDebugExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-debug-${formatter.format(Date())}.zip"
    }

    @Throws(IOException::class)
    private fun createShareDebugBundleFile(host: Activity): File {
        val shareDir = File(host.cacheDir, "share")
        if (!shareDir.exists() && !shareDir.mkdirs()) {
            throw IOException("Failed to create share directory: ${shareDir.absolutePath}")
        }
        val shareFile = File(shareDir, buildDebugExportFileName())
        val stsRoot = RuntimePaths.stsRoot(host)
        FileOutputStream(shareFile, false).use { output ->
            ZipOutputStream(output).use { zipOutput ->
                writeDebugBundleToZip(host, zipOutput, stsRoot)
            }
        }
        return shareFile
    }

    @Throws(IOException::class)
    private fun exportSaveBundle(host: Activity, uri: Uri): Int {
        val stsRoot = RuntimePaths.stsRoot(host)
        host.contentResolver.openOutputStream(uri).use { output ->
            if (output == null) {
                throw IOException("Unable to open destination file")
            }
            ZipOutputStream(output).use { zipOutput ->
                var exportedCount = 0
                for ((sourceFolder, archiveFolder) in SAVE_EXPORT_FOLDER_MAPPINGS) {
                    val sourceRoot = resolveSaveExportSourceFolder(stsRoot, sourceFolder) ?: continue
                    exportedCount += exportSaveFolderToZip(zipOutput, sourceRoot, archiveFolder)
                }
                if (exportedCount <= 0) {
                    val entry = ZipEntry("sts/README.txt")
                    zipOutput.putNextEntry(entry)
                    val message = "No save files found yet.\n" +
                        "Expected folders under: ${stsRoot.absolutePath}\n" +
                        "Folders: multiplayer, saves, betaPreferences, runs\n"
                    zipOutput.write(message.toByteArray(StandardCharsets.UTF_8))
                    zipOutput.closeEntry()
                }
                return exportedCount
            }
        }
    }

    private fun resolveSaveExportSourceFolder(stsRoot: File, sourceFolder: String): File? {
        val candidates = when (sourceFolder.lowercase(Locale.ROOT)) {
            "multiplayer" -> arrayOf("multiplayer", "multiple")
            "betapreferences" -> arrayOf("betaPreferences", "betapreferences", "betaPerferences", "betaperferences")
            else -> arrayOf(sourceFolder)
        }
        for (candidateName in candidates) {
            val candidate = File(stsRoot, candidateName)
            if (candidate.exists()) {
                return candidate
            }
        }
        return null
    }

    @Throws(IOException::class)
    private fun exportSaveFolderToZip(zipOutput: ZipOutputStream, sourceRoot: File, archiveRoot: String): Int {
        if (!sourceRoot.exists()) {
            return 0
        }
        val sourceFiles = ArrayList<File>()
        collectRegularFiles(sourceRoot, sourceFiles)
        var exportedCount = 0
        for (sourceFile in sourceFiles) {
            val entryName = buildSaveExportEntryName(sourceRoot, archiveRoot, sourceFile)
            writeFileToZip(zipOutput, sourceFile, entryName)
            exportedCount++
        }
        return exportedCount
    }

    @Throws(IOException::class)
    private fun buildSaveExportEntryName(sourceRoot: File, archiveRoot: String, sourceFile: File): String {
        val sourceRootPath = sourceRoot.canonicalPath
        val sourceFilePath = sourceFile.canonicalPath
        val relativePath = if (sourceFilePath.startsWith("$sourceRootPath${File.separator}")) {
            sourceFilePath.substring(sourceRootPath.length + 1)
        } else {
            sourceFile.name
        }
        val normalizedRelativePath = relativePath.replace('\\', '/')
        return "sts/$archiveRoot/$normalizedRelativePath"
    }

    private fun collectDebugBundleFiles(host: Activity, stsRoot: File): List<File> {
        val debugFiles = ArrayList<File>()
        addDebugFileIfExists(debugFiles, RuntimePaths.latestLog(host))
        addDebugFileIfExists(debugFiles, File(stsRoot, "jvm_output.log"))
        addDebugFileIfExists(debugFiles, RuntimePaths.lastCrashReport(host))
        addDebugFileIfExists(debugFiles, RuntimePaths.enabledModsConfig(host))

        val hsErrFiles = stsRoot.listFiles { _, name ->
            name != null && name.startsWith("hs_err_pid") && name.endsWith(".log")
        }
        if (hsErrFiles != null && hsErrFiles.isNotEmpty()) {
            Arrays.sort(hsErrFiles) { a, b -> b.lastModified().compareTo(a.lastModified()) }
            for (hsErrFile in hsErrFiles) {
                addDebugFileIfExists(debugFiles, hsErrFile)
            }
        }
        return debugFiles
    }

    @Throws(IOException::class)
    private fun writeDebugBundleToZip(host: Activity, zipOutput: ZipOutputStream, stsRoot: File): Int {
        val debugFiles = collectDebugBundleFiles(host, stsRoot)
        var exportedCount = 0
        for (file in debugFiles) {
            writeFileToZip(zipOutput, stsRoot, file)
            exportedCount++
        }
        if (exportedCount <= 0) {
            val entry = ZipEntry("sts/README.txt")
            zipOutput.putNextEntry(entry)
            val message = "No debug log files found yet.\n" +
                "Expected paths under: ${stsRoot.absolutePath}\n" +
                "Files: latestlog.txt, jvm_output.log, hs_err_pid*.log, last_crash_report.txt\n"
            zipOutput.write(message.toByteArray(StandardCharsets.UTF_8))
            zipOutput.closeEntry()
        }
        return exportedCount
    }

    @Throws(IOException::class)
    private fun writeFileToZip(zipOutput: ZipOutputStream, stsRoot: File, sourceFile: File) {
        val entryName = buildDebugEntryName(stsRoot, sourceFile)
        writeFileToZip(zipOutput, sourceFile, entryName)
    }

    @Throws(IOException::class)
    private fun writeFileToZip(zipOutput: ZipOutputStream, sourceFile: File, entryName: String) {
        val entry = ZipEntry(entryName)
        if (sourceFile.lastModified() > 0) {
            entry.time = sourceFile.lastModified()
        }
        zipOutput.putNextEntry(entry)
        FileInputStream(sourceFile).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                zipOutput.write(buffer, 0, read)
            }
        }
        zipOutput.closeEntry()
    }

    @Throws(IOException::class)
    private fun buildDebugEntryName(stsRoot: File, sourceFile: File): String {
        val rootPath = stsRoot.canonicalPath
        val filePath = sourceFile.canonicalPath
        val relativePath = if (filePath.startsWith("$rootPath${File.separator}")) {
            filePath.substring(rootPath.length + 1)
        } else {
            sourceFile.name
        }
        return "sts/${relativePath.replace('\\', '/')}"
    }

    private fun addDebugFileIfExists(files: MutableList<File>, file: File?) {
        if (file != null && file.isFile && file.length() > 0) {
            files.add(file)
        }
    }

    private fun readBackBehaviorSelection(host: Activity): Boolean {
        return host.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY_BACK_IMMEDIATE_EXIT, true)
    }

    private fun saveBackBehaviorSelection(host: Activity, immediateExit: Boolean) {
        host.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE)
            .edit {
                putBoolean(PREF_KEY_BACK_IMMEDIATE_EXIT, immediateExit)
            }
    }

    private fun readManualDismissBootOverlaySelection(host: Activity): Boolean {
        return host.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY_MANUAL_DISMISS_BOOT_OVERLAY, false)
    }

    private fun saveManualDismissBootOverlaySelection(host: Activity, enabled: Boolean) {
        host.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE)
            .edit {
                putBoolean(PREF_KEY_MANUAL_DISMISS_BOOT_OVERLAY, enabled)
            }
    }

    private fun readTargetFpsSelection(host: Activity): Int {
        val stored = host.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE)
            .getInt(PREF_KEY_TARGET_FPS, DEFAULT_TARGET_FPS)
        return normalizeTargetFps(stored)
    }

    private fun saveTargetFpsSelection(host: Activity, targetFps: Int) {
        host.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE)
            .edit {
                putInt(PREF_KEY_TARGET_FPS, normalizeTargetFps(targetFps))
            }
    }

    private fun normalizeTargetFps(targetFps: Int): Int {
        return if (TARGET_FPS_OPTIONS.contains(targetFps)) {
            targetFps
        } else {
            DEFAULT_TARGET_FPS
        }
    }

    private fun readTouchscreenEnabledSelection(host: Activity): Boolean {
        val files = arrayOf(
            File(RuntimePaths.betaPreferencesDir(host), GAMEPLAY_SETTINGS_FILE_NAME),
            File(RuntimePaths.preferencesDir(host), GAMEPLAY_SETTINGS_FILE_NAME)
        )
        for (file in files) {
            val value = readGameplaySettingsBoolean(file, GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN)
            if (value != null) {
                return value
            }
        }
        return DEFAULT_TOUCHSCREEN_ENABLED
    }

    private fun saveTouchscreenEnabledSelection(host: Activity, enabled: Boolean): Boolean {
        val value = if (enabled) "true" else "false"
        return try {
            writeGameplaySettingsValue(
                host,
                File(RuntimePaths.betaPreferencesDir(host), GAMEPLAY_SETTINGS_FILE_NAME),
                GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN,
                value
            )
            writeGameplaySettingsValue(
                host,
                File(RuntimePaths.preferencesDir(host), GAMEPLAY_SETTINGS_FILE_NAME),
                GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN,
                value
            )
            true
        } catch (error: IOException) {
            Toast.makeText(
                host,
                "Failed to save touchscreen setting: ${error.message}",
                Toast.LENGTH_SHORT
            ).show()
            false
        }
    }

    private fun readGameplaySettingsBoolean(file: File, key: String): Boolean? {
        val objectValue = readJsonObject(file) ?: return null
        if (!objectValue.has(key)) {
            return null
        }
        return parseBooleanLike(objectValue.opt(key), DEFAULT_TOUCHSCREEN_ENABLED)
    }

    @Throws(IOException::class)
    private fun writeGameplaySettingsValue(host: Activity, file: File, key: String, value: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        val root = mergeJsonObjects(
            readBundledGameplaySettingsDefaults(host),
            readJsonObject(file)
        )
        root.put(key, value)
        FileOutputStream(file, false).use { out ->
            out.write(root.toString(2).toByteArray(StandardCharsets.UTF_8))
            out.write('\n'.code)
        }
    }

    private fun readBundledGameplaySettingsDefaults(host: Activity): JSONObject? {
        return try {
            host.assets.open(GAMEPLAY_SETTINGS_DEFAULT_ASSET_PATH).use { input ->
                val text = input.readBytes().toString(StandardCharsets.UTF_8).trim()
                if (text.isEmpty()) {
                    JSONObject()
                } else {
                    val parsed = JSONTokener(text).nextValue()
                    parsed as? JSONObject ?: JSONObject()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun mergeJsonObjects(base: JSONObject?, override: JSONObject?): JSONObject {
        val merged = JSONObject()
        if (base != null) {
            val keys = base.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                merged.put(key, base.opt(key))
            }
        }
        if (override != null) {
            val keys = override.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                merged.put(key, override.opt(key))
            }
        }
        return merged
    }

    private fun readJsonObject(file: File): JSONObject? {
        if (!file.isFile) {
            return null
        }
        return try {
            val text = file.readText(StandardCharsets.UTF_8).trim()
            if (text.isEmpty()) {
                return JSONObject()
            }
            val parsed = JSONTokener(text).nextValue()
            parsed as? JSONObject
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseBooleanLike(value: Any?, fallback: Boolean): Boolean {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> {
                val normalized = value.trim()
                when {
                    normalized.equals("true", ignoreCase = true) || normalized == "1" -> true
                    normalized.equals("false", ignoreCase = true) || normalized == "0" -> false
                    else -> fallback
                }
            }

            else -> fallback
        }
    }

    private fun buildLogPathText(host: Activity): String {
        return "Log: ${RuntimePaths.latestLog(host).absolutePath}\n" +
            "VM: ${File(RuntimePaths.stsRoot(host), "jvm_output.log").absolutePath}\n" +
            "Crash: ${File(RuntimePaths.stsRoot(host), "hs_err_pid*.log").absolutePath}\n" +
            "Last: ${RuntimePaths.lastCrashReport(host).absolutePath}"
    }

    override fun onCleared() {
        executor.shutdownNow()
        super.onCleared()
    }
}

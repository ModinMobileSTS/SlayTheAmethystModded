package io.stamethyst.ui.settings

import android.app.Activity
import android.content.DialogInterface
import android.graphics.Color
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.backend.launch.JvmLogRotationManager
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.LauncherIcon
import io.stamethyst.LauncherIconManager
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.R
import io.stamethyst.config.RuntimePaths
import io.stamethyst.backend.mods.StsJarValidator
import io.stamethyst.ui.preferences.LauncherPreferences
import java.io.IOException
import java.util.ArrayList
import java.util.LinkedHashSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Stable
class SettingsScreenViewModel : ViewModel() {
    private companion object {
        const val LEGACY_JIBAO_SAVE_WARNING_MESSAGE =
            "存档为旧鸡煲版的数据格式，有丢失历史对局和当前对局的风险，请升级鸡煲版为 1.5.4-dev4 后重新导出存档再导入。"
    }

    sealed interface Effect {
        data object OpenImportJarPicker : Effect
        data object OpenImportModsPicker : Effect
        data object OpenImportSavesPicker : Effect
        data class OpenExportSavesPicker(val fileName: String) : Effect
        data class OpenExportLogsPicker(val fileName: String) : Effect
        data object OpenCompatibility : Effect
    }

    data class UiState(
        val busy: Boolean = false,
        val busyMessage: String? = null,
        val selectedRenderScale: Float = RenderScaleService.DEFAULT_RENDER_SCALE,
        val selectedTargetFps: Int = LauncherPreferences.DEFAULT_TARGET_FPS,
        val selectedJvmHeapMaxMb: Int = LauncherPreferences.DEFAULT_JVM_HEAP_MAX_MB,
        val jvmHeapMinMb: Int = LauncherPreferences.MIN_JVM_HEAP_MAX_MB,
        val jvmHeapMaxMb: Int = LauncherPreferences.MAX_JVM_HEAP_MAX_MB,
        val jvmHeapStepMb: Int = LauncherPreferences.JVM_HEAP_STEP_MB,
        val selectedLauncherIcon: LauncherIcon = LauncherIcon.AMBER,
        val backImmediateExit: Boolean = LauncherPreferences.DEFAULT_BACK_IMMEDIATE_EXIT,
        val manualDismissBootOverlay: Boolean = LauncherPreferences.DEFAULT_MANUAL_DISMISS_BOOT_OVERLAY,
        val showFloatingMouseWindow: Boolean = LauncherPreferences.DEFAULT_SHOW_FLOATING_MOUSE_WINDOW,
        val autoSwitchLeftAfterRightClick: Boolean = LauncherPreferences.DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK,
        val lwjglDebugEnabled: Boolean = LauncherPreferences.DEFAULT_LWJGL_DEBUG,
        val touchscreenEnabled: Boolean = GameplaySettingsService.DEFAULT_TOUCHSCREEN_ENABLED,
        val statusText: String = "",
        val logPathText: String = "",
        val targetFpsOptions: List<Int> = LauncherPreferences.TARGET_FPS_OPTIONS.toList()
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 16)

    var uiState by mutableStateOf(UiState())
        private set

    val effects = _effects.asSharedFlow()

    fun bind(
        activity: Activity,
        targetFpsOptions: IntArray = LauncherPreferences.TARGET_FPS_OPTIONS
    ) {
        val options = targetFpsOptions.toList()
        if (uiState.targetFpsOptions != options) {
            uiState = uiState.copy(targetFpsOptions = options)
        }

        if (uiState.statusText.isBlank()) {
            refreshStatus(activity, clearBusy = false)
        }
    }

    fun refreshStatus(host: Activity, clearBusy: Boolean = true) {
        executor.execute {
            try {
                val hasJar = RuntimePaths.importedStsJar(host).exists()
                val hasMts = RuntimePaths.importedMtsJar(host).exists() || hasBundledAsset(host, "components/mods/ModTheSpire.jar")
                val hasBaseMod = RuntimePaths.importedBaseModJar(host).exists() || hasBundledAsset(host, "components/mods/BaseMod.jar")
                val hasStsLib = RuntimePaths.importedStsLibJar(host).exists() || hasBundledAsset(host, "components/mods/StSLib.jar")

                val renderScale = RenderScaleService.readValue(host)
                val targetFps = readTargetFpsSelection(host)
                val jvmHeapMaxMb = readJvmHeapMaxSelection(host)
                val backImmediateExit = readBackBehaviorSelection(host)
                val manualDismissBootOverlay = readManualDismissBootOverlaySelection(host)
                val showFloatingMouseWindow = readShowFloatingMouseWindowSelection(host)
                val autoSwitchLeftAfterRightClick = readAutoSwitchLeftAfterRightClickSelection(host)
                val lwjglDebugEnabled = readLwjglDebugSelection(host)
                val touchscreenEnabled = readTouchscreenEnabledSelection(host)
                val selectedLauncherIcon = LauncherIconManager.readEffectiveSelection(host)
                val virtualFboPocEnabled = CompatibilitySettings.isVirtualFboPocEnabled(host)
                val globalAtlasFilterCompatEnabled = CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(host)
                val forceLinearMipmapFilterEnabled = CompatibilitySettings.isForceLinearMipmapFilterEnabled(host)

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
                    "\nRender scale: ${RenderScaleService.format(renderScale)} (0.50-1.00)" +
                    "\nTarget FPS: $targetFps" +
                    "\nJVM heap max: ${jvmHeapMaxMb} MB" +
                    "\nTouchscreen Enabled: " + if (touchscreenEnabled) "ON" else "OFF" +
                    "\nManual dismiss boot overlay: " + if (manualDismissBootOverlay) "ON" else "OFF" +
                    "\n" + host.getString(
                        R.string.status_floating_touch_mouse_window_format,
                        if (showFloatingMouseWindow) "ON" else "OFF"
                    ) +
                    "\nRight click auto switch to left: " + if (autoSwitchLeftAfterRightClick) "ON" else "OFF" +
                    "\nLWJGL Debug: " + if (lwjglDebugEnabled) "ON" else "OFF" +
                    "\nLauncher icon: ${selectedLauncherIcon.title}" +
                    "\nVirtual FBO PoC: " + if (virtualFboPocEnabled) "ON" else "OFF" +
                    "\nGlobal atlas filter compat: " + if (globalAtlasFilterCompatEnabled) "ON" else "OFF" +
                    "\nForce linear mipmap filter: " + if (forceLinearMipmapFilterEnabled) "ON" else "OFF" +
                    "\nBundled JRE path: app/src/main/assets/components/jre"

                host.runOnUiThread {
                    uiState = uiState.copy(
                        busy = if (clearBusy) false else uiState.busy,
                        busyMessage = if (clearBusy) null else uiState.busyMessage,
                        selectedRenderScale = renderScale,
                        selectedTargetFps = targetFps,
                        selectedJvmHeapMaxMb = jvmHeapMaxMb,
                        selectedLauncherIcon = selectedLauncherIcon,
                        backImmediateExit = backImmediateExit,
                        manualDismissBootOverlay = manualDismissBootOverlay,
                        showFloatingMouseWindow = showFloatingMouseWindow,
                        autoSwitchLeftAfterRightClick = autoSwitchLeftAfterRightClick,
                        lwjglDebugEnabled = lwjglDebugEnabled,
                        touchscreenEnabled = touchscreenEnabled,
                        statusText = status,
                        logPathText = buildLogPathText(host)
                    )
                }
            } catch (_: Throwable) {
                host.runOnUiThread {
                    if (clearBusy) {
                        uiState = uiState.copy(
                            busy = false,
                            busyMessage = null
                        )
                    }
                }
            }
        }
    }

    fun onRenderScaleSelected(host: Activity, value: Float) {
        if (uiState.busy) {
            return
        }
        val clampedValue = value.coerceIn(
            RenderScaleService.MIN_RENDER_SCALE,
            RenderScaleService.MAX_RENDER_SCALE
        )
        if (kotlin.math.abs(clampedValue - uiState.selectedRenderScale) < 0.0001f) {
            return
        }
        val normalized = try {
            RenderScaleService.save(host, clampedValue)
        } catch (error: IOException) {
            Toast.makeText(host, error.message ?: "Failed to save render scale", Toast.LENGTH_SHORT).show()
            return
        }
        val normalizedValue = normalized.toFloatOrNull() ?: clampedValue
        uiState = uiState.copy(selectedRenderScale = normalizedValue)
        updateStatusRenderScaleLine(normalized)
        refreshStatus(host)
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
        _effects.tryEmit(Effect.OpenExportSavesPicker(SettingsFileService.buildSaveExportFileName()))
    }

    fun onExportLogs() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(Effect.OpenExportLogsPicker(SettingsFileService.buildJvmLogExportFileName()))
    }

    fun onTargetFpsSelected(host: Activity, targetFps: Int) {
        if (uiState.busy) {
            return
        }
        val normalizedTargetFps = LauncherPreferences.normalizeTargetFps(targetFps)
        uiState = uiState.copy(selectedTargetFps = normalizedTargetFps)
        saveTargetFpsSelection(host, normalizedTargetFps)
        refreshStatus(host)
    }

    fun onJvmHeapMaxSelected(host: Activity, heapMaxMb: Int) {
        if (uiState.busy) {
            return
        }
        val normalizedHeapMax = LauncherPreferences.normalizeJvmHeapMaxMb(heapMaxMb)
        if (normalizedHeapMax == uiState.selectedJvmHeapMaxMb) {
            return
        }
        uiState = uiState.copy(selectedJvmHeapMaxMb = normalizedHeapMax)
        saveJvmHeapMaxSelection(host, normalizedHeapMax)
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

    fun onShowFloatingMouseWindowChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(showFloatingMouseWindow = enabled)
        saveShowFloatingMouseWindowSelection(host, enabled)
        refreshStatus(host)
    }

    fun onAutoSwitchLeftAfterRightClickChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(autoSwitchLeftAfterRightClick = enabled)
        saveAutoSwitchLeftAfterRightClickSelection(host, enabled)
        refreshStatus(host)
    }

    fun onLwjglDebugChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(lwjglDebugEnabled = enabled)
        saveLwjglDebugSelection(host, enabled)
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

    fun onJarPicked(
        host: Activity,
        uri: Uri?,
        onCompleted: ((Boolean) -> Unit)? = null
    ) {
        if (uri == null) {
            return
        }
        setBusy(true, "Importing desktop-1.0.jar...")
        executor.execute {
            try {
                SettingsFileService.copyUriToFile(host, uri, RuntimePaths.importedStsJar(host))
                StsJarValidator.validate(RuntimePaths.importedStsJar(host))
                host.runOnUiThread {
                    Toast.makeText(host, "Imported desktop-1.0.jar", Toast.LENGTH_SHORT).show()
                    refreshStatus(host)
                    onCompleted?.invoke(true)
//                    todo: host.notifyMainDataChanged()
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    Toast.makeText(host, "Import failed: ${error.message}", Toast.LENGTH_LONG).show()
                    refreshStatus(host)
                    onCompleted?.invoke(false)
                }
            }
        }
    }

    fun onModJarsPicked(
        host: Activity,
        uris: List<Uri>?,
        onCompleted: (() -> Unit)? = null
    ) {
        if (uris.isNullOrEmpty()) {
            return
        }
        setBusy(true, "Importing selected mod jars...")
        executor.execute {
            var imported = 0
            val errors = ArrayList<String>()
            val blockedComponents = LinkedHashSet<String>()
            for (uri in uris) {
                try {
                    SettingsFileService.importModJar(host, uri)
                    imported++
                } catch (error: Throwable) {
                    if (error is SettingsFileService.ReservedModImportException) {
                        blockedComponents.add(error.blockedComponent)
                    } else {
                        val name = SettingsFileService.resolveDisplayName(host, uri)
                        errors.add("$name: ${error.message}")
                    }
                }
            }

            val importedCount = imported
            val failedCount = errors.size
            val blockedCount = blockedComponents.size
            val firstError = if (failedCount > 0) errors[0] else null
            val blockedList = blockedComponents.toList()
            host.runOnUiThread {
                if (blockedList.isNotEmpty()) {
                    AlertDialog.Builder(host)
                        .setTitle("禁止导入内置核心组件")
                        .setMessage(SettingsFileService.buildReservedModImportMessage(blockedList))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
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

                    failedCount > 0 -> {
                        Toast.makeText(host, "Mod import failed: $firstError", Toast.LENGTH_LONG).show()
                    }

                    blockedCount > 0 -> {
                        Toast.makeText(host, "Blocked $blockedCount built-in component import(s)", Toast.LENGTH_SHORT).show()
                    }
                }
                refreshStatus(host)
                onCompleted?.invoke()
//                todo host.notifyMainDataChanged()
            }
        }
    }

    fun onSavesArchivePicked(host: Activity, uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(true, "Checking save archive...")
        executor.execute {
            try {
                val isLegacyArchive = SettingsFileService.isLegacyJibaoSaveArchive(host, uri)
                host.runOnUiThread {
                    if (!isLegacyArchive) {
                        importSavesArchive(host, uri)
                    } else {
                        setBusy(false, null)
                        showLegacySaveImportWarningDialog(host, uri)
                    }
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    Toast.makeText(host, "Save import failed: ${error.message}", Toast.LENGTH_LONG).show()
                    refreshStatus(host)
                }
            }
        }
    }

    private fun importSavesArchive(host: Activity, uri: Uri) {
        setBusy(true, "Importing save archive...")
        executor.execute {
            try {
                val result = SettingsFileService.importSaveArchive(host, uri)
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

    private fun showLegacySaveImportWarningDialog(host: Activity, uri: Uri) {
        val dialog = AlertDialog.Builder(host)
            .setTitle("警告")
            .setMessage(LEGACY_JIBAO_SAVE_WARNING_MESSAGE)
            .setNegativeButton("取消导入", null)
            .setPositiveButton("仍然导入") { _, _ ->
                importSavesArchive(host, uri)
            }
            .create()
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#D32F2F"))
    }

    fun onSavesExportPicked(host: Activity, uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(true, "Exporting save archive...")
        executor.execute {
            try {
                val exportedCount = SettingsFileService.exportSaveBundle(host, uri)
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

    fun onLogsExportPicked(host: Activity, uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(true, "Exporting JVM logs...")
        executor.execute {
            try {
                val exportedCount = SettingsFileService.exportJvmLogsBundle(host, uri)
                host.runOnUiThread {
                    Toast.makeText(host, "JVM logs exported ($exportedCount files)", Toast.LENGTH_LONG).show()
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    Toast.makeText(host, "Log export failed: ${error.message}", Toast.LENGTH_LONG).show()
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

    private fun updateStatusRenderScaleLine(normalizedRenderScale: String) {
        val status = uiState.statusText
        if (status.isBlank()) {
            return
        }
        val lines = status.lines().toMutableList()
        val lineIndex = lines.indexOfFirst { it.startsWith("Render scale: ") }
        if (lineIndex < 0) {
            return
        }
        lines[lineIndex] = "Render scale: $normalizedRenderScale (0.50-1.00)"
        uiState = uiState.copy(statusText = lines.joinToString("\n"))
    }

    private fun readBackBehaviorSelection(host: Activity): Boolean {
        return LauncherPreferences.readBackImmediateExit(host)
    }

    private fun saveBackBehaviorSelection(host: Activity, immediateExit: Boolean) {
        LauncherPreferences.saveBackImmediateExit(host, immediateExit)
    }

    private fun readManualDismissBootOverlaySelection(host: Activity): Boolean {
        return LauncherPreferences.readManualDismissBootOverlay(host)
    }

    private fun saveManualDismissBootOverlaySelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveManualDismissBootOverlay(host, enabled)
    }

    private fun readShowFloatingMouseWindowSelection(host: Activity): Boolean {
        return LauncherPreferences.readShowFloatingMouseWindow(host)
    }

    private fun saveShowFloatingMouseWindowSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveShowFloatingMouseWindow(host, enabled)
    }

    private fun readAutoSwitchLeftAfterRightClickSelection(host: Activity): Boolean {
        return LauncherPreferences.readAutoSwitchLeftAfterRightClick(host)
    }

    private fun saveAutoSwitchLeftAfterRightClickSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveAutoSwitchLeftAfterRightClick(host, enabled)
    }

    private fun readLwjglDebugSelection(host: Activity): Boolean {
        return LauncherPreferences.isLwjglDebugEnabled(host)
    }

    private fun saveLwjglDebugSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setLwjglDebugEnabled(host, enabled)
    }

    private fun readTargetFpsSelection(host: Activity): Int {
        return LauncherPreferences.readTargetFps(host)
    }

    private fun saveTargetFpsSelection(host: Activity, targetFps: Int) {
        LauncherPreferences.saveTargetFps(host, targetFps)
    }

    private fun readJvmHeapMaxSelection(host: Activity): Int {
        return LauncherPreferences.readJvmHeapMaxMb(host)
    }

    private fun saveJvmHeapMaxSelection(host: Activity, heapMaxMb: Int) {
        LauncherPreferences.saveJvmHeapMaxMb(host, heapMaxMb)
    }

    private fun readTouchscreenEnabledSelection(host: Activity): Boolean {
        return GameplaySettingsService.readTouchscreenEnabled(host)
    }

    private fun saveTouchscreenEnabledSelection(host: Activity, enabled: Boolean): Boolean {
        return try {
            GameplaySettingsService.saveTouchscreenEnabled(host, enabled)
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

    private fun buildLogPathText(host: Activity): String {
        val logs = JvmLogRotationManager.listLogFiles(host)
        val logsDir = RuntimePaths.jvmLogsDir(host).absolutePath
        return buildString {
            append("JVM logs dir: ").append(logsDir)
            append("\nSlots: ").append(logs.size).append('/').append(JvmLogRotationManager.MAX_LOG_SLOTS)
            if (logs.isEmpty()) {
                append("\n(no logs yet)")
            } else {
                for (log in logs) {
                    append("\n- ").append(log.name)
                }
            }
        }
    }

    override fun onCleared() {
        executor.shutdownNow()
        super.onCleared()
    }
}

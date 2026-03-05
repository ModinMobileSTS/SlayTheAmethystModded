package io.stamethyst.ui.settings

import android.app.Activity
import android.app.ActivityManager
import android.content.DialogInterface
import android.os.Build
import android.graphics.Color
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.launch.JvmLogRotationManager
import io.stamethyst.LauncherIcon
import io.stamethyst.LauncherIconManager
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.R
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.RuntimePaths
import io.stamethyst.backend.mods.StsJarValidator
import io.stamethyst.ui.preferences.LauncherPreferences
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.jar.Manifest
import java.util.zip.ZipInputStream
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
        data class ShareJvmLogsBundle(val payload: JvmLogsSharePayload) : Effect
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
        val backBehavior: BackBehavior = LauncherPreferences.DEFAULT_BACK_BEHAVIOR,
        val manualDismissBootOverlay: Boolean = LauncherPreferences.DEFAULT_MANUAL_DISMISS_BOOT_OVERLAY,
        val showFloatingMouseWindow: Boolean = LauncherPreferences.DEFAULT_SHOW_FLOATING_MOUSE_WINDOW,
        val longPressMouseShowsKeyboard: Boolean = LauncherPreferences.DEFAULT_LONG_PRESS_MOUSE_SHOWS_KEYBOARD,
        val autoSwitchLeftAfterRightClick: Boolean = LauncherPreferences.DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK,
        val showModFileName: Boolean = LauncherPreferences.DEFAULT_SHOW_MOD_FILE_NAME,
        val mobileHudEnabled: Boolean = LauncherPreferences.DEFAULT_MOBILE_HUD_ENABLED,
        val lwjglDebugEnabled: Boolean = LauncherPreferences.DEFAULT_LWJGL_DEBUG,
        val gdxPadCursorDebugEnabled: Boolean = LauncherPreferences.DEFAULT_GDX_PAD_CURSOR_DEBUG,
        val glBridgeSwapHeartbeatDebugEnabled: Boolean = LauncherPreferences.DEFAULT_GLBRIDGE_SWAP_HEARTBEAT_DEBUG,
        val touchscreenEnabled: Boolean = GameplaySettingsService.DEFAULT_TOUCHSCREEN_ENABLED,
        val statusText: String = "",
        val logPathText: String = "",
        val targetFpsOptions: List<Int> = LauncherPreferences.TARGET_FPS_OPTIONS.toList()
    )

    private data class CoreDependencyStatus(
        val label: String,
        val available: Boolean,
        val source: String,
        val version: String
    )

    private data class DeviceRuntimeStatus(
        val cpuModel: String,
        val cpuArch: String,
        val availableMemoryBytes: Long,
        val totalMemoryBytes: Long
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

                val renderScale = RenderScaleService.readValue(host)
                val targetFps = readTargetFpsSelection(host)
                val jvmHeapMaxMb = readJvmHeapMaxSelection(host)
                val backBehavior = readBackBehaviorSelection(host)
                val manualDismissBootOverlay = readManualDismissBootOverlaySelection(host)
                val showFloatingMouseWindow = readShowFloatingMouseWindowSelection(host)
                val longPressMouseShowsKeyboard = readLongPressMouseShowsKeyboardSelection(host)
                val autoSwitchLeftAfterRightClick = readAutoSwitchLeftAfterRightClickSelection(host)
                val showModFileName = readShowModFileNameSelection(host)
                val mobileHudEnabled = readMobileHudEnabledSelection(host)
                val lwjglDebugEnabled = readLwjglDebugSelection(host)
                val gdxPadCursorDebugEnabled = readGdxPadCursorDebugSelection(host)
                val glBridgeSwapHeartbeatDebugEnabled = readGlBridgeSwapHeartbeatDebugSelection(host)
                val touchscreenEnabled = readTouchscreenEnabledSelection(host)
                val selectedLauncherIcon = LauncherIconManager.readEffectiveSelection(host)
                val virtualFboPocEnabled = CompatibilitySettings.isVirtualFboPocEnabled(host)
                val globalAtlasFilterCompatEnabled = CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(host)
                val modManifestRootCompatEnabled = CompatibilitySettings.isModManifestRootCompatEnabled(host)
                val runtimeTextureCompatEnabled = CompatibilitySettings.isRuntimeTextureCompatEnabled(host)
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

                val coreMtsStatus = resolveCoreDependencyStatus(
                    host = host,
                    label = "ModTheSpire",
                    importedJar = RuntimePaths.importedMtsJar(host),
                    bundledAssetPath = "components/mods/ModTheSpire.jar"
                )
                val coreBaseModStatus = resolveCoreDependencyStatus(
                    host = host,
                    label = "BaseMod",
                    importedJar = RuntimePaths.importedBaseModJar(host),
                    bundledAssetPath = "components/mods/BaseMod.jar"
                )
                val coreStsLibStatus = resolveCoreDependencyStatus(
                    host = host,
                    label = "StSLib",
                    importedJar = RuntimePaths.importedStsLibJar(host),
                    bundledAssetPath = "components/mods/StSLib.jar"
                )
                val deviceRuntimeStatus = collectDeviceRuntimeStatus(host)

                val status = buildString {
                    append("核心依赖状态")
                    append("\ndesktop-1.0.jar: ").append(if (hasJar) "可用" else "缺失")
                    append('\n').append(formatCoreDependencyLine(coreMtsStatus))
                    append('\n').append(formatCoreDependencyLine(coreBaseModStatus))
                    append('\n').append(formatCoreDependencyLine(coreStsLibStatus))
                    append("\nOptional mods enabled: $optionalEnabled/$optionalTotal")

                    append("\n\n设备信息")
                    append("\nCPU 型号: ").append(deviceRuntimeStatus.cpuModel)
                    append("\nCPU 架构: ").append(deviceRuntimeStatus.cpuArch)
                    append("\n可用内存: ")
                        .append(formatBytes(deviceRuntimeStatus.availableMemoryBytes))
                    append(" / 总内存: ")
                        .append(formatBytes(deviceRuntimeStatus.totalMemoryBytes))

                    append("\n\n启动与兼容设置")
                    append("\nRender scale: ${RenderScaleService.format(renderScale)} (0.50-1.00)")
                    append("\nTarget FPS: $targetFps")
                    append("\nJVM heap max: ${jvmHeapMaxMb} MB")
                    append("\nBack behavior: ${backBehavior.displayName()}")
                    append("\nTouchscreen Enabled: ").append(if (touchscreenEnabled) "ON" else "OFF")
                    append("\nMobile HUD Enabled: ").append(if (mobileHudEnabled) "ON" else "OFF")
                    append("\nManual dismiss boot overlay: ")
                        .append(if (manualDismissBootOverlay) "ON" else "OFF")
                    append("\n")
                        .append(
                            host.getString(
                                R.string.status_floating_touch_mouse_window_format,
                                if (showFloatingMouseWindow) "ON" else "OFF"
                            )
                        )
                    append("\n")
                        .append(
                            host.getString(
                                R.string.status_touch_mouse_long_press_keyboard_format,
                                if (longPressMouseShowsKeyboard) "ON" else "OFF"
                            )
                        )
                    append("\nRight click auto switch to left: ")
                        .append(if (autoSwitchLeftAfterRightClick) "ON" else "OFF")
                    append("\nMod card name from file: ").append(if (showModFileName) "ON" else "OFF")
                    append("\nLWJGL Debug: ").append(if (lwjglDebugEnabled) "ON" else "OFF")
                    append("\nGDX pad cursor debug log: ")
                        .append(if (gdxPadCursorDebugEnabled) "ON" else "OFF")
                    append("\nGLBridge swap heartbeat log: ")
                        .append(if (glBridgeSwapHeartbeatDebugEnabled) "ON" else "OFF")
                    append("\nLauncher icon: ${selectedLauncherIcon.title}")
                    append("\nVirtual FBO PoC: ").append(if (virtualFboPocEnabled) "ON" else "OFF")
                    append("\nGlobal atlas filter compat: ")
                        .append(if (globalAtlasFilterCompatEnabled) "ON" else "OFF")
                    append("\nMod manifest root compat: ")
                        .append(if (modManifestRootCompatEnabled) "ON" else "OFF")
                    append("\nRuntime texture compat: ")
                        .append(if (runtimeTextureCompatEnabled) "ON" else "OFF")
                    append("\nForce linear mipmap filter: ")
                        .append(if (forceLinearMipmapFilterEnabled) "ON" else "OFF")
                    append("\nBundled JRE path: app/src/main/assets/components/jre")
                }

                host.runOnUiThread {
                    uiState = uiState.copy(
                        busy = if (clearBusy) false else uiState.busy,
                        busyMessage = if (clearBusy) null else uiState.busyMessage,
                        selectedRenderScale = renderScale,
                        selectedTargetFps = targetFps,
                        selectedJvmHeapMaxMb = jvmHeapMaxMb,
                        selectedLauncherIcon = selectedLauncherIcon,
                        backBehavior = backBehavior,
                        manualDismissBootOverlay = manualDismissBootOverlay,
                        showFloatingMouseWindow = showFloatingMouseWindow,
                        longPressMouseShowsKeyboard = longPressMouseShowsKeyboard,
                        autoSwitchLeftAfterRightClick = autoSwitchLeftAfterRightClick,
                        showModFileName = showModFileName,
                        mobileHudEnabled = mobileHudEnabled,
                        lwjglDebugEnabled = lwjglDebugEnabled,
                        gdxPadCursorDebugEnabled = gdxPadCursorDebugEnabled,
                        glBridgeSwapHeartbeatDebugEnabled = glBridgeSwapHeartbeatDebugEnabled,
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

    fun onExportLogsToFile() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(Effect.OpenExportLogsPicker(SettingsFileService.buildJvmLogExportFileName()))
    }

    fun onExportLogs(host: Activity) {
        if (uiState.busy) {
            return
        }
        setBusy(true, "Preparing JVM log bundle...")
        executor.execute {
            try {
                val payload = JvmLogShareService.prepareSharePayload(host)
                host.runOnUiThread {
                    setBusy(false, null)
                    _effects.tryEmit(Effect.ShareJvmLogsBundle(payload))
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    Toast.makeText(host, "Log share failed: ${error.message}", Toast.LENGTH_LONG).show()
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
                val exportedCount = SettingsFileService.exportJvmLogBundle(host, uri)
                host.runOnUiThread {
                    if (exportedCount > 0) {
                        Toast.makeText(host, "Log archive exported ($exportedCount files)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(host, "Log archive exported (no logs yet)", Toast.LENGTH_LONG).show()
                    }
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

    fun onBackBehaviorChanged(host: Activity, behavior: BackBehavior) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(backBehavior = behavior)
        saveBackBehaviorSelection(host, behavior)
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

    fun onLongPressMouseShowsKeyboardChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(longPressMouseShowsKeyboard = enabled)
        saveLongPressMouseShowsKeyboardSelection(host, enabled)
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

    fun onShowModFileNameChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(showModFileName = enabled)
        saveShowModFileNameSelection(host, enabled)
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

    fun onGdxPadCursorDebugChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(gdxPadCursorDebugEnabled = enabled)
        saveGdxPadCursorDebugSelection(host, enabled)
        refreshStatus(host)
    }

    fun onGlBridgeSwapHeartbeatDebugChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(glBridgeSwapHeartbeatDebugEnabled = enabled)
        saveGlBridgeSwapHeartbeatDebugSelection(host, enabled)
        refreshStatus(host)
    }

    fun onMobileHudEnabledChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(mobileHudEnabled = enabled)
        saveMobileHudEnabledSelection(host, enabled)
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
            val batchResult = SettingsFileService.importModJars(host, uris)
            val importedCount = batchResult.importedCount
            val failedCount = batchResult.failedCount
            val blockedCount = batchResult.blockedCount
            val compressedArchiveCount = batchResult.compressedArchiveCount
            val firstError = batchResult.firstError
            val blockedList = batchResult.blockedComponents
            val compressedArchiveList = batchResult.compressedArchives
            val patchedResults = batchResult.patchedResults
            host.runOnUiThread {
                if (blockedList.isNotEmpty()) {
                    AlertDialog.Builder(host)
                        .setTitle("禁止导入内置核心组件")
                        .setMessage(SettingsFileService.buildReservedModImportMessage(blockedList))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                if (compressedArchiveList.isNotEmpty()) {
                    showCompressedArchiveWarningDialog(host, compressedArchiveList)
                }
                if (patchedResults.isNotEmpty()) {
                    showAtlasPatchSummaryDialog(host, patchedResults)
                    showManifestRootPatchSummaryDialog(host, patchedResults)
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

                    compressedArchiveCount > 0 -> {
                        Toast.makeText(host, "检测到压缩包，请先解压后导入 .jar", Toast.LENGTH_SHORT).show()
                    }
                }
                refreshStatus(host)
                onCompleted?.invoke()
//                todo host.notifyMainDataChanged()
            }
        }
    }

    private fun showAtlasPatchSummaryDialog(host: Activity, patchedResults: List<ModImportResult>) {
        if (patchedResults.none { it.wasAtlasPatched }) {
            return
        }
        AlertDialog.Builder(host)
            .setTitle("Atlas 已离线修补")
            .setMessage(SettingsFileService.buildAtlasPatchImportSummaryMessage(patchedResults))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showManifestRootPatchSummaryDialog(host: Activity, patchedResults: List<ModImportResult>) {
        if (patchedResults.none { it.wasManifestRootPatched }) {
            return
        }
        AlertDialog.Builder(host)
            .setTitle("ModID 结构已自动修复")
            .setMessage(SettingsFileService.buildManifestRootPatchImportSummaryMessage(patchedResults))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showCompressedArchiveWarningDialog(host: Activity, archiveDisplayNames: List<String>) {
        AlertDialog.Builder(host)
            .setTitle("检测到压缩包")
            .setMessage(SettingsFileService.buildCompressedArchiveImportMessage(archiveDisplayNames))
            .setPositiveButton(android.R.string.ok, null)
            .show()
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

    private fun resolveCoreDependencyStatus(
        host: Activity,
        label: String,
        importedJar: File,
        bundledAssetPath: String
    ): CoreDependencyStatus {
        if (importedJar.isFile) {
            val importedVersion = resolveJarVersionFromFile(importedJar) ?: "unknown"
            return CoreDependencyStatus(
                label = label,
                available = true,
                source = "imported",
                version = importedVersion
            )
        }

        val bundledVersion = resolveJarVersionFromAsset(host, bundledAssetPath)
        if (bundledVersion != null) {
            return CoreDependencyStatus(
                label = label,
                available = true,
                source = "bundled",
                version = bundledVersion
            )
        }
        if (hasBundledAsset(host, bundledAssetPath)) {
            return CoreDependencyStatus(
                label = label,
                available = true,
                source = "bundled",
                version = "unknown"
            )
        }

        return CoreDependencyStatus(
            label = label,
            available = false,
            source = "missing",
            version = "unknown"
        )
    }

    private fun formatCoreDependencyLine(status: CoreDependencyStatus): String {
        if (!status.available) {
            return "${status.label}: 缺失"
        }
        val sourceLabel = when (status.source.lowercase(Locale.ROOT)) {
            "imported" -> "本地文件"
            "bundled" -> "内置组件"
            else -> status.source
        }
        return "${status.label}: 可用 (来源: $sourceLabel, 版本: ${status.version})"
    }

    private fun collectDeviceRuntimeStatus(host: Activity): DeviceRuntimeStatus {
        val activityManager = host.getSystemService(Activity.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        val availableMemoryBytes: Long
        val totalMemoryBytes: Long
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo)
            availableMemoryBytes = memoryInfo.availMem
            totalMemoryBytes = memoryInfo.totalMem
        } else {
            availableMemoryBytes = 0L
            totalMemoryBytes = 0L
        }

        return DeviceRuntimeStatus(
            cpuModel = resolveCpuModel(),
            cpuArch = resolveCpuArch(),
            availableMemoryBytes = availableMemoryBytes,
            totalMemoryBytes = totalMemoryBytes
        )
    }

    private fun resolveCpuModel(): String {
        val fromProcCpuInfo = readCpuModelFromProcCpuInfo()
        if (!fromProcCpuInfo.isNullOrBlank()) {
            return fromProcCpuInfo
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val socModel = normalizeInfoValue(Build.SOC_MODEL)
            if (socModel != "unknown") {
                return socModel
            }
        }

        val hardware = normalizeInfoValue(Build.HARDWARE)
        if (hardware != "unknown") {
            return hardware
        }

        val model = normalizeInfoValue(Build.MODEL)
        if (model != "unknown") {
            return model
        }
        return "unknown"
    }

    private fun readCpuModelFromProcCpuInfo(): String? {
        val cpuInfoFile = File("/proc/cpuinfo")
        if (!cpuInfoFile.isFile) {
            return null
        }

        var hardware: String? = null
        var modelName: String? = null
        var processor: String? = null
        var cpuModel: String? = null
        return try {
            cpuInfoFile.forEachLine { rawLine ->
                val separator = rawLine.indexOf(':')
                if (separator <= 0) {
                    return@forEachLine
                }
                val key = rawLine.substring(0, separator).trim().lowercase(Locale.ROOT)
                val value = rawLine.substring(separator + 1).trim()
                if (value.isEmpty()) {
                    return@forEachLine
                }
                when (key) {
                    "hardware" -> if (hardware.isNullOrBlank()) hardware = value
                    "model name" -> if (modelName.isNullOrBlank()) modelName = value
                    "processor" -> if (processor.isNullOrBlank()) processor = value
                    "cpu model" -> if (cpuModel.isNullOrBlank()) cpuModel = value
                }
            }
            hardware ?: modelName ?: processor ?: cpuModel
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveCpuArch(): String {
        val supportedAbis = Build.SUPPORTED_ABIS
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val abiText = supportedAbis.joinToString(", ")
        val osArch = normalizeInfoValue(System.getProperty("os.arch"))
        return when {
            abiText.isNotEmpty() && osArch != "unknown" -> "$osArch (ABI: $abiText)"
            abiText.isNotEmpty() -> abiText
            osArch != "unknown" -> osArch
            else -> "unknown"
        }
    }

    private fun normalizeInfoValue(value: String?): String {
        return value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "unknown"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) {
            return "unknown"
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

    private fun resolveJarVersionFromFile(jarFile: File): String? {
        if (!jarFile.isFile) {
            return null
        }
        try {
            val manifest = ModJarSupport.readModManifest(jarFile)
            manifest.version.trim().takeIf { it.isNotEmpty() }?.let { return it }
        } catch (_: Throwable) {
        }

        return try {
            jarFile.inputStream().use { input ->
                resolveJarVersionFromStream(input)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveJarVersionFromAsset(host: Activity, assetPath: String): String? {
        return try {
            host.assets.open(assetPath).use { input ->
                resolveJarVersionFromStream(input)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveJarVersionFromStream(input: InputStream): String? {
        ZipInputStream(BufferedInputStream(input)).use { zipInput ->
            var modManifestVersion: String? = null
            var jarManifestVersion: String? = null
            while (true) {
                val entry = zipInput.nextEntry ?: break
                if (entry.isDirectory) {
                    continue
                }

                val entryName = entry.name.trim()
                if (modManifestVersion == null &&
                    entryName.equals("ModTheSpire.json", ignoreCase = true)
                ) {
                    val jsonBytes = readCurrentZipEntryBytes(zipInput)
                    modManifestVersion = parseModManifestVersionFromJson(String(jsonBytes, Charsets.UTF_8))
                    if (!modManifestVersion.isNullOrBlank()) {
                        return modManifestVersion
                    }
                    continue
                }

                if (jarManifestVersion == null &&
                    entryName.equals("META-INF/MANIFEST.MF", ignoreCase = true)
                ) {
                    val manifestBytes = readCurrentZipEntryBytes(zipInput)
                    jarManifestVersion = parseJarManifestVersionFromBytes(manifestBytes)
                }
            }
            return jarManifestVersion
        }
    }

    private fun readCurrentZipEntryBytes(zipInput: ZipInputStream): ByteArray {
        val buffer = ByteArray(8192)
        val output = ByteArrayOutputStream()
        while (true) {
            val read = zipInput.read(buffer)
            if (read < 0) {
                break
            }
            if (read == 0) {
                continue
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun parseJarManifestVersionFromBytes(manifestBytes: ByteArray): String? {
        return try {
            val attributes = Manifest(manifestBytes.inputStream()).mainAttributes
            val keys = arrayOf(
                "Implementation-Version",
                "Bundle-Version",
                "Specification-Version",
                "Version"
            )
            for (key in keys) {
                val value = attributes.getValue(key)?.trim()
                if (!value.isNullOrEmpty()) {
                    return value
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseModManifestVersionFromJson(jsonText: String): String? {
        val regex = Regex("\"(?:version|Version)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
        val match = regex.find(jsonText) ?: return null
        val rawVersion = match.groupValues.getOrNull(1) ?: return null
        return unescapeJsonText(rawVersion)
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun unescapeJsonText(text: String): String {
        return text
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
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

    private fun readBackBehaviorSelection(host: Activity): BackBehavior {
        return LauncherPreferences.readBackBehavior(host)
    }

    private fun saveBackBehaviorSelection(host: Activity, behavior: BackBehavior) {
        LauncherPreferences.saveBackBehavior(host, behavior)
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

    private fun readLongPressMouseShowsKeyboardSelection(host: Activity): Boolean {
        return LauncherPreferences.readLongPressMouseShowsKeyboard(host)
    }

    private fun saveLongPressMouseShowsKeyboardSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveLongPressMouseShowsKeyboard(host, enabled)
    }

    private fun readAutoSwitchLeftAfterRightClickSelection(host: Activity): Boolean {
        return LauncherPreferences.readAutoSwitchLeftAfterRightClick(host)
    }

    private fun saveAutoSwitchLeftAfterRightClickSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveAutoSwitchLeftAfterRightClick(host, enabled)
    }

    private fun readShowModFileNameSelection(host: Activity): Boolean {
        return LauncherPreferences.readShowModFileName(host)
    }

    private fun saveShowModFileNameSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveShowModFileName(host, enabled)
    }

    private fun readMobileHudEnabledSelection(host: Activity): Boolean {
        return LauncherPreferences.readMobileHudEnabled(host)
    }

    private fun saveMobileHudEnabledSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveMobileHudEnabled(host, enabled)
    }

    private fun readLwjglDebugSelection(host: Activity): Boolean {
        return LauncherPreferences.isLwjglDebugEnabled(host)
    }

    private fun saveLwjglDebugSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setLwjglDebugEnabled(host, enabled)
    }

    private fun readGdxPadCursorDebugSelection(host: Activity): Boolean {
        return LauncherPreferences.isGdxPadCursorDebugEnabled(host)
    }

    private fun saveGdxPadCursorDebugSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setGdxPadCursorDebugEnabled(host, enabled)
    }

    private fun readGlBridgeSwapHeartbeatDebugSelection(host: Activity): Boolean {
        return LauncherPreferences.isGlBridgeSwapHeartbeatDebugEnabled(host)
    }

    private fun saveGlBridgeSwapHeartbeatDebugSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setGlBridgeSwapHeartbeatDebugEnabled(host, enabled)
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
        val latestLog = RuntimePaths.latestLog(host)
        val archivedDir = RuntimePaths.jvmLogsDir(host)
        val logs = JvmLogRotationManager.listLogFiles(host)
        return buildString {
            append("Log slots: ").append(logs.size).append('/').append(JvmLogRotationManager.MAX_LOG_SLOTS)
            append("\nlatest.log: ").append(latestLog.absolutePath)
            append("\narchive dir: ").append(archivedDir.absolutePath)
            if (logs.isEmpty()) {
                append("\n(no logs yet)")
            } else {
                for (log in logs) {
                    append("\n- ").append(log.name)
                    append(" (").append(log.length()).append(" bytes)")
                }
            }
        }
    }

    override fun onCleared() {
        executor.shutdownNow()
        super.onCleared()
    }

    private fun BackBehavior.displayName(): String {
        return when (this) {
            BackBehavior.EXIT_TO_LAUNCHER -> "Exit to launcher"
            BackBehavior.SEND_ESCAPE -> "Send Esc"
            BackBehavior.NONE -> "No action"
        }
    }
}

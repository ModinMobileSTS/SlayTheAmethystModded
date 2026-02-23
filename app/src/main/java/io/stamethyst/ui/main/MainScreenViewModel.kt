package io.stamethyst.ui.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.BackExitNotice
import io.stamethyst.CrashReportStore
import io.stamethyst.LauncherActivity
import io.stamethyst.ModManager
import io.stamethyst.RendererConfig
import io.stamethyst.RuntimePaths
import io.stamethyst.R
import io.stamethyst.StsGameActivity
import io.stamethyst.StsLaunchSpec
import io.stamethyst.model.ModItemUi
import java.io.IOException
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Stable
class MainScreenViewModel : ViewModel() {
    companion object {
        private const val TAG = "MainScreenViewModel"
        private const val PREF_NAME_LAUNCHER = "sts_launcher_prefs"
        private const val PREF_KEY_BACK_IMMEDIATE_EXIT = "back_immediate_exit"
        private const val PREF_KEY_TARGET_FPS = "target_fps"
        private const val PREF_KEY_MANUAL_DISMISS_BOOT_OVERLAY = "manual_dismiss_boot_overlay"
        private const val DEFAULT_TARGET_FPS = 120
        private val TARGET_FPS_OPTIONS = intArrayOf(60, 90, 120, 240)
    }

    data class UiState(
        val busy: Boolean = false,
        val busyMessage: String? = null,
        val statusSummary: String = "",
        val optionalMods: List<ModItemUi> = emptyList(),
        val controlsEnabled: Boolean = true
    )

    private data class DependencyEnableResult(
        val autoEnabledModNames: List<String>,
        val missingDependencies: List<String>
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    var uiState by mutableStateOf(UiState())
        private set

    fun refresh(host: Activity) {
        val hasJar = RuntimePaths.importedStsJar(host).exists()
        val hasMts = RuntimePaths.importedMtsJar(host).exists() || hasBundledAsset(host, "components/mods/ModTheSpire.jar")
        val hasBaseMod = isRequiredModAvailable(host, ModManager.MOD_ID_BASEMOD)
        val hasStsLib = isRequiredModAvailable(host, ModManager.MOD_ID_STSLIB)

        val mods = loadModItems(host)
        val optionalMods = mods.filter { !it.required }
        val optionalTotal = optionalMods.size
        val optionalEnabled = optionalMods.count { it.enabled }

        uiState = uiState.copy(
            busy = false,
            busyMessage = null,
            statusSummary = buildMainStatusSummary(
                hasJar = hasJar,
                hasMts = hasMts,
                hasBaseMod = hasBaseMod,
                hasStsLib = hasStsLib,
                optionalEnabledCount = optionalEnabled,
                optionalTotalCount = optionalTotal
            ),
            optionalMods = optionalMods,
            controlsEnabled = true
        )
    }

    fun onDeleteMod(host: Activity, mod: ModItemUi) {
        onDeleteModRequested(host, mod)
    }

    fun onToggleMod(host: Activity, mod: ModItemUi, enabled: Boolean) {
        onModChecked(host, mod, enabled)
    }

    fun onLaunch(host: Activity) {
        if (uiState.busy) {
            return
        }
        CrashReportStore.clear(host)
        prepareAndLaunch(host, StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD)
    }

    fun handleIncomingIntent(host: Activity, intent: Intent?, onShareCrashReport: () -> Unit) {
        if (intent == null) {
            return
        }

        val expectedBackExit = BackExitNotice.consumeExpectedBackExitIfRecent(host)
        if (expectedBackExit) {
            clearCrashExtras(intent)
            showExpectedBackExitDialog(host)
        }

        val showedCrashDialog = if (expectedBackExit) {
            false
        } else {
            maybeShowCrashDialog(host, intent, onShareCrashReport)
        }
        if (!showedCrashDialog) {
            maybeLaunchFromDebugExtra(host, intent)
        }
    }

    private fun buildMainStatusSummary(
        hasJar: Boolean,
        hasMts: Boolean,
        hasBaseMod: Boolean,
        hasStsLib: Boolean,
        optionalEnabledCount: Int,
        optionalTotalCount: Int
    ): String {
        return StringBuilder()
            .append(if (hasJar) "desktop-1.0.jar: OK" else "desktop-1.0.jar: missing")
            .append('\n')
            .append("ModTheSpire.jar: ")
            .append(if (hasMts) "OK (bundled)" else "missing")
            .append('\n')
            .append("BaseMod.jar: ")
            .append(if (hasBaseMod) "OK (required)" else "missing (required)")
            .append('\n')
            .append("StSLib.jar: ")
            .append(if (hasStsLib) "OK (required, bundled)" else "missing (required)")
            .append('\n')
            .append("Mods enabled: ")
            .append(optionalEnabledCount)
            .append('/')
            .append(optionalTotalCount)
            .toString()
    }

    private fun onModChecked(host: Activity, mod: ModItemUi, enabled: Boolean) {
        if (uiState.busy || mod.required) {
            return
        }
        try {
            if (enabled) {
                val result = enableModWithDependencies(host, mod)
                if (result.autoEnabledModNames.isNotEmpty()) {
                    Toast.makeText(
                        host,
                        "已自动启用前置模组：${result.autoEnabledModNames.joinToString(", ")}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                if (result.missingDependencies.isNotEmpty()) {
                    showMissingDependencyDialog(host, mod, result.missingDependencies)
                }
            } else {
                val dependentModNames = findEnabledDependentModNames(host, mod)
                if (dependentModNames.isNotEmpty()) {
                    Toast.makeText(
                        host,
                        "无法取消「${resolveModDisplayName(mod)}」，请先取消依赖它的模组：${dependentModNames.joinToString(", ")}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    ModManager.setOptionalModEnabled(host, mod.modId, false)
                }
            }
        } catch (error: Throwable) {
            Toast.makeText(
                host,
                "Failed to update mod selection: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
        refresh(host)
    }

    private fun onDeleteModRequested(host: Activity, mod: ModItemUi) {
        if (uiState.busy || mod.required || !mod.installed) {
            return
        }
        val dependentModNames = findEnabledDependentModNames(host, mod)
        if (dependentModNames.isNotEmpty()) {
            Toast.makeText(
                host,
                "无法删除「${resolveModDisplayName(mod)}」，请先取消依赖它的模组：${dependentModNames.joinToString(", ")}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val displayName = resolveModDisplayName(mod)
        AlertDialog.Builder(host)
            .setTitle("删除模组")
            .setMessage("确认删除「$displayName」？\n删除后需要重新导入才能再次使用。")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("删除") { _, _ ->
                setBusy(true, "正在删除模组...")
                executor.execute {
                    try {
                        val deleted = ModManager.deleteOptionalMod(host, mod.modId)
                        host.runOnUiThread {
                            if (deleted) {
                                Toast.makeText(host, "已删除模组：$displayName", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(host, "模组不存在或已被删除：$displayName", Toast.LENGTH_SHORT).show()
                            }
                            refresh(host)
                        }
                    } catch (error: Throwable) {
                        host.runOnUiThread {
                            Toast.makeText(
                                host,
                                "删除模组失败：${error.message}",
                                Toast.LENGTH_LONG
                            ).show()
                            refresh(host)
                        }
                    }
                }
            }
            .show()
    }

    private fun enableModWithDependencies(
        host: Activity,
        rootMod: ModItemUi
    ): DependencyEnableResult {
        val optionalMods = loadModItems(host).filter { !it.required && it.installed }
        val modById = LinkedHashMap<String, ModItemUi>()
        val enabledIds = LinkedHashSet<String>()
        optionalMods.forEach { mod ->
            val normalizedModId = normalizeModId(mod.modId)
            if (normalizedModId.isNotEmpty()) {
                modById[normalizedModId] = mod
                if (mod.enabled) {
                    enabledIds.add(normalizedModId)
                }
            }
            val normalizedManifestId = normalizeModId(mod.manifestModId)
            if (normalizedManifestId.isNotEmpty() && !modById.containsKey(normalizedManifestId)) {
                modById[normalizedManifestId] = mod
            }
            if (mod.enabled && normalizedManifestId.isNotEmpty()) {
                enabledIds.add(normalizedManifestId)
            }
        }

        val queue = ArrayDeque<ModItemUi>()
        val visited = LinkedHashSet<String>()
        val autoEnabledModNames = LinkedHashSet<String>()
        val missingDependencies = LinkedHashSet<String>()
        queue.add(rootMod)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val currentKey = normalizeModId(current.modId).ifBlank {
                normalizeModId(current.manifestModId)
            }
            if (currentKey.isNotEmpty() && !visited.add(currentKey)) {
                continue
            }

            val wasEnabled = isModIdEnabled(enabledIds, current)
            ModManager.setOptionalModEnabled(host, current.modId, true)

            val normalizedCurrentModId = normalizeModId(current.modId)
            if (normalizedCurrentModId.isNotEmpty()) {
                enabledIds.add(normalizedCurrentModId)
            }
            val normalizedCurrentManifestId = normalizeModId(current.manifestModId)
            if (normalizedCurrentManifestId.isNotEmpty()) {
                enabledIds.add(normalizedCurrentManifestId)
            }

            if (!wasEnabled && current.modId != rootMod.modId) {
                autoEnabledModNames.add(resolveModDisplayName(current))
            }

            val dependencies = current.dependencies
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
            for (dependency in dependencies) {
                val normalizedDependency = normalizeModId(dependency)
                if (normalizedDependency.isEmpty() || normalizedDependency == currentKey) {
                    continue
                }
                if (ModManager.isRequiredModId(normalizedDependency)) {
                    if (!isRequiredDependencyAvailable(host, normalizedDependency)) {
                        missingDependencies.add(dependency)
                    }
                    continue
                }
                val dependencyMod = modById[normalizedDependency]
                if (dependencyMod == null || !dependencyMod.installed) {
                    missingDependencies.add(dependency)
                    continue
                }
                queue.add(dependencyMod)
            }
        }

        return DependencyEnableResult(
            autoEnabledModNames = ArrayList(autoEnabledModNames),
            missingDependencies = ArrayList(missingDependencies)
        )
    }

    private fun findEnabledDependentModNames(
        host: Activity,
        targetMod: ModItemUi
    ): List<String> {
        val targetIds = LinkedHashSet<String>()
        val normalizedModId = normalizeModId(targetMod.modId)
        if (normalizedModId.isNotEmpty()) {
            targetIds.add(normalizedModId)
        }
        val normalizedManifestId = normalizeModId(targetMod.manifestModId)
        if (normalizedManifestId.isNotEmpty()) {
            targetIds.add(normalizedManifestId)
        }
        if (targetIds.isEmpty()) {
            return emptyList()
        }

        val dependentNames = LinkedHashSet<String>()
        loadModItems(host).forEach { mod ->
            if (!mod.enabled) {
                return@forEach
            }
            val candidateModId = normalizeModId(mod.modId)
            if (candidateModId.isNotEmpty() && targetIds.contains(candidateModId)) {
                return@forEach
            }
            val candidateManifestId = normalizeModId(mod.manifestModId)
            if (candidateManifestId.isNotEmpty() && targetIds.contains(candidateManifestId)) {
                return@forEach
            }
            val dependsOnTarget = mod.dependencies.any { dependency ->
                val normalizedDependency = normalizeModId(dependency)
                normalizedDependency.isNotEmpty() && targetIds.contains(normalizedDependency)
            }
            if (dependsOnTarget) {
                dependentNames.add(resolveModDisplayName(mod))
            }
        }
        return ArrayList(dependentNames)
    }

    private fun showMissingDependencyDialog(
        host: Activity,
        rootMod: ModItemUi,
        missingDependencies: List<String>
    ) {
        val missing = missingDependencies
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (missing.isEmpty()) {
            return
        }
        val message = buildString {
            append("已启用「")
            append(resolveModDisplayName(rootMod))
            append("」，但缺少以下前置模组：\n")
            missing.forEach { dep ->
                append("- ").append(dep).append('\n')
            }
            append("\n请先导入缺失前置，否则游戏可能无法正常启动。")
        }
        AlertDialog.Builder(host)
            .setTitle("检测到缺失前置模组")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun maybeShowCrashDialog(
        host: Activity,
        intent: Intent,
        onShareCrashReport: () -> Unit
    ): Boolean {
        if (!intent.getBooleanExtra(LauncherActivity.EXTRA_CRASH_OCCURRED, false)) {
            return false
        }

        val code = intent.getIntExtra(LauncherActivity.EXTRA_CRASH_CODE, -1)
        val isSignal = intent.getBooleanExtra(LauncherActivity.EXTRA_CRASH_IS_SIGNAL, false)
        val detail = intent.getStringExtra(LauncherActivity.EXTRA_CRASH_DETAIL)
        val message = if (!detail.isNullOrBlank()) {
            host.getString(R.string.sts_crash_detail_format, detail.trim())
        } else {
            val messageId = if (isSignal) R.string.sts_signal_exit else R.string.sts_normal_exit
            host.getString(messageId, code)
        }

        CrashReportStore.recordLaunchResult(host, "launcher_intent_crash", code, isSignal, detail)
        clearCrashExtras(intent)

        AlertDialog.Builder(host)
            .setTitle(R.string.sts_crash_dialog_title)
            .setMessage(message)
            .setNeutralButton(R.string.sts_share_crash_report) { _, _ -> onShareCrashReport() }
            .setPositiveButton(android.R.string.ok, null)
            .show()
        return true
    }

    private fun clearCrashExtras(intent: Intent) {
        intent.removeExtra(LauncherActivity.EXTRA_CRASH_OCCURRED)
        intent.removeExtra(LauncherActivity.EXTRA_CRASH_CODE)
        intent.removeExtra(LauncherActivity.EXTRA_CRASH_IS_SIGNAL)
        intent.removeExtra(LauncherActivity.EXTRA_CRASH_DETAIL)
    }

    private fun showExpectedBackExitDialog(host: Activity) {
        AlertDialog.Builder(host)
            .setTitle("已返回启动器")
            .setMessage(
                "这是预期内的结果：你触发了「Back 键：立即退出到主界面」。\n\n" +
                    "如不需要该行为，可在设置中关闭此开关。"
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun maybeLaunchFromDebugExtra(host: Activity, intent: Intent) {
        val debugLaunchMode = intent.getStringExtra(LauncherActivity.EXTRA_DEBUG_LAUNCH_MODE)
        Log.i(TAG, "Debug launch extra: $debugLaunchMode")
        if (debugLaunchMode != StsLaunchSpec.LAUNCH_MODE_VANILLA
            && debugLaunchMode != StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD
        ) {
            return
        }

        Log.i(TAG, "Auto launching mode from debug extra: $debugLaunchMode")
        CrashReportStore.clear(host)
        prepareAndLaunch(host, debugLaunchMode)
    }

    private fun prepareAndLaunch(host: Activity, launchMode: String) {
        val renderer = RendererConfig.readPreferredBackend(host)
        val targetFps = normalizeTargetFps(readTargetFpsSelection(host))
        val backImmediateExit = readBackBehaviorSelection(host)
        val manualDismissBootOverlay = readManualDismissBootOverlaySelection(host)

        val intent = Intent(host, StsGameActivity::class.java)
        intent.putExtra(StsGameActivity.EXTRA_LAUNCH_MODE, launchMode)
        intent.putExtra(StsGameActivity.EXTRA_RENDERER_BACKEND, renderer.rendererId())
        intent.putExtra(StsGameActivity.EXTRA_TARGET_FPS, targetFps)
        intent.putExtra(StsGameActivity.EXTRA_PRELAUNCH_PREPARED, false)
        intent.putExtra(
            StsGameActivity.EXTRA_WAIT_FOR_MAIN_MENU,
            StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD == launchMode
        )
        intent.putExtra(StsGameActivity.EXTRA_BACK_IMMEDIATE_EXIT, backImmediateExit)
        intent.putExtra(StsGameActivity.EXTRA_MANUAL_DISMISS_BOOT_OVERLAY, manualDismissBootOverlay)
        Log.i(
            TAG,
            "Start StsGameActivity directly, mode=$launchMode, renderer=${renderer.rendererId()}, targetFps=$targetFps, backImmediateExit=$backImmediateExit, manualDismissBootOverlay=$manualDismissBootOverlay"
        )
        host.startActivity(intent)
    }

    private fun loadModItems(host: Activity): List<ModItemUi> {
        return ModManager.listInstalledMods(host).map { mod ->
            ModItemUi(
                modId = mod.modId,
                manifestModId = mod.manifestModId,
                name = mod.name,
                version = mod.version,
                description = mod.description,
                dependencies = mod.dependencies,
                required = mod.required,
                installed = mod.installed,
                enabled = mod.enabled
            )
        }
    }

    private fun normalizeModId(modId: String?): String {
        return ModManager.normalizeModId(modId)
    }

    private fun isModIdEnabled(enabledIds: Set<String>, mod: ModItemUi): Boolean {
        val normalizedModId = normalizeModId(mod.modId)
        if (normalizedModId.isNotEmpty() && enabledIds.contains(normalizedModId)) {
            return true
        }
        val normalizedManifestId = normalizeModId(mod.manifestModId)
        return normalizedManifestId.isNotEmpty() && enabledIds.contains(normalizedManifestId)
    }

    private fun isRequiredDependencyAvailable(host: Activity, normalizedDependency: String): Boolean {
        return when (normalizedDependency) {
            ModManager.MOD_ID_BASEMOD -> isRequiredModAvailable(host, ModManager.MOD_ID_BASEMOD)
            ModManager.MOD_ID_STSLIB -> isRequiredModAvailable(host, ModManager.MOD_ID_STSLIB)
            else -> true
        }
    }

    private fun isRequiredModAvailable(host: Activity, modId: String): Boolean {
        return when (modId) {
            ModManager.MOD_ID_BASEMOD -> RuntimePaths.importedBaseModJar(host).exists() || hasBundledAsset(host, "components/mods/BaseMod.jar")
            ModManager.MOD_ID_STSLIB -> RuntimePaths.importedStsLibJar(host).exists() || hasBundledAsset(host, "components/mods/StSLib.jar")
            else -> true
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

    private fun resolveModDisplayName(mod: ModItemUi): String {
        return mod.name.ifBlank {
            mod.manifestModId.ifBlank { mod.modId }
        }
    }

    private fun readBackBehaviorSelection(host: Activity): Boolean {
        return host.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY_BACK_IMMEDIATE_EXIT, true)
    }

    private fun readManualDismissBootOverlaySelection(host: Activity): Boolean {
        return host.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY_MANUAL_DISMISS_BOOT_OVERLAY, false)
    }

    private fun readTargetFpsSelection(host: Activity): Int {
        val stored = host.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE)
            .getInt(PREF_KEY_TARGET_FPS, DEFAULT_TARGET_FPS)
        return normalizeTargetFps(stored)
    }

    private fun normalizeTargetFps(targetFps: Int): Int {
        return if (TARGET_FPS_OPTIONS.contains(targetFps)) {
            targetFps
        } else {
            DEFAULT_TARGET_FPS
        }
    }

    private fun setBusy(busy: Boolean, message: String?) {
        uiState = if (busy) {
            uiState.copy(
                busy = true,
                busyMessage = message,
                controlsEnabled = false
            )
        } else {
            uiState.copy(
                busy = false,
                busyMessage = null,
                controlsEnabled = true
            )
        }
    }

    override fun onCleared() {
        executor.shutdownNow()
        super.onCleared()
    }
}

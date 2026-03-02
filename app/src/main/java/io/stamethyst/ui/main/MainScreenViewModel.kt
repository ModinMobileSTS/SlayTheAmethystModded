package io.stamethyst.ui.main

import android.app.Activity
import android.app.ApplicationExitInfo
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.backend.launch.BackExitNotice
import io.stamethyst.LauncherActivity
import io.stamethyst.StsGameActivity
import io.stamethyst.backend.crash.CrashDiagnostics
import io.stamethyst.backend.crash.ProcessExitSummary
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.core.RuntimePaths
import io.stamethyst.R
import io.stamethyst.backend.launch.StsLaunchSpec
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.settings.SettingsFileService
import java.io.IOException
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Stable
class MainScreenViewModel : ViewModel() {
    companion object {
        private const val TAG = "MainScreenViewModel"
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
    private val optionalModsSnapshot = ArrayList<ModItemUi>()
    private val pendingEnabledOptionalModIds = LinkedHashSet<String>()
    private var pendingSelectionInitialized = false

    var uiState by mutableStateOf(UiState())
        private set

    fun refresh(host: Activity) {
        val hasJar = RuntimePaths.importedStsJar(host).exists()
        val hasMts = RuntimePaths.importedMtsJar(host).exists() || hasBundledAsset(host, "components/mods/ModTheSpire.jar")
        val hasBaseMod = isRequiredModAvailable(host, ModManager.MOD_ID_BASEMOD)
        val hasStsLib = isRequiredModAvailable(host, ModManager.MOD_ID_STSLIB)

        val mods = loadModItems(host)
        val optionalMods = mods.filter { !it.required }

        if (!pendingSelectionInitialized) {
            initializePendingSelection(optionalMods)
            pendingSelectionInitialized = true
        }
        optionalModsSnapshot.clear()
        optionalModsSnapshot.addAll(optionalMods.map { it.copy(enabled = false) })
        prunePendingSelectionToInstalled()
        publishUiState(
            hasJar = hasJar,
            hasMts = hasMts,
            hasBaseMod = hasBaseMod,
            hasStsLib = hasStsLib
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
        CrashDiagnostics.clear(host)
        prepareAndLaunch(host, StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD, forceJvmCrash = false)
    }

    fun onModJarsPicked(host: Activity, uris: List<Uri>?) {
        if (uiState.busy || uris.isNullOrEmpty()) {
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
            val firstError = errors.firstOrNull()
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
                refresh(host)
            }
        }
    }

    fun handleIncomingIntent(host: Activity, intent: Intent?, onShareCrashReport: () -> Unit) {
        val exitSummary = CrashDiagnostics.captureLatestProcessExitInfo(host, "launcher_intent_entry")
        val hasCrashExtra = intent?.getBooleanExtra(LauncherActivity.EXTRA_CRASH_OCCURRED, false) == true
        if (exitSummary != null || hasCrashExtra) {
            CrashDiagnostics.scheduleSnapshotCapture(host, exitSummary)
        }
        if (intent == null) {
            maybeShowProcessExitDialog(host, exitSummary, onShareCrashReport)
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
        val showedProcessExitDialog = if (showedCrashDialog) {
            false
        } else {
            maybeShowProcessExitDialog(host, exitSummary, onShareCrashReport)
        }
        if (!showedCrashDialog && !showedProcessExitDialog) {
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
            .append(if (hasMts) "OK" else "missing")
            .append('\n')
            .append("BaseMod.jar: ")
            .append(if (hasBaseMod) "OK" else "missing (required)")
            .append('\n')
            .append("StSLib.jar: ")
            .append(if (hasStsLib) "OK" else "missing (required)")
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
                val result = enableModWithDependencies(
                    host = host,
                    rootMod = mod,
                    optionalMods = resolveOptionalModsWithPendingSelection()
                )
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
                val dependentModNames = findEnabledDependentModNames(
                    targetMod = mod,
                    optionalMods = resolveOptionalModsWithPendingSelection()
                )
                if (dependentModNames.isNotEmpty()) {
                    Toast.makeText(
                        host,
                        "无法取消「${resolveModDisplayName(mod)}」，请先取消依赖它的模组：${dependentModNames.joinToString(", ")}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    setPendingOptionalModEnabled(mod, false)
                }
            }
        } catch (error: Throwable) {
            Toast.makeText(
                host,
                "Failed to update mod selection: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
        publishUiState(
            hasJar = RuntimePaths.importedStsJar(host).exists(),
            hasMts = RuntimePaths.importedMtsJar(host).exists() || hasBundledAsset(host, "components/mods/ModTheSpire.jar"),
            hasBaseMod = isRequiredModAvailable(host, ModManager.MOD_ID_BASEMOD),
            hasStsLib = isRequiredModAvailable(host, ModManager.MOD_ID_STSLIB)
        )
    }

    private fun onDeleteModRequested(host: Activity, mod: ModItemUi) {
        if (uiState.busy || mod.required || !mod.installed) {
            return
        }
        val dependentModNames = findEnabledDependentModNames(
            targetMod = mod,
            optionalMods = resolveOptionalModsWithPendingSelection()
        )
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
                            clearPendingSelectionForMod(mod)
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
        rootMod: ModItemUi,
        optionalMods: List<ModItemUi>
    ): DependencyEnableResult {
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
            setPendingOptionalModEnabled(current, true)

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
        targetMod: ModItemUi,
        optionalMods: List<ModItemUi>
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
        optionalMods.forEach { mod ->
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
        val message = if (isOutOfMemoryCrash(code, detail)) {
            host.getString(R.string.sts_oom_exit)
        } else if (!detail.isNullOrBlank()) {
            host.getString(R.string.sts_crash_detail_format, detail.trim())
        } else {
            val messageId = if (isSignal) R.string.sts_signal_exit else R.string.sts_normal_exit
            host.getString(messageId, code)
        }

        CrashDiagnostics.recordLaunchResult(host, "launcher_intent_crash", code, isSignal, detail)
        clearCrashExtras(intent)

        AlertDialog.Builder(host)
            .setTitle(R.string.sts_crash_dialog_title)
            .setMessage(message)
            .setNeutralButton(R.string.sts_share_crash_report) { _, _ -> onShareCrashReport() }
            .setPositiveButton(android.R.string.ok, null)
            .show()
        return true
    }

    private fun maybeShowProcessExitDialog(
        host: Activity,
        summary: ProcessExitSummary?,
        onShareCrashReport: () -> Unit
    ): Boolean {
        if (summary == null) {
            return false
        }
        val isSignal = summary.reason == ApplicationExitInfo.REASON_SIGNALED
        val detail = buildString {
            append("reason=").append(summary.reasonName)
            append(", status=").append(summary.status)
            append(", pid=").append(summary.pid)
            append(", timestamp=").append(summary.timestamp)
            if (summary.description.isNotBlank()) {
                append(", description=").append(summary.description)
            }
        }
        CrashDiagnostics.recordLaunchResult(
            host,
            "launcher_exit_info_crash",
            summary.status,
            isSignal,
            detail
        )
        val message = buildString {
            append("检测到上次运行异常退出。\n")
            if (isSignal) {
                append(host.getString(R.string.sts_signal_exit, summary.status))
            } else {
                append("退出原因：").append(summary.reasonName).append(" (status=").append(summary.status).append(')')
            }
            if (summary.description.isNotBlank()) {
                append("\n").append(summary.description)
            }
        }
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

    private fun isOutOfMemoryCrash(code: Int, detail: String?): Boolean {
        if (code == -8) {
            return true
        }
        if (detail.isNullOrBlank()) {
            return false
        }
        val lower = detail.lowercase(Locale.ROOT)
        return lower.contains("outofmemoryerror") ||
            lower.contains("java heap space") ||
            lower.contains("gc overhead limit exceeded")
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
        val forceJvmCrash = intent.getBooleanExtra(LauncherActivity.EXTRA_DEBUG_FORCE_JVM_CRASH, false)
        Log.i(TAG, "Debug launch extra: $debugLaunchMode")
        if (debugLaunchMode != StsLaunchSpec.LAUNCH_MODE_VANILLA
            && debugLaunchMode != StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD
        ) {
            return
        }

        Log.i(TAG, "Auto launching mode from debug extra: $debugLaunchMode, forceJvmCrash=$forceJvmCrash")
        CrashDiagnostics.clear(host)
        prepareAndLaunch(host, debugLaunchMode, forceJvmCrash = forceJvmCrash)
    }

    private fun prepareAndLaunch(host: Activity, launchMode: String, forceJvmCrash: Boolean) {
        ensurePendingSelectionInitialized(host)
        try {
            ModManager.replaceEnabledOptionalModIds(host, pendingEnabledOptionalModIds)
        } catch (error: Throwable) {
            Toast.makeText(
                host,
                "Failed to apply mod selection: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val targetFps = LauncherPreferences.readTargetFps(host)
        val backImmediateExit = readBackBehaviorSelection(host)
        val manualDismissBootOverlay = readManualDismissBootOverlaySelection(host)

        Log.i(
            TAG,
            "Start StsGameActivity directly, mode=$launchMode, renderer=opengles2, targetFps=$targetFps, backImmediateExit=$backImmediateExit, manualDismissBootOverlay=$manualDismissBootOverlay, forceJvmCrash=$forceJvmCrash"
        )
        StsGameActivity.launch(
            host,
            launchMode,
            targetFps,
            backImmediateExit,
            manualDismissBootOverlay,
            forceJvmCrash
        )
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
        return LauncherPreferences.readBackImmediateExit(host)
    }

    private fun readManualDismissBootOverlaySelection(host: Activity): Boolean {
        return LauncherPreferences.readManualDismissBootOverlay(host)
    }

    private fun initializePendingSelection(optionalMods: List<ModItemUi>) {
        pendingEnabledOptionalModIds.clear()
        optionalMods.forEach { mod ->
            if (!mod.enabled) {
                return@forEach
            }
            resolveStoredOptionalModId(mod)?.let { pendingEnabledOptionalModIds.add(it) }
        }
    }

    private fun ensurePendingSelectionInitialized(host: Activity) {
        if (pendingSelectionInitialized) {
            return
        }
        val optionalMods = loadModItems(host).filter { !it.required }
        initializePendingSelection(optionalMods)
        optionalModsSnapshot.clear()
        optionalModsSnapshot.addAll(optionalMods.map { it.copy(enabled = false) })
        prunePendingSelectionToInstalled()
        pendingSelectionInitialized = true
    }

    private fun resolveOptionalModsWithPendingSelection(): List<ModItemUi> {
        return optionalModsSnapshot.map { mod ->
            mod.copy(enabled = isPendingOptionalModEnabled(mod))
        }
    }

    private fun isPendingOptionalModEnabled(mod: ModItemUi): Boolean {
        val storedId = resolveStoredOptionalModId(mod)
        if (storedId != null && pendingEnabledOptionalModIds.contains(storedId)) {
            return true
        }
        val normalizedManifestId = normalizeModId(mod.manifestModId)
        return normalizedManifestId.isNotEmpty() && pendingEnabledOptionalModIds.contains(normalizedManifestId)
    }

    private fun setPendingOptionalModEnabled(mod: ModItemUi, enabled: Boolean) {
        val storedId = resolveStoredOptionalModId(mod)
        if (storedId != null) {
            if (enabled) {
                pendingEnabledOptionalModIds.add(storedId)
            } else {
                pendingEnabledOptionalModIds.remove(storedId)
            }
        }
        val normalizedManifestId = normalizeModId(mod.manifestModId)
        if (normalizedManifestId.isNotEmpty() && !enabled) {
            pendingEnabledOptionalModIds.remove(normalizedManifestId)
        }
    }

    private fun clearPendingSelectionForMod(mod: ModItemUi) {
        setPendingOptionalModEnabled(mod, false)
    }

    private fun resolveStoredOptionalModId(mod: ModItemUi): String? {
        val normalizedModId = normalizeModId(mod.modId)
        if (normalizedModId.isNotEmpty()) {
            return normalizedModId
        }
        val normalizedManifestId = normalizeModId(mod.manifestModId)
        return normalizedManifestId.ifEmpty { null }
    }

    private fun prunePendingSelectionToInstalled() {
        if (pendingEnabledOptionalModIds.isEmpty()) {
            return
        }
        val installedIds = LinkedHashSet<String>()
        optionalModsSnapshot.forEach { mod ->
            val storedId = resolveStoredOptionalModId(mod)
            if (storedId != null) {
                installedIds.add(storedId)
            }
            val normalizedManifestId = normalizeModId(mod.manifestModId)
            if (normalizedManifestId.isNotEmpty()) {
                installedIds.add(normalizedManifestId)
            }
        }
        pendingEnabledOptionalModIds.retainAll(installedIds)
    }

    private fun publishUiState(
        hasJar: Boolean,
        hasMts: Boolean,
        hasBaseMod: Boolean,
        hasStsLib: Boolean
    ) {
        val optionalMods = resolveOptionalModsWithPendingSelection()
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

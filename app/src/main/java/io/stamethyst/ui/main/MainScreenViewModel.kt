package io.stamethyst.ui.main

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.backend.launch.BackExitNotice
import io.stamethyst.LauncherActivity
import io.stamethyst.StsGameActivity
import io.stamethyst.backend.file_interactive.SafExportActivity
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.MtsLaunchManifestValidator
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.RuntimePaths
import io.stamethyst.R
import io.stamethyst.backend.launch.StsLaunchSpec
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.UiBusyOperation
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.settings.JvmLogShareService
import io.stamethyst.ui.settings.ModImportResult
import io.stamethyst.ui.settings.SettingsFileService
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Stable
class MainScreenViewModel : ViewModel() {
    data class ModFolder(
        val id: String,
        val name: String
    )

    data class FolderCollapseSnapshot(
        val folderCollapsed: Map<String, Boolean>,
        val unassignedCollapsed: Boolean
    )

    data class UiState(
        val initializing: Boolean = true,
        val busy: Boolean = false,
        val busyOperation: UiBusyOperation = UiBusyOperation.NONE,
        val busyMessage: String? = null,
        val statusSummary: String = "",
        val optionalMods: List<ModItemUi> = emptyList(),
        val controlsEnabled: Boolean = true,
        val showModFileName: Boolean = LauncherPreferences.DEFAULT_SHOW_MOD_FILE_NAME,
        val modFolders: List<ModFolder> = emptyList(),
        val folderAssignments: Map<String, String> = emptyMap(),
        val folderCollapsed: Map<String, Boolean> = emptyMap(),
        val unassignedCollapsed: Boolean = false,
        val statusSummaryCollapsed: Boolean = false,
        val unassignedFolderName: String = DEFAULT_UNASSIGNED_FOLDER_NAME,
        val unassignedFolderOrder: Int = 0
    )

    private data class DependencyEnableResult(
        val autoEnabledModNames: List<String>,
        val missingDependencies: List<String>
    )

    private data class MtsLaunchValidationIssue(
        val mod: ModItemUi,
        val reason: String
    )

    private data class MtsModsDirConflictIssue(
        val launchModId: String,
        val jarFiles: List<File>
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val optionalModsSnapshot = ArrayList<ModItemUi>()
    private var optionalModsWithPendingSelectionCache: List<ModItemUi> = emptyList()
    private var optionalModsWithPendingSelectionDirty = true
    private val pendingEnabledOptionalModIds = LinkedHashSet<String>()
    private var pendingSelectionInitialized = false
    private val folderStateStore = MainFolderStateStore()
    private val modFolders: MutableList<ModFolder>
        get() = folderStateStore.folders
    private val folderAssignments: MutableMap<String, String>
        get() = folderStateStore.assignments
    private val folderCollapsed: MutableMap<String, Boolean>
        get() = folderStateStore.collapsedMap
    private var unassignedCollapsed: Boolean
        get() = folderStateStore.unassignedIsCollapsed
        set(value) {
            folderStateStore.unassignedIsCollapsed = value
        }
    private var statusSummaryCollapsed: Boolean
        get() = folderStateStore.statusSummaryIsCollapsed
        set(value) {
            folderStateStore.statusSummaryIsCollapsed = value
        }
    private var unassignedFolderName: String
        get() = folderStateStore.unassignedName
        set(value) {
            folderStateStore.unassignedName = value
        }
    private var unassignedFolderOrder: Int
        get() = folderStateStore.unassignedOrder
        set(value) {
            folderStateStore.unassignedOrder = value
        }

    var uiState by mutableStateOf(UiState())
        private set

    private fun canEditMainScreenState(): Boolean {
        return resolveControlsEnabled(uiState.busy, uiState.busyOperation)
    }

    fun refresh(host: Activity) {
        val hasJar = RuntimePaths.importedStsJar(host).exists()
        val hasMts = RuntimePaths.importedMtsJar(host).exists() || hasBundledAsset(host, "components/mods/ModTheSpire.jar")
        val hasBaseMod = isRequiredModAvailable(host, ModManager.MOD_ID_BASEMOD)
        val hasStsLib = isRequiredModAvailable(host, ModManager.MOD_ID_STSLIB)

        val mods = loadModItems(host)
        val optionalMods = mods.filter { !it.required }

        ensureFolderStateLoaded(host)
        if (!pendingSelectionInitialized) {
            initializePendingSelection(optionalMods)
            pendingSelectionInitialized = true
        }
        optionalModsSnapshot.clear()
        optionalModsSnapshot.addAll(optionalMods.map { it.copy(enabled = false) })
        markOptionalModsWithPendingSelectionDirty()
        prunePendingSelectionToInstalled()
        sanitizeFolderAssignments(optionalMods)
        persistFolderState(host)
        publishUiState(
            host = host,
            hasJar = hasJar,
            hasMts = hasMts,
            hasBaseMod = hasBaseMod,
            hasStsLib = hasStsLib
        )
    }

    fun onDeleteMod(host: Activity, mod: ModItemUi) {
        onDeleteModRequested(host, mod)
    }

    fun onExportMod(host: Activity, mod: ModItemUi) {
        if (uiState.busy || !mod.installed) {
            return
        }
        val sourceFile = resolveExportableModFile(mod)
        if (sourceFile == null) {
            Toast.makeText(host, "模组文件不存在，无法导出", Toast.LENGTH_LONG).show()
            return
        }
        val exportIntent = Intent(host, SafExportActivity::class.java).apply {
            putExtra(SafExportActivity.EXTRA_SOURCE_PATH, sourceFile.absolutePath)
            putExtra(SafExportActivity.EXTRA_SUGGESTED_NAME, sourceFile.name)
            putExtra(SafExportActivity.EXTRA_MIME_TYPE, "application/java-archive")
        }
        host.startActivity(exportIntent)
    }

    fun onShareMod(host: Activity, mod: ModItemUi) {
        if (uiState.busy || !mod.installed) {
            return
        }
        val sourceFile = resolveExportableModFile(mod)
        if (sourceFile == null) {
            Toast.makeText(host, "模组文件不存在，无法分享", Toast.LENGTH_LONG).show()
            return
        }
        setBusy(true, "正在准备分享模组...")
        executor.execute {
            try {
                val shareFile = prepareModShareFile(host, sourceFile)
                val shareUri = FileProvider.getUriForFile(
                    host,
                    "${host.packageName}.fileprovider",
                    shareFile
                )
                host.runOnUiThread {
                    setBusy(false, null)
                    try {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/java-archive"
                            putExtra(Intent.EXTRA_STREAM, shareUri)
                            putExtra(Intent.EXTRA_SUBJECT, sourceFile.name)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooserIntent = Intent.createChooser(
                            shareIntent,
                            host.getString(R.string.main_mod_share_chooser_title)
                        )
                        host.startActivity(chooserIntent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(host, "没有可用应用可分享该模组。", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    Toast.makeText(host, "分享模组失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun onRenameModFile(host: Activity, mod: ModItemUi, newFileNameInput: String) {
        if (uiState.busy || !mod.installed) {
            return
        }
        val sourceFile = resolveExportableModFile(mod)
        if (sourceFile == null) {
            Toast.makeText(host, "模组文件不存在，无法重命名", Toast.LENGTH_LONG).show()
            return
        }
        val normalizedFileName = normalizeModJarFileName(newFileNameInput)
        if (normalizedFileName == null) {
            Toast.makeText(host, "文件名不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (normalizedFileName.contains('/') || normalizedFileName.contains('\\')) {
            Toast.makeText(host, "文件名不能包含路径分隔符", Toast.LENGTH_SHORT).show()
            return
        }

        val targetFile = File(sourceFile.parentFile, normalizedFileName)
        if (targetFile.absolutePath == sourceFile.absolutePath) {
            return
        }
        if (targetFile.exists()) {
            Toast.makeText(host, "目标文件已存在：${targetFile.name}", Toast.LENGTH_LONG).show()
            return
        }

        val assignedFolderId = resolveAssignedFolderId(mod)
        val shouldRestorePriorityRoot = mod.priorityRoot
        setBusy(true, "正在重命名模组文件...")
        executor.execute {
            try {
                moveFileReplacing(sourceFile, targetFile)
                if (shouldRestorePriorityRoot) {
                    ModManager.setOptionalModPriorityRoot(host, targetFile.absolutePath, true)
                }
                host.runOnUiThread {
                    setBusy(false, null)
                    clearAssignmentForMod(mod)
                    if (!assignedFolderId.isNullOrBlank()) {
                        folderAssignments[targetFile.absolutePath] = assignedFolderId
                        persistFolderState(host)
                    }
                    Toast.makeText(host, "已重命名为：${targetFile.name}", Toast.LENGTH_SHORT).show()
                    refresh(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    Toast.makeText(host, "重命名失败：${error.message}", Toast.LENGTH_LONG).show()
                    refresh(host)
                }
            }
        }
    }

    fun onToggleMod(host: Activity, mod: ModItemUi, enabled: Boolean) {
        onModChecked(host, mod, enabled)
    }

    fun onTogglePriorityLoad(host: Activity, mod: ModItemUi, enabled: Boolean) {
        if (!canEditMainScreenState() || mod.required || !mod.installed) {
            return
        }
        if (!enabled && mod.priorityLoad && !mod.priorityRoot) {
            Toast.makeText(
                host,
                "无法取消「${resolveModDisplayName(mod)}」的优先加载，它是其他优先模组的前置依赖。",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val targetKey = resolveStoredOptionalModId(mod)
        if (targetKey.isNullOrBlank()) {
            Toast.makeText(host, "无法识别模组标识，优先加载设置失败。", Toast.LENGTH_LONG).show()
            return
        }

        val beforePriorityState = resolveOptionalModsWithPendingSelection().associate { optionalMod ->
            optionalMod.storagePath to optionalMod.priorityLoad
        }
        try {
            ModManager.setOptionalModPriorityRoot(host, targetKey, enabled)
            refresh(host)
            val updated = findOptionalModByAnyId(mod.storagePath) ?: findOptionalModByAnyId(targetKey)
            if (enabled) {
                val autoMarkedNames = resolveOptionalModsWithPendingSelection()
                    .filter { optionalMod ->
                        optionalMod.priorityLoad &&
                            !beforePriorityState.getOrDefault(optionalMod.storagePath, false) &&
                            optionalMod.storagePath != mod.storagePath
                    }
                    .map { resolveModDisplayName(it) }
                    .distinct()
                val message = when {
                    autoMarkedNames.isNotEmpty() ->
                        "已标记「${resolveModDisplayName(mod)}」为优先加载，并同步标记前置：${autoMarkedNames.joinToString(", ")}"

                    else -> "已标记「${resolveModDisplayName(mod)}」为优先加载"
                }
                Toast.makeText(host, message, Toast.LENGTH_LONG).show()
            } else {
                val message = if (updated?.priorityLoad == true) {
                    "已取消「${resolveModDisplayName(mod)}」的显式优先加载，但它仍因其他优先模组的前置关系保持优先。"
                } else {
                    "已取消「${resolveModDisplayName(mod)}」的优先加载"
                }
                Toast.makeText(host, message, Toast.LENGTH_LONG).show()
            }
        } catch (error: Throwable) {
            Toast.makeText(
                host,
                "Failed to update priority load state: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun onLaunch(host: Activity) {
        if (uiState.busy) {
            return
        }
        prepareAndLaunch(host, StsLaunchSpec.LAUNCH_MODE_MTS, forceJvmCrash = false)
    }

    fun suggestNextFolderName(): String {
        return buildNextFolderName()
    }

    fun addFolder(host: Activity, name: String) {
        if (!canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        val input = name.trim()
        if (input.isEmpty()) {
            Toast.makeText(host, "文件夹名称不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        val uniqueName = ensureUniqueFolderName(input)
        modFolders.add(ModFolder(id = UUID.randomUUID().toString(), name = uniqueName))
        folderCollapsed[modFolders.last().id] = false
        persistFolderState(host)
        republish(host)
    }

    fun renameFolder(host: Activity, folderId: String, newName: String) {
        if (!canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        val input = newName.trim()
        if (input.isEmpty()) {
            Toast.makeText(host, "文件夹名称不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (folderId == UNASSIGNED_FOLDER_ID) {
            if (input == unassignedFolderName) {
                return
            }
            unassignedFolderName = input
            persistFolderState(host)
            republish(host)
            return
        }
        val folderIndex = modFolders.indexOfFirst { it.id == folderId }
        if (folderIndex < 0) {
            return
        }
        val uniqueName = ensureUniqueFolderName(input, excludeFolderId = folderId)
        if (uniqueName == modFolders[folderIndex].name) {
            return
        }
        modFolders[folderIndex] = modFolders[folderIndex].copy(name = uniqueName)
        persistFolderState(host)
        republish(host)
    }

    fun deleteFolder(host: Activity, folderId: String) {
        if (!canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        val folder = modFolders.firstOrNull { it.id == folderId } ?: return
        modFolders.removeAll { it.id == folderId }
        folderAssignments.entries.removeIf { it.value == folderId }
        folderCollapsed.remove(folderId)
        unassignedFolderOrder = unassignedFolderOrder.coerceIn(0, modFolders.size)
        persistFolderState(host)
        Toast.makeText(host, "已删除 ${folder.name}", Toast.LENGTH_SHORT).show()
        republish(host)
    }

    fun assignModToFolder(host: Activity, modId: String, folderId: String) {
        val target = findOptionalModByAnyId(modId) ?: return
        assignModToFolder(host, target, folderId)
    }

    fun assignModToFolder(host: Activity, mod: ModItemUi, folderId: String) {
        if (!canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        if (modFolders.none { it.id == folderId }) {
            return
        }
        val key = resolveModAssignmentKey(mod) ?: return
        val currentFolderId = resolveAssignedFolderId(mod)
        if (currentFolderId == folderId) {
            return
        }
        clearAssignmentForMod(mod)
        folderAssignments[key] = folderId
        persistFolderState(host)
        republish(host)
    }

    fun moveModToUnassigned(host: Activity, modId: String) {
        val target = findOptionalModByAnyId(modId) ?: return
        moveModToUnassigned(host, target)
    }

    fun moveModToUnassigned(host: Activity, mod: ModItemUi) {
        if (!canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        if (resolveAssignedFolderId(mod) == null) {
            return
        }
        clearAssignmentForMod(mod)
        persistFolderState(host)
        republish(host)
    }

    fun setFolderSelected(host: Activity, folderId: String, selected: Boolean) {
        if (!canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        val targetMods = resolveOptionalModsWithPendingSelection().filter {
            resolveAssignedFolderId(it) == folderId
        }
        if (targetMods.isEmpty()) {
            Toast.makeText(host, "文件夹为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (selected) {
            batchEnableMods(host, targetMods)
        } else {
            batchDisableMods(host, targetMods)
        }
        republish(host)
    }

    fun setUnassignedSelected(host: Activity, selected: Boolean) {
        if (!canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        val targetMods = resolveOptionalModsWithPendingSelection().filter {
            resolveAssignedFolderId(it) == null
        }
        if (targetMods.isEmpty()) {
            Toast.makeText(host, "未分类文件夹为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (selected) {
            batchEnableMods(host, targetMods)
        } else {
            batchDisableMods(host, targetMods)
        }
        republish(host)
    }

    fun toggleFolderCollapsed(host: Activity, folderId: String) {
        if (!canEditMainScreenState()) {
            return
        }
        if (modFolders.none { it.id == folderId }) {
            return
        }
        setFolderCollapsed(host, folderId, !(folderCollapsed[folderId] == true))
    }

    fun setFolderCollapsed(host: Activity, folderId: String, collapsed: Boolean) {
        if (!canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        if (modFolders.none { it.id == folderId }) {
            return
        }
        if ((folderCollapsed[folderId] == true) == collapsed) {
            return
        }
        folderCollapsed[folderId] = collapsed
        persistFolderState(host)
        republish(host)
    }

    fun toggleUnassignedCollapsed(host: Activity) {
        setUnassignedCollapsed(host, !unassignedCollapsed)
    }

    fun setUnassignedCollapsed(host: Activity, collapsed: Boolean) {
        if (!canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        if (unassignedCollapsed == collapsed) {
            return
        }
        unassignedCollapsed = collapsed
        persistFolderState(host)
        republish(host)
    }

    fun toggleStatusSummaryCollapsed(host: Activity) {
        setStatusSummaryCollapsed(host, !statusSummaryCollapsed)
    }

    fun setStatusSummaryCollapsed(host: Activity, collapsed: Boolean) {
        if (!canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        if (statusSummaryCollapsed == collapsed) {
            return
        }
        statusSummaryCollapsed = collapsed
        persistFolderState(host)
        republish(host)
    }

    fun moveFolderUp(host: Activity, folderId: String) {
        moveFolderToken(host, folderId, -1)
    }

    fun moveFolderDown(host: Activity, folderId: String) {
        moveFolderToken(host, folderId, 1)
    }

    fun moveUnassignedUp(host: Activity) {
        moveFolderToken(host, UNASSIGNED_FOLDER_ID, -1)
    }

    fun moveUnassignedDown(host: Activity) {
        moveFolderToken(host, UNASSIGNED_FOLDER_ID, 1)
    }

    fun moveFolderTokenToIndex(host: Activity, draggedFolderId: String, targetIndex: Int) {
        if (!canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        val tokens = buildFolderOrderTokens().toMutableList()
        val fromIndex = tokens.indexOf(draggedFolderId)
        if (fromIndex < 0) {
            return
        }
        val clampedTargetIndex = targetIndex.coerceIn(0, tokens.lastIndex)
        if (fromIndex == clampedTargetIndex) {
            return
        }
        val movingToken = tokens.removeAt(fromIndex)
        val insertIndex = clampedTargetIndex.coerceIn(0, tokens.size)
        tokens.add(insertIndex, movingToken)
        applyFolderOrderTokens(tokens)
        persistFolderState(host)
        republish(host)
    }

    fun collapseAllFoldersForDragWithSnapshot(host: Activity): FolderCollapseSnapshot {
        if (!canEditMainScreenState()) {
            return FolderCollapseSnapshot(
                folderCollapsed = LinkedHashMap(folderCollapsed),
                unassignedCollapsed = unassignedCollapsed
            )
        }
        ensureFolderStateLoaded(host)
        val snapshot = FolderCollapseSnapshot(
            folderCollapsed = LinkedHashMap(folderCollapsed),
            unassignedCollapsed = unassignedCollapsed
        )
        val collapsedAll = LinkedHashMap<String, Boolean>()
        modFolders.forEach { collapsedAll[it.id] = true }
        folderCollapsed.clear()
        folderCollapsed.putAll(collapsedAll)
        unassignedCollapsed = true
        persistFolderState(host)
        republish(host)
        return snapshot
    }

    fun collapseAllFoldersForModDrag(host: Activity) {
        if (!canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        val collapsedAll = LinkedHashMap<String, Boolean>()
        modFolders.forEach { collapsedAll[it.id] = true }
        folderCollapsed.clear()
        folderCollapsed.putAll(collapsedAll)
        unassignedCollapsed = true
        persistFolderState(host)
        republish(host)
    }

    fun expandOnlySourceFolderAfterModDrag(host: Activity, folderTokenId: String) {
        if (!canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        val collapsedAll = LinkedHashMap<String, Boolean>()
        modFolders.forEach { collapsedAll[it.id] = true }
        folderCollapsed.clear()
        folderCollapsed.putAll(collapsedAll)
        unassignedCollapsed = true

        if (folderTokenId == UNASSIGNED_FOLDER_ID) {
            unassignedCollapsed = false
        } else if (modFolders.any { it.id == folderTokenId }) {
            folderCollapsed[folderTokenId] = false
        } else {
            unassignedCollapsed = false
        }

        persistFolderState(host)
        republish(host)
    }

    fun restoreFolderCollapseSnapshot(host: Activity, snapshot: FolderCollapseSnapshot?) {
        if (snapshot == null) {
            return
        }
        if (!canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        val validIds = modFolders.map { it.id }.toHashSet()
        val restored = LinkedHashMap<String, Boolean>()
        modFolders.forEach { restored[it.id] = snapshot.folderCollapsed[it.id] == true }
        folderCollapsed.clear()
        folderCollapsed.putAll(restored.filterKeys { validIds.contains(it) })
        unassignedCollapsed = snapshot.unassignedCollapsed
        persistFolderState(host)
        republish(host)
    }

    fun onModJarsPicked(host: Activity, uris: List<Uri>?) {
        if (uiState.busy || uris.isNullOrEmpty()) {
            return
        }
        setBusy(
            busy = true,
            message = "Importing selected mod jars...",
            operation = UiBusyOperation.MOD_IMPORT
        )
        executor.execute {
            try {
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
                    setBusy(false, null)
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
                    refresh(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    Toast.makeText(host, "Mod import failed: ${error.message}", Toast.LENGTH_LONG).show()
                    refresh(host)
                }
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

    fun handleIncomingIntent(host: Activity, intent: Intent?) {
        if (intent == null) {
            return
        }

        val expectedBackExit = BackExitNotice.consumeExpectedBackExitIfRecent(host)
        if (expectedBackExit) {
            clearCrashExtras(intent)
            showExpectedBackExitDialog(host)
            return
        }

        val showedCrashDialog = maybeShowCrashDialog(host, intent)
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
        if (!canEditMainScreenState() || mod.required) {
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
            host = host,
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
                        val deleted = ModManager.deleteOptionalModByStoragePath(host, mod.storagePath)
                        host.runOnUiThread {
                            setBusy(false, null)
                            clearPendingSelectionForMod(mod)
                            removeFolderAssignmentForDeletedMod(mod)
                            persistFolderState(host)
                            if (deleted) {
                                Toast.makeText(host, "已删除模组：$displayName", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(host, "模组不存在或已被删除：$displayName", Toast.LENGTH_SHORT).show()
                            }
                            refresh(host)
                        }
                    } catch (error: Throwable) {
                        host.runOnUiThread {
                            setBusy(false, null)
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

    private fun findEnabledDuplicateModIdGroups(optionalMods: List<ModItemUi>): Map<String, List<ModItemUi>> {
        val grouped = LinkedHashMap<String, MutableList<ModItemUi>>()
        optionalMods.forEach { mod ->
            if (!mod.enabled || !mod.installed || mod.required) {
                return@forEach
            }
            val normalized = normalizeModId(mod.modId).ifBlank {
                normalizeModId(mod.manifestModId)
            }
            if (normalized.isEmpty()) {
                return@forEach
            }
            grouped.getOrPut(normalized) { ArrayList() }.add(mod)
        }

        val duplicates = LinkedHashMap<String, List<ModItemUi>>()
        grouped.keys.sorted().forEach { modId ->
            val conflicts = grouped[modId] ?: return@forEach
            if (conflicts.size <= 1) {
                return@forEach
            }
            duplicates[modId] = conflicts.sortedBy { resolveModFileName(it.storagePath).lowercase(Locale.ROOT) }
        }
        return duplicates
    }

    private fun showDuplicateModIdDialog(host: Activity, duplicateGroups: Map<String, List<ModItemUi>>) {
        if (duplicateGroups.isEmpty()) {
            return
        }
        val message = buildString {
            append("检测到同时启用了多个 modid 相同的模组：\n")
            duplicateGroups.forEach { (modId, mods) ->
                append("\nmodid: ").append(modId).append('\n')
                mods.forEach { mod ->
                    append("- ")
                    append(resolveModDisplayName(mod))
                    val fileName = resolveModFileName(mod.storagePath)
                    if (fileName.isNotBlank()) {
                        append(" [").append(fileName).append("]")
                    }
                    append('\n')
                }
            }
            append("\n请先只保留一个同 modid 模组为启用状态，再启动游戏。")
        }.trimEnd()
        AlertDialog.Builder(host)
            .setTitle("检测到重复 modid")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun findEnabledMtsLaunchValidationIssues(optionalMods: List<ModItemUi>): List<MtsLaunchValidationIssue> {
        val issues = ArrayList<MtsLaunchValidationIssue>()
        optionalMods.forEach { mod ->
            if (!mod.enabled || !mod.installed) {
                return@forEach
            }
            val failure = MtsLaunchManifestValidator.validateModJar(File(mod.storagePath))
                ?: return@forEach
            issues.add(
                MtsLaunchValidationIssue(
                    mod = mod,
                    reason = failure.reason
                )
            )
        }
        return issues
    }

    private fun showMtsLaunchValidationDialog(host: Activity, issues: List<MtsLaunchValidationIssue>) {
        if (issues.isEmpty()) {
            return
        }
        val message = buildString {
            append("检测到以下已勾选模组无法被 ModTheSpire 在启动时解析：\n")
            issues.forEach { issue ->
                append("\n- ").append(resolveModDisplayName(issue.mod))
                val fileName = resolveModFileName(issue.mod.storagePath)
                if (fileName.isNotBlank()) {
                    append(" [").append(fileName).append("]")
                }
                append("\n  原因：").append(issue.reason)
            }
            append("\n\n请检查这些模组的 ModTheSpire.json 是否符合 ModTheSpire 格式。")
            append("\n如果这些 jar 是从压缩包二次解包/重打包得到的，建议重新获取原始 jar 后再导入。")
        }.trimEnd()
        AlertDialog.Builder(host)
            .setTitle("检测到需要重新导入的模组")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun findModsDirLaunchConflicts(
        host: Activity,
        optionalMods: List<ModItemUi>
    ): List<MtsModsDirConflictIssue> {
        val launchModIds = LinkedHashSet<String>()
        val baseModId = try {
            ModJarSupport.resolveModId(RuntimePaths.importedBaseModJar(host)).trim()
        } catch (_: Throwable) {
            ModManager.MOD_ID_BASEMOD
        }
        if (baseModId.isNotEmpty()) {
            launchModIds.add(baseModId)
        }
        val stsLibId = try {
            ModJarSupport.resolveModId(RuntimePaths.importedStsLibJar(host)).trim()
        } catch (_: Throwable) {
            ModManager.MOD_ID_STSLIB
        }
        if (stsLibId.isNotEmpty()) {
            launchModIds.add(stsLibId)
        }

        optionalMods.forEach { mod ->
            if (!mod.enabled || !mod.installed) {
                return@forEach
            }
            val launchModId = try {
                MtsLaunchManifestValidator.resolveLaunchModId(File(mod.storagePath)).trim()
            } catch (_: Throwable) {
                ""
            }
            if (launchModId.isNotEmpty()) {
                launchModIds.add(launchModId)
            }
        }
        if (launchModIds.isEmpty()) {
            return emptyList()
        }

        return ModManager.findModsDirLaunchIdConflicts(host, launchModIds)
            .map { conflict ->
                MtsModsDirConflictIssue(
                    launchModId = conflict.launchModId,
                    jarFiles = conflict.jarFiles
                )
            }
    }

    private fun showModsDirLaunchConflictDialog(host: Activity, conflicts: List<MtsModsDirConflictIssue>) {
        if (conflicts.isEmpty()) {
            return
        }
        val message = buildString {
            append("检测到 mods 目录中存在会影响本次启动的重复模组文件：\n")
            conflicts.forEach { conflict ->
                append("\nmodid: ").append(conflict.launchModId).append('\n')
                conflict.jarFiles.forEach { file ->
                    append("- ").append(file.name).append('\n')
                }
            }
            append("\n请删除这些重复文件，只保留每个 modid 的一个 jar 后再启动。")
        }.trimEnd()
        AlertDialog.Builder(host)
            .setTitle("检测到 mods 目录残留重复模组")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun maybeShowCrashDialog(
        host: Activity,
        intent: Intent
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

        clearCrashExtras(intent)

        AlertDialog.Builder(host)
            .setTitle(R.string.sts_crash_dialog_title)
            .setView(createScrollableDialogMessageView(host, message))
            .setNeutralButton(R.string.sts_share_crash_report) { _, _ ->
                shareCrashLogs(host, code, isSignal, detail)
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
        return true
    }

    private fun createScrollableDialogMessageView(host: Activity, message: String): ScrollView {
        val density = host.resources.displayMetrics.density
        val horizontalPaddingPx = (24f * density).toInt()
        val verticalPaddingPx = (12f * density).toInt()
        val minHeightPx = (120f * density).toInt()
        val maxHeightPx = (320f * density).toInt()

        val textView = TextView(host).apply {
            text = message
            setTextIsSelectable(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(0f, 1.1f)
        }

        return ScrollView(host).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(horizontalPaddingPx, verticalPaddingPx, horizontalPaddingPx, verticalPaddingPx)
            addView(
                textView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            minimumHeight = minHeightPx
            if (layoutParams is ViewGroup.MarginLayoutParams) {
                (layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = verticalPaddingPx
            }
            post {
                if (height > maxHeightPx) {
                    layoutParams = layoutParams.apply {
                        height = maxHeightPx
                    }
                }
            }
        }
    }

    private fun shareCrashLogs(host: Activity, code: Int, isSignal: Boolean, detail: String?) {
        if (uiState.busy) {
            return
        }
        setBusy(true, "Preparing JVM log bundle...")
        executor.execute {
            try {
                val payload = JvmLogShareService.prepareCrashSharePayload(
                    host,
                    code,
                    isSignal,
                    detail
                )
                host.runOnUiThread {
                    setBusy(false, null)
                    try {
                        val shareIntent = JvmLogShareService.buildShareIntent(payload)
                        val chooserIntent = Intent.createChooser(
                            shareIntent,
                            host.getString(R.string.sts_share_crash_chooser_title)
                        )
                        host.startActivity(chooserIntent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(
                            host,
                            host.getString(R.string.sts_share_crash_report_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (_: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    Toast.makeText(
                        host,
                        host.getString(R.string.sts_share_crash_report_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
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
                    "如不需要该行为，可在设置中将 Back 键行为改为「Esc」或「无行为」。"
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun resolveExportableModFile(mod: ModItemUi): File? {
        val path = mod.storagePath.trim()
        if (path.isEmpty()) {
            return null
        }
        val file = File(path)
        return if (file.exists() && file.isFile) file else null
    }

    private fun normalizeModJarFileName(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        return if (trimmed.endsWith(".jar", ignoreCase = true)) {
            trimmed
        } else {
            "$trimmed.jar"
        }
    }

    @Throws(IOException::class)
    private fun moveFileReplacing(source: File, target: File) {
        if (target.exists()) {
            throw IOException("Target already exists: ${target.absolutePath}")
        }
        if (source.renameTo(target)) {
            return
        }
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
                output.flush()
            }
        }
        if (!source.delete()) {
            if (!target.delete()) {
                // ignore cleanup failure, keep the copied file as best-effort output.
            }
            throw IOException("Failed to delete old file: ${source.absolutePath}")
        }
    }

    @Throws(IOException::class)
    private fun prepareModShareFile(host: Activity, sourceFile: File): File {
        val shareDir = File(host.cacheDir, "share/mods")
        if (!shareDir.exists() && !shareDir.mkdirs()) {
            throw IOException("Failed to create share directory: ${shareDir.absolutePath}")
        }
        val safeName = sourceFile.name.ifBlank { "mod-export.jar" }
        val targetFile = File(shareDir, safeName)
        if (sourceFile.absolutePath == targetFile.absolutePath) {
            return sourceFile
        }
        sourceFile.inputStream().use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
                output.flush()
            }
        }
        return targetFile
    }

    private fun showBatchMissingDependencyDialog(host: Activity, missingDependencies: List<String>) {
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
            append("批量启用完成，但缺少以下前置模组：\n")
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

    private fun batchEnableMods(host: Activity, targetMods: List<ModItemUi>) {
        val autoEnabled = LinkedHashSet<String>()
        val missingDependencies = LinkedHashSet<String>()
        targetMods.forEach { mod ->
            if (!mod.installed || mod.required || isPendingOptionalModEnabled(mod)) {
                return@forEach
            }
            val result = enableModWithDependencies(
                host = host,
                rootMod = mod,
                optionalMods = resolveOptionalModsWithPendingSelection()
            )
            autoEnabled.addAll(result.autoEnabledModNames)
            missingDependencies.addAll(result.missingDependencies)
        }

        if (autoEnabled.isNotEmpty()) {
            Toast.makeText(
                host,
                "已自动启用前置模组：${autoEnabled.joinToString(", ")}",
                Toast.LENGTH_LONG
            ).show()
        }
        if (missingDependencies.isNotEmpty()) {
            showBatchMissingDependencyDialog(host, ArrayList(missingDependencies))
        }
    }

    private fun batchDisableMods(host: Activity, targetMods: List<ModItemUi>) {
        val exclusionIds = LinkedHashSet<String>()
        targetMods.forEach { mod ->
            collectModIdentityIds(mod).forEach { exclusionIds.add(it) }
        }

        val blocked = LinkedHashMap<String, List<String>>()
        targetMods.forEach { mod ->
            if (!isPendingOptionalModEnabled(mod)) {
                return@forEach
            }
            val dependents = findEnabledDependentModNamesExcludingTargets(
                targetMod = mod,
                optionalMods = resolveOptionalModsWithPendingSelection(),
                excludedIds = exclusionIds
            )
            if (dependents.isEmpty()) {
                setPendingOptionalModEnabled(mod, false)
            } else {
                blocked[resolveModDisplayName(mod)] = dependents
            }
        }

        if (blocked.isNotEmpty()) {
            val detail = blocked.entries.joinToString("；") { entry ->
                "「${entry.key}」被 ${entry.value.joinToString(", ")} 依赖"
            }
            Toast.makeText(host, "部分模组无法关闭：$detail", Toast.LENGTH_LONG).show()
        }
    }

    private fun findEnabledDependentModNamesExcludingTargets(
        targetMod: ModItemUi,
        optionalMods: List<ModItemUi>,
        excludedIds: Set<String>
    ): List<String> {
        val targetIds = collectModIdentityIds(targetMod)
        if (targetIds.isEmpty()) {
            return emptyList()
        }
        val dependentNames = LinkedHashSet<String>()
        optionalMods.forEach { mod ->
            if (!mod.enabled) {
                return@forEach
            }
            val modIds = collectModIdentityIds(mod)
            if (modIds.any { targetIds.contains(it) }) {
                return@forEach
            }
            if (modIds.any { excludedIds.contains(it) }) {
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

    private fun collectModIdentityIds(mod: ModItemUi): Set<String> {
        val ids = LinkedHashSet<String>()
        val normalizedModId = normalizeModId(mod.modId)
        if (normalizedModId.isNotEmpty()) {
            ids.add(normalizedModId)
        }
        val normalizedManifestId = normalizeModId(mod.manifestModId)
        if (normalizedManifestId.isNotEmpty()) {
            ids.add(normalizedManifestId)
        }
        return ids
    }

    private fun ensureFolderStateLoaded(host: Activity) {
        folderStateStore.ensureLoaded(host)
    }

    private fun persistFolderState(host: Activity) {
        folderStateStore.persist(host)
    }

    private fun sanitizeFolderAssignments(optionalMods: List<ModItemUi>) {
        folderStateStore.sanitize(optionalMods)
    }

    private fun resolveModAssignmentKey(mod: ModItemUi): String? {
        val storage = mod.storagePath.trim()
        if (storage.isNotEmpty()) {
            return storage
        }
        return resolveStoredOptionalModId(mod)
    }

    private fun resolveAssignmentKeyCandidates(mod: ModItemUi): List<String> {
        return io.stamethyst.ui.main.resolveAssignmentKeyCandidates(mod)
    }

    private fun resolveAssignedFolderId(mod: ModItemUi): String? {
        val folderIds = modFolders.map { it.id }.toHashSet()
        return io.stamethyst.ui.main.resolveAssignedFolderId(
            mod = mod,
            folderAssignments = folderAssignments,
            validFolderIds = folderIds
        )
    }

    private fun clearAssignmentForMod(mod: ModItemUi) {
        resolveAssignmentKeyCandidates(mod).forEach { candidate ->
            folderAssignments.remove(candidate)
        }
    }

    private fun removeFolderAssignmentForDeletedMod(mod: ModItemUi) {
        clearAssignmentForMod(mod)
    }

    private fun buildFolderOrderTokens(): List<String> {
        return folderStateStore.buildFolderOrderTokens()
    }

    private fun applyFolderOrderTokens(tokens: List<String>) {
        folderStateStore.applyFolderOrderTokens(tokens)
    }

    private fun moveFolderToken(host: Activity, folderId: String, offset: Int) {
        val moved = folderStateStore.moveFolderToken(host, folderId, offset)
        if (moved) {
            republish(host)
        }
    }

    private fun findOptionalModByAnyId(id: String): ModItemUi? {
        val input = id.trim()
        if (input.isEmpty()) {
            return null
        }
        val normalized = normalizeModId(input)
        return resolveOptionalModsWithPendingSelection().firstOrNull { mod ->
            mod.storagePath == input ||
                normalizeModId(mod.modId) == normalized ||
                normalizeModId(mod.manifestModId) == normalized
        }
    }

    private fun buildNextFolderName(): String {
        val exists = modFolders.map { it.name }.toHashSet()
        var index = 1
        while ("文件夹$index" in exists) {
            index++
        }
        return "文件夹$index"
    }

    private fun ensureUniqueFolderName(baseName: String, excludeFolderId: String? = null): String {
        val existingNames = modFolders
            .filterNot { it.id == excludeFolderId }
            .map { it.name }
            .toHashSet()
        if (!existingNames.contains(baseName)) {
            return baseName
        }
        var index = 2
        var candidate = "$baseName($index)"
        while (existingNames.contains(candidate)) {
            index++
            candidate = "$baseName($index)"
        }
        return candidate
    }

    private fun republish(host: Activity) {
        publishUiState(
            host = host,
            hasJar = RuntimePaths.importedStsJar(host).exists(),
            hasMts = RuntimePaths.importedMtsJar(host).exists() || hasBundledAsset(host, "components/mods/ModTheSpire.jar"),
            hasBaseMod = isRequiredModAvailable(host, ModManager.MOD_ID_BASEMOD),
            hasStsLib = isRequiredModAvailable(host, ModManager.MOD_ID_STSLIB)
        )
    }

    private fun maybeLaunchFromDebugExtra(host: Activity, intent: Intent) {
        val debugLaunchMode = intent.getStringExtra(LauncherActivity.EXTRA_DEBUG_LAUNCH_MODE)
        val forceJvmCrash = intent.getBooleanExtra(LauncherActivity.EXTRA_DEBUG_FORCE_JVM_CRASH, false)
        if (debugLaunchMode != StsLaunchSpec.LAUNCH_MODE_VANILLA &&
            !StsLaunchSpec.isMtsLaunchMode(debugLaunchMode)
        ) {
            return
        }

        prepareAndLaunch(host, debugLaunchMode ?: StsLaunchSpec.LAUNCH_MODE_VANILLA, forceJvmCrash = forceJvmCrash)
    }

    private fun prepareAndLaunch(host: Activity, launchMode: String, forceJvmCrash: Boolean) {
        ensurePendingSelectionInitialized(host)
        if (StsLaunchSpec.isMtsLaunchMode(launchMode)) {
            val optionalMods = resolveOptionalModsWithPendingSelection()
            val duplicateGroups = findEnabledDuplicateModIdGroups(optionalMods)
            if (duplicateGroups.isNotEmpty()) {
                showDuplicateModIdDialog(host, duplicateGroups)
                return
            }
            val invalidMods = findEnabledMtsLaunchValidationIssues(optionalMods)
            if (invalidMods.isNotEmpty()) {
                showMtsLaunchValidationDialog(host, invalidMods)
                return
            }
            val modsDirConflicts = findModsDirLaunchConflicts(host, optionalMods)
            if (modsDirConflicts.isNotEmpty()) {
                showModsDirLaunchConflictDialog(host, modsDirConflicts)
                return
            }
        }
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
        val backBehavior = readBackBehaviorSelection(host)
        val manualDismissBootOverlay = readManualDismissBootOverlaySelection(host)

        StsGameActivity.launch(
            host,
            launchMode,
            targetFps,
            backBehavior,
            manualDismissBootOverlay,
            forceJvmCrash
        )
    }

    private fun loadModItems(host: Activity): List<ModItemUi> {
        return ModManager.listInstalledMods(host).map { mod ->
            ModItemUi(
                modId = mod.modId,
                manifestModId = mod.manifestModId,
                storagePath = mod.jarFile.absolutePath,
                name = mod.name,
                version = mod.version,
                description = mod.description,
                dependencies = mod.dependencies,
                required = mod.required,
                installed = mod.installed,
                enabled = mod.enabled,
                priorityRoot = mod.priorityRoot,
                priorityLoad = mod.priorityLoad
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
        return io.stamethyst.ui.main.resolveModDisplayName(mod, showModFileName = false)
    }

    private fun resolveModFileName(storagePath: String): String {
        val normalized = storagePath.trim()
        if (normalized.isEmpty()) {
            return ""
        }
        return File(normalized).name.trim()
    }

    private fun readBackBehaviorSelection(host: Activity): BackBehavior {
        return LauncherPreferences.readBackBehavior(host)
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
        markOptionalModsWithPendingSelectionDirty()
    }

    private fun ensurePendingSelectionInitialized(host: Activity) {
        if (pendingSelectionInitialized) {
            return
        }
        val optionalMods = loadModItems(host).filter { !it.required }
        initializePendingSelection(optionalMods)
        optionalModsSnapshot.clear()
        optionalModsSnapshot.addAll(optionalMods.map { it.copy(enabled = false) })
        markOptionalModsWithPendingSelectionDirty()
        prunePendingSelectionToInstalled()
        pendingSelectionInitialized = true
    }

    private fun resolveOptionalModsWithPendingSelection(): List<ModItemUi> {
        if (!optionalModsWithPendingSelectionDirty) {
            return optionalModsWithPendingSelectionCache
        }
        optionalModsWithPendingSelectionCache = optionalModsSnapshot.map { mod ->
            mod.copy(enabled = isPendingOptionalModEnabled(mod))
        }
        optionalModsWithPendingSelectionDirty = false
        return optionalModsWithPendingSelectionCache
    }

    private fun isPendingOptionalModEnabled(mod: ModItemUi): Boolean {
        val storedId = resolveStoredOptionalModId(mod)
        if (storedId != null && pendingEnabledOptionalModIds.contains(storedId)) {
            return true
        }
        return false
    }

    private fun setPendingOptionalModEnabled(mod: ModItemUi, enabled: Boolean) {
        val storedId = resolveStoredOptionalModId(mod)
        var changed = false
        if (storedId != null) {
            if (enabled) {
                if (pendingEnabledOptionalModIds.add(storedId)) {
                    changed = true
                }
            } else {
                if (pendingEnabledOptionalModIds.remove(storedId)) {
                    changed = true
                }
            }
        }
        if (changed) {
            markOptionalModsWithPendingSelectionDirty()
        }
    }

    private fun clearPendingSelectionForMod(mod: ModItemUi) {
        setPendingOptionalModEnabled(mod, false)
    }

    private fun resolveStoredOptionalModId(mod: ModItemUi): String? {
        return io.stamethyst.ui.main.resolveStoredOptionalModId(mod)
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
        }
        val oldSize = pendingEnabledOptionalModIds.size
        pendingEnabledOptionalModIds.retainAll(installedIds)
        if (pendingEnabledOptionalModIds.size != oldSize) {
            markOptionalModsWithPendingSelectionDirty()
        }
    }

    private fun markOptionalModsWithPendingSelectionDirty() {
        optionalModsWithPendingSelectionDirty = true
    }

    private fun publishUiState(
        host: Activity,
        hasJar: Boolean,
        hasMts: Boolean,
        hasBaseMod: Boolean,
        hasStsLib: Boolean
    ) {
        val currentBusy = uiState.busy
        val currentBusyOperation = uiState.busyOperation
        val currentBusyMessage = uiState.busyMessage
        val optionalMods = resolveOptionalModsWithPendingSelection()
        val optionalTotal = optionalMods.size
        val optionalEnabled = optionalMods.count { it.enabled }
        uiState = uiState.copy(
            initializing = false,
            busy = currentBusy,
            busyOperation = if (currentBusy) currentBusyOperation else UiBusyOperation.NONE,
            busyMessage = if (currentBusy) currentBusyMessage else null,
            statusSummary = buildMainStatusSummary(
                hasJar = hasJar,
                hasMts = hasMts,
                hasBaseMod = hasBaseMod,
                hasStsLib = hasStsLib,
                optionalEnabledCount = optionalEnabled,
                optionalTotalCount = optionalTotal
            ),
            optionalMods = optionalMods,
            controlsEnabled = resolveControlsEnabled(currentBusy, currentBusyOperation),
            showModFileName = LauncherPreferences.readShowModFileName(host),
            modFolders = ArrayList(modFolders),
            folderAssignments = LinkedHashMap(folderAssignments),
            folderCollapsed = LinkedHashMap(folderCollapsed),
            unassignedCollapsed = unassignedCollapsed,
            statusSummaryCollapsed = statusSummaryCollapsed,
            unassignedFolderName = unassignedFolderName,
            unassignedFolderOrder = unassignedFolderOrder.coerceIn(0, modFolders.size)
        )
    }

    private fun setBusy(
        busy: Boolean,
        message: String?,
        operation: UiBusyOperation = UiBusyOperation.OTHER_BUSY
    ) {
        uiState = if (busy) {
            uiState.copy(
                busy = true,
                busyOperation = operation,
                busyMessage = message,
                controlsEnabled = resolveControlsEnabled(true, operation)
            )
        } else {
            uiState.copy(
                busy = false,
                busyOperation = UiBusyOperation.NONE,
                busyMessage = null,
                controlsEnabled = true
            )
        }
    }

    private fun resolveControlsEnabled(busy: Boolean, operation: UiBusyOperation): Boolean {
        return !busy || operation == UiBusyOperation.MOD_IMPORT
    }

    companion object {
        private const val DEFAULT_UNASSIGNED_FOLDER_NAME = "未分类"
    }

    override fun onCleared() {
        executor.shutdownNow()
        super.onCleared()
    }
}

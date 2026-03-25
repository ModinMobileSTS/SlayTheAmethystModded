package io.stamethyst.ui.main

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import io.stamethyst.R
import io.stamethyst.backend.file_interactive.SafFileExporter
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.MtsLaunchManifestValidator
import io.stamethyst.config.RuntimePaths
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.UiText
import io.stamethyst.ui.UiBusyOperation
import io.stamethyst.ui.VupShionPatchedDialog
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.settings.DuplicateModImportConflict
import io.stamethyst.ui.settings.InvalidModImportFailure
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
import android.widget.Toast

internal data class MainMtsLaunchValidationIssue(
    val mod: ModItemUi,
    val reason: String
)

internal class MainModManagementController(
    private val hostCallbacks: Host
) {
    interface Host {
        fun canEditMainScreenState(): Boolean
        fun isBusy(): Boolean
        fun setBusy(
            busy: Boolean,
            message: UiText?,
            operation: UiBusyOperation = UiBusyOperation.OTHER_BUSY,
            progressPercent: Int? = null
        )

        fun republish(host: Activity)
        fun emitEffect(effect: MainScreenViewModel.Effect)
    }

    data class Snapshot(
        val optionalMods: List<ModItemUi>,
        val requiredMods: List<ModItemUi>,
        val modFolders: List<MainScreenViewModel.ModFolder>,
        val folderAssignments: Map<String, String>,
        val folderCollapsed: Map<String, Boolean>,
        val unassignedCollapsed: Boolean,
        val dependencyFolderCollapsed: Boolean,
        val unassignedFolderName: String,
        val unassignedFolderOrder: Int
    )

    private data class DependencyEnableResult(
        val autoEnabledModNames: List<String>,
        val missingDependencies: List<String>
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val requiredModsSnapshot = ArrayList<ModItemUi>()
    private val optionalModsSnapshot = ArrayList<ModItemUi>()
    private var optionalModsWithPendingSelectionCache: List<ModItemUi> = emptyList()
    private var optionalModsWithPendingSelectionDirty = true
    private val pendingEnabledOptionalModIds = LinkedHashSet<String>()
    private var pendingSelectionInitialized = false
    private val folderStateStore = MainFolderStateStore()
    private val modFolders: MutableList<MainScreenViewModel.ModFolder>
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
    private var dependencyFolderCollapsed: Boolean
        get() = folderStateStore.dependencyFolderIsCollapsed
        set(value) {
            folderStateStore.dependencyFolderIsCollapsed = value
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

    fun shutdown() {
        executor.shutdownNow()
    }

    fun refresh(host: Activity, storageAccessible: Boolean) {
        ensureFolderStateLoaded(host)
        if (!storageAccessible) {
            return
        }
        val mods = loadModItems(host)
        requiredModsSnapshot.clear()
        requiredModsSnapshot.addAll(mods.filter { it.required }.map { it.copy(enabled = it.installed) })
        val optionalMods = mods.filter { !it.required }
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
    }

    fun snapshot(): Snapshot {
        return Snapshot(
            optionalMods = resolveOptionalModsWithPendingSelection(),
            requiredMods = ArrayList(requiredModsSnapshot),
            modFolders = ArrayList(modFolders),
            folderAssignments = LinkedHashMap(folderAssignments),
            folderCollapsed = LinkedHashMap(folderCollapsed),
            unassignedCollapsed = unassignedCollapsed,
            dependencyFolderCollapsed = dependencyFolderCollapsed,
            unassignedFolderName = unassignedFolderName,
            unassignedFolderOrder = unassignedFolderOrder.coerceIn(0, modFolders.size)
        )
    }

    fun currentOptionalMods(): List<ModItemUi> {
        return resolveOptionalModsWithPendingSelection()
    }

    fun applyPendingSelection(host: Activity) {
        ModManager.replaceEnabledOptionalModIds(host, LinkedHashSet(pendingEnabledOptionalModIds))
    }

    fun suggestNextFolderName(): String {
        return buildNextFolderName()
    }

    fun addFolder(host: Activity, name: String) {
        if (!hostCallbacks.canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        val input = name.trim()
        if (input.isEmpty()) {
            emitSnackbar(UiText.StringResource(R.string.main_folder_dialog_name_empty))
            return
        }
        val uniqueName = ensureUniqueFolderName(input)
        modFolders.add(MainScreenViewModel.ModFolder(id = UUID.randomUUID().toString(), name = uniqueName))
        folderCollapsed[modFolders.last().id] = false
        persistFolderState(host)
        hostCallbacks.republish(host)
    }

    fun renameFolder(host: Activity, folderId: String, newName: String) {
        if (!hostCallbacks.canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        val input = newName.trim()
        if (input.isEmpty()) {
            emitSnackbar(UiText.StringResource(R.string.main_folder_dialog_name_empty))
            return
        }
        if (folderId == UNASSIGNED_FOLDER_ID) {
            if (input == unassignedFolderName) {
                return
            }
            unassignedFolderName = input
            persistFolderState(host)
            hostCallbacks.republish(host)
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
        hostCallbacks.republish(host)
    }

    fun deleteFolder(host: Activity, folderId: String) {
        if (!hostCallbacks.canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        val folder = modFolders.firstOrNull { it.id == folderId } ?: return
        modFolders.removeAll { it.id == folderId }
        folderAssignments.entries.removeIf { it.value == folderId }
        folderCollapsed.remove(folderId)
        unassignedFolderOrder = unassignedFolderOrder.coerceIn(0, modFolders.size)
        persistFolderState(host)
        emitSnackbar(host.getString(R.string.main_folder_deleted, folder.name))
        hostCallbacks.republish(host)
    }

    fun assignModToFolder(host: Activity, modId: String, folderId: String) {
        val target = findOptionalModByAnyId(modId) ?: return
        assignModToFolder(host, target, folderId)
    }

    fun assignModToFolder(host: Activity, mod: ModItemUi, folderId: String) {
        if (!hostCallbacks.canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        val targetFolder = modFolders.firstOrNull { it.id == folderId } ?: run {
            return
        }
        val key = resolveModAssignmentKey(mod) ?: return
        val currentFolderId = resolveAssignedFolderId(mod)
        if (currentFolderId == folderId) {
            revealFolderToken(host, folderId)
            return
        }
        clearAssignmentForMod(mod)
        folderAssignments[key] = folderId
        folderCollapsed[folderId] = false
        persistFolderState(host)
        hostCallbacks.republish(host)
        emitModMovedSnackbar(host, mod, targetFolder.name)
    }

    fun moveModToUnassigned(host: Activity, modId: String) {
        val target = findOptionalModByAnyId(modId) ?: return
        moveModToUnassigned(host, target)
    }

    fun moveModToUnassigned(host: Activity, mod: ModItemUi) {
        if (!hostCallbacks.canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        if (resolveAssignedFolderId(mod) == null) {
            setUnassignedCollapsed(host, false)
            return
        }
        clearAssignmentForMod(mod)
        unassignedCollapsed = false
        persistFolderState(host)
        hostCallbacks.republish(host)
        emitModMovedSnackbar(host, mod, unassignedFolderName)
    }

    fun setFolderSelected(host: Activity, folderId: String, selected: Boolean) {
        if (!hostCallbacks.canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        val targetMods = resolveOptionalModsWithPendingSelection().filter {
            resolveAssignedFolderId(it) == folderId
        }
        if (targetMods.isEmpty()) {
            emitSnackbar(host.getString(R.string.main_folder_empty))
            return
        }
        if (selected) {
            batchEnableMods(host, targetMods)
        } else {
            batchDisableMods(host, targetMods)
        }
        hostCallbacks.republish(host)
    }

    fun setUnassignedSelected(host: Activity, selected: Boolean) {
        if (!hostCallbacks.canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        val targetMods = resolveOptionalModsWithPendingSelection().filter {
            resolveAssignedFolderId(it) == null
        }
        if (targetMods.isEmpty()) {
            emitSnackbar(host.getString(R.string.main_folder_unassigned_empty_snackbar))
            return
        }
        if (selected) {
            batchEnableMods(host, targetMods)
        } else {
            batchDisableMods(host, targetMods)
        }
        hostCallbacks.republish(host)
    }

    fun toggleFolderCollapsed(host: Activity, folderId: String) {
        if (!hostCallbacks.canEditMainScreenState()) {
            return
        }
        if (modFolders.none { it.id == folderId }) {
            return
        }
        setFolderCollapsed(host, folderId, !(folderCollapsed[folderId] == true))
    }

    fun setFolderCollapsed(host: Activity, folderId: String, collapsed: Boolean) {
        if (!hostCallbacks.canEditMainScreenState()) {
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
        hostCallbacks.republish(host)
    }

    fun toggleUnassignedCollapsed(host: Activity) {
        setUnassignedCollapsed(host, !unassignedCollapsed)
    }

    fun setUnassignedCollapsed(host: Activity, collapsed: Boolean) {
        if (!hostCallbacks.canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        if (unassignedCollapsed == collapsed) {
            return
        }
        unassignedCollapsed = collapsed
        persistFolderState(host)
        hostCallbacks.republish(host)
    }

    fun toggleDependencyFolderCollapsed(host: Activity) {
        setDependencyFolderCollapsed(host, !dependencyFolderCollapsed)
    }

    fun setDependencyFolderCollapsed(host: Activity, collapsed: Boolean) {
        if (!hostCallbacks.canEditMainScreenState()) {
            return
        }
        ensureFolderStateLoaded(host)
        if (dependencyFolderCollapsed == collapsed) {
            return
        }
        dependencyFolderCollapsed = collapsed
        persistFolderState(host)
        hostCallbacks.republish(host)
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
        if (!hostCallbacks.canEditMainScreenState()) {
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
        hostCallbacks.republish(host)
    }

    fun revealFolderToken(host: Activity, folderTokenId: String) {
        if (folderTokenId == UNASSIGNED_FOLDER_ID) {
            setUnassignedCollapsed(host, false)
        } else {
            setFolderCollapsed(host, folderTokenId, false)
        }
    }

    fun onModJarsPicked(host: Activity, uris: List<Uri>?) {
        if (hostCallbacks.isBusy() || uris.isNullOrEmpty()) {
            return
        }
        startModJarImport(host, uris)
    }

    fun onDeleteMod(host: Activity, mod: ModItemUi) {
        if (hostCallbacks.isBusy() || mod.required || !mod.installed) {
            return
        }
        val dependentModNames = findEnabledDependentModNames(
            targetMod = mod,
            optionalMods = resolveOptionalModsWithPendingSelection()
        )
        if (dependentModNames.isNotEmpty()) {
            emitDialog(
                title = host.getString(R.string.main_mod_delete_blocked_title),
                message = host.getString(
                    R.string.main_mod_delete_blocked_message,
                    dependentModNames.joinToString(", ")
                )
            )
            return
        }

        val displayName = resolveModDisplayName(mod)
        setBusy(true, UiText.StringResource(R.string.main_mod_delete_busy))
        executor.execute {
            try {
                val deleted = ModManager.deleteOptionalModByStoragePath(host, mod.storagePath)
                host.runOnUiThread {
                    setBusy(false, null)
                    clearPendingSelectionForMod(mod)
                    removeFolderAssignmentForDeletedMod(mod)
                    refresh(host, storageAccessible = true)
                    emitSnackbar(
                        if (deleted) {
                            host.getString(R.string.main_mod_deleted, displayName)
                        } else {
                            host.getString(R.string.main_mod_missing_deleted, displayName)
                        }
                    )
                    hostCallbacks.republish(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    emitSnackbar(
                        host.getString(
                            R.string.main_mod_delete_failed,
                            error.message ?: host.getString(R.string.feedback_unknown_error)
                        )
                    )
                    hostCallbacks.republish(host)
                }
            }
        }
    }

    fun onExportMod(host: Activity, mod: ModItemUi) {
        if (hostCallbacks.isBusy() || !mod.installed) {
            return
        }
        val sourceFile = resolveExportableModFile(mod)
        if (sourceFile == null) {
            emitSnackbar(host.getString(R.string.main_mod_export_missing))
            return
        }
        hostCallbacks.emitEffect(
            MainScreenViewModel.Effect.OpenExportModPicker(
                sourcePath = sourceFile.absolutePath,
                suggestedName = sourceFile.name
            )
        )
    }

    fun onExportModPicked(host: Activity, sourcePath: String?, uri: Uri?) {
        if (uri == null) {
            return
        }
        val normalizedSourcePath = sourcePath?.trim().orEmpty()
        if (normalizedSourcePath.isEmpty()) {
            emitSnackbar(host.getString(R.string.main_mod_export_missing))
            return
        }
        val sourceFile = File(normalizedSourcePath)
        if (!sourceFile.isFile) {
            emitSnackbar(host.getString(R.string.main_mod_export_missing))
            return
        }
        hostCallbacks.setBusy(
            busy = true,
            message = UiText.StringResource(R.string.main_mod_export_busy),
            operation = UiBusyOperation.MOD_IMPORT,
            progressPercent = 0
        )
        executor.execute {
            try {
                SafFileExporter.exportFile(host, sourceFile, uri) { percent ->
                    host.runOnUiThread {
                        hostCallbacks.setBusy(
                            busy = true,
                            message = UiText.StringResource(R.string.main_mod_export_busy),
                            operation = UiBusyOperation.MOD_IMPORT,
                            progressPercent = percent
                        )
                    }
                }
                host.runOnUiThread {
                    hostCallbacks.setBusy(false, null)
                    showToast(
                        host,
                        host.getString(R.string.main_mod_export_success, sourceFile.name),
                        Toast.LENGTH_SHORT
                    )
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    hostCallbacks.setBusy(false, null)
                    showToast(
                        host,
                        host.getString(
                            R.string.main_mod_export_failed,
                            error.message ?: host.getString(R.string.feedback_unknown_error)
                        ),
                        Toast.LENGTH_LONG
                    )
                }
            }
        }
    }

    fun onShareMod(host: Activity, mod: ModItemUi) {
        if (hostCallbacks.isBusy() || !mod.installed) {
            return
        }
        val sourceFile = resolveExportableModFile(mod)
        if (sourceFile == null) {
            emitSnackbar(host.getString(R.string.main_mod_share_missing))
            return
        }
        setBusy(true, UiText.StringResource(R.string.main_mod_share_busy))
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
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/java-archive"
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        clipData = ClipData.newRawUri(sourceFile.name, shareUri)
                        putExtra(Intent.EXTRA_SUBJECT, sourceFile.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooserIntent = Intent.createChooser(
                        shareIntent,
                        host.getString(R.string.main_mod_share_chooser_title)
                    )
                    hostCallbacks.emitEffect(MainScreenViewModel.Effect.LaunchIntent(chooserIntent))
                }
            } catch (_: ActivityNotFoundException) {
                host.runOnUiThread {
                    setBusy(false, null)
                    emitSnackbar(host.getString(R.string.main_mod_share_no_app))
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    emitSnackbar(
                        host.getString(
                            R.string.main_mod_share_failed,
                            error.message ?: host.getString(R.string.feedback_unknown_error)
                        )
                    )
                }
            }
        }
    }

    fun onRenameModFile(host: Activity, mod: ModItemUi, newFileNameInput: String) {
        if (hostCallbacks.isBusy() || mod.required || !mod.installed) {
            return
        }
        val sourceFile = resolveExportableModFile(mod)
        if (sourceFile == null) {
            emitSnackbar(host.getString(R.string.main_mod_rename_missing))
            return
        }
        val normalizedFileName = normalizeModJarFileName(newFileNameInput)
        if (normalizedFileName == null) {
            emitSnackbar(UiText.StringResource(R.string.main_mod_rename_error_empty))
            return
        }
        if (normalizedFileName.contains('/') || normalizedFileName.contains('\\')) {
            emitSnackbar(UiText.StringResource(R.string.main_mod_rename_error_separator))
            return
        }

        val targetFile = File(sourceFile.parentFile, normalizedFileName)
        if (targetFile.absolutePath == sourceFile.absolutePath) {
            return
        }
        if (targetFile.exists()) {
            emitSnackbar(host.getString(R.string.main_mod_rename_target_exists, targetFile.name))
            return
        }

        val assignedFolderId = resolveAssignedFolderId(mod)
        setBusy(true, UiText.StringResource(R.string.main_mod_rename_busy))
        executor.execute {
            try {
                val renamedFile = ModManager.renameOptionalModByStoragePath(
                    context = host,
                    storagePath = mod.storagePath,
                    requestedFileName = normalizedFileName
                )
                host.runOnUiThread {
                    setBusy(false, null)
                    replacePendingSelectionForRenamedMod(mod, renamedFile.absolutePath)
                    clearAssignmentForMod(mod)
                    if (!assignedFolderId.isNullOrBlank()) {
                        folderAssignments[renamedFile.absolutePath] = assignedFolderId
                    }
                    refresh(host, storageAccessible = true)
                    emitSnackbar(host.getString(R.string.main_mod_renamed, renamedFile.name))
                    hostCallbacks.republish(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    emitSnackbar(
                        host.getString(
                            R.string.main_mod_rename_failed,
                            error.message ?: host.getString(R.string.feedback_unknown_error)
                        )
                    )
                    hostCallbacks.republish(host)
                }
            }
        }
    }

    fun onToggleMod(host: Activity, mod: ModItemUi, enabled: Boolean) {
        if (!hostCallbacks.canEditMainScreenState() || mod.required) {
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
                    emitSnackbar(
                        host.getString(
                            R.string.main_mod_auto_enabled_dependencies,
                            result.autoEnabledModNames.joinToString(", ")
                        )
                    )
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
                    emitDialog(
                        title = host.getString(R.string.main_mod_toggle_off_blocked_title),
                        message = host.getString(
                            R.string.main_mod_delete_blocked_message,
                            dependentModNames.joinToString(", ")
                        )
                    )
                } else {
                    setPendingOptionalModEnabled(mod, false)
                }
            }
        } catch (error: Throwable) {
            emitSnackbar(
                host.getString(
                    R.string.main_mod_toggle_failed,
                    error.message ?: host.getString(R.string.feedback_unknown_error)
                )
            )
        }
        hostCallbacks.republish(host)
    }

    fun onTogglePriorityLoad(host: Activity, mod: ModItemUi, enabled: Boolean) {
        if (!hostCallbacks.canEditMainScreenState() || mod.required || !mod.installed) {
            return
        }
        if (!enabled && mod.priorityLoad && !mod.priorityRoot) {
            emitDialog(
                title = host.getString(R.string.main_priority_remove_blocked_title),
                message = host.getString(R.string.main_priority_remove_blocked_message)
            )
            return
        }

        val targetKey = resolveStoredOptionalModId(mod)
        if (targetKey.isNullOrBlank()) {
            emitSnackbar(host.getString(R.string.main_priority_unknown_mod))
            return
        }

        val beforePriorityState = resolveOptionalModsWithPendingSelection().associate { optionalMod ->
            optionalMod.storagePath to optionalMod.priorityLoad
        }
        try {
            ModManager.setOptionalModPriorityRoot(host, targetKey, enabled)
            refresh(host, storageAccessible = true)
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
                emitSnackbar(
                    when {
                        autoMarkedNames.isNotEmpty() ->
                            host.getString(
                                R.string.main_priority_enabled_with_dependencies,
                                resolveModDisplayName(mod),
                                autoMarkedNames.joinToString(", ")
                            )

                        else -> host.getString(
                            R.string.main_priority_enabled,
                            resolveModDisplayName(mod)
                        )
                    }
                )
            } else {
                emitSnackbar(
                    if (updated?.priorityLoad == true) {
                        host.getString(R.string.main_priority_cleared_but_required)
                    } else {
                        host.getString(
                            R.string.main_priority_disabled,
                            resolveModDisplayName(mod)
                        )
                    }
                )
            }
        } catch (error: Throwable) {
            emitSnackbar(
                host.getString(
                    R.string.main_priority_failed,
                    error.message ?: host.getString(R.string.feedback_unknown_error)
                )
            )
        }
        hostCallbacks.republish(host)
    }

    fun findEnabledDuplicateModIdGroups(optionalMods: List<ModItemUi>): Map<String, List<ModItemUi>> {
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

    fun findEnabledMtsLaunchValidationIssues(optionalMods: List<ModItemUi>): List<MainMtsLaunchValidationIssue> {
        val issues = ArrayList<MainMtsLaunchValidationIssue>()
        optionalMods.forEach { mod ->
            if (!mod.enabled || !mod.installed) {
                return@forEach
            }
            val failure = MtsLaunchManifestValidator.validateModJar(File(mod.storagePath))
                ?: return@forEach
            issues.add(
                MainMtsLaunchValidationIssue(
                    mod = mod,
                    reason = failure.reason
                )
            )
        }
        return issues
    }

    private fun startModJarImport(
        host: Activity,
        uris: List<Uri>,
        replaceExistingDuplicates: Boolean = false,
        skipDuplicateCheck: Boolean = false
    ) {
        setBusyText(
            busy = true,
            message = host.getString(R.string.mod_import_busy_message),
            operation = UiBusyOperation.MOD_IMPORT
        )
        executor.execute {
            try {
                if (!skipDuplicateCheck) {
                    val duplicateConflicts = SettingsFileService.findDuplicateModImportConflicts(host, uris)
                    if (duplicateConflicts.isNotEmpty()) {
                        host.runOnUiThread {
                            setBusy(false, null)
                            showDuplicateModImportDialog(host, uris, duplicateConflicts)
                        }
                        return@execute
                    }
                }
                val batchResult = SettingsFileService.importModJars(
                    host = host,
                    uris = uris,
                    replaceExistingDuplicates = replaceExistingDuplicates
                )
                val importedCount = batchResult.importedCount
                val failedCount = batchResult.failedCount
                val blockedCount = batchResult.blockedCount
                val compressedArchiveCount = batchResult.compressedArchiveCount
                val firstError = batchResult.firstError
                val blockedList = batchResult.blockedComponents
                val compressedArchiveList = batchResult.compressedArchives
                val invalidModJars = batchResult.invalidModJars
                val patchedResults = batchResult.patchedResults
                host.runOnUiThread {
                    setBusy(false, null)
                    if (blockedList.isNotEmpty()) {
                        AlertDialog.Builder(host)
                            .setTitle(R.string.mod_import_dialog_reserved_title)
                            .setMessage(
                                SettingsFileService.buildReservedModImportMessage(
                                    context = host,
                                    blockedComponents = blockedList
                                )
                            )
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    if (invalidModJars.isNotEmpty()) {
                        showInvalidModImportDialog(host, invalidModJars)
                    }
                    if (compressedArchiveList.isNotEmpty()) {
                        showCompressedArchiveWarningDialog(host, compressedArchiveList)
                    }
                    if (patchedResults.isNotEmpty()) {
                        showAtlasPatchSummaryDialog(host, patchedResults)
                        showManifestRootPatchSummaryDialog(host, patchedResults)
                        showDownfallPatchSummaryDialog(host, patchedResults)
                        showVupShionPatchSummaryDialog(host, patchedResults)
                    }
                    when {
                        importedCount > 0 && failedCount == 0 -> {
                            emitSnackbar(host.getString(R.string.mod_import_result_success, importedCount))
                        }

                        importedCount > 0 -> {
                            emitSnackbar(
                                host.getString(
                                    R.string.mod_import_result_partial,
                                    importedCount,
                                    failedCount,
                                    resolveModImportErrorMessage(host, firstError)
                                )
                            )
                        }

                        failedCount > 0 && invalidModJars.isEmpty() -> {
                            emitSnackbar(
                                host.getString(
                                    R.string.mod_import_result_failed,
                                    resolveModImportErrorMessage(host, firstError)
                                )
                            )
                        }

                        blockedCount > 0 -> {
                            emitSnackbar(
                                host.getString(
                                    R.string.mod_import_result_blocked_builtin,
                                    blockedCount
                                )
                            )
                        }

                        compressedArchiveCount > 0 -> {
                            emitSnackbar(host.getString(R.string.mod_import_result_archive_detected))
                        }
                    }
                    refresh(host, storageAccessible = true)
                    hostCallbacks.republish(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    emitSnackbar(
                        host.getString(
                            R.string.mod_import_result_failed,
                            resolveModImportErrorMessage(host, error.message ?: error.javaClass.simpleName)
                        )
                    )
                    refresh(host, storageAccessible = true)
                    hostCallbacks.republish(host)
                }
            }
        }
    }

    private fun showInvalidModImportDialog(
        host: Activity,
        invalidModJars: List<InvalidModImportFailure>
    ) {
        AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_invalid_title)
            .setMessage(
                SettingsFileService.buildInvalidModImportMessage(
                    context = host,
                    failures = invalidModJars
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showDuplicateModImportDialog(
        host: Activity,
        uris: List<Uri>,
        duplicateConflicts: List<DuplicateModImportConflict>
    ) {
        AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_duplicate_title)
            .setMessage(
                SettingsFileService.buildDuplicateModImportMessage(
                    context = host,
                    conflicts = duplicateConflicts
                )
            )
            .setNegativeButton(R.string.mod_import_dialog_duplicate_cancel) { _, _ ->
                emitSnackbar(host.getString(R.string.mod_import_cancelled))
            }
            .setNeutralButton(R.string.mod_import_dialog_duplicate_replace_existing) { _, _ ->
                startModJarImport(
                    host = host,
                    uris = uris,
                    replaceExistingDuplicates = true,
                    skipDuplicateCheck = true
                )
            }
            .setPositiveButton(R.string.mod_import_dialog_duplicate_keep_both) { _, _ ->
                startModJarImport(host, uris, skipDuplicateCheck = true)
            }
            .setOnCancelListener {
                emitSnackbar(host.getString(R.string.mod_import_cancelled))
            }
            .show()
    }

    private fun showAtlasPatchSummaryDialog(host: Activity, patchedResults: List<ModImportResult>) {
        if (patchedResults.none { it.wasAtlasPatched }) {
            return
        }
        AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_atlas_patched_title)
            .setMessage(
                SettingsFileService.buildAtlasPatchImportSummaryMessage(
                    context = host,
                    patchedResults = patchedResults
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showManifestRootPatchSummaryDialog(host: Activity, patchedResults: List<ModImportResult>) {
        if (patchedResults.none { it.wasManifestRootPatched }) {
            return
        }
        AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_manifest_root_patched_title)
            .setMessage(
                SettingsFileService.buildManifestRootPatchImportSummaryMessage(
                    context = host,
                    patchedResults = patchedResults
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showDownfallPatchSummaryDialog(host: Activity, patchedResults: List<ModImportResult>) {
        if (patchedResults.none { it.wasDownfallPatched }) {
            return
        }
        AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_downfall_patched_title)
            .setMessage(
                SettingsFileService.buildDownfallPatchImportSummaryMessage(
                    context = host,
                    patchedResults = patchedResults
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showVupShionPatchSummaryDialog(host: Activity, patchedResults: List<ModImportResult>) {
        if (patchedResults.none { it.wasVupShionPatched }) {
            return
        }
        VupShionPatchedDialog.show(host)
    }

    private fun showCompressedArchiveWarningDialog(host: Activity, archiveDisplayNames: List<String>) {
        AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_archive_title)
            .setMessage(
                SettingsFileService.buildCompressedArchiveImportMessage(
                    context = host,
                    archiveDisplayNames = archiveDisplayNames
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun resolveModImportErrorMessage(host: Activity, message: String?): String {
        return message
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: host.getString(R.string.mod_import_error_unknown)
    }

    private fun resolveExportableModFile(mod: ModItemUi): File? {
        val resolvedPath = resolveExistingModStoragePath(mod.storagePath) ?: return null
        val file = File(resolvedPath)
        return file.takeIf { it.isFile }
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
            emitSnackbar(
                host.getString(
                    R.string.main_mod_auto_enabled_dependencies,
                    autoEnabled.joinToString(", ")
                )
            )
        }
        if (missingDependencies.isNotEmpty()) {
            emitDialog(
                title = host.getString(R.string.main_missing_dependencies_title),
                message = buildBatchMissingDependencyMessage(host, missingDependencies.toList())
            )
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
                host.getString(
                    R.string.main_batch_disable_blocked_item,
                    entry.key,
                    entry.value.joinToString(", ")
                )
            }
            emitDialog(
                title = host.getString(R.string.main_batch_disable_blocked_title),
                message = detail
            )
        }
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
            append(
                host.getString(
                    R.string.main_missing_dependencies_single_intro,
                    resolveModDisplayName(rootMod)
                )
            )
            append('\n')
            missing.forEach { dep ->
                append("- ").append(dep).append('\n')
            }
            append('\n')
            append(host.getString(R.string.main_missing_dependencies_footer))
        }
        emitDialog(title = host.getString(R.string.main_missing_dependencies_title), message = message)
    }

    private fun buildBatchMissingDependencyMessage(host: Activity, missingDependencies: List<String>): String {
        val missing = missingDependencies
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        return buildString {
            append(host.getString(R.string.main_missing_dependencies_batch_intro))
            append('\n')
            missing.forEach { dep ->
                append("- ").append(dep).append('\n')
            }
            append('\n')
            append(host.getString(R.string.main_missing_dependencies_footer))
        }
    }

    private fun isRequiredDependencyAvailable(host: Activity, normalizedDependency: String): Boolean {
        return when (normalizedDependency) {
            ModManager.MOD_ID_BASEMOD ->
                RuntimePaths.importedBaseModJar(host).exists() || hasBundledAsset(host, "components/mods/BaseMod.jar")

            ModManager.MOD_ID_STSLIB ->
                RuntimePaths.importedStsLibJar(host).exists() || hasBundledAsset(host, "components/mods/StSLib.jar")

            else -> true
        }
    }

    private fun hasBundledAsset(host: Activity, assetPath: String): Boolean {
        return try {
            host.assets.open(assetPath).use { true }
        } catch (_: IOException) {
            false
        }
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
        return storedId != null && pendingEnabledOptionalModIds.contains(storedId)
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

    private fun replacePendingSelectionForRenamedMod(mod: ModItemUi, newStoragePath: String) {
        val oldStoredId = resolveStoredOptionalModId(mod) ?: return
        if (!pendingEnabledOptionalModIds.remove(oldStoredId)) {
            return
        }
        pendingEnabledOptionalModIds.add(newStoragePath)
        markOptionalModsWithPendingSelectionDirty()
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

    private fun resolveModAssignmentKey(mod: ModItemUi): String? {
        val storage = mod.storagePath.trim()
        if (storage.isNotEmpty()) {
            return storage
        }
        return resolveStoredOptionalModId(mod)
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
        io.stamethyst.ui.main.resolveAssignmentKeyCandidates(mod).forEach { candidate ->
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
            hostCallbacks.republish(host)
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
        while (defaultFolderName(index) in exists) {
            index++
        }
        return defaultFolderName(index)
    }

    private fun emitModMovedSnackbar(host: Activity, mod: ModItemUi, folderName: String) {
        val displayName = resolveModDisplayName(
            mod = mod,
            showModFileName = LauncherPreferences.readShowModFileName(host)
        )
        emitSnackbar(host.getString(R.string.main_mod_moved_to_folder, displayName, folderName))
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

    private fun defaultFolderName(index: Int): String {
        return if (Locale.getDefault().language.startsWith("zh")) {
            "文件夹$index"
        } else {
            "Folder $index"
        }
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

    private fun emitSnackbar(message: String) {
        emitSnackbar(UiText.DynamicString(message))
    }

    private fun emitSnackbar(message: UiText) {
        hostCallbacks.emitEffect(MainScreenViewModel.Effect.ShowSnackbar(message))
    }

    private fun emitDialog(title: String, message: String) {
        emitDialog(UiText.DynamicString(title), UiText.DynamicString(message))
    }

    private fun emitDialog(title: UiText, message: UiText) {
        hostCallbacks.emitEffect(MainScreenViewModel.Effect.ShowDialog(title, message))
    }

    private fun showToast(host: Activity, message: String, duration: Int) {
        Toast.makeText(host, message, duration).show()
    }

    private fun setBusyText(
        busy: Boolean,
        message: String?,
        operation: UiBusyOperation = UiBusyOperation.OTHER_BUSY,
        progressPercent: Int? = null
    ) {
        setBusy(busy, message?.let(UiText::DynamicString), operation, progressPercent)
    }

    private fun setBusy(
        busy: Boolean,
        message: UiText?,
        operation: UiBusyOperation = UiBusyOperation.OTHER_BUSY,
        progressPercent: Int? = null
    ) {
        hostCallbacks.setBusy(busy, message, operation, progressPercent)
    }
}

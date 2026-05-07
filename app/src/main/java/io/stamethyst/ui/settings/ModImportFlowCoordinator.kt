package io.stamethyst.ui.settings

import android.app.Activity
import android.app.Dialog
import android.net.Uri
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.stamethyst.R
import io.stamethyst.backend.mods.AtlasOfflineDownscaleStrategy
import io.stamethyst.ui.UiBusyOperation
import io.stamethyst.ui.UiText
import io.stamethyst.ui.VupShionPatchedDialog
import io.stamethyst.ui.main.MainFolderStateStore
import io.stamethyst.ui.main.NewlyImportedModHighlightStore
import io.stamethyst.ui.main.UNASSIGNED_FOLDER_ID
import io.stamethyst.ui.main.resolveModStoragePathCandidates
import java.util.concurrent.ExecutorService

internal object ModImportFlowCoordinator {
    private const val IMPORT_FOLDER_ROW_TITLE = "title"
    private const val IMPORT_FOLDER_ROW_SUBTITLE = "subtitle"

    private data class ImportFolderOption(
        val id: String?,
        val name: String
    )

    private data class ImportedModFolderSelection(
        val result: ModImportResult,
        var folderId: String?
    )

    data class Callbacks(
        val setBusy: (Boolean, UiText?, UiBusyOperation, Int?) -> Unit,
        val showNotice: (UiText, Int) -> Unit,
        val onImportApplied: (ModBatchImportResult) -> Unit = {},
        val onFlowFinished: () -> Unit = {}
    )

    fun startModJarImport(
        host: Activity,
        executor: ExecutorService,
        uris: List<Uri>,
        callbacks: Callbacks,
        replaceExistingDuplicates: Boolean = false,
        duplicateReplaceOptions: DuplicateModImportReplaceOptions = DuplicateModImportReplaceOptions(),
        skipDuplicateCheck: Boolean = false,
        importAtlasDownscaleStrategy: AtlasOfflineDownscaleStrategy? = null,
        skipAtlasDownscalePrompt: Boolean = false
    ) {
        callbacks.setBusy(
            true,
            UiText.StringResource(R.string.mod_import_busy_message),
            UiBusyOperation.MOD_IMPORT,
            null
        )
        executor.execute {
            try {
                if (!skipDuplicateCheck) {
                    val duplicateConflicts = SettingsFileService.findDuplicateModImportConflicts(host, uris)
                    if (duplicateConflicts.isNotEmpty()) {
                        host.runOnUiThread {
                            clearBusy(callbacks)
                            showDuplicateModImportDialog(
                                host = host,
                                executor = executor,
                                uris = uris,
                                duplicateConflicts = duplicateConflicts,
                                callbacks = callbacks
                            )
                        }
                        return@execute
                    }
                }
                if (!skipAtlasDownscalePrompt) {
                    val downscaleCandidates = SettingsFileService.findImportAtlasDownscaleCandidates(
                        host,
                        uris
                    )
                    if (downscaleCandidates.isNotEmpty()) {
                        host.runOnUiThread {
                            clearBusy(callbacks)
                            showAtlasDownscaleConfirmDialog(
                                host = host,
                                executor = executor,
                                uris = uris,
                                candidates = downscaleCandidates,
                                callbacks = callbacks,
                                replaceExistingDuplicates = replaceExistingDuplicates,
                                duplicateReplaceOptions = duplicateReplaceOptions
                            )
                        }
                        return@execute
                    }
                }
                val batchResult = SettingsFileService.importModJars(
                    host = host,
                    uris = uris,
                    replaceExistingDuplicates = replaceExistingDuplicates,
                    duplicateReplaceOptions = duplicateReplaceOptions,
                    importAtlasDownscaleStrategy = importAtlasDownscaleStrategy
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
                NewlyImportedModHighlightStore.mark(batchResult.importedResults.map { it.storagePath })
                host.runOnUiThread {
                    clearBusy(callbacks)
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
                        showAtlasDownscaleSummaryDialog(host, patchedResults)
                        showManifestRootPatchSummaryDialog(host, patchedResults)
                        showFrierenPatchSummaryDialog(host, patchedResults)
                        showDownfallPatchSummaryDialog(host, patchedResults)
                        showVupShionPatchSummaryDialog(host, patchedResults)
                        showJacketNoAnoKoPatchSummaryDialog(host, patchedResults)
                    }
                    when {
                        importedCount > 0 && failedCount == 0 -> {
                            callbacks.showNotice(
                                UiText.StringResource(R.string.mod_import_result_success, importedCount),
                                Toast.LENGTH_SHORT
                            )
                        }

                        importedCount > 0 -> {
                            callbacks.showNotice(
                                UiText.StringResource(
                                    R.string.mod_import_result_partial,
                                    importedCount,
                                    failedCount,
                                    resolveErrorMessage(host, firstError)
                                ),
                                Toast.LENGTH_LONG
                            )
                        }

                        failedCount > 0 && invalidModJars.isEmpty() -> {
                            callbacks.showNotice(
                                UiText.StringResource(
                                    R.string.mod_import_result_failed,
                                    resolveErrorMessage(host, firstError)
                                ),
                                Toast.LENGTH_LONG
                            )
                        }

                        blockedCount > 0 -> {
                            callbacks.showNotice(
                                UiText.StringResource(
                                    R.string.mod_import_result_blocked_builtin,
                                    blockedCount
                                ),
                                Toast.LENGTH_SHORT
                            )
                        }

                        compressedArchiveCount > 0 -> {
                            callbacks.showNotice(
                                UiText.StringResource(R.string.mod_import_result_archive_detected),
                                Toast.LENGTH_SHORT
                            )
                        }
                    }
                    showImportedModFolderPickerIfNeeded(
                        host = host,
                        importedResults = batchResult.importedResults,
                        onComplete = {
                            callbacks.onImportApplied(batchResult)
                            if (!host.isFinishing && !host.isDestroyed) {
                                callbacks.onFlowFinished()
                            }
                        }
                    )
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    clearBusy(callbacks)
                    callbacks.showNotice(
                        UiText.StringResource(
                            R.string.mod_import_result_failed,
                            resolveErrorMessage(host, error.message ?: error.javaClass.simpleName)
                        ),
                        Toast.LENGTH_LONG
                    )
                    callbacks.onImportApplied(emptyModBatchImportResult())
                    callbacks.onFlowFinished()
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
        executor: ExecutorService,
        uris: List<Uri>,
        duplicateConflicts: List<DuplicateModImportConflict>,
        callbacks: Callbacks
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
                showCancelled(callbacks)
            }
            .setNeutralButton(R.string.mod_import_dialog_duplicate_replace_existing) { _, _ ->
                showDuplicateModImportReplaceOptionsDialog(
                    host = host,
                    executor = executor,
                    uris = uris,
                    callbacks = callbacks
                )
            }
            .setPositiveButton(R.string.mod_import_dialog_duplicate_keep_both) { _, _ ->
                startModJarImport(
                    host = host,
                    executor = executor,
                    uris = uris,
                    callbacks = callbacks,
                    skipDuplicateCheck = true
                )
            }
            .setOnCancelListener {
                showCancelled(callbacks)
            }
            .show()
    }

    private fun showDuplicateModImportReplaceOptionsDialog(
        host: Activity,
        executor: ExecutorService,
        uris: List<Uri>,
        callbacks: Callbacks
    ) {
        val padding = (host.resources.displayMetrics.density * 24).toInt()
        val verticalSpacing = (host.resources.displayMetrics.density * 8).toInt()
        val container = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        val moveToPreviousFolder = CheckBox(host).apply {
            setText(R.string.mod_import_dialog_duplicate_replace_move_to_previous_folder)
            isChecked = true
        }
        val renameToPreviousFileName = CheckBox(host).apply {
            setText(R.string.mod_import_dialog_duplicate_replace_rename_to_previous_file_name)
            isChecked = true
        }
        val layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = verticalSpacing
        }
        container.addView(moveToPreviousFolder, layoutParams)
        container.addView(renameToPreviousFileName)

        AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_duplicate_replace_options_title)
            .setMessage(R.string.mod_import_dialog_duplicate_replace_options_message)
            .setView(container)
            .setNegativeButton(R.string.mod_import_dialog_duplicate_cancel) { _, _ ->
                showCancelled(callbacks)
            }
            .setPositiveButton(R.string.mod_import_dialog_duplicate_replace_options_confirm) { _, _ ->
                startModJarImport(
                    host = host,
                    executor = executor,
                    uris = uris,
                    callbacks = callbacks,
                    replaceExistingDuplicates = true,
                    duplicateReplaceOptions = DuplicateModImportReplaceOptions(
                        moveToPreviousFolder = moveToPreviousFolder.isChecked,
                        renameToPreviousFileName = renameToPreviousFileName.isChecked
                    ),
                    skipDuplicateCheck = true
                )
            }
            .setOnCancelListener {
                showCancelled(callbacks)
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

    private fun showAtlasDownscaleConfirmDialog(
        host: Activity,
        executor: ExecutorService,
        uris: List<Uri>,
        candidates: List<ModImportAtlasDownscalePreview>,
        callbacks: Callbacks,
        replaceExistingDuplicates: Boolean,
        duplicateReplaceOptions: DuplicateModImportReplaceOptions
    ) {
        AtlasDownscaleImportDialog.show(
            host = host,
            previews = candidates,
            onApply = { strategy ->
                startModJarImport(
                    host = host,
                    executor = executor,
                    uris = uris,
                    callbacks = callbacks,
                    replaceExistingDuplicates = replaceExistingDuplicates,
                    duplicateReplaceOptions = duplicateReplaceOptions,
                    skipDuplicateCheck = true,
                    importAtlasDownscaleStrategy = strategy,
                    skipAtlasDownscalePrompt = true
                )
            },
            onSkip = {
                startModJarImport(
                    host = host,
                    executor = executor,
                    uris = uris,
                    callbacks = callbacks,
                    replaceExistingDuplicates = replaceExistingDuplicates,
                    duplicateReplaceOptions = duplicateReplaceOptions,
                    skipDuplicateCheck = true,
                    importAtlasDownscaleStrategy = null,
                    skipAtlasDownscalePrompt = true
                )
            },
            onCancel = {
                showCancelled(callbacks)
            }
        )
    }

    private fun showAtlasDownscaleSummaryDialog(host: Activity, patchedResults: List<ModImportResult>) {
        if (patchedResults.none { it.wasAtlasDownscaled }) {
            return
        }
        AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_atlas_downscale_title)
            .setMessage(
                SettingsFileService.buildAtlasDownscaleImportSummaryMessage(
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

    private fun showFrierenPatchSummaryDialog(host: Activity, patchedResults: List<ModImportResult>) {
        if (patchedResults.none { it.wasFrierenAntiPiratePatched }) {
            return
        }
        AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_frieren_patched_title)
            .setMessage(
                SettingsFileService.buildFrierenPatchImportSummaryMessage(
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

    private fun showJacketNoAnoKoPatchSummaryDialog(
        host: Activity,
        patchedResults: List<ModImportResult>
    ) {
        if (patchedResults.none { it.wasJacketNoAnoKoPatched }) {
            return
        }
        AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_jacketnoanoko_patched_title)
            .setMessage(
                SettingsFileService.buildJacketNoAnoKoPatchImportSummaryMessage(
                    context = host,
                    patchedResults = patchedResults
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
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

    private fun showImportedModFolderPickerIfNeeded(
        host: Activity,
        importedResults: List<ModImportResult>,
        onComplete: () -> Unit
    ) {
        if (host.isFinishing || host.isDestroyed) {
            return
        }
        val importedMods = importedResults
            .filter { it.storagePath.trim().isNotEmpty() }
            .filterNot { it.folderPlacementHandledByDuplicateReuse }
            .distinctBy { it.storagePath.trim() }
        if (importedMods.isEmpty()) {
            onComplete()
            return
        }

        val folderStateStore = MainFolderStateStore().apply { ensureLoaded(host) }
        if (folderStateStore.folders.isEmpty()) {
            importedMods.forEach { result ->
                clearImportedModFolderAssignment(folderStateStore, result.storagePath)
            }
            folderStateStore.unassignedIsCollapsed = false
            folderStateStore.persist(host)
            onComplete()
            return
        }

        showImportedModFolderListPicker(
            host = host,
            folderStateStore = folderStateStore,
            importedMods = importedMods,
            onComplete = onComplete
        )
    }

    private fun showImportedModFolderListPicker(
        host: Activity,
        folderStateStore: MainFolderStateStore,
        importedMods: List<ModImportResult>,
        onComplete: () -> Unit
    ) {
        if (host.isFinishing || host.isDestroyed) {
            return
        }
        val folderOptions = buildImportFolderOptions(folderStateStore)
        val selections = importedMods.map { result ->
            ImportedModFolderSelection(result = result, folderId = null)
        }
        val adapterItems = buildImportFolderSelectionLabels(host, selections, folderOptions).toMutableList()
        val adapter = SimpleAdapter(
            host,
            adapterItems,
            android.R.layout.simple_list_item_2,
            arrayOf(IMPORT_FOLDER_ROW_TITLE, IMPORT_FOLDER_ROW_SUBTITLE),
            intArrayOf(android.R.id.text1, android.R.id.text2)
        )
        val listView = ListView(host).apply {
            dividerHeight = 1
            this.adapter = adapter
        }
        val padding = (host.resources.displayMetrics.density * 20).toInt()
        listView.setPadding(padding, 0, padding, 0)

        fun refreshLabels() {
            adapterItems.clear()
            adapterItems.addAll(buildImportFolderSelectionLabels(host, selections, folderOptions))
            adapter.notifyDataSetChanged()
        }

        fun applyChoices(applySelections: Boolean) {
            applyImportedModFolderChoices(
                host = host,
                folderStateStore = folderStateStore,
                selections = if (applySelections) {
                    selections
                } else {
                    selections.map { selection -> selection.copy(folderId = null) }
                }
            )
            onComplete()
        }

        var completed = false
        fun complete(applySelections: Boolean) {
            if (completed) {
                return
            }
            completed = true
            applyChoices(applySelections)
        }

        fun dismissAndComplete(dialog: Dialog, applySelections: Boolean) {
            if (completed) {
                return
            }
            completed = true
            dialog.dismiss()
            applyChoices(applySelections)
        }

        AlertDialog.Builder(host)
            .setTitle(R.string.main_import_folder_picker_list_title)
            .setMessage(R.string.main_import_folder_picker_list_message)
            .setView(listView)
            .setNegativeButton(R.string.main_import_folder_picker_skip, null)
            .setPositiveButton(R.string.main_import_folder_picker_confirm, null)
            .setOnCancelListener {
                complete(applySelections = false)
            }
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    if (!completed && !host.isFinishing && !host.isDestroyed) {
                        complete(applySelections = false)
                    }
                }
                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    dismissAndComplete(dialog, applySelections = false)
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    dismissAndComplete(dialog, applySelections = true)
                }
                listView.setOnItemClickListener { _, _, which, _ ->
                    showImportedModTargetFolderPicker(
                        host = host,
                        selection = selections[which],
                        folderOptions = folderOptions,
                        onFolderSelected = { folderId ->
                            selections[which].folderId = folderId
                            refreshLabels()
                        }
                    )
                }
            }
    }

    private fun showImportedModTargetFolderPicker(
        host: Activity,
        selection: ImportedModFolderSelection,
        folderOptions: List<ImportFolderOption>,
        onFolderSelected: (String?) -> Unit
    ) {
        val selectedIndex = folderOptions.indexOfFirst { it.id == selection.folderId }.coerceAtLeast(0)
        AlertDialog.Builder(host)
            .setTitle(
                host.getString(
                    R.string.main_import_folder_picker_item_title_format,
                    selection.result.modName.ifBlank { selection.result.modId }
                )
            )
            .setSingleChoiceItems(folderOptions.map { it.name }.toTypedArray(), selectedIndex) { dialog, which ->
                onFolderSelected(folderOptions.getOrNull(which)?.id)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.main_folder_dialog_cancel, null)
            .show()
    }

    private fun applyImportedModFolderChoices(
        host: Activity,
        folderStateStore: MainFolderStateStore,
        selections: List<ImportedModFolderSelection>
    ) {
        selections.forEach { selection ->
            applyImportedModFolderChoice(
                host = host,
                folderStateStore = folderStateStore,
                storagePath = selection.result.storagePath,
                folderId = selection.folderId
            )
        }
    }

    private fun buildImportFolderSelectionLabels(
        host: Activity,
        selections: List<ImportedModFolderSelection>,
        folderOptions: List<ImportFolderOption>
    ): List<Map<String, String>> {
        return selections.map { selection ->
            val modName = selection.result.modName.ifBlank { selection.result.modId }
            val folderName = folderOptions.firstOrNull { it.id == selection.folderId }?.name
                ?: host.getString(R.string.main_import_folder_picker_unassigned)
            mapOf(
                IMPORT_FOLDER_ROW_TITLE to modName,
                IMPORT_FOLDER_ROW_SUBTITLE to host.getString(
                    R.string.main_import_folder_picker_item_target_format,
                    folderName
                )
            )
        }
    }

    private fun buildImportFolderOptions(folderStateStore: MainFolderStateStore): List<ImportFolderOption> {
        val folderTokens = folderStateStore.buildFolderOrderTokens()
            .filter { folderToken ->
                folderToken == UNASSIGNED_FOLDER_ID || folderStateStore.folders.any { it.id == folderToken }
            }
        val foldersById = folderStateStore.folders.associateBy { it.id }
        return folderTokens.map { folderToken ->
            if (folderToken == UNASSIGNED_FOLDER_ID) {
                ImportFolderOption(id = null, name = folderStateStore.unassignedName)
            } else {
                ImportFolderOption(
                    id = folderToken,
                    name = foldersById[folderToken]?.name ?: folderToken
                )
            }
        }
    }

    private fun applyImportedModFolderChoice(
        host: Activity,
        folderStateStore: MainFolderStateStore,
        storagePath: String,
        folderId: String?
    ) {
        var changed = clearImportedModFolderAssignment(folderStateStore, storagePath)
        val targetFolderId = folderId?.trim().orEmpty()
        if (targetFolderId.isEmpty()) {
            folderStateStore.unassignedIsCollapsed = false
        } else if (folderStateStore.folders.any { it.id == targetFolderId }) {
            val normalizedPath = storagePath.trim()
            if (normalizedPath.isNotEmpty()) {
                folderStateStore.assignments[normalizedPath] = targetFolderId
            }
            folderStateStore.collapsedMap[targetFolderId] = false
            changed = true
        } else {
            folderStateStore.unassignedIsCollapsed = false
        }
        if (changed || targetFolderId.isEmpty()) {
            folderStateStore.persist(host)
        }
    }

    private fun clearImportedModFolderAssignment(
        folderStateStore: MainFolderStateStore,
        storagePath: String
    ): Boolean {
        var changed = false
        resolveModStoragePathCandidates(storagePath).forEach { candidate ->
            if (folderStateStore.assignments.remove(candidate) != null) {
                changed = true
            }
        }
        return changed
    }

    private fun showCancelled(callbacks: Callbacks) {
        callbacks.showNotice(UiText.StringResource(R.string.mod_import_cancelled), Toast.LENGTH_SHORT)
        callbacks.onFlowFinished()
    }

    private fun clearBusy(callbacks: Callbacks) {
        callbacks.setBusy(false, null, UiBusyOperation.MOD_IMPORT, null)
    }

    private fun emptyModBatchImportResult(): ModBatchImportResult {
        return ModBatchImportResult(
            importedCount = 0,
            importedResults = emptyList(),
            errors = emptyList(),
            blockedComponents = emptyList(),
            compressedArchives = emptyList(),
            invalidModJars = emptyList(),
            patchedResults = emptyList()
        )
    }

    private fun resolveErrorMessage(host: Activity, message: String?): String {
        return message
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: host.getString(R.string.mod_import_error_unknown)
    }
}

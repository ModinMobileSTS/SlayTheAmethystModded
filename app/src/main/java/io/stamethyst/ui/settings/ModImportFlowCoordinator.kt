package io.stamethyst.ui.settings

import android.app.Activity
import android.net.Uri
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.stamethyst.R
import io.stamethyst.backend.mods.AtlasOfflineDownscaleStrategy
import io.stamethyst.ui.UiBusyOperation
import io.stamethyst.ui.UiText
import io.stamethyst.ui.VupShionPatchedDialog
import java.util.concurrent.ExecutorService

internal object ModImportFlowCoordinator {
    data class Callbacks(
        val setBusy: (Boolean, UiText?, UiBusyOperation, Int?) -> Unit,
        val showNotice: (UiText, Int) -> Unit,
        val onImportApplied: () -> Unit = {},
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
                    callbacks.onImportApplied()
                    callbacks.onFlowFinished()
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
                    callbacks.onImportApplied()
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

    private fun showCancelled(callbacks: Callbacks) {
        callbacks.showNotice(UiText.StringResource(R.string.mod_import_cancelled), Toast.LENGTH_SHORT)
        callbacks.onFlowFinished()
    }

    private fun clearBusy(callbacks: Callbacks) {
        callbacks.setBusy(false, null, UiBusyOperation.MOD_IMPORT, null)
    }

    private fun resolveErrorMessage(host: Activity, message: String?): String {
        return message
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: host.getString(R.string.mod_import_error_unknown)
    }
}

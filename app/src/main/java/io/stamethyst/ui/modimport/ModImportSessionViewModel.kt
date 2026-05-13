package io.stamethyst.ui.modimport

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.StringRes
import io.stamethyst.R
import io.stamethyst.backend.mods.AtlasOfflineDownscaleStrategy
import io.stamethyst.backend.mods.importing.DuplicateImportDecision
import io.stamethyst.backend.mods.importing.ImportPatchCategory
import io.stamethyst.backend.mods.importing.ModImportDecisions
import io.stamethyst.backend.mods.importing.ModImportExecutionProgress
import io.stamethyst.backend.mods.importing.ModImportExecutionReport
import io.stamethyst.backend.mods.importing.ModImportExecutor
import io.stamethyst.backend.mods.importing.ModImportItemPlan
import io.stamethyst.backend.mods.importing.ModImportItemStatus
import io.stamethyst.backend.mods.importing.ModImportPlan
import io.stamethyst.backend.mods.importing.ModImportPlanner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal enum class ModImportStep {
    Scanning,
    Review,
    Duplicates,
    Patches,
    Confirm,
    Executing,
    Result
}

internal data class ModImportUiState(
    val visible: Boolean = false,
    val step: ModImportStep = ModImportStep.Scanning,
    val scanning: Boolean = false,
    val plan: ModImportPlan? = null,
    val decisions: ModImportDecisions = ModImportDecisions(),
    val progress: ModImportExecutionProgress? = null,
    val report: ModImportExecutionReport? = null,
    val errorMessage: String? = null
) {
    val canImport: Boolean
        get() = plan?.importableItems?.any { item ->
            val conflictKey = item.duplicateConflictKey
            conflictKey == null || decisions.duplicateDecisionFor(conflictKey) != DuplicateImportDecision.SkipNew
        } == true
}

@Stable
internal class ModImportSessionViewModel : ViewModel() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile
    private var activeRequestToken: Long = 0L

    var uiState by mutableStateOf(ModImportUiState())
        private set

    private fun updateState(transform: (ModImportUiState) -> ModImportUiState) {
        viewModelScope.launch(Dispatchers.Main) {
            uiState = transform(uiState)
        }
    }

    fun start(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        uiState = ModImportUiState(visible = true, step = ModImportStep.Scanning, scanning = true)
        val requestToken = System.nanoTime()
        activeRequestToken = requestToken
        executor.execute {
            try {
                val plan = ModImportPlanner.plan(context, uris)
                if (activeRequestToken != requestToken) {
                    ModImportPlanner.cleanup(plan.session)
                    return@execute
                }
                val initialDecisions = buildInitialDecisions(plan)
                updateState { state ->
                    if (activeRequestToken != requestToken) return@updateState state
                    state.copy(
                        step = ModImportStep.Review,
                        scanning = false,
                        plan = plan,
                        decisions = initialDecisions,
                        errorMessage = null
                    )
                }
            } catch (error: Throwable) {
                updateState { state ->
                    if (activeRequestToken != requestToken) return@updateState state
                    state.copy(
                        step = ModImportStep.Result,
                        scanning = false,
                        errorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }
    }

    fun dismiss() {
        activeRequestToken = 0L
        uiState.plan?.let { ModImportPlanner.cleanup(it.session) }
        uiState = ModImportUiState()
    }

    fun next() {
        val state = uiState
        val plan = state.plan ?: return
        uiState = when (state.step) {
            ModImportStep.Review -> state.copy(step = if (plan.duplicateConflicts.isNotEmpty()) {
                ModImportStep.Duplicates
            } else if (hasConfigurablePatches(plan)) {
                ModImportStep.Patches
            } else {
                ModImportStep.Confirm
            })
            ModImportStep.Duplicates -> state.copy(step = if (hasConfigurablePatches(plan)) {
                ModImportStep.Patches
            } else {
                ModImportStep.Confirm
            })
            ModImportStep.Patches -> state.copy(step = ModImportStep.Confirm)
            else -> state
        }
    }

    fun back() {
        val state = uiState
        val plan = state.plan ?: return
        uiState = when (state.step) {
            ModImportStep.Duplicates -> state.copy(step = ModImportStep.Review)
            ModImportStep.Patches -> state.copy(step = if (plan.duplicateConflicts.isNotEmpty()) {
                ModImportStep.Duplicates
            } else {
                ModImportStep.Review
            })
            ModImportStep.Confirm -> state.copy(step = if (hasConfigurablePatches(plan)) {
                ModImportStep.Patches
            } else if (plan.duplicateConflicts.isNotEmpty()) {
                ModImportStep.Duplicates
            } else {
                ModImportStep.Review
            })
            else -> state
        }
    }

    fun setDuplicateDecision(modId: String, decision: DuplicateImportDecision) {
        val current = uiState.decisions
        uiState = uiState.copy(
            decisions = current.copy(
                duplicateDecisions = current.duplicateDecisions + (modId to decision)
            )
        )
    }

    fun setReusePreviousFileName(enabled: Boolean) {
        uiState = uiState.copy(
            decisions = uiState.decisions.copy(reusePreviousFileNameOnReplace = enabled)
        )
    }

    fun setReusePreviousFolder(enabled: Boolean) {
        uiState = uiState.copy(
            decisions = uiState.decisions.copy(reusePreviousFolderOnReplace = enabled)
        )
    }

    fun setPatchEnabled(itemId: String, moduleId: String, enabled: Boolean) {
        val current = uiState.decisions
        val key = ModImportDecisions.patchDecisionKey(itemId, moduleId)
        uiState = uiState.copy(
            decisions = current.copy(patchEnabledByKey = current.patchEnabledByKey + (key to enabled))
        )
    }

    fun setAtlasDownscaleStrategy(strategy: AtlasOfflineDownscaleStrategy?) {
        uiState = uiState.copy(decisions = uiState.decisions.copy(atlasDownscaleStrategy = strategy))
    }

    fun execute(context: Context, onCompleted: () -> Unit = {}) {
        val plan = uiState.plan ?: return
        val decisions = uiState.decisions
        if (!uiState.canImport) return
        val requestToken = activeRequestToken
        uiState = uiState.copy(step = ModImportStep.Executing, progress = null, report = null)
        executor.execute {
            val report = ModImportExecutor.execute(
                context = context,
                plan = plan,
                decisions = decisions,
                onProgress = { progress ->
                    updateState { state ->
                        if (activeRequestToken != requestToken) state else state.copy(progress = progress)
                    }
                }
            )
            updateState { state ->
                if (activeRequestToken != requestToken) {
                    state
                } else {
                    state.copy(step = ModImportStep.Result, report = report, progress = null)
                }
            }
            viewModelScope.launch(Dispatchers.Main) {
                if (activeRequestToken == requestToken) {
                    onCompleted()
                }
            }
        }
    }

    override fun onCleared() {
        activeRequestToken = 0L
        uiState.plan?.let { ModImportPlanner.cleanup(it.session) }
        executor.shutdownNow()
        super.onCleared()
    }

    private fun buildInitialDecisions(plan: ModImportPlan): ModImportDecisions {
        val duplicateDecisions = plan.duplicateConflicts.associate {
            it.normalizedModId to DuplicateImportDecision.KeepMultiple
        }
        val patchEnabled = LinkedHashMap<String, Boolean>()
        plan.importableItems.forEach { item ->
            item.patchPlans.forEach { patch ->
                patchEnabled[ModImportDecisions.patchDecisionKey(item.id, patch.moduleId)] = patch.defaultEnabled
            }
        }
        return ModImportDecisions(
            duplicateDecisions = duplicateDecisions,
            patchEnabledByKey = patchEnabled,
            atlasDownscaleStrategy = AtlasOfflineDownscaleStrategy.maxEdge(
                AtlasOfflineDownscaleStrategy.DEFAULT_MAX_EDGE_PX
            )
        )
    }

    private fun hasConfigurablePatches(plan: ModImportPlan): Boolean {
        return plan.importableItems.any { item ->
            item.patchPlans.any { it.userConfigurable }
        }
    }
}

@StringRes
internal fun ModImportItemPlan.statusLabelResId(): Int {
    return when (status) {
        ModImportItemStatus.IMPORTABLE -> R.string.mod_import_status_ready
        ModImportItemStatus.NEEDS_DECISION -> R.string.mod_import_status_needs_decision
        ModImportItemStatus.SKIPPED -> R.string.mod_import_status_skipped
        ModImportItemStatus.BLOCKED -> R.string.mod_import_status_blocked
    }
}

@StringRes
internal fun ImportPatchCategory.displayLabelResId(): Int {
    return when (this) {
        ImportPatchCategory.Structural -> R.string.mod_import_patch_category_structural
        ImportPatchCategory.Texture -> R.string.mod_import_patch_category_texture
        ImportPatchCategory.Shader -> R.string.mod_import_patch_category_shader
        ImportPatchCategory.Bytecode -> R.string.mod_import_patch_category_bytecode
        ImportPatchCategory.ModSpecific -> R.string.mod_import_patch_category_mod_specific
        ImportPatchCategory.Safety -> R.string.mod_import_patch_category_safety
    }
}

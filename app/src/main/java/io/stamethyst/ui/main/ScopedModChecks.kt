package io.stamethyst.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.LauncherDialog
import io.stamethyst.ui.LocalLauncherBannerPage
import io.stamethyst.ui.workshop.WorkshopViewModel

/** Catalogue fetches are shared; only entries belonging to the requested mods are displayed. */
@Composable
internal fun ScopedCommunityCheckDialog(mods: List<ModItemUi>?, onDismiss: () -> Unit) {
    val workshopViewModel: WorkshopViewModel = viewModel()
    var detailId by remember { mutableStateOf<String?>(null) }
    val ids = remember(mods) {
        mods?.flatMap { listOf(it.modId, it.manifestModId) }?.filter { it.isNotBlank() }?.toSet()
    }
    LauncherDialog(onDismissRequest = onDismiss) {
        // A modal owns its top bar. Inheriting the underlying page's banner would
        // replace that page's registered toolbar and clear it on modal disposal.
        CompositionLocalProvider(LocalLauncherBannerPage provides null) {
        CommunityPatchesScreen(
            workshopViewModel = workshopViewModel,
            modId = detailId,
            scopeModIds = ids,
            refreshOnOpen = true,
            onBack = { if (detailId != null) detailId = null else onDismiss() },
            onOpenMod = { detailId = it },
        )
        }
    }
}

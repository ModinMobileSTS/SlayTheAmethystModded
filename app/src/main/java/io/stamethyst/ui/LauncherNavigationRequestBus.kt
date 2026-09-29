package io.stamethyst.ui

import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.navigation.Route
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal object LauncherNavigationRequestBus {
    private val mutableWorkshopDetailRequests = MutableSharedFlow<WorkshopItemSummary>(
        extraBufferCapacity = 1,
    )

    val workshopDetailRequests = mutableWorkshopDetailRequests.asSharedFlow()

    private val mutableResourcePackRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
    )

    val resourcePackRequests = mutableResourcePackRequests.asSharedFlow()

    fun requestWorkshopDetail(item: WorkshopItemSummary) {
        mutableWorkshopDetailRequests.tryEmit(item)
    }

    fun requestResourcePack() {
        mutableResourcePackRequests.tryEmit(Unit)
    }

    private val mutableModsRefreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val modsRefreshRequests = mutableModsRefreshRequests.asSharedFlow()

    fun requestModsRefresh() {
        mutableModsRefreshRequests.tryEmit(Unit)
    }

    private val mutableAiEditorRequests = MutableSharedFlow<Route.AiModEditor>(
        extraBufferCapacity = 1,
    )
    val aiEditorRequests = mutableAiEditorRequests.asSharedFlow()

    fun requestAiEditor(route: Route.AiModEditor) {
        mutableAiEditorRequests.tryEmit(route)
    }

}

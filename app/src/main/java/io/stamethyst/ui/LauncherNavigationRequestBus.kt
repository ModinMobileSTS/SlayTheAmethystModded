package io.stamethyst.ui

import io.stamethyst.backend.workshop.WorkshopItemSummary
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
}

package io.stamethyst.model

import io.stamethyst.backend.workshop.WorkshopResolvedModStateKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The mod page ([WorkshopModState]) and the market list
 * (`io.stamethyst.ui.workshop.WorkshopModDownloadState`) both narrow
 * [WorkshopResolvedModStateKind]. When the mod page enum was missing members it had to fold
 * distinct kinds together, which is how "queued" and "cancelling" ended up rendering as an
 * active download and how "not downloaded" ended up rendering as "download failed".
 *
 * This test asserts the mod page enum can represent every resolver kind, so no future kind can
 * be silently collapsed onto an unrelated state.
 */
class WorkshopModStateParityTest {
    @Test
    fun everyResolverKindHasADedicatedModPageState() {
        val modPageStateNames = WorkshopModState.entries.map { it.name }.toSet()
        val unrepresented = WorkshopResolvedModStateKind.entries
            .map { it.name }
            .filterNot { it in modPageStateNames }

        assertEquals(
            "WorkshopModState is missing a 1:1 counterpart for these resolver kinds, " +
                "which forces a lossy mapping on the mod page",
            emptyList<String>(),
            unrepresented,
        )
    }

    @Test
    fun modPageStatesDoNotInventStatesTheResolverCannotProduce() {
        val resolverKindNames = WorkshopResolvedModStateKind.entries.map { it.name }.toSet()
        val unreachable = WorkshopModState.entries
            .map { it.name }
            .filterNot { it in resolverKindNames }

        assertEquals(
            "these WorkshopModState members cannot be produced by the resolver and are dead",
            emptyList<String>(),
            unreachable,
        )
    }

    @Test
    fun downloadBlockedDefaultsToFalseAndIsCarriedOnTheModel() {
        // The mod page needs this flag to mirror the market's Unavailable treatment; a blocked
        // item must not offer a retry button that the download service silently cancels.
        val plain = WorkshopModUi(appId = 646570u, publishedFileId = 1234uL, state = WorkshopModState.DownloadFailed)
        assertEquals(false, plain.downloadBlocked)
        assertEquals(true, plain.copy(downloadBlocked = true).downloadBlocked)
    }
}

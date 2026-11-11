package io.stamethyst.ui.main

/** Existing groups may accept unassociated selections; fully associated selections stay locked. */
internal fun canAssociateSelection(selectedCount: Int, associatedCount: Int): Boolean =
    selectedCount >= 2 && associatedCount in 0 until selectedCount

package io.stamethyst.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchAssociationPolicyTest {
    @Test fun mixedSelectionCanJoinExistingGroup() {
        assertTrue(canAssociateSelection(3, 2))
        assertTrue(canAssociateSelection(2, 1))
    }

    @Test fun unassociatedSelectionCanCreateGroup() {
        assertTrue(canAssociateSelection(2, 0))
    }

    @Test fun fullyAssociatedSelectionStaysDisabled() {
        assertFalse(canAssociateSelection(2, 2))
        assertFalse(canAssociateSelection(3, 3))
    }

    @Test fun fewerThanTwoModsCannotFormGroup() {
        assertFalse(canAssociateSelection(0, 0))
        assertFalse(canAssociateSelection(1, 0))
        assertFalse(canAssociateSelection(1, 1))
    }
}

package io.stamethyst.ui.settings.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusRefreshBusyTrackerTest {
    @Test
    fun nonClearingRefreshInheritsClearRequestFromSupersededRefresh() {
        val tracker = SettingsScreenViewModel.StatusRefreshBusyTracker()

        assertTrue(tracker.begin(clearBusy = true))
        assertTrue(tracker.begin(clearBusy = false))
    }

    @Test
    fun completingRefreshConsumesClearRequest() {
        val tracker = SettingsScreenViewModel.StatusRefreshBusyTracker()

        tracker.begin(clearBusy = true)
        tracker.complete()

        assertFalse(tracker.begin(clearBusy = false))
    }
}

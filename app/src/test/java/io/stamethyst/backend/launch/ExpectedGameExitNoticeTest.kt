package io.stamethyst.backend.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpectedGameExitNoticeTest {
    @Test
    fun parseMarkerTimestamp_readsPlainTimestamp() {
        assertEquals(1234L, ExpectedGameExitNotice.parseMarkerTimestamp("1234"))
    }

    @Test
    fun parseMarkerTimestamp_readsStructuredTimestamp() {
        val marker = "source=main_menu_quit\ntimestampMs=5678\n"

        assertEquals(5678L, ExpectedGameExitNotice.parseMarkerTimestamp(marker))
    }

    @Test
    fun parseMarkerTimestamp_returnsNullForInvalidMarker() {
        assertNull(ExpectedGameExitNotice.parseMarkerTimestamp("source=main_menu_quit"))
    }
}

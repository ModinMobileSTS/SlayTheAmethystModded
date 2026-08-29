package io.stamethyst.ui.main

import io.stamethyst.R
import io.stamethyst.backend.easytier.EasyTierFailureCategory
import io.stamethyst.backend.easytier.EasyTierConnectionStatus
import io.stamethyst.backend.easytier.EasyTierConnectionSnapshot
import io.stamethyst.backend.easytier.EasyTierNetworkMode
import io.stamethyst.backend.easytier.EasyTierProcessService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenViewModelEasyTierResultCodeTest {
    @Test
    fun resolveEasyTierRoomCreationOutcome_requiresMatchingConnectedRoom() {
        val base = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.SESSION_READY,
            mode = EasyTierNetworkMode.Room,
            roomId = "room-1",
        )

        assertEquals(
            EasyTierRoomCreationOutcome.PENDING,
            resolveEasyTierRoomCreationOutcome("room-1", base),
        )
        assertEquals(
            EasyTierRoomCreationOutcome.COMPLETED,
            resolveEasyTierRoomCreationOutcome(
                "room-1",
                base.copy(status = EasyTierConnectionStatus.CONNECTED),
            ),
        )
        assertEquals(
            EasyTierRoomCreationOutcome.PENDING,
            resolveEasyTierRoomCreationOutcome(
                "room-2",
                base.copy(status = EasyTierConnectionStatus.CONNECTED),
            ),
        )
        assertEquals(
            EasyTierRoomCreationOutcome.FAILED,
            resolveEasyTierRoomCreationOutcome(
                "room-1",
                base.copy(status = EasyTierConnectionStatus.FAILED),
            ),
        )
    }

    @Test
    fun shouldPublishEasyTierIndicatorForResultCode_includesSessionReady() {
        assertTrue(shouldPublishEasyTierIndicatorForResultCode(EasyTierProcessService.RESULT_SESSION_READY))
    }

    @Test
    fun shouldPublishEasyTierIndicatorForResultCode_ignoresUnexpectedCodes() {
        assertFalse(shouldPublishEasyTierIndicatorForResultCode(0))
        assertFalse(shouldPublishEasyTierIndicatorForResultCode(-1))
    }

    @Test
    fun shouldDisconnectEasyTierUiState_includesDisconnectingFlowStates() {
        assertTrue(shouldDisconnectEasyTierUiState(MainScreenViewModel.EasyTierIndicatorState.CONNECTING))
        assertTrue(shouldDisconnectEasyTierUiState(MainScreenViewModel.EasyTierIndicatorState.SESSION_READY))
        assertTrue(shouldDisconnectEasyTierUiState(MainScreenViewModel.EasyTierIndicatorState.CONNECTED))
        assertTrue(shouldDisconnectEasyTierUiState(MainScreenViewModel.EasyTierIndicatorState.RECONNECTING))
        assertTrue(shouldDisconnectEasyTierUiState(MainScreenViewModel.EasyTierIndicatorState.DISCONNECTING))
        assertFalse(shouldDisconnectEasyTierUiState(MainScreenViewModel.EasyTierIndicatorState.DISCONNECTED))
    }

    @Test
    fun shouldCloseEasyTierRoomWhenOwnerLeaves_requiresOwnerAndActiveRoom() {
        assertTrue(
            shouldCloseEasyTierRoomWhenOwnerLeaves(
                state = EasyTierConnectionStatus.CONNECTED,
                activeRoomId = "room-1",
                selectedRoomId = "room-1",
                ownerPlayerId = "owner-1",
                currentPlayerId = "owner-1",
            )
        )
        assertFalse(
            shouldCloseEasyTierRoomWhenOwnerLeaves(
                state = EasyTierConnectionStatus.CONNECTED,
                activeRoomId = "room-1",
                selectedRoomId = "room-1",
                ownerPlayerId = "owner-1",
                currentPlayerId = "member-1",
            )
        )
        assertFalse(
            shouldCloseEasyTierRoomWhenOwnerLeaves(
                state = EasyTierConnectionStatus.DISCONNECTED,
                activeRoomId = "room-1",
                selectedRoomId = "room-1",
                ownerPlayerId = "owner-1",
                currentPlayerId = "owner-1",
            )
        )
    }

    @Test
    fun hasEasyTierRoomOwnerCredential_requiresEitherOwnerOrSessionToken() {
        assertFalse(
            hasEasyTierRoomOwnerCredential(
                ownerToken = "",
                sessionToken = "",
            )
        )
        assertTrue(
            hasEasyTierRoomOwnerCredential(
                ownerToken = "owner-token",
                sessionToken = "",
            )
        )
        assertTrue(
            hasEasyTierRoomOwnerCredential(
                ownerToken = "",
                sessionToken = "session-token",
            )
        )
    }

    @Test
    fun shouldPreserveEasyTierRoomSelection_keepsActiveRoomOnNotFoundButCleansInactiveRoom() {
        assertTrue(
            shouldPreserveEasyTierRoomSelection(
                selectedRoomId = "room-1",
                activeRoomId = "room-1",
                activeState = EasyTierConnectionStatus.CONNECTED,
                errorStatusCode = 404,
            )
        )
        assertFalse(
            shouldPreserveEasyTierRoomSelection(
                selectedRoomId = "room-1",
                activeRoomId = "",
                activeState = EasyTierConnectionStatus.DISCONNECTED,
                errorStatusCode = 404,
            )
        )
        assertTrue(
            shouldPreserveEasyTierRoomSelection(
                selectedRoomId = "room-1",
                activeRoomId = "",
                activeState = EasyTierConnectionStatus.DISCONNECTED,
                errorStatusCode = 503,
            )
        )
    }

    @Test
    fun easyTierTroubleshootingMessageResId_showsTerminalDisconnectedCauses() {
        assertEquals(
            R.string.main_easytier_troubleshooting_room_closed,
            easyTierTroubleshootingMessageResId(
                state = MainScreenViewModel.EasyTierIndicatorState.DISCONNECTED,
                failureCategory = EasyTierFailureCategory.RoomClosed,
            )
        )
        assertEquals(
            R.string.main_easytier_troubleshooting_session_expired,
            easyTierTroubleshootingMessageResId(
                state = MainScreenViewModel.EasyTierIndicatorState.DISCONNECTED,
                failureCategory = EasyTierFailureCategory.SessionExpired,
            )
        )
    }

    @Test
    fun easyTierTroubleshootingMessageResId_ignoresCleanDisconnectedAndActiveStates() {
        assertNull(
            easyTierTroubleshootingMessageResId(
                state = MainScreenViewModel.EasyTierIndicatorState.DISCONNECTED,
                failureCategory = EasyTierFailureCategory.None,
            )
        )
        assertNull(
            easyTierTroubleshootingMessageResId(
                state = MainScreenViewModel.EasyTierIndicatorState.CONNECTED,
                failureCategory = EasyTierFailureCategory.RuntimeBridgeUnavailable,
                errorSummary = "native runtime failed to load",
            )
        )
    }

    @Test
    fun easyTierTroubleshootingMessageResId_mapsPermissionAndUnknownFailures() {
        assertEquals(
            R.string.main_easytier_troubleshooting_vpn_permission,
            easyTierTroubleshootingMessageResId(
                state = MainScreenViewModel.EasyTierIndicatorState.PERMISSION_REQUIRED,
                failureCategory = EasyTierFailureCategory.None,
            )
        )
        assertEquals(
            R.string.main_easytier_troubleshooting_unknown,
            easyTierTroubleshootingMessageResId(
                state = MainScreenViewModel.EasyTierIndicatorState.CONNECTION_FAILED,
                failureCategory = EasyTierFailureCategory.None,
            )
        )
    }
}

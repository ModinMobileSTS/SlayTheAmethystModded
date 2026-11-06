package io.stamethyst.godot;

import org.junit.Test;
import static org.junit.Assert.*;

public class GodotExitStateTest {
    @Test public void firstBackOnlyArmsSecondBackWithinTwoSeconds() {
        GodotExitState state = new GodotExitState();
        assertFalse(state.pressBack(0));
        assertFalse(state.saving());
        assertFalse(state.returnsToLauncher());
        assertTrue(state.pressBack(2000));
        assertFalse(state.pressBack(2001));
    }

    @Test public void expiredBackAndBackgroundingRequireTwoFreshPresses() {
        GodotExitState state = new GodotExitState();
        assertFalse(state.pressBack(100));
        assertFalse(state.pressBack(2101));
        state.resetBack();
        assertFalse(state.pressBack(2200));
        assertTrue(state.pressBack(2300));
    }

    @Test public void launcherExitWaitsForSaveAndIsAcknowledgedOnlyOnce() {
        GodotExitState state = new GodotExitState();
        assertFalse(state.acknowledgeSave());
        assertTrue(state.beginSave(GodotExitState.Destination.LAUNCHER));
        assertTrue(state.returnsToLauncher());
        assertFalse(state.completed());
        assertFalse(state.beginSave(GodotExitState.Destination.LAUNCHER));
        assertFalse(state.pressBack(100));
        assertTrue(state.acknowledgeSave());
        assertTrue(state.completed());
        assertFalse(state.acknowledgeSave());
        assertFalse(state.complete());
        assertFalse(state.beginSave(GodotExitState.Destination.MAIN_GAME));
    }

    @Test public void launchButtonKeepsItsSeparateMainGameDestination() {
        GodotExitState state = new GodotExitState();
        assertTrue(state.beginSave(GodotExitState.Destination.MAIN_GAME));
        assertFalse(state.returnsToLauncher());
        assertTrue(state.acknowledgeSave());
        state.selectLauncher();
        assertFalse(state.returnsToLauncher());
    }

    @Test public void doubleBackCanRedirectPendingEntryWithoutStartingASecondSave() {
        GodotExitState state = new GodotExitState();
        assertTrue(state.beginSave(GodotExitState.Destination.MAIN_GAME));
        assertFalse(state.pressBack(500));
        assertTrue(state.pressBack(600));
        assertFalse(state.beginSave(GodotExitState.Destination.LAUNCHER));
        assertTrue(state.returnsToLauncher());
        assertTrue(state.acknowledgeSave());
    }

    @Test public void staleEntryCannotOverrideLauncherDestination() {
        GodotExitState state = new GodotExitState();
        state.selectLauncher();
        assertTrue(state.beginSave(GodotExitState.Destination.LAUNCHER));
        assertFalse(state.beginSave(GodotExitState.Destination.MAIN_GAME));
        assertTrue(state.returnsToLauncher());
    }

    @Test public void failedSaveDoesNotAcceptLateAcknowledgementWithoutRetry() {
        GodotExitState state = new GodotExitState();
        assertFalse(state.failSave());
        assertFalse(state.retrySave());
        assertTrue(state.beginSave(GodotExitState.Destination.LAUNCHER));
        assertTrue(state.failSave());
        assertTrue(state.saveFailed());
        assertFalse(state.completed());
        assertFalse(state.acknowledgeSave());
        assertFalse(state.beginSave(GodotExitState.Destination.LAUNCHER));
        assertTrue(state.retrySave());
        assertTrue(state.returnsToLauncher());
        assertTrue(state.acknowledgeSave());
    }

    @Test public void explicitDiscardKeepsRequestedDestinationWithoutFakingSaved() {
        GodotExitState state = new GodotExitState();
        assertFalse(state.complete());
        assertTrue(state.beginSave(GodotExitState.Destination.LAUNCHER));
        assertTrue(state.failSave());
        assertTrue(state.complete());
        assertTrue(state.returnsToLauncher());
        assertFalse(state.acknowledgeSave());
        assertFalse(state.retrySave());
    }
}

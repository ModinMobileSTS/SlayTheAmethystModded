package io.stamethyst.godot;

import org.junit.Test;
import static org.junit.Assert.*;

public class GodotBootCompletionTest {
    @Test public void normalSavedEntryCanCompleteOnlyOnce() {
        GodotBootCompletion route = new GodotBootCompletion();
        assertTrue(route.claimEntry());
        assertFalse(route.claimEntry());
        assertFalse(route.claimLauncherReturn());
    }

    @Test public void cancelDisarmsEntryBeforeSaveCompletesOrBinderDies() {
        GodotBootCompletion route = new GodotBootCompletion();
        route.requestLauncher();
        assertTrue(route.launcherRequested());
        assertFalse(route.claimEntry());
        // The same destination is used for saved, explicit discard, or death after cancellation.
        assertTrue(route.claimLauncherReturn());
        assertFalse(route.claimEntry());
        assertFalse(route.claimLauncherReturn());
    }

    @Test public void returnMessageItselfAlsoPreventsDelayedEntry() {
        GodotBootCompletion route = new GodotBootCompletion();
        assertTrue(route.claimLauncherReturn());
        assertTrue(route.launcherRequested());
        assertFalse(route.claimEntry());
    }

    @Test public void cancellationAloneDoesNotCommitReturnBeforeSave() {
        GodotBootCompletion route = new GodotBootCompletion();
        route.requestLauncher();
        route.requestLauncher();
        assertFalse(route.claimEntry());
        assertTrue(route.claimLauncherReturn());
    }
}

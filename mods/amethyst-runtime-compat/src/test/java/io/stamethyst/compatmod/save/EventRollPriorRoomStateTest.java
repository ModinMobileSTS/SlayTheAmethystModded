package io.stamethyst.compatmod.save;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EventRollPriorRoomStateTest {
    @Test
    public void loadedTrueValueSuppressesOnlyReplayRoll() {
        EventRollPriorRoomState state = new EventRollPriorRoomState();

        state.load(Boolean.TRUE);

        assertTrue(state.prepareRoll(true, false));
        assertFalse(state.prepareRoll(true, false));
    }

    @Test
    public void normalRollRecordsContextWithoutOverridingVanilla() {
        EventRollPriorRoomState state = new EventRollPriorRoomState();

        assertFalse(state.prepareRoll(false, true));

        assertTrue(state.valueForSave(true, false));
    }

    @Test
    public void postCombatQuestionSavePreservesLoadedContext() {
        EventRollPriorRoomState state = new EventRollPriorRoomState();

        state.load(Boolean.TRUE);
        state.prepareRoll(true, false);

        assertTrue(state.valueForSave(true, false));
    }

    @Test
    public void ordinarySaveUsesCurrentRoomInsteadOfStaleEventContext() {
        EventRollPriorRoomState state = new EventRollPriorRoomState();

        state.prepareRoll(false, true);

        assertFalse(state.valueForSave(false, false));
        assertTrue(state.valueForSave(false, true));
    }

    @Test
    public void missingOldSaveValueDoesNotSuppressShopRoll() {
        EventRollPriorRoomState state = new EventRollPriorRoomState();

        state.load(null);

        assertFalse(state.prepareRoll(true, false));
        assertFalse(state.valueForSave(true, false));
    }
}

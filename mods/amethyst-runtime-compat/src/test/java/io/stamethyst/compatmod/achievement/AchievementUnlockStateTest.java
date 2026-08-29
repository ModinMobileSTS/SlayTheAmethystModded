package io.stamethyst.compatmod.achievement;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AchievementUnlockStateTest {
    @Test
    public void changedToUnlocked_acceptsNewUnlock() {
        AchievementUnlockState state = new AchievementUnlockState();

        state.capture(false);

        assertTrue(state.changedToUnlocked(true));
    }

    @Test
    public void changedToUnlocked_rejectsAlreadyUnlockedAchievement() {
        AchievementUnlockState state = new AchievementUnlockState();

        state.capture(true);

        assertFalse(state.changedToUnlocked(true));
    }

    @Test
    public void changedToUnlocked_rejectsFailedUnlock() {
        AchievementUnlockState state = new AchievementUnlockState();

        state.capture(false);

        assertFalse(state.changedToUnlocked(false));
    }

    @Test
    public void changedToUnlocked_consumesCapturedState() {
        AchievementUnlockState state = new AchievementUnlockState();

        state.capture(false);
        assertTrue(state.changedToUnlocked(true));

        assertFalse(state.changedToUnlocked(true));
    }
}

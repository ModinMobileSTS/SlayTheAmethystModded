package io.stamethyst.compatmod.achievement;

/** Tracks whether one vanilla unlock call changed the persisted achievement state. */
final class AchievementUnlockState {
    private final ThreadLocal<Boolean> wasUnlocked = new ThreadLocal<Boolean>();

    void capture(boolean unlocked) {
        wasUnlocked.set(Boolean.valueOf(unlocked));
    }

    boolean changedToUnlocked(boolean unlocked) {
        Boolean previous = wasUnlocked.get();
        wasUnlocked.remove();
        return Boolean.FALSE.equals(previous) && unlocked;
    }
}

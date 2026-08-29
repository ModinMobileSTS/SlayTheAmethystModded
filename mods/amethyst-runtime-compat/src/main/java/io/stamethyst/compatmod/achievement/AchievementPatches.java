package io.stamethyst.compatmod.achievement;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.unlock.UnlockTracker;

/** Observes vanilla achievement unlock completion without replacing its persistence logic. */
public final class AchievementPatches {
    private static final String LUCKY_DAY = "LUCKY_DAY";

    private AchievementPatches() {
    }

    @SpirePatch2(clz = UnlockTracker.class, method = "unlockAchievement")
    public static class UnlockAchievementPatch {
        private static final AchievementUnlockState STATE = new AchievementUnlockState();

        public static void Prefix(Object[] __args) {
            String id = achievementId(__args);
            STATE.capture(id != null && UnlockTracker.isAchievementUnlocked(id));
        }

        public static void Postfix(Object[] __args) {
            String id = achievementId(__args);
            if (STATE.changedToUnlocked(id != null && UnlockTracker.isAchievementUnlocked(id))) {
                AchievementBridge.reportUnlocked(id);
            }
        }

        private static String achievementId(Object[] args) {
            return args != null && args.length > 0 && args[0] instanceof String
                ? (String) args[0]
                : null;
        }
    }

    @SpirePatch2(clz = UnlockTracker.class, method = "unlockLuckyDay")
    public static class UnlockLuckyDayPatch {
        private static final AchievementUnlockState STATE = new AchievementUnlockState();

        public static void Prefix() {
            STATE.capture(UnlockTracker.isAchievementUnlocked(LUCKY_DAY));
        }

        public static void Postfix() {
            if (STATE.changedToUnlocked(UnlockTracker.isAchievementUnlocked(LUCKY_DAY))) {
                AchievementBridge.reportUnlocked(LUCKY_DAY);
            }
        }
    }
}

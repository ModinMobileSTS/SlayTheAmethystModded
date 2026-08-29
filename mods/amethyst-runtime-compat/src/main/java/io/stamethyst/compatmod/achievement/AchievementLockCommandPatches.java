package io.stamethyst.compatmod.achievement;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.core.CardCrawlGame;

/** Applies launcher-issued achievement locks to the game's in-memory achievement preferences. */
public final class AchievementLockCommandPatches {
    private AchievementLockCommandPatches() {
    }

    @SpirePatch2(clz = CardCrawlGame.class, method = "update")
    public static class PollLockCommandPatch {
        @SpirePostfixPatch
        public static void Postfix() {
            AchievementBridge.pollLockCommand();
        }
    }
}

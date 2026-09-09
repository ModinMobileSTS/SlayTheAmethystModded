package io.stamethyst.compatmod.autoplay;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.relics.NeowsLament;

/** Keeps Neow's Lament from invalidating deterministic single-room combat benchmarks. */
public final class SingleRoomNeowsLamentPatches {
    private static int blockedTriggers;

    private SingleRoomNeowsLamentPatches() {
    }

    @SpirePatch2(clz = NeowsLament.class, method = "atBattleStart")
    public static class AtBattleStartPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix() {
            if (!AutoplayConfig.isEnabled() || !AutoplayConfig.isSingleRoomMode()) {
                return SpireReturn.Continue();
            }
            blockedTriggers++;
            AutoplayLog.info(
                "single_room blocked NeowsLament atBattleStart count=" + blockedTriggers
            );
            return SpireReturn.Return(null);
        }
    }
}

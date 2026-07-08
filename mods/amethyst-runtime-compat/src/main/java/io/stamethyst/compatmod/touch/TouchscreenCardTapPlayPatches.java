package io.stamethyst.compatmod.touch;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.characters.AbstractPlayer;

public final class TouchscreenCardTapPlayPatches {
    private TouchscreenCardTapPlayPatches() {
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "updateInput"
    )
    public static class AbstractPlayerUpdateInputPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> before(AbstractPlayer __instance) {
            if (!TouchscreenCardInputRuntime.tryConsumeTapPlayInput(__instance)) {
                return SpireReturn.Continue();
            }
            return SpireReturn.Return(null);
        }
    }
}

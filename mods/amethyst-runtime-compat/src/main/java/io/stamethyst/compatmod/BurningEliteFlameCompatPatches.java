package io.stamethyst.compatmod;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.ByRef;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.vfx.FlameAnimationEffect;

public final class BurningEliteFlameCompatPatches {
    private static final float LARGER_UI_FLAME_SCALE_MULTIPLIER = 2.0f;

    private BurningEliteFlameCompatPatches() {
    }

    @SpirePatch2(
        clz = FlameAnimationEffect.class,
        method = "render",
        paramtypez = {SpriteBatch.class, float.class}
    )
    public static class FlameAnimationEffectRenderPatch {
        public static void Prefix(SpriteBatch sb, @ByRef float[] s) {
            if (s == null
                || s.length == 0
                || !CompatRuntimeState.isMobileUiScaleStrategyActive()) {
                return;
            }

            // Mobile layout doubles the map node art, so keep the emerald flame marker
            // at the same visual prominence when the launcher reuses that layout strategy.
            s[0] *= LARGER_UI_FLAME_SCALE_MULTIPLIER;
        }
    }
}

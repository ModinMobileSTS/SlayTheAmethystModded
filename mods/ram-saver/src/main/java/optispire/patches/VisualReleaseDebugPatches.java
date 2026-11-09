package optispire.patches;

import com.badlogic.gdx.graphics.RamSaverReleaseEvents;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import optispire.debug.ReleaseDebugOverlay;

/** Independent of BaseMod/frame-probe activation: standalone release notices work too. */
public final class VisualReleaseDebugPatches {
    private static ReleaseDebugOverlay overlay;

    public static void beforeGameInput() {
        if (RamSaverReleaseEvents.ENABLED && overlay != null) overlay.beforeGameInput();
    }

    @SpirePatch2(clz = CardCrawlGame.class, method = "render")
    public static class Render {
        @SpirePostfixPatch
        public static void Postfix(SpriteBatch ___sb) {
            if (!RamSaverReleaseEvents.ENABLED) return;
            if (overlay == null) overlay = new ReleaseDebugOverlay();
            overlay.render(___sb);
        }
    }

    @SpirePatch2(clz = CardCrawlGame.class, method = "dispose")
    public static class Dispose {
        @SpirePrefixPatch
        public static void Prefix() {
            if (overlay != null) { overlay.dispose(); overlay = null; }
        }
    }
}

package optispire.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import optispire.TextureDecodeService;

/** Cancel CPU work at application shutdown without joining the worker on the GL thread. */
@SpirePatch2(clz = CardCrawlGame.class, method = "dispose")
public final class TextureDecodeLifecycle {
    @SpirePrefixPatch
    public static void Prefix() { TextureDecodeService.shutdown(); }
}

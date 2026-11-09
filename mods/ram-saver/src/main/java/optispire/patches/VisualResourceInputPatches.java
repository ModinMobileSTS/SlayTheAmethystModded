package optispire.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.helpers.input.InputHelper;

/** Keep interaction with the resource history panel out of the game's polled click path. */
@SpirePatch2(clz = InputHelper.class, method = "updateFirst")
public final class VisualResourceInputPatches {
    @SpirePostfixPatch
    public static void Postfix() { VisualReleaseDebugPatches.beforeGameInput(); }
}

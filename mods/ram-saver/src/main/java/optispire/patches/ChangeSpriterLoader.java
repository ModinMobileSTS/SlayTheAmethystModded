package optispire.patches;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.brashmonkey.spriter.Data;
import com.brashmonkey.spriter.FileReference;
import com.brashmonkey.spriter.LibGdx.LibGdxLoader;
import com.evacipated.cardcrawl.modthespire.lib.*;
import javassist.CtBehavior;
import optispire.RamSaverDiag;

public class ChangeSpriterLoader {
    // Opt-in only: the original packer eagerly holds source pixmaps and atlas pages.
    public static final String PACK_PROPERTY = "ramsaver.spriter.pack";

    @SpirePatch2(
            clz = LibGdxLoader.class,
            method = SpirePatch.CONSTRUCTOR,
            paramtypez = { Data.class, int.class, int.class }
    )
    @SpirePatch2(
            clz = LibGdxLoader.class,
            method = SpirePatch.CONSTRUCTOR,
            paramtypez = { Data.class, boolean.class }
    )
    public static class PackingPolicy {
        @SpirePostfixPatch
        public static void apply(@ByRef boolean[] ___pack) {
            // The boolean constructor overwrites the delegated constructor's pack flag.
            ___pack[0] = ___pack[0] && Boolean.getBoolean(PACK_PROPERTY);
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logStackRepeat("spriter_pack_policy", "LibGdxLoader", "pack=" + ___pack[0]);
            }
        }
    }

    @SpirePatch2(
            clz = LibGdxLoader.class,
            method = "loadResource",
            paramtypez = { FileReference.class }
    )
    public static class NoPixmap {
        @SpireInsertPatch(
                locator = Locator.class,
                localvars = { "f" }
        )
        public static SpireReturn<Sprite> justMakeTheSprite(FileReference ref, FileHandle f, Data ___data, boolean ___pack) {
            if (___pack) return SpireReturn.Continue();
            boolean diag = RamSaverDiag.enabled();
            long started = diag ? System.nanoTime() : 0L;
            Texture t = new Texture(f);
            t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            int width = (int)___data.getFile(ref.folder, ref.file).size.width;
            int height = (int)___data.getFile(ref.folder, ref.file).size.height;
            TextureRegion texRegion = new TextureRegion(t, width, height);
            if (diag) {
                RamSaverDiag.logDuration(
                        "spriter_make_sprite",
                        f.path(),
                        started,
                        "refFolder=" + ref.folder
                                + " refFile=" + ref.file
                                + " declaredSize=" + width + 'x' + height
                                + " textureFake=" + t.isFake,
                        true
                );
            }
            return SpireReturn.Return(new Sprite(texRegion));
        }

        private static class Locator extends SpireInsertLocator {
            @Override
            public int[] Locate(CtBehavior ctBehavior) throws Exception {
                Matcher finalMatcher = new Matcher.NewExprMatcher("com.badlogic.gdx.graphics.Pixmap");
                return LineFinder.findInOrder(ctBehavior, finalMatcher);
            }
        }
    }

    @SpirePatch2(
            clz = LibGdxLoader.class,
            method = "finishLoading"
    )
    public static class FinishLoading {
        @SpirePrefixPatch
        public static SpireReturn<Void> finish(boolean ___pack) {
            if (___pack) return SpireReturn.Continue();
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logStackRepeat("spriter_skip_finish_loading", "LibGdxLoader", "finishLoading skipped");
            }
            return SpireReturn.Return();
        }
    }
}

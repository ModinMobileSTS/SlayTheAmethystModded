package optispire.patches;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.PolygonRegion;
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Affine2;
import com.evacipated.cardcrawl.modthespire.lib.ByRef;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;
import optispire.RamSaverDiag;

public class HandleRenderingFakes {
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    TextureRegion.class, float.class, float.class, float.class, float.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    TextureRegion.class, float.class, float.class, float.class, float.class
            }
    )
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    TextureRegion.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class, float.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    TextureRegion.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class, float.class
            }
    )
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    TextureRegion.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class, float.class, boolean.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    TextureRegion.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class, float.class, boolean.class
            }
    )
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    TextureRegion.class, float.class, float.class, Affine2.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    TextureRegion.class, float.class, float.class, Affine2.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = { PolygonRegion.class, float.class, float.class }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = { PolygonRegion.class, float.class, float.class, float.class, float.class }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    PolygonRegion.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class, float.class
            }
    )
    public static class FakeRegion {
        @SpireInstrumentPatch
        public static ExprEditor resolveTextureRead() {
            return new ExprEditor() {
                @Override
                public void edit(FieldAccess field) throws CannotCompileException {
                    if (field.isReader() && field.getClassName().equals(TextureRegion.class.getName())
                            && field.getFieldName().equals("texture")) {
                        // Normalize the local texture before lastTexture comparison.
                        // Never mutate the shared region, including on nested draws or exceptions.
                        field.replace("$_ = " + HandleRenderingFakes.class.getName()
                                + ".resolveRegionTexture($proceed());");
                    }
                }
            };
        }
    }

    public static Texture resolveRegionTexture(Texture texture) {
        boolean diag = texture != null && texture.isFake && RamSaverDiag.enabled();
        long started = diag ? System.nanoTime() : 0L;
        if (diag) {
            RamSaverDiag.logStackRepeat("draw_region_fake_texture", textureKey(texture), textureDetails(texture));
        }
        Texture real = texture == null ? null : texture.getRealTexture();
        if (diag) {
            RamSaverDiag.logDuration("draw_region_materialize", textureKey(texture), started,
                    "realTexture=" + textureDetails(real), false);
        }
        return real;
    }

    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class, float.class,
                    int.class, int.class, int.class, int.class,
                    boolean.class, boolean.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class, float.class,
                    int.class, int.class, int.class, int.class,
                    boolean.class, boolean.class
            }
    )
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class, float.class, float.class,
                    int.class, int.class, int.class, int.class,
                    boolean.class, boolean.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class, float.class, float.class,
                    int.class, int.class, int.class, int.class,
                    boolean.class, boolean.class
            }
    )
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class,
                    int.class, int.class, int.class, int.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class,
                    int.class, int.class, int.class, int.class
            }
    )
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class
            }
    )
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class, float.class, float.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class, float.class, float.class
            }
    )
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float[].class, int.class, int.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float[].class, int.class, int.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float[].class, int.class, int.class,
                    short[].class, int.class, int.class
            }
    )
    public static class FakeTextures {
        @SpirePrefixPatch
        public static void handleFakeTexture(@ByRef Texture[] texture) {
            if (texture[0] == null) return;
            Texture original = texture[0];
            if (original.isFake) {
                boolean diag = RamSaverDiag.enabled();
                long started = diag ? System.nanoTime() : 0L;
                if (diag) {
                    RamSaverDiag.logStackRepeat("draw_texture_fake", textureKey(original), textureDetails(original));
                }
                texture[0] = original.getRealTexture();
                if (diag) {
                    RamSaverDiag.logDuration(
                            "draw_texture_materialize",
                            textureKey(original),
                            started,
                            "realTexture=" + textureDetails(texture[0]),
                            false
                    );
                }
                return;
            }
            texture[0] = texture[0].getRealTexture();
        }
    }

    private static String textureKey(Texture texture) {
        if (texture == null || texture.file == null) {
            return "null";
        }
        return texture.file.path();
    }

    private static String textureDetails(Texture texture) {
        if (!RamSaverDiag.enabled()) {
            return "";
        }
        if (texture == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(RamSaverDiag.describeObject(texture));
        builder.append(" fake=").append(texture.isFake);
        if (texture.file != null) {
            builder.append(" file=").append(RamSaverDiag.safe(texture.file.path()));
        }
        if (!texture.isFake) {
            builder.append(" handle=").append(texture.getTextureObjectHandle());
            builder.append(" size=").append(texture.getWidth()).append('x').append(texture.getHeight());
        }
        return builder.toString();
    }

}

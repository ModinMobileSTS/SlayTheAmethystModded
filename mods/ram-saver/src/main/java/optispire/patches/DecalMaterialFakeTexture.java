package optispire.patches;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.decals.DecalMaterial;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import optispire.RamSaver;
import optispire.RamSaverDiag;

public class DecalMaterialFakeTexture {
    private static final ThreadLocal<Texture> originalTexture = new ThreadLocal<>();

    @SpirePatch2(
            clz = DecalMaterial.class,
            method = "set"
    )
    public static class BindRealTexture {
        @SpirePrefixPatch
        public static void replaceFakeTexture(TextureRegion ___textureRegion) {
            originalTexture.remove();
            if (___textureRegion == null) {
                return;
            }
            Texture texture = ___textureRegion.getTexture();
            if (texture == null || !texture.isFake) {
                return;
            }
            boolean diag = RamSaverDiag.enabled();
            long started = diag ? System.nanoTime() : 0L;
            if (diag) {
                RamSaverDiag.logStackRepeat(
                        "decal_material_fake_texture",
                        textureKey(texture),
                        "region=" + regionDetails(___textureRegion) + " texture=" + textureDetails(texture)
                );
            }
            originalTexture.set(texture);
            Texture real = RamSaver.getTextureForBindFallback(texture.getRamSaverKey());
            if (real == null) {
                originalTexture.remove();
                return;
            }
            ___textureRegion.setTexture(real);
            if (diag) {
                RamSaverDiag.logDuration(
                        "decal_material_materialize",
                        textureKey(texture),
                        started,
                        "region=" + regionDetails(___textureRegion) + " realTexture=" + textureDetails(___textureRegion.getTexture()),
                        false
                );
            }
        }

        @SpirePostfixPatch
        public static void restoreFakeTexture(TextureRegion ___textureRegion) {
            Texture original = originalTexture.get();
            if (original == null) {
                return;
            }
            if (___textureRegion != null) {
                if (RamSaverDiag.enabled()) {
                    RamSaverDiag.logRepeat(
                            "decal_material_restore_fake_texture",
                            textureKey(original),
                            "region=" + regionDetails(___textureRegion) + " fake=" + textureDetails(original)
                    );
                }
                ___textureRegion.setTexture(original);
            }
            originalTexture.remove();
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

    private static String regionDetails(TextureRegion region) {
        if (!RamSaverDiag.enabled()) {
            return "";
        }
        if (region == null) {
            return "null";
        }
        return RamSaverDiag.describeObject(region)
                + " region=" + region.getRegionX() + ',' + region.getRegionY() + ' '
                + region.getRegionWidth() + 'x' + region.getRegionHeight();
    }
}

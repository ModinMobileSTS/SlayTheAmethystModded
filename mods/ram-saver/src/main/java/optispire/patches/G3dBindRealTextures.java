package optispire.patches;

import com.badlogic.gdx.graphics.GLTexture;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.utils.DefaultTextureBinder;
import com.badlogic.gdx.graphics.g3d.utils.TextureBinder;
import com.badlogic.gdx.graphics.g3d.utils.TextureDescriptor;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import optispire.RamSaverDiag;

@SpirePatch(
        clz = DefaultTextureBinder.class,
        method = "bindTexture"
)
public class G3dBindRealTextures {
    private static GLTexture original = null;

    @SpirePrefixPatch
    public static void Wheeee(TextureBinder __instance, TextureDescriptor textureDesc, boolean rebind) {
        original = null;
        if (textureDesc.texture instanceof Texture) {
            boolean diag = RamSaverDiag.enabled();
            long started = diag ? System.nanoTime() : 0L;
            Texture fakeTexture = (Texture) textureDesc.texture;
            original = textureDesc.texture;
            if (diag && fakeTexture.isFake) {
                RamSaverDiag.logStackRepeat(
                        "g3d_bind_fake_texture",
                        textureKey(fakeTexture),
                        "rebind=" + rebind + " texture=" + textureDetails(fakeTexture)
                );
            }
            textureDesc.texture = fakeTexture.getRealTexture(textureDesc.minFilter, textureDesc.magFilter,
                    textureDesc.uWrap, textureDesc.vWrap);
            if (diag) {
                RamSaverDiag.logDuration(
                        "g3d_bind_materialize",
                        textureKey(fakeTexture),
                        started,
                        "rebind=" + rebind + " realTexture=" + textureDetails((Texture) textureDesc.texture),
                        false
                );
            }
        }
    }

    @SpirePostfixPatch
    public static void Whoooo(TextureBinder __instance, TextureDescriptor textureDesc, boolean rebind) {
        if (original != null) {
            if (RamSaverDiag.enabled() && original instanceof Texture) {
                RamSaverDiag.logRepeat(
                        "g3d_bind_restore_fake_texture",
                        textureKey((Texture) original),
                        "rebind=" + rebind + " texture=" + textureDetails((Texture) original)
                );
            }
            textureDesc.texture = original;
            original = null;
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

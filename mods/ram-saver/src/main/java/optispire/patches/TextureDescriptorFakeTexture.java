package optispire.patches;

import com.badlogic.gdx.graphics.GLTexture;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.utils.TextureDescriptor;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;

public class TextureDescriptorFakeTexture {
    @SpirePatch2(
            clz = TextureDescriptor.class,
            method = "hashCode"
    )
    public static class HashCode {
        @SpirePrefixPatch
        public static SpireReturn<Integer> fakeSafeHash(TextureDescriptor __instance) {
            if (!hasFakeTexture(__instance.texture)) {
                return SpireReturn.Continue();
            }
            long result = textureTarget(__instance.texture);
            result = 811L * result + textureHandleKey(__instance.texture);
            result = 811L * result + filterKey(__instance.minFilter);
            result = 811L * result + filterKey(__instance.magFilter);
            result = 811L * result + wrapKey(__instance.uWrap);
            result = 811L * result + wrapKey(__instance.vWrap);
            return SpireReturn.Return((int)(result ^ (result >> 32)));
        }
    }

    @SpirePatch2(
            clz = TextureDescriptor.class,
            method = "compareTo",
            paramtypez = { TextureDescriptor.class }
    )
    public static class CompareTo {
        @SpirePrefixPatch
        public static SpireReturn<Integer> fakeSafeCompare(TextureDescriptor __instance, Object[] __args) {
            TextureDescriptor other = __args != null && __args.length > 0 && __args[0] instanceof TextureDescriptor
                    ? (TextureDescriptor)__args[0]
                    : null;
            if (!hasFakeTexture(__instance.texture) && (other == null || !hasFakeTexture(other.texture))) {
                return SpireReturn.Continue();
            }
            if (other == __instance) {
                return SpireReturn.Return(0);
            }
            if (other == null) {
                throw new NullPointerException("other");
            }
            int result = compareInt(textureTarget(__instance.texture), textureTarget(other.texture));
            if (result != 0) {
                return SpireReturn.Return(result);
            }
            result = compareInt(textureHandleKey(__instance.texture), textureHandleKey(other.texture));
            if (result != 0) {
                return SpireReturn.Return(result);
            }
            result = compareFakeTexturePath(__instance.texture, other.texture);
            if (result != 0) {
                return SpireReturn.Return(result);
            }
            result = compareInt(filterKey(__instance.minFilter), filterKey(other.minFilter));
            if (result != 0) {
                return SpireReturn.Return(result);
            }
            result = compareInt(filterKey(__instance.magFilter), filterKey(other.magFilter));
            if (result != 0) {
                return SpireReturn.Return(result);
            }
            result = compareInt(wrapKey(__instance.uWrap), wrapKey(other.uWrap));
            if (result != 0) {
                return SpireReturn.Return(result);
            }
            return SpireReturn.Return(compareInt(wrapKey(__instance.vWrap), wrapKey(other.vWrap)));
        }
    }

    private static boolean hasFakeTexture(GLTexture texture) {
        return texture instanceof Texture && ((Texture) texture).isFake;
    }

    private static int textureTarget(GLTexture texture) {
        return texture == null ? 0 : texture.glTarget;
    }

    private static int textureHandleKey(GLTexture texture) {
        if (texture == null) {
            return 0;
        }
        if (hasFakeTexture(texture)) {
            Texture fake = (Texture) texture;
            String path = fake.getRamSaverKey();
            int hash = path == null ? System.identityHashCode(fake) : path.hashCode();
            return 0x80000000 | (hash & 0x7fffffff);
        }
        return texture.getTextureObjectHandle();
    }

    private static int compareFakeTexturePath(GLTexture left, GLTexture right) {
        if (!hasFakeTexture(left) && !hasFakeTexture(right)) {
            return 0;
        }
        String leftPath = texturePath(left);
        String rightPath = texturePath(right);
        if (leftPath == null || rightPath == null) {
            return compareInt(System.identityHashCode(left), System.identityHashCode(right));
        }
        return leftPath.compareTo(rightPath);
    }

    private static String texturePath(GLTexture texture) {
        if (!hasFakeTexture(texture)) {
            return null;
        }
        Texture fake = (Texture) texture;
        return fake.getRamSaverKey();
    }

    private static int filterKey(Texture.TextureFilter filter) {
        return filter == null ? 0 : filter.getGLEnum();
    }

    private static int wrapKey(Texture.TextureWrap wrap) {
        return wrap == null ? 0 : wrap.getGLEnum();
    }

    private static int compareInt(int left, int right) {
        return left < right ? -1 : (left == right ? 0 : 1);
    }
}

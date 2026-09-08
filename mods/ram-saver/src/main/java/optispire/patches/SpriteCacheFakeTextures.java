package optispire.patches;

import basemod.ReflectionHacks;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteCache;
import com.badlogic.gdx.utils.Array;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import optispire.RamSaver;
import optispire.RamSaverDiag;

import java.lang.reflect.Field;

/** Resolves SpriteCache's fake textures without replacing its draw implementation. */
public class SpriteCacheFakeTextures {
    private static final Field DRAWING_FIELD = ReflectionHacks.getCachedField(SpriteCache.class, "drawing");
    private static final Field CACHES_FIELD = ReflectionHacks.getCachedField(SpriteCache.class, "caches");
    private static final Field CACHE_TEXTURE_COUNT_FIELD = cacheField("textureCount");
    private static final Field CACHE_TEXTURES_FIELD = cacheField("textures");
    private static volatile boolean reflectionAvailable = true;
    private static final ThreadLocal<Replacement> activeReplacement = new ThreadLocal<>();

    @SpirePatch2(clz = SpriteCache.class, method = "draw", paramtypez = {int.class})
    public static class DrawFullCache {
        @SpirePrefixPatch
        public static void replaceFakeTextures(SpriteCache __instance, int cacheID) {
            begin(__instance, cacheID);
        }

        @SpirePostfixPatch
        public static void restoreFakeTextures(SpriteCache __instance, int cacheID) {
            end();
        }
    }

    @SpirePatch2(clz = SpriteCache.class, method = "draw", paramtypez = {int.class, int.class, int.class})
    public static class DrawPartialCache {
        @SpirePrefixPatch
        public static void replaceFakeTextures(SpriteCache __instance, int cacheID, int offset, int length) {
            begin(__instance, cacheID);
        }

        @SpirePostfixPatch
        public static void restoreFakeTextures(SpriteCache __instance, int cacheID, int offset, int length) {
            end();
        }
    }

    private static void begin(SpriteCache spriteCache, int cacheID) {
        end();
        if (!reflectionAvailable || !isDrawing(spriteCache)) return;
        Object cache = getCache(spriteCache, cacheID);
        if (cache == null) return;
        int textureCount = textureCount(cache);
        Texture[] textures = textures(cache);
        if (textures == null || textureCount <= 0) return;

        Texture[] originals = null;
        int replaced = 0;
        for (int i = 0; i < textureCount && i < textures.length; i++) {
            Texture texture = textures[i];
            if (texture == null || !texture.isFake) continue;
            if (originals == null) originals = new Texture[textures.length];
            originals[i] = texture;
            boolean diag = RamSaverDiag.enabled();
            long started = diag ? System.nanoTime() : 0L;
            if (diag) {
                RamSaverDiag.logStackRepeat(
                        "sprite_cache_fake_texture",
                        textureKey(texture),
                        "cacheID=" + cacheID + " index=" + i
                );
            }
            Texture real = RamSaver.getTextureForBindFallback(texture.getRamSaverKey());
            if (real == null) {
                originals[i] = null;
                continue;
            }
            textures[i] = real;
            replaced++;
            if (diag) {
                RamSaverDiag.logDuration(
                        "sprite_cache_materialize",
                        textureKey(texture),
                        started,
                        "cacheID=" + cacheID + " index=" + i,
                        false
                );
            }
        }
        if (replaced > 0) activeReplacement.set(new Replacement(textures, originals));
    }

    private static void end() {
        Replacement replacement = activeReplacement.get();
        if (replacement == null) return;
        for (int i = 0; i < replacement.originals.length && i < replacement.textures.length; i++) {
            Texture original = replacement.originals[i];
            if (original != null) replacement.textures[i] = original;
        }
        activeReplacement.remove();
    }

    private static Object getCache(SpriteCache spriteCache, int cacheID) {
        Array caches = get(CACHES_FIELD, spriteCache);
        if (caches == null || cacheID < 0 || cacheID >= caches.size) return null;
        return caches.get(cacheID);
    }

    private static boolean isDrawing(SpriteCache spriteCache) {
        Boolean drawing = get(DRAWING_FIELD, spriteCache);
        return drawing != null && drawing;
    }

    private static int textureCount(Object cache) {
        Integer count = get(CACHE_TEXTURE_COUNT_FIELD, cache);
        return count == null ? 0 : count;
    }

    private static Texture[] textures(Object cache) {
        return get(CACHE_TEXTURES_FIELD, cache);
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Field field, Object instance) {
        if (field == null) {
            reflectionAvailable = false;
            return null;
        }
        try {
            return (T) field.get(instance);
        }
        catch (IllegalAccessException | RuntimeException e) {
            reflectionAvailable = false;
            RamSaverDiag.logStackRepeat(
                    "sprite_cache_reflection_failed",
                    field.getName(),
                    "error=" + e.getClass().getName() + ":" + e.getMessage()
            );
            return null;
        }
    }

    private static Field cacheField(String fieldName) {
        try {
            return ReflectionHacks.getCachedField(
                    Class.forName("com.badlogic.gdx.graphics.g2d.SpriteCache$Cache"), fieldName
            );
        }
        catch (ClassNotFoundException e) {
            RamSaverDiag.logStackRepeat(
                    "sprite_cache_reflection_failed",
                    fieldName,
                    "error=" + e.getClass().getName() + ":" + e.getMessage()
            );
            return null;
        }
    }

    private static String textureKey(Texture texture) {
        return texture == null ? "null" : texture.getRamSaverKey();
    }

    private static final class Replacement {
        final Texture[] textures;
        final Texture[] originals;

        Replacement(Texture[] textures, Texture[] originals) {
            this.textures = textures;
            this.originals = originals;
        }
    }
}

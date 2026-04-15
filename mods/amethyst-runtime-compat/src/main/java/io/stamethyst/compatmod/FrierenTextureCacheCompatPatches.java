package io.stamethyst.compatmod;

import com.badlogic.gdx.graphics.Texture;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.helpers.ImageMaster;

import java.util.HashMap;
import java.util.Locale;

public final class FrierenTextureCacheCompatPatches {
    private static final String TEXTURE_CACHE_PROP = "amethyst.runtime_compat.frieren_texture_cache";
    private static final boolean TEXTURE_CACHE_ENABLED =
        readBooleanSystemProperty(TEXTURE_CACHE_PROP, true);
    private static final Object LOCK = new Object();
    private static final HashMap<String, Texture> CACHED_TEXTURES = new HashMap<String, Texture>();

    private FrierenTextureCacheCompatPatches() {
    }

    @SpirePatch2(
        clz = ImageMaster.class,
        method = "loadImage",
        paramtypez = {String.class}
    )
    public static class ImageMasterLoadImagePatch {
        public static SpireReturn<Texture> Prefix(Object[] __args) {
            String cacheKey = normalizeFrierenTextureKey(extractPath(__args));
            if (cacheKey == null) {
                return SpireReturn.Continue();
            }
            synchronized (LOCK) {
                Texture cached = CACHED_TEXTURES.get(cacheKey);
                if (cached != null) {
                    return SpireReturn.Return(cached);
                }
            }
            return SpireReturn.Continue();
        }

        public static Texture Postfix(Texture __result, Object[] __args) {
            if (__result == null) {
                return null;
            }
            String cacheKey = normalizeFrierenTextureKey(extractPath(__args));
            if (cacheKey == null) {
                return __result;
            }
            synchronized (LOCK) {
                Texture cached = CACHED_TEXTURES.get(cacheKey);
                if (cached != null) {
                    if (cached != __result) {
                        safeDispose(__result);
                    }
                    return cached;
                }
                CACHED_TEXTURES.put(cacheKey, __result);
                return __result;
            }
        }
    }

    private static String extractPath(Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof String)) {
            return null;
        }
        return (String) args[0];
    }

    private static String normalizeFrierenTextureKey(String path) {
        if (!TEXTURE_CACHE_ENABLED || path == null || path.length() == 0) {
            return null;
        }
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!normalized.contains("frierenmodresources/")) {
            return null;
        }
        if (normalized.contains("/img/ui/slotbg/")
            || normalized.contains("/img/ui/slotpreviewandlibrary/")) {
            return normalized;
        }
        return null;
    }

    private static void safeDispose(Texture texture) {
        try {
            texture.dispose();
        } catch (Exception ignored) {
        }
    }

    private static boolean readBooleanSystemProperty(String key, boolean defaultValue) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return defaultValue;
        }
        configured = configured.trim();
        if (configured.length() == 0) {
            return defaultValue;
        }
        if ("false".equalsIgnoreCase(configured)
            || "0".equals(configured)
            || "off".equalsIgnoreCase(configured)) {
            return false;
        }
        if ("true".equalsIgnoreCase(configured)
            || "1".equals(configured)
            || "on".equalsIgnoreCase(configured)) {
            return true;
        }
        return defaultValue;
    }
}

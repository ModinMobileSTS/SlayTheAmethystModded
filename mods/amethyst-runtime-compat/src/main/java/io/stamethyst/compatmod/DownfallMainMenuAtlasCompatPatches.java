package io.stamethyst.compatmod;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.scenes.TitleBackground;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class DownfallMainMenuAtlasCompatPatches {
    private static final String ATLAS_REUSE_PROP =
        "amethyst.runtime_compat.downfall_main_menu_atlas_reuse";
    private static final boolean ATLAS_REUSE_ENABLED =
        readBooleanSystemProperty(ATLAS_REUSE_PROP, true);
    private static final Object LOCK = new Object();

    private static Class<?> mainMenuColorPatchClass;
    private static Field atlasPathField;
    private static Method setTitleBackgroundAtlasRegionMethod;
    private static Method setCloudsMethod;
    private static Method setLogoMethod;

    private static TextureAtlas cachedMainMenuAtlas;
    private static String cachedAtlasPath;

    private DownfallMainMenuAtlasCompatPatches() {
    }

    @SpirePatch2(
        requiredModId = "downfall",
        cls = "downfall.patches.MainMenuColorPatch",
        method = "setMainMenuBG",
        paramtypez = {TitleBackground.class}
    )
    public static class MainMenuColorPatchSetMainMenuBgPatch {
        public static SpireReturn<Void> Prefix(Object[] __args) {
            if (!ATLAS_REUSE_ENABLED) {
                return SpireReturn.Continue();
            }
            if (!applyCachedMainMenuAtlas(extractBackground(__args))) {
                return SpireReturn.Continue();
            }
            return SpireReturn.Return(null);
        }
    }

    private static boolean applyCachedMainMenuAtlas(TitleBackground background) {
        try {
            if (background == null) {
                if (CardCrawlGame.mainMenuScreen == null || CardCrawlGame.mainMenuScreen.bg == null) {
                    return false;
                }
                background = CardCrawlGame.mainMenuScreen.bg;
            }
            TextureAtlas atlas = resolveCachedMainMenuAtlas();
            if (atlas == null) {
                return false;
            }
            invokeSetTitleBackgroundAtlasRegion(background, atlas, "sky", "jpg/sky");
            invokeSetTitleBackgroundAtlasRegion(background, atlas, "mg3Bot", "mg3Bot");
            invokeSetTitleBackgroundAtlasRegion(background, atlas, "mg3Top", "mg3Top");
            invokeSetTitleBackgroundAtlasRegion(background, atlas, "topGlow", "mg3TopGlow1");
            invokeSetTitleBackgroundAtlasRegion(background, atlas, "topGlow2", "mg3TopGlow2");
            invokeSetTitleBackgroundAtlasRegion(background, atlas, "botGlow", "mg3BotGlow");
            resolveSetCloudsMethod().invoke(null, background, atlas);
            resolveSetLogoMethod().invoke(null, background);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static TextureAtlas resolveCachedMainMenuAtlas() throws Exception {
        String atlasPath = (String) resolveAtlasPathField().get(null);
        if (atlasPath == null || atlasPath.length() == 0) {
            return null;
        }
        synchronized (LOCK) {
            if (cachedMainMenuAtlas != null && atlasPath.equals(cachedAtlasPath)) {
                return cachedMainMenuAtlas;
            }
            TextureAtlas atlas = new TextureAtlas(Gdx.files.internal(atlasPath));
            cachedMainMenuAtlas = atlas;
            cachedAtlasPath = atlasPath;
            return atlas;
        }
    }

    private static void invokeSetTitleBackgroundAtlasRegion(
        TitleBackground background,
        TextureAtlas atlas,
        String fieldName,
        String regionName
    ) throws Exception {
        resolveSetTitleBackgroundAtlasRegionMethod().invoke(
            null,
            background,
            atlas,
            fieldName,
            regionName
        );
    }

    private static TitleBackground extractBackground(Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof TitleBackground)) {
            return null;
        }
        return (TitleBackground) args[0];
    }

    private static Class<?> resolveMainMenuColorPatchClass() throws ClassNotFoundException {
        if (mainMenuColorPatchClass == null) {
            mainMenuColorPatchClass = Class.forName("downfall.patches.MainMenuColorPatch");
        }
        return mainMenuColorPatchClass;
    }

    private static Field resolveAtlasPathField() throws Exception {
        if (atlasPathField == null) {
            atlasPathField = resolveMainMenuColorPatchClass().getDeclaredField("atlasPath");
            atlasPathField.setAccessible(true);
        }
        return atlasPathField;
    }

    private static Method resolveSetTitleBackgroundAtlasRegionMethod() throws Exception {
        if (setTitleBackgroundAtlasRegionMethod == null) {
            setTitleBackgroundAtlasRegionMethod =
                resolveMainMenuColorPatchClass().getDeclaredMethod(
                    "setTitleBackgroundAtlasRegion",
                    TitleBackground.class,
                    TextureAtlas.class,
                    String.class,
                    String.class
                );
            setTitleBackgroundAtlasRegionMethod.setAccessible(true);
        }
        return setTitleBackgroundAtlasRegionMethod;
    }

    private static Method resolveSetCloudsMethod() throws Exception {
        if (setCloudsMethod == null) {
            setCloudsMethod =
                resolveMainMenuColorPatchClass().getDeclaredMethod(
                    "setClouds",
                    TitleBackground.class,
                    TextureAtlas.class
                );
            setCloudsMethod.setAccessible(true);
        }
        return setCloudsMethod;
    }

    private static Method resolveSetLogoMethod() throws Exception {
        if (setLogoMethod == null) {
            setLogoMethod =
                resolveMainMenuColorPatchClass().getDeclaredMethod("setLogo", TitleBackground.class);
            setLogoMethod.setAccessible(true);
        }
        return setLogoMethod;
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

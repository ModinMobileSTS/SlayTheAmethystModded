package io.stamethyst.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

final class BootBridgeGameStateProbe {
    private volatile Class<?> cachedCardCrawlGameClass;
    private volatile Field cachedModeField;
    private volatile Field cachedMainMenuField;
    private volatile Class<?> cachedMainMenuScreenClass;
    private volatile Field cachedMainMenuScreenField;

    Snapshot readSnapshot() throws Exception {
        Class<?> cardCrawlGameClass = loadCardCrawlGameClass();
        Field modeField = cachedModeField;
        if (modeField == null || modeField.getDeclaringClass() != cardCrawlGameClass) {
            modeField = cardCrawlGameClass.getDeclaredField("mode");
            modeField.setAccessible(true);
            cachedModeField = modeField;
        }
        if (!Modifier.isStatic(modeField.getModifiers())) {
            return null;
        }
        Object mode = modeField.get(null);
        String modeName = readModeName(mode);

        Field mainMenuField = cachedMainMenuField;
        if (mainMenuField == null || mainMenuField.getDeclaringClass() != cardCrawlGameClass) {
            mainMenuField = cardCrawlGameClass.getDeclaredField("mainMenuScreen");
            mainMenuField.setAccessible(true);
            cachedMainMenuField = mainMenuField;
        }
        if (!Modifier.isStatic(mainMenuField.getModifiers())) {
            return new Snapshot(modeName, false, "");
        }
        Object mainMenuScreen = mainMenuField.get(null);
        if (mainMenuScreen == null) {
            return new Snapshot(modeName, false, "");
        }

        String menuScreenName = "";
        try {
            Class<?> menuClass = mainMenuScreen.getClass();
            Field screenField = cachedMainMenuScreenField;
            if (screenField == null || cachedMainMenuScreenClass != menuClass) {
                try {
                    Field discovered = menuClass.getDeclaredField("screen");
                    discovered.setAccessible(true);
                    cachedMainMenuScreenField = discovered;
                    cachedMainMenuScreenClass = menuClass;
                    screenField = discovered;
                } catch (Throwable ignored) {
                    cachedMainMenuScreenField = null;
                    cachedMainMenuScreenClass = menuClass;
                    screenField = null;
                }
            }
            if (screenField != null) {
                Object screen = screenField.get(mainMenuScreen);
                if (screen instanceof Enum<?>) {
                    menuScreenName = ((Enum<?>) screen).name();
                } else if (screen != null) {
                    menuScreenName = String.valueOf(screen);
                }
            }
        } catch (Throwable ignored) {
            // Menu screen introspection is best-effort only.
        }
        return new Snapshot(modeName, true, menuScreenName);
    }

    private Class<?> loadCardCrawlGameClass() throws ClassNotFoundException {
        Class<?> cached = cachedCardCrawlGameClass;
        if (cached != null) {
            return cached;
        }
        List<ClassLoader> candidates = new ArrayList<ClassLoader>(4);
        addCandidateLoader(candidates, resolveMtsRuntimeClassLoader());
        addCandidateLoader(candidates, Thread.currentThread().getContextClassLoader());
        addCandidateLoader(candidates, BootBridgeGameStateProbe.class.getClassLoader());
        addCandidateLoader(candidates, ClassLoader.getSystemClassLoader());

        ClassNotFoundException last = null;
        for (ClassLoader loader : candidates) {
            if (loader == null) {
                continue;
            }
            try {
                Class<?> loaded = Class.forName("com.megacrit.cardcrawl.core.CardCrawlGame", false, loader);
                cachedCardCrawlGameClass = loaded;
                return loaded;
            } catch (ClassNotFoundException error) {
                last = error;
            }
        }
        if (last != null) {
            throw last;
        }
        Class<?> loaded = Class.forName("com.megacrit.cardcrawl.core.CardCrawlGame");
        cachedCardCrawlGameClass = loaded;
        return loaded;
    }

    private static void addCandidateLoader(List<ClassLoader> out, ClassLoader loader) {
        if (loader == null) {
            return;
        }
        for (ClassLoader existing : out) {
            if (existing == loader) {
                return;
            }
        }
        out.add(loader);
    }

    private static ClassLoader resolveMtsRuntimeClassLoader() {
        try {
            Class<?> loaderClass = Class.forName("com.evacipated.cardcrawl.modthespire.Loader");
            Method getClassPool = loaderClass.getMethod("getClassPool");
            Object classPool = getClassPool.invoke(null);
            if (classPool == null) {
                return null;
            }
            try {
                Method getClassLoader = classPool.getClass().getMethod("getClassLoader");
                Object value = getClassLoader.invoke(classPool);
                if (value instanceof ClassLoader) {
                    return (ClassLoader) value;
                }
            } catch (Throwable ignored) {
                // Fall through to reflective field access.
            }
            try {
                Field classLoaderField = classPool.getClass().getDeclaredField("classLoader");
                classLoaderField.setAccessible(true);
                Object value = classLoaderField.get(classPool);
                if (value instanceof ClassLoader) {
                    return (ClassLoader) value;
                }
            } catch (Throwable ignored) {
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    private static String readModeName(Object mode) {
        if (mode instanceof Enum<?>) {
            return ((Enum<?>) mode).name();
        }
        if (mode == null) {
            return "";
        }
        return String.valueOf(mode);
    }

    static final class Snapshot {
        final String modeName;
        final boolean hasMainMenuScreen;
        final String menuScreenName;

        Snapshot(String modeName, boolean hasMainMenuScreen, String menuScreenName) {
            this.modeName = modeName;
            this.hasMainMenuScreen = hasMainMenuScreen;
            this.menuScreenName = menuScreenName;
        }
    }
}

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
    private volatile Field cachedSplashScreenField;
    private volatile Class<?> cachedMainMenuScreenClass;
    private volatile Field cachedMainMenuScreenField;
    private volatile Class<?> cachedSplashPhaseClass;
    private volatile Field cachedSplashPhaseField;
    private volatile Class<?> cachedSplashColorClass;
    private volatile Field cachedSplashColorField;
    private volatile Class<?> cachedColorAlphaClass;
    private volatile Field cachedColorAlphaField;

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
            return new Snapshot(modeName, false, "", "", Float.NaN);
        }
        Object mainMenuScreen = mainMenuField.get(null);
        String menuScreenName = "";
        boolean hasMainMenuScreen = mainMenuScreen != null;
        if (mainMenuScreen != null) {
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
        }

        String splashPhaseName = "";
        float splashLogoAlpha = Float.NaN;
        try {
            Field splashField = cachedSplashScreenField;
            if (splashField == null || splashField.getDeclaringClass() != cardCrawlGameClass) {
                splashField = cardCrawlGameClass.getDeclaredField("splashScreen");
                splashField.setAccessible(true);
                cachedSplashScreenField = splashField;
            }
            if (Modifier.isStatic(splashField.getModifiers())) {
                Object splashScreen = splashField.get(null);
                if (splashScreen != null) {
                    splashPhaseName = readSplashPhaseName(splashScreen);
                    splashLogoAlpha = readSplashLogoAlpha(splashScreen);
                }
            }
        } catch (Throwable ignored) {
            // Splash introspection is best-effort only.
        }

        return new Snapshot(modeName, hasMainMenuScreen, menuScreenName, splashPhaseName, splashLogoAlpha);
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

    private String readSplashPhaseName(Object splashScreen) {
        if (splashScreen == null) {
            return "";
        }
        try {
            Class<?> splashClass = splashScreen.getClass();
            Field phaseField = cachedSplashPhaseField;
            if (phaseField == null || cachedSplashPhaseClass != splashClass) {
                try {
                    Field discovered = splashClass.getDeclaredField("phase");
                    discovered.setAccessible(true);
                    cachedSplashPhaseField = discovered;
                    cachedSplashPhaseClass = splashClass;
                    phaseField = discovered;
                } catch (Throwable ignored) {
                    cachedSplashPhaseField = null;
                    cachedSplashPhaseClass = splashClass;
                    phaseField = null;
                }
            }
            if (phaseField == null) {
                return "";
            }
            Object phase = phaseField.get(splashScreen);
            if (phase instanceof Enum<?>) {
                return ((Enum<?>) phase).name();
            }
            if (phase == null) {
                return "";
            }
            return String.valueOf(phase);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private float readSplashLogoAlpha(Object splashScreen) {
        if (splashScreen == null) {
            return Float.NaN;
        }
        try {
            Class<?> splashClass = splashScreen.getClass();
            Field colorField = cachedSplashColorField;
            if (colorField == null || cachedSplashColorClass != splashClass) {
                try {
                    Field discovered = splashClass.getDeclaredField("color");
                    discovered.setAccessible(true);
                    cachedSplashColorField = discovered;
                    cachedSplashColorClass = splashClass;
                    colorField = discovered;
                } catch (Throwable ignored) {
                    cachedSplashColorField = null;
                    cachedSplashColorClass = splashClass;
                    colorField = null;
                }
            }
            if (colorField == null) {
                return Float.NaN;
            }
            Object color = colorField.get(splashScreen);
            if (color == null) {
                return Float.NaN;
            }

            Class<?> colorClass = color.getClass();
            Field alphaField = cachedColorAlphaField;
            if (alphaField == null || cachedColorAlphaClass != colorClass) {
                Field discovered;
                try {
                    discovered = colorClass.getField("a");
                } catch (NoSuchFieldException notPublicField) {
                    discovered = colorClass.getDeclaredField("a");
                    discovered.setAccessible(true);
                }
                cachedColorAlphaField = discovered;
                cachedColorAlphaClass = colorClass;
                alphaField = discovered;
            }
            Object alpha = alphaField.get(color);
            if (alpha instanceof Number) {
                return ((Number) alpha).floatValue();
            }
        } catch (Throwable ignored) {
            // Ignore and treat as unknown.
        }
        return Float.NaN;
    }

    static final class Snapshot {
        final String modeName;
        final boolean hasMainMenuScreen;
        final String menuScreenName;
        final String splashPhaseName;
        final float splashLogoAlpha;

        Snapshot(
                String modeName,
                boolean hasMainMenuScreen,
                String menuScreenName,
                String splashPhaseName,
                float splashLogoAlpha
        ) {
            this.modeName = modeName;
            this.hasMainMenuScreen = hasMainMenuScreen;
            this.menuScreenName = menuScreenName;
            this.splashPhaseName = splashPhaseName;
            this.splashLogoAlpha = splashLogoAlpha;
        }
    }
}

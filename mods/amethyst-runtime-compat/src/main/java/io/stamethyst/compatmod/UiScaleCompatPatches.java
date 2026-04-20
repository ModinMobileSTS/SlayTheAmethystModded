package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.Loader;
import com.evacipated.cardcrawl.modthespire.ModInfo;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.megacrit.cardcrawl.core.CardCrawlGame;

import java.io.File;

import javassist.ClassPool;
import javassist.CtBehavior;

public final class UiScaleCompatPatches {
    private UiScaleCompatPatches() {
    }

    @SpirePatch(
        clz = CardCrawlGame.class,
        method = SpirePatch.CONSTRUCTOR
    )
    public static class CardCrawlGameConstructorPatch {
        public static void Raw(CtBehavior ctBehavior) {
            if (!CompatRuntimeState.isMobileUiScaleStrategyActive()) {
                return;
            }

            ClassPool classPool = ctBehavior.getDeclaringClass().getClassPool();
            patchBaseGame(classPool);
            patchLoadedMods(classPool);
        }

        private static void patchBaseGame(ClassPool classPool) {
            try {
                MobileUiLayoutClassPatcher.patchJar(
                    new File(Loader.STS_JAR),
                    classPool,
                    "sts"
                );
            } catch (Exception exception) {
                System.out.println(
                    "[amethyst-runtime-compat] mobile UI layout patch failed source=sts reason="
                        + exception
                );
            }
        }

        private static void patchLoadedMods(ClassPool classPool) {
            if (Loader.MODINFOS == null) {
                return;
            }
            for (ModInfo modInfo : Loader.MODINFOS) {
                if (modInfo == null || modInfo.jarURL == null) {
                    continue;
                }
                if ("amethystruntimecompat".equalsIgnoreCase(modInfo.ID)) {
                    continue;
                }
                try {
                    MobileUiLayoutClassPatcher.patchJar(
                        new File(modInfo.jarURL.toURI()),
                        classPool,
                        modInfo.ID
                    );
                } catch (Exception exception) {
                    System.out.println(
                        "[amethyst-runtime-compat] mobile UI layout patch failed source="
                            + modInfo.ID
                            + " reason="
                            + exception
                    );
                }
            }
        }
    }
}

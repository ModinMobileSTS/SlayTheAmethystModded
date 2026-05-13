package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.core.CardCrawlGame;

public final class DebugRuntimeCrashPatches {
    private static final String FORCE_RUNTIME_CRASH_PROP = "amethyst.debug.force_runtime_crash";
    private static boolean crashed;

    private DebugRuntimeCrashPatches() {
    }

    @SpirePatch2(
        clz = CardCrawlGame.class,
        method = "update"
    )
    public static class CardCrawlGameUpdatePatch {
        public static void Prefix() {
            if (crashed || !Boolean.parseBoolean(System.getProperty(FORCE_RUNTIME_CRASH_PROP, "false"))) {
                return;
            }
            crashed = true;
            throw new RuntimeException("Forced runtime crash for expected-exit verification");
        }
    }
}

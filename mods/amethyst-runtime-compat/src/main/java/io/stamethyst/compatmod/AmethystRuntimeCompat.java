package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;

@SpireInitializer
public class AmethystRuntimeCompat {
    public static void initialize() {
        CompatRuntimeState.logStartupConfiguration();
        RuntimeMemoryDiagnostics.logStartupConfiguration();
    }
}

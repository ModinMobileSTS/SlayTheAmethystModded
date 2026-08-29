package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;

import io.stamethyst.compatmod.autoplay.AutoplayConfig;
import io.stamethyst.compatmod.autoplay.AutoplayLog;
import io.stamethyst.compatmod.achievement.AchievementBridge;
import io.stamethyst.compatmod.presence.RichPresenceBridge;
import io.stamethyst.compatmod.core.CompatRuntimeState;
import io.stamethyst.compatmod.diagnostics.RuntimeMemoryDiagnostics;

@SpireInitializer
public class AmethystRuntimeCompat {
    public static void initialize() {
        AchievementBridge.initialize();
        RichPresenceBridge.initialize();
        CompatRuntimeState.logStartupConfiguration();
        RuntimeMemoryDiagnostics.logStartupConfiguration();
        if (AutoplayConfig.isEnabled()) {
            AutoplayLog.info(
                "autoplay configured tickIntervalMs=" + AutoplayConfig.getTickIntervalMs()
                    + " debugLog=" + AutoplayConfig.isDebugLogEnabled()
            );
        }
    }
}

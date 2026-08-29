package io.stamethyst.compatmod.compatibility;

import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.unlock.UnlockTracker;
import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;

/** Keeps the base achievement checks on the vanilla path for modded runs. */
public final class UnlockTrackerModdedAchievementPatches {
    private UnlockTrackerModdedAchievementPatches() {
    }

    @SpirePatch2(clz = UnlockTracker.class, method = "unlockAchievement")
    public static class UnlockAchievementPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(FieldAccess access) throws CannotCompileException {
                    if (access.isReader()
                        && Settings.class.getName().equals(access.getClassName())
                        && "isModded".equals(access.getFieldName())) {
                        access.replace("{ $_ = false; }");
                    }
                }
            };
        }
    }

    @SpirePatch2(clz = UnlockTracker.class, method = "unlockLuckyDay")
    public static class UnlockLuckyDayPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(FieldAccess access) throws CannotCompileException {
                    if (access.isReader()
                        && Settings.class.getName().equals(access.getClassName())
                        && "isModded".equals(access.getFieldName())) {
                        access.replace("{ $_ = false; }");
                    }
                }
            };
        }
    }
}

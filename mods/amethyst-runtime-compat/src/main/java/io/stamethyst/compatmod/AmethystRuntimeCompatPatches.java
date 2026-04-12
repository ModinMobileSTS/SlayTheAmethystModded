package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.ByRef;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;

public class AmethystRuntimeCompatPatches {
    @SpirePatch2(
        clz = FontHelper.class,
        method = "prepFont",
        paramtypez = {float.class, boolean.class}
    )
    public static class FontHelperPrepFontPatch {
        public static void Prefix(@ByRef float[] size, boolean isLinearFiltering) {
            if (size == null || size.length == 0) {
                return;
            }
            float requestedSize = size[0];
            float remappedSize = CompatRuntimeState.remapPrepFontSize(
                requestedSize,
                Settings.BIG_TEXT_MODE
            );
            if (requestedSize != remappedSize) {
                size[0] = remappedSize;
            }
        }
    }

    @SpirePatch2(
        requiredModId = "duelistmod-zh",
        cls = "duelistmod.variables.TributeMagicNumber",
        method = "baseValue",
        paramtypez = {AbstractCard.class}
    )
    public static class TributeMagicNumberBaseValuePatch {
        public static SpireReturn<Integer> Prefix(Object[] __args) {
            Integer value = CompatRuntimeState.getDuelistBaseTributes((AbstractCard) __args[0]);
            if (value != null) {
                return SpireReturn.Return(value);
            }
            return SpireReturn.Continue();
        }
    }

    @SpirePatch2(
        requiredModId = "duelistmod-zh",
        cls = "duelistmod.variables.SummonMagicNumber",
        method = "baseValue",
        paramtypez = {AbstractCard.class}
    )
    public static class SummonMagicNumberBaseValuePatch {
        public static SpireReturn<Integer> Prefix(Object[] __args) {
            Integer value = CompatRuntimeState.getDuelistBaseSummons((AbstractCard) __args[0]);
            if (value != null) {
                return SpireReturn.Return(value);
            }
            return SpireReturn.Continue();
        }
    }

    @SpirePatch2(
        requiredModId = "duelistmod-zh",
        cls = "duelistmod.variables.GuardedNum",
        method = "value",
        paramtypez = {AbstractCard.class}
    )
    public static class GuardedNumValuePatch {
        public static SpireReturn<Integer> Prefix(Object[] __args) {
            CompatRuntimeState.GuardedDynamicSnapshot snapshot =
                CompatRuntimeState.getGuardedDynamicSnapshot((AbstractCard) __args[0], "GuardedNum.value");
            if (snapshot != null) {
                return SpireReturn.Return(snapshot.value);
            }
            return SpireReturn.Continue();
        }
    }

    @SpirePatch2(
        requiredModId = "duelistmod-zh",
        cls = "duelistmod.variables.GuardedNum",
        method = "isModified",
        paramtypez = {AbstractCard.class}
    )
    public static class GuardedNumIsModifiedPatch {
        public static SpireReturn<Boolean> Prefix(Object[] __args) {
            CompatRuntimeState.GuardedDynamicSnapshot snapshot =
                CompatRuntimeState.getGuardedDynamicSnapshot((AbstractCard) __args[0], "GuardedNum.isModified");
            if (snapshot != null) {
                return SpireReturn.Return(snapshot.modified);
            }
            return SpireReturn.Continue();
        }
    }
}

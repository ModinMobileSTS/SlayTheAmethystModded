package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.cards.AbstractCard;

public class AmethystRuntimeCompatPatches {
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

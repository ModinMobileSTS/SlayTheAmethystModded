package io.stamethyst.compatmod;

import basemod.BaseMod;
import com.evacipated.cardcrawl.modthespire.lib.ByRef;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.characters.CharacterManager;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.screens.charSelect.CharacterSelectScreen;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;

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

    @SpirePatch2(
        clz = MainMenuScreen.class,
        method = SpirePatch.CONSTRUCTOR,
        paramtypez = {boolean.class}
    )
    public static class MainMenuScreenConstructorPatch {
        public static void Prefix(Object[] __args) {
            RuntimeMemoryDiagnostics.onMainMenuConstructorBegin(
                RuntimeMemoryDiagnostics.getBooleanArgument(__args, 0, false)
            );
        }

        public static void Postfix(MainMenuScreen __instance, Object[] __args) {
            RuntimeMemoryDiagnostics.onMainMenuConstructorEnd(
                __instance,
                RuntimeMemoryDiagnostics.getBooleanArgument(__args, 0, false)
            );
        }
    }

    @SpirePatch2(
        clz = CharacterSelectScreen.class,
        method = "initialize"
    )
    public static class CharacterSelectInitializePatch {
        public static void Prefix(CharacterSelectScreen __instance) {
            RuntimeMemoryDiagnostics.onCharacterSelectInitializeBegin(__instance);
        }

        public static void Postfix(CharacterSelectScreen __instance) {
            RuntimeMemoryDiagnostics.onCharacterSelectInitializeEnd(__instance);
        }
    }

    @SpirePatch2(
        clz = CharacterSelectScreen.class,
        method = "open",
        paramtypez = {boolean.class}
    )
    public static class CharacterSelectOpenPatch {
        public static void Postfix(CharacterSelectScreen __instance, Object[] __args) {
            RuntimeMemoryDiagnostics.onCharacterSelectOpen(
                __instance,
                RuntimeMemoryDiagnostics.getBooleanArgument(__args, 0, false)
            );
        }
    }

    @SpirePatch2(
        clz = CharacterManager.class,
        method = "recreateCharacter",
        paramtypez = {AbstractPlayer.PlayerClass.class}
    )
    public static class CharacterManagerRecreateCharacterPatch {
        public static SpireReturn<AbstractPlayer> Prefix(CharacterManager __instance, Object[] __args) {
            if (__args == null || __args.length == 0 || !(__args[0] instanceof AbstractPlayer.PlayerClass)) {
                return SpireReturn.Continue();
            }
            AbstractPlayer.PlayerClass playerClass = (AbstractPlayer.PlayerClass) __args[0];
            RuntimeMemoryDiagnostics.onCharacterRecreateBegin(__instance, playerClass);
            AbstractPlayer reused = RuntimeMemoryDiagnostics.tryReuseCharacterPreview(
                __instance,
                playerClass
            );
            if (reused != null) {
                RuntimeMemoryDiagnostics.onCharacterRecreateShortcut(
                    __instance,
                    playerClass,
                    reused,
                    "menu_preview_reuse"
                );
                return SpireReturn.Return(reused);
            }
            return SpireReturn.Continue();
        }

        public static AbstractPlayer Postfix(
            AbstractPlayer __result,
            CharacterManager __instance,
            Object[] __args
        ) {
            if (__args != null && __args.length > 0 && __args[0] instanceof AbstractPlayer.PlayerClass) {
                RuntimeMemoryDiagnostics.onCharacterRecreateEnd(
                    __instance,
                    (AbstractPlayer.PlayerClass) __args[0],
                    __result
                );
            }
            return __result;
        }
    }

    @SpirePatch2(
        requiredModId = "basemod",
        clz = BaseMod.class,
        method = "generateCharacterOptions"
    )
    public static class BaseModGenerateCharacterOptionsPatch {
        public static SpireReturn<java.util.ArrayList<com.megacrit.cardcrawl.screens.charSelect.CharacterOption>> Prefix() {
            if (!RuntimeMemoryDiagnostics.isMainMenuPreviewReuseEnabled()) {
                return SpireReturn.Continue();
            }
            return SpireReturn.Return(RuntimeMemoryDiagnostics.buildModdedCharacterOptions());
        }
    }

    @SpirePatch2(
        clz = CardCrawlGame.class,
        method = "update"
    )
    public static class CardCrawlGameUpdatePatch {
        public static void Postfix() {
            RuntimeMemoryDiagnostics.observeGameState();
        }
    }

    @SpirePatch2(
        clz = CardCrawlGame.class,
        method = "createCharacter",
        paramtypez = {AbstractPlayer.PlayerClass.class}
    )
    public static class CardCrawlGameCreateCharacterPatch {
        public static void Prefix(Object[] __args) {
            if (__args == null || __args.length == 0 || !(__args[0] instanceof AbstractPlayer.PlayerClass)) {
                return;
            }
            RuntimeMemoryDiagnostics.onCreateCharacterBegin((AbstractPlayer.PlayerClass) __args[0]);
        }

        public static AbstractPlayer Postfix(AbstractPlayer __result, Object[] __args) {
            if (__args != null && __args.length > 0 && __args[0] instanceof AbstractPlayer.PlayerClass) {
                RuntimeMemoryDiagnostics.onCreateCharacterEnd(
                    (AbstractPlayer.PlayerClass) __args[0],
                    __result
                );
            }
            return __result;
        }
    }

    @SpirePatch2(
        clz = CardCrawlGame.class,
        method = "startOver"
    )
    public static class CardCrawlGameStartOverPatch {
        public static void Postfix() {
            RuntimeMemoryDiagnostics.onStartOverRequested("startOver");
        }
    }

    @SpirePatch2(
        clz = CardCrawlGame.class,
        method = "startOverButShowCredits"
    )
    public static class CardCrawlGameStartOverButShowCreditsPatch {
        public static void Postfix() {
            RuntimeMemoryDiagnostics.onStartOverRequested("startOverButShowCredits");
        }
    }

    @SpirePatch2(
        clz = AbstractDungeon.class,
        method = "reset"
    )
    public static class AbstractDungeonResetPatch {
        public static void Prefix() {
            RuntimeMemoryDiagnostics.onAbstractDungeonResetBegin();
        }

        public static void Postfix() {
            RuntimeMemoryDiagnostics.onAbstractDungeonResetEnd();
        }
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "dispose"
    )
    public static class AbstractPlayerDisposePatch {
        public static void Prefix(AbstractPlayer __instance) {
            RuntimeMemoryDiagnostics.onPlayerDispose(__instance);
        }
    }
}

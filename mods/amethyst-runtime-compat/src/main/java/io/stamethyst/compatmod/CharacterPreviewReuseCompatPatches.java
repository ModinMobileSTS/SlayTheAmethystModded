package io.stamethyst.compatmod;

import basemod.BaseMod;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.characters.CharacterManager;
import com.megacrit.cardcrawl.screens.charSelect.CharacterSelectScreen;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;

public final class CharacterPreviewReuseCompatPatches {
    private CharacterPreviewReuseCompatPatches() {
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
}

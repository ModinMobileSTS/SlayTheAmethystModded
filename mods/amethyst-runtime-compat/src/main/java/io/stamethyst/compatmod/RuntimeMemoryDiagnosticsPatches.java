package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

public final class RuntimeMemoryDiagnosticsPatches {
    private RuntimeMemoryDiagnosticsPatches() {
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

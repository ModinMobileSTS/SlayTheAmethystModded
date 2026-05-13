package io.stamethyst.compatmod;

import com.badlogic.gdx.backends.lwjgl.LwjglApplication;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.screens.mainMenu.MenuButton;

public final class ExpectedGameExitPatches {
    private ExpectedGameExitPatches() {
    }

    @SpirePatch2(
        clz = MenuButton.class,
        method = "buttonEffect"
    )
    public static class MenuButtonButtonEffectPatch {
        public static void Prefix(MenuButton __instance) {
            if (__instance != null && __instance.result == MenuButton.ClickResult.QUIT) {
                ExpectedGameExitMarker.mark("main_menu_quit");
            }
        }
    }

    @SpirePatch2(
        clz = LwjglApplication.class,
        method = "exit"
    )
    public static class LwjglApplicationExitPatch {
        public static void Prefix() {
            ExpectedGameExitMarker.mark("gdx_app_exit");
        }
    }
}

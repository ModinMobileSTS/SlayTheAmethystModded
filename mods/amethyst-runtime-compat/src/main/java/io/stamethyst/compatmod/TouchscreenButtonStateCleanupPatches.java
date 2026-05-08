package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.screens.mainMenu.MenuCancelButton;
import com.megacrit.cardcrawl.ui.buttons.CancelButton;
import com.megacrit.cardcrawl.ui.buttons.CardSelectConfirmButton;
import com.megacrit.cardcrawl.ui.buttons.ConfirmButton;
import com.megacrit.cardcrawl.ui.buttons.GridSelectConfirmButton;
import com.megacrit.cardcrawl.ui.buttons.PeekButton;
import com.megacrit.cardcrawl.ui.buttons.ProceedButton;
import com.megacrit.cardcrawl.ui.buttons.UnlockConfirmButton;

import java.lang.reflect.Field;

public final class TouchscreenButtonStateCleanupPatches {
    private static final Field PROCEED_BUTTON_HB_FIELD = resolveProceedButtonHitboxField();

    private TouchscreenButtonStateCleanupPatches() {
    }

    @SpirePatch2(
        clz = ConfirmButton.class,
        method = "hide"
    )
    public static class ConfirmButtonHidePatch {
        @SpirePostfixPatch
        public static void after(ConfirmButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = ConfirmButton.class,
        method = "hideInstantly"
    )
    public static class ConfirmButtonHideInstantlyPatch {
        @SpirePostfixPatch
        public static void after(ConfirmButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = GridSelectConfirmButton.class,
        method = "hide"
    )
    public static class GridSelectConfirmButtonHidePatch {
        @SpirePostfixPatch
        public static void after(GridSelectConfirmButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = GridSelectConfirmButton.class,
        method = "hideInstantly"
    )
    public static class GridSelectConfirmButtonHideInstantlyPatch {
        @SpirePostfixPatch
        public static void after(GridSelectConfirmButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = CardSelectConfirmButton.class,
        method = "hide"
    )
    public static class CardSelectConfirmButtonHidePatch {
        @SpirePostfixPatch
        public static void after(CardSelectConfirmButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = CardSelectConfirmButton.class,
        method = "hideInstantly"
    )
    public static class CardSelectConfirmButtonHideInstantlyPatch {
        @SpirePostfixPatch
        public static void after(CardSelectConfirmButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = CancelButton.class,
        method = "hide"
    )
    public static class CancelButtonHidePatch {
        @SpirePostfixPatch
        public static void after(CancelButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = CancelButton.class,
        method = "hideInstantly"
    )
    public static class CancelButtonHideInstantlyPatch {
        @SpirePostfixPatch
        public static void after(CancelButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = MenuCancelButton.class,
        method = "hide"
    )
    public static class MenuCancelButtonHidePatch {
        @SpirePostfixPatch
        public static void after(MenuCancelButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = MenuCancelButton.class,
        method = "hideInstantly"
    )
    public static class MenuCancelButtonHideInstantlyPatch {
        @SpirePostfixPatch
        public static void after(MenuCancelButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = ProceedButton.class,
        method = "hide"
    )
    public static class ProceedButtonHidePatch {
        @SpirePostfixPatch
        public static void after(ProceedButton __instance) {
            resetHitbox(getProceedButtonHitbox(__instance));
        }
    }

    @SpirePatch2(
        clz = ProceedButton.class,
        method = "hideInstantly"
    )
    public static class ProceedButtonHideInstantlyPatch {
        @SpirePostfixPatch
        public static void after(ProceedButton __instance) {
            resetHitbox(getProceedButtonHitbox(__instance));
        }
    }

    @SpirePatch2(
        clz = PeekButton.class,
        method = "hide"
    )
    public static class PeekButtonHidePatch {
        @SpirePostfixPatch
        public static void after(PeekButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = PeekButton.class,
        method = "hideInstantly"
    )
    public static class PeekButtonHideInstantlyPatch {
        @SpirePostfixPatch
        public static void after(PeekButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = UnlockConfirmButton.class,
        method = "hide"
    )
    public static class UnlockConfirmButtonHidePatch {
        @SpirePostfixPatch
        public static void after(UnlockConfirmButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    private static void resetHitbox(Hitbox hitbox) {
        if (!CompatRuntimeState.isTouchscreenStateCleanupEnabled() || hitbox == null) {
            return;
        }
        hitbox.clicked = false;
        hitbox.clickStarted = false;
    }

    private static Hitbox getProceedButtonHitbox(ProceedButton button) {
        if (button == null || PROCEED_BUTTON_HB_FIELD == null) {
            return null;
        }
        try {
            return (Hitbox) PROCEED_BUTTON_HB_FIELD.get(button);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static Field resolveProceedButtonHitboxField() {
        try {
            Field field = ProceedButton.class.getDeclaredField("hb");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}

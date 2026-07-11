package io.stamethyst.compatmod.touch;

import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.helpers.input.InputHelper;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;

public final class TouchscreenTargetedCardReleasePatches {
    private static boolean targetedReleaseRecoveryLogged;
    private static boolean optimizedReleaseCancelLogged;

    private TouchscreenTargetedCardReleasePatches() {
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "clickAndDragCards"
    )
    public static class AbstractPlayerClickAndDragCardsPatch {
        @SpirePrefixPatch
        public static SpireReturn<Boolean> before(AbstractPlayer __instance) {
            if (!shouldCancelOptimizedTargetedRelease(__instance)) {
                return SpireReturn.Continue();
            }
            cancelOptimizedTargetedRelease(__instance);
            return SpireReturn.Return(Boolean.TRUE);
        }

        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(FieldAccess access) throws CannotCompileException {
                    if (!access.isReader()) {
                        return;
                    }
                    if (!InputHelper.class.getName().equals(access.getClassName())) {
                        return;
                    }
                    if (!"isMouseDown".equals(access.getFieldName())) {
                        return;
                    }
                    access.replace(
                        "{ $_ = "
                            + TouchscreenTargetedCardReleasePatches.class.getName()
                            + ".resolveMouseDownForTargetedCardRelease($proceed(), "
                            + "(" + AbstractPlayer.class.getName() + ") this); }"
                    );
                }
            };
        }
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "updateSingleTargetInput"
    )
    public static class AbstractPlayerUpdateSingleTargetInputPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> before(AbstractPlayer __instance) {
            if (!shouldCancelOptimizedTargetedRelease(__instance)) {
                return SpireReturn.Continue();
            }
            cancelOptimizedTargetedRelease(__instance);
            return SpireReturn.Return(null);
        }
    }

    public static boolean resolveMouseDownForTargetedCardRelease(
        boolean originalMouseDown,
        AbstractPlayer player
    ) {
        if (originalMouseDown || !shouldEnterTargetModeOnRelease(player)) {
            return originalMouseDown;
        }
        player.isDraggingCard = true;
        logTargetedReleaseRecoveryOnce();
        return true;
    }

    private static boolean shouldEnterTargetModeOnRelease(AbstractPlayer player) {
        if (!TouchscreenCardInputRuntime.isNativeTouchscreenCardInputActive()) {
            return false;
        }
        if (player == null || player.hoveredCard == null) {
            return false;
        }
        if (!InputHelper.justReleasedClickLeft) {
            return false;
        }
        if (!player.isHoveringDropZone || player.inSingleTargetMode) {
            return false;
        }
        return TouchscreenCardInputRuntime.isTargetedCard(player.hoveredCard);
    }

    private static boolean shouldCancelOptimizedTargetedRelease(AbstractPlayer player) {
        if (!TouchscreenCardInputRuntime.isNativeTouchscreenCardPlayOptimizationActive()) {
            return false;
        }
        if (player == null || player.hoveredCard == null) {
            return false;
        }
        if (!InputHelper.justReleasedClickLeft) {
            return false;
        }
        if (!TouchscreenCardInputRuntime.isTargetedCard(player.hoveredCard)) {
            return false;
        }
        return player.inSingleTargetMode || player.isHoveringDropZone;
    }

    private static void cancelOptimizedTargetedRelease(AbstractPlayer player) {
        TouchscreenCardInputRuntime.cancelTargetedCardSelectionOnRelease(player);
        if (optimizedReleaseCancelLogged) {
            return;
        }
        optimizedReleaseCancelLogged = true;
        System.out.println(
            "[amethyst-runtime-compat] touchscreen optimized targeted-card release cancelled"
        );
    }

    private static void logTargetedReleaseRecoveryOnce() {
        if (targetedReleaseRecoveryLogged) {
            return;
        }
        targetedReleaseRecoveryLogged = true;
        System.out.println(
            "[amethyst-runtime-compat] touchscreen targeted-card release continued into target mode"
        );
    }
}

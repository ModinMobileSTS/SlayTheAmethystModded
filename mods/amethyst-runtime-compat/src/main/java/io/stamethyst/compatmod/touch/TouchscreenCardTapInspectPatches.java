package io.stamethyst.compatmod.touch;

import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.helpers.Hitbox;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;
import javassist.expr.MethodCall;

public final class TouchscreenCardTapInspectPatches {
    private TouchscreenCardTapInspectPatches() {
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "clickAndDragCards"
    )
    public static class AbstractPlayerClickAndDragCardsPatch {
        @SpirePrefixPatch
        public static void before(AbstractPlayer __instance) {
            TouchscreenCardInputRuntime.beforeTouchscreenTapInspect(__instance);
        }

        @SpirePostfixPatch
        public static void after(AbstractPlayer __instance) {
            TouchscreenCardInputRuntime.afterTouchscreenTapInspect(__instance);
        }
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "releaseCard"
    )
    public static class AbstractPlayerReleaseCardPatch {
        @SpirePrefixPatch
        public static void before(AbstractPlayer __instance) {
            TouchscreenCardInputRuntime.beforeReleaseCard(__instance);
        }
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "playCard"
    )
    public static class AbstractPlayerPlayCardPatch {
        @SpirePrefixPatch
        public static void before(AbstractPlayer __instance) {
            TouchscreenCardInputRuntime.beforePlayCard(__instance);
        }
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "updateInput"
    )
    public static class AbstractPlayerUpdateInputPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(FieldAccess access) throws CannotCompileException {
                    if (!access.isWriter()) {
                        return;
                    }
                    if (!AbstractCard.class.getName().equals(access.getClassName())) {
                        return;
                    }
                    if ("current_y".equals(access.getFieldName())) {
                        access.replace(
                            "{ "
                                + TouchscreenCardInputRuntime.class.getName()
                                + ".setAnimatedTouchHoverCurrentY(("
                                + AbstractCard.class.getName()
                                + ") $0, $1); }"
                        );
                        return;
                    }
                    if ("drawScale".equals(access.getFieldName())) {
                        access.replace(
                            "{ "
                                + TouchscreenCardInputRuntime.class.getName()
                                + ".setAnimatedTouchHoverDrawScale(("
                                + AbstractCard.class.getName()
                                + ") $0, $1); }"
                        );
                    }
                }

                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if (!AbstractCard.class.getName().equals(call.getClassName())) {
                        return;
                    }
                    if (!"isHoveredInHand".equals(call.getMethodName())) {
                        return;
                    }
                    call.replace(
                        "{ $_ = "
                            + TouchscreenCardInputRuntime.class.getName()
                            + ".resolveInspectedCardHoveredInHand($proceed($$), $0); }"
                    );
                }
            };
        }
    }

    @SpirePatch2(
        clz = AbstractCard.class,
        method = "hover"
    )
    public static class AbstractCardHoverPatch {
        @SpirePrefixPatch
        public static void before(AbstractCard __instance) {
            TouchscreenCardInputRuntime.beforeCardHover(__instance);
        }

        @SpirePostfixPatch
        public static void after(AbstractCard __instance) {
            TouchscreenCardInputRuntime.afterCardHover(__instance);
        }
    }

    @SpirePatch2(
        clz = AbstractCard.class,
        method = "updateHoverLogic"
    )
    public static class AbstractCardUpdateHoverLogicPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(FieldAccess access) throws CannotCompileException {
                    if (!access.isReader()) {
                        return;
                    }
                    if (!Hitbox.class.getName().equals(access.getClassName())) {
                        return;
                    }
                    if (!"hovered".equals(access.getFieldName())) {
                        return;
                    }
                    access.replace(
                        "{ $_ = "
                            + TouchscreenCardInputRuntime.class.getName()
                            + ".resolveInspectedCardHover($proceed(), "
                            + "("
                            + AbstractCard.class.getName()
                            + ") this); }"
                    );
                }
            };
        }
    }

    @SpirePatch2(
        clz = CardGroup.class,
        method = "refreshHandLayout"
    )
    public static class CardGroupRefreshHandLayoutPatch {
        @SpirePostfixPatch
        public static void after(CardGroup __instance) {
            TouchscreenCardInputRuntime.afterHandRefreshLayoutForTouchInspect(__instance);
        }
    }
}

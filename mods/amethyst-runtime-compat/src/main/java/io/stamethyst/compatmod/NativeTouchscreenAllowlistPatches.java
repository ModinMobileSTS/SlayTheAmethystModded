package io.stamethyst.compatmod;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.blights.AbstractBlight;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.GameCursor;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.rooms.CampfireUI;
import com.megacrit.cardcrawl.screens.CardRewardScreen;
import com.megacrit.cardcrawl.shop.ShopScreen;
import com.megacrit.cardcrawl.shop.StorePotion;
import com.megacrit.cardcrawl.shop.StoreRelic;
import com.megacrit.cardcrawl.ui.campfire.AbstractCampfireOption;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;

public final class NativeTouchscreenAllowlistPatches {
    private NativeTouchscreenAllowlistPatches() {
    }

    @SpirePatch2(
        clz = GameCursor.class,
        method = "render",
        paramtypez = {SpriteBatch.class}
    )
    public static class GameCursorRenderPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveTouchIndicatorTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = ShopScreen.class,
        method = "resetTouchscreenVars"
    )
    public static class ShopScreenResetTouchscreenVarsPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveVanillaShopTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = ShopScreen.class,
        method = "update"
    )
    public static class ShopScreenUpdatePatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveVanillaShopTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = ShopScreen.class,
        method = "updateCards"
    )
    public static class ShopScreenUpdateCardsPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveVanillaShopTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = ShopScreen.class,
        method = "updatePurgeCard"
    )
    public static class ShopScreenUpdatePurgeCardPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveVanillaShopTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = ShopScreen.class,
        method = "render",
        paramtypez = {SpriteBatch.class}
    )
    public static class ShopScreenRenderPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveVanillaShopTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = StoreRelic.class,
        method = "update",
        paramtypez = {float.class}
    )
    public static class StoreRelicUpdatePatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveVanillaShopTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = StorePotion.class,
        method = "update",
        paramtypez = {float.class}
    )
    public static class StorePotionUpdatePatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveVanillaShopTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = CardRewardScreen.class,
        method = "update"
    )
    public static class CardRewardScreenUpdatePatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveVanillaAllowlistedTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = CardRewardScreen.class,
        method = "render",
        paramtypez = {SpriteBatch.class}
    )
    public static class CardRewardScreenRenderPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveVanillaAllowlistedTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = CardRewardScreen.class,
        method = "cardSelectUpdate"
    )
    public static class CardRewardScreenCardSelectUpdatePatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveVanillaAllowlistedTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = CampfireUI.class,
        method = "updateTouchscreen"
    )
    public static class CampfireUiUpdateTouchscreenPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveVanillaAllowlistedTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = CampfireUI.class,
        method = "render",
        paramtypez = {SpriteBatch.class}
    )
    public static class CampfireUiRenderPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveVanillaAllowlistedTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = AbstractCampfireOption.class,
        method = "update"
    )
    public static class AbstractCampfireOptionUpdatePatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveVanillaAllowlistedTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "updateInput"
    )
    public static class AbstractPlayerUpdateInputPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveVanillaAllowlistedTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "updateSingleTargetInput"
    )
    public static class AbstractPlayerUpdateSingleTargetInputPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveVanillaAllowlistedTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "clickAndDragCards"
    )
    public static class AbstractPlayerClickAndDragCardsPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveVanillaAllowlistedTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = AbstractRelic.class,
        method = "update"
    )
    public static class AbstractRelicUpdatePatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveRelicTouchscreen($proceed(), this)"
            );
        }
    }

    @SpirePatch2(
        clz = AbstractBlight.class,
        method = "update"
    )
    public static class AbstractBlightUpdatePatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createTouchscreenFieldEditor(
                NativeTouchscreenAllowlistPatches.class.getName()
                    + ".resolveBlightTouchscreen($proceed(), this)"
            );
        }
    }

    public static boolean resolveTouchIndicatorTouchscreen(boolean originalTouchscreen) {
        return CompatRuntimeState.resolveTouchIndicatorFlag(originalTouchscreen);
    }

    public static boolean resolveVanillaShopTouchscreen(boolean originalTouchscreen) {
        return CompatRuntimeState.resolveVanillaShopTouchscreenFlag(originalTouchscreen);
    }

    public static boolean resolveVanillaAllowlistedTouchscreen(boolean originalTouchscreen) {
        return CompatRuntimeState.resolveVanillaAllowlistedTouchscreenFlag(originalTouchscreen);
    }

    public static boolean resolveRelicTouchscreen(
        boolean originalTouchscreen,
        AbstractRelic relic
    ) {
        return CompatRuntimeState.resolveRelicTouchscreenForObtain(originalTouchscreen, relic);
    }

    public static boolean resolveBlightTouchscreen(
        boolean originalTouchscreen,
        AbstractBlight blight
    ) {
        return CompatRuntimeState.resolveBlightTouchscreenForObtain(originalTouchscreen, blight);
    }

    private static ExprEditor createTouchscreenFieldEditor(final String replacementExpression) {
        return new ExprEditor() {
            @Override
            public void edit(FieldAccess access) throws CannotCompileException {
                if (!access.isReader()) {
                    return;
                }
                if (!Settings.class.getName().equals(access.getClassName())) {
                    return;
                }
                if (!"isTouchScreen".equals(access.getFieldName())) {
                    return;
                }
                access.replace("{ $_ = " + replacementExpression + "; }");
            }
        };
    }
}

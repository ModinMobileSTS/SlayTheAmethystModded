package io.stamethyst.compatmod;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;
import com.megacrit.cardcrawl.screens.mainMenu.MenuButton;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;

public final class MainMenuTouchLayoutCompatPatches {
    private MainMenuTouchLayoutCompatPatches() {
    }

    @SpirePatch2(
        clz = MenuButton.class,
        method = SpirePatch.CONSTRUCTOR,
        paramtypez = {MenuButton.ClickResult.class, int.class}
    )
    public static class MenuButtonConstructorPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createSettingsFieldEditor(
                "isTouchScreen",
                MainMenuTouchLayoutCompatPatches.class.getName()
                    + ".resolveMainMenuTouchLayoutTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = MenuButton.class,
        method = "render",
        paramtypez = {SpriteBatch.class}
    )
    public static class MenuButtonRenderPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createSettingsFieldEditor(
                "isTouchScreen",
                MainMenuTouchLayoutCompatPatches.class.getName()
                    + ".resolveMainMenuTouchLayoutTouchscreen($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = MainMenuScreen.class,
        method = SpirePatch.CONSTRUCTOR,
        paramtypez = {boolean.class}
    )
    public static class MainMenuScreenConstructorPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createSettingsFieldEditor(
                "isMobile",
                MainMenuTouchLayoutCompatPatches.class.getName()
                    + ".resolveMainMenuTouchLayoutMobile($proceed())"
            );
        }
    }

    @SpirePatch2(
        clz = MainMenuScreen.class,
        method = "renderNameEdit",
        paramtypez = {SpriteBatch.class}
    )
    public static class MainMenuScreenRenderNameEditPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createSettingsFieldEditor(
                "isMobile",
                MainMenuTouchLayoutCompatPatches.class.getName()
                    + ".resolveMainMenuTouchLayoutMobile($proceed())"
            );
        }
    }

    public static boolean resolveMainMenuTouchLayoutTouchscreen(boolean originalValue) {
        return CompatRuntimeState.resolveMainMenuTouchLayoutTouchscreenFlag(originalValue);
    }

    public static boolean resolveMainMenuTouchLayoutMobile(boolean originalValue) {
        return CompatRuntimeState.resolveMainMenuTouchLayoutMobileFlag(originalValue);
    }

    private static ExprEditor createSettingsFieldEditor(
        final String fieldName,
        final String replacementExpression
    ) {
        return new ExprEditor() {
            @Override
            public void edit(FieldAccess access) throws CannotCompileException {
                if (!access.isReader()) {
                    return;
                }
                if (!Settings.class.getName().equals(access.getClassName())) {
                    return;
                }
                if (!fieldName.equals(access.getFieldName())) {
                    return;
                }
                access.replace("{ $_ = " + replacementExpression + "; }");
            }
        };
    }
}

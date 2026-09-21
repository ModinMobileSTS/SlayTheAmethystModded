package io.stamethyst.compatmod.ui;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.screens.options.DropdownMenu;
import com.megacrit.cardcrawl.screens.options.OptionsPanel;
import com.megacrit.cardcrawl.screens.options.ToggleButton;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import java.util.Map;
import java.util.WeakHashMap;

public final class DisplaySettingsControlsCompatPatches {
    private static final Map<DropdownMenu, Boolean> FIXED_DROPDOWNS =
        new WeakHashMap<DropdownMenu, Boolean>();
    private static final Map<ToggleButton, Boolean> DISABLED_TOGGLES =
        new WeakHashMap<ToggleButton, Boolean>();
    private DisplaySettingsControlsCompatPatches() {
    }

    private static boolean isFixedDropdown(DropdownMenu dropdown) {
        return dropdown != null && FIXED_DROPDOWNS.containsKey(dropdown);
    }

    public static boolean shouldRenderDropdown(DropdownMenu dropdown) {
        return !isFixedDropdown(dropdown);
    }

    private static boolean isDisabledToggle(ToggleButton toggle) {
        return toggle != null && DISABLED_TOGGLES.containsKey(toggle);
    }

    public static boolean shouldRenderToggle(ToggleButton toggle) {
        return !isDisabledToggle(toggle);
    }

    private static void clearHitbox(ToggleButton toggle) {
        if (toggle == null || toggle.hb == null) {
            return;
        }
        toggle.hb.hovered = false;
        toggle.hb.justHovered = false;
        toggle.hb.clickStarted = false;
        toggle.hb.clicked = false;
    }

    private static int savedWidth() {
        return Settings.SAVED_WIDTH > 0 ? Settings.SAVED_WIDTH : Settings.WIDTH;
    }

    private static int savedHeight() {
        return Settings.SAVED_HEIGHT > 0 ? Settings.SAVED_HEIGHT : Settings.HEIGHT;
    }

    private static float screenCenterY() {
        return Settings.HEIGHT / 2.0f - 64.0f * Settings.scale;
    }

    private static String[] resolutionLabels() {
        if (OptionsPanel.TEXT != null && OptionsPanel.TEXT.length > 3) {
            String[] labels = OptionsPanel.TEXT[3].split(" NL ");
            if (labels.length >= 2) {
                return labels;
            }
        }
        return new String[] {"Resolution:", "Max Framerate:"};
    }

    private static float valueX(String label) {
        return 410.0f * Settings.xScale
            + FontHelper.getWidth(FontHelper.cardDescFont_N, label, 1.0f)
            + 8.0f * Settings.scale;
    }

    private static void renderFixedValue(SpriteBatch sb, String value, String label, float y) {
        FontHelper.renderFont(
            sb,
            FontHelper.cardDescFont_N,
            value,
            valueX(label),
            y,
            Settings.GOLD_COLOR
        );
    }

    public static String filterHiddenGraphicsLabel(String text) {
        if (text == null || OptionsPanel.TEXT == null || OptionsPanel.TEXT.length <= 17) {
            return text;
        }
        if (OptionsPanel.TEXT[4].equals(text)) {
            String[] labels = text.split(" NL ");
            return labels.length > 2 ? "NL NL " + labels[2] : "";
        }
        if (OptionsPanel.TEXT[17].equals(text)) {
            return null;
        }
        return text;
    }

    @SpirePatch2(
        clz = OptionsPanel.class,
        method = SpirePatch.CONSTRUCTOR
    )
    public static class OptionsPanelConstructorPatch {
        public static void Postfix(OptionsPanel __instance) {
            if (__instance == null) {
                return;
            }
            if (__instance.resoDropdown != null) {
                FIXED_DROPDOWNS.put(__instance.resoDropdown, Boolean.TRUE);
            }
            if (__instance.fpsDropdown != null) {
                FIXED_DROPDOWNS.put(__instance.fpsDropdown, Boolean.TRUE);
            }
            if (__instance.fsToggle != null) {
                DISABLED_TOGGLES.put(__instance.fsToggle, Boolean.TRUE);
            }
            if (__instance.wfsToggle != null) {
                DISABLED_TOGGLES.put(__instance.wfsToggle, Boolean.TRUE);
            }
            if (__instance.vSyncToggle != null) {
                DISABLED_TOGGLES.put(__instance.vSyncToggle, Boolean.TRUE);
            }
        }
    }

    @SpirePatch2(
        clz = DropdownMenu.class,
        method = "update",
        paramtypez = {}
    )
    public static class FixedDropdownUpdatePatch {
        public static SpireReturn<Void> Prefix(DropdownMenu __instance) {
            if (!isFixedDropdown(__instance)) {
                return SpireReturn.Continue();
            }
            __instance.isOpen = false;
            if (__instance.getHitbox() != null) {
                __instance.getHitbox().unhover();
            }
            return SpireReturn.Return(null);
        }
    }

    @SpirePatch2(
        clz = DropdownMenu.class,
        method = "render",
        paramtypez = {SpriteBatch.class, float.class, float.class}
    )
    public static class FixedDropdownRenderPatch {
        public static SpireReturn<Void> Prefix(DropdownMenu __instance) {
            if (isFixedDropdown(__instance)) {
                return SpireReturn.Return(null);
            }
            return SpireReturn.Continue();
        }
    }

    @SpirePatch2(
        clz = OptionsPanel.class,
        method = "renderGraphics",
        paramtypez = {SpriteBatch.class}
    )
    public static class HiddenGraphicsLabelsPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if (!FontHelper.class.getName().equals(call.getClassName())
                        || !"renderSmartText".equals(call.getMethodName())) {
                        return;
                    }
                    call.replace(
                        "{ $proceed($1, $2, "
                            + DisplaySettingsControlsCompatPatches.class.getName()
                            + ".filterHiddenGraphicsLabel($3), $4, $5, $6, $7, $8); }"
                    );
                }
            };
        }
    }

    @SpirePatch2(
        clz = OptionsPanel.class,
        method = "renderGraphics",
        paramtypez = {SpriteBatch.class}
    )
    public static class HiddenGraphicsControlsPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if (DropdownMenu.class.getName().equals(call.getClassName())
                        && "render".equals(call.getMethodName())) {
                        call.replace(
                            "{ if ("
                                + DisplaySettingsControlsCompatPatches.class.getName()
                                + ".shouldRenderDropdown($0)) { $proceed($$); } }"
                        );
                        return;
                    }
                    if (ToggleButton.class.getName().equals(call.getClassName())
                        && "render".equals(call.getMethodName())) {
                        call.replace(
                            "{ if ("
                                + DisplaySettingsControlsCompatPatches.class.getName()
                                + ".shouldRenderToggle($0)) { $proceed($$); } }"
                        );
                    }
                }
            };
        }
    }

    @SpirePatch2(
        clz = OptionsPanel.class,
        method = "changeFrameRateToIndex",
        paramtypez = {int.class}
    )
    public static class FixedFrameRateActionPatch {
        public static SpireReturn<Void> Prefix(OptionsPanel __instance) {
            return SpireReturn.Return(null);
        }
    }

    @SpirePatch2(
        clz = OptionsPanel.class,
        method = "changeResolutionToIndex",
        paramtypez = {int.class}
    )
    public static class FixedResolutionActionPatch {
        public static SpireReturn<Void> Prefix(OptionsPanel __instance) {
            return SpireReturn.Return(null);
        }
    }

    @SpirePatch2(
        clz = ToggleButton.class,
        method = "update",
        paramtypez = {}
    )
    public static class DisabledToggleUpdatePatch {
        public static SpireReturn<Void> Prefix(ToggleButton __instance) {
            if (!isDisabledToggle(__instance)) {
                return SpireReturn.Continue();
            }
            clearHitbox(__instance);
            return SpireReturn.Return(null);
        }
    }

    @SpirePatch2(
        clz = ToggleButton.class,
        method = "toggle",
        paramtypez = {}
    )
    public static class DisabledToggleActionPatch {
        public static SpireReturn<Void> Prefix(ToggleButton __instance) {
            if (isDisabledToggle(__instance)) {
                return SpireReturn.Return(null);
            }
            return SpireReturn.Continue();
        }
    }

    @SpirePatch2(
        clz = ToggleButton.class,
        method = "render",
        paramtypez = {SpriteBatch.class}
    )
    public static class DisabledToggleRenderPatch {
        public static SpireReturn<Void> Prefix(ToggleButton __instance) {
            if (isDisabledToggle(__instance)) {
                return SpireReturn.Return(null);
            }
            return SpireReturn.Continue();
        }
    }

    @SpirePatch2(
        clz = OptionsPanel.class,
        method = "renderGraphics",
        paramtypez = {SpriteBatch.class}
    )
    public static class OptionsPanelRenderGraphicsPatch {
        public static void Postfix(OptionsPanel __instance, SpriteBatch sb) {
            if (__instance == null || sb == null) {
                return;
            }
            float centerY = screenCenterY();
            String[] labels = resolutionLabels();
            renderFixedValue(
                sb,
                savedWidth() + " x " + savedHeight(),
                labels[0],
                centerY + 196.0f * Settings.scale
            );
            renderFixedValue(
                sb,
                Integer.toString(Settings.MAX_FPS),
                labels[1],
                centerY + 156.0f * Settings.scale
            );
        }
    }
}

package io.stamethyst.compatmod;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.screens.options.DropdownMenu;
import com.megacrit.cardcrawl.screens.options.OptionsPanel;

import java.util.ArrayList;

public final class ResolutionDropdownCompatPatches {
    private static final String EMPTY_RESOLUTION_LABEL = "N/A";

    private ResolutionDropdownCompatPatches() {
    }

    private static boolean hasDropdownRows(DropdownMenu dropdown) {
        return dropdown != null && dropdown.rows != null && !dropdown.rows.isEmpty();
    }

    private static boolean hasResolutionOptions() {
        return Settings.displayOptions != null && !Settings.displayOptions.isEmpty();
    }

    private static ArrayList<String> buildEmptyResolutionLabels() {
        ArrayList<String> labels = new ArrayList<String>(1);
        labels.add(EMPTY_RESOLUTION_LABEL);
        return labels;
    }

    private static void clampDropdownToAvailableRows(DropdownMenu dropdown) {
        if (dropdown == null || dropdown.rows == null || dropdown.rows.isEmpty()) {
            return;
        }
        int maxIndex = dropdown.rows.size() - 1;
        int selectedIndex = dropdown.getSelectedIndex();
        if (selectedIndex < 0) {
            dropdown.setSelectedIndex(0);
        } else if (selectedIndex > maxIndex) {
            dropdown.setSelectedIndex(maxIndex);
        }
        if (dropdown.topVisibleRowIndex < 0) {
            dropdown.topVisibleRowIndex = 0;
        } else if (dropdown.topVisibleRowIndex > maxIndex) {
            dropdown.topVisibleRowIndex = maxIndex;
        }
    }

    private static void normalizeResolutionDropdownState(OptionsPanel panel) {
        if (panel == null) {
            return;
        }
        if (!hasResolutionOptions() || !hasDropdownRows(panel.resoDropdown)) {
            ensureResolutionDropdownPlaceholder(panel);
            return;
        }
        clampDropdownToAvailableRows(panel.resoDropdown);
    }

    // Android can surface no desktop-compatible resolutions; show a placeholder instead of crashing.
    private static void ensureResolutionDropdownPlaceholder(OptionsPanel panel) {
        if (panel == null) {
            return;
        }
        if (hasDropdownRows(panel.resoDropdown) && (hasResolutionOptions() || panel.resoDropdown.rows.size() == 1)) {
            panel.resoDropdown.setSelectedIndex(0);
            panel.resoDropdown.topVisibleRowIndex = 0;
            if (!hasResolutionOptions()) {
                Settings.displayIndex = 0;
            }
            return;
        }
        panel.resoDropdown = new DropdownMenu(
            panel,
            buildEmptyResolutionLabels(),
            FontHelper.tipBodyFont,
            Settings.CREAM_COLOR
        );
        panel.resoDropdown.setSelectedIndex(0);
        panel.resoDropdown.topVisibleRowIndex = 0;
        Settings.displayIndex = 0;
    }

    @SpirePatch2(
        clz = OptionsPanel.class,
        method = "getResolutionLabels",
        paramtypez = {}
    )
    public static class OptionsPanelGetResolutionLabelsPatch {
        public static ArrayList<String> Postfix(ArrayList<String> __result) {
            if (__result != null && !__result.isEmpty()) {
                return __result;
            }
            return buildEmptyResolutionLabels();
        }
    }

    @SpirePatch2(
        clz = OptionsPanel.class,
        method = "getResolutionLabels",
        paramtypez = {int.class}
    )
    public static class OptionsPanelGetResolutionLabelsWithModePatch {
        public static ArrayList<String> Postfix(ArrayList<String> __result) {
            if (__result != null && !__result.isEmpty()) {
                return __result;
            }
            return buildEmptyResolutionLabels();
        }
    }

    @SpirePatch2(
        clz = OptionsPanel.class,
        method = "resetResolutionDropdownSelection"
    )
    public static class OptionsPanelResetResolutionDropdownSelectionPatch {
        public static SpireReturn<Void> Prefix(OptionsPanel __instance) {
            if (hasResolutionOptions() && hasDropdownRows(__instance.resoDropdown)) {
                return SpireReturn.Continue();
            }
            ensureResolutionDropdownPlaceholder(__instance);
            return SpireReturn.Return(null);
        }
    }

    @SpirePatch2(
        clz = OptionsPanel.class,
        method = "changeResolutionToIndex",
        paramtypez = {int.class}
    )
    public static class OptionsPanelChangeResolutionToIndexPatch {
        public static SpireReturn<Void> Prefix(OptionsPanel __instance, Object[] __args) {
            if (hasResolutionOptions()) {
                return SpireReturn.Continue();
            }
            ensureResolutionDropdownPlaceholder(__instance);
            return SpireReturn.Return(null);
        }
    }

    @SpirePatch2(
        clz = DropdownMenu.class,
        method = "setSelectedIndex",
        paramtypez = {int.class}
    )
    public static class DropdownMenuSetSelectedIndexPatch {
        public static SpireReturn<Void> Prefix(DropdownMenu __instance, Object[] __args) {
            if (hasDropdownRows(__instance)) {
                return SpireReturn.Continue();
            }
            return SpireReturn.Return(null);
        }
    }

    @SpirePatch2(
        clz = OptionsPanel.class,
        method = "render",
        paramtypez = {SpriteBatch.class}
    )
    public static class OptionsPanelRenderPatch {
        public static void Prefix(OptionsPanel __instance) {
            normalizeResolutionDropdownState(__instance);
        }
    }

    @SpirePatch2(
        clz = DropdownMenu.class,
        method = "visibleRowCount",
        paramtypez = {}
    )
    public static class DropdownMenuVisibleRowCountPatch {
        public static int Postfix(int __result, DropdownMenu __instance) {
            if (__instance == null || __instance.rows == null || __instance.rows.isEmpty()) {
                return 0;
            }
            int maxIndex = __instance.rows.size() - 1;
            if (__instance.topVisibleRowIndex < 0) {
                __instance.topVisibleRowIndex = 0;
            } else if (__instance.topVisibleRowIndex > maxIndex) {
                __instance.topVisibleRowIndex = maxIndex;
            }
            int remainingRows = __instance.rows.size() - __instance.topVisibleRowIndex;
            if (remainingRows <= 0) {
                return 0;
            }
            return Math.min(__result, remainingRows);
        }
    }
}

package io.stamethyst.compatmod;

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

    // Android can surface no desktop-compatible resolutions; show a placeholder instead of crashing.
    private static void ensureResolutionDropdownPlaceholder(OptionsPanel panel) {
        if (panel == null) {
            return;
        }
        if (hasDropdownRows(panel.resoDropdown)) {
            panel.resoDropdown.setSelectedIndex(0);
            panel.resoDropdown.topVisibleRowIndex = 0;
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
}

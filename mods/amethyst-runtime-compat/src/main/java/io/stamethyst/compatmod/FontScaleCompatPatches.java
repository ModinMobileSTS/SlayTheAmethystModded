package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.ByRef;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;

public final class FontScaleCompatPatches {
    private FontScaleCompatPatches() {
    }

    @SpirePatch2(
        clz = FontHelper.class,
        method = "prepFont",
        paramtypez = {float.class, boolean.class}
    )
    public static class FontHelperPrepFontPatch {
        public static void Prefix(@ByRef float[] size, boolean isLinearFiltering) {
            if (size == null || size.length == 0) {
                return;
            }
            float requestedSize = size[0];
            float remappedSize = CompatRuntimeState.remapPrepFontSize(
                requestedSize,
                Settings.BIG_TEXT_MODE
            );
            if (requestedSize != remappedSize) {
                size[0] = remappedSize;
            }
        }
    }
}

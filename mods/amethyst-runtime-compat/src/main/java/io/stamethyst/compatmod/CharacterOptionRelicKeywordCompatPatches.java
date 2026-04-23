package io.stamethyst.compatmod;

import basemod.patches.com.megacrit.cardcrawl.relics.AbstractRelic.MultiwordKeywords;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.screens.charSelect.CharacterOption;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;

public final class CharacterOptionRelicKeywordCompatPatches {
    private CharacterOptionRelicKeywordCompatPatches() {
    }

    @SpirePatch2(
        requiredModId = "basemod",
        clz = CharacterOption.class,
        method = "renderRelics"
    )
    public static class RenderRelicsPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(FieldAccess access) throws CannotCompileException {
                    if (!access.isReader()) {
                        return;
                    }
                    if (!AbstractRelic.class.getName().equals(access.getClassName())) {
                        return;
                    }
                    if (!"description".equals(access.getFieldName())) {
                        return;
                    }
                    // BaseMod normalizes starter-relic multiword keywords only on the desktop path.
                    access.replace(
                        "{ $_ = "
                            + CharacterOptionRelicKeywordCompatPatches.class.getName()
                            + ".normalizeSingleRelicDescription($proceed()); }"
                    );
                }
            };
        }
    }

    public static String normalizeSingleRelicDescription(String rawDescription) {
        if (rawDescription == null) {
            return null;
        }
        if (!CompatRuntimeState.resolveMobileLayoutFlag(Settings.isMobile)) {
            return rawDescription;
        }
        return MultiwordKeywords.replaceMultiWordKeywords(rawDescription);
    }
}

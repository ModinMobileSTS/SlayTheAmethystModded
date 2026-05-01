package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.actions.AbstractGameAction;
import com.megacrit.cardcrawl.actions.common.ApplyPowerAction;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.vfx.AbstractGameEffect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

public final class JacketNoAnoKoJesterFormCompatPatches {
    private static final String MOD_ID = "jacketnoanokomod";
    private static final String JESTER_FORM_CARD_CLASS = "jacketnoanokomod.cards.JesterForm";
    private static final String JESTER_FORM_POWER_CLASS = "jacketnoanokomod.powers.JesterFormPower";
    private static final String JESTER_FORM_ACTION_CLASS = "jacketnoanokomod.actions.JesterFormAction";
    private static final String COMBINED_FIREWORKS_EFFECT_CLASS =
        "jacketnoanokomod.effect.CombinedFireworksSpotlightEffect";
    private static final String MOD_MAIN_CLASS = "jacketnoanokomod.jacketnoanokomod";
    private static final String ENABLED_PROP =
        "amethyst.runtime_compat.jacketnoanoko_jester_form_shader";

    private static Constructor<?> jesterFormPowerConstructor;
    private static Constructor<?> jesterFormActionConstructor;
    private static Constructor<?> combinedFireworksEffectConstructor;
    private static Field shaderEffectSwitchField;
    private static boolean compatLogPrinted;
    private static boolean fallbackLogPrinted;

    private JacketNoAnoKoJesterFormCompatPatches() {
    }

    @SpirePatch2(
        cls = JESTER_FORM_CARD_CLASS,
        method = "use",
        paramtypez = {AbstractPlayer.class, AbstractMonster.class},
        requiredModId = MOD_ID,
        optional = true
    )
    public static class JesterFormUsePatch {
        public static SpireReturn<Void> Prefix(Object __instance, Object[] __args) {
            if (!shouldApplyCompat()) {
                return SpireReturn.Continue();
            }
            try {
                AbstractPlayer player = (AbstractPlayer)__args[0];
                AbstractDungeon.actionManager.addToBottom(
                    new ApplyPowerAction(player, player, createJesterFormPower(player, 1), 1)
                );
                addCombinedFireworksEffectSafely(8.0F, 0.4F, 0.5F);
                return SpireReturn.Return(null);
            } catch (Throwable throwable) {
                logFallbackOnce("JesterForm.use compatibility reimplementation failed", throwable);
                return SpireReturn.Continue();
            }
        }
    }

    @SpirePatch2(
        cls = JESTER_FORM_POWER_CLASS,
        method = "atStartOfTurnPostDraw",
        requiredModId = MOD_ID,
        optional = true
    )
    public static class JesterFormPowerStartTurnPatch {
        public static SpireReturn<Void> Prefix(Object __instance) {
            if (!shouldApplyCompat()) {
                return SpireReturn.Continue();
            }
            try {
                AbstractPower power = (AbstractPower)__instance;
                AbstractPlayer player = AbstractDungeon.player;
                AbstractDungeon.actionManager.addToBottom(
                    createJesterFormAction(player, player, power.amount, false)
                );
                addCombinedFireworksEffectSafely(8.0F, 0.4F, 0.5F);
                return SpireReturn.Return(null);
            } catch (Throwable throwable) {
                logFallbackOnce("JesterFormPower.atStartOfTurnPostDraw compatibility reimplementation failed", throwable);
                return SpireReturn.Continue();
            }
        }
    }

    private static AbstractPower createJesterFormPower(AbstractCreature owner, int amount) throws Exception {
        if (jesterFormPowerConstructor == null) {
            jesterFormPowerConstructor = Class.forName(JESTER_FORM_POWER_CLASS)
                .getConstructor(AbstractCreature.class, int.class);
        }
        return (AbstractPower)jesterFormPowerConstructor.newInstance(owner, amount);
    }

    private static AbstractGameAction createJesterFormAction(
        AbstractCreature target,
        AbstractCreature source,
        int amount,
        boolean isRandom
    ) throws Exception {
        if (jesterFormActionConstructor == null) {
            jesterFormActionConstructor = Class.forName(JESTER_FORM_ACTION_CLASS)
                .getConstructor(AbstractCreature.class, AbstractCreature.class, int.class, boolean.class);
        }
        return (AbstractGameAction)jesterFormActionConstructor.newInstance(
            target,
            source,
            amount,
            Boolean.valueOf(isRandom)
        );
    }

    private static void addCombinedFireworksEffectSafely(
        float duration,
        float detail,
        float fboScale
    ) {
        if (isModShaderEffectDisabled()) {
            return;
        }
        try {
            AbstractGameEffect effect = createCombinedFireworksEffect(duration, detail, fboScale);
            AbstractDungeon.effectList.add(effect);
            logCompatOnce();
        } catch (Throwable throwable) {
            logFallbackOnce("CombinedFireworksSpotlightEffect setup failed; skipping visual effect", throwable);
        }
    }

    private static AbstractGameEffect createCombinedFireworksEffect(
        float duration,
        float detail,
        float fboScale
    ) throws Exception {
        if (combinedFireworksEffectConstructor == null) {
            combinedFireworksEffectConstructor = Class.forName(COMBINED_FIREWORKS_EFFECT_CLASS)
                .getConstructor(float.class, float.class, float.class);
        }
        return (AbstractGameEffect)combinedFireworksEffectConstructor.newInstance(
            Float.valueOf(duration),
            Float.valueOf(detail),
            Float.valueOf(fboScale)
        );
    }

    private static boolean isModShaderEffectDisabled() {
        try {
            if (shaderEffectSwitchField == null) {
                shaderEffectSwitchField = Class.forName(MOD_MAIN_CLASS)
                    .getField("closeShaderEffectSwitch");
            }
            return shaderEffectSwitchField.getBoolean(null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean shouldApplyCompat() {
        if (!readBooleanSystemProperty(ENABLED_PROP, true)) {
            return false;
        }
        String osVersion = System.getProperty("os.version", "");
        if (osVersion.startsWith("Android-")) {
            return true;
        }
        return System.getProperty("amethyst.gdx.native_dir") != null;
    }

    private static boolean readBooleanSystemProperty(String name, boolean defaultValue) {
        String value = System.getProperty(name);
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return defaultValue;
        }
        if ("1".equals(normalized) || "true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("0".equals(normalized) || "false".equalsIgnoreCase(normalized)) {
            return false;
        }
        return defaultValue;
    }

    private static void logCompatOnce() {
        if (compatLogPrinted) {
            return;
        }
        compatLogPrinted = true;
        System.out.println(
            "[amethyst-runtime-compat] JacketNoAnoKo JesterForm shader effect guarded; " +
                "preserving gameplay if the visual shader fails"
        );
    }

    private static void logFallbackOnce(String message, Throwable throwable) {
        if (fallbackLogPrinted) {
            return;
        }
        fallbackLogPrinted = true;
        Throwable cause = unwrapInvocationTarget(throwable);
        StringBuilder builder = new StringBuilder();
        builder.append("[amethyst-runtime-compat] JacketNoAnoKo JesterForm shader fallback: ")
            .append(message);
        if (cause != null) {
            builder.append(" because ")
                .append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null && !cause.getMessage().isEmpty()) {
                builder.append(": ").append(cause.getMessage());
            }
        }
        System.out.println(builder.toString());
    }

    private static Throwable unwrapInvocationTarget(Throwable throwable) {
        if (throwable instanceof InvocationTargetException) {
            Throwable cause = ((InvocationTargetException)throwable).getCause();
            if (cause != null) {
                return cause;
            }
        }
        return throwable;
    }
}

package io.stamethyst.compatmod;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;
import com.megacrit.cardcrawl.screens.options.OptionsPanel;
import com.megacrit.cardcrawl.screens.options.SettingsScreen;
import com.megacrit.cardcrawl.vfx.AbstractGameEffect;
import com.megacrit.cardcrawl.vfx.RestartForChangesEffect;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

public final class DisplaySettingsPromptCompatPatches {
    private static final String LAUNCHER_SETTINGS_PROMPT =
        "请勿在此修改，请在启动器设置中调整画质选项。";
    private static final float PERSISTENT_DURATION = 2.0f;
    private static final float PULSE_PERIOD_SECONDS = 1.2f;
    private static final float PULSE_BASE_SCALE = 1.04f;
    private static final float PULSE_AMPLITUDE = 0.08f;
    private static final double PULSE_RADIANS_PER_SECOND =
        Math.PI * 2.0 / PULSE_PERIOD_SECONDS;
    private static final Map<RestartForChangesEffect, Float> PULSE_PHASE_BY_EFFECT =
        new WeakHashMap<RestartForChangesEffect, Float>();
    private static Field scaleField;
    private static Field colorField;
    private static boolean scaleFieldFailureLogged;
    private static boolean colorFieldFailureLogged;

    private DisplaySettingsPromptCompatPatches() {
    }

    private static void applyPersistentPromptState(RestartForChangesEffect effect) {
        if (effect == null) {
            return;
        }
        effect.duration = PERSISTENT_DURATION;
        effect.isDone = false;
        Color color = getPromptColor(effect);
        if (color != null) {
            color.a = 1.0f;
        }
    }

    private static void clearRestartPromptEffects(OptionsPanel panel) {
        if (panel == null || panel.effects == null || panel.effects.isEmpty()) {
            return;
        }
        Iterator<AbstractGameEffect> iterator = panel.effects.iterator();
        while (iterator.hasNext()) {
            AbstractGameEffect effect = iterator.next();
            if (effect instanceof RestartForChangesEffect) {
                PULSE_PHASE_BY_EFFECT.remove(effect);
                iterator.remove();
            }
        }
    }

    private static Color getPromptColor(RestartForChangesEffect effect) {
        try {
            Field field = getColorField();
            if (field == null) {
                return null;
            }
            return (Color) field.get(effect);
        } catch (Exception exception) {
            logFieldFailureOnce("color", exception);
            return null;
        }
    }

    private static Field getColorField() throws NoSuchFieldException {
        if (colorField == null) {
            colorField = RestartForChangesEffect.class.getDeclaredField("color");
            colorField.setAccessible(true);
        }
        return colorField;
    }

    private static Field getScaleField() throws NoSuchFieldException {
        if (scaleField == null) {
            scaleField = AbstractGameEffect.class.getDeclaredField("scale");
            scaleField.setAccessible(true);
        }
        return scaleField;
    }

    private static float getFrameDeltaTime() {
        if (Gdx.graphics == null) {
            return 1.0f / 60.0f;
        }
        return Gdx.graphics.getDeltaTime();
    }

    private static boolean isSettingsScreenOpen() {
        MainMenuScreen mainMenuScreen = CardCrawlGame.mainMenuScreen;
        if (mainMenuScreen != null && mainMenuScreen.isSettingsUp) {
            return true;
        }
        try {
            return AbstractDungeon.screen == AbstractDungeon.CurrentScreen.SETTINGS;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void logFieldFailureOnce(String fieldName, Exception exception) {
        if ("scale".equals(fieldName)) {
            if (scaleFieldFailureLogged) {
                return;
            }
            scaleFieldFailureLogged = true;
        } else {
            if (colorFieldFailureLogged) {
                return;
            }
            colorFieldFailureLogged = true;
        }
        System.out.println(
            "[amethyst-runtime-compat] display settings prompt field access failed field="
                + fieldName
                + " reason="
                + exception
        );
    }

    private static float nextPulsePhase(RestartForChangesEffect effect) {
        Float previousPhase = PULSE_PHASE_BY_EFFECT.get(effect);
        float phase = (previousPhase == null ? 0.0f : previousPhase.floatValue())
            + getFrameDeltaTime();
        while (phase > PULSE_PERIOD_SECONDS) {
            phase -= PULSE_PERIOD_SECONDS;
        }
        PULSE_PHASE_BY_EFFECT.put(effect, phase);
        return phase;
    }

    private static float pulseScale(float phase) {
        return PULSE_BASE_SCALE
            + PULSE_AMPLITUDE * (float)Math.sin(phase * PULSE_RADIANS_PER_SECOND);
    }

    private static void setPromptScale(RestartForChangesEffect effect, float scale) {
        try {
            Field field = getScaleField();
            if (field != null) {
                field.setFloat(effect, scale);
            }
        } catch (Exception exception) {
            logFieldFailureOnce("scale", exception);
        }
    }

    @SpirePatch2(
        clz = RestartForChangesEffect.class,
        method = SpirePatch.CONSTRUCTOR
    )
    public static class RestartForChangesEffectConstructorPatch {
        public static void Postfix() {
            if (RestartForChangesEffect.TEXT != null && RestartForChangesEffect.TEXT.length > 0) {
                RestartForChangesEffect.TEXT[0] = LAUNCHER_SETTINGS_PROMPT;
            }
        }
    }

    @SpirePatch2(
        clz = RestartForChangesEffect.class,
        method = "update"
    )
    public static class RestartForChangesEffectUpdatePatch {
        public static void Postfix(RestartForChangesEffect __instance) {
            if (!isSettingsScreenOpen()) {
                PULSE_PHASE_BY_EFFECT.remove(__instance);
                __instance.isDone = true;
                return;
            }

            float phase = nextPulsePhase(__instance);
            applyPersistentPromptState(__instance);
            setPromptScale(__instance, pulseScale(phase));
        }
    }

    @SpirePatch2(
        clz = MainMenuScreen.class,
        method = "update"
    )
    public static class MainMenuScreenUpdatePatch {
        public static void Postfix(MainMenuScreen __instance) {
            if (__instance == null || __instance.isSettingsUp) {
                return;
            }
            clearRestartPromptEffects(__instance.optionPanel);
        }
    }

    @SpirePatch2(
        clz = AbstractDungeon.class,
        method = "closeCurrentScreen"
    )
    public static class AbstractDungeonCloseCurrentScreenPatch {
        public static void Prefix() {
            if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.SETTINGS
                && AbstractDungeon.settingsScreen != null) {
                clearRestartPromptEffects(AbstractDungeon.settingsScreen.panel);
            }
        }
    }

    @SpirePatch2(
        clz = SettingsScreen.class,
        method = "open",
        paramtypez = {}
    )
    public static class SettingsScreenOpenPatch {
        public static void Prefix(SettingsScreen __instance) {
            if (__instance != null) {
                clearRestartPromptEffects(__instance.panel);
            }
        }
    }
}

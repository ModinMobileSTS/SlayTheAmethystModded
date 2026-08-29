package io.stamethyst.compatmod.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.vfx.CardTrailEffect;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

/** Batches adjacent card-trail effects under one additive blend-state span. */
public final class CardTrailRenderBatchPatches {

    private static final int SRC_ALPHA = 770;
    private static final int ONE = 1;
    private static final int ONE_MINUS_SRC_ALPHA = 771;

    private static final IdentityHashMap<CardTrailEffect, Boolean> TRAIL_RUN_ENDS =
        new IdentityHashMap<CardTrailEffect, Boolean>();

    private static boolean additiveBlendActive;
    private static long preparedFrameId = Long.MIN_VALUE;
    private static ClassLoader preparedClassLoader;
    private static Field topLevelEffectsField;

    private CardTrailRenderBatchPatches() {}

    @SpirePatch2(clz = CardTrailEffect.class, method = "render")
    public static class CardTrailEffectRenderPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if (SpriteBatch.class.getName().equals(call.getClassName())
                        && "setBlendFunction".equals(call.getMethodName())) {
                        call.replace(
                            "{ io.stamethyst.compatmod.render.CardTrailRenderBatchPatches"
                                + ".setBlendFunction(this, $0, $1, $2); }"
                        );
                    }
                }
            };
        }
    }

    public static void setBlendFunction(
        CardTrailEffect effect,
        SpriteBatch batch,
        int srcFunc,
        int dstFunc
    ) {
        if (dstFunc == ONE) {
            prepareTrailRuns(effect, batch);
        }
        Boolean runEnd = TRAIL_RUN_ENDS.get(effect);
        if (runEnd == null) {
            batch.setBlendFunction(srcFunc, dstFunc);
            return;
        }

        if (dstFunc == ONE) {
            if (!additiveBlendActive) {
                batch.setBlendFunction(srcFunc, dstFunc);
                additiveBlendActive = true;
            }
            return;
        }

        if (runEnd.booleanValue()) {
            batch.setBlendFunction(srcFunc, dstFunc);
            additiveBlendActive = false;
        }
    }

    private static void prepareTrailRuns(CardTrailEffect target, SpriteBatch batch) {
        long frameId = Gdx.graphics == null ? Long.MIN_VALUE : Gdx.graphics.getFrameId();
        if (frameId == preparedFrameId && target.getClass().getClassLoader() == preparedClassLoader) {
            return;
        }

        restoreDefaultBlend(batch);
        TRAIL_RUN_ENDS.clear();
        preparedFrameId = frameId;
        ClassLoader classLoader = target.getClass().getClassLoader();
        try {
            if (classLoader != preparedClassLoader || topLevelEffectsField == null) {
                Class<?> dungeonClass = classLoader.loadClass(
                    "com.megacrit.cardcrawl.dungeons.AbstractDungeon"
                );
                topLevelEffectsField = dungeonClass.getField("topLevelEffects");
                preparedClassLoader = classLoader;
            }

            Object value = topLevelEffectsField.get(null);
            if (!(value instanceof ArrayList)) return;
            ArrayList<?> effects = (ArrayList<?>) value;
            Class<?> trailClass = target.getClass();

            int size = effects.size();
            for (int i = 0; i < size; i++) {
                Object effect = effects.get(i);
                if (!trailClass.isInstance(effect)) continue;

                boolean runEnd = i + 1 >= size || !trailClass.isInstance(effects.get(i + 1));
                TRAIL_RUN_ENDS.put((CardTrailEffect) effect, Boolean.valueOf(runEnd));
            }
        } catch (Throwable ignored) {
            topLevelEffectsField = null;
        }
    }

    private static void restoreDefaultBlend(SpriteBatch batch) {
        if (!additiveBlendActive) return;
        batch.setBlendFunction(SRC_ALPHA, ONE_MINUS_SRC_ALPHA);
        additiveBlendActive = false;
    }
}

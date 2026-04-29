package io.stamethyst.compatmod;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.vfx.AbstractGameEffect;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

public final class JacketNoAnoKoElectrocardiogramCompatPatches {
    private static final String EFFECT_CLASS = "jacketnoanokomod.effect.ElectrocardiogramLoeweEffect";
    private static final String VERTEX_SHADER_PATH =
        "jacketnoanokomodResources/shaders/common.vs";
    private static final String FRAGMENT_SHADER_PATH =
        "jacketnoanokomodResources/shaders/ElectrocardiogramLoewe.fs";
    private static final String ENABLED_PROP =
        "amethyst.runtime_compat.jacketnoanoko_electrocardiogram_shader";
    private static final Pattern FLOAT_PRECISION_PATTERN =
        Pattern.compile("(?m)^\\s*precision\\s+(?:lowp|mediump|highp)\\s+float\\s*;");
    private static final Pattern INT_PRECISION_PATTERN =
        Pattern.compile("(?m)^\\s*precision\\s+(?:lowp|mediump|highp)\\s+int\\s*;");
    private static final Map<Object, Boolean> INITIALIZED_EFFECTS = new WeakHashMap<Object, Boolean>();
    private static final Map<Object, Boolean> FALLBACK_WITHOUT_SHADER = new WeakHashMap<Object, Boolean>();
    private static boolean compatLogPrinted;
    private static boolean fallbackLogPrinted;
    private static Field shaderField;
    private static Field textureRegionField;
    private static Field elapsedTimeField;

    private JacketNoAnoKoElectrocardiogramCompatPatches() {
    }

    @SpirePatch2(
        cls = EFFECT_CLASS,
        method = "update",
        optional = true
    )
    public static class ElectrocardiogramUpdatePatch {
        public static SpireReturn<Void> Prefix(Object __instance) {
            if (!shouldPatchShaderEffect()) {
                return SpireReturn.Continue();
            }
            try {
                updateWithCompatShader(__instance);
            } catch (Throwable throwable) {
                logFallbackOnce("unexpected compatibility patch failure", throwable);
                updateWithoutShader(__instance);
            }
            return SpireReturn.Return(null);
        }
    }

    @SpirePatch2(
        cls = EFFECT_CLASS,
        method = "render",
        paramtypez = {SpriteBatch.class},
        optional = true
    )
    public static class ElectrocardiogramRenderPatch {
        public static SpireReturn<Void> Prefix(Object __instance, SpriteBatch sb) {
            if (!shouldPatchShaderEffect()) {
                return SpireReturn.Continue();
            }
            if (isFallbackWithoutShader(__instance)) {
                return SpireReturn.Return(null);
            }
            try {
                if (getFieldValue(__instance, "shader") == null) {
                    return SpireReturn.Return(null);
                }
            } catch (Throwable throwable) {
                FALLBACK_WITHOUT_SHADER.put(__instance, Boolean.TRUE);
                logFallbackOnce("shader field lookup failed during render", throwable);
                return SpireReturn.Return(null);
            }
            return SpireReturn.Continue();
        }
    }

    @SpirePatch2(
        cls = EFFECT_CLASS,
        method = "dispose",
        optional = true
    )
    public static class ElectrocardiogramDisposePatch {
        public static SpireReturn<Void> Prefix(Object __instance) {
            if (!shouldPatchShaderEffect()) {
                return SpireReturn.Continue();
            }
            if (isFallbackWithoutShader(__instance) || !hasShader(__instance)) {
                cleanupEffect(__instance);
                return SpireReturn.Return(null);
            }
            cleanupEffect(__instance);
            return SpireReturn.Continue();
        }
    }

    private static void updateWithCompatShader(Object instance) throws Exception {
        if (!(instance instanceof AbstractGameEffect)) {
            return;
        }

        AbstractGameEffect effect = (AbstractGameEffect)instance;
        if (!isInitialized(instance)) {
            if (tryInstallCompatShader(instance)) {
                FALLBACK_WITHOUT_SHADER.remove(instance);
                logCompatOnce();
            } else {
                FALLBACK_WITHOUT_SHADER.put(instance, Boolean.TRUE);
            }
            INITIALIZED_EFFECTS.put(instance, Boolean.TRUE);
            playInspirationSound();
        }

        float delta = getDeltaTime();
        float elapsedTime = getFloatFieldValue(instance, "elapsedTime");
        setFloatFieldValue(instance, "elapsedTime", elapsedTime + delta);
        effect.duration -= delta;
        if (effect.duration <= 0.0F) {
            effect.isDone = true;
            cleanupEffect(instance);
        }
    }

    private static boolean tryInstallCompatShader(Object instance) {
        ShaderProgram shader = null;
        try {
            String vertexSource = normalizeGles100Shader(
                Gdx.files.internal(VERTEX_SHADER_PATH).readString(),
                false
            );
            String fragmentSource = normalizeGles100Shader(
                Gdx.files.internal(FRAGMENT_SHADER_PATH).readString(),
                true
            );
            shader = new ShaderProgram(vertexSource, fragmentSource);
            if (!shader.isCompiled()) {
                logFallbackOnce("rewritten shader compilation failed: " + shader.getLog(), null);
                disposeSafely(shader);
                return false;
            }
            setFieldValue(instance, "shader", shader);
            shader = null;
            setFieldValue(instance, "textureRegion", new TextureRegion(ImageMaster.WHITE_SQUARE_IMG));
            return true;
        } catch (Throwable throwable) {
            disposeSafely(shader);
            logFallbackOnce("rewritten shader setup failed", throwable);
            return false;
        }
    }

    private static String normalizeGles100Shader(String source, boolean fragmentShader) {
        if (source == null || source.isEmpty()) {
            return "#version 100\n";
        }

        String body = removeLeadingVersionDirective(source);
        String normalized = "#version 100\n" + body;
        if (!fragmentShader) {
            return normalized;
        }
        return ensureDefaultPrecision(normalized);
    }

    private static String removeLeadingVersionDirective(String source) {
        int start = 0;
        if (source.charAt(0) == '\ufeff') {
            start = 1;
        }
        while (start < source.length() && Character.isWhitespace(source.charAt(start))) {
            start++;
        }
        if (!startsWithDirective(source, start, "#version")) {
            return source.substring(start);
        }
        int lineEnd = skipLine(source, start);
        return source.substring(lineEnd);
    }

    private static String ensureDefaultPrecision(String source) {
        boolean missingFloatPrecision = !FLOAT_PRECISION_PATTERN.matcher(source).find();
        boolean missingIntPrecision = !INT_PRECISION_PATTERN.matcher(source).find();
        if (!missingFloatPrecision && !missingIntPrecision) {
            return source;
        }

        String lineSeparator = detectLineSeparator(source);
        int insertIndex = findPrecisionInsertIndex(source);
        StringBuilder patched = new StringBuilder(source.length() + 160);
        patched.append(source, 0, insertIndex);
        appendPrecisionBlock(patched, lineSeparator, missingFloatPrecision, missingIntPrecision);
        patched.append(source, insertIndex, source.length());
        return patched.toString();
    }

    private static void appendPrecisionBlock(
        StringBuilder out,
        String lineSeparator,
        boolean missingFloatPrecision,
        boolean missingIntPrecision
    ) {
        out.append("#ifdef GL_ES").append(lineSeparator);
        out.append("#ifdef GL_FRAGMENT_PRECISION_HIGH").append(lineSeparator);
        if (missingFloatPrecision) {
            out.append("precision highp float;").append(lineSeparator);
        }
        if (missingIntPrecision) {
            out.append("precision highp int;").append(lineSeparator);
        }
        out.append("#else").append(lineSeparator);
        if (missingFloatPrecision) {
            out.append("precision mediump float;").append(lineSeparator);
        }
        if (missingIntPrecision) {
            out.append("precision mediump int;").append(lineSeparator);
        }
        out.append("#endif").append(lineSeparator);
        out.append("#endif").append(lineSeparator);
    }

    private static int findPrecisionInsertIndex(String source) {
        int cursor = 0;
        if (startsWithDirective(source, cursor, "#version")) {
            cursor = skipLine(source, cursor);
        }
        while (true) {
            int next = skipWhitespace(source, cursor);
            if (!startsWithDirective(source, next, "#extension")) {
                return cursor;
            }
            cursor = skipLine(source, next);
        }
    }

    private static boolean startsWithDirective(String source, int start, String directive) {
        if (start < 0 || start + directive.length() > source.length()) {
            return false;
        }
        if (!source.regionMatches(start, directive, 0, directive.length())) {
            return false;
        }
        int next = start + directive.length();
        return next == source.length() || Character.isWhitespace(source.charAt(next));
    }

    private static int skipLine(String source, int start) {
        int index = start;
        while (index < source.length()) {
            char current = source.charAt(index++);
            if (current == '\n') {
                break;
            }
        }
        return index;
    }

    private static int skipWhitespace(String source, int start) {
        int index = start;
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
            index++;
        }
        return index;
    }

    private static String detectLineSeparator(String source) {
        int newline = source.indexOf('\n');
        if (newline > 0 && source.charAt(newline - 1) == '\r') {
            return "\r\n";
        }
        return "\n";
    }

    private static void updateWithoutShader(Object instance) {
        if (!(instance instanceof AbstractGameEffect)) {
            return;
        }

        AbstractGameEffect effect = (AbstractGameEffect)instance;
        FALLBACK_WITHOUT_SHADER.put(instance, Boolean.TRUE);
        if (!isInitialized(instance)) {
            INITIALIZED_EFFECTS.put(instance, Boolean.TRUE);
            playInspirationSound();
        }

        float delta = getDeltaTime();
        effect.duration -= delta;
        if (effect.duration <= 0.0F) {
            effect.isDone = true;
            cleanupEffect(instance);
        }
    }

    private static float getDeltaTime() {
        return Gdx.graphics == null ? 0.016F : Gdx.graphics.getDeltaTime();
    }

    private static void playInspirationSound() {
        int randomResult = MathUtils.random(2);
        switch (randomResult) {
            case 0:
                CardCrawlGame.sound.play("Inspiration1");
                break;
            case 1:
                CardCrawlGame.sound.play("Inspiration2");
                break;
            case 2:
                CardCrawlGame.sound.play("Inspiration3");
                break;
            default:
                break;
        }
    }

    private static boolean shouldPatchShaderEffect() {
        if (!readBooleanSystemProperty(ENABLED_PROP, true)) {
            return false;
        }
        String osVersion = System.getProperty("os.version", "");
        if (osVersion.startsWith("Android-")) {
            return true;
        }
        return System.getProperty("amethyst.gdx.native_dir") != null;
    }

    private static boolean isInitialized(Object instance) {
        return Boolean.TRUE.equals(INITIALIZED_EFFECTS.get(instance));
    }

    private static boolean isFallbackWithoutShader(Object instance) {
        return Boolean.TRUE.equals(FALLBACK_WITHOUT_SHADER.get(instance));
    }

    private static boolean hasShader(Object instance) {
        try {
            return getFieldValue(instance, "shader") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object getFieldValue(Object target, String fieldName) throws Exception {
        return getField(target.getClass(), fieldName).get(target);
    }

    private static float getFloatFieldValue(Object target, String fieldName) throws Exception {
        return getField(target.getClass(), fieldName).getFloat(target);
    }

    private static void setFieldValue(Object target, String fieldName, Object value) throws Exception {
        getField(target.getClass(), fieldName).set(target, value);
    }

    private static void setFloatFieldValue(Object target, String fieldName, float value) throws Exception {
        getField(target.getClass(), fieldName).setFloat(target, value);
    }

    private static Field getField(Class<?> type, String fieldName) throws Exception {
        if ("shader".equals(fieldName)) {
            if (shaderField == null) {
                shaderField = findField(type, fieldName);
            }
            return shaderField;
        }
        if ("textureRegion".equals(fieldName)) {
            if (textureRegionField == null) {
                textureRegionField = findField(type, fieldName);
            }
            return textureRegionField;
        }
        if ("elapsedTime".equals(fieldName)) {
            if (elapsedTimeField == null) {
                elapsedTimeField = findField(type, fieldName);
            }
            return elapsedTimeField;
        }
        return findField(type, fieldName);
    }

    private static Field findField(Class<?> type, String fieldName) throws Exception {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static void cleanupEffect(Object instance) {
        INITIALIZED_EFFECTS.remove(instance);
        FALLBACK_WITHOUT_SHADER.remove(instance);
    }

    private static void disposeSafely(ShaderProgram shader) {
        if (shader == null) {
            return;
        }
        try {
            shader.dispose();
        } catch (Throwable ignored) {
        }
    }

    private static void logCompatOnce() {
        if (compatLogPrinted) {
            return;
        }
        compatLogPrinted = true;
        System.out.println(
            "[amethyst-runtime-compat] JacketNoAnoKo Electrocardiogram shader rewritten " +
                "to GLES 100 source; preserving original render"
        );
    }

    private static void logFallbackOnce(String reason, Throwable throwable) {
        if (fallbackLogPrinted) {
            return;
        }
        fallbackLogPrinted = true;
        System.out.println(
            "[amethyst-runtime-compat] JacketNoAnoKo Electrocardiogram shader fallback " +
                "enabled; disabling render because " + reason
        );
        if (throwable != null) {
            throwable.printStackTrace(System.out);
        }
    }

    private static boolean readBooleanSystemProperty(String key, boolean defaultValue) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return defaultValue;
        }
        String normalized = configured.trim();
        if (normalized.isEmpty()) {
            return defaultValue;
        }
        if ("0".equals(normalized) || "false".equalsIgnoreCase(normalized)) {
            return false;
        }
        return true;
    }
}

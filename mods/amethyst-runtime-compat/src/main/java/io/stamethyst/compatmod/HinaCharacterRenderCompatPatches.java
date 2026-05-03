package io.stamethyst.compatmod;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.core.Settings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public final class HinaCharacterRenderCompatPatches {
    private static final String HINA_MOD_ID = "Blue archive Hina mod";
    private static final String HINA_HELPER_CLASS = "tuner.helpers.ModelController.character3DHelper";
    private static final String HINA_CHARACTER_RENDER_COMPAT_PROP =
        "amethyst.runtime_compat.hina_character_render";
    private static final int ORIGINAL_SSAA_SCALE = 4;
    private static final int MOBILE_RENDER_SCALE = 1;
    private static final boolean HINA_CHARACTER_RENDER_COMPAT_ENABLED =
        readBooleanSystemProperty(HINA_CHARACTER_RENDER_COMPAT_PROP, true);
    private static final boolean SHOULD_APPLY_COMPAT = resolveShouldApplyCompat();
    private static final Map<String, Field> FIELD_CACHE = new HashMap<>();
    private static final Map<String, Method> METHOD_CACHE = new HashMap<>();
    private static boolean resizeLogPrinted;
    private static boolean renderFallbackLogPrinted;

    private HinaCharacterRenderCompatPatches() {
    }

    @SpirePatch2(
        cls = HINA_HELPER_CLASS,
        method = "init",
        paramtypez = {int.class},
        requiredModId = HINA_MOD_ID,
        optional = true
    )
    public static class Character3DHelperInitPatch {
        public static void Postfix(Object __instance) {
            if (!shouldApplyCompat()) {
                return;
            }
            try {
                ensureMobileRenderTargets(__instance);
            } catch (Exception error) {
                System.out.println(
                    "[amethyst-runtime-compat] Hina init compat skipped: " + error.getClass().getSimpleName() +
                        ": " + error.getMessage()
                );
            }
        }
    }

    @SpirePatch2(
        cls = HINA_HELPER_CLASS,
        method = "render",
        paramtypez = {SpriteBatch.class, boolean.class},
        requiredModId = HINA_MOD_ID,
        optional = true
    )
    public static class Character3DHelperRenderPatch {
        public static SpireReturn<Void> Prefix(
            Object __instance,
            SpriteBatch spriteBatch,
            boolean flipHorizontal
        ) {
            if (!shouldApplyCompat()) {
                return SpireReturn.Continue();
            }
            try {
                renderWithMobileTargets(__instance, spriteBatch, flipHorizontal);
                return SpireReturn.Return(null);
            } catch (Exception error) {
                if (!spriteBatch.isDrawing()) {
                    spriteBatch.begin();
                }
                if (!renderFallbackLogPrinted) {
                    renderFallbackLogPrinted = true;
                    System.out.println(
                        "[amethyst-runtime-compat] Hina render compat fallback: " +
                            error.getClass().getSimpleName() + ": " + error.getMessage()
                    );
                }
                return SpireReturn.Continue();
            }
        }
    }

    private static boolean shouldApplyCompat() {
        return SHOULD_APPLY_COMPAT && Gdx.graphics != null;
    }

    private static boolean resolveShouldApplyCompat() {
        if (!HINA_CHARACTER_RENDER_COMPAT_ENABLED) {
            return false;
        }
        String osVersion = System.getProperty("os.version", "");
        if (osVersion.startsWith("Android-")) {
            return true;
        }
        return System.getProperty("amethyst.gdx.native_dir") != null;
    }

    private static void renderWithMobileTargets(
        Object helper,
        SpriteBatch spriteBatch,
        boolean flipHorizontal
    ) throws Exception {
        ensureMobileRenderTargets(helper);

        Object modelController = getFieldValue(helper, "modelController");
        invokeMethod(modelController, "flip", new Class<?>[]{boolean.class}, Boolean.valueOf(flipHorizontal));

        FrameBuffer frameBuffer = (FrameBuffer)getFieldValue(helper, "frameBuffer");
        OrthographicCamera camera = (OrthographicCamera)getFieldValue(helper, "camera");
        Environment environment = (Environment)getFieldValue(helper, "environment");
        PolygonSpriteBatch polygonSpriteBatch = ensurePolygonSpriteBatch(helper);

        boolean spriteBatchEnded = false;
        boolean frameBufferBegan = false;
        boolean polygonSpriteBatchBegan = false;
        try {
            if (spriteBatch.isDrawing()) {
                spriteBatch.end();
                spriteBatchEnded = true;
            }

            frameBuffer.begin();
            frameBufferBegan = true;
            Gdx.gl.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            invokeMethod(
                modelController,
                "render",
                new Class<?>[]{OrthographicCamera.class, Environment.class},
                camera,
                environment
            );

            frameBuffer.end();
            frameBufferBegan = false;

            Texture colorBufferTexture = frameBuffer.getColorBufferTexture();
            TextureRegion region = (TextureRegion)getFieldValue(helper, "region");
            if (region == null || region.getTexture() != colorBufferTexture) {
                region = new TextureRegion(colorBufferTexture);
                region.flip(false, true);
                setFieldValue(helper, "region", region);
            }

            float drawX = getFloatFieldValue(helper, "drawX");
            float drawY = getFloatFieldValue(helper, "drawY");
            float width = (float)Gdx.graphics.getWidth();
            float height = (float)Gdx.graphics.getHeight();

            polygonSpriteBatch.begin();
            polygonSpriteBatchBegan = true;
            polygonSpriteBatch.draw(
                region,
                drawX - (float)Settings.WIDTH / 2.0F,
                drawY - (float)Settings.HEIGHT / 2.0F,
                width,
                height,
                width,
                height,
                1.0F,
                1.0F,
                0.0F
            );
            polygonSpriteBatch.setShader(null);
            polygonSpriteBatch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            polygonSpriteBatch.end();
            polygonSpriteBatchBegan = false;
        } finally {
            if (frameBufferBegan) {
                frameBuffer.end();
            }
            if (polygonSpriteBatchBegan) {
                polygonSpriteBatch.end();
            }
            if (spriteBatchEnded && !spriteBatch.isDrawing()) {
                spriteBatch.begin();
            }
        }
    }

    private static void ensureMobileRenderTargets(Object helper) throws Exception {
        int targetWidth = Math.max(1, Gdx.graphics.getWidth() * MOBILE_RENDER_SCALE);
        int targetHeight = Math.max(1, Gdx.graphics.getHeight() * MOBILE_RENDER_SCALE);
        int logicalCameraWidth = Math.max(1, Gdx.graphics.getWidth() * ORIGINAL_SSAA_SCALE);
        int logicalCameraHeight = Math.max(1, Gdx.graphics.getHeight() * ORIGINAL_SSAA_SCALE);

        FrameBuffer currentFrameBuffer = (FrameBuffer)getFieldValue(helper, "frameBuffer");
        OrthographicCamera currentCamera = (OrthographicCamera)getFieldValue(helper, "camera");

        boolean frameBufferMatches =
            currentFrameBuffer != null &&
                currentFrameBuffer.getWidth() == targetWidth &&
                currentFrameBuffer.getHeight() == targetHeight;
        boolean cameraMatches =
            currentCamera != null &&
                Math.round(currentCamera.viewportWidth) == logicalCameraWidth &&
                Math.round(currentCamera.viewportHeight) == logicalCameraHeight;

        if (frameBufferMatches && cameraMatches) {
            return;
        }

        if (currentFrameBuffer != null) {
            currentFrameBuffer.dispose();
        }

        FrameBuffer mobileFrameBuffer = new FrameBuffer(
            Pixmap.Format.RGBA8888,
            targetWidth,
            targetHeight,
            true
        );
        mobileFrameBuffer.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        OrthographicCamera mobileCamera = createMobileCamera(logicalCameraWidth, logicalCameraHeight);

        setFieldValue(helper, "frameBuffer", mobileFrameBuffer);
        setFieldValue(helper, "camera", mobileCamera);
        setFieldValue(helper, "region", null);

        if (!resizeLogPrinted) {
            resizeLogPrinted = true;
            int previousWidth = currentFrameBuffer == null ? -1 : currentFrameBuffer.getWidth();
            int previousHeight = currentFrameBuffer == null ? -1 : currentFrameBuffer.getHeight();
            System.out.println(
                "[amethyst-runtime-compat] Hina mobile render target resize: " +
                    previousWidth + "x" + previousHeight + " -> " +
                    targetWidth + "x" + targetHeight +
                    " (cameraLogical=" + logicalCameraWidth + "x" + logicalCameraHeight + ")"
            );
        }
    }

    private static OrthographicCamera createMobileCamera(int width, int height) {
        OrthographicCamera camera = new OrthographicCamera((float)width, (float)height);
        camera.position.set(0.0F, 0.0F, 0.0F);
        camera.near = -1000.0F;
        camera.far = 1000.0F;
        camera.rotate(new Vector3(0.0F, 1.0F, 0.0F), 90.0F);
        camera.update();
        return camera;
    }

    private static PolygonSpriteBatch ensurePolygonSpriteBatch(Object helper) throws Exception {
        PolygonSpriteBatch polygonSpriteBatch = (PolygonSpriteBatch)getFieldValue(helper, "psb");
        if (polygonSpriteBatch != null) {
            return polygonSpriteBatch;
        }
        polygonSpriteBatch = new PolygonSpriteBatch();
        setFieldValue(helper, "psb", polygonSpriteBatch);
        return polygonSpriteBatch;
    }

    private static Object getFieldValue(Object target, String fieldName) throws Exception {
        return findField(target.getClass(), fieldName).get(target);
    }

    private static float getFloatFieldValue(Object target, String fieldName) throws Exception {
        return findField(target.getClass(), fieldName).getFloat(target);
    }

    private static void setFieldValue(Object target, String fieldName, Object value) throws Exception {
        findField(target.getClass(), fieldName).set(target, value);
    }

    private static Field findField(Class<?> type, String fieldName) throws Exception {
        String cacheKey = type.getName() + "#" + fieldName;
        Field cached = FIELD_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                FIELD_CACHE.put(cacheKey, field);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static Object invokeMethod(
        Object target,
        String methodName,
        Class<?>[] parameterTypes,
        Object... args
    ) throws Exception {
        Method method = findMethod(target.getClass(), methodName, parameterTypes);
        return method.invoke(target, args);
    }

    private static Method findMethod(
        Class<?> type,
        String methodName,
        Class<?>[] parameterTypes
    ) throws Exception {
        String cacheKey = buildMethodCacheKey(type, methodName, parameterTypes);
        Method cached = METHOD_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);
                METHOD_CACHE.put(cacheKey, method);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    private static String buildMethodCacheKey(Class<?> type, String methodName, Class<?>[] parameterTypes) {
        StringBuilder builder = new StringBuilder(type.getName()).append('#').append(methodName).append('(');
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(parameterTypes[i].getName());
        }
        return builder.append(')').toString();
    }

    private static boolean readBooleanSystemProperty(String key, boolean defaultValue) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return defaultValue;
        }
        configured = configured.trim();
        if (configured.length() == 0) {
            return defaultValue;
        }
        if ("false".equalsIgnoreCase(configured)
            || "0".equals(configured)
            || "off".equalsIgnoreCase(configured)) {
            return false;
        }
        if ("true".equalsIgnoreCase(configured)
            || "1".equals(configured)
            || "on".equalsIgnoreCase(configured)) {
            return true;
        }
        return defaultValue;
    }
}

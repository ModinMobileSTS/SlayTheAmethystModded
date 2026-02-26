package collector.util;

import basemod.abstracts.CustomEnergyOrb;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Interpolation;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.TipHelper;
import com.megacrit.cardcrawl.localization.UIStrings;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

import java.lang.reflect.Method;

/**
 * Android compatibility variant of Downfall's DoubleEnergyOrb.
 *
 * <p>Original class uses an internal framebuffer path on several renderer stacks
 * and can crash with unknown FBO status. This replacement keeps a close visual
 * behavior while rendering directly through SpriteBatch layers.</p>
 */
public class DoubleEnergyOrb extends CustomEnergyOrb {
    public static final int SECOND_ORB_W = 128;
    public static final int PRIMARY_ORB_W = 128;
    public static final float SECOND_ORB_IMG_SCALE;
    public static final float PRIMARY_ORB_IMG_SCALE;
    public static final float X_OFFSET;
    public static final float Y_OFFSET;

    public static float secondVfxTimer;

    protected Texture secondBaseLayer;
    protected Texture[] secondEnergyLayers;
    protected Texture[] secondNoEnergyLayers;
    protected float[] secondLayerSpeeds;
    protected float[] secondAngles;

    private static float secondEnergyVfxAngle;
    private static float secondEnergyVfxScale;
    private static Color secondEnergyVfxColor;
    private static Hitbox hb;
    private static UIStrings uiStrings;

    static {
        SECOND_ORB_IMG_SCALE = 0.75f * Settings.scale;
        PRIMARY_ORB_IMG_SCALE = 1.15f * Settings.scale;
        X_OFFSET = 100.0f * Settings.scale;
        Y_OFFSET = 0.0f * Settings.scale;
        secondVfxTimer = 0.0f;
        secondEnergyVfxAngle = 0.0f;
        secondEnergyVfxScale = Settings.scale;
        secondEnergyVfxColor = Color.WHITE.cpy();
        hb = new Hitbox(80.0f * Settings.scale, 80.0f * Settings.scale);
        uiStrings = resolveUiStrings();
    }

    public DoubleEnergyOrb(String[] orbTexturePaths,
                           String orbVfxPath,
                           float[] layerSpeeds,
                           String[] orbTexturePathsAlt,
                           String orbVfxPathAlt) {
        super(orbTexturePaths, orbVfxPath, layerSpeeds);

        int middleIdx = orbTexturePathsAlt == null ? 0 : orbTexturePathsAlt.length / 2;
        if (middleIdx <= 0 || orbTexturePathsAlt.length % 2 != 1) {
            middleIdx = 0;
        }

        secondEnergyLayers = new Texture[middleIdx];
        secondNoEnergyLayers = new Texture[middleIdx];

        for (int i = 0; i < middleIdx; i++) {
            secondEnergyLayers[i] = safeLoadTexture(orbTexturePathsAlt[i]);
            secondNoEnergyLayers[i] = safeLoadTexture(orbTexturePathsAlt[i + middleIdx + 1]);
        }

        secondBaseLayer = safeLoadTexture("collectorResources/images/char/mainChar/orb/alt/layer6.png");
        orbVfx = safeLoadTexture(orbVfxPath);

        secondLayerSpeeds = layerSpeeds == null
                ? new float[]{-20.0f, 20.0f, -40.0f, 40.0f, 360.0f}
                : layerSpeeds;
        secondAngles = new float[secondLayerSpeeds.length];
    }

    @Override
    public Texture getEnergyImage() {
        return orbVfx;
    }

    @Override
    public void updateOrb(int energyCount) {
        super.updateOrb(energyCount);

        int d = secondAngles.length;
        for (int i = 0; i < secondAngles.length; i++) {
            if (energyCount == 0) {
                secondAngles[i] -= (Gdx.graphics.getDeltaTime() * secondLayerSpeeds[(d - 1) - i]) / 4.0f;
            } else {
                secondAngles[i] -= Gdx.graphics.getDeltaTime() * secondLayerSpeeds[(d - 1) - i];
            }
        }

        if (secondVfxTimer != 0.0f) {
            secondEnergyVfxColor.a = Interpolation.exp10In.apply(0.5f, 0.0f, 1.0f - (secondVfxTimer / 2.0f));
            secondEnergyVfxAngle += Gdx.graphics.getDeltaTime() * (-30.0f);
            secondEnergyVfxScale = Settings.scale
                    * Interpolation.exp10In.apply(1.0f, 0.1f, 1.0f - (secondVfxTimer / 2.0f));
            secondVfxTimer -= Gdx.graphics.getDeltaTime();
            if (secondVfxTimer < 0.0f) {
                secondVfxTimer = 0.0f;
                secondEnergyVfxColor.a = 0.0f;
            }
        }

        hb.update();
    }

    @Override
    public void renderOrb(SpriteBatch sb, boolean enabled, float currentX, float currentY) {
        hb.move(currentX + X_OFFSET, currentY + Y_OFFSET);
        sb.setColor(Color.WHITE);

        renderPrimaryOrb(sb, enabled, currentX, currentY);

        int reserveCount = resolveReserveCount();
        if (isCollectorChosenClass() || reserveCount > 0) {
            renderSecondaryOrb(sb, reserveCount > 0, currentX + X_OFFSET, currentY + Y_OFFSET);
        }

        if (hb.hovered
                && AbstractDungeon.getCurrRoom() != null
                && AbstractDungeon.getCurrRoom().phase == AbstractRoom.RoomPhase.COMBAT
                && !AbstractDungeon.isScreenUp) {
            String title = text(0, "Reserve");
            String body = text(1, "Secondary energy orb");
            TipHelper.renderGenericTip(50.0f * Settings.scale, 380.0f * Settings.scale, title, body);
        }

        hb.render(sb);
    }

    private void renderPrimaryOrb(SpriteBatch sb, boolean enabled, float x, float y) {
        Texture[] layers = enabled ? energyLayers : noEnergyLayers;
        float[] layerAngles = angles;
        if (layers != null) {
            int n = Math.min(layers.length, layerAngles == null ? 0 : layerAngles.length);
            for (int i = 0; i < n; i++) {
                drawOrbLayer(sb, layers[i], x, y, PRIMARY_ORB_IMG_SCALE, layerAngles[i]);
            }
        }
        drawOrbLayer(sb, baseLayer, x, y, PRIMARY_ORB_IMG_SCALE, 0.0f);
    }

    private void renderSecondaryOrb(SpriteBatch sb, boolean hasReserve, float x, float y) {
        Texture[] layers = hasReserve ? secondEnergyLayers : secondNoEnergyLayers;
        if (layers != null) {
            int n = Math.min(layers.length, secondAngles == null ? 0 : secondAngles.length);
            for (int i = 0; i < n; i++) {
                drawOrbLayer(sb, layers[i], x, y, SECOND_ORB_IMG_SCALE, secondAngles[i]);
            }
        }
        drawOrbLayer(sb, secondBaseLayer, x, y, SECOND_ORB_IMG_SCALE, 0.0f);
    }

    private void drawOrbLayer(SpriteBatch sb, Texture texture, float centerX, float centerY, float scale, float angle) {
        if (texture == null) {
            return;
        }
        sb.draw(texture,
                centerX - 64.0f,
                centerY - 64.0f,
                64.0f,
                64.0f,
                128.0f,
                128.0f,
                scale,
                scale,
                angle,
                0,
                0,
                128,
                128,
                false,
                false);
    }

    private static UIStrings resolveUiStrings() {
        try {
            if (CardCrawlGame.languagePack != null) {
                return CardCrawlGame.languagePack.getUIString("collector:SecondEnergyOrb");
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String text(int index, String fallback) {
        try {
            if (uiStrings == null || uiStrings.TEXT == null || index < 0 || index >= uiStrings.TEXT.length) {
                return fallback;
            }
            String value = uiStrings.TEXT[index];
            if (value == null || value.trim().isEmpty()) {
                return fallback;
            }
            return value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Texture safeLoadTexture(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        try {
            return ImageMaster.loadImage(path);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int resolveReserveCount() {
        try {
            Class<?> clazz = Class.forName("collector.util.NewReserves");
            Method method = clazz.getMethod("reserveCount");
            Object value = method.invoke(null);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static boolean isCollectorChosenClass() {
        try {
            if (AbstractDungeon.player == null || AbstractDungeon.player.chosenClass == null) {
                return false;
            }
            String enumName = AbstractDungeon.player.chosenClass.name();
            return "THE_COLLECTOR".equals(enumName);
        } catch (Throwable ignored) {
            return false;
        }
    }
}

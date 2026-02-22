package downfall.vfx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.esotericsoftware.spine.AnimationState;
import com.esotericsoftware.spine.AnimationStateData;
import com.esotericsoftware.spine.Skeleton;
import com.esotericsoftware.spine.SkeletonData;
import com.esotericsoftware.spine.SkeletonJson;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.HeartAnimListener;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * Android compatibility variant of Downfall's CustomAnimatedNPC.
 *
 * <p>If portal mask framebuffers fail to initialize or render, this class
 * disables the portal FBO path and falls back to direct portal + skeleton draw,
 * preventing fatal crashes from unstable framebuffer behavior.</p>
 */
public class CustomAnimatedNPC {
    public TextureAtlas atlas;
    public Skeleton skeleton;
    public AnimationState state;
    public AnimationStateData stateData;
    public boolean customFlipX;
    public Float customRot;
    public Float customShadowScale;
    public boolean highlighted;
    public boolean portalRender;
    public boolean portalRenderActive;
    private boolean colorSwapped;
    private boolean noMesh;
    private ArrayList<PortalBorderEffect> borderEffects;
    private float heartCenterX;
    private float heartCenterY;
    private float heartScale;
    public Texture portalImage;
    private HeartAnimListener animListener;
    private FrameBuffer heartBuffer;
    private FrameBuffer maskBuffer;
    private static final float PORTAL_GROW_TIME = 2.0f;
    private float maskDuration;
    public static int borderEffectCount = 36;
    private static final TextureRegion MASK_REGION;

    private boolean portalFboEnabled;
    private boolean portalFboFailureLogged;

    static {
        TextureRegion safeMask = null;
        try {
            safeMask = new TextureRegion(new Texture("downfallResources/images/vfx/HeartMask.png"), 500, 500);
        } catch (Throwable ignored) {
        }
        MASK_REGION = safeMask;
    }

    public CustomAnimatedNPC(float x, float y, String atlasUrl, String skeletonUrl, String trackName, boolean portalRender, int portalType) {
        this(x, y, atlasUrl, skeletonUrl, trackName, portalRender, portalType, false, 1.0f);
    }

    public CustomAnimatedNPC(float x,
                             float y,
                             String atlasUrl,
                             String skeletonUrl,
                             String trackName,
                             boolean portalRender,
                             int portalType,
                             boolean noMesh,
                             float heartScale) {
        this.atlas = null;
        this.customRot = Float.valueOf(0.0f);
        this.customShadowScale = Float.valueOf(0.0f);
        this.highlighted = false;
        this.portalRenderActive = false;
        this.colorSwapped = false;
        this.borderEffects = new ArrayList<>();
        this.animListener = new HeartAnimListener();
        this.maskDuration = 0.0f;
        this.noMesh = noMesh;
        this.heartScale = heartScale;
        this.portalFboEnabled = false;
        this.portalFboFailureLogged = false;

        if (!this.noMesh) {
            loadAnimation(atlasUrl, skeletonUrl, 1.0f);
            this.skeleton.setPosition(x, y - ((300.0f * Settings.scale) * this.heartScale));
            this.state.setAnimation(0, trackName, true);
            this.state.setTimeScale(1.0f);
        }

        // Keep consistent with SlayTheSpireModded android patch:
        // fully disable portal FBO render path for stability.
        this.portalRender = false;
        this.heartCenterX = x;
        this.heartCenterY = y;
        this.portalImage = resolvePortalTexture(portalType);

        if (this.portalRender) {
            if (!this.noMesh) {
                addListener(new HeartAnimListener());
                this.skeleton.getRootBone().setScale(0.8f * this.heartScale);
            }

            for (int i = 1; i <= borderEffectCount; i++) {
                try {
                    PortalBorderEffect effect = new PortalBorderEffect(
                            this.heartCenterX,
                            this.heartCenterY,
                            i * 100 * (borderEffectCount / 360.0f),
                            this.heartScale
                    );
                    this.borderEffects.add(effect);
                    if (!this.borderEffects.isEmpty()) {
                        effect.orbitalInterval = this.borderEffects.get(0).orbitalInterval;
                    }
                    effect.ELLIPSIS_SCALE = this.heartScale;
                    effect.calculateEllipseSize();
                } catch (Throwable ignored) {
                }
            }
            initPortalFboIfPossible();
        }
    }

    public void loadAnimation(String atlasUrl, String skeletonUrl, float scale) {
        this.atlas = new TextureAtlas(Gdx.files.internal(atlasUrl));
        SkeletonJson json = new SkeletonJson(this.atlas);
        json.setScale(Settings.scale / scale);
        SkeletonData skeletonData = json.readSkeletonData(Gdx.files.internal(skeletonUrl));
        this.skeleton = new Skeleton(skeletonData);
        this.skeleton.setColor(Color.WHITE);
        this.stateData = new AnimationStateData(skeletonData);
        this.state = new AnimationState(this.stateData);
    }

    public void changeBorderColor(Color color) {
        Iterator<PortalBorderEffect> it = this.borderEffects.iterator();
        while (it.hasNext()) {
            PortalBorderEffect pb = it.next();
            pb.borderColor = color;
        }
    }

    public void update() {
        if (this.portalRender && this.portalRenderActive) {
            Iterator<PortalBorderEffect> it = this.borderEffects.iterator();
            while (it.hasNext()) {
                it.next().update();
            }
            if (this.maskDuration < PORTAL_GROW_TIME) {
                this.maskDuration += Gdx.graphics.getDeltaTime();
                float normalized = Math.min(PORTAL_GROW_TIME, this.maskDuration) / PORTAL_GROW_TIME;
                Iterator<PortalBorderEffect> it2 = this.borderEffects.iterator();
                while (it2.hasNext()) {
                    PortalBorderEffect pb = it2.next();
                    pb.ELLIPSIS_SCALE = normalized * this.heartScale;
                    pb.calculateEllipseSize();
                }
                if (this.maskDuration > PORTAL_GROW_TIME) {
                    this.maskDuration = PORTAL_GROW_TIME;
                }
            }
        }
    }

    public void render(SpriteBatch sb) {
        render(sb, Color.WHITE);
    }

    public void render(SpriteBatch sb, Color color) {
        if (this.portalRender) {
            if (this.portalFboEnabled && this.heartBuffer != null && this.maskBuffer != null && MASK_REGION != null) {
                try {
                    renderWithPortalFbo(sb);
                    return;
                } catch (Throwable error) {
                    disablePortalFbo(error);
                }
            }
            renderPortalFallback(sb, color);
            return;
        }
        standardRender(sb, color);
    }

    public void dispose() {
        if (this.atlas != null) {
            this.atlas.dispose();
        }
        disposePortalFboBuffers();
    }

    public void standardRender(SpriteBatch sb, Color color) {
        if (!this.noMesh) {
            this.state.update(Gdx.graphics.getDeltaTime());
            this.state.apply(this.skeleton);
            if (this.skeleton.getRootBone() != null) {
                this.skeleton.getRootBone().setRotation(this.customRot.floatValue());
                if (this.skeleton.findBone("shadow") != null) {
                    this.skeleton.findBone("shadow").setRotation((-1.0f) * this.customRot.floatValue());
                    this.skeleton.findBone("shadow").setScale(this.customShadowScale.floatValue());
                }
            }
            this.skeleton.updateWorldTransform();
            this.skeleton.setFlip(this.customFlipX, false);
            this.skeleton.setColor(color);
            sb.end();
            CardCrawlGame.psb.begin();
            AbstractCreature.sr.draw(CardCrawlGame.psb, this.skeleton);
            CardCrawlGame.psb.end();
            sb.begin();
            sb.setBlendFunction(770, 771);
        }
    }

    public void standardRender(SpriteBatch sb) {
        standardRender(sb, Color.WHITE);
    }

    public void setTimeScale(float setScale) {
        this.state.setTimeScale(setScale);
    }

    public void addListener(HeartAnimListener listener) {
        this.state.addListener(listener);
    }

    private void renderWithPortalFbo(SpriteBatch sb) {
        sb.end();
        this.maskBuffer.begin();
        Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        Gdx.gl.glClear(16640);
        Gdx.gl.glColorMask(true, true, true, true);
        sb.begin();
        sb.setColor(Color.WHITE);
        float scale = (this.maskDuration / PORTAL_GROW_TIME) * Settings.scale;
        float w = MASK_REGION.getRegionWidth() * this.heartScale;
        float h = MASK_REGION.getRegionHeight() * this.heartScale;
        sb.draw(MASK_REGION,
                this.heartCenterX - (w / 2.0f),
                this.heartCenterY - (h / 2.0f),
                w / 2.0f,
                h / 2.0f,
                w,
                h,
                scale,
                scale,
                0.0f);
        sb.end();
        this.maskBuffer.end();

        TextureRegion tmpMask = new TextureRegion(this.maskBuffer.getColorBufferTexture());
        tmpMask.flip(false, true);

        this.heartBuffer.begin();
        Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        Gdx.gl.glClear(16640);
        Gdx.gl.glColorMask(true, true, true, true);
        sb.begin();
        if (this.highlighted) {
            sb.setColor(Color.WHITE);
        } else {
            sb.setColor(Color.LIGHT_GRAY);
        }
        if (this.portalImage != null) {
            sb.draw(this.portalImage,
                    this.heartCenterX - (250.0f * Settings.scale),
                    this.heartCenterY - (250.0f * Settings.scale),
                    500.0f * Settings.scale,
                    500.0f * Settings.scale);
        }
        standardRender(sb);
        sb.setBlendFunction(0, 770);
        sb.draw(tmpMask, 0.0f, 0.0f);
        sb.setBlendFunction(770, 771);
        sb.end();
        this.heartBuffer.end();

        TextureRegion maskedHeart = new TextureRegion(this.heartBuffer.getColorBufferTexture());
        maskedHeart.flip(false, true);
        sb.begin();
        sb.draw(maskedHeart, (-2) * Settings.VERT_LETTERBOX_AMT, (-2) * Settings.HORIZ_LETTERBOX_AMT);
    }

    private void renderPortalFallback(SpriteBatch sb, Color color) {
        if (this.highlighted) {
            sb.setColor(Color.WHITE);
        } else {
            sb.setColor(Color.LIGHT_GRAY);
        }
        if (this.portalImage != null) {
            sb.draw(this.portalImage,
                    this.heartCenterX - (250.0f * Settings.scale),
                    this.heartCenterY - (250.0f * Settings.scale),
                    500.0f * Settings.scale,
                    500.0f * Settings.scale);
        }
        standardRender(sb, color);
        sb.setBlendFunction(770, 771);
        sb.setColor(Color.WHITE);
    }

    private Texture resolvePortalTexture(int portalType) {
        try {
            if (portalType == 0) {
                return new Texture("downfallResources/images/vfx/beyondPortal.png");
            }
            if (portalType == 1) {
                return new Texture("downfallResources/images/vfx/cityPortal.png");
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void initPortalFboIfPossible() {
        disposePortalFboBuffers();
        try {
            int width = Math.max(1, Gdx.graphics.getWidth());
            int height = Math.max(1, Gdx.graphics.getHeight());
            this.heartBuffer = new FrameBuffer(Pixmap.Format.RGBA8888, width, height, false, false);
            this.maskBuffer = new FrameBuffer(Pixmap.Format.RGBA8888, width, height, false, false);
            this.portalFboEnabled = this.heartBuffer != null && this.maskBuffer != null && MASK_REGION != null;
            if (!this.portalFboEnabled) {
                disablePortalFbo(null);
            }
        } catch (Throwable error) {
            disablePortalFbo(error);
        }
    }

    private void disablePortalFbo(Throwable error) {
        this.portalFboEnabled = false;
        disposePortalFboBuffers();
        if (!this.portalFboFailureLogged) {
            this.portalFboFailureLogged = true;
            String reason = error == null
                    ? "unknown"
                    : error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            System.out.println("[compat] Downfall CustomAnimatedNPC: portal FBO disabled, fallback to direct render; reason=" + reason);
        }
    }

    private void disposePortalFboBuffers() {
        if (this.heartBuffer != null) {
            try {
                this.heartBuffer.dispose();
            } catch (Throwable ignored) {
            }
            this.heartBuffer = null;
        }
        if (this.maskBuffer != null) {
            try {
                this.maskBuffer.dispose();
            } catch (Throwable ignored) {
            }
            this.maskBuffer = null;
        }
    }
}

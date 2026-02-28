package basemod.patches.com.megacrit.cardcrawl.core.CardCrawlGame;

import basemod.ReflectionHacks;
import basemod.interfaces.ScreenPostProcessor;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.ScreenShake;
import java.util.ArrayList;
import java.util.List;

public class ApplyScreenPostProcessor {
    public static final List<ScreenPostProcessor> postProcessors = new ArrayList<ScreenPostProcessor>();

    private static boolean failedInitialized = false;
    private static boolean initLogged = false;
    private static boolean processorsLogged = false;

    private static int defaultFramebufferHandle;
    private static FrameBuffer primaryFrameBuffer;
    private static FrameBuffer secondaryFrameBuffer;
    private static TextureRegion primaryFboRegion;
    private static TextureRegion secondaryFboRegion;

    public static Texture getFrameBufferTexture() {
        return (Texture) primaryFrameBuffer.getColorBufferTexture();
    }

    public static void BeforeSpriteBatchBegin(SpriteBatch sb) {
        if (failedInitialized) {
            return;
        }
        if (primaryFrameBuffer == null) {
            initFrameBuffer();
            if (failedInitialized) {
                return;
            }
        }
        primaryFrameBuffer.begin();
        sb.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glClearColor(0, 0, 0, 0);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT | GL20.GL_STENCIL_BUFFER_BIT);
    }

    public static void BeforeSpriteBatchEnd(SpriteBatch sb, OrthographicCamera camera) {
        if (failedInitialized || primaryFrameBuffer == null) {
            return;
        }
        if (!processorsLogged) {
            processorsLogged = true;
            System.out.println("[compat] BaseMod post-process active, processors=" + postProcessors.size());
            for (int i = 0; i < postProcessors.size(); i++) {
                ScreenPostProcessor processor = postProcessors.get(i);
                String name = processor == null ? "null" : processor.getClass().getName();
                System.out.println("[compat] BaseMod post-process[" + i + "]=" + name);
            }
        }
        sb.end();
        primaryFrameBuffer.end();

        FrameBuffer origPrimary = primaryFrameBuffer;
        for (ScreenPostProcessor postProcessor : postProcessors) {
            swapBuffers();

            primaryFrameBuffer.begin();
            Gdx.gl.glClearColor(0, 0, 0, 0);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT | GL20.GL_STENCIL_BUFFER_BIT);

            sb.begin();
            sb.setBlendFunction(GL20.GL_ONE, GL20.GL_ZERO);
            postProcessor.postProcess(sb, secondaryFboRegion, camera);
            sb.end();
            primaryFrameBuffer.end();
        }

        sb.setShader(null);
        if (Settings.SCREEN_SHAKE
            && (Float) ReflectionHacks.getPrivate(CardCrawlGame.screenShake, ScreenShake.class, "duration") > 0
            && CardCrawlGame.viewport.getScreenWidth() > 0
            && CardCrawlGame.viewport.getScreenHeight() > 0) {
            CardCrawlGame.viewport.apply();
        }
        sb.begin();
        sb.setColor(Color.WHITE);
        sb.setBlendFunction(GL20.GL_ONE, GL20.GL_ZERO);
        sb.setProjectionMatrix(camera.combined);
        sb.draw(primaryFboRegion, -Settings.VERT_LETTERBOX_AMT, -Settings.HORIZ_LETTERBOX_AMT);
        sb.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        if (origPrimary != primaryFrameBuffer) {
            swapBuffers();
        }
    }

    private static void swapBuffers() {
        FrameBuffer tempBuffer = primaryFrameBuffer;
        primaryFrameBuffer = secondaryFrameBuffer;
        secondaryFrameBuffer = tempBuffer;

        TextureRegion tempRegion = primaryFboRegion;
        primaryFboRegion = secondaryFboRegion;
        secondaryFboRegion = tempRegion;
    }

    private static void initFrameBuffer() {
        defaultFramebufferHandle = (Integer) ReflectionHacks.getPrivateStatic(GLFrameBuffer.class, "defaultFramebufferHandle");
        int width = Gdx.graphics.getWidth();
        int height = Gdx.graphics.getHeight();
        try {
            primaryFrameBuffer = new FrameBuffer(Pixmap.Format.RGBA8888, width, height, false, false);
            primaryFboRegion = new TextureRegion((Texture) primaryFrameBuffer.getColorBufferTexture());
            primaryFboRegion.flip(false, true);
            secondaryFrameBuffer = new FrameBuffer(Pixmap.Format.RGBA8888, width, height, false, false);
            secondaryFboRegion = new TextureRegion((Texture) secondaryFrameBuffer.getColorBufferTexture());
            secondaryFboRegion.flip(false, true);
            if (!initLogged) {
                initLogged = true;
                System.out.println("[compat] BaseMod post-process FBO init ok, size=" + width + "x" + height
                    + ", defaultFboHandle=" + defaultFramebufferHandle
                    + ", primaryHandle=" + primaryFrameBuffer.getFramebufferHandle()
                    + ", secondaryHandle=" + secondaryFrameBuffer.getFramebufferHandle());
            }
        } catch (Exception e) {
            failedInitialized = true;
            System.out.println("[compat] BaseMod post-process FBO init failed: "
                + e.getClass().getName() + ": " + e.getMessage());
            if (primaryFrameBuffer != null) {
                primaryFrameBuffer.dispose();
                primaryFrameBuffer = null;
                primaryFboRegion = null;
            }
            if (secondaryFrameBuffer != null) {
                secondaryFrameBuffer.dispose();
                secondaryFrameBuffer = null;
                secondaryFboRegion = null;
            }
            e.printStackTrace();
        }
    }
}

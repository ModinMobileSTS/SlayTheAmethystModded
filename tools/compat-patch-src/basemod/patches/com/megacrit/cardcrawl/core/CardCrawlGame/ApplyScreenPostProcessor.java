package basemod.patches.com.megacrit.cardcrawl.core.CardCrawlGame;

import basemod.interfaces.ScreenPostProcessor;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * FBO compatibility fallback for Android renderer stacks where BaseMod post-process
 * framebuffers become unstable or context loss occurs at runtime.
 */
public class ApplyScreenPostProcessor {
    public static final List<ScreenPostProcessor> postProcessors = new ArrayList<ScreenPostProcessor>();

    private static boolean failedInitialized = true;
    private static boolean loggedDisabled = false;

    private static int defaultFramebufferHandle;
    private static FrameBuffer primaryFrameBuffer;
    private static FrameBuffer secondaryFrameBuffer;
    private static TextureRegion primaryFboRegion;
    private static TextureRegion secondaryFboRegion;

    public static Texture getFrameBufferTexture() {
        if (primaryFrameBuffer == null) {
            return null;
        }
        try {
            return (Texture) primaryFrameBuffer.getColorBufferTexture();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void BeforeSpriteBatchBegin(SpriteBatch sb) {
        logDisabledOnce("BeforeSpriteBatchBegin");
    }

    public static void BeforeSpriteBatchEnd(SpriteBatch sb, OrthographicCamera camera) {
        logDisabledOnce("BeforeSpriteBatchEnd");
    }

    private static void logDisabledOnce(String entry) {
        if (loggedDisabled) {
            return;
        }
        loggedDisabled = true;
        System.out.println("[compat] BaseMod ApplyScreenPostProcessor disabled (" + entry
                + "): bypassing FBO post-process path");
    }
}

package basemod.helpers;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.megacrit.cardcrawl.cards.AbstractCard;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;

public class CardBorderGlowManager {
    public abstract static class GlowInfo implements Comparable<GlowInfo> {
        public int priority = 0;

        public abstract boolean test(AbstractCard var1);

        public abstract Color getColor(AbstractCard var1);

        public abstract String glowID();

        public int compareTo(GlowInfo other) {
            return this.priority - other.priority;
        }
    }

    public static class RenderGlowPatch {
        private static FrameBuffer fbo = null;
        private static FrameBuffer maskfbo = null;
        private static ShapeRenderer shape = null;
        private static TextureRegion currentMask = null;
        private static ArrayList<CardBorderGlowManager.GlowInfo> colorTracker = new ArrayList();
        private static HashMap<CardBorderGlowManager.GlowInfo, Object> masks = new HashMap();
        private static CardBorderGlowManager.GlowInfo currentRender = null;
        private static Color defaultColor = null;

        public static void Prefix(AbstractCard var0, SpriteBatch var1) {
        }

        public static void Postfix(AbstractCard var0, SpriteBatch var1) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        }

        public static FrameBuffer createBuffer() {
            return null;
        }

        public static void beginBuffer(FrameBuffer var0) {
        }

        public static TextureRegion getBufferTexture(FrameBuffer var0) {
            return null;
        }

        static CardBorderGlowManager.GlowInfo access$000() {
            return currentRender;
        }
    }
}

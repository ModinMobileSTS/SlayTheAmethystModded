package com.badlogic.gdx.graphics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import optispire.debug.ReleaseDebugOverlay;
import org.junit.Test;
import sun.misc.Unsafe;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Executes the production render/input methods with real glyph layout and a recording batch; no GL draw. */
public class ReleaseOverlayRenderTest extends RamSaverTestSupport {
    public static class RecordingBatch extends SpriteBatch {
        List<float[]> rectangles;
        Color color;
        @Override public boolean isDrawing() { return true; }
        @Override public float getPackedColor() { return color.toFloatBits(); }
        @Override public void setColor(Color value) { color.set(value); }
        @Override public void setColor(float value) { Color.abgr8888ToColor(color, value); }
        @Override public void draw(Texture texture, float x, float y, float w, float h) {
            rectangles.add(new float[] {x, y, w, h, color.r, color.g, color.b});
        }
    }

    private static class RecordingFont extends BitmapFont {
        final List<String> text = new ArrayList<>();
        RecordingFont(BitmapFontData data, Texture texture) { super(data, new TextureRegion(texture), true); }
        @Override public GlyphLayout draw(Batch batch, CharSequence value, float x, float y) {
            text.add(value.toString());
            return null;
        }
    }

    @Test public void permanentPanelRendersBothActionsWithinBoundsAndPreservesInputAndBatch() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        RecordingBatch batch = (RecordingBatch) unsafe.allocateInstance(RecordingBatch.class);
        batch.rectangles = new ArrayList<>(); batch.color = new Color(Color.WHITE);
        BitmapFont.BitmapFontData data = new BitmapFont.BitmapFontData();
        data.lineHeight = 16; data.capHeight = 12; data.down = -16; data.spaceWidth = 8;
        for (int c = 32; c < 127; c++) {
            BitmapFont.Glyph glyph = new BitmapFont.Glyph();
            glyph.id = c; glyph.width = 8; glyph.height = 12; glyph.xadvance = 8;
            data.setGlyph(c, glyph);
        }
        Texture fontTexture;
        RamSaverReleaseEvents.beginInternalResources();
        try { fontTexture = new Texture(new MockData(1)); }
        finally { RamSaverReleaseEvents.endInternalResources(); }
        RecordingFont font = new RecordingFont(data, fontTexture);
        BitmapFont previousFont = FontHelper.tipBodyFont;
        float previousScale = Settings.scale;
        int previousWidth = Settings.WIDTH, previousHeight = Settings.HEIGHT;
        Input previousInput = Gdx.input;
        InputProcessor original = new InputAdapter();
        final InputProcessor[] processor = {original};
        final int[] position = {20, 20};
        Gdx.input = proxy(Input.class, (name, args) -> {
            if (name.equals("getInputProcessor")) return processor[0];
            if (name.equals("setInputProcessor")) processor[0] = (InputProcessor) args[0];
            if (name.equals("getX")) return position[0];
            if (name.equals("getY")) return position[1];
            return null;
        });
        ReleaseDebugOverlay overlay = new ReleaseDebugOverlay();
        try {
            Settings.scale = 1f; Settings.WIDTH = 1280; Settings.HEIGHT = 720;
            FontHelper.tipBodyFont = font;
            RamSaverReleaseEvents.recordCreated(6, "image", "image.png", RamSaverReleaseEvents.TEXTURE, "upload", 1024, 7);
            RamSaverReleaseEvents.record(7, "image", "image.png", RamSaverReleaseEvents.TEXTURE, "idle", 1024, 7);
            long before = RamSaverReleaseEvents.latestSequence();
            // Bypass native pixel allocation only; the production method still draws every panel element.
            set(overlay, "pixel", fontTexture);
            overlay.render(batch);
            assertTrue(font.text.stream().anyMatch(s -> s.contains("+CREATE")));
            assertTrue(font.text.stream().anyMatch(s -> s.contains("-DESTROY")));
            assertTrue(font.text.contains("[Latest]"));
            float[] panel = batch.rectangles.get(0);
            assertEquals(12f, panel[0], 0f);
            assertEquals(708f, panel[1] + panel[3], 0f);
            assertEquals(194f, panel[3], 0f);
            assertEquals(Color.WHITE.toFloatBits(), batch.getPackedColor(), 0f);
            assertEquals(before, RamSaverReleaseEvents.latestSequence());
            assertNotSame(original, processor[0]);
            InputHelper.justClickedLeft = InputHelper.isMouseDown = true;
            overlay.beforeGameInput();
            assertFalse(InputHelper.justClickedLeft); assertFalse(InputHelper.isMouseDown);
            assertEquals(-10000, InputHelper.mX);
            InputProcessor replacement = new InputAdapter(); processor[0] = replacement;
            overlay.beforeGameInput(); assertNotSame(replacement, processor[0]);
            overlay.render(batch);
            // Reset only the visual history to exercise the production empty-state render path.
            Object history = field(RamSaverReleaseEvents.class, "HISTORY");
            set(history, "sequence", 0L);
            font.text.clear(); batch.rectangles.clear();
            overlay.render(batch);
            assertFalse(batch.rectangles.isEmpty());
            assertTrue(font.text.contains("Waiting for resource events..."));
            // Empty state still owns the same fixed-height panel and remains interactive.
            assertEquals(194f, batch.rectangles.get(0)[3], 0f);
            set(history, "sequence", before);
            overlay.dispose();
            assertSame(replacement, processor[0]);
            assertEquals("The panel pixel must not create its own destruction event", before, RamSaverReleaseEvents.latestSequence());
        } finally {
            overlay.dispose(); FontHelper.tipBodyFont = previousFont;
            Settings.scale = previousScale; Settings.WIDTH = previousWidth; Settings.HEIGHT = previousHeight;
            Gdx.input = previousInput;
        }
    }
}

package optispire.debug;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.RamSaverReleaseEvents;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;

/** Render-only diagnostics. No resource previews, file reads, stack captures or per-release GL work. */
public final class ReleaseDebugOverlay {
    private final ReleaseNoticeFeed feed = new ReleaseNoticeFeed();
    private final Color[] colors = new Color[RamSaverReleaseEvents.COLOR_COUNT];
    private final Color background = new Color(0f, 0f, 0f, .78f);
    private final Color textColor = new Color(.95f, .95f, .95f, 1f);
    private final String[] lines = new String[ReleaseNoticeFeed.PAGE_SIZE];
    private final GlyphLayout layout = new GlyphLayout();
    private long formattedPage;
    private float formattedWidth;
    private Texture pixel;

    public ReleaseDebugOverlay() {
        for (int i = 0; i < colors.length; i++) colors[i] = new Color(RamSaverReleaseEvents.colorRgba(i));
    }

    public void render(SpriteBatch sb) {
        feed.update(System.nanoTime());
        if (feed.size == 0 || FontHelper.tipBodyFont == null || sb == null) return;
        float scale = Settings.scale;
        float row = 23f * scale;
        float width = Math.min(1000f * scale, Settings.WIDTH - 24f * scale);
        if (formattedPage != feed.page[0].sequence || formattedWidth != width) {
            formattedPage = feed.page[0].sequence;
            formattedWidth = width;
            for (int i = 0; i < feed.size; i++) lines[i] = line(feed.page[i], width - 32f * scale);
        }
        float x = 12f * scale;
        float top = Settings.HEIGHT - 72f * scale;
        float height = row * (feed.size + 2) + 10f * scale;
        float oldColor = sb.getPackedColor();
        boolean began = !sb.isDrawing();
        if (began) sb.begin();
        try {
            sb.setColor(background);
            sb.draw(pixel(), x, top - height, width, height);
            FontHelper.renderFontLeftTopAligned(sb, FontHelper.tipBodyFont,
                    "RamSaver releases  total:" + RamSaverReleaseEvents.latestSequence()
                            + "  queued:" + feed.pending() + "  overwritten:" + feed.overwritten,
                    x + 8f * scale, top - 3f * scale, textColor);
            for (int i = 0; i < feed.size; i++) {
                float y = top - row * (i + 1);
                Color color = colors[feed.page[i].color];
                sb.setColor(color);
                sb.draw(pixel(), x + 8f * scale, y - 15f * scale, 7f * scale, 13f * scale);
                FontHelper.renderFontLeftTopAligned(sb, FontHelper.tipBodyFont,
                        lines[i], x + 22f * scale, y, color);
            }
            FontHelper.renderFontLeftTopAligned(sb, FontHelper.tipBodyFont,
                    "T: texture (estimated)   R: region (0 GPU bytes)   colors = frame markers",
                    x + 8f * scale, top - row * (feed.size + 1), textColor);
        } finally {
            sb.setColor(oldColor);
            if (began) sb.end();
        }
    }

    private String line(RamSaverReleaseEvents.Event event, float availableWidth) {
        String path = event.resource.replace('\n', ' ').replace('\r', ' ');
        if (path.length() > 74) path = "..." + path.substring(path.length() - 71);
        String prefix = "#" + event.sequence + " f:" + event.frame
                + (event.kind == RamSaverReleaseEvents.TEXTURE ? " T ~" + event.estimatedBytes / 1024L + " KiB" : " R 0 GPU")
                + " [" + event.reason + "] ";
        String text = prefix + path;
        layout.setText(FontHelper.tipBodyFont, text);
        while (layout.width > availableWidth && path.length() > 8) {
            path = "..." + path.substring(Math.min(path.length() - 5, 7));
            text = prefix + path;
            layout.setText(FontHelper.tipBodyFont, text);
        }
        return text;
    }

    private Texture pixel() {
        if (pixel == null) {
            Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            try {
                pixmap.setColor(Color.WHITE);
                pixmap.fill();
                pixel = new Texture(pixmap);
            } finally { pixmap.dispose(); }
        }
        return pixel;
    }

    public void dispose() {
        if (pixel != null) { pixel.dispose(); pixel = null; }
    }
}

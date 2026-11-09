package optispire.debug;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.RamSaverReleaseEvents;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.Gdx;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;

/** Bounded lifecycle history panel. No resource previews, file reads, stacks or per-event GL work. */
public final class ReleaseDebugOverlay {
    private final ReleaseNoticeFeed feed = new ReleaseNoticeFeed();
    private final ReleaseOverlayInput input = new ReleaseOverlayInput(feed);
    private final Color[] colors = new Color[RamSaverReleaseEvents.COLOR_COUNT];
    private final Color background = new Color(0f, 0f, 0f, .78f);
    private final Color textColor = new Color(.95f, .95f, .95f, 1f);
    private final String[] lines = new String[ReleaseNoticeFeed.PAGE_SIZE];
    private final GlyphLayout layout = new GlyphLayout();
    private long formattedPage;
    private float formattedWidth;
    private Texture pixel;
    private String heading = "RamSaver resources";
    private long formattedLatest = -1L;
    private int formattedOffset = -1;

    public ReleaseDebugOverlay() {
        for (int i = 0; i < colors.length; i++) colors[i] = new Color(RamSaverReleaseEvents.colorRgba(i));
    }

    public void render(SpriteBatch sb) {
        feed.update(System.nanoTime());
        if (FontHelper.tipBodyFont == null || sb == null) return;
        float scale = Settings.scale;
        float row = 23f * scale;
        float width = Math.min(1000f * scale, Settings.WIDTH - 24f * scale);
        long first = feed.size == 0 ? 0L : feed.page[0].sequence;
        long latest = RamSaverReleaseEvents.latestSequence();
        if (formattedPage != first || formattedWidth != width || formattedLatest != latest || formattedOffset != feed.offset()) {
            formattedPage = first;
            formattedWidth = width;
            formattedLatest = latest;
            formattedOffset = feed.offset();
            heading = "RamSaver resources  total:" + latest + "  "
                    + (feed.available() == 0 ? "0/0" : (feed.offset() + 1) + "-" + (feed.offset() + feed.size) + "/" + feed.available())
                    + "  overwritten:" + feed.overwritten;
            heading = fit(heading, width - row * 5f - 16f * scale);
            for (int i = 0; i < feed.size; i++) lines[i] = line(feed.page[i], width - 54f * scale);
        }
        float x = 12f * scale;
        float top = Settings.HEIGHT - 12f * scale;
        float height = row * (ReleaseNoticeFeed.PAGE_SIZE + 2) + 10f * scale;
        input.bounds(x, Settings.HEIGHT - top, width, height, row);
        ensureInputProcessor();
        float oldColor = sb.getPackedColor();
        boolean began = !sb.isDrawing();
        if (began) sb.begin();
        try {
            sb.setColor(background);
            sb.draw(pixel(), x, top - height, width, height);
            FontHelper.renderFontLeftTopAligned(sb, FontHelper.tipBodyFont,
                    heading,
                    x + 8f * scale, top - 3f * scale, textColor);
            FontHelper.renderFontLeftTopAligned(sb, FontHelper.tipBodyFont, "[Latest]",
                    x + width - row * 4f, top - 3f * scale, textColor);
            if (feed.size == 0) FontHelper.renderFontLeftTopAligned(sb, FontHelper.tipBodyFont,
                    "Waiting for resource events...", x + 8f * scale, top - row, textColor);
            for (int i = 0; i < feed.size; i++) {
                float y = top - row * (i + 1);
                Color color = colors[feed.page[i].color];
                sb.setColor(color);
                sb.draw(pixel(), x + 8f * scale, y - 15f * scale, 7f * scale, 13f * scale);
                FontHelper.renderFontLeftTopAligned(sb, FontHelper.tipBodyFont,
                        lines[i], x + 22f * scale, y, color);
            }
            FontHelper.renderFontLeftTopAligned(sb, FontHelper.tipBodyFont,
                    "Blue + create  |  Orange - destroy  |  Drag / wheel to scroll",
                    x + 8f * scale, top - row * (ReleaseNoticeFeed.PAGE_SIZE + 1), textColor);
            if (feed.maxOffset() > 0) {
                float trackHeight = row * ReleaseNoticeFeed.PAGE_SIZE;
                float thumb = Math.max(row / 2f, trackHeight * ReleaseNoticeFeed.PAGE_SIZE / feed.available());
                float travel = trackHeight - thumb;
                sb.setColor(textColor);
                sb.draw(pixel(), x + width - 10f * scale,
                        top - row - thumb - travel * feed.offset() / feed.maxOffset(), 4f * scale, thumb);
            }
        } finally {
            sb.setColor(oldColor);
            if (began) sb.end();
        }
    }

    private String line(RamSaverReleaseEvents.Event event, float availableWidth) {
        String path = event.resource.replace('\n', ' ').replace('\r', ' ');
        if (path.length() > 74) path = "..." + path.substring(path.length() - 71);
        String prefix = "#" + event.sequence + " f:" + event.frame
                + (event.action == RamSaverReleaseEvents.CREATE ? " +CREATE" : " -DESTROY")
                + (event.handle == 0 ? "" : " h:" + event.handle)
                + (event.kind == RamSaverReleaseEvents.REGION ? " R 0 GPU"
                    : (event.kind == RamSaverReleaseEvents.FRAMEBUFFER ? " FBO ~" : " T ~") + event.estimatedBytes / 1024L + " KiB")
                + " [" + event.reason + "] ";
        String text = prefix + path;
        layout.setText(FontHelper.tipBodyFont, text);
        while (layout.width > availableWidth && path.length() > 8) {
            path = "..." + path.substring(Math.min(path.length() - 5, 7));
            text = prefix + path;
            layout.setText(FontHelper.tipBodyFont, text);
        }
        return fit(text, availableWidth);
    }

    private String fit(String text, float availableWidth) {
        layout.setText(FontHelper.tipBodyFont, text);
        int end = text.length();
        while (layout.width > availableWidth && end > 0) {
            end--;
            if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            layout.setText(FontHelper.tipBodyFont, text.substring(0, end) + "...");
        }
        return end == text.length() ? text : text.substring(0, end) + "...";
    }

    private Texture pixel() {
        if (pixel == null) {
            RamSaverReleaseEvents.beginInternalResources();
            try {
                Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
                try {
                    pixmap.setColor(Color.WHITE);
                    pixmap.fill();
                    pixel = new Texture(pixmap);
                } finally { pixmap.dispose(); }
            } finally { RamSaverReleaseEvents.endInternalResources(); }
        }
        return pixel;
    }

    public void dispose() {
        if (Gdx.input != null && Gdx.input.getInputProcessor() == input) Gdx.input.setInputProcessor(input.delegate());
        if (pixel != null) {
            RamSaverReleaseEvents.beginInternalResources();
            try { pixel.dispose(); pixel = null; }
            finally { RamSaverReleaseEvents.endInternalResources(); }
        }
    }

    private void ensureInputProcessor() {
        if (Gdx.input == null) return;
        com.badlogic.gdx.InputProcessor current = Gdx.input.getInputProcessor();
        if (current != input) { input.delegate(current); Gdx.input.setInputProcessor(input); }
    }

    /** Also mask the game's polled pointer path so panel gestures do not click the game underneath. */
    public void beforeGameInput() {
        ensureInputProcessor();
        if (Gdx.input != null && input.suppressGamePointer(Gdx.input.getX(), Gdx.input.getY())) {
            InputHelper.justClickedLeft = false;
            InputHelper.justReleasedClickLeft = false;
            InputHelper.isMouseDown = false;
            InputHelper.touchDown = false;
            InputHelper.touchUp = false;
            InputHelper.mX = InputHelper.mY = -10000;
        }
        input.finishInputFrame();
    }
}

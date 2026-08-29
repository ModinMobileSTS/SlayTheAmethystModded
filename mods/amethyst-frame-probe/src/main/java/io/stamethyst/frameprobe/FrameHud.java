package io.stamethyst.frameprobe;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;

/**
 * Lightweight per-frame bar chart HUD.
 *
 * <p>One bar per entry in a 180-frame rolling window (2 s at 90 fps).
 * Colours indicate severity relative to {@link com.badlogic.gdx.backends.lwjgl.FrameRingBuffer#BUDGET_NS}:
 * <ul>
 *   <li>Green  — within budget</li>
 *   <li>Yellow — 1×–2× budget</li>
 *   <li>Red    — > 2× budget</li>
 * </ul>
 *
 * <p>The HUD also prints a one-line text summary in the top-left corner.
 * Rendered via BaseMod's PostRenderSubscriber so it always sits on top.
 *
 * <p>Zero heap allocation per frame on the hot path.
 */
public final class FrameHud {

    // ── layout ───────────────────────────────────────────────────────────────
    private static final int   WINDOW    = 180;   // bars shown
    private static final float BAR_W     = 4f;
    private static final float BAR_GAP   = 1f;
    private static final float ORIGIN_X  = 12f;
    private static final float ORIGIN_Y  = 12f;   // bottom of chart, in screen coords
    private static final float MAX_H     = 60f;   // height at 3× budget
    private static final float SCALE_H   = MAX_H / 3f; // px per budget multiple

    // ── colours ───────────────────────────────────────────────────────────────
    private static final Color GREEN  = new Color(0.22f, 0.85f, 0.28f, 0.85f);
    private static final Color YELLOW = new Color(0.95f, 0.85f, 0.15f, 0.85f);
    private static final Color RED    = new Color(0.95f, 0.22f, 0.15f, 0.90f);
    private static final Color BLACK  = new Color(0f, 0f, 0f, 0.50f);
    private static final Color WHITE  = new Color(1f, 1f, 1f, 0.90f);

    // ── ring shadow for the chart ─────────────────────────────────────────────
    /** Circular buffer of totalNs values, length WINDOW. */
    private final long[] ring   = new long[WINDOW];
    private int ringHead = 0;   // next write position
    private int ringSize = 0;   // how many valid entries

    // ── stats for the text summary ────────────────────────────────────────────
    private long   lastTotalNs   = 0L;
    private float  p99Ms         = 0f;
    private int    budgetBreaches = 0;

    // ── IncidentWriter back-ref for drain loop ────────────────────────────────
    private final IncidentWriter writer;
    private final long budgetNs;

    // ── launcher perf snapshot (low-frequency, launcher writes every 1 s) ──────
    private final java.io.File snapshotFile;
    private String lastSnapshotRenderer = "";
    private String lastSnapshotFps      = "--";
    private String lastSnapshotJvm      = "--";
    private String lastSnapshotGpu      = "--";
    private String lastSnapshotMem      = "--";
    private String lastSnapshotGc       = "--";

    /**
     * A 1×1 white Pixmap texture owned entirely by this HUD.
     * Not managed by ram-saver, so it is never aged out.
     * Created lazily on first render to avoid issues with GL context not yet ready.
     */
    private com.badlogic.gdx.graphics.Texture ownTexture;

    public FrameHud(IncidentWriter writer, long budgetNs, java.io.File snapshotFile) {
        this.writer       = writer;
        this.budgetNs     = budgetNs;
        this.snapshotFile = snapshotFile;
    }

    /**
     * Called once per game update from {@link AmethystFrameProbe}.
     * Drains FrameRingBuffer, writes incidents, pushes data into local ring.
     */
    public void update() {
        com.badlogic.gdx.backends.lwjgl.FrameRingBuffer.FrameConsumer consumer =
            new com.badlogic.gdx.backends.lwjgl.FrameRingBuffer.FrameConsumer() {
                @Override
                public void consume(
                        long fid, long wallMs, long total, long render,
                        long guardian, long reclaim, long swap,
                        long heap, int flushes, int switches) {
                    // push to chart ring
                    ring[ringHead] = total;
                    ringHead = (ringHead + 1) % WINDOW;
                    if (ringSize < WINDOW) ringSize++;
                    lastTotalNs = total;
                    // write incident if over budget
                    if (total >= budgetNs && writer != null) {
                        writer.enqueue(buildIncidentLine(
                            fid, wallMs, total, render, guardian, reclaim, swap,
                            heap, flushes, switches));
                    }
                }
            };
        // drain all available frames this update tick
        //noinspection StatementWithEmptyBody
        while (com.badlogic.gdx.backends.lwjgl.FrameRingBuffer.drain(consumer)) {}

        // compute p99 and breach count over current window
        recomputeStats();
        // read launcher perf snapshot（启动器每秒写一次，这里低频读取）
        readLauncherSnapshot();
    }

    private void recomputeStats() {
        if (ringSize == 0) return;
        long[] copy = new long[ringSize];
        // collect the most recent `ringSize` entries (handles wrap)
        int head = ringHead;
        for (int i = 0; i < ringSize; i++) {
            copy[i] = ring[(head - 1 - i + WINDOW) % WINDOW];
        }
        java.util.Arrays.sort(copy);
        int p99idx = (int)(ringSize * 0.99f);
        if (p99idx >= ringSize) p99idx = ringSize - 1;
        p99Ms = copy[p99idx] / 1_000_000f;
        budgetBreaches = 0;
        for (long v : copy) if (v >= budgetNs) budgetBreaches++;
    }

    /**
     * 统一浮层渲染，替代启动器的 Android TextView。
     * 布局（从上到下）：
     *   ① 启动器指标行：▣ 渲染   ◷ FPS   J JVM   G GPU   Σ 总计   ↺ GC
     *   ② 帧时统计行：  last:Xms  p99:Xms  over:N/M
     *   ③ 帧时柱状图（180 根，绿/黄/红）
     *
     * SpriteBatch 已由 BaseMod 的 PostRenderSubscriber 开启。
     */
    public void render(SpriteBatch sb) {
        if (ringSize == 0 && lastSnapshotFps.equals("--")) return;
        float scale = Settings.scale;
        float ox = ORIGIN_X * scale;
        float oy = ORIGIN_Y * scale;
        float bw = BAR_W   * scale;
        float bg = BAR_GAP  * scale;
        float maxH = MAX_H  * scale;
        float scaleH = SCALE_H * scale;

        float lineH  = 22f * scale;  // 每行文字高度
        float textPad = 4f * scale;

        // ── 柱状图区域尺寸 ─────────────────────────────────────────────────────
        float barAreaW = WINDOW * (bw + bg);
        // ── 背景面板（柱状图 + 两行文字 + 上下边距）──────────────────────────
        float panelW = barAreaW + bg * 4f;
        float panelH = maxH + lineH * 2f + textPad * 3f;
        sb.setColor(BLACK);
        sb.draw(ownTexture(), ox - bg * 2f, oy - bg, panelW, panelH + bg * 2f);

        // ── ① 启动器指标行 ─────────────────────────────────────────────────────
        float row1Y = oy + panelH - textPad - lineH;
        sb.setColor(WHITE);
        String launcherLine = String.format(
            java.util.Locale.US,
            "▣ %s  ◷ %s  J %s  G %s  Σ %s  ↺ %s",
            lastSnapshotRenderer, lastSnapshotFps,
            lastSnapshotJvm, lastSnapshotGpu,
            lastSnapshotMem, lastSnapshotGc);
        FontHelper.renderFontLeftTopAligned(sb, FontHelper.tipBodyFont,
            launcherLine, ox, row1Y + lineH, WHITE);

        // ── ② 帧时统计行 ───────────────────────────────────────────────────────
        float row2Y = row1Y - lineH;
        float lastMs = lastTotalNs / 1_000_000f;
        String frameLine = String.format(
            java.util.Locale.US,
            "last:%.1fms  p99:%.1fms  over:%d/%d",
            lastMs, p99Ms, budgetBreaches, ringSize);
        // p99 超预算时用红色提示
        Color frameColor = p99Ms > (budgetNs / 1_000_000f) ? RED : WHITE;
        FontHelper.renderFontLeftTopAligned(sb, FontHelper.tipBodyFont,
            frameLine, ox, row2Y + lineH, frameColor);

        // ── ③ 柱状图 ───────────────────────────────────────────────────────────
        int head = ringHead;
        for (int i = 0; i < ringSize; i++) {
            long ns = ring[(head - ringSize + i + WINDOW) % WINDOW];
            float ratio = (float)(ns / (double) budgetNs);
            float barH  = Math.min(ratio * scaleH, maxH);
            Color c = ratio <= 1f ? GREEN : (ratio <= 2f ? YELLOW : RED);
            sb.setColor(c);
            float bx = ox + i * (bw + bg);
            sb.draw(ownTexture(), bx, oy, bw, Math.max(barH, 1f));
        }

        sb.setColor(WHITE);  // 恢复颜色，避免影响后续绘制
    }

    /** Returns the HUD's own 1×1 white texture, creating it on first call. */
    private com.badlogic.gdx.graphics.Texture ownTexture() {
        if (ownTexture == null) {
            com.badlogic.gdx.graphics.Pixmap pm =
                new com.badlogic.gdx.graphics.Pixmap(1, 1,
                    com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
            pm.setColor(1f, 1f, 1f, 1f);
            pm.fill();
            ownTexture = new com.badlogic.gdx.graphics.Texture(pm);
            pm.dispose();
        }
        return ownTexture;
    }

    /** @deprecated Called by BaseMod when the mod is unloaded (not actually called in StS,
     *  but good practice to release the texture). */
    public void dispose() {
        if (ownTexture != null) { ownTexture.dispose(); ownTexture = null; }
    }

    /**
     * 读取启动器写入的性能快照文件，解析 key=value;… 格式。
     * 文件最多 256 字节，读取开销极低。
     */
    private void readLauncherSnapshot() {
        if (snapshotFile == null || !snapshotFile.isFile()) return;
        try {
            String line = new java.io.BufferedReader(
                new java.io.FileReader(snapshotFile)).readLine();
            if (line == null || line.isEmpty()) return;
            for (String entry : line.split(";")) {
                int eq = entry.indexOf('=');
                if (eq <= 0 || eq >= entry.length() - 1) continue;
                String key = entry.substring(0, eq).trim();
                String val = entry.substring(eq + 1).trim();
                switch (key) {
                    case "renderer": lastSnapshotRenderer = val; break;
                    case "fps":      lastSnapshotFps      = val; break;
                    case "jvm":      lastSnapshotJvm      = val; break;
                    case "gpu":      lastSnapshotGpu      = val; break;
                    case "mem":      lastSnapshotMem      = val; break;
                    case "gc":       lastSnapshotGc       = val; break;
                }
            }
        } catch (Throwable ignored) {}
    }

    // ── JSONL line builder ────────────────────────────────────────────────────

    private String buildIncidentLine(
            long fid, long wallMs, long total, long render,
            long guardian, long reclaim, long swap,
            long heap, int flushes, int switches) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendLong(sb, "t", wallMs, false);
        appendLong(sb, "frame", fid, true);
        appendDouble(sb, "totalMs",    total    / 1e6, true);
        appendDouble(sb, "renderMs",   render   / 1e6, true);
        appendDouble(sb, "guardianMs", guardian / 1e6, true);
        appendDouble(sb, "reclaimMs",  reclaim  / 1e6, true);
        appendDouble(sb, "swapMs",     swap     / 1e6, true);
        appendLong(sb, "heapMb", heap / (1024L * 1024L), true);
        appendInt(sb, "flushes",  flushes,  true);
        appendInt(sb, "switches", switches, true);
        // game context
        String ctx = GameContext.INSTANCE.toJsonFragment();
        if (!ctx.isEmpty()) sb.append(',').append(ctx);
        sb.append('}');
        return sb.toString();
    }

    private static void appendLong(StringBuilder sb, String k, long v, boolean comma) {
        if (comma) sb.append(',');
        sb.append('"').append(k).append("\":").append(v);
    }
    private static void appendInt(StringBuilder sb, String k, int v, boolean comma) {
        if (comma) sb.append(',');
        sb.append('"').append(k).append("\":").append(v);
    }
    private static void appendDouble(StringBuilder sb, String k, double v, boolean comma) {
        if (comma) sb.append(',');
        sb.append('"').append(k).append("\":").append(
            String.format(java.util.Locale.US, "%.3f", v));
    }
}

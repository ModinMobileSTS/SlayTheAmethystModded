package com.badlogic.gdx.backends.lwjgl;

/**
 * Zero-allocation per-frame ring buffer.
 *
 * <p>Single writer (render thread), single reader (also render thread via BaseMod
 * PostRenderSubscriber). No synchronization needed – both sides run on the same
 * thread. The consumer drains via {@link #drain(FrameConsumer)} once per game
 * update; frames that have been lapped by the writer (buffer overrun) are
 * silently skipped.
 *
 * <p>Enabled by the system property {@code amethyst.gdx.frame_ring=true}.
 * When disabled, {@link #record} is a no-op and every timing variable in
 * LwjglApplication stays at 0 – the JIT eliminates the dead branches.
 */
public final class FrameRingBuffer {

    /** Property key consumed by LwjglApplication, SpriteBatch, and the frame-probe mod. */
    public static final String PROP = "amethyst.gdx.frame_ring";

    /** True iff frame recording is active. Evaluated once at class-load. */
    public static final boolean ENABLED = Boolean.getBoolean(PROP);

    /** Budget threshold in nanoseconds, default 1000ms/90fps ≈ 11.1ms.
     *  Override with {@code amethyst.gdx.frame_ring.budget_ms}. */
    public static final long BUDGET_NS;

    /** Ring capacity: 20 s @ 90 fps. */
    public static final int CAPACITY = 1800;

    // ── per-slot arrays (parallel layout, indexed by slot = writePos % CAPACITY) ──

    /** Game frame ID (graphics.frameId). */
    public static final long[] frameId     = new long[CAPACITY];
    /** System.currentTimeMillis() at the start of the frame. */
    public static final long[] wallClockMs = new long[CAPACITY];
    /** Total frame duration ns (from first nanoTime to after Display.update). */
    public static final long[] totalNs     = new long[CAPACITY];
    /** Duration of listener.render() only. */
    public static final long[] renderNs    = new long[CAPACITY];
    /** Duration of GPU resource guardian pass. */
    public static final long[] guardianNs  = new long[CAPACITY];
    /** Duration of texture + FBO reclaim passes combined. */
    public static final long[] reclaimNs   = new long[CAPACITY];
    /** Duration of Display.update() (EGL swap). */
    public static final long[] swapNs      = new long[CAPACITY];
    /** JVM heap used bytes at end of frame. */
    public static final long[] heapBytes   = new long[CAPACITY];
    /** SpriteBatch flush count this frame. */
    public static final int[]  flushCount  = new int[CAPACITY];
    /** SpriteBatch texture-switch count this frame. */
    public static final int[]  switchCount = new int[CAPACITY];

    // ── cursors (render-thread only, no volatile/atomic needed) ──

    /** Monotonically increasing write cursor. Slot = writePos % CAPACITY. */
    private static int writePos = 0;
    /** Read cursor advanced by {@link #drain}. */
    private static int readPos  = 0;

    static {
        int ms = 11;
        try {
            String v = System.getProperty("amethyst.gdx.frame_ring.budget_ms");
            if (v != null) {
                int parsed = Integer.parseInt(v.trim());
                if (parsed > 0 && parsed < 10000) ms = parsed;
            }
        } catch (Throwable ignored) {}
        BUDGET_NS = ms * 1_000_000L;
    }

    private FrameRingBuffer() {}

    /**
     * Record one frame. Called at the end of every rendered frame from
     * LwjglApplication.mainLoop(). No-op when {@link #ENABLED} is false.
     */
    public static void record(
            long fid, long wallMs,
            long total, long render, long guardian, long reclaim, long swap,
            long heap, int flushes, int switches) {
        if (!ENABLED) return;
        int slot = writePos % CAPACITY;
        frameId[slot]     = fid;
        wallClockMs[slot] = wallMs;
        totalNs[slot]     = total;
        renderNs[slot]    = render;
        guardianNs[slot]  = guardian;
        reclaimNs[slot]   = reclaim;
        swapNs[slot]      = swap;
        heapBytes[slot]   = heap;
        flushCount[slot]  = flushes;
        switchCount[slot] = switches;
        // Unsigned increment; wrap guard keeps it non-negative.
        if (++writePos < 0) writePos = CAPACITY;
    }

    /** Returns the number of unread frames available to drain. */
    public static int available() {
        return writePos - readPos;
    }

    /** Callback interface for {@link #drain}. No allocation required on the hot path. */
    public interface FrameConsumer {
        void consume(long fid, long wallMs, long totalNs, long renderNs,
                     long guardianNs, long reclaimNs, long swapNs,
                     long heapBytes, int flushes, int switches);
    }

    /**
     * Deliver one unread frame to {@code consumer}. If the writer has lapped the
     * reader (overrun), the reader is fast-forwarded to the oldest available slot.
     *
     * @return {@code true} if a frame was delivered, {@code false} when the buffer
     *         is empty.
     */
    public static boolean drain(FrameConsumer consumer) {
        if (readPos >= writePos) return false;
        // Guard overrun: skip silently to oldest available slot.
        if (writePos - readPos > CAPACITY) readPos = writePos - CAPACITY;
        int slot = readPos % CAPACITY;
        consumer.consume(
            frameId[slot], wallClockMs[slot], totalNs[slot], renderNs[slot],
            guardianNs[slot], reclaimNs[slot], swapNs[slot],
            heapBytes[slot], flushCount[slot], switchCount[slot]);
        readPos++;
        return true;
    }
}

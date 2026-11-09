package com.badlogic.gdx.graphics;

/** Small, opt-in bridge shared by RamSaver and the frame HUD. Never owns game/GL resources. */
public final class RamSaverReleaseEvents {
    public static final String PROPERTY = "ramsaver.debug.visual";
    public static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    public static final int TEXTURE = 1;
    public static final int REGION = 2;
    public static final int FRAMEBUFFER = 3;
    public static final int CREATE = 1;
    public static final int DESTROY = 2;
    public static final int CAPACITY = 512;
    public static final int COLOR_COUNT = 2;
    private static final int FRAME_CAPACITY = 1800;
    private static final int[] COLORS = {
            0x55C8FFFF, 0xFFA45CFF
    };
    // Disabled launches do not even allocate history arrays.
    private static final History HISTORY = ENABLED ? new History() : null;
    private static final ThreadLocal<Integer> INTERNAL = ENABLED ? new ThreadLocal<Integer>() : null;

    private RamSaverReleaseEvents() { }

    public static int colorIndex(int action) { return action == CREATE ? 0 : 1; }

    public static int colorRgba(int index) { return COLORS[index % COLOR_COUNT]; }

    public static void record(long frame, String key, String resource, int kind,
                              String reason, long estimatedBytes, int oldHandle) {
        recordAction(frame, key, resource, kind, DESTROY, reason, estimatedBytes, oldHandle);
    }

    public static void recordCreated(long frame, String key, String resource, int kind,
                                     String reason, long estimatedBytes, int handle) {
        recordAction(frame, key, resource, kind, CREATE, reason, estimatedBytes, handle);
    }

    /** Diagnostic pixels must not produce notices/markers about the diagnostics themselves. */
    public static void beginInternalResources() {
        if (!ENABLED) return;
        Integer depth = INTERNAL.get();
        INTERNAL.set(depth == null ? 1 : depth + 1);
    }

    public static void endInternalResources() {
        if (!ENABLED) return;
        Integer depth = INTERNAL.get();
        if (depth == null || depth <= 1) INTERNAL.remove();
        else INTERNAL.set(depth - 1);
    }

    private static void recordAction(long frame, String key, String resource, int kind, int action,
                                     String reason, long estimatedBytes, int handle) {
        if (!ENABLED || INTERNAL.get() != null) return;
        synchronized (HISTORY) {
            long sequence = ++HISTORY.sequence;
            Event event = new Event(sequence, frame, key, resource, kind, action, reason,
                    Math.max(0L, estimatedBytes), handle);
            HISTORY.events[(int) ((sequence - 1L) % CAPACITY)] = event;
            if (frame < 0L) return; // Initialisation events have no matching rendered frame.
            int slot = (int) (frame % FRAME_CAPACITY);
            if (HISTORY.frameSequences[slot] != frame + 1L) {
                HISTORY.frameSequences[slot] = frame + 1L;
                HISTORY.frameColors[slot] = 0;
            }
            HISTORY.frameColors[slot] |= 1 << event.color;
        }
    }

    public static long latestSequence() {
        if (!ENABLED) return 0L;
        synchronized (HISTORY) { return HISTORY.sequence; }
    }

    /** A bounded history: callers can explicitly report overwritten notification entries. */
    public static long firstSequence() {
        return Math.max(1L, latestSequence() - CAPACITY + 1L);
    }

    public static Event event(long sequence) {
        if (!ENABLED || sequence < 1L) return null;
        synchronized (HISTORY) {
            Event event = HISTORY.events[(int) ((sequence - 1L) % CAPACITY)];
            return event != null && event.sequence == sequence ? event : null;
        }
    }

    public static int colorsForFrame(long frame) {
        if (!ENABLED || frame < 0L) return 0;
        synchronized (HISTORY) {
            int slot = (int) (frame % FRAME_CAPACITY);
            return HISTORY.frameSequences[slot] == frame + 1L ? HISTORY.frameColors[slot] : 0;
        }
    }

    public static final class Event {
        public final long sequence, frame, estimatedBytes;
        public final String key, resource, reason;
        public final int kind, action, handle, oldHandle, color;

        private Event(long sequence, long frame, String key, String resource, int kind, int action,
                      String reason, long estimatedBytes, int handle) {
            this.sequence = sequence;
            this.frame = frame;
            this.key = bounded(key);
            this.resource = bounded(resource);
            this.kind = kind;
            this.action = action;
            this.reason = reason;
            this.estimatedBytes = kind == REGION ? 0L : estimatedBytes;
            this.handle = handle;
            this.oldHandle = action == DESTROY ? handle : 0;
            this.color = colorIndex(action);
        }

        private static String bounded(String text) {
            if (text == null) return "unknown";
            return text.length() <= 1024 ? text : text.substring(0, 1024);
        }
    }

    private static final class History {
        final Event[] events = new Event[CAPACITY];
        final long[] frameSequences = new long[FRAME_CAPACITY];
        final int[] frameColors = new int[FRAME_CAPACITY];
        long sequence;
    }
}

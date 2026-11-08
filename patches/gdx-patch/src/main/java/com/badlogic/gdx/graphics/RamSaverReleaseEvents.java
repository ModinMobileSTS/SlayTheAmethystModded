package com.badlogic.gdx.graphics;

/** Small, opt-in bridge shared by RamSaver and the frame HUD. Never owns game/GL resources. */
public final class RamSaverReleaseEvents {
    public static final String PROPERTY = "ramsaver.debug.visual";
    public static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    public static final int TEXTURE = 1;
    public static final int REGION = 2;
    public static final int CAPACITY = 512;
    public static final int COLOR_COUNT = 12;
    private static final int FRAME_CAPACITY = 1800;
    private static final int[] COLORS = {
            0x45CFFFFF, 0xFF80BFFF, 0xB39DFFFF, 0xFFAD66FF,
            0x66E0C2FF, 0xA8D86AFF, 0x82B1FFFF, 0xE6B3FFFF,
            0xFFCF80FF, 0xB0E0E6FF, 0xFF8A80FF, 0xD4E157FF
    };
    // Disabled launches do not even allocate history arrays.
    private static final History HISTORY = ENABLED ? new History() : null;

    private RamSaverReleaseEvents() { }

    public static int colorIndex(String key) {
        int hash = key == null ? 0 : key.hashCode();
        hash ^= hash >>> 16;
        return (hash & Integer.MAX_VALUE) % COLOR_COUNT;
    }

    public static int colorRgba(int index) { return COLORS[index % COLOR_COUNT]; }

    public static void record(long frame, String key, String resource, int kind,
                              String reason, long estimatedBytes, int oldHandle) {
        if (!ENABLED) return;
        synchronized (HISTORY) {
            long sequence = ++HISTORY.sequence;
            Event event = new Event(sequence, frame, key, resource, kind, reason,
                    Math.max(0L, estimatedBytes), oldHandle);
            HISTORY.events[(int) ((sequence - 1L) % CAPACITY)] = event;
            if (frame < 0L) return; // Initialisation releases have no matching rendered frame.
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
        public final int kind, oldHandle, color;

        private Event(long sequence, long frame, String key, String resource, int kind,
                      String reason, long estimatedBytes, int oldHandle) {
            this.sequence = sequence;
            this.frame = frame;
            this.key = bounded(key);
            this.resource = bounded(resource);
            this.kind = kind;
            this.reason = reason;
            this.estimatedBytes = kind == TEXTURE ? estimatedBytes : 0L;
            this.oldHandle = oldHandle;
            this.color = colorIndex(key);
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

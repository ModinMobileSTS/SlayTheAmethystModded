package optispire;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class RamSaverDiag {
    private static final String RAM_SAVER_DIAG_PROP = "ramsaver.diag.enabled";
    private static final String RAM_SAVER_VERBOSE_PROP = "ramsaver.diag.verbose";
    private static final boolean ENABLED = readBoolean(RAM_SAVER_DIAG_PROP, false);
    private static final boolean VERBOSE = readBoolean(RAM_SAVER_VERBOSE_PROP, false);
    private static final int STACK_DEPTH = readInt("ramsaver.diag.stack_depth", 14, 1, 64);
    private static final long SLOW_EVENT_NANOS = readLong("ramsaver.diag.slow_ms", 4L, 0L, 60000L) * 1000000L;
    private static final Map<String, Integer> repeats = new ConcurrentHashMap<>();
    private static volatile boolean glTextureLiveCounterResolved = false;
    private static volatile AtomicInteger glTextureLiveCounter = null;

    private RamSaverDiag() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static long now() {
        return enabled() ? System.nanoTime() : 0L;
    }

    public static boolean verbose() {
        return VERBOSE;
    }

    public static void markFakeTextureWrapperConstructed(String key) {
        AtomicInteger counter = getGlTextureLiveCounter();
        if (counter == null) {
            return;
        }
        int live = counter.decrementAndGet();
        if (live < 0) {
            counter.set(0);
            live = 0;
        }
        if (verbose()) {
            logRepeat("fake_texture_gl_diag_untracked", key, "live=" + live);
        }
    }

    public static void log(String event, String details) {
        if (!enabled() || !shouldEmit(event)) {
            return;
        }
        System.out.println("[ram-saver] event=" + event + " " + details + runtimeSuffix());
    }

    public static void logRepeat(String event, String key, String details) {
        if (!enabled() || !shouldEmit(event)) {
            return;
        }
        int repeat = increment(event + "|" + key);
        if (shouldLogRepeat(repeat)) {
            log(event, "repeat=" + repeat + " key=" + safe(key) + " " + details);
        }
    }

    public static void logStackRepeat(String event, String key, String details) {
        if (!enabled() || !shouldEmit(event)) {
            return;
        }
        String stack = stack();
        int repeat = increment(event + "|" + key + "|" + stack);
        if (shouldLogRepeat(repeat)) {
            log(event, "repeat=" + repeat + " key=" + safe(key) + " " + details + " stack=" + stack);
        }
    }

    public static void logDuration(String event, String key, long startedNanos, String details, boolean includeStack) {
        if (!enabled()) {
            return;
        }
        long elapsedNanos = Math.max(0L, System.nanoTime() - startedNanos);
        String durationEvent = elapsedNanos >= SLOW_EVENT_NANOS ? event + "_slow" : event;
        if (!shouldEmit(durationEvent)) {
            return;
        }
        String elapsedDetails = "elapsedMs=" + formatMillis(elapsedNanos) + " " + details;
        if (elapsedNanos >= SLOW_EVENT_NANOS) {
            if (includeStack) {
                logStackRepeat(durationEvent, key, elapsedDetails);
            }
            else {
                logRepeat(durationEvent, key, elapsedDetails);
            }
            return;
        }
        logRepeat(durationEvent, key, elapsedDetails);
    }

    public static String elapsedMs(long startedNanos) {
        return formatMillis(Math.max(0L, System.nanoTime() - startedNanos));
    }

    public static String formatElapsedMillis(long elapsedNanos) {
        return formatMillis(Math.max(0L, elapsedNanos));
    }

    public static String describeObject(Object value) {
        if (value == null) {
            return "null";
        }
        return value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    public static String safe(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace('\n', '_').replace('\r', '_');
    }

    private static int increment(String key) {
        Integer current = repeats.get(key);
        int next = current == null ? 1 : current + 1;
        repeats.put(key, next);
        return next;
    }

    private static boolean shouldLogRepeat(int repeat) {
        return repeat <= 3
                || repeat == 5
                || repeat == 10
                || repeat == 25
                || repeat == 50
                || repeat == 100
                || (repeat <= 1000 && repeat % 100 == 0)
                || repeat % 1000 == 0;
    }

    private static boolean shouldEmit(String event) {
        return verbose() || isImportantEvent(event);
    }

    private static boolean isImportantEvent(String event) {
        return "init".equals(event)
                || event.indexOf("_slow") >= 0
                || event.indexOf("failed") >= 0
                || event.indexOf("unknown") >= 0
                || event.indexOf("rejected") >= 0
                || event.indexOf("null") >= 0
                || event.indexOf("missing") >= 0
                || event.indexOf("repeated_render_texture_create") >= 0
                || event.indexOf("aggressive_gc") >= 0;
    }

    private static AtomicInteger getGlTextureLiveCounter() {
        if (glTextureLiveCounterResolved) {
            return glTextureLiveCounter;
        }
        synchronized (RamSaverDiag.class) {
            if (glTextureLiveCounterResolved) {
                return glTextureLiveCounter;
            }
            try {
                Class<?> glTextureClass = Class.forName("com.badlogic.gdx.graphics.GLTexture");
                Field liveField = glTextureClass.getDeclaredField("TEXTURES_LIVE");
                liveField.setAccessible(true);
                Object value = liveField.get(null);
                if (value instanceof AtomicInteger) {
                    glTextureLiveCounter = (AtomicInteger) value;
                }
            }
            catch (Throwable ignored) {
                glTextureLiveCounter = null;
            }
            glTextureLiveCounterResolved = true;
            return glTextureLiveCounter;
        }
    }

    private static String runtimeSuffix() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        return " thread=" + Thread.currentThread().getName()
                + " heapUsed=" + used
                + " heapTotal=" + runtime.totalMemory()
                + " heapMax=" + runtime.maxMemory();
    }

    private static String stack() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        StringBuilder builder = new StringBuilder();
        int written = 0;
        for (StackTraceElement element : trace) {
            String className = element.getClassName();
            if (className.equals(Thread.class.getName()) || className.equals(RamSaverDiag.class.getName())) {
                continue;
            }
            if (written > 0) {
                builder.append(" <- ");
            }
            builder.append(className)
                    .append('#')
                    .append(element.getMethodName())
                    .append(':')
                    .append(element.getLineNumber());
            written++;
            if (written >= STACK_DEPTH) {
                break;
            }
        }
        return builder.toString();
    }

    private static String formatMillis(long nanos) {
        long micros = nanos / 1000L;
        return (micros / 1000L) + "." + zeroPad3(micros % 1000L);
    }

    private static String zeroPad3(long value) {
        if (value < 10L) {
            return "00" + value;
        }
        if (value < 100L) {
            return "0" + value;
        }
        return Long.toString(value);
    }

    private static int readInt(String property, int defaultValue, int minValue, int maxValue) {
        String raw = System.getProperty(property);
        if (raw == null) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(minValue, Math.min(maxValue, value));
        }
        catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static boolean readBoolean(String property, boolean defaultValue) {
        String raw = System.getProperty(property);
        if (raw == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private static long readLong(String property, long defaultValue, long minValue, long maxValue) {
        String raw = System.getProperty(property);
        if (raw == null) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return Math.max(minValue, Math.min(maxValue, value));
        }
        catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}

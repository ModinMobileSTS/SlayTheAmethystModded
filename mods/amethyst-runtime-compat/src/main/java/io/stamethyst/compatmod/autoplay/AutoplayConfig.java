package io.stamethyst.compatmod.autoplay;

/**
 * Reads launcher-injected configuration for the bundled autoplay driver.
 *
 * <p>The autoplay driver is gated by {@code -Damethyst.debug.autoplay=true}. The
 * launcher only sets this property when the user explicitly opts into the special
 * autoplay launch mode (see the Gradle {@code stsStartAutoplay} task), so on
 * regular launches every driver hook short-circuits immediately.</p>
 */
public final class AutoplayConfig {
    /** Master switch — autoplay only runs when this property is {@code true}. */
    public static final String AUTOPLAY_ENABLED_PROP = "amethyst.debug.autoplay";
    /** Minimum delay between driver ticks. Avoids burning the engine with state changes. */
    public static final String AUTOPLAY_TICK_INTERVAL_MS_PROP =
        "amethyst.debug.autoplay.tick_interval_ms";
    /** Optional verbose logging toggle. */
    public static final String AUTOPLAY_DEBUG_PROP = "amethyst.debug.autoplay.debug";
    /** When true, normal autoplay waits until an agent PlayMonitor connects. */
    public static final String AUTOPLAY_WAIT_FOR_AGENT_PROP =
        "amethyst.autoplay.wait_for_agent";
    /** Optional delay before autoplay resolves visible choice screens. */
    public static final String AUTOPLAY_CHOICE_DELAY_MS_PROP =
        "amethyst.debug.autoplay.choice_delay_ms";
    /** Save handling mode: clear stale saves and start fresh, or resume the last run. */
    public static final String AUTOPLAY_SAVE_MODE_PROP = "amethyst.debug.autoplay.save_mode";
    /** Autoplay behavior mode: normal long-run smoke, or one configured combat room. */
    public static final String AUTOPLAY_MODE_PROP = "amethyst.debug.autoplay.mode";
    /** Properties file consumed by single-room mode. */
    public static final String AUTOPLAY_SINGLE_ROOM_SPEC_PROP =
        "amethyst.debug.autoplay.single_room_spec";
    /** When true, single-room configures the encounter but never plays cards or ends turns. */
    public static final String AUTOPLAY_SINGLE_ROOM_HOLD_PROP =
        "amethyst.debug.autoplay.single_room_hold";
    /**
     * When true, single-room runs in bench mode: plays all cards every turn, refills
     * energy to 99 and restores HP to max each turn so the run never ends prematurely.
     * Used by the perf-bench harness command.
     */
    public static final String AUTOPLAY_SINGLE_ROOM_BENCH_MODE_PROP =
        "amethyst.debug.autoplay.single_room_bench_mode";

    public static final String MODE_NORMAL = "normal";
    public static final String MODE_SINGLE_ROOM = "single_room";
    public static final String SAVE_MODE_FRESH = "fresh";
    public static final String SAVE_MODE_CONTINUE = "continue";

    private static final long DEFAULT_TICK_INTERVAL_MS = 250L;
    private static final long DEFAULT_CHOICE_DELAY_MS = 0L;
    private static final long MIN_TICK_INTERVAL_MS = 50L;
    private static final long MAX_TICK_INTERVAL_MS = 5000L;
    private static final long MAX_CHOICE_DELAY_MS = 30000L;

    private static final boolean ENABLED =
        readBoolean(AUTOPLAY_ENABLED_PROP, false);
    private static final boolean DEBUG_LOG_ENABLED =
        readBoolean(AUTOPLAY_DEBUG_PROP, false);
    private static final boolean WAIT_FOR_AGENT =
        readBoolean(AUTOPLAY_WAIT_FOR_AGENT_PROP, false);
    private static final String MODE =
        readMode(AUTOPLAY_MODE_PROP, MODE_NORMAL);
    private static final String SAVE_MODE =
        readSaveMode(AUTOPLAY_SAVE_MODE_PROP, SAVE_MODE_FRESH);
    private static final String SINGLE_ROOM_SPEC_PATH =
        readString(AUTOPLAY_SINGLE_ROOM_SPEC_PROP, "");
    private static final boolean SINGLE_ROOM_HOLD =
        readBoolean(AUTOPLAY_SINGLE_ROOM_HOLD_PROP, false);
    private static final boolean SINGLE_ROOM_BENCH_MODE =
        readBoolean(AUTOPLAY_SINGLE_ROOM_BENCH_MODE_PROP, false);
    private static final long TICK_INTERVAL_MS =
        clampLong(readLong(AUTOPLAY_TICK_INTERVAL_MS_PROP, DEFAULT_TICK_INTERVAL_MS),
            MIN_TICK_INTERVAL_MS, MAX_TICK_INTERVAL_MS);
    private static final long CHOICE_DELAY_MS =
        clampLong(readLong(AUTOPLAY_CHOICE_DELAY_MS_PROP, DEFAULT_CHOICE_DELAY_MS),
            0L, MAX_CHOICE_DELAY_MS);

    private AutoplayConfig() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static boolean isDebugLogEnabled() {
        return DEBUG_LOG_ENABLED;
    }

    public static boolean isWaitForAgentEnabled() {
        return WAIT_FOR_AGENT;
    }

    public static long getTickIntervalMs() {
        return TICK_INTERVAL_MS;
    }

    public static long getChoiceDelayMs() {
        return CHOICE_DELAY_MS;
    }

    public static String getMode() {
        return MODE;
    }

    public static String getSaveMode() {
        return SAVE_MODE;
    }

    public static boolean isSingleRoomMode() {
        return MODE_SINGLE_ROOM.equals(MODE);
    }

    public static String getSingleRoomSpecPath() {
        return SINGLE_ROOM_SPEC_PATH;
    }

    public static boolean isSingleRoomHoldEnabled() {
        return SINGLE_ROOM_HOLD;
    }

    public static boolean isSingleRoomBenchModeEnabled() {
        return SINGLE_ROOM_BENCH_MODE;
    }

    public static boolean shouldContinueLastSave() {
        return SAVE_MODE_CONTINUE.equals(SAVE_MODE);
    }

    private static boolean readBoolean(String key, boolean defaultValue) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return defaultValue;
        }
        configured = configured.trim();
        if (configured.length() == 0) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(configured) || "1".equals(configured)
            || "on".equalsIgnoreCase(configured) || "yes".equalsIgnoreCase(configured)) {
            return true;
        }
        if ("false".equalsIgnoreCase(configured) || "0".equals(configured)
            || "off".equalsIgnoreCase(configured) || "no".equalsIgnoreCase(configured)) {
            return false;
        }
        return defaultValue;
    }

    private static long readLong(String key, long defaultValue) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return defaultValue;
        }
        configured = configured.trim();
        if (configured.length() == 0) {
            return defaultValue;
        }
        try {
            return Long.parseLong(configured);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String readString(String key, String defaultValue) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return defaultValue;
        }
        configured = configured.trim();
        if (configured.length() == 0) {
            return defaultValue;
        }
        return configured;
    }

    private static String readMode(String key, String defaultValue) {
        String configured = readString(key, defaultValue)
            .trim()
            .toLowerCase(java.util.Locale.ROOT)
            .replace('-', '_');
        if (MODE_NORMAL.equals(configured) || "run".equals(configured)) {
            return MODE_NORMAL;
        }
        if (MODE_SINGLE_ROOM.equals(configured) || "single".equals(configured)
            || "room".equals(configured)) {
            return MODE_SINGLE_ROOM;
        }
        return defaultValue;
    }

    private static String readSaveMode(String key, String defaultValue) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return defaultValue;
        }
        configured = configured.trim().toLowerCase(java.util.Locale.ROOT);
        if (configured.length() == 0) {
            return defaultValue;
        }
        if (SAVE_MODE_FRESH.equals(configured) || "clear".equals(configured)
            || "reset".equals(configured) || "new".equals(configured)) {
            return SAVE_MODE_FRESH;
        }
        if (SAVE_MODE_CONTINUE.equals(configured) || "resume".equals(configured)) {
            return SAVE_MODE_CONTINUE;
        }
        return defaultValue;
    }

    private static long clampLong(long value, long min, long max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}

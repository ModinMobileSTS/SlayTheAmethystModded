package optispire;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.GLTexture;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Render-thread-only, scope-owned residency. No deadlines and no ownership of card/library objects. */
public final class RunTextureResidency {
    private static final long MIB = 1024L * 1024L;
    private static final long BUDGET_BYTES = property("ramsaver.run.budget_mb", 128L, 0L, 512L) * MIB;
    private static final long PRESSURE_BYTES = property("ramsaver.run.pressure_mb",
            Math.min(512L, Long.getLong("amethyst.gdx.gpu_guardian_soft_budget_bytes", 768L * MIB) / MIB),
            64L, 4096L) * MIB;
    private static final long PRESSURE_COOLDOWN_NANOS = 30_000_000_000L;
    private static final long PRESSURE_GRACE_NANOS = 5_000_000_000L;
    private static final long PRESSURE_SAMPLE_NANOS = 500_000_000L;
    private static final LinkedHashMap<String, Entry> wanted = new LinkedHashMap<>();
    private static long admittedBytes;
    private static boolean suspended;
    private static boolean constrained;
    private static long protectionBudget = PRESSURE_BYTES;
    private static long resumeAfterNanos;
    private static long pressureStarted = -1L;
    private static long nextPressureSample;

    private RunTextureResidency() { }

    public static boolean enabled() { return BUDGET_BYTES > 0L; }

    /** Caller supplies only the current player's assets, in hand/draw/deck priority order. */
    public static void replaceCandidates(Map<String, Long> candidates) {
        if (BUDGET_BYTES == 0L) {
            clear();
            return;
        }
        Iterator<Map.Entry<String, Entry>> old = wanted.entrySet().iterator();
        while (old.hasNext()) {
            Map.Entry<String, Entry> entry = old.next();
            if (!candidates.containsKey(entry.getKey())) {
                TextureDecodeService.cancel(entry.getKey(), TextureDecodeService.RUN);
                RamSaver.releaseRunTexturePin(entry.getKey());
                old.remove();
            }
        }
        LinkedHashMap<String, Entry> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, Long> candidate : candidates.entrySet()) {
            String key = candidate.getKey();
            if (key == null || !RamSaver.textureExists(key)) continue;
            Entry entry = wanted.get(key);
            if (entry == null) {
                entry = new Entry();
                Texture cached = RamSaver.getAsset(key, false);
                if (cached != null && cached.getTextureObjectHandle() != 0) {
                    entry.bytes = cached.getRamSaverEstimatedBytes();
                    entry.materialized = true;
                    entry.attempted = true;
                }
            }
            // Actual upload dimensions, once known, are more accurate than header/atlas estimates.
            if (!entry.materialized) entry.bytes = Math.max(0L, candidate.getValue());
            ordered.put(key, entry);
        }
        wanted.clear();
        wanted.putAll(ordered);
        admit();
    }

    private static void admit() {
        admittedBytes = 0L;
        for (Map.Entry<String, Entry> item : wanted.entrySet()) {
            Entry entry = item.getValue();
            boolean admitted = !suspended && entry.bytes > 0L
                    && entry.bytes <= Math.min(BUDGET_BYTES, protectionBudget) - admittedBytes;
            // One owner/charge per key: run candidates never also consume the generic hot budget.
            RamSaver.releaseRunTexturePin(item.getKey());
            entry.admitted = admitted;
            if (!admitted) TextureDecodeService.cancel(item.getKey(), TextureDecodeService.RUN);
            if (admitted) admittedBytes += entry.bytes;
        }
        RamSaver.setHotPinBudget(constrained ? 0L : protectionBudget - admittedBytes);
    }

    public static boolean isProtected(String key) {
        Entry entry = wanted.get(key);
        return entry != null && entry.admitted;
    }

    public static boolean allowsHotPin(String key) {
        return !constrained && !wanted.containsKey(key);
    }

    public static boolean isUnderPressure() { return constrained; }

    public static void onTextureLoaded(String key, Texture texture) {
        Entry entry = wanted.get(key);
        if (entry == null) return;
        long bytes = (long) texture.getWidth() * texture.getHeight() * 4L;
        if (texture.getTextureData().useMipMaps()) bytes += bytes / 3L;
        entry.bytes = bytes;
        entry.materialized = true;
        entry.attempted = true;
        admit();
    }

    public static void onTextureDisposed(String key) {
        Entry entry = wanted.get(key);
        if (entry != null) entry.attempted = false;
    }

    public static boolean hasPending(Collection<String> priority) {
        if (constrained) return false;
        if (priority == null) {
            for (Entry entry : wanted.values()) if (entry.admitted && !entry.attempted) return true;
        } else {
            for (String key : priority) {
                Entry entry = wanted.get(key);
                if (entry != null && entry.admitted && !entry.attempted) return true;
            }
        }
        return false;
    }

    /** At most one request, ready upload or synchronous fallback; the patch owns the frame budget. */
    public static void prewarmOne(Collection<String> priority) {
        if (constrained) return;
        Iterable<String> keys = priority == null ? wanted.keySet() : priority;
        for (String key : keys) {
            Entry entry = wanted.get(key);
            if (entry == null || !entry.admitted || entry.attempted) continue;
            try {
                entry.attempted = RamSaver.prewarmTexture(key, true, TextureDecodeService.RUN);
            } catch (RuntimeException error) {
                entry.attempted = true;
                RamSaverDiag.logRepeat("run_texture_prewarm_failed", key, error.toString());
            }
            return;
        }
    }

    /** Runs even on menu screens and when run protection is disabled. */
    public static void samplePressure() {
        long now = System.nanoTime();
        if (now < nextPressureSample) return;
        nextPressureSample = now + PRESSURE_SAMPLE_NANOS;
        Runtime runtime = Runtime.getRuntime();
        updatePressure(GLTexture.getEstimatedNativeBytes() + GLFrameBuffer.getEstimatedNativeBytes(),
                runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), now);
    }

    /** Shared policy for run protection, hot pins and speculative prewarm. No GL deletion here. */
    public static void updatePressure(long trackedBytes, long heapUsed, long heapMax, long now) {
        // Reservations include CPU/native staging, which is absent from GL counters. Count once here,
        // not as reclaimable RamSaver GL credit or as a second run/hot allocation.
        long staging = TextureDecodeService.reservedBytes();
        trackedBytes = trackedBytes > Long.MAX_VALUE - staging ? Long.MAX_VALUE : trackedBytes + staging;
        boolean heapPressure = heapMax > 0L && heapUsed >= heapMax - heapMax / 10L;
        boolean pressure = trackedBytes >= PRESSURE_BYTES || heapPressure;
        if (pressure) {
            resumeAfterNanos = now + PRESSURE_COOLDOWN_NANOS;
            if (pressureStarted < 0L) pressureStarted = now;
            boolean severe = heapPressure || trackedBytes >= PRESSURE_BYTES + PRESSURE_BYTES / 4L
                    || now - pressureStarted >= PRESSURE_GRACE_NANOS;
            boolean changed = !constrained || (severe && !suspended);
            constrained = true;
            TextureDecodeService.cancelAll();
            suspended |= severe;
            // Subtract only bytes actually present in the aggregate GL counters, not RGBA estimates
            // (RGB565, mipmaps, downscaling and context loss can all make those estimates differ).
            long externalBytes = Math.max(0L, trackedBytes - RamSaver.getLoadedTextureTrackedBytes());
            long available = suspended ? 0L : Math.max(0L, PRESSURE_BYTES * 4L / 5L - externalBytes);
            // No expansion during the cooldown: reclaiming our own textures must not repin them.
            protectionBudget = Math.min(protectionBudget, available);
            admit();
            RamSaver.requestPressureAging();
            if (changed && RamSaverDiag.enabled()) {
                RamSaverDiag.log("texture_residency_pressure", "trackedBytes=" + trackedBytes
                        + " heapUsed=" + heapUsed + " severe=" + suspended
                        + " protectionBudget=" + protectionBudget + " protectedBytes=" + admittedBytes);
            }
        } else if (constrained && now >= resumeAfterNanos && trackedBytes < PRESSURE_BYTES * 4L / 5L
                && (heapMax <= 0L || heapUsed < heapMax - heapMax / 5L)) {
            suspended = false;
            constrained = false;
            pressureStarted = -1L;
            long externalBytes = Math.max(0L, trackedBytes - RamSaver.getLoadedTextureTrackedBytes());
            protectionBudget = Math.max(0L, PRESSURE_BYTES - externalBytes);
            admit();
            RamSaverDiag.log("run_residency_resumed", "trackedBytes=" + trackedBytes + " protectedBytes=" + admittedBytes);
        } else if (!pressure) {
            pressureStarted = -1L;
            if (!constrained) {
                long externalBytes = Math.max(0L, trackedBytes - RamSaver.getLoadedTextureTrackedBytes());
                long available = Math.max(0L, PRESSURE_BYTES - externalBytes);
                if (available != protectionBudget) {
                    protectionBudget = available;
                    admit();
                }
            }
        }
    }

    public static void clear() {
        TextureDecodeService.cancelScope(TextureDecodeService.RUN);
        for (String key : wanted.keySet()) RamSaver.releaseRunTexturePin(key);
        wanted.clear();
        admittedBytes = 0L;
        // A new run/menu transition must not reset process-wide memory pressure.
        RamSaver.setHotPinBudget(constrained ? 0L : protectionBudget);
    }

    private static long property(String name, long fallback, long min, long max) {
        try { return Math.max(min, Math.min(max, Long.parseLong(System.getProperty(name, "" + fallback)))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static final class Entry {
        long bytes;
        boolean admitted;
        boolean materialized;
        boolean attempted;
    }
}

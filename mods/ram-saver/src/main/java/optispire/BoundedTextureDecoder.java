package optispire;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.concurrent.Executor;

/** No GL or RamSaver references. Identity is the generation token; canceled running work keeps its charge.
 * The executor must enqueue work, not run it inline on the caller. Sources own only CPU data, and their
 * cancel/close methods must be nonblocking (production uses a volatile flag / drops a byte array).
 */
public final class BoundedTextureDecoder<P extends AutoCloseable> {
    public interface Source<P> {
        long probe() throws Exception;
        P decode() throws Exception;
        default void cancel() { }
    }
    public enum State { MISSING, PENDING, READY, FALLBACK }
    private final Executor executor;
    private final long capacity;
    private final int limit;
    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private long reserved;
    private int outstanding;
    private boolean scheduled, closed;

    private final class Job {
        final Source<P> source;
        long bytes;
        int scopes;
        boolean canceled, active, failed;
        P pixels;
        Job(Source<P> source) { this.source = source; }
    }

    public BoundedTextureDecoder(Executor executor, long capacity, int limit) {
        if (capacity <= 0 || limit <= 0) throw new IllegalArgumentException("Positive bounds required");
        this.executor = executor;
        this.capacity = capacity;
        this.limit = limit;
    }

    public synchronized State request(String key, Source<P> source) {
        return request(key, source, 1);
    }
    public synchronized State request(String key, Source<P> source, int scope) {
        Job job = jobs.get(key);
        if (job != null) { job.scopes |= scope; schedule(); return state(job); }
        if (closed) return State.FALLBACK;
        if (outstanding >= limit) return State.MISSING;
        job = new Job(source);
        job.scopes = scope;
        jobs.put(key, job);
        outstanding++;
        schedule();
        return state(jobs.get(key));
    }
    public synchronized State state(String key) { return state(jobs.get(key)); }
    private State state(Job job) {
        return job == null ? State.MISSING : job.failed ? State.FALLBACK
                : job.pixels != null ? State.READY : State.PENDING;
    }

    /** Caller must close the lease after upload (including failure); capacity remains charged until then. */
    public synchronized Lease take(String key) {
        Job job = jobs.get(key);
        if (job == null || job.pixels == null) return null;
        jobs.remove(key);
        P pixels = job.pixels;
        job.pixels = null;
        return new Lease(pixels, job.bytes);
    }
    public final class Lease implements AutoCloseable {
        public final P pixels;
        private final long bytes;
        private boolean released;
        private Lease(P pixels, long bytes) { this.pixels = pixels; this.bytes = bytes; }
        public void close() {
            synchronized (BoundedTextureDecoder.this) {
                if (released) return;
                released = true;
                release(pixels);
                reserved -= bytes;
                outstanding--;
                schedule();
            }
        }
    }

    public synchronized void cancel(String key) {
        Job job = jobs.remove(key);
        if (job == null) return;
        job.canceled = true;
        try { job.source.cancel(); } catch (RuntimeException ignored) { }
        if (!job.active) {
            release(job.pixels);
            if (job.pixels != null) reserved -= job.bytes;
            job.pixels = null;
            outstanding--;
        }
        schedule();
    }
    public synchronized void cancelAll() {
        for (String key : new ArrayList<>(jobs.keySet())) cancel(key);
    }
    public synchronized void cancelScope(int scope) {
        for (String key : new ArrayList<>(jobs.keySet())) {
            cancel(key, scope);
        }
    }
    public synchronized void cancel(String key, int scope) {
        Job job = jobs.get(key);
        if (job == null) return;
        job.scopes &= ~scope;
        if (job.scopes == 0) cancel(key);
    }
    public synchronized void shutdown() { closed = true; cancelAll(); }
    public synchronized long reservedBytes() { return reserved; }
    public synchronized int pendingCount() { return outstanding; }

    private void schedule() {
        if (scheduled || closed || next() == null) return;
        scheduled = true;
        try { executor.execute(this::pump); }
        catch (RuntimeException error) {
            scheduled = false;
            for (Job job : jobs.values()) if (job.pixels == null) job.failed = true;
        }
    }

    private Job next() {
        for (Job job : jobs.values()) {
            if (!job.failed && !job.active && job.pixels == null
                    && (job.bytes == 0 || job.bytes > capacity || job.bytes <= capacity - reserved)) return job;
        }
        return null;
    }

    private void pump() {
        try {
            while (true) {
                Job job;
                synchronized (this) {
                    job = next();
                    if (job == null || closed) return;
                    job.active = true;
                }
                P pixels = null;
                boolean charged = false;
                try {
                    long bytes = job.bytes == 0 ? job.source.probe() : job.bytes;
                    synchronized (this) {
                        job.bytes = bytes;
                        if (job.canceled || closed) continue;
                        if (job.bytes <= 0 || job.bytes > capacity) { job.failed = true; continue; }
                        if (job.bytes > capacity - reserved) continue;
                        reserved += job.bytes;
                        charged = true;
                    }
                    pixels = job.source.decode();
                    if (pixels == null) throw new IllegalStateException("Decoder returned no pixels");
                } catch (Throwable error) {
                    synchronized (this) { job.failed = true; }
                } finally {
                    synchronized (this) {
                        if (!job.canceled && !closed && !job.failed && pixels != null) {
                            job.pixels = pixels;
                        } else {
                            release(pixels);
                            if (charged) reserved -= job.bytes;
                        }
                        job.active = false;
                        if (job.canceled) outstanding--;
                    }
                }
            }
        } finally {
            synchronized (this) { scheduled = false; schedule(); }
        }
    }

    private static void release(AutoCloseable pixels) {
        if (pixels != null) try { pixels.close(); } catch (Exception ignored) { }
    }
}

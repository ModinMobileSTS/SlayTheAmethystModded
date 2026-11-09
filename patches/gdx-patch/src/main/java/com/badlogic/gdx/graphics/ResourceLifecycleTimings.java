package com.badlogic.gdx.graphics;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in, lossless-until-I/O-failure CPU lifecycle trace. No GL waits, stacks or per-event objects. */
public final class ResourceLifecycleTimings {
    public static final String PROPERTY = "amethyst.gdx.resource_timings";
    public static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final Recorder RECORDER = ENABLED ? new Recorder(
            new File(System.getProperty(PROPERTY + ".dir", "resource_timings")), 4096) : null;

    static {
        if (RECORDER != null) {
            RECORDER.start();
            Runtime.getRuntime().addShutdownHook(new Thread(RECORDER::close, "Resource-Timings-Shutdown"));
        }
    }

    private ResourceLifecycleTimings() { }

    public static long begin(String kind, String action, String key, String reason) {
        return RECORDER == null ? 0L : RECORDER.begin(kind, action, key, reason);
    }

    public static void end(long token, int handle, long bytes, boolean success) {
        if (RECORDER != null && token != 0L) RECORDER.end(token, handle, bytes, success);
    }

    /** Reuse the upload path's existing binding query rather than issuing a diagnostic GL query. */
    public static void uploaded(int handle, long bytes) {
        if (RECORDER == null) return;
        Stack stack = RECORDER.stacks.get();
        if (stack.depth == 0) return;
        Span span = stack.spans.get(stack.depth - 1);
        span.handle = handle; span.bytes = bytes;
    }

    /** Reused per-thread nesting state; resource owners and GL objects never enter the queue. */
    private static final class Span {
        long id, parent, started, waitBefore, frame;
        long bytes;
        int handle;
        String kind, action, key, reason;
    }

    private static final class Stack {
        final ArrayList<Span> spans = new ArrayList<>();
        int depth;
        long diagnosticWait;
    }

    private static final class Event {
        long id, parent, started, elapsed, nestedWait, enqueueWait, frame, threadId, bytes;
        int handle;
        boolean success;
        String kind, action, key, reason, thread;

        void copyFrom(Event e) {
            id = e.id; parent = e.parent; started = e.started; elapsed = e.elapsed;
            nestedWait = e.nestedWait; enqueueWait = e.enqueueWait; frame = e.frame;
            threadId = e.threadId; bytes = e.bytes; handle = e.handle; success = e.success;
            kind = e.kind; action = e.action; key = e.key; reason = e.reason; thread = e.thread;
        }

        void clear() { kind = action = key = reason = thread = null; }
    }

    private static final class Totals {
        long count, failures, blocking, max, enqueueWait;
    }

    /** Package-private so queue saturation, shutdown and write failures can be tested deterministically. */
    static final class Recorder {
        final File directory;
        final Event[] events;
        final AtomicLong nextId = new AtomicLong();
        final AtomicInteger active = new AtomicInteger();
        final ThreadLocal<Stack> stacks = ThreadLocal.withInitial(Stack::new);
        final long origin = System.nanoTime(), epochMs = System.currentTimeMillis();
        final Map<String, Totals> totals = new LinkedHashMap<>(); // writer thread only
        final Thread writer;
        int head, size, highWater;
        long accepted, written, serialized, failed, totalWait, maxWait;
        volatile boolean closing, finished;
        volatile String error;

        Recorder(File directory, int capacity) {
            if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
            this.directory = directory;
            events = new Event[capacity];
            for (int i = 0; i < capacity; i++) events[i] = new Event();
            writer = new Thread(this::writeLoop, "Resource-Timings-Writer");
            writer.setDaemon(true);
        }

        void start() { writer.start(); }

        long begin(String kind, String action, String key, String reason) {
            if (closing) return 0L;
            active.incrementAndGet();
            if (closing) { active.decrementAndGet(); return 0L; }
            Stack stack = stacks.get();
            if (stack.depth == stack.spans.size()) stack.spans.add(new Span());
            Span span = stack.spans.get(stack.depth);
            span.id = nextId.incrementAndGet();
            span.parent = stack.depth == 0 ? 0L : stack.spans.get(stack.depth - 1).id;
            span.kind = kind; span.action = action; span.key = key; span.reason = reason;
            span.handle = 0; span.bytes = 0L;
            span.waitBefore = stack.diagnosticWait;
            span.frame = com.badlogic.gdx.Gdx.graphics == null ? -1L : com.badlogic.gdx.Gdx.graphics.getFrameId();
            stack.depth++;
            span.started = System.nanoTime();
            return span.id;
        }

        void end(long token, int handle, long bytes, boolean success) {
            if (token == 0L) return;
            long ended = System.nanoTime();
            Stack stack = stacks.get();
            if (stack.depth == 0 || stack.spans.get(stack.depth - 1).id != token)
                throw new IllegalStateException("Unbalanced resource timing scope");
            Span span = stack.spans.get(--stack.depth);
            long waitStarted = 0L;
            boolean interrupted = false;
            synchronized (this) {
                while (size == events.length && error == null) {
                    if (waitStarted == 0L) waitStarted = System.nanoTime();
                    try { wait(); } catch (InterruptedException ignored) { interrupted = true; }
                }
                long waited = waitStarted == 0L ? 0L : Math.max(0L, System.nanoTime() - waitStarted);
                if (error != null) {
                    failed++;
                } else {
                    Event e = events[(head + size) % events.length];
                    e.id = span.id; e.parent = span.parent; e.started = span.started - origin;
                    e.elapsed = Math.max(0L, ended - span.started);
                    e.nestedWait = Math.max(0L, stack.diagnosticWait - span.waitBefore);
                    e.enqueueWait = waited; e.handle = handle < 0 ? span.handle : handle;
                    e.bytes = Math.max(0L, bytes < 0L ? span.bytes : bytes);
                    e.kind = span.kind; e.action = span.action; e.key = span.key; e.reason = span.reason;
                    e.success = success;
                    e.thread = Thread.currentThread().getName();
                    e.threadId = Thread.currentThread().getId();
                    e.frame = span.frame;
                    size++; accepted++; totalWait += waited; maxWait = Math.max(maxWait, waited);
                    highWater = Math.max(highWater, size);
                    notifyAll();
                }
                stack.diagnosticWait += waited;
                active.decrementAndGet();
                notifyAll();
            }
            span.kind = span.action = span.key = span.reason = null;
            if (interrupted) Thread.currentThread().interrupt();
        }

        void close() {
            synchronized (this) { closing = true; notifyAll(); }
            boolean interrupted = false;
            while (writer.isAlive()) {
                try { writer.join(); } catch (InterruptedException ignored) { interrupted = true; }
            }
            if (error != null) {
                try { writeSummary(false); } catch (IOException ignored) { }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        private void writeLoop() {
            Event current = new Event();
            StringBuilder json = new StringBuilder(1024);
            long lastFlush = 0L;
            String acknowledged = null;
            String requested = null;
            long flushTarget = 0L;
            File request = new File(directory, "flush.request");
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
                rotate("events.jsonl"); rotate("summary.json");
                Files.deleteIfExists(new File(directory, "flush.ack").toPath());
                try (BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(new File(directory, "events.jsonl")), StandardCharsets.UTF_8), 65536)) {
                    writeHeader(output, json);
                    output.flush();
                    writeSummary(false);
                    for (;;) {
                        boolean hasEvent;
                        synchronized (this) {
                            if (size == 0 && (!closing || active.get() != 0)) wait(100L);
                            hasEvent = size != 0;
                            if (hasEvent) {
                                current.copyFrom(events[head]); events[head].clear();
                                head = (head + 1) % events.length; size--; notifyAll();
                            }
                        }
                        if (hasEvent) {
                            writeEvent(output, json, current);
                            serialized++;
                            String group = current.kind + "/" + current.action;
                            Totals t = totals.get(group);
                            if (t == null) { t = new Totals(); totals.put(group, t); }
                            long blocking = Math.max(0L, current.elapsed - current.nestedWait);
                            t.count++; if (!current.success) t.failures++;
                            t.blocking += blocking; t.max = Math.max(t.max, blocking); t.enqueueWait += current.enqueueWait;
                            current.clear();
                        }
                        long now = System.nanoTime();
                        boolean empty; synchronized (this) { empty = size == 0 && active.get() == 0; }
                        if (now - lastFlush >= 250000000L && request.isFile()) {
                            String next = new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8);
                            if (!next.equals(requested)) {
                                requested = next;
                                synchronized (this) { flushTarget = accepted; }
                            }
                        }
                        if (now - lastFlush >= 250000000L || (closing && empty)) {
                            output.flush();
                            written = serialized;
                            writeSummary(closing && empty);
                            lastFlush = now;
                            if (requested != null && !requested.equals(acknowledged) && written >= flushTarget) {
                                String summary = new String(Files.readAllBytes(new File(directory, "summary.json").toPath()), StandardCharsets.UTF_8);
                                atomicText("flush.ack", requested + "\n" + new File(directory, "events.jsonl").length() + "\n" + summary);
                                acknowledged = requested;
                            }
                        }
                        if (closing && empty) break;
                    }
                }
            } catch (Throwable failure) {
                synchronized (this) {
                    error = failure.getClass().getName() + ": " + failure.getMessage();
                    failed += accepted - written;
                    size = 0;
                    for (Event event : events) event.clear();
                    notifyAll();
                }
                System.err.println("[resource-timings] recording_incomplete error=" + error);
                try { writeSummary(false); } catch (IOException ignored) { }
            } finally { finished = true; }
        }

        private void rotate(String name) throws IOException {
            File file = new File(directory, name);
            if (file.exists()) Files.move(file.toPath(), new File(directory, "previous-" + name).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        private void writeHeader(BufferedWriter output, StringBuilder b) throws IOException {
            b.setLength(0);
            b.append("{\"type\":\"session\",\"schema\":1,\"epoch_ms\":").append(epochMs)
                    .append(",\"clock\":\"monotonic_ns\",\"measurement\":\"calling_thread_wall_time\",")
                    .append("\"overflow_policy\":\"wait\",\"gpu_completion_wait\":false}\n");
            output.write(b.toString());
        }

        private void writeEvent(BufferedWriter output, StringBuilder b, Event e) throws IOException {
            b.setLength(0);
            b.append("{\"type\":\"event\",\"id\":").append(e.id).append(",\"parent_id\":").append(e.parent)
                    .append(",\"start_ns\":").append(e.started).append(",\"elapsed_ns\":").append(e.elapsed)
                    .append(",\"blocking_ns\":").append(Math.max(0L, e.elapsed - e.nestedWait))
                    .append(",\"nested_diagnostic_wait_ns\":").append(e.nestedWait)
                    .append(",\"enqueue_wait_ns\":").append(e.enqueueWait).append(",\"frame\":").append(e.frame)
                    .append(",\"thread_id\":").append(e.threadId).append(",\"handle\":").append(e.handle)
                    .append(",\"estimated_bytes\":").append(e.bytes).append(",\"success\":").append(e.success);
            field(b, "kind", e.kind); field(b, "action", e.action); field(b, "key", e.key);
            field(b, "reason", e.reason); field(b, "thread", e.thread);
            b.append("}\n"); output.write(b.toString());
        }

        private void writeSummary(boolean complete) throws IOException {
            StringBuilder b = new StringBuilder(1024);
            b.append("{\"schema\":1,\"epoch_ms\":").append(epochMs)
                    .append(",\"closed_cleanly\":").append(complete && error == null);
            synchronized (this) {
                b.append(",\"submitted\":").append(accepted).append(",\"written\":").append(written)
                        .append(",\"pending\":").append(size).append(",\"unsaved\":").append(failed)
                        .append(",\"capacity\":").append(events.length).append(",\"queue_high_water\":").append(highWater)
                        .append(",\"active_spans\":").append(active.get())
                        .append(",\"enqueue_wait_ns\":").append(totalWait).append(",\"max_enqueue_wait_ns\":").append(maxWait);
            }
            field(b, "error", error);
            b.append(",\"groups\":[");
            boolean comma = false;
            for (Map.Entry<String, Totals> group : totals.entrySet()) {
                if (comma) b.append(','); comma = true;
                Totals t = group.getValue(); b.append('{'); quote(b, "group"); b.append(':'); quote(b, group.getKey());
                b.append(",\"count\":").append(t.count).append(",\"failures\":").append(t.failures)
                        .append(",\"inclusive_blocking_ns\":").append(t.blocking).append(",\"max_blocking_ns\":").append(t.max)
                        .append(",\"enqueue_wait_ns\":").append(t.enqueueWait).append('}');
            }
            b.append("]}\n"); atomicText("summary.json", b.toString());
        }

        private void atomicText(String name, String text) throws IOException {
            File staging = new File(directory, name + ".tmp");
            Files.write(staging.toPath(), text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(staging.toPath(), new File(directory, name).toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staging.toPath(), new File(directory, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void field(StringBuilder b, String name, String value) {
        b.append(','); quote(b, name); b.append(':');
        if (value == null) b.append("null"); else quote(b, value);
    }

    private static void quote(StringBuilder b, String value) {
        b.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 32 || Character.isSurrogate(c)) {
                b.append("\\u");
                for (int shift = 12; shift >= 0; shift -= 4) b.append("0123456789abcdef".charAt((c >>> shift) & 15));
            } else b.append(c);
        }
        b.append('"');
    }
}

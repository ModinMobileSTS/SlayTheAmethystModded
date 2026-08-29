package io.stamethyst.frameprobe;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Off-render-thread JSONL writer.
 *
 * <p>The render thread enqueues pre-formatted strings via {@link #enqueue};
 * a single background daemon drains the queue and does all disk I/O.
 * This keeps disk latency completely off the render thread.
 *
 * <p>Output file: {@code <stsRoot>/frame-probe-incidents.jsonl}.
 * A new file is created on each session (previous one is rotated to
 * {@code frame-probe-incidents.prev.jsonl}).
 */
public final class IncidentWriter {

    private static final int QUEUE_CAPACITY = 2048;

    private final ArrayBlockingQueue<String> queue =
        new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private final File outFile;
    private volatile boolean started;

    public IncidentWriter(File stsRoot) {
        this.outFile = new File(stsRoot, "frame-probe-incidents.jsonl");
    }

    /** Must be called once from the game thread before the first enqueue. */
    public void start() {
        if (started) return;
        started = true;
        rotate();
        Thread t = new Thread(this::writerLoop, "STS-FrameProbe-Writer");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Enqueue one JSONL line. Called from the render thread.
     * If the queue is full the oldest entry is dropped silently to avoid
     * blocking the render thread.
     */
    public void enqueue(String line) {
        if (!queue.offer(line)) {
            queue.poll(); // drop oldest
            queue.offer(line);
        }
    }

    private void rotate() {
        if (outFile.exists()) {
            File prev = new File(outFile.getParent(), "frame-probe-incidents.prev.jsonl");
            //noinspection ResultOfMethodCallIgnored
            outFile.renameTo(prev);
        }
    }

    private void writerLoop() {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outFile, true), 64 * 1024)) {
            while (true) {
                String line;
                try {
                    line = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                try {
                    bw.write(line);
                    bw.newLine();
                    // Flush every 64 entries or on each slow-frame burst.
                    if (queue.isEmpty()) bw.flush();
                } catch (IOException ignored) {
                    // Disk errors are non-fatal.
                }
            }
        } catch (IOException ignored) {}
    }
}

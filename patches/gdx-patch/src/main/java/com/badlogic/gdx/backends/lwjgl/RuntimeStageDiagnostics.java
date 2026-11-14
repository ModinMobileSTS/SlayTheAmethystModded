package com.badlogic.gdx.backends.lwjgl;

import java.lang.management.ManagementFactory;
import java.util.UUID;
import org.lwjgl.glfw.CallbackBridge;

/** Observation only. Never queries OpenAL or attempts to cancel/re-enter a stalled operation. */
final class RuntimeStageDiagnostics {
    private static final String SESSION = UUID.randomUUID().toString();
    private static final String PROCESS = processIdentity();
    private static volatile Stage current;
    private static boolean started;

    private static String processIdentity() {
        try {
            return ManagementFactory.getRuntimeMXBean().getName() + " processStartMs="
                + ManagementFactory.getRuntimeMXBean().getStartTime();
        }
        catch (Throwable ignored) { return "unknown"; }
    }

    static final class Stage implements AutoCloseable {
        final String name;
        final long startedNs = System.nanoTime();
        final Thread owner = Thread.currentThread();
        final Stage previous;

        Stage(String name, Stage previous) {
            this.name = name;
            this.previous = previous;
        }

        @Override public void close() {
            log("end", this, (System.nanoTime() - startedNs) / 1000000);
            current = previous;
        }
    }

    static Stage begin(String name) {
        startObserver(Thread.currentThread());
        Stage stage = new Stage(name, current);
        current = stage;
        log("begin", stage, 0);
        return stage;
    }

    private static void log(String event, Stage stage, long elapsedMs) {
        System.out.println("[runtime-stage] event=" + event + " process=" + PROCESS + " session=" + SESSION
            + " stage=" + stage.name + " thread=" + stage.owner.getName() + ":" + stage.owner.getId()
            + " elapsedMs=" + elapsedMs);
    }

    static boolean isStalled(long elapsedMs, boolean active) {
        return active && elapsedMs >= 5000;
    }

    private static synchronized void startObserver(final Thread owner) {
        if (started) return;
        started = true;
        Thread observer = new Thread(new Runnable() {
            @Override public void run() {
                Stage reported = null;
                long nativeSnapshot = 0, nativeSince = System.nanoTime(), nativeReported = -1;
                boolean nativeAvailable = true;
                while (owner.isAlive()) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
                    long now = System.nanoTime();
                    Stage stage = current;
                    if (stage != null && stage != reported && isStalled((now - stage.startedNs) / 1000000, true)) {
                        reported = stage;
                        log("stalled_observation", stage, (now - stage.startedNs) / 1000000);
                        dumpJavaStack(owner);
                    }
                    if (!nativeAvailable) continue;
                    try {
                        // Atomic snapshot only: no OpenAL/queue/recovery mutex is touched here.
                        long value = CallbackBridge.nativeGetAudioOperationSnapshot();
                        if (value != nativeSnapshot) {
                            nativeSnapshot = value;
                            nativeSince = now;
                        }
                        if (value != nativeReported && isStalled((now - nativeSince) / 1000000, (value & 255) != 0)) {
                            nativeReported = value;
                            System.out.println("[runtime-stage] event=native_audio_stalled_observation process=" + PROCESS
                                + " session=" + SESSION + " stage=" + (value & 255) + " snapshot=" + value
                                + " thread=" + owner.getName() + ":" + owner.getId()
                                + " elapsedMs=" + ((now - nativeSince) / 1000000));
                            dumpJavaStack(owner);
                        }
                    } catch (Throwable unavailable) { nativeAvailable = false; }
                }
            }
        }, "Runtime stage observer");
        observer.setDaemon(true);
        observer.start();
    }

    private static void dumpJavaStack(Thread owner) {
        System.out.println("[runtime-stage] Java stack only; JNI frames do not describe native lock ownership.");
        for (StackTraceElement frame : owner.getStackTrace()) System.out.println("[runtime-stage]   at " + frame);
    }
}

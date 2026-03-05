package io.stamethyst.bridge;

import java.util.concurrent.atomic.AtomicBoolean;

final class BootBridgeReporter {
    private static final int MAX_MESSAGE_LENGTH = 300;

    private final BootBridgeEventSink sink;
    private final AtomicBoolean readySent = new AtomicBoolean(false);
    private final AtomicBoolean failSent = new AtomicBoolean(false);
    private final AtomicBoolean consoleReadyHint = new AtomicBoolean(false);
    private final Object progressLock = new Object();

    private int lastProgress = 0;

    BootBridgeReporter(BootBridgeEventSink sink) {
        this.sink = sink;
    }

    void phase(int progress, String message) {
        if (failSent.get()) {
            return;
        }
        int bounded = clamp(progress, 0, 100);
        synchronized (progressLock) {
            if (bounded < lastProgress) {
                bounded = lastProgress;
            }
            lastProgress = bounded;
        }
        sink.write("PHASE", bounded, sanitizeMessage(message));
    }

    void splash(String message) {
        if (failSent.get()) {
            return;
        }
        sink.write("SPLASH", 94, sanitizeMessage(message));
    }

    void ready(String message) {
        if (readySent.getAndSet(true) || failSent.get()) {
            return;
        }
        synchronized (progressLock) {
            lastProgress = Math.max(lastProgress, 100);
        }
        sink.write("READY", 100, sanitizeMessage(message));
    }

    void fail(String message) {
        if (failSent.getAndSet(true)) {
            return;
        }
        sink.write("FAIL", -1, sanitizeMessage(message));
    }

    void markConsoleReadyHint() {
        consoleReadyHint.set(true);
    }

    boolean hasConsoleReadyHint() {
        return consoleReadyHint.get();
    }

    boolean isReadySent() {
        return readySent.get();
    }

    boolean isFailSent() {
        return failSent.get();
    }

    String summarizeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        StringBuilder out = new StringBuilder(128);
        out.append(throwable.getClass().getSimpleName());
        String message = throwable.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            out.append(": ").append(message.trim());
        }
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            out.append(" | cause=").append(cause.getClass().getSimpleName());
            String causeMessage = cause.getMessage();
            if (causeMessage != null && !causeMessage.trim().isEmpty()) {
                out.append(": ").append(causeMessage.trim());
            }
        }
        return sanitizeMessage(out.toString());
    }

    static String sanitizeMessage(String message) {
        if (message == null) {
            return "";
        }
        String value = message.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .trim();
        return value.length() > MAX_MESSAGE_LENGTH ? value.substring(0, MAX_MESSAGE_LENGTH) : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

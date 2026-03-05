package io.stamethyst.bridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class BootBridgeEventSink {
    private static final String PROP_EVENTS = "amethyst.bridge.events";

    private final Object lock = new Object();
    private final File eventsFile;

    private BootBridgeEventSink(File eventsFile) {
        this.eventsFile = eventsFile;
    }

    static BootBridgeEventSink fromSystemProperty() {
        String path = System.getProperty(PROP_EVENTS, "").trim();
        if (path.isEmpty()) {
            return new BootBridgeEventSink(null);
        }

        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream ignored = new FileOutputStream(file, false)) {
            // Truncate existing events.
        } catch (Throwable ignored) {
        }
        return new BootBridgeEventSink(file);
    }

    void write(String type, int progress, String message) {
        if (eventsFile == null) {
            return;
        }
        String safeType = type == null ? "UNKNOWN" : type.trim();
        if (safeType.isEmpty()) {
            safeType = "UNKNOWN";
        }
        String safeMessage = message == null ? "" : message;
        byte[] bytes = (safeType + "\t" + progress + "\t" + safeMessage + "\n").getBytes(StandardCharsets.UTF_8);

        synchronized (lock) {
            try (FileOutputStream output = new FileOutputStream(eventsFile, true)) {
                output.write(bytes);
                output.flush();
            } catch (IOException ignored) {
            }
        }
    }
}

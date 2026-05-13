package io.stamethyst.compatmod;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ExpectedGameExitMarker {
    private static final String MARKER_PATH_PROP = "amethyst.expected_exit_marker";
    private static final Object LOCK = new Object();

    private ExpectedGameExitMarker() {
    }

    public static void mark(String source) {
        String path = System.getProperty(MARKER_PATH_PROP, "").trim();
        if (path.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        String safeSource = sanitizeSource(source);
        String content = "timestampMs=" + now + "\nsource=" + safeSource + "\n";
        synchronized (LOCK) {
            File markerFile = new File(path);
            File parent = markerFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return;
            }
            try (FileOutputStream output = new FileOutputStream(markerFile, false)) {
                output.write(content.getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.getFD().sync();
            } catch (IOException ignored) {
            } catch (Throwable ignored) {
            }
        }
    }

    private static String sanitizeSource(String source) {
        if (source == null || source.trim().isEmpty()) {
            return "unknown";
        }
        StringBuilder builder = new StringBuilder();
        String trimmed = source.trim();
        for (int i = 0; i < trimmed.length() && builder.length() < 64; i++) {
            char ch = trimmed.charAt(i);
            if ((ch >= 'a' && ch <= 'z') ||
                    (ch >= 'A' && ch <= 'Z') ||
                    (ch >= '0' && ch <= '9') ||
                    ch == '_' ||
                    ch == '-') {
                builder.append(ch);
            }
        }
        return builder.length() == 0 ? "unknown" : builder.toString();
    }
}

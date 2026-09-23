package io.stamethyst.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class MtsModFileListOverride {
    public static final String PROPERTY_NAME = "amethyst.mts.mod_file_list";

    /**
     * Optional path where the list actually handed to ModTheSpire is recorded.
     *
     * This is the authoritative record of which jars MTS loaded: it is written by the same call that
     * produces the array passed to {@code Loader.runMods}. The AI patch smoke test reads it back to
     * prove the run really used the mod set it asked for, instead of trusting that nothing else
     * overwrote the list in the meantime.
     */
    public static final String AUDIT_PROPERTY_NAME = "amethyst.mts.mod_file_list.audit";

    private MtsModFileListOverride() {
    }

    public static File[] resolve(File[] fallback) {
        File[] resolved = resolveInternal(fallback);
        writeAudit(resolved);
        return resolved;
    }

    private static File[] resolveInternal(File[] fallback) {
        String rawListPath = System.getProperty(PROPERTY_NAME);
        if (rawListPath == null || rawListPath.trim().isEmpty()) {
            return fallbackOrEmpty(fallback);
        }

        File listFile = new File(rawListPath.trim());
        if (!listFile.isFile()) {
            log("MTS mod file list not found: " + listFile.getAbsolutePath());
            return fallbackOrEmpty(fallback);
        }

        List<File> files = new ArrayList<File>();
        LinkedHashSet<String> seenPaths = new LinkedHashSet<String>();
        try {
            List<String> lines = Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8);
            for (String rawLine : lines) {
                String path = rawLine == null ? "" : rawLine.trim();
                if (path.isEmpty() || path.startsWith("#")) {
                    continue;
                }
                File file = new File(path);
                if (!file.isFile()) {
                    log("Skipping missing MTS mod jar: " + file.getAbsolutePath());
                    continue;
                }
                if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    log("Skipping non-jar MTS mod file: " + file.getAbsolutePath());
                    continue;
                }
                String absolutePath = file.getAbsolutePath();
                if (seenPaths.add(absolutePath)) {
                    files.add(file);
                }
            }
        } catch (IOException error) {
            log("Failed to read MTS mod file list: " + error.getMessage());
            return fallbackOrEmpty(fallback);
        } catch (RuntimeException error) {
            log("Failed to parse MTS mod file list: " + error.getMessage());
            return fallbackOrEmpty(fallback);
        }

        if (files.isEmpty()) {
            log("MTS mod file list was empty after validation; using original scan result");
            return fallbackOrEmpty(fallback);
        }
        log("Using MTS mod file list with " + files.size() + " jar(s)");
        return files.toArray(new File[files.size()]);
    }

    private static void writeAudit(File[] resolved) {
        String auditPath = System.getProperty(AUDIT_PROPERTY_NAME);
        if (auditPath == null || auditPath.trim().isEmpty()) {
            return;
        }
        File auditFile = new File(auditPath.trim());
        File parent = auditFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log("Failed to create MTS mod file list audit directory: " + parent.getAbsolutePath());
            return;
        }
        StringBuilder out = new StringBuilder();
        if (resolved != null) {
            for (File file : resolved) {
                if (file == null) {
                    continue;
                }
                out.append(file.getAbsolutePath()).append('\n');
            }
        }
        try {
            Files.write(auditFile.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            log("Failed to write MTS mod file list audit: " + error.getMessage());
        }
    }

    private static File[] fallbackOrEmpty(File[] fallback) {
        return fallback != null ? fallback : new File[0];
    }

    private static void log(String message) {
        System.out.println("[Amethyst] " + message);
    }
}

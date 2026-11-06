package io.stamethyst.godot;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.JSONException;

/** Only used before engine initialization, when no native save writer is running. */
final class LegacySlingBreakMigrationStore {
    private final File directory;
    LegacySlingBreakMigrationStore(File directory) { this.directory = directory; }
    private File marker() { return new File(directory, "legacy-web-migration-v1.complete"); }
    File progress() { return new File(directory, "slingbreak-progress.json"); }
    boolean completed() {
        try { return "v1\n".equals(new String(Files.readAllBytes(marker().toPath()), StandardCharsets.UTF_8)); }
        catch (IOException error) { return false; }
    }
    void migrate(String raw) throws IOException, JSONException {
        if (completed()) return;
        byte[] converted = LegacySlingBreakProgress.convert(raw).toString().getBytes(StandardCharsets.UTF_8);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create save directory");
        // Keep both the exact Web JSON and any existing native progress. Never alter localStorage.
        File backup = Files.createTempDirectory(directory.toPath(), "legacy-migration-backup-").toFile();
        write(new File(backup, "web-save.json"), raw.getBytes(StandardCharsets.UTF_8));
        if (progress().exists()) write(new File(backup, "native-progress.json"), Files.readAllBytes(progress().toPath()));
        write(progress(), converted);
        if (!java.util.Arrays.equals(converted, Files.readAllBytes(progress().toPath()))) throw new IOException("Save verification failed");
        // No completion flag on cancellation, conversion error, backup failure or write failure.
        write(marker(), "v1\n".getBytes(StandardCharsets.UTF_8));
    }
    private static void write(File target, byte[] data) throws IOException {
        File pending = new File(target.getPath() + ".migration-pending");
        try {
            try (FileOutputStream out = new FileOutputStream(pending)) { out.write(data); out.getFD().sync(); }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { pending.delete(); }
    }
}

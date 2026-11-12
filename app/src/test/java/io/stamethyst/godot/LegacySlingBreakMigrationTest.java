package io.stamethyst.godot;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacySlingBreakMigrationTest {
    private static final String WEB = "{\"level\":12,\"coins\":321.5,\"total\":88,\"best\":9,\"up\":{\"power\":2,\"arrow\":3,\"brick\":1},\"sound\":false,\"skills\":{\"echo\":2},\"board\":null}";

    @Test public void oldestWebStateBecomesNativeEnvelopeWithoutLosingProgress() throws Exception {
        JSONObject converted = LegacySlingBreakProgress.convert(WEB);
        assertEquals("slingbreak-progress", converted.getString("format"));
        assertEquals(1, converted.getInt("version"));
        JSONObject state = converted.getJSONObject("state");
        assertEquals(12, state.getInt("level"));
        assertEquals(321.5, state.getDouble("coins"), 0);
        assertEquals(88, state.getInt("total"));
        assertEquals(9, state.getInt("best"));
        assertEquals(3, state.getJSONObject("up").getInt("arrow"));
        assertEquals(0, state.getJSONObject("up").getInt("draw"));
        assertEquals(0, state.getInt("volume"));
        assertEquals(1, state.getJSONObject("skills").getInt("echo"));
    }
    @Test public void preservesBoardDamageObstaclesAndAchievementCounters() throws Exception {
        JSONObject original = new JSONObject(WEB);
        original.put("achievements", new JSONObject("{\"shots\":42}"));
        original.put("board", new JSONObject("{\"level\":12,\"initial\":70,\"killed\":2,\"core\":false,\"bricks\":[{\"x\":102,\"y\":170,\"w\":84,\"h\":44,\"hp\":1.5,\"max\":5,\"type\":\"frost\",\"frozen\":true}],\"obstacles\":[{\"x\":198,\"y\":230,\"w\":84,\"h\":44}]}"));
        JSONObject state = LegacySlingBreakProgress.convert(original.toString()).getJSONObject("state");
        assertEquals(original.getJSONObject("board").toString(), state.getJSONObject("board").toString());
        assertEquals(42, state.getJSONObject("achievements").getInt("shots"));
    }
    @Test public void corruptAndUnsupportedSavesAreRejected() throws Exception {
        for (String raw : new String[]{"null", "{}", "not json", WEB.replace("321.5", "\"321.5\""), WEB.replace("\"level\":12", "\"level\":-1"), WEB.replace("\"echo\":2", "\"unknown\":1")}) {
            assertThrows(JSONException.class, () -> LegacySlingBreakProgress.convert(raw));
        }
        JSONObject original = new JSONObject(WEB).put("nativeBoss", new JSONObject());
        assertThrows(JSONException.class, () -> LegacySlingBreakProgress.convert(original.toString()));
    }
    @Test public void successPersistsCompletionAcrossSessionsAndBacksUpBothSaves() throws Exception {
        File root = Files.createTempDirectory("legacy-progress-test").toFile();
        try {
            LegacySlingBreakMigrationStore store = new LegacySlingBreakMigrationStore(root);
            assertFalse(store.completed());
            Files.write(store.progress().toPath(), "previous-native-save".getBytes(StandardCharsets.UTF_8));
            store.migrate(WEB);
            assertTrue(new LegacySlingBreakMigrationStore(root).completed());
            store.migrate(WEB.replace("\"level\":12", "\"level\":99")); // Completion is idempotent.
            assertEquals(12, new JSONObject(new String(Files.readAllBytes(store.progress().toPath()), StandardCharsets.UTF_8)).getJSONObject("state").getInt("level"));
            File[] backups = root.listFiles(file -> file.isDirectory() && file.getName().startsWith("legacy-migration-backup-"));
            assertNotNull(backups); assertEquals(1, backups.length);
            assertEquals(WEB, new String(Files.readAllBytes(new File(backups[0], "web-save.json").toPath()), StandardCharsets.UTF_8));
            assertEquals("previous-native-save", new String(Files.readAllBytes(new File(backups[0], "native-progress.json").toPath()), StandardCharsets.UTF_8));
        } finally { clean(root); }
    }
    @Test public void decodedGeckoSaveMigratesWithoutChangingOriginalData() throws Exception {
        byte[] encoded = WEB.getBytes(StandardCharsets.UTF_16LE);
        byte[] original = encoded.clone();
        String decoded = LegacyGeckoSlingBreakSave.decode(encoded, WEB.length(), 0, 0);
        File root = Files.createTempDirectory("legacy-gecko-progress-test").toFile();
        try {
            LegacySlingBreakMigrationStore store = new LegacySlingBreakMigrationStore(root);
            store.migrate(decoded);
            assertTrue(store.completed());
            JSONObject state = new JSONObject(new String(Files.readAllBytes(store.progress().toPath()),
                StandardCharsets.UTF_8)).getJSONObject("state");
            assertEquals(12, state.getInt("level"));
            assertEquals(321.5, state.getDouble("coins"), 0);
            assertArrayEquals(original, encoded);
        } finally { clean(root); }
    }
    @Test public void failedConversionDoesNotTouchNativeSaveOrMarkCompletion() throws Exception {
        File root = Files.createTempDirectory("legacy-progress-test").toFile();
        try {
            LegacySlingBreakMigrationStore store = new LegacySlingBreakMigrationStore(root);
            Files.write(store.progress().toPath(), "keep-me".getBytes(StandardCharsets.UTF_8));
            assertThrows(JSONException.class, () -> store.migrate("{}"));
            assertFalse(store.completed());
            assertEquals("keep-me", new String(Files.readAllBytes(store.progress().toPath()), StandardCharsets.UTF_8));
        } finally { clean(root); }
    }
    @Test public void failedBackupDoesNotReplaceProgressOrMarkCompletion() throws Exception {
        File root = Files.createTempDirectory("legacy-progress-test").toFile();
        try {
            LegacySlingBreakMigrationStore store = new LegacySlingBreakMigrationStore(root);
            assertTrue(store.progress().mkdir()); // Existing unreadable progress: fail closed.
            assertThrows(IOException.class, () -> store.migrate(WEB));
            assertTrue(store.progress().isDirectory());
            assertFalse(store.completed());
        } finally { clean(root); }
    }
    private static void clean(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) clean(child);
        file.delete();
    }
}

package io.stamethyst.compatmod.achievement;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.screens.stats.AchievementItem;
import com.megacrit.cardcrawl.screens.stats.StatsScreen;
import com.megacrit.cardcrawl.unlock.UnlockTracker;

/** JVM-only bridge for launcher-owned achievement synchronization and notifications. */
public final class AchievementBridge {
    private static final String REQUEST_PATH_PROP = "amethyst.achievement.request_path";
    private static final String LOCK_COMMAND_PATH_PROP = "amethyst.achievement.lock_command_path";
    private static final Set<String> SENT = new HashSet<String>();
    private static boolean initialized;

    private AchievementBridge() {
    }

    public static void initialize() {
        initialized = true;
        System.out.println(
            "[amethyst-achievement] bridge initialized requestPathConfigured="
                + Boolean.toString(requestFile() != null)
        );
    }

    public static void reportUnlocked(String id) {
        if (!initialized || id == null || id.trim().length() == 0) return;
        String normalized = sanitize(id);
        synchronized (AchievementBridge.class) {
            if (!SENT.add(normalized)) return;
        }
        boolean written = writeRequest(buildSyncRequest());
        System.out.println(
            "[amethyst-achievement] report id=" + normalized + " requestWritten=" + Boolean.toString(written)
        );
    }

    /** Called on the game thread to apply launcher-confirmed remote locks to the active cache. */
    public static void pollLockCommand() {
        if (!initialized || UnlockTracker.achievementPref == null) return;
        File commandFile = lockCommandFile();
        if (commandFile == null || !commandFile.isFile()) return;
        java.util.List<String> ids;
        byte[] commandBytes;
        try {
            commandBytes = Files.readAllBytes(commandFile.toPath());
            ids = java.util.Arrays.asList(
                new String(commandBytes, StandardCharsets.UTF_8).split("\\r?\\n")
            );
        } catch (Exception ignored) {
            return;
        }
        try {
            for (String rawId : ids) {
                String id = rawId == null ? "" : rawId.trim();
                if (!id.matches("[a-z0-9_]+")) continue;
                removeAchievementFromCache(id);
                refreshAchievementScreen(id);
            }
            UnlockTracker.achievementPref.flush();
            // Do not remove a newer command that the launcher atomically published while
            // this frame was applying the previous batch.
            if (commandFile.isFile()) {
                byte[] currentBytes = Files.readAllBytes(commandFile.toPath());
                if (java.util.Arrays.equals(commandBytes, currentBytes)) {
                    commandFile.delete();
                }
            }
        } catch (Exception ignored) {
            // Keep the command so a later game update can retry the in-memory cache update.
        }
    }

    private static void removeAchievementFromCache(String apiName) {
        Map<String, String> data = UnlockTracker.achievementPref.data;
        ArrayList<String> keysToRemove = new ArrayList<String>();
        for (String key : data.keySet()) {
            if (apiName.equalsIgnoreCase(key)) keysToRemove.add(key);
        }
        for (String key : keysToRemove) data.remove(key);
    }

    private static void refreshAchievementScreen(String apiName) {
        if (StatsScreen.achievements == null || StatsScreen.achievements.items == null) return;
        for (AchievementItem item : StatsScreen.achievements.items) {
            if (item != null && item.key != null && apiName.equalsIgnoreCase(item.key)) {
                item.isUnlocked = false;
                item.reloadImg();
            }
        }
    }

    private static boolean writeRequest(String request) {
        File target = requestFile();
        if (target == null) return false;
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        File temporary = new File(target.getPath() + ".tmp");
        try {
            Writer writer = new OutputStreamWriter(new FileOutputStream(temporary), StandardCharsets.UTF_8.name());
            try {
                writer.write(request);
                writer.write('\n');
            } finally {
                writer.close();
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailure) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception error) {
            temporary.delete();
            System.out.println(
                "[amethyst-achievement] request write failed error=" + error.getClass().getSimpleName()
            );
            return false;
        }
    }

    private static String buildSyncRequest() {
        StringBuilder request = new StringBuilder("{\"version\":1,\"type\":\"achievement_sync\",\"save_slot\":");
        request.append(CardCrawlGame.saveSlot >= 0 ? CardCrawlGame.saveSlot : 0);
        request.append(",\"achievements\":[");
        synchronized (AchievementBridge.class) {
            boolean first = true;
            for (String id : SENT) {
                if (!first) request.append(',');
                request.append('\"').append(json(id)).append('\"');
                first = false;
            }
        }
        return request.append("]}").toString();
    }

    private static File requestFile() {
        String path = System.getProperty(REQUEST_PATH_PROP, "").trim();
        return path.length() == 0 ? null : new File(path);
    }

    private static File lockCommandFile() {
        String path = System.getProperty(LOCK_COMMAND_PATH_PROP, "").trim();
        return path.length() == 0 ? null : new File(path);
    }

    private static String sanitize(String value) {
        return value.replace('\r', '_').replace('\n', '_').trim().toLowerCase(Locale.ROOT);
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

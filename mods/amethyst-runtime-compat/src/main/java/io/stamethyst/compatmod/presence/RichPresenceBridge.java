package io.stamethyst.compatmod.presence;

import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** JVM-only IPC bridge: atomically writes current rich-presence key-value state so the
 *  launcher can upload it via CMsgClientRichPresenceUpload (EMsg 7501).
 *
 *  <p>Key contract (matches BaseMod.setRichPresence and Steam's documented rules):
 *  the human-readable text goes into {@code status}, while {@code steam_display} must be
 *  a localization token registered for the app. Steam states that if {@code steam_display}
 *  is not a valid localization tag, rich presence is not displayed at all — so raw text
 *  there silently shows nothing. AppID 646570 ships {@code #Status}, which resolves to
 *  {@code %status%}, so {@code #Status} is the only correct {@code steam_display} value. */
public final class RichPresenceBridge {
    private static final String PRESENCE_PATH_PROP = "amethyst.richpresence.path";
    private static final String PREFIX_PROP = "amethyst.richpresence.prefix";
    private static final String DEVICE_NAME_PROP = "amethyst.richpresence.device_name";
    private static final String SHOW_CHARACTER_PROP = "amethyst.richpresence.show_character";
    private static final String SHOW_FLOOR_PROP = "amethyst.richpresence.show_floor";
    private static final String SHOW_ASCENSION_PROP = "amethyst.richpresence.show_ascension";
    private static final String SHOW_ACT_PROP = "amethyst.richpresence.show_act";
    private static final String GAME_PREFIX = "在 Slay the Amethyst 上游玩";
    /** Localization token registered by Slay the Spire; renders the `status` value. */
    private static final String STEAM_DISPLAY_TOKEN = "#Status";
    private static boolean initialized;
    private static String lastWrittenPayload = "";

    private RichPresenceBridge() {
    }

    public static void initialize() {
        initialized = true;
        publishMainMenuState();
        System.out.println(
            "[amethyst-presence] bridge initialized pathConfigured="
                + Boolean.toString(presenceFile() != null)
        );
    }

    /** Re-publishes the menu state whenever the game creates a main-menu screen. */
    public static void updateMainMenuState() {
        if (!initialized) return;
        publishMainMenuState();
    }

    /** Makes the launcher aware of an active game before a dungeon has been created. */
    private static void publishMainMenuState() {
        Map<String, String> kv = new LinkedHashMap<>();
        String prefix = prefixText();
        kv.put("status", prefix.isEmpty() ? "主菜单" : prefix + " - 主菜单");
        kv.put("steam_display", STEAM_DISPLAY_TOKEN);
        String payload = serializeKv(kv);
        boolean written = writePresence(payload);
        if (written) {
            lastWrittenPayload = payload;
        }
        System.out.println("[amethyst-presence] main_menu written=" + written);
    }

    /**
     * Called on dungeon state transitions (floor change, run start).
     * Reads current {@link AbstractDungeon} and {@link CardCrawlGame#player} statics,
     * serialises to key=value lines, and atomically overwrites the IPC file when the
     * state has changed.
     */
    public static void updateDungeonState() {
        if (!initialized) return;
        Map<String, String> kv = buildKvPairs();
        if (kv == null) return;
        String payload = serializeKv(kv);
        if (payload.equals(lastWrittenPayload)) return;
        boolean written = writePresence(payload);
        if (written) {
            lastWrittenPayload = payload;
        }
        System.out.println(
            "[amethyst-presence] state_updated written=" + written
                + " floor=" + kv.get("floor")
                + " character=" + kv.get("character")
        );
    }

    // -----------------------------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------------------------

    private static Map<String, String> buildKvPairs() {
        try {
            AbstractPlayer player = AbstractDungeon.player;
            if (player == null) return null;
            int floorNum = AbstractDungeon.floorNum;
            String character = player.chosenClass != null
                ? player.chosenClass.name().toLowerCase(java.util.Locale.ROOT)
                : "unknown";
            // Use the localized character name for the human-readable status string.
            // Falls back to the enum name if the method is unavailable.
            String displayName;
            try {
                displayName = player.getLocalizedCharacterName();
                if (displayName == null || displayName.isEmpty()) displayName = character;
            } catch (Throwable ignored) {
                displayName = character;
            }
            // The visible text must live in `status`; `steam_display` only names the
            // localization token that renders it. See the class javadoc for why raw text
            // in `steam_display` never displays.
            String prefix = prefixText();
            boolean showCharacter = readBooleanProperty(SHOW_CHARACTER_PROP, true);
            boolean showFloor = readBooleanProperty(SHOW_FLOOR_PROP, true);
            boolean showAscension = readBooleanProperty(SHOW_ASCENSION_PROP, false)
                && AbstractDungeon.isAscensionMode;
            StringBuilder displayText = new StringBuilder(prefix);
            if (showCharacter) displayText.append(displayName);
            if (showFloor) {
                appendStatusSegment(displayText, "第" + floorNum + "层");
                if (showAscension) displayText.append("（进阶").append(AbstractDungeon.ascensionLevel).append("）");
            } else if (showAscension) {
                appendStatusSegment(displayText, "进阶" + AbstractDungeon.ascensionLevel);
            }
            if (readBooleanProperty(SHOW_ACT_PROP, false)) {
                appendStatusSegment(displayText, "第" + AbstractDungeon.actNum + "幕");
            }
            if (displayText.length() == 0) displayText.append("游玩中");
            Map<String, String> kv = new LinkedHashMap<>();
            kv.put("status", displayText.toString());
            kv.put("steam_display", STEAM_DISPLAY_TOKEN);
            kv.put("character", character);
            kv.put("floor", String.valueOf(floorNum));
            return kv;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String serializeKv(Map<String, String> kv) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : kv.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(entry.getKey()).append('=').append(escapeValue(entry.getValue()));
        }
        return sb.toString();
    }

    private static String prefixText() {
        String prefix = System.getProperty(PREFIX_PROP, "game").trim();
        if ("device".equals(prefix)) {
            String deviceName = System.getProperty(DEVICE_NAME_PROP, "Android").trim();
            return deviceName.isEmpty() ? "在 Android 上游玩" : "在 " + deviceName + " 上游玩";
        }
        if ("none".equals(prefix)) return "";
        return GAME_PREFIX;
    }

    private static boolean readBooleanProperty(String key, boolean defaultValue) {
        String value = System.getProperty(key, "").trim();
        return value.isEmpty() ? defaultValue : Boolean.parseBoolean(value);
    }

    private static void appendStatusSegment(StringBuilder status, String segment) {
        if (segment == null || segment.isEmpty()) return;
        if (status.length() > 0) status.append(" - ");
        status.append(segment);
    }

    /** Escapes newlines and equals signs in values to keep the format unambiguous. */
    private static String escapeValue(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\=");
    }

    private static boolean writePresence(String payload) {
        File target = presenceFile();
        if (target == null) return false;
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        File temporary = new File(target.getPath() + ".tmp");
        try {
            Writer writer = new OutputStreamWriter(
                new FileOutputStream(temporary), StandardCharsets.UTF_8.name());
            try {
                writer.write(payload);
                writer.write('\n');
            } finally {
                writer.close();
            }
            try {
                Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailure) {
                Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception error) {
            temporary.delete();
            System.out.println(
                "[amethyst-presence] write failed error=" + error.getClass().getSimpleName()
            );
            return false;
        }
    }

    private static File presenceFile() {
        String path = System.getProperty(PRESENCE_PATH_PROP, "").trim();
        return path.isEmpty() ? null : new File(path);
    }
}

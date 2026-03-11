package io.stamethyst.bridge;

import java.util.Locale;

final class BootBridgePhaseMapper {
    private static final String[] READY_HINT_FRAGMENTS = new String[]{
            "basemod.basemod> publishaddcustommodemods",
            "stats.statsscreen> loading character stats.",
            "core.displayconfig> displayconfig successfully read.",
            "characters.charactermanager> successfully recreated"
    };

    private static final PhaseRule[] PHASE_RULES = new PhaseRule[]{
            PhaseRule.startsWith("Searching for workshop items", 30, "searching_workshop_items"),
            PhaseRule.startsWith("Begin patching", 36, "begin_patching"),
            PhaseRule.startsWith("Patching enums", 44, "patching_enums"),
            PhaseRule.startsWith("Finding core patches", 52, "finding_core_patches"),
            PhaseRule.startsWith("Finding patches", 60, "finding_patches"),
            PhaseRule.startsWith("Busting enums", 72, "patching_enums"),
            PhaseRule.startsWith("Setting isModded = true", 80, "setting_modded_state"),
            PhaseRule.startsWith("Adding ModTheSpire to version", 84, "adding_modthespire_version"),
            PhaseRule.startsWith("Initializing mods", 90, "initializing_mods"),
            PhaseRule.startsWith("Starting game", 93, "starting_game_entry"),
            PhaseRule.contains("DesktopLauncher> Launching application", 95, "starting_game_entry"),
            PhaseRule.contains("CardCrawlGame> No migration", 96, "game_main_boot")
    };

    private BootBridgePhaseMapper() {
    }

    static PhaseMatch matchPhase(String line) {
        String value = normalize(line);
        if (value.isEmpty()) {
            return null;
        }
        for (PhaseRule rule : PHASE_RULES) {
            if (rule.matches(value)) {
                return new PhaseMatch(rule.progress, BootBridgeStartupMessage.key(rule.messageKey));
            }
        }
        return null;
    }

    static boolean isReadyConsoleLine(String line) {
        String lower = normalize(line).toLowerCase(Locale.ROOT);
        for (String fragment : READY_HINT_FRAGMENTS) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    static String encodeConsoleError(String line) {
        String value = normalize(line);
        if ("ERROR: Failed to find Steam installation.".equals(value)) {
            return BootBridgeStartupMessage.key("steam_installation_not_found");
        }
        return BootBridgeStartupMessage.keyWithPayload("mts_error", value);
    }

    static String encodeStepMessage(String stepMessage, int mtsPercent) {
        PhaseMatch match = matchPhase(stepMessage);
        if (match != null) {
            return match.message;
        }
        if (mtsPercent >= 0) {
            return BootBridgeStartupMessage.keyWithPayload(
                    "mts_phase_percent",
                    String.valueOf(mtsPercent)
            );
        }
        return BootBridgeStartupMessage.key("launching_modthespire");
    }

    static String normalize(String line) {
        if (line == null) {
            return "";
        }
        return line.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .trim();
    }

    static final class PhaseMatch {
        final int progress;
        final String message;

        PhaseMatch(int progress, String message) {
            this.progress = progress;
            this.message = message;
        }
    }

    private static final class PhaseRule {
        private final String text;
        private final int progress;
        private final String messageKey;
        private final boolean contains;

        private PhaseRule(String text, int progress, String messageKey, boolean contains) {
            this.text = text;
            this.progress = progress;
            this.messageKey = messageKey;
            this.contains = contains;
        }

        private static PhaseRule startsWith(String text, int progress, String messageKey) {
            return new PhaseRule(text, progress, messageKey, false);
        }

        private static PhaseRule contains(String text, int progress, String messageKey) {
            return new PhaseRule(text, progress, messageKey, true);
        }

        private boolean matches(String line) {
            if (contains) {
                return line.contains(text);
            }
            return line.startsWith(text);
        }
    }
}

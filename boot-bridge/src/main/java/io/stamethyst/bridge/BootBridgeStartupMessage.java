package io.stamethyst.bridge;

final class BootBridgeStartupMessage {
    private static final String PREFIX = "@amethyst.startup/";

    private BootBridgeStartupMessage() {
    }

    static String key(String key) {
        String safeKey = sanitizeKey(key);
        return PREFIX + safeKey;
    }

    static String keyWithPayload(String key, String payload) {
        String safeKey = sanitizeKey(key);
        String safePayload = BootBridgeReporter.sanitizeMessage(payload);
        if (safePayload.isEmpty()) {
            return PREFIX + safeKey;
        }
        return PREFIX + safeKey + ":" + safePayload;
    }

    private static String sanitizeKey(String key) {
        String value = key == null ? "" : key.trim();
        return value.isEmpty() ? "unknown" : value;
    }
}

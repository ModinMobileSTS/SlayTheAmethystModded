package io.stamethyst;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DisplayConfigSync {
    private static final int DEFAULT_FPS_LIMIT = 60;
    private static final int FALLBACK_TARGET_FPS_LIMIT = 120;
    private static final boolean DEFAULT_FULLSCREEN = false;
    private static final boolean DEFAULT_WINDOWED_FULLSCREEN = false;
    private static final boolean DEFAULT_VSYNC = true;
    private static final int MIN_WIDTH = 800;
    private static final int MIN_HEIGHT = 450;

    private DisplayConfigSync() {
    }

    static void syncToCurrentResolution(Context context, int width, int height, int targetFpsLimit) throws IOException {
        int safeWidth = Math.max(MIN_WIDTH, width);
        int safeHeight = Math.max(MIN_HEIGHT, height);
        int normalizedTargetFpsLimit = normalizeTargetFpsLimit(targetFpsLimit);

        File configFile = RuntimePaths.displayConfigFile(context);
        DisplayConfigState state = readExisting(configFile).withFpsLimit(normalizedTargetFpsLimit);
        writeConfig(configFile, safeWidth, safeHeight, state);
    }

    private static DisplayConfigState readExisting(File configFile) {
        if (!configFile.isFile()) {
            return DisplayConfigState.defaults();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return DisplayConfigState.defaults();
        }

        int fps = lines.size() > 2 ? parsePositiveInt(lines.get(2), DEFAULT_FPS_LIMIT) : DEFAULT_FPS_LIMIT;
        boolean fullscreen = lines.size() > 3 ? parseBoolean(lines.get(3), DEFAULT_FULLSCREEN) : DEFAULT_FULLSCREEN;
        boolean wfs = lines.size() > 4 ? parseBoolean(lines.get(4), DEFAULT_WINDOWED_FULLSCREEN) : DEFAULT_WINDOWED_FULLSCREEN;
        boolean vsync = lines.size() > 5 ? parseBoolean(lines.get(5), DEFAULT_VSYNC) : DEFAULT_VSYNC;
        return new DisplayConfigState(fps, fullscreen, wfs, vsync);
    }

    private static void writeConfig(File configFile, int width, int height, DisplayConfigState state) throws IOException {
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
        }

        List<String> lines = new ArrayList<>(6);
        lines.add(Integer.toString(width));
        lines.add(Integer.toString(height));
        lines.add(Integer.toString(state.fpsLimit));
        lines.add(Boolean.toString(state.fullscreen));
        lines.add(Boolean.toString(state.windowedFullscreen));
        lines.add(Boolean.toString(state.vsync));
        Files.write(configFile.toPath(), lines, StandardCharsets.UTF_8);
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        String normalized = raw.trim().toLowerCase(Locale.US);
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        return fallback;
    }

    private static int normalizeTargetFpsLimit(int targetFpsLimit) {
        if (targetFpsLimit == 60
                || targetFpsLimit == 90
                || targetFpsLimit == 120
                || targetFpsLimit == 240) {
            return targetFpsLimit;
        }
        return FALLBACK_TARGET_FPS_LIMIT;
    }

    private static final class DisplayConfigState {
        private final int fpsLimit;
        private final boolean fullscreen;
        private final boolean windowedFullscreen;
        private final boolean vsync;

        private DisplayConfigState(int fpsLimit, boolean fullscreen, boolean windowedFullscreen, boolean vsync) {
            this.fpsLimit = fpsLimit;
            this.fullscreen = fullscreen;
            this.windowedFullscreen = windowedFullscreen;
            this.vsync = vsync;
        }

        private DisplayConfigState withFpsLimit(int fpsLimit) {
            return new DisplayConfigState(
                    fpsLimit,
                    fullscreen,
                    windowedFullscreen,
                    vsync
            );
        }

        private static DisplayConfigState defaults() {
            return new DisplayConfigState(
                    DEFAULT_FPS_LIMIT,
                    DEFAULT_FULLSCREEN,
                    DEFAULT_WINDOWED_FULLSCREEN,
                    DEFAULT_VSYNC
            );
        }
    }
}

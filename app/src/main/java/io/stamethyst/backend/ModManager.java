package io.stamethyst.backend;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

public final class ModManager {
    public static final String MOD_ID_BASEMOD = "basemod";
    public static final String MOD_ID_STSLIB = "stslib";
    private static final Set<String> REQUIRED_MOD_IDS = new HashSet<>(Arrays.asList(
            MOD_ID_BASEMOD,
            MOD_ID_STSLIB
    ));

    public static final class InstalledMod {
        public final String modId;
        public final String manifestModId;
        public final String name;
        public final String version;
        public final String description;
        public final List<String> dependencies;
        public final File jarFile;
        public final boolean required;
        public final boolean installed;
        public final boolean enabled;

        private InstalledMod(String modId,
                             String manifestModId,
                             String name,
                             String version,
                             String description,
                             List<String> dependencies,
                             File jarFile,
                             boolean required,
                             boolean installed,
                             boolean enabled) {
            this.modId = modId;
            this.manifestModId = manifestModId;
            this.name = name;
            this.version = version;
            this.description = description;
            this.dependencies = dependencies == null ? new ArrayList<>() : dependencies;
            this.jarFile = jarFile;
            this.required = required;
            this.installed = installed;
            this.enabled = enabled;
        }
    }

    private ModManager() {
    }

    public static String normalizeModId(@Nullable String modId) {
        if (modId == null) {
            return "";
        }
        String normalized = modId.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "" : normalized;
    }

    public static boolean isRequiredModId(String modId) {
        return REQUIRED_MOD_IDS.contains(normalizeModId(modId));
    }

    public static boolean hasBundledRequiredModAsset(Context context, String modId) {
        String normalized = normalizeModId(modId);
        if (MOD_ID_BASEMOD.equals(normalized)) {
            return hasBundledAsset(context, "components/mods/BaseMod.jar");
        }
        if (MOD_ID_STSLIB.equals(normalized)) {
            return hasBundledAsset(context, "components/mods/StSLib.jar");
        }
        return false;
    }

    public static File resolveStorageFileForModId(Context context, String modId) {
        String normalized = normalizeModId(modId);
        if (MOD_ID_BASEMOD.equals(normalized)) {
            return RuntimePaths.importedBaseModJar(context);
        }
        if (MOD_ID_STSLIB.equals(normalized)) {
            return RuntimePaths.importedStsLibJar(context);
        }
        return new File(RuntimePaths.modsDir(context), sanitizeFileName(normalized) + ".jar");
    }

    public static void setOptionalModEnabled(Context context, String modId, boolean enabled) throws IOException {
        String normalized = normalizeModId(modId);
        if (normalized.isEmpty() || isRequiredModId(normalized)) {
            return;
        }
        Set<String> selected = readEnabledOptionalModIds(context);
        boolean changed;
        if (enabled) {
            changed = selected.add(normalized);
        } else {
            changed = selected.remove(normalized);
        }
        if (changed) {
            writeEnabledOptionalModIds(context, selected);
        }
    }

    public static boolean deleteOptionalMod(Context context, String modId) throws IOException {
        String normalized = normalizeModId(modId);
        if (normalized.isEmpty() || isRequiredModId(normalized)) {
            return false;
        }

        TreeMap<String, File> optionalModFiles = findOptionalModFiles(context);
        File target = optionalModFiles.get(normalized);

        setOptionalModEnabled(context, normalized, false);

        if (target == null || !target.isFile()) {
            return false;
        }
        if (!target.delete()) {
            throw new IOException("Failed to delete mod file: " + target.getAbsolutePath());
        }
        return true;
    }

    public static List<InstalledMod> listInstalledMods(Context context) {
        List<InstalledMod> result = new ArrayList<>();
        result.add(buildRequiredEntry(context, MOD_ID_BASEMOD, "BaseMod", RuntimePaths.importedBaseModJar(context)));
        result.add(buildRequiredEntry(context, MOD_ID_STSLIB, "StSLib", RuntimePaths.importedStsLibJar(context)));

        Set<String> enabledOptional = readEnabledOptionalModIdsSafely(context);
        TreeMap<String, File> optionalModFiles = findOptionalModFiles(context);
        maybePruneEnabledSelection(context, enabledOptional, optionalModFiles.keySet());

        for (String modId : optionalModFiles.keySet()) {
            File jarFile = optionalModFiles.get(modId);
            String manifestModId = modId;
            String name = modId;
            String version = "";
            String description = "";
            List<String> dependencies = new ArrayList<>();
            try {
                ModJarSupport.ModManifestInfo manifest = ModJarSupport.readModManifest(jarFile);
                manifestModId = defaultIfBlank(manifest.modId, modId);
                name = defaultIfBlank(manifest.name, manifestModId);
                version = trimToEmpty(manifest.version);
                description = trimToEmpty(manifest.description);
                dependencies = new ArrayList<>(manifest.dependencies);
            } catch (Throwable ignored) {
                name = modId;
            }
            result.add(new InstalledMod(
                    modId,
                    manifestModId,
                    name,
                    version,
                    description,
                    dependencies,
                    jarFile,
                    false,
                    true,
                    enabledOptional.contains(modId)
            ));
        }
        return result;
    }

    public static List<String> resolveLaunchModIds(Context context) throws IOException {
        String baseModId = resolveRequiredLaunchModId(RuntimePaths.importedBaseModJar(context), MOD_ID_BASEMOD, "BaseMod.jar");
        String stsLibId = resolveRequiredLaunchModId(RuntimePaths.importedStsLibJar(context), MOD_ID_STSLIB, "StSLib.jar");

        Set<String> enabledOptional = readEnabledOptionalModIds(context);
        TreeMap<String, File> optionalModFiles = findOptionalModFiles(context);
        maybePruneEnabledSelection(context, enabledOptional, optionalModFiles.keySet());

        List<String> launchModIds = new ArrayList<>();
        launchModIds.add(baseModId);
        launchModIds.add(stsLibId);

        for (String modId : enabledOptional) {
            File modJar = optionalModFiles.get(modId);
            if (modJar != null) {
                String rawModId = resolveRawModId(modJar);
                if (!rawModId.isEmpty()) {
                    launchModIds.add(rawModId);
                }
            }
        }
        return launchModIds;
    }

    private static InstalledMod buildRequiredEntry(Context context, String expectedModId, String label, File jarFile) {
        boolean installed = false;
        String manifestModId = expectedModId;
        String name = label;
        String version = "";
        String description = "";
        List<String> dependencies = new ArrayList<>();
        if (jarFile.isFile()) {
            try {
                ModJarSupport.ModManifestInfo manifest = ModJarSupport.readModManifest(jarFile);
                installed = expectedModId.equals(manifest.normalizedModId);
                if (installed) {
                    manifestModId = defaultIfBlank(manifest.modId, expectedModId);
                    name = defaultIfBlank(manifest.name, label);
                    version = trimToEmpty(manifest.version);
                    description = trimToEmpty(manifest.description);
                    dependencies = new ArrayList<>(manifest.dependencies);
                }
            } catch (Throwable ignored) {
                installed = false;
            }
        }
        boolean bundled = hasBundledRequiredModAsset(context, expectedModId);
        boolean available = installed || bundled;
        return new InstalledMod(
                expectedModId,
                manifestModId,
                name,
                version,
                description,
                dependencies,
                jarFile,
                true,
                available,
                available
        );
    }

    private static boolean hasBundledAsset(Context context, String assetPath) {
        try (InputStream ignored = context.getAssets().open(assetPath)) {
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String resolveRequiredLaunchModId(File jarFile, String expectedModId, String label) throws IOException {
        if (!jarFile.isFile()) {
            throw new IOException(label + " not found");
        }
        String raw = resolveRawModId(jarFile);
        String normalized = normalizeModId(raw);
        if (!expectedModId.equals(normalized)) {
            throw new IOException(label + " has unexpected modid: " + raw);
        }
        return raw;
    }

    private static TreeMap<String, File> findOptionalModFiles(Context context) {
        TreeMap<String, File> modsById = new TreeMap<>();
        File modsDir = RuntimePaths.modsDir(context);
        File[] files = modsDir.listFiles();
        if (files == null) {
            return modsById;
        }
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            String name = file.getName();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            if (isReservedJarName(name)) {
                continue;
            }
            String modId;
            try {
                modId = normalizeModId(ModJarSupport.resolveModId(file));
            } catch (Throwable ignored) {
                continue;
            }
            if (modId.isEmpty() || isRequiredModId(modId) || modsById.containsKey(modId)) {
                continue;
            }
            modsById.put(modId, file);
        }
        return modsById;
    }

    private static boolean isReservedJarName(String fileName) {
        String normalized = fileName.toLowerCase(Locale.ROOT);
        return "basemod.jar".equals(normalized) || "stslib.jar".equals(normalized);
    }

    private static Set<String> readEnabledOptionalModIdsSafely(Context context) {
        try {
            return readEnabledOptionalModIds(context);
        } catch (Throwable ignored) {
            return new LinkedHashSet<>();
        }
    }

    private static Set<String> readEnabledOptionalModIds(Context context) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        File config = RuntimePaths.enabledModsConfig(context);
        if (!config.isFile()) {
            return ids;
        }
        try (FileInputStream input = new FileInputStream(config);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
             BufferedReader buffered = new BufferedReader(reader)) {
            String line;
            while ((line = buffered.readLine()) != null) {
                String modId = normalizeModId(line);
                if (!modId.isEmpty() && !isRequiredModId(modId)) {
                    ids.add(modId);
                }
            }
        }
        return ids;
    }

    private static void writeEnabledOptionalModIds(Context context, Set<String> modIds) throws IOException {
        File config = RuntimePaths.enabledModsConfig(context);
        File parent = config.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
        }
        try (FileOutputStream output = new FileOutputStream(config, false);
             OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
             BufferedWriter buffered = new BufferedWriter(writer)) {
            for (String modId : modIds) {
                String normalized = normalizeModId(modId);
                if (normalized.isEmpty() || isRequiredModId(normalized)) {
                    continue;
                }
                buffered.write(normalized);
                buffered.newLine();
            }
        }
    }

    private static void maybePruneEnabledSelection(Context context,
                                                   Set<String> enabledOptional,
                                                   Set<String> installedOptional) {
        if (enabledOptional.isEmpty()) {
            return;
        }
        Set<String> pruned = new LinkedHashSet<>(enabledOptional);
        pruned.retainAll(installedOptional);
        if (pruned.equals(enabledOptional)) {
            return;
        }
        try {
            writeEnabledOptionalModIds(context, pruned);
        } catch (Throwable ignored) {
        }
        enabledOptional.clear();
        enabledOptional.addAll(pruned);
    }

    private static String sanitizeFileName(String value) {
        if (value == null || value.isEmpty()) {
            return "mod";
        }
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '.'
                    || ch == '_'
                    || ch == '-') {
                sanitized.append(ch);
            } else {
                sanitized.append('_');
            }
        }
        if (sanitized.length() == 0) {
            return "mod";
        }
        return sanitized.toString();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        String trimmed = trimToEmpty(value);
        if (!trimmed.isEmpty()) {
            return trimmed;
        }
        return trimToEmpty(fallback);
    }

    private static String resolveRawModId(File jarFile) throws IOException {
        String raw = ModJarSupport.resolveModId(jarFile);
        if (raw == null) {
            return "";
        }
        return raw.trim();
    }
}

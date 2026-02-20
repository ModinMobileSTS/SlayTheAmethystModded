package io.stamethyst;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ModJarSupport {
    private static final String[] MOD_ID_JSON_KEYS = new String[]{
            "modid",
            "modId",
            "id",
            "ID",
            "mod_id"
    };
    private static final String[] MOD_NAME_JSON_KEYS = new String[]{
            "name",
            "Name",
            "modName",
            "mod_name"
    };
    private static final String[] MOD_VERSION_JSON_KEYS = new String[]{
            "version",
            "Version",
            "modVersion",
            "ModVersion"
    };
    private static final String[] MOD_DESCRIPTION_JSON_KEYS = new String[]{
            "description",
            "Description",
            "detail",
            "Detail"
    };
    private static final String[] MOD_DEPENDENCIES_JSON_KEYS = new String[]{
            "dependencies",
            "Dependencies",
            "depends",
            "DependsOn",
            "requiredMods",
            "RequiredMods",
            "required_mods"
    };
    private static final Pattern MOD_ID_PATTERN = Pattern.compile(
            "\"(?:modid|modId|id|ID|mod_id)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\""
    );
    private static final Pattern MOD_NAME_PATTERN = Pattern.compile(
            "\"(?:name|Name|modName|mod_name)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\""
    );
    private static final Pattern MOD_VERSION_PATTERN = Pattern.compile(
            "\"(?:version|Version|modVersion|ModVersion)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\""
    );
    private static final Pattern MOD_DESCRIPTION_PATTERN = Pattern.compile(
            "\"(?:description|Description|detail|Detail)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\""
    );
    private static final String GDX_CLASS_PREFIX = "com/badlogic/gdx/";
    private static final String GDX_BACKEND_PREFIX = "com/badlogic/gdx/backends/";
    private static final Set<String> ALLOWED_PARENT_BACKEND_CLASSES = new HashSet<>();
    private static final Set<String> REQUIRED_GDX_CLASSES = new HashSet<>(Arrays.asList(
            "com/badlogic/gdx/Application.class",
            "com/badlogic/gdx/graphics/g2d/Batch.class",
            "com/badlogic/gdx/utils/Disposable.class"
    ));
    private static final Set<String> GDX_BRIDGE_CLASSES = new HashSet<>(Arrays.asList(
            "com/badlogic/gdx/utils/SharedLibraryLoader.class",
            "com/badlogic/gdx/backends/lwjgl/LwjglNativesLoader.class"
    ));
    private static final String GDX_BRIDGE_LWJGL_INPUT_PREFIX =
            "com/badlogic/gdx/backends/lwjgl/LwjglInput";
    private static final String STS_PATCH_LWJGL_GRAPHICS_PREFIX =
            "com/badlogic/gdx/backends/lwjgl/LwjglGraphics";
    private static final String STS_PATCH_PIXEL_SCALE_CLASS =
            "com/badlogic/gdx/backends/lwjgl/PixelScaleCompat.class";
    private static final String STS_PATCH_LWJGL_INPUT_CLASS =
            "com/badlogic/gdx/backends/lwjgl/LwjglInput.class";
    private static final String STS_PATCH_LWJGL_NATIVES_CLASS =
            "com/badlogic/gdx/backends/lwjgl/LwjglNativesLoader.class";
    private static final String STS_PATCH_SHARED_LOADER_CLASS =
            "com/badlogic/gdx/utils/SharedLibraryLoader.class";
    private static final String STS_PATCH_STEAM_UTILS_CLASS =
            "com/codedisaster/steamworks/SteamUtils.class";
    private static final String STS_PATCH_STEAM_UTILS_ENUM_CLASS =
            "com/codedisaster/steamworks/SteamUtils$FloatingGamepadTextInputMode.class";
    private static final String STS_PATCH_STEAM_INPUT_HELPER_CLASS =
            "com/megacrit/cardcrawl/helpers/steamInput/SteamInputHelper.class";
    private static final String STS_PATCH_GL_FRAMEBUFFER_CLASS =
            "com/badlogic/gdx/graphics/glutils/GLFrameBuffer.class";
    private static final String STS_PATCH_DESKTOP_CONTROLLER_MANAGER_CLASS =
            "com/badlogic/gdx/controllers/desktop/DesktopControllerManager.class";
    private static final String STS_PATCH_DESKTOP_CONTROLLER_MANAGER_DIRECT_CLASS =
            "com/badlogic/gdx/controllers/desktop/DesktopControllerManager$DirectController.class";
    private static final String STS_PATCH_DESKTOP_CONTROLLER_MANAGER_POLL_CLASS =
            "com/badlogic/gdx/controllers/desktop/DesktopControllerManager$PollRunnable.class";
    private static final String STS_PATCH_DESKTOP_CONTROLLER_MANAGER_PREFIX =
            "com/badlogic/gdx/controllers/desktop/DesktopControllerManager";
    private static final String STS_PATCH_BUILD_PROPERTIES = "build.properties";
    private static final String STS_RESOURCE_SENTINEL = "build.properties";
    private static final String BASEMOD_RESOURCE_SENTINEL =
            "localization/basemod/eng/customMods.json";
    private static final String BASEMOD_MOD_ID = "basemod";
    private static final String BASEMOD_JAR_FILE_NAME = "BaseMod.jar";
    private static final String BASEMOD_GLOW_PATCH_JAR = "basemod-glow-fbo-compat.jar";
    private static final String BASEMOD_GLOW_PATCH_CLASS =
            "basemod/helpers/CardBorderGlowManager$RenderGlowPatch.class";
    private static final String BASEMOD_POSTPROCESS_PATCH_JAR = "basemod-postprocess-fbo-compat.jar";
    private static final String BASEMOD_POSTPROCESS_PATCH_CLASS =
            "basemod/patches/com/megacrit/cardcrawl/core/CardCrawlGame/ApplyScreenPostProcessor.class";
    private static final String DOWNFALL_MOD_ID = "downfall";
    private static final String DOWNFALL_FBO_PATCH_JAR = "downfall-fbo-compat.jar";
    private static final String DOWNFALL_FBO_PATCH_CLASS =
            "collector/util/DoubleEnergyOrb.class";
    private static final String DOWNFALL_NPC_FBO_PATCH_CLASS =
            "downfall/vfx/CustomAnimatedNPC.class";
    private static final String STSLIB_MAIN_CLASS =
            "com/evacipated/cardcrawl/mod/stslib/StSLib.class";
    private static final String COMPAT_LOG_PREFIX = "[compat] ";
    private static final Set<String> REQUIRED_STS_PATCH_CLASSES = new HashSet<>(Arrays.asList(
            "com/badlogic/gdx/backends/lwjgl/LwjglGraphics.class",
            STS_PATCH_PIXEL_SCALE_CLASS,
            STS_PATCH_LWJGL_INPUT_CLASS,
            STS_PATCH_LWJGL_NATIVES_CLASS,
            STS_PATCH_SHARED_LOADER_CLASS,
            STS_PATCH_STEAM_UTILS_CLASS,
            STS_PATCH_STEAM_UTILS_ENUM_CLASS,
            STS_PATCH_STEAM_INPUT_HELPER_CLASS,
            STS_PATCH_GL_FRAMEBUFFER_CLASS,
            STS_PATCH_DESKTOP_CONTROLLER_MANAGER_CLASS,
            STS_PATCH_DESKTOP_CONTROLLER_MANAGER_DIRECT_CLASS,
            STS_PATCH_DESKTOP_CONTROLLER_MANAGER_POLL_CLASS,
            STS_PATCH_BUILD_PROPERTIES
    ));
    private static final CompatPatchRule[] COMPAT_PATCH_RULES = new CompatPatchRule[]{
            new CompatPatchRule(
                    BASEMOD_MOD_ID,
                    BASEMOD_GLOW_PATCH_JAR,
                    BASEMOD_GLOW_PATCH_CLASS,
                    "BaseMod glow",
                    false,
                    BASEMOD_JAR_FILE_NAME
            ),
            new CompatPatchRule(
                    BASEMOD_MOD_ID,
                    BASEMOD_POSTPROCESS_PATCH_JAR,
                    BASEMOD_POSTPROCESS_PATCH_CLASS,
                    "BaseMod postprocess FBO",
                    false,
                    BASEMOD_JAR_FILE_NAME
            ),
            new CompatPatchRule(
                    DOWNFALL_MOD_ID,
                    DOWNFALL_FBO_PATCH_JAR,
                    DOWNFALL_FBO_PATCH_CLASS,
                    "Downfall FBO",
                    true,
                    null
            ),
            new CompatPatchRule(
                    DOWNFALL_MOD_ID,
                    DOWNFALL_FBO_PATCH_JAR,
                    DOWNFALL_NPC_FBO_PATCH_CLASS,
                    "Downfall NPC portal FBO",
                    true,
                    null
            )
    };

    private enum CompatPatchApplyResult {
        PATCHED,
        ALREADY_PATCHED
    }

    private static final class CompatPatchRule {
        final String modId;
        final String patchJarName;
        final String targetClassEntry;
        final String label;
        final boolean applyWhenInstalled;
        final String fixedTargetJarName;

        CompatPatchRule(String modId,
                        String patchJarName,
                        String targetClassEntry,
                        String label,
                        boolean applyWhenInstalled,
                        String fixedTargetJarName) {
            this.modId = modId;
            this.patchJarName = patchJarName;
            this.targetClassEntry = targetClassEntry;
            this.label = label;
            this.applyWhenInstalled = applyWhenInstalled;
            this.fixedTargetJarName = fixedTargetJarName;
        }
    }

    public static final class ModManifestInfo {
        public final String modId;
        public final String normalizedModId;
        public final String name;
        public final String version;
        public final String description;
        public final List<String> dependencies;

        private ModManifestInfo(String modId,
                                String normalizedModId,
                                String name,
                                String version,
                                String description,
                                List<String> dependencies) {
            this.modId = modId;
            this.normalizedModId = normalizedModId;
            this.name = name;
            this.version = version;
            this.description = description;
            this.dependencies = dependencies == null ? new ArrayList<>() : dependencies;
        }
    }

    private ModJarSupport() {
    }

    public static void validateMtsJar(File jarFile) throws IOException {
        if (jarFile == null || !jarFile.isFile()) {
            throw new IOException("ModTheSpire.jar not found");
        }
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            ZipEntry loader = zipFile.getEntry("com/evacipated/cardcrawl/modthespire/Loader.class");
            if (loader == null) {
                throw new IOException("Invalid ModTheSpire.jar: missing Loader class");
            }
        }
    }

    public static void validateBaseModJar(File jarFile) throws IOException {
        if (jarFile == null || !jarFile.isFile()) {
            throw new IOException("BaseMod.jar not found");
        }
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            if (zipFile.getEntry("basemod/BaseMod.class") == null) {
                throw new IOException("Invalid BaseMod.jar: missing basemod/BaseMod.class");
            }
        }
        String modId = normalizeModId(resolveModId(jarFile));
        if (!ModManager.MOD_ID_BASEMOD.equals(modId)) {
            throw new IOException("Invalid BaseMod.jar: modid is " + modId);
        }
    }

    public static void validateStsLibJar(File jarFile) throws IOException {
        if (jarFile == null || !jarFile.isFile()) {
            throw new IOException("StSLib.jar not found");
        }
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            if (zipFile.getEntry(STSLIB_MAIN_CLASS) == null) {
                throw new IOException("Invalid StSLib.jar: missing " + STSLIB_MAIN_CLASS);
            }
        }
        String modId = normalizeModId(resolveModId(jarFile));
        if (!ModManager.MOD_ID_STSLIB.equals(modId)) {
            throw new IOException("Invalid StSLib.jar: modid is " + modId);
        }
    }

    public static ModManifestInfo readModManifest(File modJar) throws IOException {
        if (modJar == null || !modJar.isFile()) {
            throw new IOException("Mod jar not found");
        }
        try (ZipFile zipFile = new ZipFile(modJar)) {
            ZipEntry modInfo = findEntryIgnoreCase(zipFile, "ModTheSpire.json");
            if (modInfo == null) {
                throw new IOException("ModTheSpire.json not found in " + modJar.getName());
            }
            String json = readEntry(zipFile, modInfo);
            ModManifestInfo manifest = parseManifest(json);
            if (manifest == null || manifest.normalizedModId.isEmpty()) {
                throw new IOException("modid not found in " + modJar.getName());
            }
            return manifest;
        }
    }

    public static String resolveModId(File modJar) throws IOException {
        return readModManifest(modJar).modId;
    }

    public static void prepareMtsClasspath(Context context) throws IOException {
        File stsJar = RuntimePaths.importedStsJar(context);
        File patchJar = RuntimePaths.gdxPatchJar(context);
        File baseModJar = RuntimePaths.importedBaseModJar(context);
        appendCompatLog(context, "prepare classpath start");
        appendCompatDiagnostics(context, "prepare_start");
        ensurePatchedStsJar(stsJar, patchJar);
        applyCompatPatchRules(context);
        ensureGdxApiJar(stsJar, RuntimePaths.mtsGdxApiJar(context));
        ensureStsResourceJar(stsJar, RuntimePaths.mtsStsResourcesJar(context));
        ensureBaseModResourceJar(baseModJar, RuntimePaths.mtsBaseModResourcesJar(context));
        appendCompatDiagnostics(context, "prepare_done");
        appendCompatLog(context, "prepare classpath done");
    }

    static void appendCompatDiagnosticSnapshot(Context context, String stage) {
        appendCompatDiagnostics(context, stage);
    }

    private static void ensurePatchedStsJar(File stsJar, File patchJar) throws IOException {
        if (stsJar == null || !stsJar.isFile()) {
            throw new IOException("desktop-1.0.jar not found");
        }
        if (patchJar == null || !patchJar.isFile()) {
            throw new IOException("gdx-patch.jar not found");
        }

        Map<String, byte[]> patchEntries = loadPatchClassEntries(patchJar);
        if (!patchEntries.keySet().containsAll(REQUIRED_STS_PATCH_CLASSES)) {
            throw new IOException("gdx-patch.jar is missing required patched classes");
        }
        if (isStsPatched(stsJar, patchEntries)) {
            return;
        }

        File tempJar = new File(stsJar.getAbsolutePath() + ".patching.tmp");
        Set<String> seenNames = new HashSet<>();
        try (FileInputStream fileInput = new FileInputStream(stsJar);
             ZipInputStream zipIn = new ZipInputStream(fileInput);
             FileOutputStream outputStream = new FileOutputStream(tempJar, false);
             ZipOutputStream zipOut = new ZipOutputStream(outputStream)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !seenNames.add(name)) {
                    zipIn.closeEntry();
                    continue;
                }
                ZipEntry outEntry = new ZipEntry(name);
                if (entry.getTime() > 0) {
                    outEntry.setTime(entry.getTime());
                }
                zipOut.putNextEntry(outEntry);
                byte[] patchBytes = patchEntries.get(name);
                if (patchBytes != null) {
                    zipOut.write(patchBytes);
                } else {
                    copyStream(zipIn, zipOut);
                }
                zipOut.closeEntry();
                zipIn.closeEntry();
            }

            for (Map.Entry<String, byte[]> patchEntry : patchEntries.entrySet()) {
                String name = patchEntry.getKey();
                if (seenNames.contains(name)) {
                    continue;
                }
                ZipEntry outEntry = new ZipEntry(name);
                zipOut.putNextEntry(outEntry);
                zipOut.write(patchEntry.getValue());
                zipOut.closeEntry();
            }
        }

        if (!isStsPatched(tempJar, patchEntries)) {
            if (tempJar.exists()) {
                tempJar.delete();
            }
            throw new IOException("Failed to patch desktop-1.0.jar with gdx-patch classes");
        }

        if (stsJar.exists() && !stsJar.delete()) {
            throw new IOException("Failed to replace " + stsJar.getAbsolutePath());
        }
        if (!tempJar.renameTo(stsJar)) {
            throw new IOException("Failed to move " + tempJar.getAbsolutePath() + " -> " + stsJar.getAbsolutePath());
        }
        stsJar.setLastModified(System.currentTimeMillis());
    }

    private static Map<String, byte[]> loadPatchClassEntries(File patchJar) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (FileInputStream fileInput = new FileInputStream(patchJar);
             ZipInputStream zipIn = new ZipInputStream(fileInput)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()
                        || !shouldPatchStsEntry(name)
                        || name.startsWith("META-INF/")
                        || entries.containsKey(name)) {
                    zipIn.closeEntry();
                    continue;
                }
                entries.put(name, readAll(zipIn));
                zipIn.closeEntry();
            }
        }
        return entries;
    }

    private static boolean shouldPatchStsEntry(String entryName) {
        return STS_PATCH_BUILD_PROPERTIES.equals(entryName)
                || STS_PATCH_PIXEL_SCALE_CLASS.equals(entryName)
                || STS_PATCH_LWJGL_NATIVES_CLASS.equals(entryName)
                || STS_PATCH_SHARED_LOADER_CLASS.equals(entryName)
                || STS_PATCH_STEAM_UTILS_CLASS.equals(entryName)
                || STS_PATCH_STEAM_UTILS_ENUM_CLASS.equals(entryName)
                || STS_PATCH_STEAM_INPUT_HELPER_CLASS.equals(entryName)
                || STS_PATCH_GL_FRAMEBUFFER_CLASS.equals(entryName)
                || entryName.startsWith(STS_PATCH_DESKTOP_CONTROLLER_MANAGER_PREFIX)
                || entryName.startsWith(STS_PATCH_LWJGL_GRAPHICS_PREFIX)
                || entryName.startsWith(GDX_BRIDGE_LWJGL_INPUT_PREFIX);
    }

    private static boolean isStsPatched(File stsJar, Map<String, byte[]> patchEntries) {
        try (ZipFile zipFile = new ZipFile(stsJar)) {
            for (String className : REQUIRED_STS_PATCH_CLASSES) {
                ZipEntry entry = zipFile.getEntry(className);
                byte[] expected = patchEntries.get(className);
                if (entry == null || expected == null) {
                    return false;
                }
                byte[] actual = readEntryBytes(zipFile, entry);
                if (!Arrays.equals(actual, expected)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void applyCompatPatchRules(Context context) throws IOException {
        Map<String, File> installedModsById = findInstalledModsById(context);
        for (CompatPatchRule rule : COMPAT_PATCH_RULES) {
            boolean ruleEnabled = isCompatRuleEnabled(context, rule);
            if (rule.applyWhenInstalled) {
                File targetJar = installedModsById.get(rule.modId);
                if (targetJar == null) {
                    appendCompatLog(context, "rule skip not installed: " + rule.label + " (modid=" + rule.modId + ")");
                    continue;
                }
                if (!ruleEnabled) {
                    appendCompatLog(context, "rule disabled by user setting: " + rule.label);
                    tryRestoreCompatRule(context, rule, targetJar);
                    continue;
                }
                applyCompatRuleToJar(context, rule, targetJar);
            } else {
                if (!ruleEnabled) {
                    appendCompatLog(context, "rule disabled by user setting: " + rule.label);
                    continue;
                }
                File targetJar = resolveFixedTargetJar(context, rule);
                if (targetJar == null || !targetJar.isFile()) {
                    appendCompatLog(context, "rule missing required target: " + rule.label + " (jar="
                            + (targetJar == null ? "null" : targetJar.getAbsolutePath()) + ")");
                    throw new IOException(rule.label + " target jar not found");
                }
                applyCompatRuleToJar(context, rule, targetJar);
            }
        }
    }

    private static boolean isCompatRuleEnabled(Context context, CompatPatchRule rule) {
        if (context == null || rule == null) {
            return true;
        }
        if (DOWNFALL_MOD_ID.equals(rule.modId)
                && DOWNFALL_FBO_PATCH_JAR.equals(rule.patchJarName)) {
            return CompatibilitySettings.isDownfallFboPatchEnabled(context);
        }
        return true;
    }

    private static void applyCompatRuleToJar(Context context, CompatPatchRule rule, File targetJar) throws IOException {
        File patchJar = new File(RuntimePaths.gdxPatchDir(context), rule.patchJarName);
        appendCompatLog(context, "rule matched: " + rule.label + " target=" + targetJar.getName()
                + " class=" + rule.targetClassEntry);
        tryBackupCompatRule(context, rule, targetJar, patchJar);
        try {
            CompatPatchApplyResult result = ensureJarClassCompat(targetJar, patchJar, rule.targetClassEntry, rule.label);
            if (result == CompatPatchApplyResult.ALREADY_PATCHED) {
                appendCompatLog(context, "rule already patched: " + rule.label);
            } else {
                appendCompatLog(context, "rule patched successfully: " + rule.label);
            }
        } catch (Throwable error) {
            appendCompatLog(context, "rule failed: " + rule.label + " reason="
                    + error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException(rule.label + " compat apply failed", error);
        }
    }

    private static void tryBackupCompatRule(Context context,
                                            CompatPatchRule rule,
                                            File targetJar,
                                            File patchJar) {
        if (!isDownfallFboRule(rule) || targetJar == null || !targetJar.isFile()) {
            return;
        }
        try {
            File backupFile = resolveCompatRuleBackupFile(rule, targetJar);
            if (backupFile == null) {
                return;
            }
            if (backupFile.isFile() && backupFile.length() > 0) {
                appendCompatLog(context, "rule backup keep existing: " + rule.label
                        + " -> " + backupFile.getName());
                return;
            }
            Map<String, byte[]> patchEntries = loadSinglePatchEntry(patchJar, rule.targetClassEntry, rule.label);
            if (isJarClassCompatPatched(targetJar, rule.targetClassEntry, patchEntries)) {
                appendCompatLog(context, "rule backup skip (already patched): " + rule.label);
                return;
            }
            copyFileReplacing(targetJar, backupFile);
            appendCompatLog(context, "rule backup updated: " + rule.label + " -> " + backupFile.getName());
        } catch (Throwable error) {
            appendCompatLog(context, "rule backup failed: " + rule.label + " reason="
                    + error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
        }
    }

    private static void tryRestoreCompatRule(Context context,
                                             CompatPatchRule rule,
                                             File targetJar) {
        if (!isDownfallFboRule(rule) || targetJar == null || !targetJar.isFile()) {
            return;
        }
        File patchJar = new File(RuntimePaths.gdxPatchDir(context), rule.patchJarName);
        if (!patchJar.isFile()) {
            appendCompatLog(context, "rule restore skip, patch jar missing: " + patchJar.getAbsolutePath());
            return;
        }
        try {
            Map<String, byte[]> patchEntries = loadSinglePatchEntry(patchJar, rule.targetClassEntry, rule.label);
            if (!isJarClassCompatPatched(targetJar, rule.targetClassEntry, patchEntries)) {
                appendCompatLog(context, "rule restore skip (target not patched): " + rule.label);
                return;
            }
            File backupFile = resolveCompatRuleBackupFile(rule, targetJar);
            if (backupFile == null || !backupFile.isFile()) {
                appendCompatLog(context, "rule restore skip, backup missing: " + rule.label);
                return;
            }
            if (isJarClassCompatPatched(backupFile, rule.targetClassEntry, patchEntries)) {
                appendCompatLog(context, "rule restore skip, backup already patched: " + rule.label);
                return;
            }
            copyFileReplacing(backupFile, targetJar);
            appendCompatLog(context, "rule restored from backup: " + rule.label);
        } catch (Throwable error) {
            appendCompatLog(context, "rule restore failed: " + rule.label + " reason="
                    + error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
        }
    }

    private static boolean isDownfallFboRule(CompatPatchRule rule) {
        if (rule == null) {
            return false;
        }
        if (!DOWNFALL_MOD_ID.equals(rule.modId)
                || !DOWNFALL_FBO_PATCH_JAR.equals(rule.patchJarName)) {
            return false;
        }
        return DOWNFALL_FBO_PATCH_CLASS.equals(rule.targetClassEntry)
                || DOWNFALL_NPC_FBO_PATCH_CLASS.equals(rule.targetClassEntry);
    }

    private static File resolveCompatRuleBackupFile(CompatPatchRule rule, File targetJar) {
        if (!isDownfallFboRule(rule) || targetJar == null) {
            return null;
        }
        return new File(targetJar.getAbsolutePath() + ".amethyst.downfall_fbo.backup");
    }

    private static File resolveFixedTargetJar(Context context, CompatPatchRule rule) {
        if (rule.fixedTargetJarName == null || rule.fixedTargetJarName.trim().isEmpty()) {
            return null;
        }
        return new File(RuntimePaths.modsDir(context), rule.fixedTargetJarName);
    }

    private static Map<String, File> findInstalledModsById(Context context) {
        Map<String, File> modsById = new HashMap<>();
        File[] modFiles = RuntimePaths.modsDir(context).listFiles();
        if (modFiles == null) {
            return modsById;
        }
        for (File modFile : modFiles) {
            if (modFile == null || !modFile.isFile()) {
                continue;
            }
            String name = modFile.getName();
            if (name == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            try {
                String modId = normalizeModId(resolveModId(modFile));
                if (!modId.isEmpty() && !modsById.containsKey(modId)) {
                    modsById.put(modId, modFile);
                }
            } catch (Throwable ignored) {
            }
        }
        return modsById;
    }

    private static CompatPatchApplyResult ensureJarClassCompat(File targetJar,
                                                               File patchJar,
                                                               String classEntry,
                                                               String label) throws IOException {
        if (targetJar == null || !targetJar.isFile()) {
            throw new IOException(label + " target jar not found");
        }
        if (patchJar == null || !patchJar.isFile()) {
            throw new IOException(label + " compat patch not found");
        }

        Map<String, byte[]> patchEntries = loadSinglePatchEntry(patchJar, classEntry, label);
        if (isJarClassCompatPatched(targetJar, classEntry, patchEntries)) {
            return CompatPatchApplyResult.ALREADY_PATCHED;
        }

        File tempJar = new File(targetJar.getAbsolutePath() + ".compat.tmp");
        Set<String> seenNames = new HashSet<>();
        try (FileInputStream fileInput = new FileInputStream(targetJar);
             ZipInputStream zipIn = new ZipInputStream(fileInput);
             FileOutputStream outputStream = new FileOutputStream(tempJar, false);
             ZipOutputStream zipOut = new ZipOutputStream(outputStream)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !seenNames.add(name)) {
                    zipIn.closeEntry();
                    continue;
                }
                ZipEntry outEntry = new ZipEntry(name);
                if (entry.getTime() > 0) {
                    outEntry.setTime(entry.getTime());
                }
                zipOut.putNextEntry(outEntry);
                byte[] patchBytes = patchEntries.get(name);
                if (patchBytes != null) {
                    zipOut.write(patchBytes);
                } else {
                    copyStream(zipIn, zipOut);
                }
                zipOut.closeEntry();
                zipIn.closeEntry();
            }

            for (Map.Entry<String, byte[]> patchEntry : patchEntries.entrySet()) {
                String name = patchEntry.getKey();
                if (seenNames.contains(name)) {
                    continue;
                }
                ZipEntry outEntry = new ZipEntry(name);
                zipOut.putNextEntry(outEntry);
                zipOut.write(patchEntry.getValue());
                zipOut.closeEntry();
            }
        }

        if (!isJarClassCompatPatched(tempJar, classEntry, patchEntries)) {
            if (tempJar.exists()) {
                tempJar.delete();
            }
            throw new IOException("Failed to patch " + label + " class: " + classEntry);
        }

        if (targetJar.exists() && !targetJar.delete()) {
            throw new IOException("Failed to replace " + targetJar.getAbsolutePath());
        }
        if (!tempJar.renameTo(targetJar)) {
            throw new IOException("Failed to move " + tempJar.getAbsolutePath() + " -> " + targetJar.getAbsolutePath());
        }
        targetJar.setLastModified(System.currentTimeMillis());
        return CompatPatchApplyResult.PATCHED;
    }

    private static Map<String, byte[]> loadSinglePatchEntry(File patchJar,
                                                            String requiredClassEntry,
                                                            String label) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (FileInputStream fileInput = new FileInputStream(patchJar);
             ZipInputStream zipIn = new ZipInputStream(fileInput)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()
                        || name.startsWith("META-INF/")
                        || !requiredClassEntry.equals(name)
                        || entries.containsKey(name)) {
                    zipIn.closeEntry();
                    continue;
                }
                entries.put(name, readAll(zipIn));
                zipIn.closeEntry();
            }
        }
        if (!entries.containsKey(requiredClassEntry)) {
            throw new IOException(label + " compat patch is missing required class: " + requiredClassEntry);
        }
        return entries;
    }

    private static boolean isJarClassCompatPatched(File targetJar,
                                                   String classEntry,
                                                   Map<String, byte[]> patchEntries) {
        try (ZipFile zipFile = new ZipFile(targetJar)) {
            ZipEntry entry = zipFile.getEntry(classEntry);
            byte[] expected = patchEntries.get(classEntry);
            if (entry == null || expected == null) {
                return false;
            }
            byte[] actual = readEntryBytes(zipFile, entry);
            return Arrays.equals(actual, expected);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void appendCompatLog(Context context, String message) {
        if (context == null || message == null) {
            return;
        }
        try {
            File logFile = RuntimePaths.latestLog(context);
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (!logFile.exists()) {
                logFile.createNewFile();
            }
            String line = COMPAT_LOG_PREFIX + message + "\n";
            try (FileOutputStream output = new FileOutputStream(logFile, true)) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void appendCompatDiagnostics(Context context, String stage) {
        if (context == null) {
            return;
        }
        String safeStage = normalizeDiagStage(stage);
        try {
            appendCompatLog(context, "diag[" + safeStage + "] setting original_fbo="
                    + CompatibilitySettings.isOriginalFboPatchEnabled(context)
                    + ", downfall_fbo="
                    + CompatibilitySettings.isDownfallFboPatchEnabled(context));
        } catch (Throwable error) {
            appendCompatLog(context, "diag[" + safeStage + "] setting read failed: "
                    + error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
        }
        try {
            Map<String, File> installedMods = findInstalledModsById(context);
            appendCompatLog(context, "diag[" + safeStage + "] installed mods=" + installedMods.keySet());
            appendClassPatchStatus(
                    context,
                    safeStage,
                    "STS GLFrameBuffer",
                    RuntimePaths.importedStsJar(context),
                    RuntimePaths.gdxPatchJar(context),
                    STS_PATCH_GL_FRAMEBUFFER_CLASS
            );
            File baseModJar = RuntimePaths.importedBaseModJar(context);
            appendClassPatchStatus(
                    context,
                    safeStage,
                    "BaseMod glow",
                    baseModJar,
                    new File(RuntimePaths.gdxPatchDir(context), BASEMOD_GLOW_PATCH_JAR),
                    BASEMOD_GLOW_PATCH_CLASS
            );
            appendClassPatchStatus(
                    context,
                    safeStage,
                    "BaseMod ApplyScreenPostProcessor",
                    baseModJar,
                    new File(RuntimePaths.gdxPatchDir(context), BASEMOD_POSTPROCESS_PATCH_JAR),
                    BASEMOD_POSTPROCESS_PATCH_CLASS
            );
            File downfallJar = installedMods.get(DOWNFALL_MOD_ID);
            if (downfallJar == null) {
                appendCompatLog(context, "diag[" + safeStage + "] Downfall not installed (modid=" + DOWNFALL_MOD_ID + ")");
            } else {
                File downfallPatchJar = new File(RuntimePaths.gdxPatchDir(context), DOWNFALL_FBO_PATCH_JAR);
                appendClassPatchStatus(
                        context,
                        safeStage,
                        "Downfall DoubleEnergyOrb",
                        downfallJar,
                        downfallPatchJar,
                        DOWNFALL_FBO_PATCH_CLASS
                );
                appendClassPatchStatus(
                        context,
                        safeStage,
                        "Downfall CustomAnimatedNPC",
                        downfallJar,
                        downfallPatchJar,
                        DOWNFALL_NPC_FBO_PATCH_CLASS
                );
                File backupFile = new File(downfallJar.getAbsolutePath() + ".amethyst.downfall_fbo.backup");
                appendCompatLog(context, "diag[" + safeStage + "] Downfall backup exists=" + backupFile.isFile()
                        + ", file=" + backupFile.getName());
            }
        } catch (Throwable error) {
            appendCompatLog(context, "diag[" + safeStage + "] failed: "
                    + error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
        }
    }

    private static String normalizeDiagStage(String stage) {
        if (stage == null) {
            return "unknown";
        }
        String normalized = stage.trim();
        return normalized.isEmpty() ? "unknown" : normalized;
    }

    private static void appendClassPatchStatus(Context context,
                                               String stage,
                                               String label,
                                               File targetJar,
                                               File patchJar,
                                               String classEntry) {
        if (targetJar == null || !targetJar.isFile()) {
            appendCompatLog(context, "diag[" + stage + "] " + label + " target missing: "
                    + (targetJar == null ? "null" : targetJar.getAbsolutePath()));
            return;
        }
        if (patchJar == null || !patchJar.isFile()) {
            appendCompatLog(context, "diag[" + stage + "] " + label + " patch missing: "
                    + (patchJar == null ? "null" : patchJar.getAbsolutePath()));
            return;
        }

        byte[] targetBytes = readJarEntryBytes(targetJar, classEntry);
        byte[] patchBytes = readJarEntryBytes(patchJar, classEntry);
        boolean patched = targetBytes != null && patchBytes != null && Arrays.equals(targetBytes, patchBytes);
        appendCompatLog(context, "diag[" + stage + "] " + label
                + " patched=" + patched
                + ", targetJar=" + targetJar.getName()
                + ", targetSize=" + (targetBytes == null ? -1 : targetBytes.length)
                + ", targetHash=" + digestShort(targetBytes)
                + ", patchJar=" + patchJar.getName()
                + ", patchSize=" + (patchBytes == null ? -1 : patchBytes.length)
                + ", patchHash=" + digestShort(patchBytes)
                + ", class=" + classEntry);
    }

    private static byte[] readJarEntryBytes(File jarFile, String entryName) {
        if (jarFile == null || entryName == null || entryName.trim().isEmpty()) {
            return null;
        }
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                return null;
            }
            return readEntryBytes(zipFile, entry);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String digestShort(byte[] data) {
        if (data == null || data.length == 0) {
            return "missing";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder text = new StringBuilder(24);
            for (int i = 0; i < hash.length && i < 6; i++) {
                int value = hash[i] & 0xFF;
                if (value < 0x10) {
                    text.append('0');
                }
                text.append(Integer.toHexString(value));
            }
            return text.toString();
        } catch (Throwable ignored) {
            return "hash_error";
        }
    }

    private static void ensureStsResourceJar(File stsJar, File targetJar) throws IOException {
        ensureResourceJar(stsJar, targetJar, STS_RESOURCE_SENTINEL, "STS");
    }

    private static void ensureBaseModResourceJar(File baseModJar, File targetJar) throws IOException {
        ensureResourceJar(baseModJar, targetJar, BASEMOD_RESOURCE_SENTINEL, "BaseMod");
    }

    private static void ensureResourceJar(File sourceJar, File targetJar, String requiredEntry, String label) throws IOException {
        if (sourceJar == null || !sourceJar.isFile()) {
            throw new IOException(label + " jar not found");
        }
        if (targetJar.isFile()
                && targetJar.length() > 0
                && targetJar.lastModified() >= sourceJar.lastModified()
                && hasRequiredResource(targetJar, requiredEntry)) {
            return;
        }

        File parent = targetJar.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
        }

        File tempJar = new File(targetJar.getAbsolutePath() + ".tmp");
        int copiedEntries = 0;
        Set<String> seenNames = new HashSet<>();
        try (FileInputStream fileInput = new FileInputStream(sourceJar);
             ZipInputStream zipIn = new ZipInputStream(fileInput);
             FileOutputStream outputStream = new FileOutputStream(tempJar, false);
             ZipOutputStream zipOut = new ZipOutputStream(outputStream)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()
                        || name.startsWith("META-INF/")
                        || name.endsWith(".class")
                        || !seenNames.add(name)) {
                    zipIn.closeEntry();
                    continue;
                }
                ZipEntry outEntry = new ZipEntry(name);
                if (entry.getTime() > 0) {
                    outEntry.setTime(entry.getTime());
                }
                zipOut.putNextEntry(outEntry);
                copyStream(zipIn, zipOut);
                zipOut.closeEntry();
                zipIn.closeEntry();
                copiedEntries++;
            }
        }

        if (copiedEntries == 0 || !hasRequiredResource(tempJar, requiredEntry)) {
            if (tempJar.exists()) {
                tempJar.delete();
            }
            throw new IOException("Failed to build " + label + " resources classpath jar");
        }

        if (targetJar.exists() && !targetJar.delete()) {
            throw new IOException("Failed to replace " + targetJar.getAbsolutePath());
        }
        if (!tempJar.renameTo(targetJar)) {
            throw new IOException("Failed to move " + tempJar.getAbsolutePath() + " -> " + targetJar.getAbsolutePath());
        }
        targetJar.setLastModified(sourceJar.lastModified());
    }

    private static boolean hasRequiredResource(File jarFile, String requiredEntry) {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            return zipFile.getEntry(requiredEntry) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void ensureGdxApiJar(File stsJar, File targetJar) throws IOException {
        if (stsJar == null || !stsJar.isFile()) {
            throw new IOException("desktop-1.0.jar not found");
        }
        if (targetJar.isFile()
                && targetJar.length() > 0
                && targetJar.lastModified() >= stsJar.lastModified()
                && hasRequiredGdxApi(targetJar)) {
            return;
        }

        File parent = targetJar.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
        }

        File tempJar = new File(targetJar.getAbsolutePath() + ".tmp");
        int copiedClasses = 0;
        Set<String> seenNames = new HashSet<>();
        try (FileInputStream fileInput = new FileInputStream(stsJar);
             ZipInputStream zipIn = new ZipInputStream(fileInput);
             FileOutputStream outputStream = new FileOutputStream(tempJar, false);
             ZipOutputStream zipOut = new ZipOutputStream(outputStream)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()
                        || !name.endsWith(".class")
                        || !REQUIRED_GDX_CLASSES.contains(name)
                        || !seenNames.add(name)) {
                    zipIn.closeEntry();
                    continue;
                }

                ZipEntry outEntry = new ZipEntry(name);
                if (entry.getTime() > 0) {
                    outEntry.setTime(entry.getTime());
                }
                zipOut.putNextEntry(outEntry);
                copyStream(zipIn, zipOut);
                zipOut.closeEntry();
                zipIn.closeEntry();
                copiedClasses++;
            }
        }

        if (copiedClasses == 0 || !hasRequiredGdxApi(tempJar)) {
            if (tempJar.exists()) {
                tempJar.delete();
            }
            throw new IOException("No gdx classes found in desktop-1.0.jar");
        }

        if (targetJar.exists() && !targetJar.delete()) {
            throw new IOException("Failed to replace " + targetJar.getAbsolutePath());
        }
        if (!tempJar.renameTo(targetJar)) {
            throw new IOException("Failed to move " + tempJar.getAbsolutePath() + " -> " + targetJar.getAbsolutePath());
        }
        targetJar.setLastModified(stsJar.lastModified());
    }

    private static boolean hasRequiredGdxApi(File jarFile) {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            Set<String> foundRequired = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (REQUIRED_GDX_CLASSES.contains(name)) {
                    foundRequired.add(name);
                    continue;
                }
                if (name.startsWith(GDX_BACKEND_PREFIX)
                        && name.endsWith(".class")
                        && !ALLOWED_PARENT_BACKEND_CLASSES.contains(name)) {
                    return false;
                }
            }
            return foundRequired.containsAll(REQUIRED_GDX_CLASSES);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void ensureGdxBridgeJar(File sourcePatchJar, File targetJar) throws IOException {
        if (sourcePatchJar == null || !sourcePatchJar.isFile()) {
            throw new IOException("gdx-patch.jar not found");
        }
        if (targetJar.isFile()
                && targetJar.length() > 0
                && targetJar.lastModified() >= sourcePatchJar.lastModified()
                && hasRequiredGdxBridge(targetJar)) {
            return;
        }

        File parent = targetJar.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
        }

        File tempJar = new File(targetJar.getAbsolutePath() + ".tmp");
        int copiedClasses = 0;
        Set<String> seenNames = new HashSet<>();
        try (FileInputStream fileInput = new FileInputStream(sourcePatchJar);
             ZipInputStream zipIn = new ZipInputStream(fileInput);
             FileOutputStream outputStream = new FileOutputStream(tempJar, false);
             ZipOutputStream zipOut = new ZipOutputStream(outputStream)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()
                        || (!GDX_BRIDGE_CLASSES.contains(name)
                        && !(name.startsWith(GDX_BRIDGE_LWJGL_INPUT_PREFIX) && name.endsWith(".class")))
                        || !seenNames.add(name)) {
                    zipIn.closeEntry();
                    continue;
                }

                ZipEntry outEntry = new ZipEntry(name);
                if (entry.getTime() > 0) {
                    outEntry.setTime(entry.getTime());
                }
                zipOut.putNextEntry(outEntry);
                copyStream(zipIn, zipOut);
                zipOut.closeEntry();
                zipIn.closeEntry();
                copiedClasses++;
            }
        }

        if (copiedClasses < GDX_BRIDGE_CLASSES.size() || !hasRequiredGdxBridge(tempJar)) {
            if (tempJar.exists()) {
                tempJar.delete();
            }
            throw new IOException("Failed to prepare MTS gdx bridge jar");
        }

        if (targetJar.exists() && !targetJar.delete()) {
            throw new IOException("Failed to replace " + targetJar.getAbsolutePath());
        }
        if (!tempJar.renameTo(targetJar)) {
            throw new IOException("Failed to move " + tempJar.getAbsolutePath() + " -> " + targetJar.getAbsolutePath());
        }
        targetJar.setLastModified(sourcePatchJar.lastModified());
    }

    private static boolean hasRequiredGdxBridge(File jarFile) {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            Set<String> found = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (GDX_BRIDGE_CLASSES.contains(name)) {
                    found.add(name);
                }
            }
            return found.containsAll(GDX_BRIDGE_CLASSES);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ModManifestInfo parseManifest(String json) {
        if (json == null) {
            return null;
        }
        JSONObject object = tryParseManifestObject(json);
        String modId = readManifestText(object, json, MOD_ID_JSON_KEYS, MOD_ID_PATTERN);
        String normalizedModId = normalizeModId(modId);
        if (normalizedModId.isEmpty()) {
            return null;
        }

        String resolvedModId = sanitizeManifestText(modId);
        if (resolvedModId.isEmpty()) {
            resolvedModId = normalizedModId;
        }
        String name = readManifestText(object, json, MOD_NAME_JSON_KEYS, MOD_NAME_PATTERN);
        String version = readManifestText(object, json, MOD_VERSION_JSON_KEYS, MOD_VERSION_PATTERN);
        String description = readManifestText(object, json, MOD_DESCRIPTION_JSON_KEYS, MOD_DESCRIPTION_PATTERN);
        List<String> dependencies = readManifestDependencies(object);

        String resolvedName = sanitizeManifestText(name);
        if (resolvedName.isEmpty()) {
            resolvedName = resolvedModId;
        }
        return new ModManifestInfo(
                resolvedModId,
                normalizedModId,
                resolvedName,
                sanitizeManifestText(version),
                sanitizeManifestText(description),
                dependencies
        );
    }

    private static List<String> readManifestDependencies(JSONObject object) {
        if (object == null) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> dependencies = new LinkedHashSet<>();
        for (String key : MOD_DEPENDENCIES_JSON_KEYS) {
            if (key == null || key.isEmpty()) {
                continue;
            }
            if (object.has(key) && !object.isNull(key)) {
                addManifestDependenciesFromValue(object.opt(key), dependencies);
            }
            String matchedKey = findJsonKeyIgnoreCase(object, key);
            if (matchedKey != null && !matchedKey.equals(key) && object.has(matchedKey) && !object.isNull(matchedKey)) {
                addManifestDependenciesFromValue(object.opt(matchedKey), dependencies);
            }
        }
        return new ArrayList<>(dependencies);
    }

    private static void addManifestDependenciesFromValue(Object value, Set<String> output) {
        if (output == null || value == null || value == JSONObject.NULL) {
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                addManifestDependenciesFromValue(array.opt(i), output);
            }
            return;
        }
        if (value instanceof JSONObject) {
            return;
        }
        String text = sanitizeManifestText(String.valueOf(value));
        if (text.isEmpty()) {
            return;
        }
        String[] parts = text.split("[,;\\n]");
        for (String part : parts) {
            String normalized = sanitizeManifestText(part);
            if (!normalized.isEmpty()) {
                output.add(normalized);
            }
        }
    }

    private static JSONObject tryParseManifestObject(String json) {
        try {
            Object root = new JSONTokener(json).nextValue();
            if (root instanceof JSONObject) {
                return (JSONObject) root;
            }
            if (root instanceof JSONArray) {
                JSONArray array = (JSONArray) root;
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.opt(i);
                    if (item instanceof JSONObject) {
                        return (JSONObject) item;
                    }
                }
            }
        } catch (JSONException ignored) {
        }
        return null;
    }

    private static String readManifestText(JSONObject object,
                                           String rawJson,
                                           String[] jsonKeys,
                                           Pattern fallbackPattern) {
        if (object != null && jsonKeys != null) {
            for (String key : jsonKeys) {
                String direct = readJsonFieldAsText(object, key);
                if (!direct.isEmpty()) {
                    return direct;
                }
            }
            for (String key : jsonKeys) {
                String matchedKey = findJsonKeyIgnoreCase(object, key);
                if (matchedKey == null) {
                    continue;
                }
                String fallbackCase = readJsonFieldAsText(object, matchedKey);
                if (!fallbackCase.isEmpty()) {
                    return fallbackCase;
                }
            }
        }
        if (rawJson == null || fallbackPattern == null) {
            return "";
        }
        Matcher matcher = fallbackPattern.matcher(rawJson);
        if (!matcher.find()) {
            return "";
        }
        return unescapeJsonString(matcher.group(1));
    }

    private static String readJsonFieldAsText(JSONObject object, String key) {
        if (object == null || key == null || key.isEmpty() || !object.has(key) || object.isNull(key)) {
            return "";
        }
        Object value = object.opt(key);
        return sanitizeManifestText(stringifyJsonValue(value));
    }

    private static String findJsonKeyIgnoreCase(JSONObject object, String key) {
        if (object == null || key == null || key.isEmpty()) {
            return null;
        }
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            String current = iterator.next();
            if (current != null && current.equalsIgnoreCase(key)) {
                return current;
            }
        }
        return null;
    }

    private static String stringifyJsonValue(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                String itemText = stringifyJsonValue(array.opt(i)).trim();
                if (itemText.isEmpty()) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append(", ");
                }
                text.append(itemText);
            }
            return text.toString();
        }
        if (value instanceof JSONObject) {
            return "";
        }
        return String.valueOf(value);
    }

    private static String unescapeJsonString(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\")
                .trim();
    }

    private static String sanitizeManifestText(String text) {
        if (text == null) {
            return "";
        }
        return text.trim();
    }

    private static ZipEntry findEntryIgnoreCase(ZipFile zipFile, String entryName) {
        String target = entryName.toLowerCase(java.util.Locale.ROOT);
        return zipFile.stream()
                .filter(entry -> entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(target))
                .findFirst()
                .orElse(null);
    }

    private static String normalizeModId(String modId) {
        if (modId == null) {
            return "";
        }
        return modId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static void copyFileReplacing(File source, File target) throws IOException {
        if (source == null || !source.isFile()) {
            throw new IOException("Source file not found");
        }
        if (target == null) {
            throw new IOException("Target file is null");
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
        }

        File temp = new File(target.getAbsolutePath() + ".tmpcopy");
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(temp, false)) {
            copyStream(input, output);
        }

        if (target.exists() && !target.delete()) {
            if (temp.exists()) {
                temp.delete();
            }
            throw new IOException("Failed to replace " + target.getAbsolutePath());
        }
        if (!temp.renameTo(target)) {
            if (temp.exists()) {
                temp.delete();
            }
            throw new IOException("Failed to move " + temp.getAbsolutePath() + " -> " + target.getAbsolutePath());
        }
        target.setLastModified(source.lastModified() > 0L ? source.lastModified() : System.currentTimeMillis());
    }

    private static void copyStream(InputStream input, java.io.OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
    }

    private static String readEntry(ZipFile zipFile, ZipEntry entry) throws IOException {
        try (InputStream input = zipFile.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static byte[] readEntryBytes(ZipFile zipFile, ZipEntry entry) throws IOException {
        try (InputStream input = zipFile.getInputStream(entry)) {
            return readAll(input);
        }
    }
}

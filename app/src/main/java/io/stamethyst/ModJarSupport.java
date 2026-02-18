package io.stamethyst;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ModJarSupport {
    private static final Pattern MOD_ID_PATTERN = Pattern.compile("\"modid\"\\s*:\\s*\"([^\"]+)\"");
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
    private static final String STS_PATCH_BUILD_PROPERTIES = "build.properties";
    private static final String STS_RESOURCE_SENTINEL = "build.properties";
    private static final String BASEMOD_RESOURCE_SENTINEL =
            "localization/basemod/eng/customMods.json";
    private static final String BASEMOD_MOD_ID = "basemod";
    private static final String BASEMOD_JAR_FILE_NAME = "BaseMod.jar";
    private static final String BASEMOD_GLOW_PATCH_JAR = "basemod-glow-fbo-compat.jar";
    private static final String BASEMOD_GLOW_PATCH_CLASS =
            "basemod/helpers/CardBorderGlowManager$RenderGlowPatch.class";
    private static final String DOWNFALL_MOD_ID = "downfall";
    private static final String DOWNFALL_FBO_PATCH_JAR = "downfall-fbo-compat.jar";
    private static final String DOWNFALL_FBO_PATCH_CLASS =
            "collector/util/DoubleEnergyOrb.class";
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
                    DOWNFALL_MOD_ID,
                    DOWNFALL_FBO_PATCH_JAR,
                    DOWNFALL_FBO_PATCH_CLASS,
                    "Downfall FBO",
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

    public static String resolveModId(File modJar) throws IOException {
        try (ZipFile zipFile = new ZipFile(modJar)) {
            ZipEntry modInfo = findEntryIgnoreCase(zipFile, "ModTheSpire.json");
            if (modInfo == null) {
                throw new IOException("ModTheSpire.json not found in " + modJar.getName());
            }
            String json = readEntry(zipFile, modInfo);
            Matcher matcher = MOD_ID_PATTERN.matcher(json);
            if (!matcher.find()) {
                throw new IOException("modid not found in " + modJar.getName());
            }
            String modId = matcher.group(1).trim();
            if (modId.isEmpty()) {
                throw new IOException("modid is empty in " + modJar.getName());
            }
            return modId;
        }
    }

    public static void prepareMtsClasspath(Context context) throws IOException {
        File stsJar = RuntimePaths.importedStsJar(context);
        File patchJar = RuntimePaths.gdxPatchJar(context);
        File baseModJar = RuntimePaths.importedBaseModJar(context);
        appendCompatLog(context, "prepare classpath start");
        ensurePatchedStsJar(stsJar, patchJar);
        applyCompatPatchRules(context);
        ensureGdxApiJar(stsJar, RuntimePaths.mtsGdxApiJar(context));
        ensureStsResourceJar(stsJar, RuntimePaths.mtsStsResourcesJar(context));
        ensureBaseModResourceJar(baseModJar, RuntimePaths.mtsBaseModResourcesJar(context));
        appendCompatLog(context, "prepare classpath done");
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
            if (rule.applyWhenInstalled) {
                File targetJar = installedModsById.get(rule.modId);
                if (targetJar == null) {
                    appendCompatLog(context, "rule skip not installed: " + rule.label + " (modid=" + rule.modId + ")");
                    continue;
                }
                applyCompatRuleToJar(context, rule, targetJar);
            } else {
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

    private static void applyCompatRuleToJar(Context context, CompatPatchRule rule, File targetJar) throws IOException {
        File patchJar = new File(RuntimePaths.gdxPatchDir(context), rule.patchJarName);
        appendCompatLog(context, "rule matched: " + rule.label + " target=" + targetJar.getName()
                + " class=" + rule.targetClassEntry);
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

package io.stamethyst;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ComponentInstaller {
    private static final String DEFAULT_PREFS_ASSET_DIR = "components/default_saves/preferences";
    private static final String PREF_FILE_PLAYER = "STSPlayer";
    private static final String PREF_FILE_PLAYER_BACKUP = "STSPlayer.backUp";
    private static final String PREF_FILE_SAVE_SLOTS = "STSSaveSlots";
    private static final String PREF_FILE_SAVE_SLOTS_BACKUP = "STSSaveSlots.backUp";
    private static final String PLAYER_REQUIRED_TOKEN = "\"name\"";
    private static final String SAVE_SLOTS_REQUIRED_TOKEN = "\"DEFAULT_SLOT\"";

    private interface JarValidator {
        void validate(File jarFile) throws IOException;
    }

    private ComponentInstaller() {
    }

    public static void ensureInstalled(Context context) throws IOException {
        ensureInstalled(context, null);
    }

    public static void ensureInstalled(Context context, StartupProgressCallback progressCallback) throws IOException {
        RuntimePaths.ensureBaseDirs(context);
        AssetManager assets = context.getAssets();

        reportProgress(progressCallback, 5, "Installing LWJGL bridge...");
        copyAssetTree(assets, "components/lwjgl3", RuntimePaths.lwjglDir(context));
        reportProgress(progressCallback, 20, "Installing LWJGL2 injector...");
        copyAssetTree(assets, "components/lwjgl2_methods_injector", RuntimePaths.lwjgl2InjectorDir(context));
        reportProgress(progressCallback, 35, "Installing gdx patches...");
        copyAssetTree(assets, "components/gdx_patch", RuntimePaths.gdxPatchDir(context));
        reportProgress(progressCallback, 50, "Installing Caciocavallo runtime...");
        copyAssetTree(assets, "components/caciocavallo", RuntimePaths.cacioDir(context));
        reportProgress(progressCallback, 70, "Installing bundled mods...");
        installBundledMods(assets, context);
        reportProgress(progressCallback, 85, "Preparing local java shim...");
        ensureMtsLocalJreShim(context);
        reportProgress(progressCallback, 95, "Checking default preferences...");
        ensureDefaultPreferencesIfMissing(assets, context);
        reportProgress(progressCallback, 100, "Components ready");
    }

    private static void installBundledMods(AssetManager assets, Context context) throws IOException {
        ensureBundledMod(
                assets,
                "components/mods/ModTheSpire.jar",
                RuntimePaths.importedMtsJar(context),
                ModJarSupport::validateMtsJar
        );
        ensureBundledMod(
                assets,
                "components/mods/BaseMod.jar",
                RuntimePaths.importedBaseModJar(context),
                ModJarSupport::validateBaseModJar
        );
        ensureBundledMod(
                assets,
                "components/mods/StSLib.jar",
                RuntimePaths.importedStsLibJar(context),
                ModJarSupport::validateStsLibJar
        );
    }

    private static void copyAssetTree(AssetManager assets, String assetPath, File targetDir) throws IOException {
        String[] names = assets.list(assetPath);
        if (names == null) {
            throw new IOException("Asset listing failed: " + assetPath);
        }
        if (names.length == 0) {
            copyFile(assets, assetPath, new File(targetDir.getParentFile(), new File(assetPath).getName()));
            return;
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Failed to create: " + targetDir);
        }
        for (String name : names) {
            String childAssetPath = assetPath + "/" + name;
            String[] childList = assets.list(childAssetPath);
            File childFile = new File(targetDir, name);
            if (childList != null && childList.length > 0) {
                copyAssetTree(assets, childAssetPath, childFile);
            } else {
                copyFile(assets, childAssetPath, childFile);
            }
        }
    }

    private static void copyAssetTreeIfMissing(AssetManager assets, String assetPath, File targetDir) throws IOException {
        String[] names = assets.list(assetPath);
        if (names == null) {
            throw new IOException("Asset listing failed: " + assetPath);
        }
        if (names.length == 0) {
            copyFileIfMissing(assets, assetPath, new File(targetDir.getParentFile(), new File(assetPath).getName()));
            return;
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Failed to create: " + targetDir);
        }
        for (String name : names) {
            String childAssetPath = assetPath + "/" + name;
            String[] childList = assets.list(childAssetPath);
            File childFile = new File(targetDir, name);
            if (childList != null && childList.length > 0) {
                copyAssetTreeIfMissing(assets, childAssetPath, childFile);
            } else {
                copyFileIfMissing(assets, childAssetPath, childFile);
            }
        }
    }

    private static void copyFile(AssetManager assets, String assetPath, File targetFile) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create parent: " + parent);
        }
        try (InputStream input = assets.open(assetPath);
             FileOutputStream output = new FileOutputStream(targetFile, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void copyFileIfMissing(AssetManager assets, String assetPath, File targetFile) throws IOException {
        if (targetFile.isFile() && targetFile.length() > 0) {
            return;
        }
        copyFile(assets, assetPath, targetFile);
    }

    private static void ensureBundledMod(AssetManager assets,
                                         String assetPath,
                                         File targetFile,
                                         JarValidator validator) throws IOException {
        if (targetFile.isFile() && targetFile.length() > 0) {
            try {
                validator.validate(targetFile);
                return;
            } catch (Throwable ignored) {
                if (!targetFile.delete()) {
                    throw new IOException("Failed to replace invalid mod file: " + targetFile.getAbsolutePath());
                }
            }
        }
        copyFileIfMissing(assets, assetPath, targetFile);
        if (!targetFile.isFile() || targetFile.length() <= 0) {
            throw new IOException("Bundled mod install failed: " + targetFile.getAbsolutePath());
        }
        try {
            validator.validate(targetFile);
        } catch (Throwable error) {
            throw new IOException("Bundled mod validation failed: " + targetFile.getName() + ": " + error.getMessage(), error);
        }
    }

    private static void ensureMtsLocalJreShim(Context context) throws IOException {
        File javaShim = RuntimePaths.mtsLocalJavaShim(context);
        File parent = javaShim.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create MTS jre shim directory: " + parent);
        }

        File runtimeJava = new File(RuntimePaths.runtimeRoot(context), "bin/java");
        String script = "#!/system/bin/sh\n"
                + "RUNTIME_JAVA=\"" + runtimeJava.getAbsolutePath() + "\"\n"
                + "if [ -x \"$JAVA_HOME/bin/java\" ]; then\n"
                + "  exec \"$JAVA_HOME/bin/java\" \"$@\"\n"
                + "fi\n"
                + "exec \"$RUNTIME_JAVA\" \"$@\"\n";

        try (FileOutputStream output = new FileOutputStream(javaShim, false)) {
            output.write(script.getBytes(StandardCharsets.UTF_8));
        }

        javaShim.setReadable(true, false);
        javaShim.setWritable(true, true);
        if (!javaShim.setExecutable(true, false)) {
            throw new IOException("Failed to mark MTS jre shim executable: " + javaShim.getAbsolutePath());
        }
    }

    private static void ensureDefaultPreferencesIfMissing(AssetManager assets, Context context) throws IOException {
        if (!hasAssetChildren(assets, DEFAULT_PREFS_ASSET_DIR)) {
            return;
        }
        ensureDefaultPreferencesForDir(assets, RuntimePaths.preferencesDir(context));
        ensureDefaultPreferencesForDir(assets, RuntimePaths.betaPreferencesDir(context));
    }

    private static void ensureDefaultPreferencesForDir(AssetManager assets, File preferencesDir) throws IOException {
        if (!shouldInstallDefaultPreferences(preferencesDir)) {
            return;
        }
        copyAssetTreeIfMissing(assets, DEFAULT_PREFS_ASSET_DIR, preferencesDir);
        repairSentinelFile(assets, preferencesDir, PREF_FILE_PLAYER, PLAYER_REQUIRED_TOKEN);
        repairSentinelFile(assets, preferencesDir, PREF_FILE_SAVE_SLOTS, SAVE_SLOTS_REQUIRED_TOKEN);
        copyFileIfMissing(
                assets,
                DEFAULT_PREFS_ASSET_DIR + "/" + PREF_FILE_PLAYER_BACKUP,
                new File(preferencesDir, PREF_FILE_PLAYER_BACKUP)
        );
        copyFileIfMissing(
                assets,
                DEFAULT_PREFS_ASSET_DIR + "/" + PREF_FILE_SAVE_SLOTS_BACKUP,
                new File(preferencesDir, PREF_FILE_SAVE_SLOTS_BACKUP)
        );
    }

    private static void repairSentinelFile(AssetManager assets,
                                           File preferencesDir,
                                           String fileName,
                                           String requiredToken) throws IOException {
        File target = new File(preferencesDir, fileName);
        if (fileContainsToken(target, requiredToken)) {
            return;
        }
        copyFile(assets, DEFAULT_PREFS_ASSET_DIR + "/" + fileName, target);
    }

    private static boolean shouldInstallDefaultPreferences(File preferencesDir) {
        return !fileContainsToken(new File(preferencesDir, PREF_FILE_PLAYER), PLAYER_REQUIRED_TOKEN)
                || !fileContainsToken(new File(preferencesDir, PREF_FILE_SAVE_SLOTS), SAVE_SLOTS_REQUIRED_TOKEN);
    }

    private static boolean fileContainsToken(File file, String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        if (!file.isFile() || file.length() <= 0) {
            return false;
        }
        byte[] bytes = new byte[(int) Math.min(file.length(), 4096)];
        try (FileInputStream input = new FileInputStream(file)) {
            int read = input.read(bytes);
            if (read <= 0) {
                return false;
            }
            String text = new String(bytes, 0, read, StandardCharsets.UTF_8);
            return text.contains(token);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasAssetChildren(AssetManager assets, String assetPath) {
        try {
            String[] names = assets.list(assetPath);
            return names != null && names.length > 0;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void reportProgress(StartupProgressCallback callback, int percent, String message) {
        if (callback == null) {
            return;
        }
        int bounded = Math.max(0, Math.min(100, percent));
        callback.onProgress(bounded, message);
    }
}

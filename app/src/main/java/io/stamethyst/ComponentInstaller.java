package io.stamethyst;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ComponentInstaller {
    private ComponentInstaller() {
    }

    public static void ensureInstalled(Context context) throws IOException {
        RuntimePaths.ensureBaseDirs(context);
        AssetManager assets = context.getAssets();

        copyAssetTree(assets, "components/lwjgl3", RuntimePaths.lwjglDir(context));
        copyAssetTree(assets, "components/lwjgl2_methods_injector", RuntimePaths.lwjgl2InjectorDir(context));
        copyAssetTree(assets, "components/gdx_patch", RuntimePaths.gdxPatchDir(context));
        copyAssetTree(assets, "components/caciocavallo", RuntimePaths.cacioDir(context));
        installBundledMods(assets, context);
        ensureMtsLocalJreShim(context);
    }

    private static void installBundledMods(AssetManager assets, Context context) throws IOException {
        copyFileIfMissing(assets, "components/mods/ModTheSpire.jar", RuntimePaths.importedMtsJar(context));
        copyFileIfMissing(assets, "components/mods/BaseMod.jar", RuntimePaths.importedBaseModJar(context));
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
}

package io.stamethyst;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.Os;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;

public final class RuntimePackInstaller {
    private static final String[] REQUIRED_FILES = {"universal.tar.xz", "bin-aarch64.tar.xz", "version"};

    private RuntimePackInstaller() {
    }

    public static void ensureInstalled(Context context) throws IOException {
        ensureInstalled(context, null);
    }

    public static void ensureInstalled(Context context, StartupProgressCallback progressCallback) throws IOException {
        reportProgress(progressCallback, 2, "Checking runtime pack...");
        RuntimePaths.ensureBaseDirs(context);

        File runtimeRoot = RuntimePaths.runtimeRoot(context);
        File markerFile = new File(runtimeRoot, ".installed-version");
        String bundledVersion = readAssetAsString(context.getAssets(), "components/jre/version").trim();

        if (markerFile.exists()) {
            String installedVersion = new String(Files.readAllBytes(markerFile.toPath()), StandardCharsets.UTF_8).trim();
            File javaHome = locateJavaHome(runtimeRoot);
            if (installedVersion.equals(bundledVersion) && javaHome != null && isRuntimeReady(javaHome)) {
                reportProgress(progressCallback, 100, "Runtime pack already up to date");
                return;
            }
        }

        reportProgress(progressCallback, 10, "Preparing runtime directory...");
        deleteRecursively(runtimeRoot);
        if (!runtimeRoot.mkdirs()) {
            throw new IOException("Failed to create runtime root: " + runtimeRoot);
        }

        File stagingDir = new File(context.getCacheDir(), "runtime-staging");
        reportProgress(progressCallback, 18, "Preparing runtime staging...");
        deleteRecursively(stagingDir);
        if (!stagingDir.mkdirs()) {
            throw new IOException("Failed to create staging directory: " + stagingDir);
        }

        AssetManager assets = context.getAssets();
        reportProgress(progressCallback, 26, "Copying runtime archives...");
        for (int i = 0; i < REQUIRED_FILES.length; i++) {
            String required = REQUIRED_FILES[i];
            copyAssetToFile(assets, "components/jre/" + required, new File(stagingDir, required));
            int copiedPercent = 26 + Math.round(((i + 1) * 14f) / REQUIRED_FILES.length);
            reportProgress(progressCallback, copiedPercent, "Copied " + required);
        }

        reportProgress(progressCallback, 42, "Extracting universal runtime...");
        extractTarXz(new File(stagingDir, "universal.tar.xz"), runtimeRoot);
        reportProgress(progressCallback, 62, "Extracting architecture runtime...");
        extractTarXz(new File(stagingDir, "bin-aarch64.tar.xz"), runtimeRoot);

        reportProgress(progressCallback, 78, "Unpacking runtime pack200 files...");
        unpackPack200Files(context, runtimeRoot, progressCallback);

        File javaHome = locateJavaHome(runtimeRoot);
        if (javaHome == null) {
            throw new IOException("Runtime install failed: libjli.so not found under " + runtimeRoot.getAbsolutePath());
        }
        reportProgress(progressCallback, 92, "Finalizing runtime setup...");
        postPrepareRuntime(context, javaHome);
        if (!isRuntimeReady(javaHome)) {
            throw new IOException("Runtime install failed: missing core Java classes under " + javaHome.getAbsolutePath());
        }

        reportProgress(progressCallback, 98, "Writing runtime install marker...");
        Files.write(markerFile.toPath(), bundledVersion.getBytes(StandardCharsets.UTF_8));
        reportProgress(progressCallback, 100, "Runtime pack ready");
    }

    public static File locateJavaHome(File runtimeRoot) {
        File libjli = findFileByName(runtimeRoot, "libjli.so");
        if (libjli == null) {
            return null;
        }
        File cursor = libjli.getParentFile();
        while (cursor != null) {
            if ("lib".equals(cursor.getName())) {
                return cursor.getParentFile();
            }
            cursor = cursor.getParentFile();
        }
        return null;
    }

    public static File findFileByName(File root, String fileName) {
        if (root == null || !root.exists()) {
            return null;
        }
        if (root.isFile()) {
            return fileName.equals(root.getName()) ? root : null;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            File found = findFileByName(child, fileName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void extractTarXz(File tarXzFile, File destination) throws IOException {
        try (FileInputStream fileInput = new FileInputStream(tarXzFile);
             XZInputStream xzInput = new XZInputStream(fileInput);
             TarArchiveInputStream tarInput = new TarArchiveInputStream(xzInput)) {
            TarArchiveEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = tarInput.getNextTarEntry()) != null) {
                File outFile = new File(destination, entry.getName());
                if (entry.isDirectory()) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw new IOException("Failed to create directory: " + outFile);
                    }
                    continue;
                }

                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Failed to create parent: " + parent);
                }
                if (entry.isSymbolicLink()) {
                    try {
                        if (outFile.exists() && !outFile.delete()) {
                            throw new IOException("Failed to replace existing symlink target: " + outFile);
                        }
                        Os.symlink(entry.getLinkName(), outFile.getAbsolutePath());
                    } catch (Throwable ignored) {
                        // Best effort: most runtimes do not require symlink entries for JVM startup.
                    }
                    continue;
                }

                try (FileOutputStream output = new FileOutputStream(outFile)) {
                    int read;
                    while ((read = tarInput.read(buffer)) > 0) {
                        output.write(buffer, 0, read);
                    }
                }

                if (!outFile.setExecutable((entry.getMode() & 0111) != 0, false)) {
                    // Best effort.
                }
                if (!outFile.setReadable(true, false)) {
                    // Best effort.
                }
                if (!outFile.setWritable(true, true)) {
                    // Best effort.
                }
            }
        }
    }

    private static boolean isRuntimeReady(File javaHome) {
        File libDir = new File(javaHome, "lib");
        return new File(libDir, "rt.jar").exists() || new File(libDir, "modules").exists();
    }

    private static void unpackPack200Files(Context context,
                                           File runtimeRoot,
                                           StartupProgressCallback progressCallback) throws IOException {
        List<File> packFiles = new ArrayList<>();
        collectPackFiles(runtimeRoot, packFiles);
        if (packFiles.isEmpty()) {
            return;
        }

        File unpackBinary = new File(context.getApplicationInfo().nativeLibraryDir, "libunpack200.so");
        if (!unpackBinary.exists()) {
            throw new IOException("Missing unpack helper binary: " + unpackBinary.getAbsolutePath());
        }

        ProcessBuilder processBuilder = new ProcessBuilder().directory(new File(context.getApplicationInfo().nativeLibraryDir));
        for (int i = 0; i < packFiles.size(); i++) {
            File packFile = packFiles.get(i);
            int startPercent = 78 + Math.round((i * 10f) / packFiles.size());
            reportProgress(
                    progressCallback,
                    startPercent,
                    "Unpacking " + packFile.getName() + " (" + (i + 1) + "/" + packFiles.size() + ")..."
            );
            String name = packFile.getName();
            File unpackedJar = new File(packFile.getParentFile(), name.substring(0, name.length() - ".pack".length()));
            Process process = processBuilder.command(
                    unpackBinary.getAbsolutePath(),
                    "-r",
                    packFile.getAbsolutePath(),
                    unpackedJar.getAbsolutePath()
            ).redirectErrorStream(true).start();

            String output;
            try (InputStream stream = process.getInputStream()) {
                output = readAll(stream);
            }
            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while unpacking " + packFile.getAbsolutePath(), e);
            }
            if (exitCode != 0 || !unpackedJar.exists()) {
                throw new IOException("unpack200 failed for " + packFile.getName() + " (exit=" + exitCode + "): " + output);
            }
            int endPercent = 78 + Math.round(((i + 1) * 10f) / packFiles.size());
            reportProgress(
                    progressCallback,
                    endPercent,
                    "Unpacked " + packFile.getName() + " (" + (i + 1) + "/" + packFiles.size() + ")"
            );
        }
    }

    private static void collectPackFiles(File root, List<File> out) {
        if (root == null || !root.exists()) {
            return;
        }
        if (root.isFile()) {
            if (root.getName().endsWith(".pack")) {
                out.add(root);
            }
            return;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectPackFiles(child, out);
        }
    }

    private static void postPrepareRuntime(Context context, File javaHome) throws IOException {
        File archLibDir = new File(new File(javaHome, "lib"), "aarch64");
        if (!archLibDir.exists()) {
            return;
        }

        File freetypeVersioned = new File(archLibDir, "libfreetype.so.6");
        File freetype = new File(archLibDir, "libfreetype.so");
        if (freetypeVersioned.exists() && (!freetype.exists() || freetype.length() != freetypeVersioned.length())) {
            if (!freetypeVersioned.renameTo(freetype)) {
                copyFile(freetypeVersioned, freetype);
            }
        }

        File appAwtXawt = new File(context.getApplicationInfo().nativeLibraryDir, "libawt_xawt.so");
        if (appAwtXawt.exists()) {
            copyFile(appAwtXawt, new File(archLibDir, "libawt_xawt.so"));
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        byte[] buffer = new byte[4096];
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            int read;
            while ((read = stream.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name()).trim();
        }
    }

    private static String readAssetAsString(AssetManager assets, String assetPath) throws IOException {
        try (InputStream input = assets.open(assetPath)) {
            byte[] data = new byte[4096];
            StringBuilder out = new StringBuilder();
            int read;
            while ((read = input.read(data)) > 0) {
                out.append(new String(data, 0, read, StandardCharsets.UTF_8));
            }
            return out.toString();
        }
    }

    private static void copyAssetToFile(AssetManager assets, String assetPath, File targetFile) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create asset target directory: " + parent);
        }
        try (InputStream input = assets.open(assetPath);
             FileOutputStream output = new FileOutputStream(targetFile, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create target directory: " + parent);
        }
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private static void reportProgress(StartupProgressCallback callback, int percent, String message) {
        if (callback == null) {
            return;
        }
        int bounded = Math.max(0, Math.min(100, percent));
        callback.onProgress(bounded, message);
    }
}

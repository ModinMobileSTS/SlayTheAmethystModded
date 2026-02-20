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
    private static final String ARCHIVE_UNIVERSAL = "universal.tar.xz";
    private static final String ARCHIVE_VERSION = "version";
    private static final String ARCHIVE_AARCH64 = "bin-aarch64.tar.xz";
    private static final String ARCHIVE_ARM32 = "bin-arm.tar.xz";

    private RuntimePackInstaller() {
    }

    public static void ensureInstalled(Context context) throws IOException {
        ensureInstalled(context, null);
    }

    public static void ensureInstalled(Context context, StartupProgressCallback progressCallback) throws IOException {
        reportProgress(progressCallback, 2, "Checking runtime pack...");
        RuntimePaths.ensureBaseDirs(context);
        AssetManager assets = context.getAssets();
        String archArchive = resolveArchArchive(assets);

        File runtimeRoot = RuntimePaths.runtimeRoot(context);
        File markerFile = new File(runtimeRoot, ".installed-version");
        String bundledVersion = readAssetAsString(assets, "components/jre/" + ARCHIVE_VERSION).trim();
        String bundledMarker = bundledVersion + "|" + archArchive;

        if (markerFile.exists()) {
            String installedMarker = new String(Files.readAllBytes(markerFile.toPath()), StandardCharsets.UTF_8).trim();
            boolean markerMatched = installedMarker.equals(bundledMarker)
                    || (installedMarker.equals(bundledVersion) && ARCHIVE_AARCH64.equals(archArchive));
            File javaHome = locateJavaHome(runtimeRoot);
            if (markerMatched && javaHome != null && isRuntimeReady(javaHome)) {
                reportProgress(progressCallback, 100, "Runtime pack already up to date");
                return;
            }
        }

        reportProgress(progressCallback, 10, "Preparing runtime directory...");
        prepareCleanDirectory(runtimeRoot, "runtime root");

        File stagingDir = new File(context.getCacheDir(), "runtime-staging");
        reportProgress(progressCallback, 18, "Preparing runtime staging...");
        prepareCleanDirectory(stagingDir, "staging directory");

        String[] requiredFiles = {ARCHIVE_UNIVERSAL, archArchive, ARCHIVE_VERSION};
        reportProgress(progressCallback, 26, "Copying runtime archives...");
        for (int i = 0; i < requiredFiles.length; i++) {
            String required = requiredFiles[i];
            copyAssetToFile(assets, "components/jre/" + required, new File(stagingDir, required));
            int copiedPercent = 26 + Math.round(((i + 1) * 14f) / requiredFiles.length);
            reportProgress(progressCallback, copiedPercent, "Copied " + required);
        }

        reportProgress(progressCallback, 42, "Extracting universal runtime...");
        extractTarXz(new File(stagingDir, ARCHIVE_UNIVERSAL), runtimeRoot);
        reportProgress(progressCallback, 62, "Extracting architecture runtime...");
        extractTarXz(new File(stagingDir, archArchive), runtimeRoot);

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
        Files.write(markerFile.toPath(), bundledMarker.getBytes(StandardCharsets.UTF_8));
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
        File archLibDir = findRuntimeArchLibDir(javaHome);
        if (archLibDir == null) {
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

    private static String resolveArchArchive(AssetManager assets) throws IOException {
        boolean is64BitProcess = android.os.Process.is64Bit();
        String required = is64BitProcess ? ARCHIVE_AARCH64 : ARCHIVE_ARM32;
        if (assetExists(assets, "components/jre/" + required)) {
            return required;
        }
        throw new IOException(
                "Runtime pack missing required architecture archive: "
                        + required
                        + " (process="
                        + (is64BitProcess ? "64-bit" : "32-bit")
                        + ")"
        );
    }

    private static File findRuntimeArchLibDir(File javaHome) {
        File libRoot = new File(javaHome, "lib");
        String[] candidates = {"aarch64", "arm64", "aarch32", "arm32", "armeabi-v7a", "arm"};
        for (String candidate : candidates) {
            File dir = new File(libRoot, candidate);
            if (dir.isDirectory()) {
                return dir;
            }
        }
        return null;
    }

    private static boolean assetExists(AssetManager assets, String assetPath) {
        try (InputStream ignored = assets.open(assetPath)) {
            return true;
        } catch (IOException ignored) {
            return false;
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

    private static void prepareCleanDirectory(File directory, String label) throws IOException {
        File parent = directory.getParentFile();
        if (parent != null) {
            if (parent.exists() && !parent.isDirectory()) {
                throw new IOException(label + " parent is not a directory: " + parent.getAbsolutePath());
            }
            if (!parent.exists() && !parent.mkdirs()) {
                throw new IOException("Failed to create " + label + " parent: " + parent.getAbsolutePath());
            }
        }

        deleteRecursively(directory);
        if (!directory.exists()) {
            if (!directory.mkdirs() && !directory.isDirectory()) {
                throw new IOException("Failed to create " + label + ": " + directory.getAbsolutePath());
            }
            return;
        }

        if (!directory.isDirectory()) {
            throw new IOException(label + " path is not a directory: " + directory.getAbsolutePath());
        }

        File[] remaining = directory.listFiles();
        if (remaining == null) {
            throw new IOException("Failed to inspect " + label + ": " + directory.getAbsolutePath());
        }
        if (remaining.length == 0) {
            return;
        }

        for (File child : remaining) {
            deleteRecursively(child);
        }
        File[] stillRemaining = directory.listFiles();
        if (stillRemaining == null || stillRemaining.length > 0) {
            throw new IOException("Failed to clean " + label + ": " + directory.getAbsolutePath());
        }
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        boolean deleted = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleted &= deleteRecursively(child);
                }
            }
        }
        if (!file.delete() && file.exists()) {
            deleted = false;
        }
        return deleted;
    }

    private static void reportProgress(StartupProgressCallback callback, int percent, String message) {
        if (callback == null) {
            return;
        }
        int bounded = Math.max(0, Math.min(100, percent));
        callback.onProgress(bounded, message);
    }
}

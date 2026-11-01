package io.stamethyst.godot;

import android.content.Context;
import dalvik.system.DexClassLoader;
import io.stamethyst.backend.minigame.SlingBreakReleases;
import io.stamethyst.backend.minigame.SlingBreakInstallation;
import io.stamethyst.backend.update.UpdateReleaseInfo;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.CRC32;
import java.util.zip.ZipOutputStream;

/** Verified dynamic loader. Downloads and atomic activation belong to SlingBreakService. */
public final class GodotFiles {
    private static final long LIMIT = 256L * 1024 * 1024;
    private static final Set<String> LIBS = new HashSet<>(Arrays.asList("libgodot_android.so", "libc++_shared.so"));
    private final Context context;
    private final UpdateReleaseInfo release;
    public GodotFiles(Context context, UpdateReleaseInfo release) { this.context = context; this.release = release; }
    public static File root(Context context) { return SlingBreakInstallation.root(context); }
    public static File engine(Context context) { return new File(root(context), "engine-" + SlingBreakReleases.ENGINE_SHA256); }
    public File engine() { return engine(context); }
    public File pck() { return new File(root(context), "game-" + release.getAssetSha256() + ".pck"); }
    public File assets() { return new File(root(context), "assets-" + release.getAssetSha256() + ".zip"); }

    /** Derived, code-free AssetManager mount. The downloaded PCK remains an independent file. */
    public static void writeAssets(File pck, File target) throws Exception {
        CRC32 crc = new CRC32();
        try (InputStream in = new FileInputStream(pck)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) crc.update(buffer, 0, n);
        }
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(target))) {
            if (!target.setReadOnly()) throw new IOException("Cannot protect derived assets");
            ZipEntry entry = new ZipEntry("assets/game.pck");
            entry.setMethod(ZipEntry.STORED); entry.setSize(pck.length());
            entry.setCompressedSize(pck.length()); entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            try (InputStream in = new FileInputStream(pck)) { copyBounded(in, zip, LIMIT); }
            zip.closeEntry();
        }
    }
    public static void verifyAssets(File file, String hash) throws Exception {
        if (!file.isFile() || Files.isSymbolicLink(file.toPath())) throw new IOException("Missing asset mount");
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry("assets/game.pck");
            if (zip.size() != 1 || entry == null || entry.getMethod() != ZipEntry.STORED || entry.getSize() > LIMIT)
                throw new IOException("Unexpected asset mount content");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = zip.getInputStream(entry)) { hash(in, digest); }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            if (!hex.toString().equalsIgnoreCase(hash)) throw new IOException("Asset PCK hash mismatch");
        }
    }
    public static void verify(File file, String hash) throws Exception {
        if (!hash.matches("[a-fA-F0-9]{64}") || !file.isFile() || Files.isSymbolicLink(file.toPath()))
            throw new IOException("Missing component or pinned SHA-256");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (!hex.toString().equalsIgnoreCase(hash)) throw new IOException("SHA-256 mismatch: " + file.getName());
    }
    public static void extractEngine(File apk, File directory) throws Exception {
        verify(apk, SlingBreakReleases.ENGINE_SHA256);
        File lib = new File(directory, "lib");
        if (!lib.isDirectory() && !lib.mkdirs()) throw new IOException("Cannot create native directory");
        try (ZipFile zip = new ZipFile(apk)) {
            for (String name : LIBS) {
                ZipEntry entry = zip.getEntry("lib/arm64-v8a/" + name);
                if (entry == null) throw new IOException("Missing engine library " + name);
                File nativeFile = new File(lib, name);
                try (InputStream in = zip.getInputStream(entry); OutputStream out = new FileOutputStream(nativeFile)) {
                    if (!nativeFile.setReadOnly()) throw new IOException("Cannot protect native code");
                    copyBounded(in, out, LIMIT);
                }
            }
        }
        if (!apk.setReadOnly()) throw new IOException("Cannot protect dynamic code");
    }
    public void prepare() throws Exception {
        verifyEngine(engine());
        verify(pck(), release.getAssetSha256());
        File mount = assets();
        synchronized (GodotFiles.class) {
            boolean valid;
            try { verifyAssets(mount, release.getAssetSha256()); valid = true; }
            catch (Exception invalid) { valid = false; }
            if (!valid) {
                File temp = new File(root(context), "assets-" + UUID.randomUUID() + ".part");
                try {
                    writeAssets(pck(), temp);
                    verifyAssets(temp, release.getAssetSha256());
                    Files.move(temp.toPath(), mount.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } finally { temp.delete(); }
            }
        }
    }
    public static void verifyEngine(File engine) throws Exception {
        File apk = new File(engine, "engine.apk");
        verify(apk, SlingBreakReleases.ENGINE_SHA256);
        // Compare the two SO files against the hash-pinned APK before each load.
        try (ZipFile zip = new ZipFile(apk)) {
            for (String name : LIBS) {
                File so = new File(engine, "lib/" + name);
                ZipEntry entry = zip.getEntry("lib/arm64-v8a/" + name);
                if (entry == null || !so.isFile() || Files.isSymbolicLink(so.toPath())) throw new IOException("Missing native library");
                MessageDigest a = MessageDigest.getInstance("SHA-256"), b = MessageDigest.getInstance("SHA-256");
                try (InputStream in = zip.getInputStream(entry)) { hash(in, a); }
                try (InputStream in = new FileInputStream(so)) { hash(in, b); }
                if (!MessageDigest.isEqual(a.digest(), b.digest())) throw new IOException("Native library modified");
            }
        }
    }
    public ClassLoader loader() throws Exception {
        prepare();
        return new DexClassLoader(new File(engine(), "engine.apk").getPath(), context.getCodeCacheDir().getPath(), new File(engine(), "lib").getPath(), context.getClassLoader());
    }
    private static void hash(InputStream in, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[65536]; int n;
        while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
    }
    static void copyBounded(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[65536]; long total = 0; int n;
        while ((n = in.read(buffer)) != -1) {
            total += n; if (total > limit) throw new IOException("Component exceeds size limit");
            out.write(buffer, 0, n);
        }
    }
}

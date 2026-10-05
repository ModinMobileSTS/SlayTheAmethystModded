package io.stamethyst.web;

import java.io.*;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Pure Java archive validation, also used in local tests. No code runs before this succeeds. */
public final class GeckoDependencyArchive {
    private static final long MAX_EXTRACT = 400L * 1024 * 1024;

    public static void unpack(File directory, String expectedSha256) throws Exception {
        File bundle = new File(directory, "bundle.zip");
        checkDigest(bundle, expectedSha256);
        try (ZipFile zip = new ZipFile(bundle)) {
            requireContainer(zip);
            copyEntry(zip, zip.getEntry("runtime.apk"), new File(directory, "runtime.apk"));
        }
        File libraries = new File(directory, "lib");
        if (!libraries.mkdir()) throw new IOException("Cannot create Gecko library directory");
        try (ZipFile apk = new ZipFile(new File(directory, "runtime.apk"))) {
            Enumeration<? extends ZipEntry> entries = apk.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (isLibrary(entry)) copyEntry(apk, entry, new File(libraries, new File(entry.getName()).getName()));
            }
        }
        verify(directory, expectedSha256);
        if (!new File(directory, "runtime.apk").setReadOnly() || !bundle.setReadOnly())
            throw new IOException("Cannot protect dependency code");
        File[] nativeFiles = libraries.listFiles();
        if (nativeFiles == null) throw new IOException("Cannot inspect Gecko library directory");
        for (File nativeFile : nativeFiles) if (!nativeFile.setReadOnly()) throw new IOException("Cannot protect native code");
    }
    public static void verify(File directory, String expectedSha256) throws Exception {
        File bundle = new File(directory, "bundle.zip");
        checkDigest(bundle, expectedSha256);
        File runtime = new File(directory, "runtime.apk");
        try (ZipFile zip = new ZipFile(bundle)) {
            requireContainer(zip);
            if (!digest(zip.getInputStream(zip.getEntry("runtime.apk"))).equals(sha256(runtime)))
                throw new IOException("Gecko code checksum mismatch");
        }
        boolean foundXul = false;
        try (ZipFile apk = new ZipFile(runtime)) {
            Enumeration<? extends ZipEntry> entries = apk.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!isLibrary(entry)) continue;
                String name = new File(entry.getName()).getName();
                if (!digest(apk.getInputStream(entry)).equals(sha256(new File(directory, "lib/" + name))))
                    throw new IOException("Gecko native checksum mismatch: " + name);
                foundXul |= name.equals("libxul.so");
            }
        }
        if (!foundXul) throw new IOException("Missing arm64 Gecko native libraries");
    }
    private static boolean isLibrary(ZipEntry entry) {
        return entry.getName().matches("lib/arm64-v8a/lib[a-zA-Z0-9_+.-]+\\.so");
    }
    private static void checkDigest(File bundle, String expected) throws Exception {
        if (expected == null || !expected.matches("[a-f0-9]{64}") || !sha256(bundle).equals(expected))
            throw new IOException("Web dependency SHA-256 mismatch");
    }
    private static void requireContainer(ZipFile zip) throws IOException {
        if (zip.size() != 1 || zip.getEntry("runtime.apk") == null || zip.getEntry("runtime.apk").isDirectory())
            throw new IOException("Invalid web dependency archive");
    }
    private static void copyEntry(ZipFile zip, ZipEntry entry, File file) throws IOException {
        try (InputStream in = zip.getInputStream(entry); OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            long size = 0;
            for (int n; (n = in.read(buffer)) >= 0;) {
                size += n;
                if (size > MAX_EXTRACT) throw new IOException("Oversized Gecko archive entry");
                out.write(buffer, 0, n);
            }
        }
    }
    public static String sha256(File file) throws Exception { return digest(new FileInputStream(file)); }
    private static String digest(InputStream stream) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = stream) {
            byte[] buffer = new byte[128 * 1024];
            for (int n; (n = in.read(buffer)) >= 0;) digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}

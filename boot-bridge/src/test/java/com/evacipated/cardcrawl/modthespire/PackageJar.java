package com.evacipated.cardcrawl.modthespire;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class PackageJar {
    public static boolean writePackageJarFiles = true;
    public static boolean observedOutJarWasNull = false;
    public static int observedOutJarSize = -1;
    public static boolean observedPackageFlag = false;
    public static String observedUserDir = null;
    public static String forcedPackageDir = null;
    /**
     * When set, packageJar reports that Amethyst's fast writer produced the main jar,
     * like the patched MTS class does. The jar written below deliberately omits the
     * compiled-class overrides, so a store() that honours the takeover flag leaves
     * them absent — a store() that wrongly re-runs its serial merge makes them appear.
     */
    public static java.lang.Runnable onPackageJarStart = null;

    private PackageJar() {
    }

    private static String createClassPath() {
        return "desktop-1.0.jar";
    }

    private static String createModdedJarName(String fileName) {
        int dot = fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                ? fileName.length() - ".jar".length()
                : fileName.length();
        return fileName.substring(0, dot) + "-modded.jar";
    }

    public static void resetTracking() {
        observedOutJarWasNull = false;
        observedOutJarSize = -1;
        observedPackageFlag = false;
        observedUserDir = null;
        forcedPackageDir = null;
        onPackageJarStart = null;
    }

    public static void packageJar(MTSClassPool classPool, String outputPath) throws Exception {
        Set<String> outJarClasses = classPool.getOutJarClasses();
        observedOutJarWasNull = outJarClasses == null;
        observedOutJarSize = outJarClasses == null ? -1 : outJarClasses.size();
        observedPackageFlag = Loader.PACKAGE;
        observedUserDir = System.getProperty("user.dir");
        if (onPackageJarStart != null) {
            onPackageJarStart.run();
        }
        JarOutputStream output = new JarOutputStream(new FileOutputStream(outputPath));
        try {
            output.putNextEntry(new JarEntry("com/example/Patched.class"));
            output.write("patched".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            output.putNextEntry(new JarEntry("amethyst-cache-padding.bin"));
            byte[] padding = new byte[1024 * 1024];
            new Random(42L).nextBytes(padding);
            output.write(padding);
            output.closeEntry();
        } finally {
            output.close();
        }

        if (writePackageJarFiles) {
            File packageDir = forcedPackageDir == null
                    ? new File(System.getProperty("amethyst.mts.patch_cache.package_dir"))
                    : new File(forcedPackageDir);
            if (!packageDir.isDirectory() && !packageDir.mkdirs()) {
                throw new IllegalStateException("Failed to create package dir");
            }
            JarOutputStream packageOutput = new JarOutputStream(
                    new FileOutputStream(new File(packageDir, "Example's Mod-modded.jar"))
            );
            try {
                packageOutput.putNextEntry(new JarEntry("example/ExampleMod.class"));
                packageOutput.write("mod".getBytes(StandardCharsets.UTF_8));
                packageOutput.closeEntry();
            } finally {
                packageOutput.close();
            }
        }
    }

    public static final class Entries {
        private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();

        public boolean add(Entry entry) {
            if (entries.containsKey(entry.path)) {
                return false;
            }
            entries.put(entry.path, entry);
            return true;
        }
    }

    public static final class Entry {
        String path;
        String modID;
        Type type;
        byte[] b;
        URL locationURL;

        public Entry(String path, Type type) {
            this.path = path;
            this.type = type;
        }

        public Entry(String path, String modId) {
            this.path = path;
            this.modID = modId;
            this.type = modId == null ? Type.BASEGAME : Type.MOD;
        }

        public Entry(String path, byte[] bytes, URL locationUrl) {
            this.path = path;
            this.b = bytes;
            this.locationURL = locationUrl;
            this.type = Type.OUTJAR;
        }
    }

    public enum Type {
        BASEGAME,
        MOD,
        OUTJAR,
        MTS,
        COREPATCH,
        KOTLIN
    }
}

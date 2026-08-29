package io.stamethyst.bridge;

import com.evacipated.cardcrawl.modthespire.ByteArrayMapClassPath;
import com.evacipated.cardcrawl.modthespire.MTSClassPool;
import com.evacipated.cardcrawl.modthespire.ModInfo;
import com.evacipated.cardcrawl.modthespire.PackageJar;
import com.evacipated.cardcrawl.modthespire.Loader;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MtsPatchCacheStoreTest {
    private static final String PROP_ENABLED = "amethyst.mts.patch_cache.enabled";
    private static final String PROP_JAR = "amethyst.mts.patch_cache.jar";
    private static final String PROP_MARKER = "amethyst.mts.patch_cache.marker";
    private static final String PROP_PACKAGE_DIR = "amethyst.mts.patch_cache.package_dir";
    private static final String PROP_EXPECTED = "amethyst.mts.patch_cache.expected";
    private static final String PROP_PACKAGE_JAR_THREADS = "amethyst.mts.patch_cache.package_jar_threads";
    private static final String PROP_MIN_FREE_BYTES = "amethyst.mts.patch_cache.min_free_bytes";

    @Test
    public void store_writesMarkerWhenCacheJarAndPackageJarsExist() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-store-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            String previousUserDir = System.getProperty("user.dir");

            MtsPatchCacheStore.store(new MTSClassPool());

            assertTrue(new File(root, "desktop-1.0-modded.jar").length() >= 1024L * 1024L);
            assertTrue(new File(root, ".mts_patch_cache").isFile());
            assertTrue(new File(root, "package/Example's Mod-modded.jar").isFile());
            assertTrue(new File(root, "package/Exampleu0027s Mod-modded.jar").isFile());
            assertEquals(1, MTSClassPool.getModifiedClassesCalls);
            assertTrue(MTSClassPool.getOutJarClassesCalls >= 1);
            assertFalse(PackageJar.observedOutJarWasNull);
            assertEquals(1, PackageJar.observedOutJarSize);
            assertFalse(PackageJar.observedPackageFlag);
            assertEquals(root.getAbsolutePath(), PackageJar.observedUserDir);
            assertEquals(previousUserDir, System.getProperty("user.dir"));
            assertFalse(Loader.PACKAGE);
            assertFalse(Loader.OUT_JAR);
        } finally {
            clearCacheProperties();
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            deleteRecursively(root);
        }
    }

    @Test
    public void resolvePackageDirPath_usesCachePackageDirOnlyWhenCacheIsEnabled() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-store-package-dir-").toFile();
        try {
            clearCacheProperties();
            System.setProperty(PROP_PACKAGE_DIR, new File(root, "package").getAbsolutePath());
            assertEquals("package", MtsPatchCacheStore.resolvePackageDirPath());

            System.setProperty(PROP_ENABLED, "true");
            assertEquals(new File(root, "package").getAbsolutePath(), MtsPatchCacheStore.resolvePackageDirPath());
        } finally {
            clearCacheProperties();
            deleteRecursively(root);
        }
    }

    @Test
    public void packageJarFastPath_writesCacheJarAndPackageJarsFromEntries() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-fast-package-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            File baseJar = new File(root, "base.jar");
            File modJar = new File(root, "Example Mod.jar");
            writeJar(baseJar, "base/Base.class", "base");
            writeJar(modJar, "example/ExampleMod.class", "mod");
            Loader.STS_JAR = baseJar.getAbsolutePath();
            Loader.MODINFOS = new ModInfo[] {
                    new ModInfo("example", modJar.toURI().toURL())
            };

            PackageJar.Entries entries = new PackageJar.Entries();
            entries.add(new PackageJar.Entry(
                    "com/evacipated/cardcrawl/modthespire/PackageJar$PrepackagedLauncher.class",
                    "launcher".getBytes("UTF-8"),
                    null
            ));
            entries.add(new PackageJar.Entry("base/Base.class", PackageJar.Type.BASEGAME));
            entries.add(new PackageJar.Entry(
                    "base/Patched.class",
                    "patched".getBytes("UTF-8"),
                    null
            ));
            entries.add(new PackageJar.Entry("example/ExampleMod.class", "example"));
            entries.add(new PackageJar.Entry(
                    "example/Generated.class",
                    "generated".getBytes("UTF-8"),
                    modJar.toURI().toURL()
            ));

            JarOutputStream openOutput = new JarOutputStream(
                    new FileOutputStream(new File(root, "desktop-1.0-modded.jar"))
            );

            assertTrue(MtsPatchCacheStore.packageJarFastPath(
                    new MTSClassPool(),
                    entries,
                    openOutput,
                    new File(root, "desktop-1.0-modded.jar").getAbsolutePath()
            ));

            File cachedJar = new File(root, "desktop-1.0-modded.jar");
            File packageJar = new File(root, "package/Example Mod-modded.jar");
            assertTrue(cachedJar.isFile());
            assertTrue(packageJar.isFile());
            assertTrue(hasJarEntry(cachedJar, "META-INF/MANIFEST.MF"));
            assertTrue(java.util.Arrays.equals("base".getBytes("UTF-8"), readJarEntry(cachedJar, "base/Base.class")));
            assertTrue(java.util.Arrays.equals("patched".getBytes("UTF-8"), readJarEntry(cachedJar, "base/Patched.class")));
            assertTrue(java.util.Arrays.equals("mod".getBytes("UTF-8"), readJarEntry(packageJar, "example/ExampleMod.class")));
            assertTrue(java.util.Arrays.equals("generated".getBytes("UTF-8"), readJarEntry(packageJar, "example/Generated.class")));
        } finally {
            clearCacheProperties();
            resetStubTracking();
            deleteRecursively(root);
        }
    }

    @Test
    public void packageJarFastPath_keepsSharedPackageDirectoryEntriesInEveryModPackageJar() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-shared-dirs-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            File baseJar = new File(root, "base.jar");
            File rpcJar = new File(root, "rpc-mod.jar");
            File metricsJar = new File(root, "metrics.jar");
            writeJar(baseJar, "base/Base.class", "base");
            writeJarEntries(
                    rpcJar,
                    "com/github/",
                    "com/github/paopaoyue/",
                    "com/github/paopaoyue/rpcmod/",
                    "com/github/paopaoyue/rpcmod/RpcApp.class"
            );
            writeJarEntries(
                    metricsJar,
                    "com/github/",
                    "com/github/paopaoyue/",
                    "com/github/paopaoyue/metrics/",
                    "com/github/paopaoyue/metrics/MetricsCaller.class"
            );
            Loader.STS_JAR = baseJar.getAbsolutePath();
            Loader.MODINFOS = new ModInfo[] {
                    new ModInfo("ypp-rpc", rpcJar.toURI().toURL()),
                    new ModInfo("sts-metrics", metricsJar.toURI().toURL())
            };

            // Mirrors MTS PackageJar.Entries: paths are deduplicated globally on a first-wins
            // basis, so the shared `com/github/` directories end up owned by ypp-rpc alone.
            PackageJar.Entries entries = new PackageJar.Entries();
            entries.add(new PackageJar.Entry(
                    "com/evacipated/cardcrawl/modthespire/PackageJar$PrepackagedLauncher.class",
                    "launcher".getBytes("UTF-8"),
                    null
            ));
            entries.add(new PackageJar.Entry("base/Base.class", PackageJar.Type.BASEGAME));
            entries.add(new PackageJar.Entry("com/github/", "ypp-rpc"));
            entries.add(new PackageJar.Entry("com/github/paopaoyue/", "ypp-rpc"));
            entries.add(new PackageJar.Entry("com/github/paopaoyue/rpcmod/", "ypp-rpc"));
            entries.add(new PackageJar.Entry("com/github/paopaoyue/rpcmod/RpcApp.class", "ypp-rpc"));
            assertFalse(entries.add(new PackageJar.Entry("com/github/", "sts-metrics")));
            assertFalse(entries.add(new PackageJar.Entry("com/github/paopaoyue/", "sts-metrics")));
            entries.add(new PackageJar.Entry("com/github/paopaoyue/metrics/", "sts-metrics"));
            entries.add(new PackageJar.Entry("com/github/paopaoyue/metrics/MetricsCaller.class", "sts-metrics"));

            JarOutputStream openOutput = new JarOutputStream(
                    new FileOutputStream(new File(root, "desktop-1.0-modded.jar"))
            );

            assertTrue(MtsPatchCacheStore.packageJarFastPath(
                    new MTSClassPool(),
                    entries,
                    openOutput,
                    new File(root, "desktop-1.0-modded.jar").getAbsolutePath()
            ));

            File rpcPackageJar = new File(root, "package/rpc-mod-modded.jar");
            File metricsPackageJar = new File(root, "package/metrics-modded.jar");
            assertTrue(rpcPackageJar.isFile());
            assertTrue(metricsPackageJar.isFile());

            // Spring resolves `classpath*:com/github/**` by enumerating getResources("com/github/"),
            // which only matches jars physically containing that directory entry. Both package jars
            // must therefore keep the shared prefixes even though MTS assigned them to one mod.
            assertTrue(hasJarEntry(rpcPackageJar, "com/github/"));
            assertTrue(hasJarEntry(rpcPackageJar, "com/github/paopaoyue/"));
            assertTrue(hasJarEntry(rpcPackageJar, "com/github/paopaoyue/rpcmod/"));
            assertTrue(hasJarEntry(metricsPackageJar, "com/github/"));
            assertTrue(hasJarEntry(metricsPackageJar, "com/github/paopaoyue/"));
            assertTrue(hasJarEntry(metricsPackageJar, "com/github/paopaoyue/metrics/"));

            // Class ownership must still be respected: no mod may leak another mod's classes.
            assertTrue(hasJarEntry(rpcPackageJar, "com/github/paopaoyue/rpcmod/RpcApp.class"));
            assertFalse(hasJarEntry(rpcPackageJar, "com/github/paopaoyue/metrics/MetricsCaller.class"));
            assertTrue(hasJarEntry(metricsPackageJar, "com/github/paopaoyue/metrics/MetricsCaller.class"));
            assertFalse(hasJarEntry(metricsPackageJar, "com/github/paopaoyue/rpcmod/RpcApp.class"));
        } finally {
            clearCacheProperties();
            resetStubTracking();
            deleteRecursively(root);
        }
    }

    @Test
    public void store_rejectsCacheWhenPackageJarsAreMissing() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-store-missing-package-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            PackageJar.writePackageJarFiles = false;

            MtsPatchCacheStore.store(new MTSClassPool());

            assertFalse(new File(root, "desktop-1.0-modded.jar").exists());
            assertFalse(new File(root, ".mts_patch_cache").exists());
        } finally {
            clearCacheProperties();
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            deleteRecursively(root);
        }
    }

    @Test
    public void store_migratesPackageJarsGeneratedInLaunchUserDir() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-store-migrate-package-").toFile();
        String previousUserDir = System.getProperty("user.dir");
        try {
            File cacheRoot = new File(root, "cache");
            File launchRoot = new File(root, "launch");
            assertTrue(cacheRoot.mkdirs());
            assertTrue(launchRoot.mkdirs());
            setCacheProperties(cacheRoot);
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            PackageJar.forcedPackageDir = new File(launchRoot, "package").getAbsolutePath();
            System.setProperty("user.dir", launchRoot.getAbsolutePath());

            MtsPatchCacheStore.store(new MTSClassPool());

            assertTrue(new File(cacheRoot, "desktop-1.0-modded.jar").length() >= 1024L * 1024L);
            assertTrue(new File(cacheRoot, ".mts_patch_cache").isFile());
            assertTrue(new File(cacheRoot, "package/Example's Mod-modded.jar").isFile());
            assertFalse(new File(launchRoot, "package/Example's Mod-modded.jar").exists());
            assertEquals(cacheRoot.getAbsolutePath(), PackageJar.observedUserDir);
            assertEquals(launchRoot.getAbsolutePath(), System.getProperty("user.dir"));
        } finally {
            if (previousUserDir == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", previousUserDir);
            }
            clearCacheProperties();
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            deleteRecursively(root);
        }
    }

    @Test
    public void store_reusesOutJarCapturedDuringCompileBeforeDetachedClassesDisappear() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-store-compile-capture-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            MTSClassPool.detachAfterCompileSnapshot = true;
            MTSClassPool classPool = new MTSClassPool();
            String previousUserDir = System.getProperty("user.dir");

            MtsPatchCacheStore.beginCompileCapture();
            try {
                assertEquals(1, classPool.getModifiedClasses().size());
            } finally {
                MtsPatchCacheStore.finishCompileCapture();
            }

            MtsPatchCacheStore.store(classPool);

            assertTrue(new File(root, "desktop-1.0-modded.jar").isFile());
            assertTrue(new File(root, ".mts_patch_cache").isFile());
            assertEquals(1, MTSClassPool.getModifiedClassesCalls);
            assertTrue(MTSClassPool.getOutJarClassesCalls >= 2);
            assertFalse(PackageJar.observedOutJarWasNull);
            assertEquals(1, PackageJar.observedOutJarSize);
            assertEquals(root.getAbsolutePath(), PackageJar.observedUserDir);
            assertEquals(previousUserDir, System.getProperty("user.dir"));
            assertFalse(Loader.PACKAGE);
            assertFalse(Loader.OUT_JAR);
        } finally {
            clearCacheProperties();
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            deleteRecursively(root);
        }
    }

    @Test
    public void store_mergesCompiledBaseGameClassesIntoCacheJar() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-store-compiled-classes-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            ByteArrayMapClassPath compiledClasses = new ByteArrayMapClassPath();
            byte[] patchedCardLibrary = "patched-card-library".getBytes("UTF-8");
            byte[] modClass = "mod-class".getBytes("UTF-8");
            compiledClasses.addClass(
                    "com.megacrit.cardcrawl.helpers.CardLibrary",
                    null,
                    patchedCardLibrary
            );
            compiledClasses.addClass(
                    "example.ExampleMod",
                    new java.net.URL("file:/mods/ExampleMod.jar"),
                    modClass
            );

            MtsPatchCacheStore.store(new MTSClassPool(), compiledClasses);

            File cachedJar = new File(root, "desktop-1.0-modded.jar");
            assertTrue(cachedJar.isFile());
            assertTrue(new File(root, ".mts_patch_cache").isFile());
            assertTrue(
                    java.util.Arrays.equals(
                            patchedCardLibrary,
                            readJarEntry(cachedJar, "com/megacrit/cardcrawl/helpers/CardLibrary.class")
                    )
            );
            assertFalse(hasJarEntry(cachedJar, "example/ExampleMod.class"));
        } finally {
            clearCacheProperties();
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            deleteRecursively(root);
        }
    }

    @Test
    public void store_keepsMergedCacheJarUncompressed() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-store-merge-level-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            ByteArrayMapClassPath compiledClasses = new ByteArrayMapClassPath();

            // Highly compressible, so a default-deflate rewrite is unmistakable:
            // stored at level 0 the entry grows slightly, deflated it collapses.
            byte[] patchedCardLibrary = new byte[64 * 1024];
            java.util.Arrays.fill(patchedCardLibrary, (byte) 'a');
            compiledClasses.addClass(
                    "com.megacrit.cardcrawl.helpers.CardLibrary",
                    null,
                    patchedCardLibrary
            );

            MtsPatchCacheStore.store(new MTSClassPool(), compiledClasses);

            File cachedJar = new File(root, "desktop-1.0-modded.jar");
            assertTrue(cachedJar.isFile());
            // The merge pass rewrites every entry of the main jar. It must preserve the
            // NO_COMPRESSION level writeFastMainJar uses, otherwise a cache hit pays
            // inflater cost on every class load for the whole base game jar. At level 0
            // deflate only adds a small block wrapper; real deflate would collapse this
            // buffer of identical bytes by orders of magnitude.
            long compressedSize = jarEntryCompressedSize(
                    cachedJar,
                    "com/megacrit/cardcrawl/helpers/CardLibrary.class"
            );
            assertTrue(
                    "merged entry was recompressed: compressedSize=" + compressedSize,
                    compressedSize >= patchedCardLibrary.length
            );
        } finally {
            clearCacheProperties();
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            deleteRecursively(root);
        }
    }

    @Test
    public void packageJarFastPath_foldsCompiledClassOverridesIntoMainJar() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-fold-overrides-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            File baseJar = new File(root, "base.jar");
            File modJar = new File(root, "Example Mod.jar");
            writeJar(baseJar, "base/Base.class", "base");
            writeJar(modJar, "example/ExampleMod.class", "mod");
            Loader.STS_JAR = baseJar.getAbsolutePath();
            Loader.MODINFOS = new ModInfo[] {
                    new ModInfo("example", modJar.toURI().toURL())
            };

            // The OUTJAR snapshot carries the pre-merge bytes for a patched base-game
            // class; the compiled override must win over it, and a class no source jar
            // contains must still be appended.
            PackageJar.Entries entries = new PackageJar.Entries();
            entries.add(new PackageJar.Entry("base/Base.class", PackageJar.Type.BASEGAME));
            entries.add(new PackageJar.Entry(
                    "com/megacrit/cardcrawl/helpers/CardLibrary.class",
                    "snapshot-version".getBytes("UTF-8"),
                    null
            ));
            entries.add(new PackageJar.Entry("example/ExampleMod.class", "example"));

            Map<String, byte[]> overrides = new LinkedHashMap<String, byte[]>();
            overrides.put(
                    "com/megacrit/cardcrawl/helpers/CardLibrary.class",
                    "override-version".getBytes("UTF-8")
            );
            overrides.put("com/megacrit/brand/New.class", "brand-new".getBytes("UTF-8"));
            MtsPatchCacheStore.pendingCompiledClassOverrides = overrides;
            assertFalse(MtsPatchCacheStore.lastPackageJarFastPathTookOver);

            JarOutputStream openOutput = new JarOutputStream(
                    new FileOutputStream(new File(root, "desktop-1.0-modded.jar"))
            );

            assertTrue(MtsPatchCacheStore.packageJarFastPath(
                    new MTSClassPool(),
                    entries,
                    openOutput,
                    new File(root, "desktop-1.0-modded.jar").getAbsolutePath()
            ));

            assertTrue(MtsPatchCacheStore.lastPackageJarFastPathTookOver);
            File cachedJar = new File(root, "desktop-1.0-modded.jar");
            assertArrayEquals(
                    "compiled override must replace the snapshot's OUTJAR bytes",
                    "override-version".getBytes("UTF-8"),
                    readJarEntry(cachedJar, "com/megacrit/cardcrawl/helpers/CardLibrary.class")
            );
            assertArrayEquals(
                    "override with no source entry must be appended",
                    "brand-new".getBytes("UTF-8"),
                    readJarEntry(cachedJar, "com/megacrit/brand/New.class")
            );
            assertArrayEquals(
                    "entries without an override keep their source bytes",
                    "base".getBytes("UTF-8"),
                    readJarEntry(cachedJar, "base/Base.class")
            );

            // Overrides belong to the main jar only: mod package jars are untouched.
            File packageJarFile = new File(root, "package/Example Mod-modded.jar");
            assertTrue(packageJarFile.isFile());
            assertArrayEquals(
                    "mod".getBytes("UTF-8"),
                    readJarEntry(packageJarFile, "example/ExampleMod.class")
            );
            assertFalse(hasJarEntry(packageJarFile, "com/megacrit/brand/New.class"));
        } finally {
            MtsPatchCacheStore.pendingCompiledClassOverrides = null;
            clearCacheProperties();
            resetStubTracking();
            deleteRecursively(root);
        }
    }

    @Test
    public void store_skipsMergeRewriteWhenFastPathTookOver() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-store-skip-merge-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            ByteArrayMapClassPath compiledClasses = new ByteArrayMapClassPath();
            byte[] patchedCardLibrary = "patched-card-library".getBytes("UTF-8");
            compiledClasses.addClass(
                    "com.megacrit.cardcrawl.helpers.CardLibrary",
                    null,
                    patchedCardLibrary
            );

            // The stub reports the takeover from inside packageJar, matching the real
            // patched MTS call order: store() clears the flag before invoking, the fast
            // writer sets it during the invocation, and store() reads it afterwards.
            // The stub's jar omits the override entry, so a skipped merge leaves it
            // absent while a wrongly re-run merge would add it.
            PackageJar.onPackageJarStart = new Runnable() {
                @Override
                public void run() {
                    MtsPatchCacheStore.lastPackageJarFastPathTookOver = true;
                }
            };

            MtsPatchCacheStore.store(new MTSClassPool(), compiledClasses);

            File cachedJar = new File(root, "desktop-1.0-modded.jar");
            assertTrue(cachedJar.isFile());
            assertTrue(new File(root, ".mts_patch_cache").isFile());
            assertNull(
                    "merge rewrite must not run again after a fast-path takeover",
                    readJarEntry(cachedJar, "com/megacrit/cardcrawl/helpers/CardLibrary.class")
            );
        } finally {
            PackageJar.onPackageJarStart = null;
            MtsPatchCacheStore.pendingCompiledClassOverrides = null;
            MtsPatchCacheStore.lastPackageJarFastPathTookOver = false;
            clearCacheProperties();
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            deleteRecursively(root);
        }
    }

    @Test
    public void packageJarFastPath_writesEveryModPackageJarInParallel() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-parallel-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            File baseJar = new File(root, "base.jar");
            writeJar(baseJar, "base/Base.class", "base");
            Loader.STS_JAR = baseJar.getAbsolutePath();

            // Enough mods to actually spread across the pool, each with its own
            // class so a cross-thread mixup shows up as wrong bytes rather than
            // as a missing file.
            int modCount = 12;
            File[] modJars = new File[modCount];
            ModInfo[] modInfos = new ModInfo[modCount];
            PackageJar.Entries entries = new PackageJar.Entries();
            entries.add(new PackageJar.Entry("base/Base.class", PackageJar.Type.BASEGAME));
            for (int index = 0; index < modCount; index++) {
                String modId = "mod" + index;
                modJars[index] = new File(root, "Mod" + index + ".jar");
                writeJar(modJars[index], modId + "/Entry.class", "body-" + index);
                modInfos[index] = new ModInfo(modId, modJars[index].toURI().toURL());
                entries.add(new PackageJar.Entry(modId + "/Entry.class", modId));
                entries.add(new PackageJar.Entry(
                        modId + "/Generated.class",
                        ("generated-" + index).getBytes("UTF-8"),
                        modJars[index].toURI().toURL()
                ));
            }
            Loader.MODINFOS = modInfos;

            JarOutputStream openOutput = new JarOutputStream(
                    new FileOutputStream(new File(root, "desktop-1.0-modded.jar"))
            );

            assertTrue(MtsPatchCacheStore.packageJarFastPath(
                    new MTSClassPool(),
                    entries,
                    openOutput,
                    new File(root, "desktop-1.0-modded.jar").getAbsolutePath()
            ));

            // Guards against this test silently degrading into a serial run and
            // therefore no longer covering the concurrent path at all.
            assertTrue(
                    "Expected the parallel path to run with more than one worker",
                    MtsPatchCacheStore.lastPackageJarThreadCount > 1
            );
            // The main jar must share the pool with the package jars rather than being
            // written serially ahead of them: it carries the whole base game jar, so
            // overlapping it is where most of the wall-clock saving comes from.
            assertNotNull("Main jar task never ran", MtsPatchCacheStore.lastMainJarThreadName);
            assertTrue(
                    "Main jar must run on a pool worker, not the calling thread, was: "
                            + MtsPatchCacheStore.lastMainJarThreadName,
                    MtsPatchCacheStore.lastMainJarThreadName.startsWith("amethyst-cache-jar-")
            );
            assertTrue(
                    "Expected cache jar work to span multiple threads, saw: "
                            + MtsPatchCacheStore.lastCacheJarThreadNames,
                    MtsPatchCacheStore.lastCacheJarThreadNames.size() > 1
            );

            for (int index = 0; index < modCount; index++) {
                String modId = "mod" + index;
                File packageJar = new File(root, "package/Mod" + index + "-modded.jar");
                assertTrue("Missing package jar for " + modId, packageJar.isFile());                assertArrayEquals(
                        "Mod body landed in the wrong package jar",
                        ("body-" + index).getBytes("UTF-8"),
                        readJarEntry(packageJar, modId + "/Entry.class")
                );
                assertArrayEquals(
                        "OUTJAR entry landed in the wrong package jar",
                        ("generated-" + index).getBytes("UTF-8"),
                        readJarEntry(packageJar, modId + "/Generated.class")
                );
                // Each package jar must carry only its own mod's classes.
                for (int other = 0; other < modCount; other++) {
                    if (other != index) {
                        assertNull(
                                "Package jar leaked another mod's class",
                                readJarEntry(packageJar, "mod" + other + "/Entry.class")
                        );
                    }
                }
            }
        } finally {
            clearCacheProperties();
            resetStubTracking();
            deleteRecursively(root);
        }
    }

    @Test
    public void packageJarFastPath_writesDuplicateTargetNamesSerially() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-dupe-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            File baseJar = new File(root, "base.jar");
            writeJar(baseJar, "base/Base.class", "base");
            Loader.STS_JAR = baseJar.getAbsolutePath();

            // Two mods whose source jars share a file name collapse onto one target
            // path. Writing those concurrently would interleave into the same file,
            // so they must fall back to a serial write and stay readable.
            File firstDir = new File(root, "a");
            File secondDir = new File(root, "b");
            assertTrue(firstDir.mkdirs());
            assertTrue(secondDir.mkdirs());
            File firstJar = new File(firstDir, "Same.jar");
            File secondJar = new File(secondDir, "Same.jar");
            writeJar(firstJar, "first/Entry.class", "first");
            writeJar(secondJar, "second/Entry.class", "second");

            Loader.MODINFOS = new ModInfo[] {
                    new ModInfo("first", firstJar.toURI().toURL()),
                    new ModInfo("second", secondJar.toURI().toURL())
            };

            PackageJar.Entries entries = new PackageJar.Entries();
            entries.add(new PackageJar.Entry("base/Base.class", PackageJar.Type.BASEGAME));
            entries.add(new PackageJar.Entry("first/Entry.class", "first"));
            entries.add(new PackageJar.Entry("second/Entry.class", "second"));

            JarOutputStream openOutput = new JarOutputStream(
                    new FileOutputStream(new File(root, "desktop-1.0-modded.jar"))
            );

            assertTrue(MtsPatchCacheStore.packageJarFastPath(
                    new MTSClassPool(),
                    entries,
                    openOutput,
                    new File(root, "desktop-1.0-modded.jar").getAbsolutePath()
            ));

            // The jar must be intact and readable rather than a corrupted interleave.
            File packageJar = new File(root, "package/Same-modded.jar");
            assertTrue(packageJar.isFile());
            assertTrue(hasJarEntry(packageJar, "second/Entry.class"));
            assertArrayEquals(
                    "second".getBytes("UTF-8"),
                    readJarEntry(packageJar, "second/Entry.class")
            );
        } finally {
            clearCacheProperties();
            resetStubTracking();
            deleteRecursively(root);
        }
    }

    @Test
    public void packageJarFastPath_honoursThreadCountOverride() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-threads-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            System.setProperty(PROP_PACKAGE_JAR_THREADS, "1");
            File baseJar = new File(root, "base.jar");
            File modJar = new File(root, "Solo.jar");
            writeJar(baseJar, "base/Base.class", "base");
            writeJar(modJar, "solo/Entry.class", "solo");
            Loader.STS_JAR = baseJar.getAbsolutePath();
            Loader.MODINFOS = new ModInfo[] {
                    new ModInfo("solo", modJar.toURI().toURL())
            };

            PackageJar.Entries entries = new PackageJar.Entries();
            entries.add(new PackageJar.Entry("base/Base.class", PackageJar.Type.BASEGAME));
            entries.add(new PackageJar.Entry("solo/Entry.class", "solo"));

            JarOutputStream openOutput = new JarOutputStream(
                    new FileOutputStream(new File(root, "desktop-1.0-modded.jar"))
            );

            assertTrue(MtsPatchCacheStore.packageJarFastPath(
                    new MTSClassPool(),
                    entries,
                    openOutput,
                    new File(root, "desktop-1.0-modded.jar").getAbsolutePath()
            ));

            File packageJar = new File(root, "package/Solo-modded.jar");
            assertTrue(packageJar.isFile());
            assertArrayEquals("solo".getBytes("UTF-8"), readJarEntry(packageJar, "solo/Entry.class"));
            assertEquals(
                    "Override must force a single worker",
                    1,
                    MtsPatchCacheStore.lastPackageJarThreadCount
            );
            // With one worker the tasks run inline on the calling thread, so no pool
            // thread should ever appear.
            assertEquals(
                    "Serial path must not spawn pool threads",
                    1,
                    MtsPatchCacheStore.lastCacheJarThreadNames.size()
            );
            assertFalse(
                    "Serial path must run inline, not on a pool worker",
                    MtsPatchCacheStore.lastMainJarThreadName.startsWith("amethyst-cache-jar-")
            );
        } finally {
            System.clearProperty(PROP_PACKAGE_JAR_THREADS);
            clearCacheProperties();
            resetStubTracking();
            deleteRecursively(root);
        }
    }

    @Test
    public void packageJarFastPath_propagatesFailureFromWorker() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-worker-fail-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            File baseJar = new File(root, "base.jar");
            writeJar(baseJar, "base/Base.class", "base");
            Loader.STS_JAR = baseJar.getAbsolutePath();

            File goodJar = new File(root, "Good.jar");
            writeJar(goodJar, "good/Entry.class", "good");
            // Points at a jar that was never created, so that worker throws.
            File missingJar = new File(root, "Missing.jar");

            Loader.MODINFOS = new ModInfo[] {
                    new ModInfo("good", goodJar.toURI().toURL()),
                    new ModInfo("missing", missingJar.toURI().toURL())
            };

            PackageJar.Entries entries = new PackageJar.Entries();
            entries.add(new PackageJar.Entry("base/Base.class", PackageJar.Type.BASEGAME));
            entries.add(new PackageJar.Entry("good/Entry.class", "good"));
            entries.add(new PackageJar.Entry("missing/Entry.class", "missing"));

            JarOutputStream openOutput = new JarOutputStream(
                    new FileOutputStream(new File(root, "desktop-1.0-modded.jar"))
            );

            // A worker failure must not be swallowed: the fast path has already taken
            // over the MTS output stream, so it has to raise rather than report success
            // over a partially written package dir.
            try {
                MtsPatchCacheStore.packageJarFastPath(
                        new MTSClassPool(),
                        entries,
                        openOutput,
                        new File(root, "desktop-1.0-modded.jar").getAbsolutePath()
                );
                fail("Expected worker failure to propagate");
            } catch (IllegalStateException expected) {
                assertNotNull(expected.getCause());
            }
        } finally {
            clearCacheProperties();
            resetStubTracking();
            deleteRecursively(root);
        }
    }

    @Test
    public void store_skipsBuildAndKeepsPreviousCacheWhenFreeSpaceIsInsufficient() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-no-space-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            // A previous, still-valid cache. The precheck must run before store's cleanup
            // so a build that cannot succeed does not destroy what already works.
            File previousJar = new File(root, "desktop-1.0-modded.jar");
            File previousMarker = new File(root, ".mts_patch_cache");
            writeJar(previousJar, "old/Old.class", "old");
            writeTextFile(previousMarker, "expected\n");
            // Larger than any real filesystem can offer.
            System.setProperty(PROP_MIN_FREE_BYTES, Long.toString(Long.MAX_VALUE));

            MtsPatchCacheStore.store(new MTSClassPool());

            assertTrue(previousJar.isFile());
            assertTrue(previousMarker.isFile());
            assertArrayEquals("old".getBytes("UTF-8"), readJarEntry(previousJar, "old/Old.class"));
            // observedUserDir stays null only if packageJar was never called at all.
            assertNull(PackageJar.observedUserDir);
        } finally {
            System.clearProperty(PROP_MIN_FREE_BYTES);
            clearCacheProperties();
            resetStubTracking();
            deleteRecursively(root);
        }
    }

    @Test
    public void store_buildsCacheWhenFreeSpaceRequirementIsSatisfied() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-has-space-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            // Pairs with the previous test: identical setup, trivial requirement. Proves the
            // skip above comes from the space check and not from unrelated setup.
            System.setProperty(PROP_MIN_FREE_BYTES, "1");

            MtsPatchCacheStore.store(new MTSClassPool());

            assertTrue(new File(root, "desktop-1.0-modded.jar").length() >= 1024L * 1024L);
            assertTrue(new File(root, ".mts_patch_cache").isFile());
        } finally {
            System.clearProperty(PROP_MIN_FREE_BYTES);
            clearCacheProperties();
            resetStubTracking();
            deleteRecursively(root);
        }
    }

    private static void writeTextFile(File file, String content) throws Exception {
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write(content.getBytes("UTF-8"));
        } finally {
            output.close();
        }
    }

    private static void setCacheProperties(File root) {
        System.setProperty(PROP_ENABLED, "true");
        System.setProperty(PROP_JAR, new File(root, "desktop-1.0-modded.jar").getAbsolutePath());
        System.setProperty(PROP_MARKER, new File(root, ".mts_patch_cache").getAbsolutePath());
        System.setProperty(PROP_PACKAGE_DIR, new File(root, "package").getAbsolutePath());
        System.setProperty(PROP_EXPECTED, "expected");
    }

    private static void resetStubTracking() {
        Loader.PACKAGE = false;
        Loader.OUT_JAR = false;
        Loader.STS_JAR = "";
        Loader.KOTLIN_JAR = "/missing-kotlin.jar";
        Loader.COREPATCHES_JAR = "/missing-corepatches.jar";
        Loader.MODINFOS = new ModInfo[0];
        MTSClassPool.resetTracking();
        PackageJar.resetTracking();
        MtsPatchCacheStore.pendingCompiledClassOverrides = null;
        MtsPatchCacheStore.lastPackageJarFastPathTookOver = false;
    }

    private static void clearCacheProperties() {
        System.clearProperty(PROP_ENABLED);
        System.clearProperty(PROP_JAR);
        System.clearProperty(PROP_MARKER);
        System.clearProperty(PROP_PACKAGE_DIR);
        System.clearProperty(PROP_EXPECTED);
    }

    private static byte[] readJarEntry(File jar, String name) throws Exception {
        JarFile jarFile = new JarFile(jar);
        try {
            java.util.jar.JarEntry entry = jarFile.getJarEntry(name);
            if (entry == null) {
                return null;
            }
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            java.io.InputStream input = jarFile.getInputStream(entry);
            try {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            } finally {
                input.close();
            }
            return output.toByteArray();
        } finally {
            jarFile.close();
        }
    }

    private static long jarEntryCompressedSize(File jar, String name) throws Exception {
        JarFile jarFile = new JarFile(jar);
        try {
            JarEntry entry = jarFile.getJarEntry(name);
            assertNotNull("missing jar entry: " + name, entry);
            return entry.getCompressedSize();
        } finally {
            jarFile.close();
        }
    }

    private static boolean hasJarEntry(File jar, String name) throws Exception {
        JarFile jarFile = new JarFile(jar);
        try {
            return jarFile.getJarEntry(name) != null;
        } finally {
            jarFile.close();
        }
    }

    private static void writeJar(File jar, String entryName, String content) throws Exception {
        JarOutputStream output = new JarOutputStream(new FileOutputStream(jar, false));
        try {
            output.putNextEntry(new JarEntry(entryName));
            output.write(content.getBytes("UTF-8"));
            output.closeEntry();
        } finally {
            output.close();
        }
    }

    private static void writeJarEntries(File jar, String... entryNames) throws Exception {
        JarOutputStream output = new JarOutputStream(new FileOutputStream(jar, false));
        try {
            for (String entryName : entryNames) {
                output.putNextEntry(new JarEntry(entryName));
                if (!entryName.endsWith("/")) {
                    output.write(entryName.getBytes("UTF-8"));
                }
                output.closeEntry();
            }
        } finally {
            output.close();
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
